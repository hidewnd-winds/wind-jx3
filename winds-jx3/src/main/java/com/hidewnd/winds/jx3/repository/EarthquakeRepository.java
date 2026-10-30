package com.hidewnd.winds.jx3.repository;

import com.hidewnd.winds.jx3.model.EarthquakeReport;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.Date;

/** 独立集合持久保存地震档案，利用 Mongo 的 _id 唯一性和条件更新登记发布资格。 */
public class EarthquakeRepository {
    private static final String COLLECTION = "jx3_earthquakes";
    private static final String BASELINE = "state:ceic-baseline";
    private final MongoTemplate mongo;

    public EarthquakeRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public boolean baselineReady() {
        return mongo.exists(Query.query(Criteria.where("_id").is(BASELINE)), COLLECTION);
    }

    public void finishBaseline(Instant now) {
        mongo.upsert(Query.query(Criteria.where("_id").is(BASELINE)),
                new Update().setOnInsert("completedAt", Date.from(now)), COLLECTION);
    }

    public void archive(EarthquakeReport report, boolean baseline, Instant now) {
        Document observation = new Document("source", report.source())
                .append("sourceId", report.sourceId()).append("raw", Document.parse(report.raw().toString()));
        Update update = new Update()
                .setOnInsert("firstSeenAt", Date.from(now))
                .setOnInsert("baseline", baseline)
                .setOnInsert("pushRegistered", false)
                .set("lastSeenAt", Date.from(now))
                .set("data", Document.parse(report.data().toString()))
                .addToSet("observations", observation);
        mongo.upsert(Query.query(Criteria.where("_id").is(report.identity())), update, COLLECTION);
    }

    public boolean claimPush(EarthquakeReport report, Instant now) {
        // 先登记后广播：同一身份至多发布一次，发送失败也不自动重发，避免群内重复。
        var result = mongo.updateFirst(Query.query(Criteria.where("_id").is(report.identity())
                        .and("baseline").is(false).and("pushRegistered").is(false)),
                new Update().set("pushRegistered", true).set("pushRegisteredAt", Date.from(now))
                        .set("pushSource", report.source()), COLLECTION);
        return result.getModifiedCount() == 1;
    }
}
