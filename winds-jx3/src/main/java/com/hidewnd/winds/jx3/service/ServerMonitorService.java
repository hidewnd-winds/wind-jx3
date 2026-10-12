package com.hidewnd.winds.jx3.service;

import com.hidewnd.winds.jx3.model.ServerOpening;
import java.util.concurrent.CompletableFuture;

/** 监听服务契约；调度层只依赖接口。 */
public interface ServerMonitorService {
    void poll();

    /** 优先登记有效三方开服通知并广播，不以 TCP 探测结果作为前置条件。 */
    CompletableFuture<Void> verifyOpening(ServerOpening opening);
}
