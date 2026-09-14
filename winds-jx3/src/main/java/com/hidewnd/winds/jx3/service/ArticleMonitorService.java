package com.hidewnd.winds.jx3.service;

import com.hidewnd.winds.jx3.model.ArticleNotification;
import java.util.concurrent.CompletableFuture;

/** 监听服务契约；调度层只依赖接口。 */
public interface ArticleMonitorService {
    void poll();

    CompletableFuture<Void> verifyArticle(ArticleNotification notification);
}
