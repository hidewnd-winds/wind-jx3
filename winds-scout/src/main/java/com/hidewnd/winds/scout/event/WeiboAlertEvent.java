package com.hidewnd.winds.scout.event;

/** 脱敏告警快照；事件 ID 在状态落库时确定，重投不产生新身份。 */
public record WeiboAlertEvent(
        String eventId, String type, String incidentId, String occurredAt,
        String level, String message, String accountId, String status,
        String recoverAt, int failCount, int totalCount, int waitingCount,
        int stoppedCount, String earliestRecoverAt, String reason, long successGeneration) {
}
