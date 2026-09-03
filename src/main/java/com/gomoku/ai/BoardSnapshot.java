package com.gomoku.ai;

import com.gomoku.model.enums.PieceColor;

/**
 * 不可变盘面快照模型 (Record)
 * 供 AI 异步计算任务读取，保证计算期间盘面数据不被并发修改
 *
 * @param grid             15x15 深拷贝盘面数组
 * @param occupiedCount    当前盘面已落子总数
 * @param aiColor          AI 执子颜色
 * @param baseStateVersion 计算触发时的房间状态版本号
 */
public record BoardSnapshot(
    int[][] grid,
    int occupiedCount,
    PieceColor aiColor,
    long baseStateVersion
) {
    public BoardSnapshot {
        if (grid == null) {
            throw new IllegalArgumentException("grid cannot be null");
        }
        // 深拷贝二维数组保证快照真正不可变
        int[][] copy = new int[grid.length][];
        for (int i = 0; i < grid.length; i++) {
            copy[i] = grid[i].clone();
        }
        grid = copy;
    }
}
