package com.hidewnd.winds.jx3.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hidewnd.winds.jx3.client.OfficialClient;
import com.hidewnd.winds.jx3.event.Jx3Event;
import com.hidewnd.winds.jx3.event.Jx3EventFactory;
import com.hidewnd.winds.jx3.repository.Jx3RecordRepository;
import com.hidewnd.winds.jx3.service.ArticleMonitorService;
import com.hidewnd.winds.jx3.support.Jx3Time;

import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** 新闻（含活动）和官方公告分别拥有首轮基线，任一来源异常不影响另一个来源。 */
public class ArticleMonitorServiceImpl implements ArticleMonitorService {
    private final boolean maintenance;
    private final OfficialClient client;
    private final Jx3RecordRepository store;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper mapper;
    private final Clock clock;
    private Instant lastSuccess;

    public ArticleMonitorServiceImpl(
            boolean maintenance,
            OfficialClient client,
            Jx3RecordRepository store,
            ApplicationEventPublisher publisher,
            ObjectMapper mapper,
            Clock clock) {
        this.maintenance = maintenance;
        this.client = client;
        this.store = store;
        this.publisher = publisher;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void poll() {
        Instant now = clock.instant();
        Instant today = Jx3Time.startOfDay(now);
        Instant since = null;
        if (maintenance) {
            since = now.minus(Duration.ofDays(7));
        } else if (lastSuccess != null) {
            since = lastSuccess.minusSeconds(120);
        }
        var articles = client.fetchArticles(maintenance, since);
        for (var article : articles) {
            String key = (maintenance ? "maintenance:" : "news:") + article.articleId();
            ObjectNode state = mapper.valueToTree(article);
            ObjectNode previous = store.load(key);
            if (state.equals(previous)) {
                continue;
            }
            // 历史文章仍同步正文，但置顶、补抓或内容更新都不能触发今日推送。
            Jx3Event event =
                    lastSuccess == null || Jx3Time.parse(article.publishedAt()).isBefore(today)
                            ? null
                            : Jx3EventFactory.createArticleEvent(
                                    UUID.randomUUID().toString(), now, article, previous == null);
            store.save(key, state, event);
        }
        lastSuccess = now;
        // 当日文章即使内容没变或进程重启仍可重试；跨日后不再补发。
        for (Jx3Event event : store.pendingArticleEvents(maintenance, clock.instant())) {
            publisher.publishEvent(event);
        }
    }
}
