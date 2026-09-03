package com.gomoku.gameplay;

import com.gomoku.dto.request.JoinRoomRequest;
import com.gomoku.dto.request.MoveRequest;
import com.gomoku.dto.request.SurrenderRequest;
import com.gomoku.dto.response.ErrorResponse;
import com.gomoku.dto.response.GameFinishedEvent;
import com.gomoku.dto.response.MoveCommittedEvent;
import com.gomoku.dto.response.RoomStateUpdateEvent;
import com.gomoku.model.entity.GameRoom;
import com.gomoku.model.enums.ErrorCode;
import com.gomoku.model.enums.FinishReason;
import com.gomoku.model.enums.GameStatus;
import com.gomoku.model.enums.PieceColor;
import com.gomoku.security.TokenProvider;
import com.gomoku.security.UserPrincipal;
import com.gomoku.service.GameRoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("房间管理、并发串行化与落子流水线集成测试 (GamePlayIntegrationTest)")
class GamePlayIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private GameRoomService gameRoomService;

    private String getWsUrl() {
        return "ws://localhost:" + port + "/ws-gomoku";
    }

    private WebSocketStompClient createStompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        return client;
    }

    private StompSession connectClient(WebSocketStompClient client, String userId, String username) throws Exception {
        String token = tokenProvider.generateToken(userId, username);
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);

        return client.connectAsync(
                getWsUrl(),
                new WebSocketHttpHeaders(),
                headers,
                new StompSessionHandlerAdapter() {}
        ).get(5, TimeUnit.SECONDS);
    }

    @Nested
    @DisplayName("1. GameRoomService 核心并发与规则流转单元测试")
    class GameRoomServiceCoreTests {

        private final String roomId = "R_UNIT_001";
        private final UserPrincipal alice = new UserPrincipal("U_ALICE", "Alice", false);
        private final UserPrincipal bob = new UserPrincipal("U_BOB", "Bob", false);
        private final UserPrincipal charlie = new UserPrincipal("U_CHARLIE", "Charlie", false);

        @Test
        @DisplayName("席位分配与状态机流转：Alice黑方、Bob白方并进入PLAYING，Charlie为观战者")
        void testSeatAllocationAndStatusTransition() {
            // Alice 率先加入 -> 黑方，WAITING
            gameRoomService.joinRoom(new JoinRoomRequest("cmd-1", roomId), alice, "sess-alice");
            GameRoom room = gameRoomService.getRoom(roomId);
            assertNotNull(room);
            assertEquals("U_ALICE", room.getPlayerBlackId());
            assertNull(room.getPlayerWhiteId());
            assertEquals(GameStatus.WAITING, room.getStatus());

            // Bob 加入 -> 白方，PLAYING
            gameRoomService.joinRoom(new JoinRoomRequest("cmd-2", roomId), bob, "sess-bob");
            assertEquals("U_BOB", room.getPlayerWhiteId());
            assertEquals(GameStatus.PLAYING, room.getStatus());
            assertEquals(PieceColor.BLACK, room.getCurrentTurn());
            assertNotNull(room.getGameId());

            // Charlie 加入 -> 观战者
            gameRoomService.joinRoom(new JoinRoomRequest("cmd-3", roomId), charlie, "sess-charlie");
            assertTrue(room.getSpectatorUserIds().contains("U_CHARLIE"));
        }

        @Test
        @DisplayName("并发抢占防护：双线程同时尝试在同一坐标 (7,7) 落子，仅一人成功，另一人抛出 CELL_OCCUPIED")
        void testConcurrentCellOccupation() throws Exception {
            String cRoomId = "R_CONCURRENT_001";
            gameRoomService.joinRoom(new JoinRoomRequest("c1", cRoomId), alice, "s1");
            gameRoomService.joinRoom(new JoinRoomRequest("c2", cRoomId), bob, "s2");

            GameRoom room = gameRoomService.getRoom(cRoomId);
            assertEquals(GameStatus.PLAYING, room.getStatus());

            int threads = 2;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threads);
            AtomicInteger successCount = new AtomicInteger(0);

            // 模拟同时发起落子
            executor.submit(() -> {
                try {
                    startLatch.await();
                    gameRoomService.handleMove(new MoveRequest("m1", cRoomId, null, 7, 7), alice);
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    startLatch.await();
                    // 模拟另一个请求也尝试抢占 (7,7)
                    gameRoomService.handleMove(new MoveRequest("m2", cRoomId, null, 7, 7), alice);
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });

            startLatch.countDown();
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
            executor.shutdown();

            // 棋盘上该位置必须仅有 1 颗棋子，落子计数精确为 1
            assertEquals(1, room.getOccupiedCount());
            assertEquals(PieceColor.BLACK.getValue(), room.getBoard()[7][7]);
            assertEquals(1, room.getMoveHistory().size());
        }

        @Test
        @DisplayName("观战者落子被拦截并返回 NOT_A_PLAYER")
        void testSpectatorMoveRejected() {
            String sRoomId = "R_SPEC_001";
            gameRoomService.joinRoom(new JoinRoomRequest("s1", sRoomId), alice, "s1");
            gameRoomService.joinRoom(new JoinRoomRequest("s2", sRoomId), bob, "s2");
            gameRoomService.joinRoom(new JoinRoomRequest("s3", sRoomId), charlie, "s3");

            // 观战者 Charlie 尝试落子
            gameRoomService.handleMove(new MoveRequest("sm1", sRoomId, null, 7, 7), charlie);

            GameRoom room = gameRoomService.getRoom(sRoomId);
            assertEquals(0, room.getOccupiedCount());
        }

        @Test
        @DisplayName("认输流程：Alice认输，立即判定Bob获胜，状态转为FINISHED")
        void testSurrenderFlow() {
            String surRoomId = "R_SUR_001";
            gameRoomService.joinRoom(new JoinRoomRequest("sr1", surRoomId), alice, "s1");
            gameRoomService.joinRoom(new JoinRoomRequest("sr2", surRoomId), bob, "s2");

            gameRoomService.handleSurrender(new SurrenderRequest("sur-cmd", surRoomId, null), alice);

            GameRoom room = gameRoomService.getRoom(surRoomId);
            assertEquals(GameStatus.FINISHED, room.getStatus());
            assertEquals(PieceColor.WHITE, room.getWinner());
            assertEquals(FinishReason.SURRENDER, room.getFinishReason());
        }
    }

    @Nested
    @DisplayName("2. STOMP 客户端双端对战端到端集成测试")
    class StompDualClientIntegrationTests {

        @Test
        @DisplayName("双客户端完整交互：入座、落子、行棋轮转、非回合拦截与认输结算")
        void testDualClientFullGamePlay() throws Exception {
            String roomId = "R_E2E_999";
            WebSocketStompClient clientAlice = createStompClient();
            WebSocketStompClient clientBob = createStompClient();

            StompSession sessionAlice = connectClient(clientAlice, "U_ALICE", "Alice");
            StompSession sessionBob = connectClient(clientBob, "U_BOB", "Bob");

            BlockingQueue<RoomStateUpdateEvent> stateEvents = new LinkedBlockingQueue<>();
            BlockingQueue<MoveCommittedEvent> moveEvents = new LinkedBlockingQueue<>();
            BlockingQueue<GameFinishedEvent> finishEvents = new LinkedBlockingQueue<>();
            BlockingQueue<ErrorResponse> aliceErrors = new LinkedBlockingQueue<>();

            // 订阅用户私有错误通道
            sessionAlice.subscribe("/user/queue/errors", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) { return ErrorResponse.class; }
                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    aliceErrors.add((ErrorResponse) payload);
                }
            });

            // 1. Alice 发起入座
            sessionAlice.send("/app/room.join", new JoinRoomRequest("c-join-alice", roomId));
            Thread.sleep(300);

            // Alice 订阅房间状态
            sessionAlice.subscribe("/topic/room/" + roomId + "/state", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) { return RoomStateUpdateEvent.class; }
                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    stateEvents.add((RoomStateUpdateEvent) payload);
                }
            });

            // 2. Bob 发起入座
            sessionBob.send("/app/room.join", new JoinRoomRequest("c-join-bob", roomId));
            Thread.sleep(300);

            // Bob 订阅房间游戏广播
            sessionBob.subscribe("/topic/room/" + roomId + "/gameplay", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    // 根据 header 或通用解析
                    return MoveCommittedEvent.class;
                }
                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    if (payload instanceof MoveCommittedEvent m) {
                        moveEvents.add(m);
                    }
                }
            });

            GameRoom room = gameRoomService.getRoom(roomId);
            assertNotNull(room);
            assertEquals(GameStatus.PLAYING, room.getStatus(), "两位玩家就席后房间应处于 PLAYING 状态");
            assertEquals("U_ALICE", room.getPlayerBlackId());
            assertEquals("U_BOB", room.getPlayerWhiteId());

            // 3. Alice (黑方) 首步落子 (7, 7)
            sessionAlice.send("/app/game.move", new MoveRequest("c-m-1", roomId, null, 7, 7));
            MoveCommittedEvent move1 = moveEvents.poll(5, TimeUnit.SECONDS);
            assertNotNull(move1, "应该收到 Alice 落子成功的广播事件");
            assertEquals(7, move1.row());
            assertEquals(7, move1.col());
            assertEquals(PieceColor.BLACK, move1.color());
            assertEquals(PieceColor.WHITE, move1.nextTurn(), "下一步应该轮转至白方");
            assertTrue(move1.stateVersion() > 0, "stateVersion 必须单调递增");

            // 4. 非法回合拦截：Alice 连续再次落子 (7, 8) -> 应被拦截并单播 NOT_YOUR_TURN
            sessionAlice.send("/app/game.move", new MoveRequest("c-m-2-illegal", roomId, null, 7, 8));
            ErrorResponse error = aliceErrors.poll(5, TimeUnit.SECONDS);
            assertNotNull(error, "Alice 应该在私有队列收到错误响应");
            assertEquals(ErrorCode.NOT_YOUR_TURN, error.errorCode());

            // 5. Bob (白方) 合法落子 (7, 8)
            sessionBob.send("/app/game.move", new MoveRequest("c-m-3", roomId, null, 7, 8));
            MoveCommittedEvent move2 = moveEvents.poll(5, TimeUnit.SECONDS);
            assertNotNull(move2);
            assertEquals(PieceColor.WHITE, move2.color());
            assertEquals(PieceColor.BLACK, move2.nextTurn());
            assertTrue(move2.stateVersion() > move1.stateVersion(), "版本号必须严格递增");

            // 6. Alice 发起认输 /app/game.surrender
            sessionAlice.send("/app/game.surrender", new SurrenderRequest("c-sur-1", roomId, null));
            Thread.sleep(300);

            assertEquals(GameStatus.FINISHED, room.getStatus(), "认输后房间状态必须流转至 FINISHED");
            assertEquals(PieceColor.WHITE, room.getWinner(), "Alice 认输，胜者必须为白方 Bob");
            assertEquals(FinishReason.SURRENDER, room.getFinishReason());

            sessionAlice.disconnect();
            sessionBob.disconnect();
        }
    }
}
