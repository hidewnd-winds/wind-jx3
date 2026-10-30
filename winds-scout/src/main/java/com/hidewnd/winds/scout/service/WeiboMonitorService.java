package com.hidewnd.winds.scout.service;

import com.hidewnd.winds.scout.model.WeiboPost;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 微博监控轮询服务。
 */
public interface WeiboMonitorService {

    /**
     * 维护轮询租约，按 UID 到期时间异步派发抓取和按序处理任务。
     */
    void poll();

    /** 等待该 UID 的旧抓取与处理结束，再获取并保存静默基线；等待期间不派发该 UID 的新抓取。 */
    void initializeBaseline(String uid, Supplier<Optional<WeiboPost>> fetchBaseline) throws InterruptedException;
}
