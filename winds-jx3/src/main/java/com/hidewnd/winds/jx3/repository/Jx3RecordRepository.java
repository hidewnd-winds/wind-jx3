package com.hidewnd.winds.jx3.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hidewnd.winds.jx3.event.Jx3Event;
import com.hidewnd.winds.jx3.support.Jx3Time;

import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/** 文章按身份唯一保存，其他状态保留修订；业务数据和事件同文档原子写入。 */
public class Jx3RecordRepository {
    private static final String COLLECTION = "jx3_records";
    private static final String ARTICLES = "jx3_articles";
    private final MongoTemplate mongo;
    private final ObjectMapper mapper;
    private boolean indexed;

    public Jx3RecordRepository(MongoTemplate mongo, ObjectMapper mapper) {
        this.mongo = mongo;
        this.mapper = mapper;
    }

    private synchronized void ensureIndex() {
        if (!indexed) {
            mongo.indexOps(COLLECTION)
                    .ensureIndex(
                            new Index()
                                    .on("key", Sort.Direction.ASC)
                                    .on("revision", Sort.Direction.DESC)
                                    .unique());
            indexed = true;
        }
    }

    private Document latest(String key) {
        ensureIndex();
        return mongo.findOne(
                Query.query(Criteria.where("key").is(key))
                        .with(Sort.by(Sort.Direction.DESC, "revision")),
                Document.class,
                COLLECTION);
    }

    public ObjectNode load(String key) {
        if (key.startsWith("news:") || key.startsWith("maintenance:")) {
            Document article = mongo.findById(key, Document.class, ARTICLES);
            if (article == null) {
                return null;
            }
            ObjectNode state = mapper.valueToTree(article);
            state.remove(List.of("_id", "recordedAt", "event", "pushPending", "pushedAt",
                    "pushClaimToken", "pushClaimUntil"));
            return state;
        }
        Document record = latest(key);
        return record == null ? null : mapper.valueToTree(record.get("state"));
    }

    public boolean save(String key, ObjectNode state, Jx3Event event) {
        if (key.startsWith("news:") || key.startsWith("maintenance:")) {
            return saveArticle(key, state, event);
        }
        Document previous = latest(key);
        Document storedState = Document.parse(state.toString());
        if (previous != null && previous.get("state").equals(storedState)) {
            return false;
        }
        long revision = previous == null ? 1 : ((Number) previous.get("revision")).longValue() + 1;
        Document record =
                new Document("_id", key + ":" + revision)
                        .append("key", key)
                        .append("revision", revision)
                        .append("state", storedState)
                        .append("recordedAt", Jx3Time.format(java.time.Instant.now()))
                        .append(
                                "event",
                                event == null
                                        ? null
                                        : Document.parse(mapper.valueToTree(event).toString()));
        mongo.insert(record, COLLECTION);
        return true;
    }

    private boolean saveArticle(String key, ObjectNode state, Jx3Event event) {
        if (!state.hasNonNull("publishedAt")) {
            throw new IllegalArgumentException("文章缺少有效的官方发布时间");
        }
        try {
            Jx3Time.parse(state.get("publishedAt").asText());
        } catch (java.time.DateTimeException exception) {
            throw new IllegalArgumentException("文章缺少有效的官方发布时间", exception);
        }
        ObjectNode previous = load(key);
        if (previous != null) {
            ObjectNode businessNow = state.deepCopy();
            previous.remove("updatedAt");
            businessNow.remove("updatedAt");
            if (previous.equals(businessNow)) {
                return false;
            }
        }
        // _id 唯一索引约束文章身份，内容变化只更新原记录，不再新增修订。
        Update update = new Update();
        Document.parse(state.toString()).forEach(update::set);
        // 首轮基线不能清除上次进程留下的待发送事件；内容变化也不能清除成功标记。
        if (event != null && !articlePushed(key)) {
            update.set("event", Document.parse(mapper.valueToTree(event).toString()));
            update.set("pushPending", true);
        }
        update.setOnInsert("recordedAt", Jx3Time.format(java.time.Instant.now()));
        var result = mongo.upsert(Query.query(Criteria.where("_id").is(key)), update, ARTICLES);
        return result.getUpsertedId() != null || result.getModifiedCount() > 0;
    }

    private boolean articlePushed(String key) {
        return mongo.exists(Query.query(Criteria.where("_id").is(key).and("pushedAt").ne(null)), ARTICLES);
    }

    public List<Jx3Event> pendingArticleEvents(boolean maintenance) {
        // 独立于官网回看窗口重试，且不把历史上仅保存过的事件误认作待发送或发送成功。
        Query query = Query.query(Criteria.where("pushPending").is(true).and("pushedAt").is(null)
                .and("event.type").is(maintenance ? "jx3.maintenance.updated" : "jx3.news.updated"))
                .with(Sort.by(Sort.Direction.ASC, "recordedAt")).limit(100);
        return mongo.find(query, Document.class, ARTICLES).stream()
                .map(record -> mapper.convertValue(record.get("event"), Jx3Event.class))
                .toList();
    }

    public boolean claimArticlePush(String key, String token, Instant now) {
        // 同一文章的不同事件也共用数据库占用，避免异步监听或多实例同时发送。
        Criteria available = Criteria.where("_id").is(key).and("pushedAt").is(null)
                .orOperator(Criteria.where("pushClaimUntil").is(null),
                        Criteria.where("pushClaimUntil").lte(Date.from(now)));
        Update claim = new Update().set("pushClaimToken", token)
                .set("pushClaimUntil", Date.from(now.plus(Duration.ofMinutes(5))));
        return mongo.updateFirst(Query.query(available), claim, ARTICLES).getModifiedCount() == 1;
    }

    public void finishArticlePush(String key, String token, boolean sent, Instant now) {
        Update result = new Update().unset("pushClaimToken").unset("pushClaimUntil");
        if (sent) {
            result.set("pushedAt", Date.from(now)).unset("pushPending");
        }
        var updated = mongo.updateFirst(
                Query.query(Criteria.where("_id").is(key).and("pushClaimToken").is(token)), result, ARTICLES);
        if (updated.getMatchedCount() != 1) {
            throw new IllegalStateException("文章推送占用已失效，无法确认发送结果：" + key);
        }
    }
}
