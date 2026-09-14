package com.hidewnd.winds.ws.spring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hidewnd.winds.bot.huangli.event.HuangliUpdatedEvent;
import com.hidewnd.winds.scout.event.WeiboUpdatedEvent;
import com.hidewnd.winds.scout.event.WeiboAccountInvalidEvent;
import com.hidewnd.winds.scout.config.ScoutAuthorization;
import com.hidewnd.winds.scout.config.ScoutManagementTokenAuthenticator;
import com.hidewnd.winds.scout.service.WeiboSubscriptionService;
import com.hidewnd.winds.jx3.event.Jx3Event;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class WindsWebSocketHandler extends TextWebSocketHandler {

    private static final TextMessage CONNECTION_SUCCESS = new TextMessage(
            "{\"type\":\"connection.success\",\"message\":\"连接成功\"}");

    private final ObjectMapper objectMapper;
    private final ScoutManagementTokenAuthenticator managementAuthenticator;
    private final WeiboSubscriptionService subscriptions;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public WindsWebSocketHandler(ObjectMapper objectMapper,
                                 ScoutManagementTokenAuthenticator managementAuthenticator,
                                 WeiboSubscriptionService subscriptions) {
        this.objectMapper = objectMapper;
        this.managementAuthenticator = managementAuthenticator;
        this.subscriptions = subscriptions;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        WebSocketSession concurrentSession = new ConcurrentWebSocketSessionDecorator(
                session, 10_000, 64 * 1024);
        sessions.put(session.getId(), concurrentSession);
        try {
            concurrentSession.sendMessage(CONNECTION_SUCCESS);
            log.info("WebSocket连接建立，sessionId={}，在线连接数={}", session.getId(), sessions.size());
        } catch (IOException exception) {
            sessions.remove(session.getId());
            throw exception;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("WebSocket连接关闭，sessionId={}，状态={}，在线连接数={}",
                session.getId(), status.getCode(), sessions.size());
    }

    @EventListener
    public void broadcast(HuangliUpdatedEvent event) {
        ObjectNode payload = objectMapper.createObjectNode()
                .put("type", "huangli.updated")
                .put("date", event.date())
                .put("url", event.url());
        TextMessage message;
        try {
            message = new TextMessage(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("黄历更新消息序列化失败", exception);
        }
        int online = sessions.size();
        int sent = sendToSessions(message);
        log.info("WebSocket黄历广播完成，日期={}，在线连接数={}，成功发送数={}", event.date(), online, sent);
    }

    @EventListener
    @Async
    public void broadcast(WeiboUpdatedEvent event) {
        ObjectNode payload = objectMapper.createObjectNode().put("type", "weibo.updated");
        payload.setAll((ObjectNode) objectMapper.valueToTree(event.post()));
        TextMessage message;
        try {
            message = new TextMessage(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("微博更新消息序列化失败", exception);
        }
        int online = sessions.size();
        if (online == 0) {
            return;
        }
        Set<String> subscribedTokens;
        try {
            subscribedTokens = subscriptions.subscriberTokens(event.post().uid());
        } catch (RuntimeException exception) {
            log.error("微博订阅查询失败，uid={}，异常类型={}", event.post().uid(), exception.getClass().getSimpleName());
            return;
        }
        int sent = 0;
        // 每个事件重新读取订阅与有效性；同一 Token 多个连接仅查询一次鉴权，不保存长期权限快照。
        Map<String, Boolean> authorizedTokens = new HashMap<>();
        for (Map.Entry<String, WebSocketSession> entry : sessions.entrySet()) {
            WebSocketSession session = entry.getValue();
            if (!session.isOpen()) {
                sessions.remove(entry.getKey());
                continue;
            }
            Object token = session.getAttributes().get(WsTokenHandshakeInterceptor.TOKEN_ATTRIBUTE);
            if (!(token instanceof String tokenValue) || !subscribedTokens.contains(tokenValue)) {
                continue;
            }
            Boolean authorized = authorizedTokens.get(tokenValue);
            if (authorized == null) {
                try {
                    ScoutAuthorization result = managementAuthenticator.authorize(tokenValue);
                    authorized = result == ScoutAuthorization.AUTHORIZED || result == ScoutAuthorization.FORBIDDEN;
                } catch (RuntimeException exception) {
                    log.error("微博推文鉴权失败，sessionId={}，异常类型={}",
                            entry.getKey(), exception.getClass().getSimpleName());
                    authorized = false;
                }
                authorizedTokens.put(tokenValue, authorized);
            }
            if (authorized && send(entry.getKey(), session, message)) {
                sent++;
            }
        }
        log.info("WebSocket微博广播完成，weiboId={}，在线连接数={}，成功发送数={}",
                event.post().weiboId(), online, sent);
    }

    @EventListener
    @Async
    public void broadcast(Jx3Event event) {
        TextMessage message;
        try {
            message = new TextMessage(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("剑三消息序列化失败", exception);
        }
        int sent = sendToSessions(message);
        log.info("WebSocket剑三广播完成，类型={}，事件={}，成功发送数={}", event.type(), event.eventId(), sent);
    }

    /**
     * 账号失效告警只发送给当前仍有管理权限的在线连接，不缓存握手时的权限快照。
     */
    @EventListener
    @Async
    public void broadcast(WeiboAccountInvalidEvent event) {
        ObjectNode payload = objectMapper.valueToTree(event);
        payload.put("type", "weibo.account.invalid")
                .put("level", "warning")
                .put("message", "微博监听账号已停止使用且不会自动恢复，请检查账号并更新Cookie")
                .put("status", "fail")
                .putNull("recoverAt");
        TextMessage message;
        try {
            message = new TextMessage(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("微博账号失效告警序列化失败", exception);
        }
        int sent = 0;
        for (Map.Entry<String, WebSocketSession> entry : sessions.entrySet()) {
            WebSocketSession session = entry.getValue();
            if (!session.isOpen()) {
                sessions.remove(entry.getKey());
                continue;
            }
            Object token = session.getAttributes().get(WsTokenHandshakeInterceptor.TOKEN_ATTRIBUTE);
            if (!(token instanceof String tokenValue)) {
                continue;
            }
            ScoutAuthorization authorization;
            try {
                authorization = managementAuthenticator.authorize(tokenValue);
            } catch (RuntimeException exception) {
                // 数据库故障时拒绝发送；不输出可能包含 token 查询条件的异常详情。
                log.error("微博管理告警鉴权失败，sessionId={}，异常类型={}",
                        entry.getKey(), exception.getClass().getSimpleName());
                continue;
            }
            if (authorization == ScoutAuthorization.AUTHORIZED && send(entry.getKey(), session, message)) {
                sent++;
            }
        }
        log.info("WebSocket微博账号失效告警完成，accountId={}，成功发送数={}", event.accountId(), sent);
    }

    private int sendToSessions(TextMessage message) {
        int sent = 0;
        for (Map.Entry<String, WebSocketSession> entry : sessions.entrySet()) {
            if (send(entry.getKey(), entry.getValue(), message)) {
                sent++;
            }
        }
        return sent;
    }

    private boolean send(String sessionId, WebSocketSession session, TextMessage message) {
        if (!session.isOpen()) {
            sessions.remove(sessionId);
            return false;
        }
        try {
            session.sendMessage(message);
            return true;
        } catch (IOException exception) {
            sessions.remove(sessionId);
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            log.warn("WebSocket 消息发送失败，sessionId={}", sessionId, exception);
            return false;
        }
    }
}
