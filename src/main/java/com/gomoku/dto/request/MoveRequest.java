package com.gomoku.dto.request;

/**
 * 玩家落子请求载荷
 *
 * @param commandId            客户端命令ID
 * @param roomId               房间ID
 * @param expectedStateVersion 客户端已知的期望房间状态版本号 (乐观锁校验)
 * @param row                  落子行号 (0..14)
 * @param col                  落子列号 (0..14)
 */
public record MoveRequest(
    String commandId,
    String roomId,
    Long expectedStateVersion,
    int row,
    int col
) {}
