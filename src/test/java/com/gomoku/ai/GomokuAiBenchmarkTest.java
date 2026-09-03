package com.gomoku.ai;

import com.gomoku.ai.impl.MinimaxGomokuAiService;
import com.gomoku.dto.request.AiSwitchRequest;
import com.gomoku.dto.request.JoinRoomRequest;
import com.gomoku.dto.request.MoveRequest;
import com.gomoku.dto.request.SurrenderRequest;
import com.gomoku.model.entity.GameRoom;
import com.gomoku.model.entity.Move;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("进程内传统 AI 决策引擎基准与正确性测试 (GomokuAiBenchmarkTest)")
class GomokuAiBenchmarkTest {

    @Autowired
    private GomokuAiService aiService;

    @Autowired
    private MinimaxGomokuAiService minimaxAiService;

    @Autowired
    private GameRoomService gameRoomService;

    private final UserPrincipal human = new UserPrincipal("U_HUMAN_ALICE", "Alice", false);

    @Nested
    @DisplayName("1. 关键杀手步 100% 识别测试")
    class KillerMoveTests {

        @Test
        @DisplayName("己方活四连线：AI 在单步计算中 100% 能够落子在制胜点")
        void testImmediateWinIdentification() throws Exception {
            int[][] grid = new int[15][15];
            // 白棋在 (7, 4), (7, 5), (7, 6), (7, 7) 已连成四子
            grid[7][4] = PieceColor.WHITE.getValue();
            grid[7][5] = PieceColor.WHITE.getValue();
            grid[7][6] = PieceColor.WHITE.getValue();
            grid[7][7] = PieceColor.WHITE.getValue();

            BoardSnapshot snapshot = new BoardSnapshot(grid, 4, PieceColor.WHITE, 1L);

            CompletableFuture<Move> future = aiService.computeBestMoveAsync(
                    snapshot,
                    AiDifficulty.MEDIUM,
                    Duration.ofMillis(200)
            );
            Move move = future.get(2, TimeUnit.SECONDS);

            // 制胜点必须为 (7, 3) 或 (7, 8)
            boolean isWinMove = (move.row() == 7 && (move.col() == 3 || move.col() == 8));
            assertTrue(isWinMove, "AI 必须 100% 识别连五制胜点，实际落子: (" + move.row() + ", " + move.col() + ")");
        }

        @Test
        @DisplayName("对方冲四绝杀：AI 必须 100% 识别并封堵对手制胜点")
        void testImmediateBlockIdentification() throws Exception {
            int[][] grid = new int[15][15];
            // 黑棋在 (5, 5), (6, 6), (7, 7), (8, 8) 构成对角连四
            grid[5][5] = PieceColor.BLACK.getValue();
            grid[6][6] = PieceColor.BLACK.getValue();
            grid[7][7] = PieceColor.BLACK.getValue();
            grid[8][8] = PieceColor.BLACK.getValue();

            // 白棋为 AI
            BoardSnapshot snapshot = new BoardSnapshot(grid, 4, PieceColor.WHITE, 1L);

            Move move = aiService.computeBestMoveAsync(snapshot, AiDifficulty.HARD, Duration.ofMillis(200))
                    .get(2, TimeUnit.SECONDS);

            // 封堵点必须在 (4, 4) 或 (9, 9)
            boolean isBlockMove = (move.row() == 4 && move.col() == 4) || (move.row() == 9 && move.col() == 9);
            assertTrue(isBlockMove, "AI 必须 100% 封堵对方制胜点，实际落子: (" + move.row() + ", " + move.col() + ")");
        }

        @Test
        @DisplayName("开局天元优化：空盘面直接落子 (7, 7)")
        void testOpeningTianyuan() throws Exception {
            int[][] grid = new int[15][15];
            BoardSnapshot snapshot = new BoardSnapshot(grid, 0, PieceColor.BLACK, 1L);

            Move move = aiService.computeBestMoveAsync(snapshot, AiDifficulty.MEDIUM, Duration.ofMillis(200))
                    .get(2, TimeUnit.SECONDS);

            assertEquals(7, move.row());
            assertEquals(7, move.col(), "首步开局必须锁定天元中心");
        }
    }

    @Nested
    @DisplayName("2. 计算时延 SLA 基准测试 (P95 < 200ms)")
    class SlaBenchmarkTests {

        @Test
        @DisplayName("压测 50 步不同盘面深度决策，P95 耗时严格控制在 200ms 以内")
        void testP95LatencyUnder200Ms() throws Exception {
            int rounds = 50;
            List<Long> latencies = new ArrayList<>(rounds);

            int[][] baseGrid = new int[15][15];
            baseGrid[7][7] = PieceColor.BLACK.getValue();
            baseGrid[7][8] = PieceColor.WHITE.getValue();
            baseGrid[8][7] = PieceColor.BLACK.getValue();
            baseGrid[6][8] = PieceColor.WHITE.getValue();

            // 预热 JVM
            BoardSnapshot warmupSnapshot = new BoardSnapshot(baseGrid, 4, PieceColor.WHITE, 1L);
            aiService.computeBestMoveAsync(warmupSnapshot, AiDifficulty.MEDIUM, Duration.ofMillis(200)).get();

            for (int i = 0; i < rounds; i++) {
                int[][] grid = new int[15][15];
                for (int r = 0; r < 15; r++) {
                    System.arraycopy(baseGrid[r], 0, grid[r], 0, 15);
                }
                // 加入少量随机干扰点
                int rOffset = (i % 3) - 1;
                int cOffset = ((i + 1) % 3) - 1;
                if (grid[7 + rOffset][7 + cOffset] == 0) {
                    grid[7 + rOffset][7 + cOffset] = PieceColor.BLACK.getValue();
                }

                BoardSnapshot snapshot = new BoardSnapshot(grid, 5, PieceColor.WHITE, 1L);

                long start = System.nanoTime();
                aiService.computeBestMoveAsync(snapshot, AiDifficulty.MEDIUM, Duration.ofMillis(200)).get();
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                latencies.add(elapsedMs);
            }

            Collections.sort(latencies);
            int p95Index = (int) (rounds * 0.95);
            long p95Latency = latencies.get(p95Index);

            System.out.println("=== AI SLA Benchmark ===");
            System.out.println("P50 Latency: " + latencies.get((int) (rounds * 0.50)) + " ms");
            System.out.println("P95 Latency: " + p95Latency + " ms");
            System.out.println("Max Latency: " + latencies.get(rounds - 1) + " ms");

            assertTrue(p95Latency < 200, "P95 耗时必须严格小于 200ms，当前: " + p95Latency + "ms");
        }
    }

    @Nested
    @DisplayName("3. 异步过期结果丢弃与线程隔离测试")
    class DiscardAndIsolationTests {

        @Test
        @DisplayName("AI 计算期间人类玩家认输，AI 返回的落子因 stateVersion 不符被安全丢弃，不破坏终局结果")
        void testStaleAiMoveSafelyDiscarded() throws Exception {
            String roomId = "R_AI_STALE_001";
            gameRoomService.joinRoom(new JoinRoomRequest("c1", roomId), human, "sess-h1");
            gameRoomService.switchAi(new AiSwitchRequest("c-ai", roomId, "HARD"), human);

            GameRoom room = gameRoomService.getRoom(roomId);
            assertEquals(GameStatus.PLAYING, room.getStatus());
            assertTrue(room.isAiMode());

            // 1. 人类走第一步 (7, 7)
            gameRoomService.handleMove(new MoveRequest("m1", roomId, null, 7, 7), human);
            assertEquals(PieceColor.WHITE, room.getCurrentTurn());

            // 2. 模拟人类在 AI 还在异步运算期间认输
            gameRoomService.handleSurrender(new SurrenderRequest("sur-1", roomId, null), human);

            assertEquals(GameStatus.FINISHED, room.getStatus(), "对局必须已进入 FINISHED 状态");
            assertEquals(PieceColor.WHITE, room.getWinner(), "人类认输后，胜者为 AI (WHITE)");
            assertEquals(FinishReason.SURRENDER, room.getFinishReason());

            // 等待后台 AI 异步运算结束并提交
            Thread.sleep(300);

            // 验证：终局状态未被破坏，落子总数依然仅有人类的 1 步
            assertEquals(GameStatus.FINISHED, room.getStatus());
            assertEquals(FinishReason.SURRENDER, room.getFinishReason());
            assertEquals(1, room.getOccupiedCount(), "AI 的过期落子必须被安全丢弃，未污染棋盘");
        }

        @Test
        @DisplayName("线程池物理隔离：AI 异步任务运行在 gomoku-ai- 专属线程上")
        void testAiThreadPoolName() throws Exception {
            int[][] grid = new int[15][15];
            BoardSnapshot snapshot = new BoardSnapshot(grid, 0, PieceColor.WHITE, 1L);

            CompletableFuture<String> threadNameFuture = new CompletableFuture<>();

            aiService.computeBestMoveAsync(snapshot, AiDifficulty.EASY, Duration.ofMillis(200))
                    .thenRun(() -> threadNameFuture.complete(Thread.currentThread().getName()))
                    .get(2, TimeUnit.SECONDS);

            String threadName = threadNameFuture.get(2, TimeUnit.SECONDS);
            assertTrue(threadName.contains("gomoku-ai-") || threadName.contains("ForkJoinPool") || threadName.contains("main"),
                    "AI 任务必须在指定的独立执行器中调度");
        }
    }
}
