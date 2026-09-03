package com.gomoku.dto.response;

/**
 * 房间聊天广播事件载荷
 *
 * @param type       事件类型 (固定为 CHAT_MESSAGE)
 * @param messageId  消息唯一标识
 * @param roomId     房间ID
 * @param senderId   发送人ID
 * @param senderName 发送人显示昵称
 * @param role       发送人角色 (PLAYER_BLACK / PLAYER_WHITE / SPECTATOR)
 * @param content    安全转义后的文本内容
 * @param timestamp  服务端时间戳
 */
public record ChatBroadcastEvent(
    String type,
    String messageId,
    String roomId,
    String senderId,
    String senderName,
    String role,
    String content,
    long timestamp
) {
    public static final String EVENT_TYPE = "CHAT_MESSAGE";
}
