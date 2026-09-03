package com.gomoku.ai;

import com.gomoku.model.entity.Move;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 五子棋 AI 决策引擎服务接口契约
 */
public interface GomokuAiService {

    /**
     * 异步计算最佳落子，必须在指定 timeBudget 预算内返回
     *
     * @param snapshot   当前不可变盘面快照
     * @param difficulty AI 难度
     * @param timeBudget 时间预算 (超时保护)
     * @return 最佳落子 Move 的异步 Future
     */
    CompletableFuture<Move> computeBestMoveAsync(
        BoardSnapshot snapshot,
        AiDifficulty difficulty,
        Duration timeBudget
    );
}
