package com.hidewnd.winds.scout.service;

import com.hidewnd.winds.scout.model.WeiboAccount;
import com.hidewnd.winds.scout.model.WeiboPost;
import com.hidewnd.winds.scout.model.WeiboUserProfile;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 微博内容抓取服务。
 */
public interface WeiboFetchService {

    /** 按 UID 查询主页资料，不依赖博主是否发布过微博。 */
    WeiboUserProfile fetchUserProfile(String uid, WeiboAccount account);

    /** 使用微博用户搜索精确匹配全称并返回 UID；无匹配返回空，歧义明确报错。 */
    Optional<String> findUidByScreenName(String screenName, WeiboAccount account);

    /**
     * 使用指定抓取账号查询博主最新一条非置顶微博，并补全长文本内容。
     *
     * @param uid                微博 UID
     * @param fallbackScreenName 接口未返回昵称时使用的备用昵称
     * @param account            本次请求使用的抓取账号
     * @return 最新微博；接口无内容或没有可用微博时返回空
     */
    Optional<WeiboPost> fetchLatest(String uid, String fallbackScreenName, WeiboAccount account);
    /** 监听专用：取得 HTTP 名额后、发送首个列表请求前通知实际开始时间。 */
    Optional<WeiboPost> fetchLatest(String uid, String fallbackScreenName, WeiboAccount account,
                                   Consumer<Instant> onRequestStarted);
}
