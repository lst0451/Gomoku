package com.gomoku.referee;

import com.gomoku.model.enums.PieceColor;

/**
 * 五子棋裁判规则引擎 (Gomoku Referee Engine)
 * 采用 FREESTYLE (自由规则)：
 * 1. 黑白双方均无禁手限制。
 * 2. 任意轴向（水平、垂直、主对角线、副对角线）连续同色棋子达到或超过 5 颗 (count >= 5，包含长连) 即判定获胜。
 * 3. 当棋盘 225 格全部占满且未决出胜者时判定平局。
 */
public final class GomokuReferee {

    /**
     * 标准棋盘边长 (15 x 15)
     */
    public static final int BOARD_SIZE = 15;

    /**
     * 棋盘总交叉点数 (225)
     */
    public static final int TOTAL_CELLS = BOARD_SIZE * BOARD_SIZE;

    /**
     * 获胜所需最少连续棋子数 (5)
     */
    public static final int WIN_COUNT = 5;

    /**
     * 4 个轴向向量：水平、垂直、主对角线(\)、副对角线(/)
     */
    private static final int[][] DIRECTIONS = {
        {0, 1},   // 水平向右/左
        {1, 0},   // 垂直向下/上
        {1, 1},   // 主对角线向右下/左上 (\)
        {1, -1}   // 副对角线向右上/左下 (/)
    };

    private GomokuReferee() {
        // 工具类私有构造器
    }

    /**
     * 胜负裁定：检查在 (row, col) 位置落入指定颜色棋子后，是否形成五连或以上获胜
     *
     * @param board 15x15 盘面数组 (0: 空, 1: 黑, 2: 白)
     * @param row   落子行号 (0..14)
     * @param col   落子列号 (0..14)
     * @param color 执子颜色对应数值 (1: 黑, 2: 白)
     * @return true 若形成五连或长连，否则 false
     */
    public static boolean checkWin(int[][] board, int row, int col, int color) {
        if (board == null || !isValidCoordinate(row, col)) {
            return false;
        }
        if (board.length != BOARD_SIZE || board[row].length != BOARD_SIZE) {
            return false;
        }
        if (board[row][col] != color) {
            return false;
        }

        for (int[] dir : DIRECTIONS) {
            int count = 1;
            count += countConsecutive(board, row, col, dir[0], dir[1], color);
            count += countConsecutive(board, row, col, -dir[0], -dir[1], color);
            if (count >= WIN_COUNT) {
                return true;
            }
        }
        return false;
    }

    /**
     * 胜负裁定 (基于 PieceColor 枚举的重载方法)
     *
     * @param board 15x15 盘面数组
     * @param row   落子行号 (0..14)
     * @param col   落子列号 (0..14)
     * @param color 执子颜色枚举
     * @return true 若形成五连或长连，否则 false
     */
    public static boolean checkWin(int[][] board, int row, int col, PieceColor color) {
        if (color == null) {
            return false;
        }
        return checkWin(board, row, col, color.getValue());
    }

    /**
     * 平局裁定：当盘面落子数达到 225 且当前未分出胜负时裁定平局
     *
     * @param occupiedCount 当前盘面已落子总数
     * @param isWin         当前步是否已经获胜
     * @return true 若判定为平局，否则 false
     */
    public static boolean checkDraw(int occupiedCount, boolean isWin) {
        return !isWin && occupiedCount >= TOTAL_CELLS;
    }

    /**
     * 坐标合法性校验 (0 <= row < 15 且 0 <= col < 15)
     *
     * @param row 行号
     * @param col 列号
     * @return true 若坐标在棋盘范围内，否则 false
     */
    public static boolean isValidCoordinate(int row, int col) {
        return row >= 0 && row < BOARD_SIZE && col >= 0 && col < BOARD_SIZE;
    }

    /**
     * 检查目标坐标是否为空位
     *
     * @param board 15x15 盘面数组
     * @param row   行号
     * @param col   列号
     * @return true 若坐标合法且该位置为 0 (EMPTY)，否则 false
     */
    public static boolean isCellEmpty(int[][] board, int row, int col) {
        return isValidCoordinate(row, col)
            && board != null
            && board.length == BOARD_SIZE
            && board[row].length == BOARD_SIZE
            && board[row][col] == 0;
    }

    /**
     * 沿指定向量方向统计连续同色棋子数量
     *
     * @param board    盘面数组
     * @param startRow 起始行
     * @param startCol 起始列
     * @param dRow     行增量 (-1, 0, 1)
     * @param dCol     列增量 (-1, 0, 1)
     * @param color    棋子颜色数值
     * @return 连续同色棋子数量（不包含起始点本身）
     */
    private static int countConsecutive(int[][] board, int startRow, int startCol, int dRow, int dCol, int color) {
        int count = 0;
        int r = startRow + dRow;
        int c = startCol + dCol;
        while (isValidCoordinate(r, c) && board[r][c] == color) {
            count++;
            r += dRow;
            c += dCol;
        }
        return count;
    }
}
