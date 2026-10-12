package com.hidewnd.winds.jx3.model;

import java.time.Instant;

/** 第三方开服通知；展示文本由协议边界依据原始通知生成。 */
public record ServerOpening(String zone, String server, Instant time, String message) {
    public ServerOpening {
        if (zone == null || zone.isBlank() || server == null || server.isBlank()
                || time == null || message == null || message.isBlank()) {
            throw new IllegalArgumentException("第三方开服通知缺少区服、时间或消息");
        }
    }
}
