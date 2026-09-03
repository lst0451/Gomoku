package com.gomoku.security;

import com.gomoku.config.WebSocketConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("WebSocket/STOMP 安全鉴权与拦截器集成测试")
class WebSocketSecurityIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private DefaultRoomMembershipValidator roomMembershipValidator;

    @Autowired
    private AuthChannelInterceptor authChannelInterceptor;

    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        roomMembershipValidator.clear();
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
    }

    private String getWsUrl() {
        return "ws://localhost:" + port + "/ws-gomoku";
    }

    @Nested
    @DisplayName("1. TokenProvider 签发与解析单元测试")
    class TokenProviderTests {

        @Test
        @DisplayName("正式用户与访客 Token 能够正常生成并提取 UserPrincipal")
        void testGenerateAndValidate() {
            String token = tokenProvider.generateToken("U1001", "Alice");
            assertNotNull(token);

            UserPrincipal principal = tokenProvider.validateAndExtractPrincipal(token);
            assertEquals("U1001", principal.getUserId());
            assertEquals("U1001", principal.getName());
            assertEquals("Alice", principal.getUsername());
            assertFalse(principal.isGuest());

            // 访客 Token
            String guestToken = tokenProvider.generateGuestToken("Bob");
            UserPrincipal guestPrincipal = tokenProvider.validateAndExtractPrincipal(guestToken);
            assertNotNull(guestPrincipal.getUserId());
            assertTrue(guestPrincipal.getUserId().startsWith("guest-"));
            assertEquals("Bob", guestPrincipal.getUsername());
            assertTrue(guestPrincipal.isGuest());
        }

        @Test
        @DisplayName("支持 Bearer 前缀解析")
        void testBearerPrefix() {
            String rawToken = tokenProvider.generateToken("U1002", "Charlie");
            UserPrincipal principal = tokenProvider.validateAndExtractPrincipal("Bearer " + rawToken);
            assertEquals("U1002", principal.getUserId());
        }

        @Test
        @DisplayName("非法签名与篡改 Token 抛出 SecurityException")
        void testTamperedTokenThrows() {
            String token = tokenProvider.generateToken("U1001", "Alice");
            String[] parts = token.split("\\.");
            // 篡改 payload
            String tamperedToken = parts[0] + ".e30." + parts[2];

            assertThrows(SecurityException.class, () -> tokenProvider.validateAndExtractPrincipal(tamperedToken));
            assertThrows(SecurityException.class, () -> tokenProvider.validateAndExtractPrincipal("invalid.token"));
            assertThrows(SecurityException.class, () -> tokenProvider.validateAndExtractPrincipal(null));
        }

        @Test
        @DisplayName("过期 Token 校验失败")
        void testExpiredToken() {
            // 生成 TTL 为负数的过期 Token
            String expiredToken = tokenProvider.generateToken("U1001", "Alice", false, -1000L);
            SecurityException ex = assertThrows(SecurityException.class,
                    () -> tokenProvider.validateAndExtractPrincipal(expiredToken));
            assertTrue(ex.getMessage().contains("expired"));
        }
    }

    @Nested
    @DisplayName("2. AuthChannelInterceptor 拦截器逻辑测试")
    class InterceptorDirectTests {

        @Test
        @DisplayName("CONNECT 命令未带 Token 时抛出 SecurityException")
        void testConnectWithoutTokenThrows() {
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            SecurityException ex = assertThrows(SecurityException.class,
                    () -> authChannelInterceptor.preSend(message, null));
            assertTrue(ex.getMessage().contains("Missing authentication token"));
        }

        @Test
        @DisplayName("CONNECT 命令携带合法 Token 成功绑定 UserPrincipal")
        void testConnectWithValidTokenBindsPrincipal() {
            String token = tokenProvider.generateToken("U8888", "David");
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            accessor.setNativeHeader("Authorization", "Bearer " + token);
            Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            Message<?> result = authChannelInterceptor.preSend(message, null);
            assertNotNull(result);

            StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
            assertNotNull(resultAccessor);
            assertNotNull(resultAccessor.getUser());
            assertEquals("U8888", resultAccessor.getUser().getName());
        }

        @Test
        @DisplayName("SUBSCRIBE 阶段非房间成员订阅 /topic/room/{roomId}/** 被拦截拒绝")
        void testSubscribeUnauthorizedRoomThrows() {
            UserPrincipal principal = new UserPrincipal("U9999", "Eve", false);
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            accessor.setUser(principal);
            accessor.setDestination("/topic/room/R101/chat");
            Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            SecurityException ex = assertThrows(SecurityException.class,
                    () -> authChannelInterceptor.preSend(message, null));
            assertTrue(ex.getMessage().contains("Access denied"));
            assertTrue(ex.getMessage().contains("R101"));
        }

        @Test
        @DisplayName("SUBSCRIBE 阶段已加入房间成员订阅 /topic/room/{roomId}/** 顺利放行")
        void testSubscribeAuthorizedRoomPasses() {
            UserPrincipal principal = new UserPrincipal("U9999", "Eve", false);
            roomMembershipValidator.addMember("R101", "U9999");

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            accessor.setUser(principal);
            accessor.setDestination("/topic/room/R101/chat");
            Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            Message<?> result = authChannelInterceptor.preSend(message, null);
            assertNotNull(result);
        }

        @Test
        @DisplayName("SUBSCRIBE 阶段未认证用户订阅直接拒绝")
        void testSubscribeUnauthenticatedThrows() {
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            accessor.setDestination("/user/queue/errors");
            Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            SecurityException ex = assertThrows(SecurityException.class,
                    () -> authChannelInterceptor.preSend(message, null));
            assertTrue(ex.getMessage().contains("Authentication required"));
        }
    }

    @Nested
    @DisplayName("3. 嵌入式 WebSocket 容器端到端连接集成测试")
    class EndToEndWebSocketSecurityTests {

        @Test
        @DisplayName("未携带 Token 发起 CONNECT 命令连接被服务端拒绝")
        void testE2eUnauthenticatedConnectRejected() {
            StompHeaders connectHeaders = new StompHeaders();
            CompletableFuture<StompSession> future = stompClient.connectAsync(
                    getWsUrl(),
                    new WebSocketHttpHeaders(),
                    connectHeaders,
                    new StompSessionHandlerAdapter() {}
            );

            // 连接应该由于鉴权拦截失败而抛出异常
            ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertNotNull(ex.getCause());
        }

        @Test
        @DisplayName("携带合法 Token 发起 CONNECT 成功建立 STOMP 会话并订阅私有队列")
        void testE2eAuthenticatedConnectAndSubscribePrivateQueue() throws Exception {
            String token = tokenProvider.generateToken("U2001", "Frank");
            StompHeaders connectHeaders = new StompHeaders();
            connectHeaders.add("Authorization", "Bearer " + token);

            CompletableFuture<StompSession> future = stompClient.connectAsync(
                    getWsUrl(),
                    new WebSocketHttpHeaders(),
                    connectHeaders,
                    new StompSessionHandlerAdapter() {}
            );

            StompSession session = future.get(5, TimeUnit.SECONDS);
            assertTrue(session.isConnected(), "STOMP 会话必须处于连接成功状态");

            // 成功订阅私有队列
            StompSession.Subscription sub = session.subscribe("/user/queue/errors", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return String.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {}
            });
            assertNotNull(sub);
            session.disconnect();
        }

        @Test
        @DisplayName("已加入房间成员成功订阅房间广播主题")
        void testE2eAuthorizedRoomSubscription() throws Exception {
            String token = tokenProvider.generateToken("U3001", "Grace");
            roomMembershipValidator.addMember("R202", "U3001");

            StompHeaders connectHeaders = new StompHeaders();
            connectHeaders.add("Authorization", "Bearer " + token);

            StompSession session = stompClient.connectAsync(
                    getWsUrl(),
                    new WebSocketHttpHeaders(),
                    connectHeaders,
                    new StompSessionHandlerAdapter() {}
            ).get(5, TimeUnit.SECONDS);

            StompSession.Subscription sub = session.subscribe("/topic/room/R202/state", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return String.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {}
            });

            assertNotNull(sub);
            session.disconnect();
        }
    }

    @Nested
    @DisplayName("4. 跨域配置安全性检查")
    class CorsSecurityCheckTests {

        @Test
        @DisplayName("跨域允许源排除了全局通配符 '*'，仅允许特定受控白名单")
        void testCorsExcludesWildcard() {
            for (String pattern : WebSocketConfig.ALLOWED_ORIGIN_PATTERNS) {
                assertNotEquals("*", pattern, "严禁将 '*' 配置为全域开放源");
                assertTrue(pattern.startsWith("http://") || pattern.startsWith("https://"),
                        "允许源必须包含明确的 http/https 协议前缀");
            }
        }
    }
}
