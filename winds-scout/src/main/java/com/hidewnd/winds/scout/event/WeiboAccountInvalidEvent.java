package com.hidewnd.winds.scout.event;

import java.time.Instant;

/**
 * 账号永久停用状态成功入库后发布并定期补发，仅向具有微博管理权限的连接通知。
 *
 * @param accountId 失效账号 ID，不包含登录凭据
 * @param occurredAt 失效时间
 */
public record WeiboAccountInvalidEvent(String accountId, Instant occurredAt) {
}
