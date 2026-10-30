package com.hidewnd.winds.scout.service.impl;

import com.hidewnd.winds.scout.event.WeiboUpdatedEvent;
import com.hidewnd.winds.scout.exception.WeiboCookieUpdateException;
import com.hidewnd.winds.scout.model.WeiboAccount;
import com.hidewnd.winds.scout.model.WeiboBlogger;
import com.hidewnd.winds.scout.model.WeiboPost;
import com.hidewnd.winds.scout.repository.WeiboBloggerRepository;
import com.hidewnd.winds.scout.repository.WeiboPostRepository;
import com.hidewnd.winds.scout.service.WeiboAccountPoolService;
import com.hidewnd.winds.scout.service.WeiboFetchService;
import com.hidewnd.winds.scout.service.WeiboMonitorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/** 按博主请求开始时间调度；HTTP 抓取与同 UID 的按序入库分别管理。 */
@Slf4j
@Service
public class WeiboMonitorServiceImpl implements WeiboMonitorService {
    private static final DateTimeFormatter POST_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(150);
    private static final Duration MAX_INTERVAL = Duration.ofSeconds(180);

    private final WeiboBloggerRepository bloggerRepository;
    private final WeiboAccountPoolService accountPool;
    private final WeiboFetchService fetchService;
    private final WeiboPostRepository postRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final AsyncTaskExecutor executor;
    private final IntSupplier pauseSeconds;
    private final String leaseOwner = UUID.randomUUID().toString();
    // 调度集合和两路错峰时间仅由 poll 修改；工作线程只更新各 UID 的在途状态与处理队列。
    private final Map<String, PollState> states = new HashMap<>();
    private final Instant[] nextDispatch = {Instant.MIN, Instant.MIN};
    private Instant nextRefresh = Instant.MIN;
    private LocalDateTime activeSince;
    private boolean dispatchEnabled;

    @Autowired
    public WeiboMonitorServiceImpl(WeiboBloggerRepository bloggerRepository, WeiboAccountPoolService accountPool,
            WeiboFetchService fetchService, WeiboPostRepository postRepository,
            ApplicationEventPublisher eventPublisher, Clock clock,
            @Qualifier("asyncTaskExecutor") AsyncTaskExecutor executor) {
        this(bloggerRepository, accountPool, fetchService, postRepository, eventPublisher, clock, executor,
                () -> ThreadLocalRandom.current().nextInt(16));
    }

    WeiboMonitorServiceImpl(WeiboBloggerRepository bloggerRepository, WeiboAccountPoolService accountPool,
            WeiboFetchService fetchService, WeiboPostRepository postRepository,
            ApplicationEventPublisher eventPublisher, Clock clock, AsyncTaskExecutor executor, IntSupplier pauseSeconds) {
        this.bloggerRepository = bloggerRepository;
        this.accountPool = accountPool;
        this.fetchService = fetchService;
        this.postRepository = postRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.executor = executor;
        this.pauseSeconds = pauseSeconds;
    }

    @Scheduled(fixedDelay = 1_000L, initialDelay = 10_000L)
    @Override
    public synchronized void poll() {
        Instant now = clock.instant();
        if (!now.isBefore(nextRefresh)) {
            nextRefresh = now.plusSeconds(30);
            try {
                if (!postRepository.tryAcquirePollLease(leaseOwner, now)) {
                    dispatchEnabled = false;
                    activeSince = null;
                    log.info("微博监听暂停派发：未获得轮询租约");
                    return;
                }
                List<WeiboBlogger> bloggers = bloggerRepository.findSubscribed();
                dispatchEnabled = true;
                if (activeSince == null) activeSince = LocalDateTime.ofInstant(now, clock.getZone());
                states.values().forEach(state -> state.subscribed = false);
                for (WeiboBlogger blogger : bloggers) {
                    if (blogger.getUid() == null || !blogger.getUid().matches("\\d+")) {
                        log.warn("微博监听跳过：UID无效");
                        continue;
                    }
                    PollState state = states.computeIfAbsent(blogger.getUid(), uid -> new PollState(blogger, now));
                    state.blogger = blogger;
                    state.subscribed = true;
                }
                // 取消订阅后保留仍在途的队列，避免短时间重新订阅产生第二条入库链。
                states.values().removeIf(state -> {
                    synchronized (state) {
                        return !state.subscribed && !state.fetching && !state.processing && state.pending.isEmpty();
                    }
                });
            } catch (RuntimeException exception) {
                // 暂停派发，但不能把一次刷新故障当成新的监听起点，否则会静默过滤故障期间的新微博。
                dispatchEnabled = false;
                log.warn("微博监听租约或订阅刷新失败，暂停派发，异常类型={}", exception.getClass().getSimpleName());
                return;
            }
        }
        // 已取得的结果继续按序处理；失去租约只停止产生新的抓取任务。
        states.values().forEach(this::startProcessing);
        if (!dispatchEnabled) return;

        List<PollState> due = states.values().stream()
                .filter(state -> state.subscribed && !state.fetching)
                .filter(state -> state.lastStarted == null || !now.isBefore(state.lastStarted.plus(POLL_INTERVAL)))
                .sorted(Comparator.comparing((PollState state) -> state.lastStarted == null ? Instant.MIN : state.lastStarted)
                        .thenComparing(state -> state.blogger.getUid()))
                .toList();
        int index = 0;
        boolean noAccount = false;
        for (int lane = 0; lane < nextDispatch.length && index < due.size(); lane++) {
            if (now.isBefore(nextDispatch[lane])) continue;
            Optional<WeiboAccount> selected = accountPool.chooseAccount();
            if (selected.isEmpty()) {
                noAccount = true;
                break;
            }
            PollState state = due.get(index++);
            WeiboBlogger blogger = state.blogger;
            LocalDateTime baselineSince = activeSince;
            state.fetching = true;
            nextDispatch[lane] = clock.instant().plusSeconds(pauseSeconds.getAsInt());
            try {
                executor.execute(() -> fetch(state, blogger, selected.get(), baselineSince));
            } catch (RuntimeException exception) {
                state.fetching = false;
                accountPool.releaseAccount(selected.get());
                log.warn("微博抓取提交失败，uid={}，异常类型={}", blogger.getUid(), exception.getClass().getSimpleName());
            }
        }
        for (PollState state : states.values()) {
            if (!state.subscribed) continue;
            Instant last = state.lastStarted == null ? state.discoveredAt : state.lastStarted;
            if (now.isAfter(last.plus(MAX_INTERVAL)) && !now.isBefore(state.nextDelayWarning)) {
                state.nextDelayWarning = now.plusSeconds(30);
                log.warn("微博监听延期，uid={}，delayMs={}，原因={}", state.blogger.getUid(),
                        Duration.between(last.plus(MAX_INTERVAL), now).toMillis(),
                        state.fetching ? "抓取链在途或等待HTTP名额" : noAccount ? "无可用抓取账号" : "等待错峰派发或调度积压");
            }
        }
    }

    private void fetch(PollState state, WeiboBlogger blogger, WeiboAccount account, LocalDateTime baselineSince) {
        try {
            Optional<WeiboPost> latest;
            try {
                latest = fetchService.fetchLatest(blogger.getUid(), blogger.getScreenName(), account, startedAt -> {
                    Instant previous = state.lastStarted;
                    state.lastStarted = startedAt;
                    long intervalMs = previous == null ? 0 : Duration.between(previous, startedAt).toMillis();
                    log.info("微博监听请求开始，uid={}，intervalMs={}", blogger.getUid(), intervalMs);
                    if (intervalMs > MAX_INTERVAL.toMillis()) {
                        log.warn("微博监听请求间隔超限，uid={}，delayMs={}",
                                blogger.getUid(), intervalMs - MAX_INTERVAL.toMillis());
                    }
                });
            } catch (WeiboCookieUpdateException | CancellationException exception) {
                accountPool.releaseAccount(account);
                log.warn("微博抓取中断或Cookie状态更新失败，uid={}，accountId={}，异常类型={}",
                        blogger.getUid(), account.getId(), exception.getClass().getSimpleName());
                return;
            } catch (RuntimeException exception) {
                accountPool.recordFailure(account, exception);
                log.warn("微博请求失败，uid={}，异常类型={}", blogger.getUid(), exception.getClass().getSimpleName());
                return;
            }
            accountPool.recordSuccess(account);
            if (latest.isEmpty()) return;
            synchronized (state) {
                state.pending.addLast(new PendingPost(latest.get(), baselineSince, System.nanoTime()));
                if (state.processing || state.pending.size() > 1) {
                    log.warn("微博处理积压，uid={}，pendingCount={}", blogger.getUid(), state.pending.size());
                }
            }
            startProcessing(state);
        } catch (RuntimeException exception) {
            // 本地状态或入队失败不能算作上游账号失败；下一轮仍可继续抓取。
            log.warn("微博抓取结果处理失败，uid={}，异常类型={}", blogger.getUid(), exception.getClass().getSimpleName());
        } finally {
            state.fetching = false;
        }
    }

    private void startProcessing(PollState state) {
        synchronized (state) {
            if (state.processing || state.pending.isEmpty()) return;
            state.processing = true;
        }
        try {
            executor.execute(() -> processPending(state));
        } catch (RuntimeException exception) {
            synchronized (state) {
                state.processing = false;
            }
            // 提交失败保留队列，由下一次调度重试，不静默丢弃已取得的结果。
            log.warn("微博处理任务提交失败，异常类型={}", exception.getClass().getSimpleName());
        }
    }

    private void processPending(PollState state) {
        while (true) {
            PendingPost pending;
            synchronized (state) {
                if (Thread.currentThread().isInterrupted()) {
                    state.processing = false;
                    return;
                }
                pending = state.pending.pollFirst();
                if (pending == null) {
                    state.processing = false;
                    return;
                }
            }
            long processingAt = System.nanoTime();
            WeiboPost post = pending.post();
            try {
                boolean hasBaseline = postRepository.hasPosts(post.uid());
                boolean historical = false;
                if (hasBaseline) {
                    try {
                        historical = !LocalDateTime.parse(post.publishedAt(), POST_TIME).isAfter(pending.baselineSince());
                    } catch (DateTimeParseException exception) {
                        historical = true;
                    }
                }
                boolean noPush = !hasBaseline || historical;
                boolean inserted = postRepository.saveOrSync(post, noPush);
                if (!inserted) {
                    log.info("微博监听无新增，uid={}，weiboId={}", post.uid(), post.weiboId());
                } else if (noPush) {
                    log.info("微博已保存但不推送，uid={}，weiboId={}，原因={}",
                            post.uid(), post.weiboId(), hasBaseline ? "历史微博" : "首次基线");
                } else {
                    eventPublisher.publishEvent(new WeiboUpdatedEvent(post));
                    log.info("微博更新事件已发布，uid={}，weiboId={}", post.uid(), post.weiboId());
                }
            } catch (RuntimeException exception) {
                log.warn("微博入库或事件发布失败，uid={}，weiboId={}，异常类型={}",
                        post.uid(), post.weiboId(), exception.getClass().getSimpleName());
            } finally {
                log.info("微博处理耗时，uid={}，weiboId={}，processingWaitMs={}，processingMs={}", post.uid(), post.weiboId(),
                        TimeUnit.NANOSECONDS.toMillis(processingAt - pending.queuedAt()),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - processingAt));
            }
        }
    }

    private static final class PollState {
        private WeiboBlogger blogger;
        private final Instant discoveredAt;
        private boolean subscribed;
        private volatile Instant lastStarted;
        private volatile boolean fetching;
        private Instant nextDelayWarning = Instant.MIN;
        // 队列和 processing 在同一个 UID 锁内更新，锁内不执行任何 HTTP、数据库或事件 I/O。
        private final ArrayDeque<PendingPost> pending = new ArrayDeque<>();
        private boolean processing;

        private PollState(WeiboBlogger blogger, Instant discoveredAt) {
            this.blogger = blogger;
            this.discoveredAt = discoveredAt;
        }
    }

    private record PendingPost(WeiboPost post, LocalDateTime baselineSince, long queuedAt) { }
}
