package com.gomoku.model.enums;

/**
 * 终局原因枚举
 */
public enum FinishReason {
    FIVE_IN_A_ROW,        // 连五成线获胜
    SURRENDER,            // 对手认输
    TURN_TIMEOUT,         // 回合落子超时
    DISCONNECT_TIMEOUT,   // 掉线重连超时
    DRAW                  // 满盘平局
}
