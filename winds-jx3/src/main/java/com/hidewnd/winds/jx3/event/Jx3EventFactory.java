package com.hidewnd.winds.jx3.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.hidewnd.winds.jx3.model.Article;
import com.hidewnd.winds.jx3.model.GameServer;
import com.hidewnd.winds.jx3.support.Jx3Time;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** 构造固定事件载荷和用户展示文本，保留既有协议。 */
public final class Jx3EventFactory {
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    private Jx3EventFactory() {}

    public static Jx3Event createEarthquakeEvent(String id, JsonNode detail) {
        if (!detail.isObject()
                || !detail.path("latitude").isNumber()
                || !detail.path("longitude").isNumber()
                || !detail.path("time").isIntegralNumber()
                || !detail.path("time").canConvertToLong()) {
            throw new IllegalArgumentException("地震事件坐标或时间类型无效");
        }
        for (String field : new String[]{"location", "intensity", "magnitude", "depth"}) {
            if (!detail.path(field).isTextual() || detail.path(field).textValue().isBlank()) {
                throw new IllegalArgumentException("地震事件缺少有效字段：" + field);
            }
        }
        double latitude = detail.path("latitude").doubleValue();
        double longitude = detail.path("longitude").doubleValue();
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("地震事件坐标超出范围");
        }
        // 2007 明确使用 Unix 秒；保留原始 detail 字段及类型，不转换震级、烈度或深度字符串。
        String occurredAt = Jx3Time.format(Instant.ofEpochSecond(detail.path("time").longValue()));
        String message = "[" + occurredAt + "]国内地震预警\n"
                + detail.path("location").textValue() + "发生" + detail.path("magnitude").textValue() + "级地震"
                + "\n烈度：" + detail.path("intensity").textValue()
                + "，震源深度：" + detail.path("depth").textValue() + "千米"
                + "\n震中纬度：" + detail.path("latitude").asText()
                + "，经度：" + detail.path("longitude").asText();
        return new Jx3Event("jx3.earthquake.warning", id, occurredAt, message, detail);
    }

    public static Jx3Event createPatchEvent(
            String id, Instant observed, String previous, String version, int count, long bytes) {
        String message =
                String.format(
                        Locale.ROOT,
                        "[%s]西山居又偷偷更新了！\n版本 %s->%s\n共%d个更新包，总计%.2f MB",
                        CLOCK.format(observed),
                        previous,
                        version,
                        count,
                        bytes / 1048576.0);
        return new Jx3Event(
                "jx3.patch.updated",
                id,
                Jx3Time.format(observed),
                message,
                new PatchEventData(previous, version, count, bytes));
    }

    public static Jx3Event createArticleEvent(
            String id, Instant observed, Article article, boolean created) {
        String change = created ? "created" : "updated";
        String label = article.maintenance() ? "剑网3公告" : "剑网3新闻";
        String message =
                "["
                        + CLOCK.format(observed)
                        + "]"
                        + label
                        + (created ? "发布" : "更新")
                        + "\n"
                        + article.title()
                        + (article.summary() == null ? "" : "\n" + article.summary())
                        + "\n"
                        + article.url();
        Object data =
                article.maintenance()
                        ? new MaintenanceEventData(
                                article.articleId(),
                                change,
                                article.title(),
                                article.summary(),
                                article.url(),
                                article.publishedAt(),
                                article.updatedAt(),
                                article.maintenanceStatus(),
                                article.startsAt(),
                                article.expectedEndsAt())
                        : new NewsEventData(
                                article.articleId(),
                                article.categoryId(),
                                change,
                                article.title(),
                                article.summary(),
                                article.url(),
                                article.publishedAt(),
                                article.updatedAt());
        return new Jx3Event(
                article.maintenance() ? "jx3.maintenance.updated" : "jx3.news.updated",
                id,
                Jx3Time.format(observed),
                message,
                data);
    }

    public static Jx3Event createServerEvent(
            String id,
            Instant observed,
            Instant confirmed,
            GameServer server,
            String previous,
            String status) {
        String message =
                "["
                        + CLOCK.format(observed)
                        + "]"
                        + server.serverName()
                        + (status.equals("reachable") ? "开服啦！" : "暂时无法连接，可能维护中。");
        return new Jx3Event(
                "jx3.server.changed",
                id,
                Jx3Time.format(observed),
                message,
                new ServerEventData(
                        server.zoneId(),
                        server.zoneName(),
                        server.serverName(),
                        server.aliases(),
                        previous,
                        status,
                        "tcp",
                        Jx3Time.format(confirmed)));
    }
}
