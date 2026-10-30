package com.hidewnd.winds.jx3.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("winds.jx3.earthquake")
public record EarthquakeProperties(
        @DefaultValue("http://www.ceic.ac.cn/data/data.json") URI endpoint,
        @DefaultValue("35s") Duration interval) {
    public EarthquakeProperties {
        if (endpoint == null || endpoint.getHost() == null
                || !("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))) {
            throw new IllegalArgumentException("官方地震地址必须为 HTTP(S) 地址");
        }
        if (interval == null || interval.compareTo(Duration.ofSeconds(30)) < 0
                || interval.compareTo(Duration.ofSeconds(40)) > 0) {
            throw new IllegalArgumentException("官方地震轮询间隔应为 30 至 40 秒");
        }
    }
}
