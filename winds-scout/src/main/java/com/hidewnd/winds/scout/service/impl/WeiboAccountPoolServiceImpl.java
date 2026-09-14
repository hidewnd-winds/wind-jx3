package com.hidewnd.winds.scout.service.impl;

import com.hidewnd.winds.scout.event.WeiboAccountInvalidEvent;
import com.hidewnd.winds.scout.exception.WeiboAccountInvalidException;
import com.hidewnd.winds.scout.model.WeiboAccount;
import com.hidewnd.winds.scout.repository.WeiboAccountRepository;
import com.hidewnd.winds.scout.service.WeiboAccountPoolService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 微博抓取账号池服务实现，负责账号选择、失败退避和自动恢复。
 */
@Service
public class WeiboAccountPoolServiceImpl implements WeiboAccountPoolService {

    private final WeiboAccountRepository repository;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    public WeiboAccountPoolServiceImpl(
            WeiboAccountRepository repository, Clock clock, ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public Optional<WeiboAccount> chooseAccount() {
        Instant now = clock.instant();
        List<WeiboAccount> usable = repository.findAll().stream()
                .filter(account -> StringUtils.hasText(account.getCookie())
                        && StringUtils.hasText(account.getXsrfToken()))
                .filter(account -> recoverIfDue(account, now))
                .toList();
        if (usable.isEmpty()) {
            return Optional.empty();
        }
        WeiboAccount account = usable.get(ThreadLocalRandom.current().nextInt(usable.size()));
        account.setRequestCount(account.getRequestCount() + 1);
        account.setLastUsedAt(now);
        account.setUpdatedAt(now);
        return Optional.of(repository.save(account));
    }

    @Override
    public void recordSuccess(String accountId) {
        repository.findById(accountId).ifPresent(account -> {
            account.setStatus("active");
            account.setFailCount(0);
            account.setRecoverAt(null);
            account.setUpdatedAt(clock.instant());
            repository.save(account);
        });
    }

    @Override
    public void recordFailure(String accountId, Exception exception) {
        repository.findById(accountId).ifPresent(account -> {
            Instant now = clock.instant();
            boolean invalid = exception instanceof WeiboAccountInvalidException;
            // 永久停用后的重复结果不改写故障时间，补发沿用同一事件供接收端去重。
            if ("fail".equals(account.getStatus()) && account.getRecoverAt() == null) {
                return;
            }
            int failCount = account.getFailCount() + 1;
            account.setStatus("fail");
            account.setFailCount(failCount);
            account.setLastErrorAt(now);
            account.setLastErrorMessage(exception.getClass().getSimpleName());
            account.setRecoverAt(invalid ? null : switch (failCount) {
                case 1 -> now.plus(Duration.ofHours(1));
                case 2 -> now.plus(Duration.ofHours(3));
                default -> null;
            });
            account.setUpdatedAt(now);
            repository.save(account);
            if (account.getRecoverAt() == null) {
                eventPublisher.publishEvent(new WeiboAccountInvalidEvent(accountId, now));
            }
        });
    }

    /**
     * 持久化的永久停用状态即为待提醒来源；恢复后自然退出补发。
     * 不依赖订阅博主或在线连接，覆盖全池失效、服务重启及 WS 漏收。
     * 沿用原失败时间，已成功通知的 Bot 可按账号和时间去重。
     */
    @Scheduled(fixedDelay = 240_000L, initialDelay = 10_000L)
    public void republishUnavailableAccounts() {
        for (WeiboAccount account : repository.findAll()) {
            if ("fail".equals(account.getStatus()) && account.getRecoverAt() == null
                    && account.getLastErrorAt() != null) {
                eventPublisher.publishEvent(new WeiboAccountInvalidEvent(
                        account.getId(), account.getLastErrorAt()));
            }
        }
    }

    private boolean recoverIfDue(WeiboAccount account, Instant now) {
        if (account.getStatus() == null || "active".equals(account.getStatus())) {
            return true;
        }
        if (account.getRecoverAt() == null || account.getRecoverAt().isAfter(now)) {
            return false;
        }
        account.setStatus("active");
        account.setRecoverAt(null);
        account.setUpdatedAt(now);
        repository.save(account);
        return true;
    }
}
