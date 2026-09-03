package com.gomoku.dto.request;

/**
 * 用户加入/重连房间请求载荷
 *
 * @param commandId 客户端生成的命令唯一ID (防重)
 * @param roomId    目标房间ID
 */
public record JoinRoomRequest(
    String commandId,
    String roomId
) {}
