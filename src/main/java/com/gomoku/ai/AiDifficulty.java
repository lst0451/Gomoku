package com.gomoku.ai;

/**
 * AI 对战难度等级枚举
 */
public enum AiDifficulty {
    EASY(2),    // 入门级 (深度 2)
    MEDIUM(3),  // 进阶级 (深度 3)
    HARD(4);    // 专家级 (深度 4)

    private final int searchDepth;

    AiDifficulty(int searchDepth) {
        this.searchDepth = searchDepth;
    }

    public int getSearchDepth() {
        return searchDepth;
    }

    public static AiDifficulty fromString(String difficulty) {
        if (difficulty == null || difficulty.isBlank()) {
            return MEDIUM;
        }
        try {
            return valueOf(difficulty.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MEDIUM;
        }
    }
}
