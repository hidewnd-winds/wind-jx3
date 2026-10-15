package com.hidewnd.winds.jx3.support;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 剑三对外协议与存储统一使用北京时间。 */
public final class Jx3Time {
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(BEIJING);

    private Jx3Time() {}

    public static String format(Instant time) {
        return time == null ? null : DATE_TIME.format(time);
    }

    public static Instant parse(String value) {
        return LocalDateTime.parse(value, DATE_TIME).atZone(BEIJING).toInstant();
    }

    public static Instant startOfDay(Instant time) {
        return time.atZone(BEIJING).toLocalDate().atStartOfDay(BEIJING).toInstant();
    }
}
