package com.hidewnd.winds.jx3.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hidewnd.winds.jx3.config.EarthquakeProperties;
import com.hidewnd.winds.jx3.model.EarthquakeReport;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class CeicEarthquakeClient {
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final EarthquakeProperties properties;

    public CeicEarthquakeClient(HttpClient http, ObjectMapper mapper, EarthquakeProperties properties) {
        this.http = http;
        this.mapper = mapper;
        this.properties = properties;
    }

    public List<EarthquakeReport> fetch() {
        try {
            var response = http.send(HttpRequest.newBuilder(properties.endpoint())
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("官方地震接口 HTTP " + response.statusCode());
            }
            var root = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readTree(response.body());
            if (root == null || !root.isArray() || root.isEmpty()) {
                throw new IllegalArgumentException("官方地震列表必须为非空数组");
            }
            // 整批校验成功才交给存档，避免损坏响应被当作完整首次基线。
            List<EarthquakeReport> reports = new ArrayList<>();
            for (var item : root) {
                reports.add(EarthquakeReport.fromCeic(item));
            }
            return reports;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("官方地震采集被中断", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("官方地震采集失败", exception);
        }
    }
}
