package com.gomoku.model.enums;

/**
 * 房间对局状态机枚举
 */
public enum GameStatus {
    WAITING,   // 等待玩家加入
    PLAYING,   // 对局进行中
    PAUSED,    // 玩家掉线重连中（保留期）
    FINISHED   // 对局已结束
}
