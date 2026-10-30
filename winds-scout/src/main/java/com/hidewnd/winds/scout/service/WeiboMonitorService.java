package com.hidewnd.winds.scout.service;

/**
 * 微博监控轮询服务。
 */
public interface WeiboMonitorService {

    /**
     * 维护轮询租约，按 UID 到期时间异步派发抓取和按序处理任务。
     */
    void poll();
}
