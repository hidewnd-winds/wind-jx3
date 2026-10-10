package com.hidewnd.winds.scout.service.impl;

import com.hidewnd.winds.scout.config.ScoutMongoTimeConverter;
import com.hidewnd.winds.scout.dto.WeiboAlertResponse;
import com.hidewnd.winds.scout.event.WeiboAlertEvent;
import com.hidewnd.winds.scout.exception.ScoutApiException;
import com.hidewnd.winds.scout.model.WeiboAccount;
import com.hidewnd.winds.scout.repository.WeiboAccountRepository;
import com.hidewnd.winds.scout.repository.WeiboAlertRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.bson.Document;
import java.util.UUID;

/** 账号池健康及告警投递的业务所有者；外部发送失败不回滚抓取状态。 */
@Service
@Slf4j
public class WeiboAlertService {
    private final WeiboAccountRepository accounts;
    private final WeiboAlertRepository alerts;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public WeiboAlertService(WeiboAccountRepository accounts, WeiboAlertRepository alerts,
                             ApplicationEventPublisher publisher, Clock clock) {
        this.accounts = accounts;
        this.alerts = alerts;
        this.publisher = publisher;
        this.clock = clock;
    }

    public static String time(Instant instant) {
        return instant == null ? null : ScoutMongoTimeConverter.FORMATTER.format(instant);
    }

    public static boolean usable(WeiboAccount account, Instant now) {
        return StringUtils.hasText(account.getCookie()) && StringUtils.hasText(account.getXsrfToken())
                && (account.getStatus() == null || "active".equals(account.getStatus())
                || account.getRecoverAt() != null && !account.getRecoverAt().isAfter(now));
    }

    public void checkPool() {
        // 先读状态版本再读账号快照，期间成功请求会更新版本，阻止旧快照开启故障。
        var state = alerts.poolState();
        if (state.getString("incidentId") != null) return;
        Instant now = clock.instant();
        List<WeiboAccount> rows = accounts.findAll();
        if (rows.stream().anyMatch(account -> usable(account, now)
                && (account.getStatus() == null || "active".equals(account.getStatus()))
                && account.getRecoveryAttemptId() == null)) return;
        int waiting = (int) rows.stream().filter(account -> (account.getRecoverAt() != null
                || account.getRecoveryAttemptId() != null) && StringUtils.hasText(account.getCookie()) && StringUtils.hasText(account.getXsrfToken())).count();
        Instant earliest = rows.stream().filter(account -> StringUtils.hasText(account.getCookie())
                        && StringUtils.hasText(account.getXsrfToken())).map(WeiboAccount::getRecoverAt)
                .filter(value -> value != null).min(Instant::compareTo).orElse(null);
        String id = UUID.randomUUID().toString();
        String reason = "initial".equals(state.getString("version")) ? "first_detection" : "no_usable_accounts";
        alerts.openPool(state.getString("version"), new WeiboAlertEvent(id, "weibo.pool.unavailable", id,
                time(now), "warning", "微博账号池已无可用账号，微博抓取暂时中断", null, "fail", null,
                0, rows.size(), waiting, rows.size() - waiting, time(earliest), reason, ((Number) state.getOrDefault("successGeneration", 0L)).longValue()), rows);
    }

    public void preserveRecovery(WeiboAccount account) { alerts.preserveRecovery(account); }

    public void initializeAccount(WeiboAccount account) {
        var history = alerts.recoveryHistory(account.getId());
        if (history != null) {
            account.setSuccessGeneration(((Number) history.getOrDefault("generation", 0L)).longValue());
            String succeededAt = history.getString("succeededAt");
            if (succeededAt != null) account.setLastSuccessAt(java.time.LocalDateTime.parse(succeededAt,
                    ScoutMongoTimeConverter.FORMATTER).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant());
        }
    }

    public void recordSuccess(WeiboAccount account) {
        try {
            // 根据故障开启时的成功代数判断先后，不能提前读故障 ID 再写账号，二者间会有竞态。
            for (int attempt = 0; attempt < 3; attempt++) {
                var state = alerts.poolState();
                long observed = state.getList("accountSuccesses", Document.class, List.of()).stream()
                        .filter(item -> account.getId().equals(item.getString("accountId")))
                        .mapToLong(item -> ((Number) item.getOrDefault("generation", 0L)).longValue()).findFirst().orElse(0L);
                if (state.getString("incidentId") != null && observed >= account.getSuccessGeneration()) {
                    alerts.completeSuccess(account);
                    return;
                }
                if (alerts.resolvePool(state.getString("version"), time(account.getLastSuccessAt()))) {
                    alerts.completeSuccess(account);
                    return;
                }
            }
            // 并发持续更新时保留成功标记，下一轮继续，不能把未完成的关闭当作成功。
            log.warn("微博账号池恢复确认发生并发冲突，accountId={}", account.getId());
        } catch (RuntimeException exception) {
            log.error("微博账号池恢复确认失败，保留成功标记，accountId={}，异常类型={}",
                    account.getId(), exception.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 10_000L)
    public void maintain() {
        flush();
        try { checkPool(); }
        catch (RuntimeException exception) { log.error("微博账号池检查失败，异常类型={}", exception.getClass().getSimpleName()); }
        flush();
    }

    public void flush() {
        try {
            for (WeiboAccount account : alerts.pendingSuccesses()) recordSuccess(account);
            for (WeiboAccount account : alerts.pendingAccounts()) {
                for (WeiboAlertEvent event : account.getPendingAlerts()) transfer(account.getId(), event);
            }
            for (WeiboAlertEvent event : alerts.pendingPool()) transfer(null, event);
        } catch (RuntimeException exception) {
            log.error("微博告警扫描失败，待发布状态保留，异常类型={}", exception.getClass().getSimpleName());
        }
    }

    private void transfer(String accountId, WeiboAlertEvent event) {
        try {
            alerts.store(event);
            publisher.publishEvent(event);
            alerts.removePending(accountId, event.eventId());
        } catch (RuntimeException exception) {
            log.error("微博告警转存失败，eventId={}，异常类型={}", event.eventId(), exception.getClass().getSimpleName());
        }
    }

    public List<WeiboAlertResponse> pending(String token, int limit) {
        if (limit < 1 || limit > 100) throw new ScoutApiException(HttpStatus.BAD_REQUEST, "limit须为1至100");
        return alerts.pending(tokenKey(token), limit).stream().map(this::response).toList();
    }

    public WeiboAlertResponse get(String id) {
        WeiboAlertEvent event = alerts.find(id);
        if (event == null) throw new ScoutApiException(HttpStatus.NOT_FOUND, "告警不存在");
        return response(event);
    }

    public void acknowledge(String id, String token) {
        if (alerts.find(id) == null) throw new ScoutApiException(HttpStatus.NOT_FOUND, "告警不存在");
        alerts.acknowledge(id, tokenKey(token));
    }

    private WeiboAlertResponse response(WeiboAlertEvent event) {
        String success;
        long generation;
        String state = "unavailable";
        if (event.accountId() == null) {
            var pool = alerts.poolState();
            success = pool.getString("lastSuccessAt");
            generation = ((Number) pool.getOrDefault("successGeneration", 0L)).longValue();
            if (accounts.findAll().stream().anyMatch(account -> usable(account, clock.instant()))) state = "pending_verification";
        } else {
            WeiboAccount account = accounts.findById(event.accountId()).orElse(null);
            if (account == null) {
                String resolved = alerts.recoveredAt(event.accountId(), event.successGeneration());
                return new WeiboAlertResponse(event, resolved == null ? "deleted" : "recovered", resolved);
            }
            success = time(account.getLastSuccessAt());
            generation = account.getSuccessGeneration();
            if (usable(account, clock.instant())) state = "pending_verification";
        }
        // 使用成功代数，避免同一秒内先成功再失败被时间字符串误判为已恢复。
        boolean recovered = generation > event.successGeneration();
        return new WeiboAlertResponse(event, recovered ? "recovered" : state, recovered ? success : null);
    }

    private String tokenKey(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }
}
