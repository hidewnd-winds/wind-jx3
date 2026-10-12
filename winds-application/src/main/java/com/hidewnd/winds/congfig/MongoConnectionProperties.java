package com.hidewnd.winds.congfig;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** 官方 MongoDB 驱动连接池的统一维护和超时配置。 */
@ConfigurationProperties("winds.mongodb")
public record MongoConnectionProperties(
        @DefaultValue("2") int minPoolSize,
        @DefaultValue("100") int maxPoolSize,
        @DefaultValue("2") int maxConnecting,
        @DefaultValue("10s") Duration maxWaitTime,
        @DefaultValue("5m") Duration maxConnectionIdleTime,
        @DefaultValue("30s") Duration maintenanceFrequency,
        @DefaultValue("5s") Duration serverSelectionTimeout,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("30s") Duration readTimeout) {

    public MongoConnectionProperties {
        if (minPoolSize < 0 || maxPoolSize < 1 || minPoolSize > maxPoolSize || maxConnecting < 1) {
            throw new IllegalArgumentException("MongoDB 连接池要求 0 <= min-pool-size <= max-pool-size，容量和并发建连数必须为正数");
        }
        // 读取和等待必须有界，维护周期必须为正；限制为驱动毫秒参数可安全表示的范围。
        for (Duration duration : new Duration[] {maxWaitTime, maxConnectionIdleTime, maintenanceFrequency,
                serverSelectionTimeout, connectTimeout, readTimeout}) {
            if (duration == null || duration.compareTo(Duration.ofMillis(1)) < 0
                    || duration.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
                throw new IllegalArgumentException("MongoDB 超时和维护间隔必须在 1 到 2147483647 毫秒之间");
            }
        }
    }
}
