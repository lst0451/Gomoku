package com.gomoku.dto.request;

/**
 * 房间聊天发送请求载荷
 *
 * @param commandId 客户端命令ID
 * @param roomId    目标房间ID
 * @param content   消息文本内容 (上限200字符)
 */
public record ChatMessageRequest(
    String commandId,
    String roomId,
    String content
) {}
