package com.hidewnd.winds.scout.repository;

import com.hidewnd.winds.scout.event.WeiboAlertEvent;
import com.hidewnd.winds.scout.model.WeiboAccount;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** 告警日志、按 Token 的接收确认及单文档账号池故障状态。 */
@Repository
public class WeiboAlertRepository {
    private static final String ALERTS = "weibo_alerts";
    private static final String STATE = "weibo_alert_state";
    private final MongoTemplate mongo;

    public WeiboAlertRepository(MongoTemplate mongo) { this.mongo = mongo; }

    public Document poolState() {
        Document existing = mongo.findById("pool", Document.class, STATE);
        if (existing != null) return existing;
        mongo.upsert(Query.query(Criteria.where("_id").is("pool")),
                new Update().setOnInsert("version", "initial"), STATE);
        return mongo.findById("pool", Document.class, STATE);
    }

    public boolean openPool(String version, WeiboAlertEvent event, List<WeiboAccount> snapshot) {
        return mongo.updateFirst(Query.query(Criteria.where("_id").is("pool").and("version").is(version)
                        .and("incidentId").is(null)),
                new Update().set("version", UUID.randomUUID().toString()).set("incidentId", event.incidentId())
                        .set("accountSuccesses", snapshot.stream().map(account -> new Document("accountId", account.getId())
                                .append("generation", account.getSuccessGeneration())).toList())
                        .push("pendingAlerts", document(event)), STATE).getModifiedCount() == 1;
    }

    public boolean resolvePool(String version, String now) {
        // 不清空待发布快照；即使故障很快恢复，历史预警也必须可补发。
        return mongo.updateFirst(Query.query(Criteria.where("_id").is("pool").and("version").is(version)),
                new Update().set("version", UUID.randomUUID().toString()).unset("incidentId")
                        .unset("accountSuccesses").inc("successGeneration", 1).max("lastSuccessAt", now), STATE).getModifiedCount() == 1;
    }

    public List<WeiboAccount> pendingSuccesses() {
        return mongo.find(Query.query(Criteria.where("pendingPoolRecovery").ne(null)), WeiboAccount.class);
    }

    public void preserveRecovery(WeiboAccount account) {
        // 仅删除前保存最后成功代数，不为每次正常请求追加历史行。
        Update update = new Update().max("generation", account.getSuccessGeneration());
        if (account.getLastSuccessAt() != null) update.max("succeededAt",
                com.hidewnd.winds.scout.config.ScoutMongoTimeConverter.FORMATTER.format(account.getLastSuccessAt()));
        mongo.upsert(Query.query(Criteria.where("_id").is(account.getId())), update, "weibo_account_recovery_history");
    }

    public Document recoveryHistory(String accountId) {
        return mongo.findById(accountId, Document.class, "weibo_account_recovery_history");
    }

    public String recoveredAt(String accountId, long generation) {
        Document success = recoveryHistory(accountId);
        return success != null && ((Number) success.getOrDefault("generation", 0L)).longValue() > generation
                ? success.getString("succeededAt") : null;
    }

    public void completeSuccess(WeiboAccount account) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(account.getId())
                        .and("successGeneration").is(account.getSuccessGeneration())),
                new Update().unset("pendingPoolRecovery"), WeiboAccount.class);
    }

    public List<WeiboAccount> pendingAccounts() {
        return mongo.find(Query.query(Criteria.where("pendingAlerts.0").exists(true)), WeiboAccount.class);
    }

    public List<WeiboAlertEvent> pendingPool() {
        Document state = poolState();
        return state.getList("pendingAlerts", Document.class, List.of()).stream()
                .map(value -> mongo.getConverter().read(WeiboAlertEvent.class, value)).toList();
    }

    public void store(WeiboAlertEvent event) {
        Update update = new Update();
        document(event).forEach(update::setOnInsert);
        mongo.upsert(Query.query(Criteria.where("_id").is(event.eventId())), update, ALERTS);
    }

    public void removePending(String accountId, String eventId) {
        Query query = Query.query(Criteria.where("_id").is(accountId == null ? "pool" : accountId));
        Update update = new Update().pull("pendingAlerts", new Document("eventId", eventId));
        if (accountId == null) mongo.updateFirst(query, update, STATE);
        else mongo.updateFirst(query, update, WeiboAccount.class);
    }

    public List<WeiboAlertEvent> pending(String tokenKey, int limit) {
        return mongo.find(Query.query(Criteria.where("acknowledgedBy").ne(tokenKey))
                .with(Sort.by("occurredAt", "_id")).limit(limit), WeiboAlertEvent.class, ALERTS);
    }

    public WeiboAlertEvent find(String id) { return mongo.findById(id, WeiboAlertEvent.class, ALERTS); }

    public void acknowledge(String id, String tokenKey) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().addToSet("acknowledgedBy", tokenKey), ALERTS);
    }

    private Document document(WeiboAlertEvent event) {
        Document document = new Document();
        mongo.getConverter().write(event, document);
        return document;
    }
}
