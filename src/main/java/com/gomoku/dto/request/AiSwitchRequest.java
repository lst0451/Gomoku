package com.gomoku.dto.request;

/**
 * 切换/开启人机对战模式请求载荷
 *
 * @param commandId  客户端命令ID
 * @param roomId     房间ID
 * @param difficulty 目标 AI 难度 (EASY / MEDIUM / HARD)
 */
public record AiSwitchRequest(
    String commandId,
    String roomId,
    String difficulty
) {}
