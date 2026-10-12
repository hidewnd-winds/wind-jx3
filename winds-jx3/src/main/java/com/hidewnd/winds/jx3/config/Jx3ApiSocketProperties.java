package com.hidewnd.winds.jx3.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/** 第三方事件版独立连接配置，不复用综合版 HTTP token。 */
@ConfigurationProperties("winds.jx3.socket")
public record Jx3ApiSocketProperties(
        @DefaultValue("wss://socket.nicemoe.cn") URI endpoint,
        @DefaultValue("15s") Duration reconnectInterval,
        @DefaultValue("45s") Duration heartbeatTimeout) {
    public Jx3ApiSocketProperties {
        if (endpoint == null || !"wss".equals(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("第三方事件地址必须是有效的 wss 地址");
        }
        if (reconnectInterval == null || reconnectInterval.compareTo(Duration.ofSeconds(1)) < 0
                || heartbeatTimeout == null || heartbeatTimeout.compareTo(reconnectInterval) <= 0) {
            throw new IllegalArgumentException("重连间隔至少一秒，心跳超时必须大于重连间隔");
        }
    }
}
