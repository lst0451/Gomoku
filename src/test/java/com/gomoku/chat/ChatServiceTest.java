package com.gomoku.chat;

import com.gomoku.dto.request.ChatMessageRequest;
import com.gomoku.dto.request.JoinRoomRequest;
import com.gomoku.model.entity.ChatMessage;
import com.gomoku.security.UserPrincipal;
import com.gomoku.service.GameRoomService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("房间聊天安全与环形队列测试 (ChatServiceTest)")
class ChatServiceTest {

    @Autowired
    private ChatService chatService;

    @Autowired
    private GameRoomService gameRoomService;

    private final UserPrincipal alice = new UserPrincipal("U_CHAT_ALICE", "Alice", false);
    private final UserPrincipal bob = new UserPrincipal("U_CHAT_BOB", "Bob", false);

    @Test
    @DisplayName("令牌桶防刷限流：1秒内连续发送第3条消息被拒绝并返回 CHAT_RATE_LIMITED")
    void testChatRateLimiting() {
        String roomId = "R_CHAT_RATE_001";
        gameRoomService.joinRoom(new JoinRoomRequest("c1", roomId), alice, "sess-alice");

        // 连续发送前两条：成功
        boolean msg1 = chatService.sendMessage(new ChatMessageRequest("cmd-1", roomId, "Hello 1"), alice);
        boolean msg2 = chatService.sendMessage(new ChatMessageRequest("cmd-2", roomId, "Hello 2"), alice);
        assertTrue(msg1, "第1条消息应发送成功");
        assertTrue(msg2, "第2条消息应发送成功");

        // 连续发送第3条：触发令牌桶限流，拒绝
        boolean msg3 = chatService.sendMessage(new ChatMessageRequest("cmd-3", roomId, "Hello 3 (too fast)"), alice);
        assertFalse(msg3, "第3条消息因超限必须被拒绝");
    }

    @Test
    @DisplayName("HTML 实体转义：含有恶意 XSS 脚本标签被安全转义为 HTML 实体")
    void testHtmlEscaping() {
        String roomId = "R_CHAT_XSS_001";
        gameRoomService.joinRoom(new JoinRoomRequest("c1", roomId), bob, "sess-bob");

        String maliciousText = "<script>alert('XSS')</script>";
        boolean success = chatService.sendMessage(new ChatMessageRequest("cmd-xss", roomId, maliciousText), bob);
        assertTrue(success);

        List<ChatMessage> recentChats = chatService.getRecentChats(roomId);
        assertFalse(recentChats.isEmpty());
        ChatMessage lastMessage = recentChats.get(recentChats.size() - 1);

        assertEquals("&lt;script&gt;alert(&#39;XSS&#39;)&lt;/script&gt;", lastMessage.content(),
                "恶意外链脚本标签必须被安全转义");
    }

    @Test
    @DisplayName("消息字长约束：超过 200 字符的消息被拒绝")
    void testMessageLengthLimit() {
        String roomId = "R_CHAT_LEN_001";
        UserPrincipal charlie = new UserPrincipal("U_CHAT_CHARLIE", "Charlie", false);
        gameRoomService.joinRoom(new JoinRoomRequest("c1", roomId), charlie, "sess-charlie");

        String over200Chars = "A".repeat(201);
        boolean success = chatService.sendMessage(new ChatMessageRequest("cmd-len", roomId, over200Chars), charlie);
        assertFalse(success, "超长 200 字符文本应被拦截拒绝");
    }

    @Test
    @DisplayName("环形缓冲队列：保留上限为 50 条消息，超出后淘汰最旧的消息")
    void testRingBufferLimit() {
        String roomId = "R_CHAT_RING_001";
        UserPrincipal david = new UserPrincipal("U_CHAT_DAVID", "David", false);
        gameRoomService.joinRoom(new JoinRoomRequest("c1", roomId), david, "sess-david");

        // 使用 55 个不同用户或不同消息发送，验证环形队列 50 条上限
        for (int i = 1; i <= 55; i++) {
            UserPrincipal sender = new UserPrincipal("U_SENDER_" + i, "User" + i, false);
            gameRoomService.joinRoom(new JoinRoomRequest("c-" + i, roomId), sender, "sess-" + i);
            boolean sent = chatService.sendMessage(new ChatMessageRequest("cmd-" + i, roomId, "Msg " + i), sender);
            assertTrue(sent, "每位新用户的第1条消息应成功发送");
        }

        List<ChatMessage> recentChats = chatService.getRecentChats(roomId);
        assertEquals(50, recentChats.size(), "环形队列最多保留 50 条消息");

        // 前 5 条 (Msg 1 ~ Msg 5) 已被挤出队列，第一条应为 Msg 6
        assertEquals("Msg 6", recentChats.get(0).content());
        assertEquals("Msg 55", recentChats.get(recentChats.size() - 1).content());
    }
}
