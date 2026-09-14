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
            state.remove(java.util.List.of("_id", "recordedAt", "event"));
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

    /** 冷启动尚无官方清单时，按通知身份查找已登记的开服时间，避免重复广播。 */
    public java.time.Instant lastServerOpening(String zone, String server) {
        var criteria = Criteria.where("state.server.zoneName").is(zone)
                .and("state.lastOpeningAt").exists(true)
                .orOperator(Criteria.where("state.server.serverName").is(server),
                        Criteria.where("state.server.aliases").is(server));
        Document record = mongo.findOne(Query.query(criteria)
                .with(Sort.by(Sort.Direction.DESC, "state.lastOpeningAt")), Document.class, COLLECTION);
        return record == null ? null
                : Jx3Time.parse(record.get("state", Document.class).getString("lastOpeningAt"));
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
        update.set(
                "event",
                event == null ? null : Document.parse(mapper.valueToTree(event).toString()));
        update.setOnInsert("recordedAt", Jx3Time.format(java.time.Instant.now()));
        var result = mongo.upsert(Query.query(Criteria.where("_id").is(key)), update, ARTICLES);
        return result.getUpsertedId() != null || result.getModifiedCount() > 0;
    }
}
