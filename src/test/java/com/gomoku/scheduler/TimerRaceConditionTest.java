package com.gomoku.scheduler;

import com.gomoku.dto.request.JoinRoomRequest;
import com.gomoku.dto.request.MoveRequest;
import com.gomoku.model.entity.GameRoom;
import com.gomoku.model.enums.FinishReason;
import com.gomoku.model.enums.GameStatus;
import com.gomoku.model.enums.PieceColor;
import com.gomoku.security.UserPrincipal;
import com.gomoku.service.GameRoomService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("防竞态定时器与单机生命周期回收测试 (TimerRaceConditionTest)")
class TimerRaceConditionTest {

    @Autowired
    private GameRoomService gameRoomService;

    @Autowired
    private GameTimerService gameTimerService;

    @Autowired
    private RoomEvictionScheduler roomEvictionScheduler;

    private final UserPrincipal alice = new UserPrincipal("U_TIMER_ALICE", "Alice", false);
    private final UserPrincipal bob = new UserPrincipal("U_TIMER_BOB", "Bob", false);

    @Nested
    @DisplayName("1. 回合自然超时与胜负裁决测试")
    class TurnTimeoutTests {

        @Test
        @DisplayName("行棋方在时间预算内未落子，定时器触发判负，对方直接胜出")
        void testTurnTimeoutTriggersLoss() throws Exception {
            String roomId = "R_TIMEOUT_001";
            gameRoomService.joinRoom(new JoinRoomRequest("c1", roomId), alice, "s-alice-1");
            gameRoomService.joinRoom(new JoinRoomRequest("c2", roomId), bob, "s-bob-1");

            GameRoom room = gameRoomService.getRoom(roomId);
            assertEquals(GameStatus.PLAYING, room.getStatus());
            assertEquals(PieceColor.BLACK, room.getCurrentTurn());

            // 注册一个 50ms 快速超时的测试任务
            TimeoutContext context = new TimeoutContext(
                    room.getGameId(),
                    PieceColor.BLACK,
                    room.getStateVersion(),
                    System.currentTimeMillis() + 50
            );
            gameTimerService.scheduleTurnTimeout(roomId, context, 50);

            // 等待定时器执行
            Thread.sleep(150);

            assertEquals(GameStatus.FINISHED, room.getStatus(), "对局应流转至 FINISHED");
            assertEquals(PieceColor.WHITE, room.getWinner(), "黑方超时，白方应判定获胜");
            assertEquals(FinishReason.TURN_TIMEOUT, room.getFinishReason(), "终局原因确认为 TURN_TIMEOUT");
        }
    }

    @Nested
    @DisplayName("2. 防误判竞态验证 (消除 ABA 误杀下一手玩家)")
    class AntiRaceConditionTests {

        @Test
        @DisplayName("临界落子竞态：旧定时任务由于 stateVersion 不匹配静默放弃，绝不误杀下一手玩家")
        void testOldTimerDiscardedOnVersionMismatch() {
            String roomId = "R_ABA_001";
            gameRoomService.joinRoom(new JoinRoomRequest("c1", roomId), alice, "s-alice-1");
            gameRoomService.joinRoom(new JoinRoomRequest("c2", roomId), bob, "s-bob-1");

            GameRoom room = gameRoomService.getRoom(roomId);
            long initialVersion = room.getStateVersion();
            assertEquals(PieceColor.BLACK, room.getCurrentTurn());

            // 模拟抓取当前旧回合快照
            TimeoutContext staleContext = new TimeoutContext(
                    room.getGameId(),
                    PieceColor.BLACK,
                    initialVersion,
                    System.currentTimeMillis() + 30000
            );

            // Alice 在倒计时最后一刻成功落子 (7, 7)
            gameRoomService.handleMove(new MoveRequest("m1", roomId, null, 7, 7), alice);

            // 落子成功后版本已自增，轮到 Bob 行棋
            assertTrue(room.getStateVersion() > initialVersion);
            assertEquals(PieceColor.WHITE, room.getCurrentTurn());
            assertEquals(GameStatus.PLAYING, room.getStatus());

            // 随后迟到的旧定时任务触发回调
            gameRoomService.handleTurnTimeout(roomId, staleContext);

            // 必须验证：对局依然处于 PLAYING 状态，绝不将白方 Bob 误判超时！
            assertEquals(GameStatus.PLAYING, room.getStatus(), "房间必须保持 PLAYING 状态");
            assertEquals(PieceColor.WHITE, room.getCurrentTurn(), "依然为白方回合");
            assertNull(room.getWinner(), "不可产生胜者");
        }
    }

    @Nested
    @DisplayName("3. 多 Session 离线隔离与断线保留期测试")
    class MultiSessionGracePeriodTests {

        @Test
        @DisplayName("双 Tab 打开场景：关闭单 Tab 对局继续；所有 Tab 关闭切入 PAUSED 并启动 45 秒保留期")
        void testMultiSessionDisconnectAndPause() {
            String roomId = "R_MULTI_SESS_001";
            // Alice 开启两个 Tab 连接：tab-1, tab-2
            gameRoomService.joinRoom(new JoinRoomRequest("c1", roomId), alice, "s-alice-tab-1");
            gameRoomService.joinRoom(new JoinRoomRequest("c2", roomId), alice, "s-alice-tab-2");
            gameRoomService.joinRoom(new JoinRoomRequest("c3", roomId), bob, "s-bob-tab-1");

            GameRoom room = gameRoomService.getRoom(roomId);
            assertEquals(GameStatus.PLAYING, room.getStatus());
            assertEquals(2, room.getUserActiveSessions().get("U_TIMER_ALICE").size());

            // Alice 关闭 Tab-1
            gameRoomService.handleSessionDisconnect("s-alice-tab-1", "U_TIMER_ALICE");

            // 对局不受影响，依然保持 PLAYING
            assertEquals(GameStatus.PLAYING, room.getStatus());
            assertEquals(1, room.getUserActiveSessions().get("U_TIMER_ALICE").size());

            // Alice 关闭 Tab-2 (全部会话离线)
            gameRoomService.handleSessionDisconnect("s-alice-tab-2", "U_TIMER_ALICE");

            // 房间切入 PAUSED 状态，并设置了 45 秒保留期截止时间
            assertEquals(GameStatus.PAUSED, room.getStatus());
            assertTrue(room.getDisconnectDeadline() > System.currentTimeMillis());
            assertNotNull(room.getCurrentDisconnectTimer());
        }

        @Test
        @DisplayName("保留期内重连成功自愈恢复 PLAYING；超时未归判负 DISCONNECT_TIMEOUT")
        void testGracePeriodRecoveryAndTimeout() {
            String roomId = "R_GRACE_002";
            gameRoomService.joinRoom(new JoinRoomRequest("c1", roomId), alice, "s-alice-1");
            gameRoomService.joinRoom(new JoinRoomRequest("c2", roomId), bob, "s-bob-1");

            GameRoom room = gameRoomService.getRoom(roomId);

            // Alice 掉线进入 PAUSED
            gameRoomService.handleSessionDisconnect("s-alice-1", "U_TIMER_ALICE");
            assertEquals(GameStatus.PAUSED, room.getStatus());

            // 1. 测试重连自愈：Alice 使用新 Session 重连入房
            gameRoomService.joinRoom(new JoinRoomRequest("c-recon", roomId), alice, "s-alice-reconnected");
            assertEquals(GameStatus.PLAYING, room.getStatus(), "重连后状态自愈恢复至 PLAYING");
            assertNull(room.getCurrentDisconnectTimer(), "重连后掉线定时器必须被清空");

            // 2. 再次掉线并测试超时结算
            gameRoomService.handleSessionDisconnect("s-alice-reconnected", "U_TIMER_ALICE");
            assertEquals(GameStatus.PAUSED, room.getStatus());
            long pauseVer = room.getStateVersion();

            // 模拟 45 秒超时触发
            gameRoomService.handleDisconnectTimeout(roomId, "U_TIMER_ALICE", pauseVer);

            assertEquals(GameStatus.FINISHED, room.getStatus());
            assertEquals(PieceColor.WHITE, room.getWinner(), "Alice 未在保留期归来，Bob 获胜");
            assertEquals(FinishReason.DISCONNECT_TIMEOUT, room.getFinishReason());
        }
    }

    @Nested
    @DisplayName("4. 房间生命周期与内存防泄漏回收测试")
    class RoomEvictionTests {

        @Test
        @DisplayName("回收超期空房间 (>10min) 与超期终局房间 (>5min)，且显式 cancel 未完成的定时任务")
        void testRoomEvictionAndTimerCancellation() {
            String emptyRoomId = "R_STALE_EMPTY";
            String finishedRoomId = "R_STALE_FINISHED";
            String activeRoomId = "R_STALE_ACTIVE";

            // 1. 创建空房间，模拟 11 分钟前无活动
            GameRoom emptyRoom = gameRoomService.getOrCreateRoom(emptyRoomId);
            // 绑定定时任务模拟句柄
            ScheduledFuture<?> dummyTimer = gameTimerService.scheduleTurnTimeout(emptyRoomId,
                    new TimeoutContext("g", PieceColor.BLACK, 1, System.currentTimeMillis() + 60000), 60000);

            // 2. 创建终局房间，模拟 6 分钟前终局
            GameRoom finishedRoom = gameRoomService.getOrCreateRoom(finishedRoomId);
            finishedRoom.setStatus(GameStatus.FINISHED);
            finishedRoom.setFinishedAt(System.currentTimeMillis() - 6 * 60 * 1000L);

            // 3. 创建活跃房间，刚开始
            GameRoom activeRoom = gameRoomService.getOrCreateRoom(activeRoomId);
            activeRoom.setStatus(GameStatus.PLAYING);

            // 执行清理扫描 (使用 10分钟/5分钟标准阈值)
            int evicted = roomEvictionScheduler.evictStaleRooms(
                    10 * 60 * 1000L,
                    5 * 60 * 1000L
            );

            // 验证：超期终局房间被清理，活跃房间依然保留
            assertNull(gameRoomService.getRoom(finishedRoomId), "超期终局房间必须被注销");
            assertNotNull(gameRoomService.getRoom(activeRoomId), "活跃房间不可被回收");

            // 测试自定义短阈值清理空房间
            int evictedEmpty = roomEvictionScheduler.evictStaleRooms(0, 0);
            assertTrue(evictedEmpty >= 1);
            assertNull(gameRoomService.getRoom(emptyRoomId), "空房间必须被回收");
            if (dummyTimer != null) {
                assertTrue(dummyTimer.isCancelled(), "回收时关联的任务句柄必须显式被 cancel 取消");
            }
        }
    }
}
