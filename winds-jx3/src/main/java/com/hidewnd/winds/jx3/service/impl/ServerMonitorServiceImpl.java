package com.hidewnd.winds.jx3.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hidewnd.winds.jx3.client.OfficialClient;
import com.hidewnd.winds.jx3.client.TcpServerProbe;
import com.hidewnd.winds.jx3.event.Jx3Event;
import com.hidewnd.winds.jx3.event.Jx3EventFactory;
import com.hidewnd.winds.jx3.event.ServerEventData;
import com.hidewnd.winds.jx3.model.GameServer;
import com.hidewnd.winds.jx3.model.ProbeStatus;
import com.hidewnd.winds.jx3.model.ServerOpening;
import com.hidewnd.winds.jx3.model.ServerProbeResult;
import com.hidewnd.winds.jx3.model.ServerStateTracker;
import com.hidewnd.winds.jx3.model.ServerTransition;
import com.hidewnd.winds.jx3.parser.ServerListParser;
import com.hidewnd.winds.jx3.repository.Jx3RecordRepository;
import com.hidewnd.winds.jx3.service.ServerMonitorService;
import com.hidewnd.winds.jx3.support.Jx3Time;

import jakarta.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;

import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/** 编排清单刷新、网关探测、状态确认与持久化后的事件发布。 */
@Slf4j
public class ServerMonitorServiceImpl implements ServerMonitorService {
    private final OfficialClient client;
    private final Jx3RecordRepository repository;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TcpServerProbe probe;
    private final Executor executor;
    private final Map<String, ServerState> states = new HashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Object refreshLock = new Object();
    private final Map<ServerOpening, CompletableFuture<Void>> openingTasks = new ConcurrentHashMap<>();
    private volatile boolean stopped;
    private List<GameServer> servers = List.of();
    private Instant nextRefreshAt;

    public ServerMonitorServiceImpl(
            OfficialClient client,
            Jx3RecordRepository repository,
            ApplicationEventPublisher publisher,
            ObjectMapper mapper,
            Clock clock,
            TcpServerProbe probe,
            Executor executor) {
        this.client = client;
        this.repository = repository;
        this.publisher = publisher;
        this.mapper = mapper;
        this.clock = clock;
        this.probe = probe;
        this.executor = executor;
    }

    @Override
    public void poll() {
        List<GameServer> snapshot = serverSnapshot();
        // 每个网关独立进行有超时的 TCP 探测，按完成顺序处理，避免慢网关阻塞快网关推送。
        var completed = new ExecutorCompletionService<ProbeObservation>(executor);
        List<Future<ProbeObservation>> pending = new ArrayList<>();
        try {
            for (GameServer server : snapshot) {
                long ticket = sequence.incrementAndGet();
                pending.add(completed.submit(() -> {
                    ServerProbeResult result = probe.check(server);
                    return new ProbeObservation(server, result, clock.instant(), ticket);
                }));
            }
            for (int index = 0; index < snapshot.size(); index++) {
                ProbeObservation observation = completed.take().get();
                acceptProbe(observation.server(), observation.result(), observation.observedAt(), observation.ticket());
            }
        } catch (InterruptedException exception) {
            interruptCandidates();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("区服探测被中断", exception);
        } catch (ExecutionException exception) {
            interruptCandidates();
            throw new IllegalStateException("区服探测异常", exception.getCause());
        } finally {
            pending.forEach(future -> future.cancel(true));
        }
    }

    private synchronized void acceptProbe(GameServer server, ServerProbeResult result, Instant observedAt, long ticket) {
        if (stopped) {
            return;
        }
        ServerState current = stateFor(server);
        // 核验和轮询共享状态；较早启动但较晚返回的探测不得覆盖新结论。
        if (ticket < current.sequence) {
            return;
        }
        ServerStateTracker tracker = current.tracker;
        String previousStatus = tracker.getConfirmedStatus();
        ServerTransition transition = tracker.observe(result.status(), observedAt);
        // 明确结果也要可追踪，否则线上只剩无关网关的超时日志，无法区分漏检测和漏投递。
        log.debug("区服探测完成，大区={}，服务器={}，地址={}，结果={}，已确认状态={}，耗时={}ms",
                server.zoneName(), server.serverName(), server.endpoint(), result.status(),
                previousStatus, result.elapsedMillis());
        if (result.status() == ProbeStatus.UNKNOWN) {
            log.warn(
                    "区服探测结果未知，大区={}，服务器={}，地址={}，耗时={}ms，异常类型={}，原因={}；不据此判断维护",
                    server.zoneName(),
                    server.serverName(),
                    server.endpoint(),
                    result.elapsedMillis(),
                    result.failureType(),
                    result.detail());
            return;
        }
        if (tracker.getConfirmedStatus() == null) {
            return;
        }
        String status = transition == null ? tracker.getConfirmedStatus() : transition.status();
        var state = mapper.createObjectNode().put("status", status);
        state.set("server", mapper.valueToTree(server));
        Instant openingAt = transition != null && status.equals("reachable")
                ? observedAt : current.lastOpeningAt;
        if (openingAt != null) {
            state.put("lastOpeningAt", Jx3Time.format(openingAt));
        }
        Jx3Event event =
                transition == null
                        ? null
                        : Jx3EventFactory.createServerEvent(
                                UUID.randomUUID().toString(),
                                transition.observed(),
                                clock.instant(),
                                server,
                                tracker.getConfirmedStatus(),
                                status);
        boolean inserted =
                repository.save(
                        "server:" + server.zoneId() + ":" + server.serverName(), state, event);
        current.lastOpeningAt = openingAt;
        if (previousStatus == null) {
            log.info("区服基线已建立，大区={}，服务器={}，地址={}，状态={}；首次确认不推送",
                    server.zoneName(), server.serverName(), server.endpoint(), status);
        }
        if (transition != null) {
            // 候选探测不能淘汰并行核验；只有已保存的状态变化才推进提交顺序。
            current.sequence = ticket;
            tracker.confirm(status);
            if (inserted) {
                log.info("区服状态变化已保存，大区={}，服务器={}，地址={}，状态={}->{}，事件={}",
                        server.zoneName(), server.serverName(), server.endpoint(),
                        previousStatus, status, event.eventId());
                publisher.publishEvent(event);
            } else {
                log.info("区服状态变化记录已存在，服务器={}，状态={}->{}；不重复推送",
                        server.serverName(), previousStatus, status);
            }
        }
    }

    private void refreshServers(Instant now) {
        List<GameServer> updated;
        try {
            updated = ServerListParser.parse(client.fetchServerList());
        } catch (RuntimeException exception) {
            synchronized (this) {
                if (servers.isEmpty()) {
                    throw exception;
                }
                // 清单刷新故障不等于网关状态未知；保留有效清单和候选状态，一分钟后重试刷新。
                nextRefreshAt = now.plusSeconds(60);
                log.warn("官方区服清单刷新失败，继续探测上次有效清单，数量={}，下次刷新={}",
                        servers.size(), nextRefreshAt, exception);
            }
            return;
        }
        synchronized (this) {
            states.keySet().retainAll(updated.stream().map(GameServer::endpoint).toList());
            servers = updated;
            nextRefreshAt = now.plusSeconds(3600);
            log.info("官方区服清单已刷新，网关数={}，下次刷新={}", servers.size(), nextRefreshAt);
        }
    }

    private List<GameServer> serverSnapshot() {
        synchronized (refreshLock) {
            Instant now = clock.instant();
            synchronized (this) {
                if (nextRefreshAt != null && now.isBefore(nextRefreshAt)) {
                    return servers;
                }
            }
            // 清单请求只串行化清单刷新，不阻塞已有网关的核验结果提交。
            refreshServers(now);
            synchronized (this) {
                return servers;
            }
        }
    }

    private synchronized void interruptCandidates() {
        states.values().forEach(state -> state.tracker.observe(ProbeStatus.UNKNOWN, clock.instant()));
    }

    /** 通知登记交给共享执行器，WS 回调不等待数据库。 */
    @Override
    public CompletableFuture<Void> verifyOpening(ServerOpening opening) {
        if (stopped) {
            return CompletableFuture.failedFuture(new IllegalStateException("开服监听已停止"));
        }
        // 接收线程不获取持久化锁，慢数据库不能阻塞 WS 接收与心跳。
        CompletableFuture<Void> task = new CompletableFuture<>();
        CompletableFuture<Void> existing = openingTasks.putIfAbsent(opening, task);
        if (existing != null) {
            return existing;
        }
        if (stopped) {
            openingTasks.remove(opening, task);
            task.cancel(false);
            return task;
        }
        try {
            executor.execute(() -> {
                try {
                    if (!task.isCancelled()) {
                        verifyOpeningNow(opening);
                    }
                    task.complete(null);
                } catch (RuntimeException exception) {
                    task.completeExceptionally(exception);
                } finally {
                    openingTasks.remove(opening, task);
                }
            });
        } catch (RuntimeException exception) {
            openingTasks.remove(opening, task);
            task.completeExceptionally(exception);
        }
        return task;
    }

    private synchronized void verifyOpeningNow(ServerOpening opening) {
        // 只使用已有清单补充身份；没有清单时按通知中的区服登记，不等待官网请求。
        if (stopped) {
            throw new IllegalStateException("开服监听已停止");
        }
        List<GameServer> matches = servers.stream()
                .filter(server -> server.zoneName().equals(opening.zone())
                        && (server.serverName().equals(opening.server()) || server.aliases().contains(opening.server())))
                .toList();
        if (matches.size() != 1) {
            log.warn("第三方开服通知无法唯一匹配正式服，大区={}，服务器={}，匹配数={}",
                    opening.zone(), opening.server(), matches.size());
            publishUnresolvedOpening(opening);
            return;
        }
        acceptOpening(matches.getFirst(), opening);
    }

    private synchronized void acceptOpening(GameServer server, ServerOpening opening) {
        if (stopped) {
            throw new IllegalStateException("开服监听已停止");
        }
        ServerState current = stateFor(server);
        if (current.lastOpeningAt != null && !opening.time().isAfter(current.lastOpeningAt)) {
            return;
        }
        // 三方有效通知直接作为开服依据；TCP 轮询独立继续，不阻塞或否决通知。
        long ticket = sequence.incrementAndGet();
        Jx3Event event = new Jx3Event("jx3.server.changed", UUID.randomUUID().toString(),
                Jx3Time.format(opening.time()), opening.message(),
                new ServerEventData(server.zoneId(), server.zoneName(), server.serverName(), server.aliases(),
                        current.tracker.getConfirmedStatus(), "reachable", "jx3api", null));
        var state = mapper.createObjectNode().put("status", "reachable")
                .put("lastOpeningAt", Jx3Time.format(opening.time()));
        state.set("server", mapper.valueToTree(server));
        boolean inserted = repository.save("server:" + server.zoneId() + ":" + server.serverName(), state, event);
        // 保存成功后才推进状态并清除旧维护候选，迟到的旧探测不得覆盖开服通知。
        current.tracker.observe(ProbeStatus.UNKNOWN, clock.instant());
        current.tracker.confirm("reachable");
        current.lastOpeningAt = opening.time();
        current.sequence = ticket;
        if (inserted) {
            log.info("第三方开服通知已保存，服务器={}，事件时间={}，事件={}",
                    server.serverName(), Jx3Time.format(opening.time()), event.eventId());
            publisher.publishEvent(event);
        }
    }

    private ServerState stateFor(GameServer server) {
        return states.computeIfAbsent(server.endpoint(), ignored -> {
            ServerState state = new ServerState();
            var stored = repository.load("server:" + server.zoneId() + ":" + server.serverName());
            if (stored != null && stored.hasNonNull("lastOpeningAt")) {
                state.lastOpeningAt = Jx3Time.parse(stored.path("lastOpeningAt").asText());
            }
            // 首次清单失败时的通知仍可去重；恢复映射后沿用同一开服时间水位。
            List<String> names = new ArrayList<>(server.aliases());
            names.add(server.serverName());
            for (String name : names) {
                var unresolved = repository.load("server-unresolved:" + server.zoneName() + ":" + name);
                if (unresolved != null && unresolved.hasNonNull("lastOpeningAt")) {
                    Instant openingAt = Jx3Time.parse(unresolved.path("lastOpeningAt").asText());
                    if (state.lastOpeningAt == null || openingAt.isAfter(state.lastOpeningAt)) {
                        state.lastOpeningAt = openingAt;
                        state.tracker.confirm("reachable");
                    }
                }
            }
            return state;
        });
    }

    /** 无缓存映射时保留第三方区服身份，不伪造官方大区 ID。 */
    private synchronized void publishUnresolvedOpening(ServerOpening opening) {
        if (stopped) {
            throw new IllegalStateException("开服监听已停止");
        }
        String key = "server-unresolved:" + opening.zone() + ":" + opening.server();
        var previous = repository.load(key);
        if (previous != null && !opening.time().isAfter(Jx3Time.parse(previous.path("lastOpeningAt").asText()))) {
            return;
        }
        Instant registered = repository.lastServerOpening(opening.zone(), opening.server());
        if (registered != null && !opening.time().isAfter(registered)) {
            return;
        }
        var event = new Jx3Event("jx3.server.changed", UUID.randomUUID().toString(), Jx3Time.format(opening.time()),
                opening.message(), new ServerEventData(null, opening.zone(), opening.server(), List.of(),
                        null, "reachable", "jx3api", null));
        var state = mapper.createObjectNode().put("status", "reachable")
                .put("lastOpeningAt", Jx3Time.format(opening.time()));
        if (repository.save(key, state, event)) {
            log.warn("第三方开服消息已保存，无法取得官方核验地址，大区={}，服务器={}，事件={}",
                    opening.zone(), opening.server(), event.eventId());
            publisher.publishEvent(event);
        }
    }

    @PreDestroy
    public synchronized void close() {
        stopped = true;
        openingTasks.values().forEach(task -> task.cancel(false));
        openingTasks.clear();
    }

    /** 此状态只在监听服务锁内访问，网络请求不持有该锁。 */
    private static final class ServerState {
        private final ServerStateTracker tracker = new ServerStateTracker();
        private Instant lastOpeningAt;
        private long sequence;
    }

    private record ProbeObservation(GameServer server, ServerProbeResult result, Instant observedAt, long ticket) {}

}
