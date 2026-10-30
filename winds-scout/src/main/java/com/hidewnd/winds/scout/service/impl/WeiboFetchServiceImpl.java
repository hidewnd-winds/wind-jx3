package com.hidewnd.winds.scout.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hidewnd.winds.scout.exception.WeiboCookieUpdateException;
import com.hidewnd.winds.scout.exception.WeiboAccountInvalidException;
import com.hidewnd.winds.scout.exception.ScoutApiException;
import com.hidewnd.winds.scout.model.WeiboAccount;
import com.hidewnd.winds.scout.model.WeiboPost;
import com.hidewnd.winds.scout.model.WeiboUserProfile;
import com.hidewnd.winds.scout.repository.WeiboAccountCookieRepository;
import com.hidewnd.winds.scout.service.WeiboFetchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微博移动端接口抓取服务实现，负责请求最新微博并补全长文本。
 */
@Service
@Slf4j
public class WeiboFetchServiceImpl implements WeiboFetchService {

    private static final String API_URL = "https://m.weibo.cn/api/container/getIndex";
    private static final String USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1";

    // 所有微博入口共享同一实例的名额，处理响应和等待下一次派发均不持有名额。
    private final Semaphore requestSlots = new Semaphore(2, true);
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final RestClient restClient;
    private final WeiboResponseParser parser;
    private final WeiboCookieStore cookieStore;
    private final WeiboAccountCookieRepository cookieRepository;

    public WeiboFetchServiceImpl(
            @Qualifier("weiboRestClient") RestClient restClient,
            WeiboResponseParser parser,
            WeiboCookieStore cookieStore,
            WeiboAccountCookieRepository cookieRepository, ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.restClient = restClient;
        this.parser = parser;
        this.cookieStore = cookieStore;
        this.cookieRepository = cookieRepository;
    }

    @Override
    public WeiboUserProfile fetchUserProfile(String uid, WeiboAccount account) {
        if (uid == null || !uid.matches("\\d+")) {
            throw new IllegalArgumentException("微博UID仅支持数字");
        }
        // 不指定推文 containerid，读取主页 userInfo；空时间线不代表博主不存在。
        JsonNode response = getJson(URI.create(API_URL + "?type=uid&value=" + uid), account, headers -> {
            headers.set(HttpHeaders.ACCEPT, "application/json");
            headers.set(HttpHeaders.REFERER, "https://m.weibo.cn/u/" + uid);
            headers.set("x-requested-with", "XMLHttpRequest");
        });
        if (response == null || response.path("ok").asInt() != 1) {
            throw new IllegalStateException("微博博主资料响应异常");
        }
        JsonNode user = response.path("data").path("userInfo");
        String screenName = user.path("screen_name").asText("");
        String avatar = user.path("avatar_hd").asText("");
        if (avatar.isBlank()) {
            avatar = user.path("profile_image_url").asText("");
        }
        if (!uid.equals(user.path("id").asText()) || screenName.isBlank() || avatar.isBlank()) {
            throw new IllegalStateException("微博博主资料缺失或UID不匹配");
        }
        return new WeiboUserProfile(screenName, avatar);
    }

    @Override
    public Optional<String> findUidByScreenName(String screenName, WeiboAccount account) {
        if (screenName == null || screenName.isBlank()) {
            throw new IllegalArgumentException("微博博主全称不能为空");
        }
        URI uri = UriComponentsBuilder.fromHttpUrl(API_URL)
                .queryParam("containerid", "{container}").queryParam("page_type", "searchall")
                .encode().buildAndExpand("100103type=3&q=" + screenName + "&t=0").toUri();
        JsonNode response = getJson(uri, account, headers -> {
            headers.set(HttpHeaders.ACCEPT, "application/json");
            headers.set(HttpHeaders.REFERER, "https://m.weibo.cn/");
            headers.set("x-requested-with", "XMLHttpRequest");
        });
        if (response == null || response.path("ok").asInt() != 1
                || !response.path("data").path("cards").isArray()) {
            throw new IllegalStateException("微博用户搜索响应异常");
        }
        Set<String> matches = new LinkedHashSet<>();
        // 搜索结果可能把同一用户放在多个卡片中；只接受全称完全相等的用户，不猜测相似名称。
        for (JsonNode user : response.path("data").path("cards").findValues("user")) {
            if (screenName.equals(user.path("screen_name").asText())) {
                String uid = user.path("id").asText("");
                if (!uid.matches("\\d+")) {
                    throw new IllegalStateException("微博用户搜索返回无效UID");
                }
                matches.add(uid);
            }
        }
        if (matches.size() > 1) {
            throw new ScoutApiException(HttpStatus.CONFLICT, "存在多个同名微博博主，请使用UID");
        }
        return matches.stream().findFirst();
    }

    @Override
    public Optional<WeiboPost> fetchLatest(String uid, String fallbackScreenName, WeiboAccount account) {
        return fetchLatest(uid, fallbackScreenName, account, null);
    }

    @Override
    public Optional<WeiboPost> fetchLatest(String uid, String fallbackScreenName, WeiboAccount account,
                                          Consumer<Instant> onRequestStarted) {
        if (uid == null || !uid.matches("\\d+")) {
            throw new IllegalArgumentException("微博UID仅支持数字");
        }
        URI uri = URI.create(API_URL + "?type=uid&value=" + uid + "&containerid=107603" + uid);
        JsonNode response = getJson(uri, account, headers -> {
            headers.set(HttpHeaders.ACCEPT, "*/*");
            headers.set(HttpHeaders.REFERER, "https://m.weibo.cn/u/" + uid);
            headers.set("x-requested-with", "XMLHttpRequest");
        }, onRequestStarted);
        if (response == null) {
            throw new IllegalStateException("微博接口返回空响应");
        }
        if (response.path("ok").asInt() != 1) {
            String message = response.path("msg").asText("");
            if (message.isBlank() || "这里还没有内容".equals(message)) {
                return Optional.empty();
            }
            throw new IllegalStateException("微博接口返回异常");
        }
        long selectStarted = System.nanoTime();
        Optional<ObjectNode> latestCard = parser.selectLatestCard(response);
        log.info("微博列表筛选耗时，uid={}，selectMs={}", uid,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - selectStarted));
        if (latestCard.isEmpty()) {
            return Optional.empty();
        }
        JsonNode mblog = latestCard.get().path("mblog");
        // 与解析器的转发深度一致，原文和每层转发分别补全，避免媒体串到外层微博。
        for (int depth = 0; depth <= 5 && mblog.isObject(); depth++) {
            completeLongText(mblog, account);
            completeArticle(mblog, account);
            mblog = mblog.path("retweeted_status");
        }
        long contentStarted = System.nanoTime();
        try {
            WeiboPost post = parser.parseCard(latestCard.get(), uid);
            if (!post.screenName().isBlank() || fallbackScreenName == null) {
                return Optional.of(post);
            }
            return Optional.of(new WeiboPost(
                    post.uid(), fallbackScreenName, post.weiboId(), post.publishedAt(), post.content(),
                    post.rawContent(), post.source(), post.regionName(), post.repostsCount(), post.commentsCount(),
                    post.attitudesCount(), post.url(),
                    post.images(), post.topics(), post.videoCoverImages(), post.retweet(),
                    post.media(), post.links(), post.article(), post.truncated()));
        } finally {
            log.info("微博内容解析耗时，uid={}，contentParseMs={}", uid,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - contentStarted));
        }
    }

    private void completeLongText(JsonNode mblog, WeiboAccount account) {
        if (!(mblog instanceof ObjectNode objectNode)
                || !WeiboResponseParser.needsExtendedText(mblog)) {
            return;
        }
        String id = mblog.path("idstr").asText(mblog.path("id").asText(""));
        if (!id.matches("\\d+")) {
            log.warn("微博长文无法补全：微博 ID 无效");
            return;
        }
        JsonNode response;
        try {
            response = getJson(
                    URI.create("https://m.weibo.cn/statuses/extend?id=" + id),
                    account,
                    headers -> headers.set(HttpHeaders.ACCEPT, "*/*"));
        } catch (RestClientException exception) {
            log.warn("微博长文请求失败，weiboId={}，异常类型={}", id, exception.getClass().getSimpleName());
            return;
        }
        if (response == null || response.path("ok").asInt() != 1) {
            log.warn("微博长文响应无效，weiboId={}", mblog.path("id").asText());
            return;
        }
        JsonNode data = response.path("data");
        for (String field : new String[]{"longTextContent", "fullText", "text", "content"}) {
            String content = data.path(field).asText("");
            if (!content.isBlank()) {
                objectNode.put("text", content);
                ObjectNode longText = objectNode.path("longText") instanceof ObjectNode existing
                        ? existing : objectNode.putObject("longText");
                longText.put("longTextContent", content);
                // 全文接口可能同时返回完整配图、视频及短链映射，只合并有值的已知内容字段。
                for (String metadata : new String[]{"pics", "pic_ids", "pic_infos", "page_info",
                        "mix_media_info", "url_struct", "topic_struct"}) {
                    JsonNode value = data.path(metadata);
                    if (value.isContainerNode() && !value.isEmpty()) {
                        objectNode.set(metadata, value);
                    }
                }
                return;
            }
        }
        log.warn("微博长文响应缺少正文，weiboId={}", id);
    }

    private void completeArticle(JsonNode mblog, WeiboAccount account) {
        if (!(mblog instanceof ObjectNode objectNode)
                || !"article".equals(mblog.path("page_info").path("type").asText())
                || !mblog.path("article").path("content").asText("").isBlank()) {
            return;
        }
        String pageUrl = mblog.path("page_info").path("page_url").asText("");
        Matcher match = Pattern.compile("(?:[?&]id=|/id/)(\\d+)(?:[&#/?]|$)").matcher(pageUrl);
        if (!match.find()) {
            log.warn("微博文章链接缺少文章 ID，保留卡片摘要");
            return;
        }
        String id = match.group(1);
        try {
            // 仅从卡片提取数字 ID；请求地址固定，绝不携带 Cookie 跟随任意卡片链接。
            JsonNode response = getJson(URI.create("https://card.weibo.com/article/m/aj/detail?id=" + id), account,
                    headers -> headers.set(HttpHeaders.REFERER, "https://card.weibo.com/article/m/show/id/" + id));
            if (response != null && response.path("data").isObject()
                    && !response.path("data").path("content").asText("").isBlank()) {
                objectNode.set("article", response.path("data"));
            } else {
                log.warn("微博文章响应无完整正文，articleId={}，保留卡片摘要", id);
            }
        } catch (RestClientException exception) {
            log.warn("微博文章请求失败，articleId={}，异常类型={}", id, exception.getClass().getSimpleName());
        }
    }

    private JsonNode getJson(URI uri, WeiboAccount account, Consumer<HttpHeaders> headersCustomizer) {
        return getJson(uri, account, headersCustomizer, null);
    }

    private JsonNode getJson(URI uri, WeiboAccount account, Consumer<HttpHeaders> headersCustomizer,
                             Consumer<Instant> onRequestStarted) {
        String cookieHeader = cookieStore.buildCookieHeader(account, uri);
        String xsrfToken = cookieStore.getXsrfToken(account, uri);
        long waitingAt = System.nanoTime();
        long waitNanos = 0;
        long httpNanos = 0;
        long parseNanos = 0;
        long cookieNanos = 0;
        boolean success = false;
        try {
            try {
                requestSlots.acquire();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new CancellationException("微博请求等待已中断");
            } finally {
                waitNanos = System.nanoTime() - waitingAt;
            }
            ResponseEntity<byte[]> response = null;
            RestClientResponseException httpFailure = null;
            long httpStarted = System.nanoTime();
            try {
                var request = restClient.get().uri(uri).headers(headers -> {
                    headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
                    if (!cookieHeader.isBlank()) headers.set(HttpHeaders.COOKIE, cookieHeader);
                    if (!xsrfToken.isBlank()) headers.set("x-xsrf-token", xsrfToken);
                    headersCustomizer.accept(headers);
                });
                if (onRequestStarted != null) onRequestStarted.accept(clock.instant());
                response = request.retrieve().toEntity(byte[].class);
            } catch (RestClientResponseException exception) {
                httpFailure = exception;
            } finally {
                httpNanos = System.nanoTime() - httpStarted;
                requestSlots.release();
            }

            // 先完整接收字节并释放 HTTP 名额，再解析与写入 Cookie；错误响应同样遵守此边界。
            JsonNode body = null;
            boolean invalidAccount = httpFailure != null && httpFailure.getStatusCode().value() == 401;
            if (httpFailure == null) {
                long parseStarted = System.nanoTime();
                try {
                    byte[] bytes = response.getBody();
                    body = bytes == null || bytes.length == 0 ? null : objectMapper.readTree(bytes);
                } catch (IOException exception) {
                    throw new RestClientException("微博响应 JSON 无效", exception);
                } finally {
                    parseNanos = System.nanoTime() - parseStarted;
                }
                // 列表和补全共用失效判定，不能把失效账号当成长文降级成功。
                if (body != null && body.path("ok").asInt() != 1) {
                    String message = body.path("msg").asText("");
                    invalidAccount = message.contains("请先登录") || message.contains("请重新登录")
                            || message.contains("登录失效") || message.contains("登录已过期")
                            || message.contains("未登录");
                }
            }
            long cookieStarted = System.nanoTime();
            try {
                updateResponseState(account, uri,
                        httpFailure == null ? response.getHeaders() : httpFailure.getResponseHeaders(), invalidAccount);
            } finally {
                cookieNanos = System.nanoTime() - cookieStarted;
            }
            if (httpFailure != null) throw httpFailure;
            success = true;
            return body;
        } finally {
            log.info("微博请求耗时，accountId={}，endpoint={}，slotWaitMs={}，httpMs={}，jsonParseMs={}，cookieMs={}，success={}",
                    account.getId(), uri.getHost() + uri.getPath(), TimeUnit.NANOSECONDS.toMillis(waitNanos),
                    TimeUnit.NANOSECONDS.toMillis(httpNanos), TimeUnit.NANOSECONDS.toMillis(parseNanos),
                    TimeUnit.NANOSECONDS.toMillis(cookieNanos), success);
        }
    }

    private void updateResponseState(WeiboAccount account, URI uri, HttpHeaders responseHeaders, boolean invalidAccount) {
        WeiboCookieUpdateException cookieFailure = null;
        try {
            if (responseHeaders != null
                    && cookieStore.applyResponse(account, uri, responseHeaders.get(HttpHeaders.SET_COOKIE))) {
                cookieRepository.save(account);
            }
        } catch (RuntimeException exception) {
            cookieFailure = new WeiboCookieUpdateException("微博Cookie状态保存失败", exception);
        }
        // 已明确失效时仍必须触发停用；Cookie 保存故障附在异常上，避免丢失故障信息。
        if (invalidAccount) {
            WeiboAccountInvalidException invalid = new WeiboAccountInvalidException();
            if (cookieFailure != null) {
                invalid.addSuppressed(cookieFailure);
            }
            throw invalid;
        }
        if (cookieFailure != null) {
            throw cookieFailure;
        }
    }
}
