package com.hidewnd.winds.jx3.event;

import com.hidewnd.winds.jx3.model.EarthquakeReport;
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

    public static Jx3Event createEarthquakeEvent(EarthquakeReport report) {
        String occurredAt = Jx3Time.format(report.time());
        String message = "[" + occurredAt + "]国内地震" + (report.source().equals("ceic") ? "信息" : "预警")
                + "\n" + report.location() + "发生" + report.magnitude() + "级地震"
                + "\n" + (report.intensity() == null ? "" : "烈度：" + report.intensity() + "，")
                + "震源深度：" + report.depth() + "千米"
                + "\n震中纬度：" + report.latitude().toPlainString()
                + "，经度：" + report.longitude().toPlainString();
        return new Jx3Event("jx3.earthquake.warning", report.identity(), occurredAt, message,
                report.data(), report.source());
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
