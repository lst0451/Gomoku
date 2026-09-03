package com.gomoku.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebSocket/STOMP 入站安全通道拦截器
 * 1. CONNECT 阶段：校验 Header 中的 Token，解析并绑定服务端信任的 UserPrincipal。
 * 2. SUBSCRIBE 阶段：拦截 /topic/room/{roomId}/** 订阅，核验房间成员资格；限制私有队列订阅范围。
 */
@Component
public class AuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthChannelInterceptor.class);

    // 匹配 /topic/room/{roomId}/... 订阅路径
    private static final Pattern ROOM_DESTINATION_PATTERN = Pattern.compile("^/topic/room/([^/]+)(/.*)?$");

    private final TokenProvider tokenProvider;
    private final RoomMembershipValidator roomMembershipValidator;

    public AuthChannelInterceptor(TokenProvider tokenProvider, RoomMembershipValidator roomMembershipValidator) {
        this.tokenProvider = tokenProvider;
        this.roomMembershipValidator = roomMembershipValidator;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command)) {
            return handleConnect(message, accessor);
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            handleSubscribe(accessor);
        }

        return message;
    }

    /**
     * 处理 CONNECT 握手认证
     */
    private Message<?> handleConnect(Message<?> message, StompHeaderAccessor accessor) {
        String token = extractToken(accessor);
        if (token == null || token.isBlank()) {
            log.warn("STOMP CONNECT rejected: missing authentication token");
            throw new SecurityException("Missing authentication token in STOMP headers");
        }

        try {
            UserPrincipal principal = tokenProvider.validateAndExtractPrincipal(token);
            if (accessor.isMutable()) {
                accessor.setUser(principal);
                log.info("STOMP authenticated successfully: userId={}, isGuest={}",
                        principal.getUserId(), principal.isGuest());
                return message;
            } else {
                StompHeaderAccessor mutableAccessor = StompHeaderAccessor.wrap(message);
                mutableAccessor.setUser(principal);
                log.info("STOMP authenticated successfully: userId={}, isGuest={}",
                        principal.getUserId(), principal.isGuest());
                return org.springframework.messaging.support.MessageBuilder.createMessage(
                        message.getPayload(), mutableAccessor.getMessageHeaders());
            }
        } catch (Exception e) {
            log.warn("STOMP CONNECT rejected: token validation failed: {}", e.getMessage());
            throw new SecurityException("Authentication failed: " + e.getMessage(), e);
        }
    }

    /**
     * 处理 SUBSCRIBE 订阅鉴权
     */
    private void handleSubscribe(StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user == null) {
            log.warn("STOMP SUBSCRIBE rejected: unauthenticated user");
            throw new SecurityException("Authentication required to subscribe");
        }

        String destination = accessor.getDestination();
        if (destination == null || destination.isBlank()) {
            return;
        }

        // 校验 /topic/room/{roomId}/** 订阅
        Matcher matcher = ROOM_DESTINATION_PATTERN.matcher(destination);
        if (matcher.matches()) {
            String roomId = matcher.group(1);
            String userId = user.getName();
            boolean isMember = roomMembershipValidator.isUserInRoom(roomId, userId);
            if (!isMember) {
                log.warn("STOMP SUBSCRIBE rejected: user {} is not a member of room {}", userId, roomId);
                throw new SecurityException("Access denied: User " + userId + " is not a member of room " + roomId);
            }
            log.debug("STOMP SUBSCRIBE authorized: user {} subscribed to room {}", userId, roomId);
        }
    }

    /**
     * 从 STOMP 消息头中提取 Token
     */
    private String extractToken(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null || authHeader.isBlank()) {
            authHeader = accessor.getFirstNativeHeader("token");
        }
        if (authHeader == null || authHeader.isBlank()) {
            authHeader = accessor.getFirstNativeHeader("auth-token");
        }
        return authHeader;
    }
}
