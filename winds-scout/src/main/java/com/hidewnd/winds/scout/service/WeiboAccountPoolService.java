package com.hidewnd.winds.scout.service;

import com.hidewnd.winds.scout.model.WeiboAccount;

import java.util.Optional;

/**
 * 微博抓取账号池服务。
 */
public interface WeiboAccountPoolService {

    /**
     * 从账号池中随机选择一个凭据完整且当前可用的账号，并记录本次使用。
     *
     * @return 可用账号；没有可用账号时返回空
     */
    Optional<WeiboAccount> chooseAccount();

    /**
     * 记录账号请求成功，并恢复账号的可用状态。
     *
     * @param account 选取时的账号及状态版本
     */
    void recordSuccess(WeiboAccount account);

    /** 释放请求占用，不改变失败计数和待验证的恢复轮次。 */
    void releaseAccount(WeiboAccount account);

    /**
     * 记录请求失败；登录凭据失效时停用并发布管理告警，其余失败按次数退避恢复。
     *
     * @param account 选取时的账号及状态版本
     * @param exception 本次请求异常
     */
    void recordFailure(WeiboAccount account, Exception exception);
}
