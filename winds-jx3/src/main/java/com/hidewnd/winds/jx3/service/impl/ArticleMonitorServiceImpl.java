package com.hidewnd.winds.jx3.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hidewnd.winds.jx3.client.OfficialClient;
import com.hidewnd.winds.jx3.event.Jx3Event;
import com.hidewnd.winds.jx3.event.Jx3EventFactory;
import com.hidewnd.winds.jx3.model.Article;
import com.hidewnd.winds.jx3.model.ArticleNotification;
import com.hidewnd.winds.jx3.repository.Jx3RecordRepository;
import com.hidewnd.winds.jx3.service.ArticleMonitorService;
import com.hidewnd.winds.jx3.support.Jx3Time;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/** 新闻（含活动）和官方公告分别拥有首轮基线，任一来源异常不影响另一个来源。 */
@Slf4j
public class ArticleMonitorServiceImpl implements ArticleMonitorService {
    private final boolean maintenance;
    private final OfficialClient client;
    private final Jx3RecordRepository store;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Executor executor;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, Long> acceptedTickets = new HashMap<>();
    private final Map<String, Jx3Event> pendingEvents = new HashMap<>();
    private final Map<ArticleNotification, CompletableFuture<Void>> articleTasks = new ConcurrentHashMap<>();
    private final Map<String, ArticleNotification> pendingNotifications = new ConcurrentHashMap<>();
    private volatile boolean stopped;
    private Instant lastSuccess;

    public ArticleMonitorServiceImpl(
            boolean maintenance,
            OfficialClient client,
            Jx3RecordRepository store,
            ApplicationEventPublisher publisher,
            ObjectMapper mapper,
            Clock clock,
            Executor executor) {
        this.maintenance = maintenance;
        this.client = client;
        this.store = store;
        this.publisher = publisher;
        this.mapper = mapper;
        this.clock = clock;
        this.executor = executor;
    }

    @Override
    public void poll() {
        if (stopped) {
            return;
        }
        // 单次通知核验失败时，下一轮重新提交该身份，不必等文章出现在官网列表中。
        pendingNotifications.values().forEach(this::verifyArticle);
        Instant now = clock.instant();
        long ticket = sequence.incrementAndGet();
        Instant since = null;
        if (maintenance) {
            since = now.minus(Duration.ofDays(7));
        } else if (lastSuccess != null) {
            since = lastSuccess.minusSeconds(120);
        }
        boolean publish = lastSuccess != null;
        client.fetchArticles(maintenance, since, article -> acceptArticle(article, publish, ticket, "official"));
        lastSuccess = now;
    }

    @Override
    public CompletableFuture<Void> verifyArticle(ArticleNotification notification) {
        if (stopped || notification.maintenance() != maintenance) {
            return CompletableFuture.failedFuture(new IllegalStateException("文章监听已停止或通知栏目不匹配"));
        }
        pendingNotifications.put(notification.articleId(), notification);
        var result = new CompletableFuture<Void>();
        var existing = articleTasks.putIfAbsent(notification, result);
        if (existing != null) {
            return existing;
        }
        try {
            executor.execute(() -> {
                try {
                    if (stopped) {
                        throw new IllegalStateException("文章监听已停止");
                    }
                    long ticket = sequence.incrementAndGet();
                    Article article = client.fetchArticle(notification);
                    // 第三方实时通知不受常规首次基线限制，两条来源使用同一存储身份和比较规则。
                    acceptArticle(article, true, ticket, "jx3api");
                    pendingNotifications.remove(notification.articleId(), notification);
                    result.complete(null);
                } catch (RuntimeException exception) {
                    log.warn("第三方文章核验失败，文章={}，异常类型={}；保留待重试通知",
                            notification.articleId(), exception.getClass().getSimpleName());
                    result.completeExceptionally(exception);
                } finally {
                    articleTasks.remove(notification, result);
                }
            });
        } catch (RuntimeException exception) {
            articleTasks.remove(notification, result);
            result.completeExceptionally(exception);
        }
        return result;
    }

    /** 网络请求在锁外进行；保存和事件发布串行，已采信的新请求结果不能被迟到旧请求覆盖。 */
    private synchronized void acceptArticle(Article article, boolean publish, long ticket, String source) {
        if (stopped) {
            throw new IllegalStateException("文章监听已停止");
        }
        if (ticket < acceptedTickets.getOrDefault(article.articleId(), 0L)) {
            return;
        }
        Jx3Event pending = pendingEvents.get(article.articleId());
        if (pending != null) {
            // 保存已成功但发布器异常时，重试同一事件，不能被相同正文的去重分支吞掉。
            publisher.publishEvent(pending);
            pendingEvents.remove(article.articleId());
        }
        String key = (maintenance ? "maintenance:" : "news:") + article.articleId();
        ObjectNode state = mapper.valueToTree(article);
        ObjectNode previous = store.load(key);
        if (state.equals(previous)) {
            acceptedTickets.put(article.articleId(), ticket);
            return;
        }
        Instant observed = clock.instant();
        // 首轮列表若与实时通知重叠，也必须发布该通知对应的文章，不能被静默基线抢先吞掉。
        Jx3Event event = (publish || pendingNotifications.containsKey(article.articleId())) ? Jx3EventFactory.createArticleEvent(
                UUID.randomUUID().toString(), observed, article, previous == null) : null;
        boolean inserted = store.save(key, state, event);
        acceptedTickets.put(article.articleId(), ticket);
        if (inserted) {
            log.info("剑三文章已保存，来源={}，文章={}，发布时间={}，发现时间={}，发布时间差={}s，事件={}",
                    source, key, article.publishedAt(), Jx3Time.format(observed),
                    Duration.between(Jx3Time.parse(article.publishedAt()), observed).getSeconds(),
                    event == null ? "baseline" : event.eventId());
            if (event != null) {
                pendingEvents.put(article.articleId(), event);
                publisher.publishEvent(event);
                pendingEvents.remove(article.articleId());
            }
        }
    }

    @PreDestroy
    public synchronized void close() {
        stopped = true;
        articleTasks.values().forEach(task -> task.cancel(true));
        articleTasks.clear();
        pendingNotifications.clear();
        pendingEvents.clear();
    }
}
