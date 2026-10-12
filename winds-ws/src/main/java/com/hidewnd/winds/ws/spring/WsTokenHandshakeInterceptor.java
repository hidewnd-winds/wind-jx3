package com.hidewnd.winds.ws.spring;

import com.hidewnd.winds.ws.auth.WsTokenAuthenticator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

@Component
@Slf4j
public class WsTokenHandshakeInterceptor implements HandshakeInterceptor {

    /** 仅保存在服务端会话中，供管理事件发送前重新鉴权。 */
    static final String TOKEN_ATTRIBUTE = "wsToken";

    private final WsTokenAuthenticator authenticator;

    public WsTokenHandshakeInterceptor(WsTokenAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        String token = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .getFirst("token");
        if (!StringUtils.hasText(token)) {
            token = bearerToken(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        }
        boolean authorized;
        try {
            authorized = authenticator.isAuthorized(token);
        } catch (DataAccessException exception) {
            // 数据库故障不代表令牌无效；拒绝升级，且不让框架将含 Token 的请求 URI 写入异常日志。
            log.error("WebSocket握手鉴权存储不可用，异常类型={}", exception.getClass().getSimpleName());
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
        if (authorized) {
            attributes.put(TOKEN_ATTRIBUTE, token);
            return true;
        }
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring("Bearer ".length()).trim();
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
    }
}
