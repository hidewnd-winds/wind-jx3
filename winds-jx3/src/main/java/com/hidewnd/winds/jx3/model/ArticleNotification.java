package com.hidewnd.winds.jx3.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/** 第三方文章通知仅提供发现线索；身份来自官方链接，完整内容与发布时间仍从官网获取。 */
public record ArticleNotification(String articleId, String categoryId) {
    private static final Pattern ANNOUNCEMENT = Pattern.compile(
            "https?://jx3\\.xoyo\\.com/announce/gg\\.html\\?id=(\\d+)");
    private static final Pattern NEWS = Pattern.compile(
            "https?://jx3\\.xoyo\\.com/show-(2458|2461)-(\\d+)-\\d+\\.html");
    private static final Pattern ARTICLE_PAGE = Pattern.compile(
            "https?://jx3\\.xoyo\\.com/index/(?:index\\.html)?#/article-details\\?(?:kid=(\\d+)|catid=(2458|2461)&id=(\\d+))");

    public ArticleNotification {
        if (articleId == null || !articleId.matches("[0-9]{1,20}")
                || !("0".equals(categoryId) || "2458".equals(categoryId) || "2461".equals(categoryId))) {
            throw new IllegalArgumentException("文章通知身份无效");
        }
    }

    public static ArticleNotification from(JsonNode detail) {
        String type = detail.path("type").textValue();
        String title = detail.path("title").textValue();
        String url = detail.path("url").textValue();
        String date = detail.path("date").textValue();
        if (!("官方公告".equals(type) || "官方新闻".equals(type))
                || title == null || title.isBlank() || url == null
                || date == null || !date.matches("[0-9]{2}/[0-9]{2}")) {
            throw new IllegalArgumentException("文章通知字段无效");
        }
        // MM/dd 不含年份和时分秒，只验证格式，不能当作 publishedAt 或用来判定消息过期。
        MonthDay.parse(date, DateTimeFormatter.ofPattern("MM/dd"));
        ArticleNotification result;
        var announcement = ANNOUNCEMENT.matcher(url);
        var news = NEWS.matcher(url);
        var page = ARTICLE_PAGE.matcher(url);
        if (announcement.matches()) {
            result = new ArticleNotification(announcement.group(1), "0");
        } else if (news.matches()) {
            result = new ArticleNotification(news.group(2), news.group(1));
        } else if (page.matches()) {
            result = page.group(1) != null ? new ArticleNotification(page.group(1), "0")
                    : new ArticleNotification(page.group(3), page.group(2));
        } else {
            throw new IllegalArgumentException("文章通知不是受支持的官方链接");
        }
        if (result.maintenance() != "官方公告".equals(type)) {
            throw new IllegalArgumentException("文章通知类型与链接不匹配");
        }
        return result;
    }

    public boolean maintenance() {
        return categoryId.equals("0");
    }
}
