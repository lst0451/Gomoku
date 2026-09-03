package com.gomoku.model.entity;

import com.gomoku.model.enums.UserRole;

/**
 * 房间内聊天记录不可变对象 (Record)
 *
 * @param messageId  消息唯一标识
 * @param senderId   发送者用户ID (由 Principal 提供)
 * @param senderName 发送者昵称
 * @param role       发送时角色 (PLAYER_BLACK / PLAYER_WHITE / SPECTATOR)
 * @param content    过滤转义后的文本内容
 * @param timestamp  服务端时间戳
 */
public record ChatMessage(
    String messageId,
    String senderId,
    String senderName,
    UserRole role,
    String content,
    long timestamp
) {}
