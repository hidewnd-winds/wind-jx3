package com.hidewnd.winds.jx3.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hidewnd.winds.jx3.support.Jx3Time;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;

/** 两个外部入口只在此校验和转换，内部使用统一的地震报告。 */
public record EarthquakeReport(String source, String sourceId, Instant time,
        BigDecimal latitude, BigDecimal longitude, String location,
        String magnitude, String depth, String intensity, JsonNode raw) {
    private static final DateTimeFormatter OFFICIAL_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final List<String> DOMESTIC_PREFIXES = List.of(
            "北京", "天津", "河北", "山西", "内蒙古", "辽宁", "吉林", "黑龙江",
            "上海", "江苏", "浙江", "安徽", "福建", "江西", "山东", "河南",
            "湖北", "湖南", "广东", "广西", "海南", "重庆", "四川", "贵州",
            "云南", "西藏", "陕西", "甘肃", "青海", "宁夏", "新疆", "台湾", "香港", "澳门");

    public static EarthquakeReport fromJx3Api(JsonNode detail) {
        return parse(detail, false);
    }

    public static EarthquakeReport fromCeic(JsonNode detail) {
        return parse(detail, true);
    }

    private static EarthquakeReport parse(JsonNode detail, boolean official) {
        if (!detail.isObject()) {
            throw new IllegalArgumentException("地震报告必须是对象");
        }
        BigDecimal latitude = number(detail, "latitude");
        BigDecimal longitude = number(detail, "longitude");
        if (latitude.abs().compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.abs().compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalArgumentException("地震坐标超出范围");
        }
        Instant time;
        if (official) {
            time = LocalDateTime.parse(text(detail, "time"), OFFICIAL_TIME)
                    .atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        } else {
            JsonNode value = detail.path("time");
            if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                throw new IllegalArgumentException("地震发震时间必须为 Unix 秒");
            }
            time = Instant.ofEpochSecond(value.longValue());
            Jx3Time.format(time);
        }
        return new EarthquakeReport(official ? "ceic" : "jx3api",
                official ? text(detail, "id") : null, time, latitude, longitude,
                text(detail, "location"),
                official ? number(detail, "magnitude").toPlainString() : text(detail, "magnitude"),
                official ? number(detail, "depth").toPlainString() : text(detail, "depth"),
                official ? null : text(detail, "intensity"), detail.deepCopy());
    }

    private static BigDecimal number(JsonNode detail, String field) {
        JsonNode value = detail.path(field);
        if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException("地震字段必须为有限数值：" + field);
        }
        return value.decimalValue().stripTrailingZeros();
    }

    private static String text(JsonNode detail, String field) {
        JsonNode value = detail.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("地震字段必须为非空文本：" + field);
        }
        return value.textValue();
    }

    public String identity() {
        // 不按震级、地名或上游 ID 合并；数值等价的坐标去掉末尾零后得到同一身份。
        return "earthquake:" + time.getEpochSecond() + ":" + latitude.toPlainString()
                + ":" + longitude.toPlainString();
    }

    public boolean domestic() {
        return DOMESTIC_PREFIXES.stream().anyMatch(location::startsWith);
    }

    public ObjectNode data() {
        ObjectNode data = JsonNodeFactory.instance.objectNode()
                .put("time", time.getEpochSecond()).put("latitude", latitude).put("longitude", longitude)
                .put("location", location).put("magnitude", magnitude).put("depth", depth);
        if (intensity != null) {
            data.put("intensity", intensity);
        }
        return data;
    }
}
