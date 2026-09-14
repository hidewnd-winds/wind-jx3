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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 微博轮询服务实现，负责租约续期、顺序抓取、基线判断和更新事件发布。
 */
@Slf4j
@Service
public class WeiboMonitorServiceImpl implements WeiboMonitorService, SchedulingConfigurer {

    private static final DateTimeFormatter POST_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WeiboBloggerRepository bloggerRepository;
    private final WeiboAccountPoolService accountPool;
    private final WeiboFetchService fetchService;
    private final WeiboPostRepository postRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final String leaseOwner = UUID.randomUUID().toString();
    private LocalDateTime activeSince;

    public WeiboMonitorServiceImpl(
            WeiboBloggerRepository bloggerRepository,
            WeiboAccountPoolService accountPool,
            WeiboFetchService fetchService,
            WeiboPostRepository postRepository,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.bloggerRepository = bloggerRepository;
        this.accountPool = accountPool;
        this.fetchService = fetchService;
        this.postRepository = postRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.addTriggerTask(this::poll, context -> {
            Instant completedAt = context.lastCompletion();
            if (completedAt == null) {
                return context.getClock().instant().plusSeconds(10);
            }
            // 每轮完成后重新生成 3 分钟 ±30 秒的间隔，避免固定周期请求。
            return completedAt.plusSeconds(ThreadLocalRandom.current().nextLong(150, 211));
        });
    }

    @Override
    public void poll() {
        log.info("微博监听轮询开始");
        if (!renewLease()) {
            activeSince = null;
            log.info("微博监听轮询跳过：未获得轮询租约");
            return;
        }
        if (activeSince == null) {
            activeSince = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        }
        List<WeiboBlogger> bloggers = bloggerRepository.findSubscribed();
        log.info("微博监听轮询已获得租约，订阅博主数={}", bloggers.size());
        for (int index = 0; index < bloggers.size(); index++) {
            if (index > 0) {
                pauseBetweenRequests();
                if (!renewLease()) {
                    activeSince = null;
                    return;
                }
            }
            WeiboBlogger blogger = bloggers.get(index);
            try {
                process(blogger);
            } catch (RuntimeException exception) {
                log.warn("微博轮询处理失败，uid={}", blogger.getUid(), exception);
            }
        }
        log.info("微博监听轮询完成，订阅博主数={}", bloggers.size());
    }

    private void process(WeiboBlogger blogger) {
        if (blogger.getUid() == null || !blogger.getUid().matches("\\d+")) {
            log.warn("微博轮询跳过：UID无效");
            return;
        }
        Optional<WeiboAccount> selected = accountPool.chooseAccount();
        if (selected.isEmpty()) {
            log.warn("微博轮询跳过：当前没有可用账号");
            return;
        }
        WeiboAccount account = selected.get();
        Optional<WeiboPost> latest;
        try {
            latest = fetchService.fetchLatest(
                    blogger.getUid(), blogger.getScreenName(), account);
        } catch (WeiboCookieUpdateException exception) {
            log.warn("微博Cookie状态更新失败，uid={}，accountId={}",
                    blogger.getUid(), account.getId(), exception);
            return;
        } catch (Exception exception) {
            accountPool.recordFailure(account.getId(), exception);
            log.warn("微博请求失败，uid={}", blogger.getUid(), exception);
            return;
        }
        accountPool.recordSuccess(account.getId());
        if (latest.isEmpty()) {
            log.info("微博监听未获取到有效内容，uid={}", blogger.getUid());
            return;
        }
        WeiboPost post = latest.get();
        boolean hasBaseline = postRepository.hasPosts(blogger.getUid());
        boolean historical = hasBaseline && isHistorical(post.publishedAt());
        boolean noPush = !hasBaseline || historical;
        boolean inserted = postRepository.saveOrSync(post, noPush);
        if (!inserted) {
            log.info("微博监听无新增，uid={}，weiboId={}", blogger.getUid(), post.weiboId());
        } else if (noPush) {
            log.info("微博已保存但不推送，uid={}，weiboId={}，原因={}",
                    blogger.getUid(), post.weiboId(), hasBaseline ? "历史微博" : "首次基线");
        } else {
            eventPublisher.publishEvent(new WeiboUpdatedEvent(post));
            log.info("微博更新事件已发布，uid={}，weiboId={}", blogger.getUid(), post.weiboId());
        }
    }

    private boolean isHistorical(String publishedAt) {
        try {
            return !LocalDateTime.parse(publishedAt, POST_TIME).isAfter(activeSince);
        } catch (DateTimeParseException exception) {
            return true;
        }
    }

    private boolean renewLease() {
        return postRepository.tryAcquirePollLease(leaseOwner, clock.instant());
    }

    private void pauseBetweenRequests() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5, 21) * 1_000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("微博轮询等待被中断", exception);
        }
    }
}
