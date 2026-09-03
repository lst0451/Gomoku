package com.gomoku.listener;

import com.gomoku.service.GameRoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;

/**
 * WebSocket 会话断开事件监听器
 * 捕获 SessionDisconnectEvent 并通知 GameRoomService 维护引用计数与触发断线保留期
 */
@Component
public class WebSocketSessionEventListener {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionEventListener.class);
    private final GameRoomService gameRoomService;

    public WebSocketSessionEventListener(GameRoomService gameRoomService) {
        this.gameRoomService = gameRoomService;
    }

    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        Principal principal = accessor.getUser();

        if (principal != null && sessionId != null) {
            log.info("WebSocket session disconnected: userId={}, sessionId={}", principal.getName(), sessionId);
            gameRoomService.handleSessionDisconnect(sessionId, principal.getName());
        }
    }
}
