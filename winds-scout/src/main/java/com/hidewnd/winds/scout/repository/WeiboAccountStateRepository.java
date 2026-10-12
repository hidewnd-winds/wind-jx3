package com.hidewnd.winds.scout.repository;

import com.hidewnd.winds.scout.model.WeiboAccount;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** 所有运行状态写入均按选取版本比较更新；局部更新保留 Cookie 和待发布告警。 */
@Repository
public class WeiboAccountStateRepository {
    private final MongoTemplate mongo;
    public WeiboAccountStateRepository(MongoTemplate mongo) { this.mongo = mongo; }

    public boolean delete(WeiboAccount expected) {
        return mongo.remove(Query.query(Criteria.where("_id").is(expected.getId())
                .and("stateVersion").is(expected.getStateVersion())
                .and("pendingAlerts.0").exists(false).and("pendingPoolRecovery").is(null)),
                WeiboAccount.class).getDeletedCount() == 1;
    }

    public WeiboAccount update(WeiboAccount expected, Update update) {
        Document unset = update.getUpdateObject().get("$unset", Document.class);
        if (unset != null) {
            // Spring Data 4.3 会把 $unset 的整数占位值也交给字段时间转换器。
            // $unset 忽略操作数，改用 null 避免类型误转换；仍删除字段，不写入 null 字段。
            unset.replaceAll((field, operand) -> null);
        }
        return mongo.findAndModify(Query.query(Criteria.where("_id").is(expected.getId())
                        .and("stateVersion").is(expected.getStateVersion())), update,
                FindAndModifyOptions.options().returnNew(true), WeiboAccount.class);
    }
}
