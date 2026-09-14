package com.hidewnd.winds.jx3.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hidewnd.winds.jx3.client.OfficialClient;
import com.hidewnd.winds.jx3.event.Jx3Event;
import com.hidewnd.winds.jx3.event.Jx3EventFactory;
import com.hidewnd.winds.jx3.model.PatchManifest;
import com.hidewnd.winds.jx3.model.PatchInfo;
import com.hidewnd.winds.jx3.model.PatchNotification;
import com.hidewnd.winds.jx3.repository.Jx3RecordRepository;
import com.hidewnd.winds.jx3.service.PatchMonitorService;
import com.hidewnd.winds.jx3.support.Jx3Time;

import org.springframework.context.ApplicationEventPublisher;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;

/** 基线只在完整补丁链及大小保存成功后前进；待处理版本保留首次发现时间。 */
@Slf4j
public class PatchMonitorServiceImpl implements PatchMonitorService {
    private final OfficialClient client;
    private final Jx3RecordRepository store;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Executor executor;
    // 补丁版本是单条递增链；两路扫描串行，WS 线程仅提交任务，不等待此锁或网络请求。
    private final Object scanLock = new Object();
    private final Map<String, PatchNotification> notifications = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> notificationTasks = new ConcurrentHashMap<>();
    private volatile boolean stopped;
    private String previous;
    private String pending;
    private Instant observed;
    private boolean baselineOnly;
    private Jx3Event pendingEvent;

    public PatchMonitorServiceImpl(
            OfficialClient client,
            Jx3RecordRepository store,
            ApplicationEventPublisher publisher,
            ObjectMapper mapper,
            Clock clock,
            Executor executor) {
        this.client = client;
        this.store = store;
        this.publisher = publisher;
        this.mapper = mapper;
        this.clock = clock;
        this.executor = executor;
    }

    @Override
    public void poll() {
        synchronized (scanLock) {
            if (stopped) {
                throw new IllegalStateException("更新包监听已停止");
            }
            long started = System.nanoTime();
            try {
                collectPatch();
            } finally {
                log.debug("更新包采集轮次结束，已处理版本={}，待处理版本={}，待核验通知={}，耗时={}ms",
                        previous, pending, notifications.size(), (System.nanoTime() - started) / 1_000_000);
            }
        }
    }

    @Override
    public CompletableFuture<Void> verifyPatch(PatchNotification notification) {
        if (stopped) {
            return CompletableFuture.failedFuture(new IllegalStateException("更新包监听已停止"));
        }
        // 同一目标版本保留首次接收时间，重复报文不重复排队。
        notifications.putIfAbsent(notification.newVersion(), notification);
        var result = new CompletableFuture<Void>();
        var existing = notificationTasks.putIfAbsent(notification.newVersion(), result);
        if (existing != null) {
            return existing;
        }
        try {
            executor.execute(() -> {
                try {
                    poll();
                    if (notifications.containsKey(notification.newVersion())) {
                        throw new IllegalStateException("官方目标版本尚未核验完成");
                    }
                    result.complete(null);
                } catch (RuntimeException exception) {
                    log.warn("第三方更新包核验失败，目标版本={}，异常类型={}；保留通知待后续轮询重试",
                            notification.newVersion(), exception.getClass().getSimpleName());
                    result.completeExceptionally(exception);
                } finally {
                    notificationTasks.remove(notification.newVersion(), result);
                }
            });
        } catch (RuntimeException exception) {
            notificationTasks.remove(notification.newVersion(), result);
            result.completeExceptionally(exception);
        }
        return result;
    }

    /** 两路共用完整持久化版本，未知升级链、大小或文件时间时不推进基线。 */
    private void collectPatch() {
        if (pendingEvent != null) {
            publisher.publishEvent(pendingEvent);
            pendingEvent = null;
        }
        PatchNotification notification = notifications.values().stream()
                .max((left, right) -> PatchManifest.compareVersions(left.newVersion(), right.newVersion()))
                .orElse(null);
        if (previous == null) {
            var saved = store.load("patch");
            if (saved != null && saved.hasNonNull("version") && saved.hasNonNull("packageCount")
                    && saved.hasNonNull("totalBytes") && saved.hasNonNull("updatedAt")) {
                // 重启不抹掉已知旧版本，否则新补丁会被当成首次基线保存而不推送。
                previous = saved.path("version").asText();
                if (pending != null && PatchManifest.compareVersions(previous, pending) >= 0) {
                    pending = null;
                }
                baselineOnly = "manifest".equals(saved.path("packageScope").asText())
                        || "latestVersion".equals(saved.path("packageScope").asText());
                log.info("更新包监听恢复持久化版本={}，仅基线={}", previous, baselineOnly);
            }
        }
        boolean notifiedBaseline = notification != null && baselineOnly
                && notification.newVersion().equals(previous);
        if (notification != null && previous != null
                && PatchManifest.compareVersions(notification.newVersion(), previous) <= 0 && !notifiedBaseline) {
            notifications.entrySet().removeIf(entry -> PatchManifest.compareVersions(entry.getKey(), previous) <= 0);
            log.debug("忽略已处理更新包通知，通知版本={}，已处理版本={}", notification.newVersion(), previous);
            return;
        }
        PatchManifest manifest = client.fetchPatchManifest();
        if (notification != null && PatchManifest.compareVersions(manifest.latestVersion(), notification.newVersion()) < 0) {
            // 提前到达的通知留待重试，不能阻断官网已经可用的较低版本。
            log.debug("官方清单暂未同步第三方目标版本，官方版本={}，通知版本={}",
                    manifest.latestVersion(), notification.newVersion());
            notification = notifications.values().stream()
                    .filter(notice -> PatchManifest.compareVersions(notice.newVersion(), manifest.latestVersion()) <= 0)
                    .max((left, right) -> PatchManifest.compareVersions(left.newVersion(), right.newVersion()))
                    .orElse(null);
        }
        notifiedBaseline = notification != null && baselineOnly && notification.newVersion().equals(previous);
        if (notification != null && previous != null && !notifiedBaseline
                && PatchManifest.compareVersions(notification.newVersion(), previous) <= 0) {
            notifications.entrySet().removeIf(entry -> PatchManifest.compareVersions(entry.getKey(), previous) <= 0);
            notification = null;
        }
        boolean baseline = previous == null && notification == null;
        if (previous != null && PatchManifest.compareVersions(manifest.latestVersion(), previous) < 0) {
            throw new IllegalStateException("官方版本回退，保留已处理基线");
        }
        if (notification == null && pending == null && manifest.latestVersion().equals(previous)) {
            return;
        }
        if (notification != null && (pending == null
                || PatchManifest.compareVersions(notification.newVersion(), pending) > 0
                || previous == null || notifiedBaseline)) {
            pending = notification.newVersion();
            observed = notification.observedAt();
        } else if (pending == null || baseline && !pending.equals(manifest.latestVersion())) {
            pending = manifest.latestVersion();
            observed = clock.instant();
        }
        if (PatchManifest.compareVersions(manifest.latestVersion(), pending) < 0) {
            throw new IllegalStateException("官方清单尚未包含待核验目标版本 " + pending);
        }
        // 首轮只核验直接到达当前版本的包；不得将几千个历史包作为发现新版本的前置条件。
        String from = notification != null && (previous == null || notifiedBaseline)
                ? notification.nowVersion() : previous;
        var chain = baseline ? manifest.patches().stream().filter(patch -> patch.to().equals(pending)).toList()
                : manifest.chain(from, pending);
        if (chain.isEmpty()) {
            throw new IllegalStateException("官方清单缺少目标版本更新包 " + pending);
        }
        long bytes = 0;
        Instant updated = null;
        // 升级链最多四个 HEAD 并行，不再逐个累计网络等待。
        var completed = new ExecutorCompletionService<PatchInfo>(executor);
        Set<Future<PatchInfo>> requests = new HashSet<>();
        int submitted = 0;
        try {
            while (submitted < chain.size() || !requests.isEmpty()) {
                if (stopped) {
                    throw new IllegalStateException("更新包监听已停止");
                }
                while (submitted < chain.size() && requests.size() < 4) {
                    var patch = chain.get(submitted++);
                    requests.add(completed.submit(() -> client.fetchPatchInfo(patch.fileName())));
                }
                var finished = completed.take();
                requests.remove(finished);
                PatchInfo info = finished.get();
                bytes = Math.addExact(bytes, info.size());
                if (updated == null || info.updatedAt().isAfter(updated)) {
                    updated = info.updatedAt();
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("更新包采集已中断", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException("更新包文件核验失败", exception.getCause());
        } finally {
            requests.forEach(request -> request.cancel(true));
        }
        if (stopped) {
            throw new IllegalStateException("更新包监听已停止");
        }
        if (baseline && notifications.values().stream()
                .anyMatch(notice -> PatchManifest.compareVersions(notice.newVersion(), pending) <= 0)) {
            // 采集期间已收到实时通知，交给下一次核验按通知起点计算，不抢先写入静默基线。
            pending = null;
            return;
        }
        Jx3Event event =
                baseline
                        ? null
                        : Jx3EventFactory.createPatchEvent(
                                UUID.randomUUID().toString(),
                                observed,
                                from,
                                pending,
                                chain.size(),
                                bytes);
        var state = mapper.createObjectNode().put("version", pending);
        state.set("packages", mapper.valueToTree(chain));
        state.put("totalBytes", bytes);
        state.put("packageCount", chain.size());
        state.put("updatedAt", Jx3Time.format(updated));
        state.put("updateTimeSource", "packageLastModified");
        state.put("packageScope", baseline ? "latestVersion" : "upgradeChain");
        boolean inserted = store.save("patch", state, event);
        if (stopped) {
            throw new IllegalStateException("更新包监听已停止");
        }
        previous = pending;
        pending = null;
        baselineOnly = baseline;
        log.info("更新包状态处理完成，来源={}，版本={}->{}，包数={}，字节数={}，官方文件更新时间={}，发现时间={}，新增记录={}，事件={}",
                notification == null ? "official" : "jx3api", from, previous, chain.size(), bytes,
                Jx3Time.format(updated), Jx3Time.format(observed), inserted,
                event == null ? "baseline" : inserted ? event.eventId() : "duplicate");
        if (baseline) {
            log.info("更新包首次基线已建立，版本={}；无已知升级起点，暂不推送", previous);
        }
        // 写入结果不确定后重试时，数据库可能已有同一状态；不广播未持久化的新事件 ID。
        if (inserted && event != null) {
            pendingEvent = event;
            publisher.publishEvent(event);
            pendingEvent = null;
        }
        if (!baseline) {
            // 基线入库期间到达的通知仍需发布，不能因版本相同而在此清除。
            notifications.entrySet().removeIf(entry -> PatchManifest.compareVersions(entry.getKey(), previous) <= 0);
        }
    }

    @PreDestroy
    public void close() {
        stopped = true;
        notificationTasks.values().forEach(task -> task.cancel(true));
        notificationTasks.clear();
        synchronized (scanLock) {
            // 等在途扫描退出后再完成关闭，关闭返回后不会再发布事件。
            notifications.clear();
            pendingEvent = null;
        }
    }
}
