package com.hidewnd.winds.scout.model;

import com.hidewnd.winds.scout.config.ScoutMongoTimeConverter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.convert.ValueConverter;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.List;

/**
 * 微博抓取账号及其运行状态的 MongoDB 实体。
 */
@Data
@NoArgsConstructor
@Document(collection = "weibo_account_pool")
@Schema(description = "微博抓取账号持久化实体")
public class WeiboAccount {

    @Id
    @Schema(description = "抓取账号唯一 ID", example = "account-1")
    private String id;
    @Schema(description = "微博登录 Cookie", accessMode = Schema.AccessMode.WRITE_ONLY)
    private String cookie;
    @Field("xsrf_token")
    @Schema(description = "微博 XSRF Token", accessMode = Schema.AccessMode.WRITE_ONLY)
    private String xsrfToken;
    @Schema(description = "账号状态", allowableValues = {"active", "fail"})
    private String status;
    @Field("fail_count")
    @Schema(description = "连续失败次数")
    private int failCount;
    @Field("request_count")
    @Schema(description = "累计请求次数")
    private long requestCount;
    @Field("last_error_at")
    @ValueConverter(ScoutMongoTimeConverter.class)
    @Schema(description = "最近失败时间")
    private Instant lastErrorAt;
    @Field("last_error_message")
    @Schema(description = "最近错误类型", accessMode = Schema.AccessMode.READ_ONLY)
    private String lastErrorMessage;
    @Field("last_used_at")
    @ValueConverter(ScoutMongoTimeConverter.class)
    @Schema(description = "最近使用时间")
    private Instant lastUsedAt;
    @Field("recover_at")
    @ValueConverter(ScoutMongoTimeConverter.class)
    @Schema(description = "自动恢复时间，空值表示不自动恢复")
    private Instant recoverAt;
    @Field("created_at")
    @ValueConverter(ScoutMongoTimeConverter.class)
    @Schema(description = "创建时间")
    private Instant createdAt;
    @Field("updated_at")
    @ValueConverter(ScoutMongoTimeConverter.class)
    @Schema(description = "更新时间")
    private Instant updatedAt;
    @Schema(description = "按域、路径和有效期维护的完整 Cookie 状态", accessMode = Schema.AccessMode.WRITE_ONLY)
    private List<WeiboCookie> cookies;

    // 选取和管理变更生成新版本，迟到的请求不能覆盖新凭据或重复结束恢复轮次。
    private long successGeneration;
    private String pendingPoolRecovery;
    private Long requestLeaseUntil;
    private String stateVersion;
    private String recoveryAttemptId;
    @ValueConverter(ScoutMongoTimeConverter.class)
    private Instant lastSuccessAt;
    private List<com.hidewnd.winds.scout.event.WeiboAlertEvent> pendingAlerts;

    public WeiboAccount(
            String id,
            String cookie,
            String xsrfToken,
            String status,
            int failCount,
            long requestCount,
            Instant lastErrorAt,
            String lastErrorMessage,
            Instant lastUsedAt,
            Instant recoverAt,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.cookie = cookie;
        this.xsrfToken = xsrfToken;
        this.status = status;
        this.failCount = failCount;
        this.requestCount = requestCount;
        this.lastErrorAt = lastErrorAt;
        this.lastErrorMessage = lastErrorMessage;
        this.lastUsedAt = lastUsedAt;
        this.recoverAt = recoverAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public WeiboAccount(String id, String cookie, String xsrfToken) {
        this.id = id;
        this.cookie = cookie;
        this.xsrfToken = xsrfToken;
        this.status = "active";
    }
}
