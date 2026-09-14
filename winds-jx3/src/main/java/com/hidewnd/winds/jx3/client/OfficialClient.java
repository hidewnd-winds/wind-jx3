package com.hidewnd.winds.jx3.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hidewnd.winds.jx3.model.Article;
import com.hidewnd.winds.jx3.model.ArticleNotification;
import com.hidewnd.winds.jx3.model.PatchInfo;
import com.hidewnd.winds.jx3.model.PatchManifest;
import com.hidewnd.winds.jx3.parser.ArticleParser;
import com.hidewnd.winds.jx3.parser.PatchManifestParser;
import com.hidewnd.winds.jx3.support.Jx3Time;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/** 只访问官方固定来源；清单条件请求缓存由此客户端持有，失败响应不会替换有效缓存。 */
public class OfficialClient implements AutoCloseable {
    private static final String API = "https://jx3.xoyo.com/api.php?op=search_api&";
    private static final URI PATCH_BASE =
            URI.create("https://jx3hdv4qq-autoupdate.xoyocdn.com/jx3hd_v4/zhcn_hd/");
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Executor executor;
    // 列表与通知共享总预算，公平排队避免单一路径持续占满官方正文请求。
    private final Semaphore articleRequests = new Semaphore(8, true);
    private final Map<URI, CachedResponse> cache = new ConcurrentHashMap<>();

    public OfficialClient(ObjectMapper mapper, Executor executor) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                mapper, executor);
    }

    OfficialClient(HttpClient http, ObjectMapper mapper, Executor executor) {
        this.http = http;
        this.mapper = mapper;
        this.executor = executor;
    }

    public PatchManifest fetchPatchManifest() {
        return PatchManifestParser.parse(
                new String(
                        get(PATCH_BASE.resolve("autoupdateentry.txt"), true),
                        StandardCharsets.UTF_8));
    }

    public String fetchServerList() {
        byte[] content =
                get(
                        URI.create(
                                "https://jx3comm.xoyocdn.com/jx3hd/zhcn_hd/serverlist/serverlist.ini"),
                        true);
        try {
            // 官网清单为 GBK；必须按源编码解码，不能依赖 Ubuntu/Docker 默认 UTF-8。
            return Charset.forName("GBK").newDecoder().decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException("官方区服清单不是有效 GBK 数据", exception);
        }
    }

    public PatchInfo fetchPatchInfo(String fileName) {
        if (!fileName.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("非法补丁文件名");
        }
        HttpResponse<byte[]> response =
                request(
                        HttpRequest.newBuilder(PATCH_BASE.resolve(fileName))
                                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                                .timeout(Duration.ofSeconds(10))
                                .build());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("补丁尚不可用，HTTP " + response.statusCode());
        }
        long length =
                response.headers()
                        .firstValueAsLong("Content-Length")
                        .orElseThrow(() -> new IllegalStateException("补丁缺少大小"));
        if (length <= 0) {
            throw new IllegalStateException("补丁大小无效");
        }
        String modified =
                response.headers()
                        .firstValue("Last-Modified")
                        .orElseThrow(() -> new IllegalStateException("补丁缺少官方文件更新时间"));
        try {
            Instant updated =
                    ZonedDateTime.parse(
                                    modified, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant();
            return new PatchInfo(length, updated);
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalStateException("补丁文件更新时间无效", exception);
        }
    }

    /** 分页回看至上轮扫描前或七日边界；按发布时间排序，置顶条目不作为停止依据。 */
    public List<Article> fetchArticles(boolean maintenance, Instant since) {
        List<Article> articles = new ArrayList<>();
        fetchArticles(maintenance, since, articles::add);
        return articles;
    }

    /** 监听使用逐篇回调，已经完成的文章不等待同页其他正文。 */
    public void fetchArticles(boolean maintenance, Instant since, Consumer<Article> consumer) {
        Set<String> seen = new HashSet<>();
        // 对应官网最新消息的三个栏目：0 为公告，2458 为新闻，2461 为活动。
        // 保留 maintenance 参数作为现有公告通道标识，分类不再依赖标题关键词。
        List<String> sources = maintenance
                ? List.of("action=get_customer_article_list&game=jx3&order=auto")
                : List.of(
                        "action=get_article_list&catid=2458&order_by=inputtime&sort_by=desc",
                        "action=get_article_list&catid=2461&order_by=inputtime&sort_by=desc");
        RuntimeException failure = null;
        for (String source : sources) {
            // 各栏目更新速度不同，必须独立分页，避免活动被新闻的时间边界挡住。
            try {
                fetchArticleList(source, maintenance, since, consumer, seen);
            } catch (RuntimeException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    throw exception;
                }
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void fetchArticleList(
            String query, boolean maintenance, Instant since, Consumer<Article> consumer, Set<String> seen) {
        Set<String> pageSeen = new HashSet<>();
        RuntimeException failure = null;
        for (int page = 1; page <= 100; page++) {
            JsonNode data = json(URI.create(API + query + "&num=30&page=" + page));
            JsonNode list = data.path("list");
            if (!list.isArray() && !list.isObject()) {
                throw new IllegalStateException("文章列表格式变化");
            }
            if (list.isEmpty()) {
                break;
            }
            boolean reachedBoundary = false;
            int newItems = 0;
            List<JsonNode> items = new ArrayList<>();
            for (JsonNode item : list) {
                String id = item.path("id").asText();
                if (!id.matches("\\d+")) {
                    throw new IllegalStateException("文章 ID 无效");
                }
                if (!pageSeen.add(id)) {
                    continue;
                }
                newItems++;
                String published = ArticleParser.parseTimestamp(item.get("inputtime"));
                if (published == null) {
                    published = ArticleParser.parseTimestamp(item.get("asktime"));
                }
                boolean old =
                        since != null
                                && published != null
                                && Jx3Time.parse(published).isBefore(since);
                if (old && !item.path("top").asText("0").equals("1")) {
                    reachedBoundary = true;
                }
                String category = item.path("catid").asText();
                if (!category.matches("\\d+")) {
                    throw new IllegalStateException("文章栏目 ID 无效");
                }
                boolean customer = category.equals("0");
                if (maintenance != customer || (maintenance && old) || !seen.add(id)) {
                    continue;
                }
                items.add(item);
            }
            try {
                fetchArticleDetails(items, maintenance, consumer);
            } catch (RuntimeException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    throw exception;
                }
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (since == null || reachedBoundary || list.size() < 30) {
                break;
            }
            if (newItems == 0) {
                throw new IllegalStateException("官网分页重复，停止本轮采集");
            }
            if (page == 100) {
                throw new IllegalStateException("官网分页超过上限，本轮不更新基线");
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** 只从通知提取数字身份，固定请求官网接口，不访问第三方载荷中的任意 URL。 */
    public Article fetchArticle(ArticleNotification notification) {
        ObjectNode item = mapper.createObjectNode()
                .put("id", notification.articleId()).put("catid", notification.categoryId());
        return fetchArticleDetail(item, notification.maintenance());
    }

    private Article fetchArticleDetail(JsonNode item, boolean maintenance) {
        try {
            articleRequests.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("文章采集等待已中断", exception);
        }
        try {
            String id = item.path("id").asText();
            String category = item.path("catid").asText();
            String query = maintenance ? "action=get_customer_article_detail&game=jx3&kid=" + id
                    : "action=get_article_detail&catid=" + category + "&id=" + id;
            JsonNode detail = json(URI.create(API + query));
            if (!maintenance) {
                detail = detail.isArray() && !detail.isEmpty() ? detail.get(0) : null;
            }
            if (detail == null || !detail.isObject() || !detail.hasNonNull("content")) {
                throw new IllegalStateException("文章详情缺失，文章=" + id);
            }
            ObjectNode merged = ((ObjectNode) item).deepCopy();
            merged.setAll((ObjectNode) detail);
            // 身份来自已校验的列表/链接；详情的空时间不能覆盖列表中的官方时间。
            merged.put("id", id).put("catid", category);
            for (String field : List.of("inputtime", "asktime")) {
                if (ArticleParser.parseTimestamp(merged.get(field)) == null
                        && ArticleParser.parseTimestamp(item.get(field)) != null) {
                    merged.set(field, item.get(field));
                }
            }
            return ArticleParser.parse(merged, detail.get("content").asText(), maintenance);
        } finally {
            articleRequests.release();
        }
    }

    /** 每轮最多六个在途正文请求；完成一个补一个，慢请求和单篇失败不阻塞其他结果交付。 */
    private void fetchArticleDetails(List<JsonNode> items, boolean maintenance, Consumer<Article> consumer) {
        var completed = new ExecutorCompletionService<Article>(executor);
        Set<Future<Article>> pending = new HashSet<>();
        int submitted = 0;
        RuntimeException failure = null;
        try {
            while (submitted < items.size() || !pending.isEmpty()) {
                while (submitted < items.size() && pending.size() < 6) {
                    JsonNode item = items.get(submitted++);
                    pending.add(completed.submit(() -> fetchArticleDetail(item, maintenance)));
                }
                Future<Article> future = completed.take();
                pending.remove(future);
                try {
                    consumer.accept(future.get());
                } catch (ExecutionException | RuntimeException exception) {
                    if (failure == null) {
                        failure = new IllegalStateException("部分文章处理失败，本轮不更新基线");
                    }
                    failure.addSuppressed(exception);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("文章采集已中断", exception);
        } finally {
            pending.forEach(future -> future.cancel(true));
        }
        if (failure != null) {
            throw failure;
        }
    }

    private JsonNode json(URI uri) {
        try {
            JsonNode root = mapper.readTree(get(uri, false));
            if (root.path("code").asInt() != 1 || !root.hasNonNull("data")) {
                throw new IllegalStateException("官网接口返回失败");
            }
            return root.get("data");
        } catch (IOException exception) {
            throw new IllegalStateException("官网 JSON 解析失败", exception);
        }
    }

    byte[] get(URI uri, boolean conditional) {
        CachedResponse previous = conditional ? cache.get(uri) : null;
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET();
        if (previous != null) {
            if (previous.etag() != null) {
                builder.header("If-None-Match", previous.etag());
            }
            if (previous.modified() != null) {
                builder.header("If-Modified-Since", previous.modified());
            }
        }
        HttpResponse<byte[]> response = request(builder.build());
        if (response.statusCode() == 304 && previous != null) {
            return previous.body();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("官网请求失败，HTTP " + response.statusCode());
        }
        if (conditional) {
            cache.put(
                    uri,
                    new CachedResponse(
                            response.body(),
                            response.headers().firstValue("ETag").orElse(null),
                            response.headers().firstValue("Last-Modified").orElse(null)));
        }
        return response.body();
    }

    private HttpResponse<byte[]> request(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("官网采集已中断", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("官网连接失败", exception);
        }
    }

    @Override
    public void close() {
        http.close();
    }

    private record CachedResponse(byte[] body, String etag, String modified) {}
}
