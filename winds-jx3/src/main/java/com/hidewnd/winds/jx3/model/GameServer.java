package com.hidewnd.winds.jx3.model;

import java.util.List;

/** 同一连接地址对应的主服及合服别名。 */
public record GameServer(
        String zoneId,
        String zoneName,
        String serverName,
        List<String> aliases,
        String host,
        int port) {
    public String endpoint() {
        return host + ":" + port;
    }

    /** 清单探测和第三方通知共用同一排除规则。 */
    public static boolean excludedFromMonitoring(String zone, String server) {
        return "比赛专区".equals(zone)
                && ("练习专用服".equals(server) || "比赛专用服".equals(server));
    }
}
