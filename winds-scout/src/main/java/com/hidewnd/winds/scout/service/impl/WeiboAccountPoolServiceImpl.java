package com.hidewnd.winds.scout.service.impl;

import com.hidewnd.winds.scout.event.WeiboAlertEvent;
import com.hidewnd.winds.scout.exception.WeiboAccountInvalidException;
import com.hidewnd.winds.scout.model.WeiboAccount;
import com.hidewnd.winds.scout.repository.WeiboAccountRepository;
import com.hidewnd.winds.scout.repository.WeiboAccountStateRepository;
import com.hidewnd.winds.scout.service.WeiboAccountPoolService;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

/** 账号选取、退避与恢复轮次；结果仅能修改选取时的状态版本。 */
@Service
public class WeiboAccountPoolServiceImpl implements WeiboAccountPoolService {
    private final WeiboAccountRepository repository;
    private final WeiboAccountStateRepository states;
    private final Clock clock;
    private final WeiboAlertService alerts;

    public WeiboAccountPoolServiceImpl(WeiboAccountRepository repository, WeiboAccountStateRepository states,
                                      Clock clock, WeiboAlertService alerts) {
        this.repository = repository;
        this.states = states;
        this.clock = clock;
        this.alerts = alerts;
    }

    @Override
    public Optional<WeiboAccount> chooseAccount() {
        Instant now = clock.instant();
        var candidates = new ArrayList<>(repository.findAll().stream()
                .filter(account -> WeiboAlertService.usable(account, now)).toList());
        Collections.shuffle(candidates);
        for (WeiboAccount account : candidates) {
            if (account.getRequestLeaseUntil() != null && account.getRequestLeaseUntil() > now.toEpochMilli()) continue;
            String version = UUID.randomUUID().toString();
            // 同一账号只允许一个完整抓取链在途；过期租约允许崩溃恢复，迟到结果由版本挡住。
            Update update = new Update().inc("requestCount", 1).set("lastUsedAt", now).set("updatedAt", now)
                    .set("stateVersion", version).set("requestLeaseUntil", now.plus(Duration.ofMinutes(10)).toEpochMilli());
            // 只激活实际选中的到期账号。恢复身份保留到首次有效结果，重启也不会遗忘。
            if (!"active".equals(account.getStatus()) && account.getStatus() != null) {
                String attempt = version;
                update.set("status", "active").unset("recoverAt")
                        .set("recoveryAttemptId", attempt).set("stateVersion", attempt);
            }
            WeiboAccount selected = states.update(account, update);
            if (selected != null) return Optional.of(selected);
        }
        alerts.maintain();
        return Optional.empty();
    }

    @Override
    public void recordSuccess(WeiboAccount selected) {
        Instant now = clock.instant();
        WeiboAccount updated = states.update(selected, new Update().set("status", "active")
                .unset("recoverAt").unset("recoveryAttemptId").unset("requestLeaseUntil").set("updatedAt", now)
                .set("lastSuccessAt", now).inc("successGeneration", 1)
                .set("pendingPoolRecovery", "pending").set("stateVersion", UUID.randomUUID().toString()));
        if (updated != null) alerts.recordSuccess(updated);
    }

    @Override
    public void releaseAccount(WeiboAccount selected) {
        // Cookie 持久化异常不消耗失败次数，也不能把恢复验证误记为成功。
        states.update(selected, new Update().unset("requestLeaseUntil").set("stateVersion", UUID.randomUUID().toString()));
    }

    @Override
    public void recordFailure(WeiboAccount selected, Exception exception) {
        Instant now = clock.instant();
        boolean invalid = exception instanceof WeiboAccountInvalidException;
        boolean recovery = selected.getRecoveryAttemptId() != null;
        int count = selected.getFailCount() + 1;
        Instant recoverAt = invalid ? null : switch (count) {
            case 1 -> now.plus(Duration.ofHours(1));
            case 2 -> now.plus(Duration.ofHours(3));
            default -> null;
        };
        Update update = new Update().set("status", "fail").set("failCount", count)
                .set("lastErrorAt", now).set("lastErrorMessage", exception.getClass().getSimpleName())
                .set("recoverAt", recoverAt).set("updatedAt", now).unset("recoveryAttemptId").unset("requestLeaseUntil")
                .set("stateVersion", UUID.randomUUID().toString());
        if (invalid || recovery) {
            String id = UUID.randomUUID().toString();
            String message = recovery ? (invalid ? "微博账号自动恢复失败，登录凭据失效，已停止使用" : "微博账号自动恢复后请求再次失败")
                    : "微博监听账号已失效，已停止使用，请更新Cookie";
            update.push("pendingAlerts", new WeiboAlertEvent(id,
                    recovery ? "weibo.account.recovery_failed" : "weibo.account.invalid",
                    recovery ? selected.getRecoveryAttemptId() : id, WeiboAlertService.time(now), "warning", message,
                    selected.getId(), "fail", WeiboAlertService.time(recoverAt), count, 0, 0, 0, null,
                    invalid ? "credentials_invalid" : exception.getClass().getSimpleName(), selected.getSuccessGeneration()));
        }
        if (states.update(selected, update) != null) alerts.maintain();
    }
}
