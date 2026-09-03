package com.gomoku.model.enums;

/**
 * 棋子颜色 / 行棋方枚举
 */
public enum PieceColor {
    BLACK(1),  // 黑棋（先手）
    WHITE(2);  // 白棋（后手）

    private final int value;

    PieceColor(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    /**
     * 获取对手执子颜色
     *
     * @return 对手棋色
     */
    public PieceColor opposite() {
        return this == BLACK ? WHITE : BLACK;
    }

    /**
     * 根据数值解析棋子颜色
     *
     * @param value 棋子数值 (1: 黑, 2: 白)
     * @return 对应的 PieceColor 枚举
     */
    public static PieceColor fromValue(int value) {
        for (PieceColor color : values()) {
            if (color.value == value) {
                return color;
            }
        }
        throw new IllegalArgumentException("Unknown PieceColor value: " + value);
    }
}
