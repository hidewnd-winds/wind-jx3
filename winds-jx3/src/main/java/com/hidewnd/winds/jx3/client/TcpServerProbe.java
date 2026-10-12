package com.hidewnd.winds.jx3.client;

import com.hidewnd.winds.jx3.model.GameServer;
import com.hidewnd.winds.jx3.model.ProbeStatus;
import com.hidewnd.winds.jx3.model.ServerProbeResult;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 只探测网关 TCP 连通性；超时和路由错误不能作为维护证据。 */
public class TcpServerProbe {
    private final int timeoutMillis;

    public TcpServerProbe(Duration timeout) {
        timeoutMillis = Math.toIntExact(timeout.toMillis());
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("区服连接超时必须为正数");
        }
    }

    public ServerProbeResult check(GameServer server) {
        long started = System.nanoTime();
        // 网关探测必须直连，避免 JVM SOCKS/ProxySelector 让探测与 nc 使用不同路径。
        try (Socket socket = new Socket(Proxy.NO_PROXY)) {
            socket.connect(new InetSocketAddress(server.host(), server.port()), timeoutMillis);
            return new ServerProbeResult(
                    ProbeStatus.REACHABLE,
                    null,
                    null,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } catch (IOException exception) {
            String detail = Objects.toString(exception.getMessage(), "");
            String message = detail.toLowerCase(Locale.ROOT);
            // ConnectException 也可能包含网络不可达；仅明确拒绝才参与关服确认。
            boolean refused =
                    exception instanceof ConnectException
                            && (message.contains("refused") || message.contains("拒绝"));
            return new ServerProbeResult(
                    refused ? ProbeStatus.REFUSED : ProbeStatus.UNKNOWN,
                    exception.getClass().getSimpleName(),
                    detail,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }
}
