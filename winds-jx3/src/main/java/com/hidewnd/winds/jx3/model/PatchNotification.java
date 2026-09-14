package com.hidewnd.winds.jx3.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** 第三方更新线索；展示大小不换算成精确字节数，更新时间仍以官方文件为准。 */
public record PatchNotification(
        String nowVersion, String newVersion, int packageCount, String packageSize, Instant observedAt) {
    public PatchNotification {
        for (String version : new String[] {nowVersion, newVersion}) {
            if (version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) {
                throw new IllegalArgumentException("更新包通知版本号无效");
            }
            // 比较器可能在前段就返回，边界仍需检查所有版本段是否能安全表示。
            for (String part : version.split("\\.")) {
                Long.parseLong(part);
            }
        }
        if (PatchManifest.compareVersions(newVersion, nowVersion) <= 0 || packageCount <= 0
                || packageSize == null || packageSize.isBlank() || observedAt == null) {
            throw new IllegalArgumentException("更新包通知必须包含递增版本、正整数包数、大小和接收时间");
        }
    }

    public static PatchNotification from(JsonNode detail, Instant observedAt) {
        JsonNode count = detail.path("package_num");
        if (!count.isIntegralNumber() || !count.canConvertToInt()) {
            throw new IllegalArgumentException("更新包数量必须为有效整数");
        }
        return new PatchNotification(detail.path("now_version").textValue(),
                detail.path("new_version").textValue(), count.intValue(),
                detail.path("package_size").textValue(), observedAt);
    }
}
