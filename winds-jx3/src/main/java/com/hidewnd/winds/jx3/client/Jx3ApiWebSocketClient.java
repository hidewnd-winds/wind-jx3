package com.hidewnd.winds.jx3.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hidewnd.winds.jx3.config.Jx3ApiSocketProperties;
import com.hidewnd.winds.jx3.model.ServerOpening;
import com.hidewnd.winds.jx3.model.ArticleNotification;
import com.hidewnd.winds.jx3.model.PatchNotification;
import com.hidewnd.winds.jx3.service.ArticleMonitorService;
import com.hidewnd.winds.jx3.service.PatchMonitorService;
import com.hidewnd.winds.jx3.service.ServerMonitorService;
import com.hidewnd.winds.jx3.support.Jx3Time;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;

import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;

/** 常驻事件版 WSS；连接、分片和心跳归此客户端，业务核验交给监听服务。 */
@Slf4j
public class Jx3ApiWebSocketClient {
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final ServerMonitorService monitor;
    private final ArticleMonitorService news;
    private final ArticleMonitorService maintenance;
    private final PatchMonitorService patches;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final Jx3ApiSocketProperties properties;
    private ScheduledFuture<?> scheduled;
    private CompletableFuture<WebSocket> connecting;
    private Connection connection;
    private WebSocket socket;
    private Instant lastPong;
    private boolean stopped = true;

    public Jx3ApiWebSocketClient(HttpClient http, ObjectMapper mapper, ServerMonitorService monitor,
            ArticleMonitorService news, ArticleMonitorService maintenance, PatchMonitorService patches,
            TaskScheduler scheduler, Clock clock, Jx3ApiSocketProperties properties) {
        this.http = http;
        this.mapper = mapper;
        this.monitor = monitor;
        this.news = news;
        this.maintenance = maintenance;
        this.patches = patches;
        this.scheduler = scheduler;
        this.clock = clock;
        this.properties = properties;
    }

    @PostConstruct
    public synchronized void start() {
        if (!stopped) {
            return;
        }
        stopped = false;
        scheduled = scheduler.scheduleWithFixedDelay(this::tick, properties.reconnectInterval());
        tick();
    }

    /** 定时器只提交非阻塞握手/Ping；断线后按固定间隔重连，避免异常时忙循环。 */
    private synchronized void tick() {
        if (stopped) {
            return;
        }
        if (socket == null) {
            if (connecting != null && !connecting.isDone()) {
                return;
            }
            Connection next = new Connection();
            connection = next;
            try {
                connecting = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                        .buildAsync(properties.endpoint(), next);
                connecting.whenComplete((opened, error) -> {
                    synchronized (Jx3ApiWebSocketClient.this) {
                        if (stopped || connection != next) {
                            if (opened != null) {
                                opened.abort();
                            }
                        } else if (error != null) {
                            disconnect(next, "握手失败");
                        }
                    }
                });
            } catch (RuntimeException exception) {
                disconnect(next, "握手提交失败");
            }
            return;
        }
        if (!clock.instant().isBefore(lastPong.plus(properties.heartbeatTimeout()))) {
            disconnect(connection, "心跳超时");
            return;
        }
        Connection active = connection;
        try {
            socket.sendPing(ByteBuffer.allocate(0)).whenComplete((ignored, error) -> {
                if (error != null) {
                    disconnect(active, "Ping 发送失败");
                }
            });
        } catch (RuntimeException exception) {
            disconnect(active, "Ping 提交失败");
        }
    }

    private synchronized void disconnect(Connection source, String reason) {
        if (connection != source) {
            return;
        }
        connection = null;
        WebSocket previous = socket;
        socket = null;
        if (previous != null) {
            previous.abort();
        }
        if (connecting != null) {
            connecting.cancel(true);
            connecting = null;
        }
        if (!stopped) {
            // 不记录远端关闭原因、异常文本或连接 URL，避免第三方载荷或查询凭据进入日志。
            log.warn("第三方事件 WSS 已断开，原因={}；{} 后重连", reason, properties.reconnectInterval());
        }
    }

    @PreDestroy
    public synchronized void close() {
        stopped = true;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        disconnect(connection, "应用停止");
    }

    private void acceptMessage(String message) {
        try {
            var root = mapper.readTree(message);
            if (root == null || !root.path("action").isIntegralNumber()
                    || !root.path("action").canConvertToInt()
                    || !"success".equals(root.path("status").textValue())) {
                return;
            }
            var detail = root.path("detail");
            int action = root.path("action").intValue();
            if (action == 2003) {
                PatchNotification notification = PatchNotification.from(detail, clock.instant());
                log.info("收到第三方更新包通知，版本={}->{}，声明包数={}",
                        notification.nowVersion(), notification.newVersion(), notification.packageCount());
                patches.verifyPatch(notification).whenComplete((ignored, error) -> {
                    if (error != null) {
                        log.error("第三方更新包通知处理失败，目标版本={}，异常类型={}",
                                notification.newVersion(), error.getClass().getSimpleName());
                    }
                });
                return;
            }
            if (action == 2002) {
                ArticleNotification notification = ArticleNotification.from(detail);
                log.info("收到第三方文章通知，栏目={}，文章={}", notification.categoryId(), notification.articleId());
                (notification.maintenance() ? maintenance : news).verifyArticle(notification)
                        .whenComplete((ignored, error) -> {
                            if (error != null) {
                                log.error("第三方文章通知处理失败，文章={}，异常类型={}",
                                        notification.articleId(), error.getClass().getSimpleName());
                            }
                        });
                return;
            }
            if (action != 2001) {
                return;
            }
            if (!"1".equals(detail.path("status").textValue())) {
                return;
            }
            if (!detail.path("time").isIntegralNumber() || !detail.path("time").canConvertToLong()) {
                throw new IllegalArgumentException("事件时间必须为整数时间戳");
            }
            long timestamp = detail.path("time").longValue();
            // 上游仅声明整数时间戳而未标注单位；边界接受 Unix 秒和毫秒，内部只用 Instant。
            Instant time = timestamp >= 1_000_000_000_000L
                    ? Instant.ofEpochMilli(timestamp) : Instant.ofEpochSecond(timestamp);
            Instant now = clock.instant();
            if (time.isBefore(now.minus(Duration.ofMinutes(10))) || time.isAfter(now.plusSeconds(60))) {
                log.warn("忽略过期或未来的第三方开服通知");
                return;
            }
            String zone = detail.path("zone").textValue();
            String server = detail.path("server").textValue();
            // 协议无 message 字段，兜底展示仅根据原始区服和事件时间生成。
            ServerOpening opening = new ServerOpening(zone, server, time,
                    "[" + Jx3Time.format(time) + "]" + zone + "·" + server + "开服啦！");
            monitor.verifyOpening(opening).whenComplete((ignored, error) -> {
                if (error != null) {
                    log.error("第三方开服通知处理失败，大区={}，服务器={}，异常类型={}",
                            zone, server, error.getClass().getSimpleName());
                }
            });
        } catch (JsonProcessingException | IllegalArgumentException | java.time.DateTimeException exception) {
            log.warn("忽略无效的第三方事件消息，异常类型={}", exception.getClass().getSimpleName());
        }
    }

    /** 每次连接使用独立缓冲，旧连接的迟到回调不能影响新连接。 */
    private final class Connection implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();

        @Override
        public void onOpen(WebSocket opened) {
            synchronized (Jx3ApiWebSocketClient.this) {
                if (stopped || connection != this) {
                    opened.abort();
                    return;
                }
                socket = opened;
                lastPong = clock.instant();
                log.info("第三方事件 WSS 连接成功");
            }
            opened.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket source, CharSequence data, boolean last) {
            synchronized (Jx3ApiWebSocketClient.this) {
                if (stopped || connection != this) {
                    return null;
                }
            }
            if (text.length() + data.length() > 65_536) {
                disconnect(this, "消息超过大小限制");
                return null;
            }
            text.append(data);
            if (last) {
                String message = text.toString();
                text.setLength(0);
                acceptMessage(message);
            }
            source.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket source, ByteBuffer data) {
            synchronized (Jx3ApiWebSocketClient.this) {
                if (!stopped && connection == this) {
                    lastPong = clock.instant();
                }
            }
            source.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket source, int statusCode, String reason) {
            disconnect(this, "远端关闭");
            return null;
        }

        @Override
        public void onError(WebSocket source, Throwable error) {
            disconnect(this, "连接异常");
        }
    }
}
