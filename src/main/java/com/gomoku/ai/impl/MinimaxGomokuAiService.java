package com.gomoku.ai.impl;

import com.gomoku.ai.AiDifficulty;
import com.gomoku.ai.BoardSnapshot;
import com.gomoku.ai.GomokuAiService;
import com.gomoku.model.entity.Move;
import com.gomoku.model.enums.PieceColor;
import com.gomoku.referee.GomokuReferee;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 进程内传统五子棋博弈树引擎 (Minimax + Alpha-Beta 剪枝)
 * 纯本地 Java 实现，零外部网络/商业 LLM 依赖
 */
@Service
public class MinimaxGomokuAiService implements GomokuAiService {

    private static final Logger log = LoggerFactory.getLogger(MinimaxGomokuAiService.class);

    private static final int WIN_SCORE = 1_000_000;
    private static final int OPEN_FOUR = 50_000;
    private static final int BLOCKED_FOUR = 10_000;
    private static final int OPEN_THREE = 5_000;
    private static final int BLOCKED_THREE = 500;
    private static final int OPEN_TWO = 100;
    private static final int BLOCKED_TWO = 10;

    private final Executor aiExecutor;

    public MinimaxGomokuAiService(@Qualifier("aiTaskExecutor") Executor aiExecutor) {
        this.aiExecutor = aiExecutor;
    }

    @Override
    public CompletableFuture<Move> computeBestMoveAsync(
            BoardSnapshot snapshot,
            AiDifficulty difficulty,
            Duration timeBudget) {

        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            long budgetMs = timeBudget != null ? timeBudget.toMillis() : 200L;
            long deadline = startTime + (long) (budgetMs * 0.85); // 85% 软超时阈值

            int[] bestCoord = computeBestMove(snapshot, difficulty, deadline);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("AI computed move ({}, {}) in {}ms, difficulty={}, version={}",
                    bestCoord[0], bestCoord[1], elapsed, difficulty, snapshot.baseStateVersion());

            return new Move(
                    snapshot.occupiedCount() + 1,
                    snapshot.aiColor(),
                    bestCoord[0],
                    bestCoord[1],
                    System.currentTimeMillis()
            );
        }, aiExecutor);
    }

    /**
     * 核心决策计算
     */
    public int[] computeBestMove(BoardSnapshot snapshot, AiDifficulty difficulty, long deadline) {
        int[][] grid = snapshot.grid();
        PieceColor aiColor = snapshot.aiColor();
        PieceColor oppColor = aiColor.opposite();

        // 1. 开局天元优化：若盘面为空或第一手落于非中心，优先占领天元 (7, 7)
        if (snapshot.occupiedCount() == 0 || (snapshot.occupiedCount() == 1 && grid[7][7] == 0)) {
            if (grid[7][7] == 0) {
                return new int[]{7, 7};
            }
        }

        List<int[]> candidates = getCandidateMoves(grid);
        if (candidates.isEmpty()) {
            return new int[]{7, 7};
        }

        // 2. 杀手步快速检查 (Immediate Win / Immediate Block)
        // 2.1 检查己方是否有一步制胜点
        for (int[] cand : candidates) {
            grid[cand[0]][cand[1]] = aiColor.getValue();
            boolean win = GomokuReferee.checkWin(grid, cand[0], cand[1], aiColor);
            grid[cand[0]][cand[1]] = 0;
            if (win) {
                log.debug("AI found immediate win at ({}, {})", cand[0], cand[1]);
                return cand;
            }
        }

        // 2.2 检查对方是否有一步制胜点 (必防杀手步)
        for (int[] cand : candidates) {
            grid[cand[0]][cand[1]] = oppColor.getValue();
            boolean oppWin = GomokuReferee.checkWin(grid, cand[0], cand[1], oppColor);
            grid[cand[0]][cand[1]] = 0;
            if (oppWin) {
                log.debug("AI blocked immediate opponent win at ({}, {})", cand[0], cand[1]);
                return cand;
            }
        }

        // 3. 候选点评分排序与剪枝 (取前 N 个高价值候选点)
        int maxCandidates = switch (difficulty) {
            case EASY -> 12;
            case MEDIUM -> 18;
            case HARD -> 24;
        };
        List<int[]> topCandidates = sortAndFilterCandidates(grid, candidates, aiColor, maxCandidates);

        // 4. Minimax + Alpha-Beta 剪枝搜索
        int depth = difficulty.getSearchDepth();
        int bestScore = Integer.MIN_VALUE;
        int[] bestMove = topCandidates.get(0);

        int alpha = Integer.MIN_VALUE;
        int beta = Integer.MAX_VALUE;

        for (int[] cand : topCandidates) {
            if (System.currentTimeMillis() > deadline) {
                break;
            }

            grid[cand[0]][cand[1]] = aiColor.getValue();
            int score = minimax(grid, depth - 1, alpha, beta, false, aiColor, oppColor, deadline);
            grid[cand[0]][cand[1]] = 0;

            if (score > bestScore) {
                bestScore = score;
                bestMove = cand;
            }
            alpha = Math.max(alpha, bestScore);
            if (beta <= alpha) {
                break; // Alpha 剪枝
            }
        }

        return bestMove;
    }

    private int minimax(int[][] grid, int depth, int alpha, int beta, boolean isMaximizing,
                        PieceColor aiColor, PieceColor oppColor, long deadline) {
        if (System.currentTimeMillis() > deadline || depth <= 0) {
            return evaluateBoard(grid, aiColor);
        }

        List<int[]> candidates = sortAndFilterCandidates(grid, getCandidateMoves(grid),
                isMaximizing ? aiColor : oppColor, 12);
        if (candidates.isEmpty()) {
            return evaluateBoard(grid, aiColor);
        }

        if (isMaximizing) {
            int maxEval = Integer.MIN_VALUE;
            for (int[] move : candidates) {
                grid[move[0]][move[1]] = aiColor.getValue();
                if (GomokuReferee.checkWin(grid, move[0], move[1], aiColor)) {
                    grid[move[0]][move[1]] = 0;
                    return WIN_SCORE + depth; // 越早获胜得分越高
                }

                int eval = minimax(grid, depth - 1, alpha, beta, false, aiColor, oppColor, deadline);
                grid[move[0]][move[1]] = 0;

                maxEval = Math.max(maxEval, eval);
                alpha = Math.max(alpha, eval);
                if (beta <= alpha) {
                    break;
                }
            }
            return maxEval;
        } else {
            int minEval = Integer.MAX_VALUE;
            for (int[] move : candidates) {
                grid[move[0]][move[1]] = oppColor.getValue();
                if (GomokuReferee.checkWin(grid, move[0], move[1], oppColor)) {
                    grid[move[0]][move[1]] = 0;
                    return -WIN_SCORE - depth;
                }

                int eval = minimax(grid, depth - 1, alpha, beta, true, aiColor, oppColor, deadline);
                grid[move[0]][move[1]] = 0;

                minEval = Math.min(minEval, eval);
                beta = Math.min(beta, eval);
                if (beta <= alpha) {
                    break;
                }
            }
            return minEval;
        }
    }

    /**
     * 提取已有棋子周边 1~2 格内的空余候选交叉点
     */
    private List<int[]> getCandidateMoves(int[][] grid) {
        int size = GomokuReferee.BOARD_SIZE;
        boolean[][] visited = new boolean[size][size];
        List<int[]> candidates = new ArrayList<>();

        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                if (grid[r][c] != 0) {
                    // 扫描周围 1~2 格邻域
                    for (int dr = -2; dr <= 2; dr++) {
                        for (int dc = -2; dc <= 2; dc++) {
                            int nr = r + dr;
                            int nc = c + dc;
                            if (GomokuReferee.isValidCoordinate(nr, nc) && grid[nr][nc] == 0 && !visited[nr][nc]) {
                                visited[nr][nc] = true;
                                candidates.add(new int[]{nr, nc});
                            }
                        }
                    }
                }
            }
        }
        return candidates;
    }

    /**
     * 候选点静态评估打分与排序截断
     */
    private List<int[]> sortAndFilterCandidates(int[][] grid, List<int[]> candidates,
                                               PieceColor color, int limit) {
        PieceColor oppColor = color.opposite();

        record ScoredMove(int[] coord, int score) {}
        List<ScoredMove> scoredList = new ArrayList<>(candidates.size());

        for (int[] cand : candidates) {
            int r = cand[0];
            int c = cand[1];
            int attackScore = evaluatePoint(grid, r, c, color.getValue());
            int defenseScore = evaluatePoint(grid, r, c, oppColor.getValue());
            int total = attackScore + (int) (defenseScore * 1.2);
            scoredList.add(new ScoredMove(cand, total));
        }

        scoredList.sort((a, b) -> Integer.compare(b.score(), a.score()));

        int count = Math.min(limit, scoredList.size());
        List<int[]> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(scoredList.get(i).coord());
        }
        return result;
    }

    /**
     * 评估单一落点的局部棋型分
     */
    private int evaluatePoint(int[][] grid, int r, int c, int color) {
        int total = 0;
        int[][] directions = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};

        for (int[] dir : directions) {
            int count = 1;
            int openEnds = 0;

            // 正向
            int nr = r + dir[0];
            int nc = c + dir[1];
            while (GomokuReferee.isValidCoordinate(nr, nc) && grid[nr][nc] == color) {
                count++;
                nr += dir[0];
                nc += dir[1];
            }
            if (GomokuReferee.isValidCoordinate(nr, nc) && grid[nr][nc] == 0) {
                openEnds++;
            }

            // 反向
            nr = r - dir[0];
            nc = c - dir[1];
            while (GomokuReferee.isValidCoordinate(nr, nc) && grid[nr][nc] == color) {
                count++;
                nr -= dir[0];
                nc -= dir[1];
            }
            if (GomokuReferee.isValidCoordinate(nr, nc) && grid[nr][nc] == 0) {
                openEnds++;
            }

            total += scorePattern(count, openEnds);
        }
        return total;
    }

    /**
     * 全盘启发式评估函数
     */
    public int evaluateBoard(int[][] grid, PieceColor aiColor) {
        int aiScore = evaluateColorOnBoard(grid, aiColor.getValue());
        int oppScore = evaluateColorOnBoard(grid, aiColor.opposite().getValue());
        return aiScore - (int) (oppScore * 1.2);
    }

    private int evaluateColorOnBoard(int[][] grid, int color) {
        int total = 0;
        int size = GomokuReferee.BOARD_SIZE;
        int[][] directions = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};

        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                if (grid[r][c] == color) {
                    for (int[] dir : directions) {
                        // 仅在起始点处计算，避免重复
                        int pr = r - dir[0];
                        int pc = c - dir[1];
                        if (GomokuReferee.isValidCoordinate(pr, pc) && grid[pr][pc] == color) {
                            continue;
                        }

                        int count = 0;
                        int nr = r;
                        int nc = c;
                        while (GomokuReferee.isValidCoordinate(nr, nc) && grid[nr][nc] == color) {
                            count++;
                            nr += dir[0];
                            nc += dir[1];
                        }

                        int openEnds = 0;
                        if (GomokuReferee.isValidCoordinate(pr, pc) && grid[pr][pc] == 0) {
                            openEnds++;
                        }
                        if (GomokuReferee.isValidCoordinate(nr, nc) && grid[nr][nc] == 0) {
                            openEnds++;
                        }

                        total += scorePattern(count, openEnds);
                    }
                }
            }
        }
        return total;
    }

    private int scorePattern(int count, int openEnds) {
        if (count >= 5) {
            return WIN_SCORE;
        }
        if (count == 4) {
            return openEnds == 2 ? OPEN_FOUR : (openEnds == 1 ? BLOCKED_FOUR : 0);
        }
        if (count == 3) {
            return openEnds == 2 ? OPEN_THREE : (openEnds == 1 ? BLOCKED_THREE : 0);
        }
        if (count == 2) {
            return openEnds == 2 ? OPEN_TWO : (openEnds == 1 ? BLOCKED_TWO : 0);
        }
        return 0;
    }
}
