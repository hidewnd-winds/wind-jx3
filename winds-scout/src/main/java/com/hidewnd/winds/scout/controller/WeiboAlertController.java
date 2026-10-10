package com.hidewnd.winds.scout.controller;

import com.hidewnd.winds.common.base.response.R;
import com.hidewnd.winds.scout.config.ScoutManagementInterceptor;
import com.hidewnd.winds.scout.config.WeiboAccountManagement;
import com.hidewnd.winds.scout.dto.WeiboAlertResponse;
import com.hidewnd.winds.scout.service.impl.WeiboAlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 告警读取与接收确认只面向当前管理 Token；接收确认不表示私聊已送达。 */
@RestController
@RequestMapping("/scout/weibo/alerts")
@Tag(name = "微博管理告警")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "Authorization")
public class WeiboAlertController {
    private final WeiboAlertService alerts;
    public WeiboAlertController(WeiboAlertService alerts) { this.alerts = alerts; }

    @GetMapping
    @WeiboAccountManagement
    @Operation(summary = "读取当前管理Token尚未确认接收的告警")
    public R<List<WeiboAlertResponse>> pending(
            @RequestAttribute(ScoutManagementInterceptor.TOKEN_ATTRIBUTE) String token,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return R.successByObj(alerts.pending(token, limit));
    }

    @GetMapping("/{id}")
    @WeiboAccountManagement
    @Operation(summary = "读取告警快照及当前恢复状态")
    public R<WeiboAlertResponse> get(@PathVariable("id") String id) {
        return R.successByObj(alerts.get(id));
    }

    @PostMapping("/{id}/ack")
    @WeiboAccountManagement
    @Operation(summary = "幂等确认当前管理Token已持久化接收告警")
    public R<Void> acknowledge(@PathVariable("id") String id,
            @RequestAttribute(ScoutManagementInterceptor.TOKEN_ATTRIBUTE) String token) {
        alerts.acknowledge(id, token);
        return R.success("已确认接收");
    }
}
