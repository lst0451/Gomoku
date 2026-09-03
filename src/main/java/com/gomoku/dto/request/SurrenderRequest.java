package com.gomoku.dto.request;

/**
 * 玩家认输请求载荷
 *
 * @param commandId            客户端命令ID
 * @param roomId               房间ID
 * @param expectedStateVersion 客户端已知的期望房间状态版本号
 */
public record SurrenderRequest(
    String commandId,
    String roomId,
    Long expectedStateVersion
) {}
