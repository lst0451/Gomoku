package com.gomoku.chat;

import com.gomoku.dto.request.ChatMessageRequest;
import com.gomoku.dto.response.ChatBroadcastEvent;
import com.gomoku.dto.response.ErrorResponse;
import com.gomoku.model.entity.ChatMessage;
import com.gomoku.model.entity.GameRoom;
import com.gomoku.model.enums.ErrorCode;
import com.gomoku.model.enums.UserRole;
import com.gomoku.security.UserPrincipal;
import com.gomoku.service.GameRoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 房间安全聊天服务 (ChatService)
 * 1. 令牌桶限流 (每秒最多 2 条)
 * 2. 文本长度限制 (上限 200 字符)
 * 3. HTML 实体转义 (防 XSS 攻击)
 * 4. 环形缓冲队列 (保留最近 50 条消息)
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int MAX_CONTENT_LENGTH = 200;
    private static final int RING_BUFFER_CAPACITY = 50;

    private final SimpMessagingTemplate messagingTemplate;
    private final GameRoomService gameRoomService;

    // 针对每个用户的令牌桶：userId -> TokenBucket
    private final ConcurrentHashMap<String, TokenBucket> userBuckets = new ConcurrentHashMap<>();

    // 每个房间的环形消息缓冲队列：roomId -> ArrayDeque
    private final ConcurrentHashMap<String, ArrayDeque<ChatMessage>> roomChatBuffers = new ConcurrentHashMap<>();

    public ChatService(@Lazy SimpMessagingTemplate messagingTemplate,
                       @Lazy GameRoomService gameRoomService) {
        this.messagingTemplate = messagingTemplate;
        this.gameRoomService = gameRoomService;
    }

    /**
     * 处理发送聊天消息
     */
    public boolean sendMessage(ChatMessageRequest request, UserPrincipal principal) {
        String userId = principal.getUserId();
        String commandId = request.commandId();
        String roomId = request.roomId();

        // 1. 令牌桶限流检查 (每秒最多2条)
        TokenBucket bucket = userBuckets.computeIfAbsent(userId, k -> new TokenBucket());
        if (!bucket.tryConsume()) {
            sendError(userId, commandId, ErrorCode.CHAT_RATE_LIMITED, "发言过于频繁，请稍后再试");
            return false;
        }

        // 2. 校验房间是否存在与用户成员资格
        GameRoom room = gameRoomService.getRoom(roomId);
        if (room == null || !room.isUserInRoom(userId)) {
            sendError(userId, commandId, ErrorCode.UNAUTHORIZED_ACTION, "未加入该房间，无权发言");
            return false;
        }

        // 3. 校验消息内容非空与长度限制 (200字)
        String rawContent = request.content();
        if (rawContent == null || rawContent.isBlank()) {
            sendError(userId, commandId, ErrorCode.INVALID_COORDINATES, "消息内容不可为空");
            return false;
        }
        if (rawContent.length() > MAX_CONTENT_LENGTH) {
            sendError(userId, commandId, ErrorCode.INVALID_COORDINATES, "消息长度超出 200 字符限制");
            return false;
        }

        // 4. HTML 实体转义 (防 XSS)
        String sanitizedContent = HtmlUtils.htmlEscape(rawContent.trim());

        // 5. 确定发送人角色
        UserRole role = UserRole.SPECTATOR;
        if (userId.equals(room.getPlayerBlackId())) {
            role = UserRole.PLAYER_BLACK;
        } else if (userId.equals(room.getPlayerWhiteId())) {
            role = UserRole.PLAYER_WHITE;
        }

        String messageId = "msg-" + UUID.randomUUID();
        long now = System.currentTimeMillis();
        ChatMessage chatMessage = new ChatMessage(
                messageId,
                userId,
                principal.getUsername(),
                role,
                sanitizedContent,
                now
        );

        // 6. 存入房间环形缓冲队列 (上限 50 条)
        synchronized (this) {
            ArrayDeque<ChatMessage> buffer = roomChatBuffers.computeIfAbsent(roomId, k -> new ArrayDeque<>());
            if (buffer.size() >= RING_BUFFER_CAPACITY) {
                buffer.pollFirst();
            }
            buffer.addLast(chatMessage);
        }

        // 7. 广播到房间聊天主题
        ChatBroadcastEvent broadcastEvent = new ChatBroadcastEvent(
                ChatBroadcastEvent.EVENT_TYPE,
                messageId,
                roomId,
                userId,
                principal.getUsername(),
                role.name(),
                sanitizedContent,
                now
        );

        messagingTemplate.convertAndSend("/topic/room/" + roomId + "/chat", broadcastEvent);
        log.info("Chat message broadcasted: roomId={}, sender={}, role={}", roomId, userId, role);
        return true;
    }

    /**
     * 获取指定房间的历史聊天记录 (按时间顺序)
     */
    public synchronized List<ChatMessage> getRecentChats(String roomId) {
        ArrayDeque<ChatMessage> buffer = roomChatBuffers.get(roomId);
        if (buffer == null || buffer.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(buffer);
    }

    private void sendError(String userId, String commandId, ErrorCode errorCode, String message) {
        ErrorResponse errorResponse = new ErrorResponse(
                commandId,
                errorCode,
                message,
                0,
                System.currentTimeMillis()
        );
        messagingTemplate.convertAndSendToUser(userId, "/queue/errors", errorResponse);
        log.warn("Chat error for user {}: errorCode={}, message={}", userId, errorCode, message);
    }

    /**
     * 令牌桶实现：容量 2.0，充填速率 2.0 令牌/秒
     */
    static class TokenBucket {
        private final double capacity = 2.0;
        private final double refillRatePerMs = 2.0 / 1000.0;
        private double tokens = capacity;
        private long lastRefillTime = System.currentTimeMillis();

        public synchronized boolean tryConsume() {
            long now = System.currentTimeMillis();
            tokens = Math.min(capacity, tokens + (now - lastRefillTime) * refillRatePerMs);
            lastRefillTime = now;

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
