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
     * @param accountId 账号 ID
     */
    void recordSuccess(String accountId);

    /**
     * 记录请求失败；凭据失效或连续三次失败时永久停用并告警，其余失败按次数退避恢复。
     *
     * @param accountId 账号 ID
     * @param exception 本次请求异常
     */
    void recordFailure(String accountId, Exception exception);
}
