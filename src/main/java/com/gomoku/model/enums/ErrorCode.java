package com.gomoku.model.enums;

/**
 * 业务错误码枚举
 */
public enum ErrorCode {
    NOT_YOUR_TURN,        // 非当前行动回合
    CELL_OCCUPIED,        // 目标坐标已有落子
    INVALID_COORDINATES,  // 坐标越界
    GAME_NOT_PLAYING,     // 对局不在进行状态
    NOT_A_PLAYER,         // 非对局选手（观战者越权）
    STALE_STATE,          // 客户端状态版本过期
    DUPLICATE_COMMAND,    // 重复提交的指令
    ROOM_FULL,            // 房间选手席已满
    UNAUTHORIZED_ACTION,  // 未授权的操作
    RATE_LIMITED,         // 频率超限
    CHAT_RATE_LIMITED     // 聊天频率超限
}
