package com.hidewnd.winds.scout.repository;

import com.hidewnd.winds.scout.model.WeiboAccount;
import com.mongodb.client.result.UpdateResult;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * 微博账号 Cookie 状态的原子持久化组件。
 */
@Repository
public class WeiboAccountCookieRepository {

    private final MongoTemplate mongoTemplate;

    public WeiboAccountCookieRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * 一次性更新完整 Cookie 状态及其兼容字段，避免写入半套凭据。
     *
     * @param account 已吸收响应 Cookie 的账号
     */
    public void save(WeiboAccount account) {
        Update update = new Update()
                .set("cookie", account.getCookie())
                .set("xsrf_token", account.getXsrfToken())
                .set("cookies", account.getCookies())
                // 使用实体属性名，让 Mongo 字段转换器同时处理时间格式与字段映射。
                .set("updatedAt", account.getUpdatedAt());
        UpdateResult result = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(account.getId()).and("stateVersion").is(account.getStateVersion())), update, WeiboAccount.class);
        if (result.getMatchedCount() == 0) {
            throw new IllegalStateException("微博账号不存在或状态版本已变化，无法保存Cookie状态");
        }
    }
}
