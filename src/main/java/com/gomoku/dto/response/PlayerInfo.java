package com.gomoku.dto.response;

/**
 * 棋手简要信息
 *
 * @param userId   用户ID
 * @param username 用户昵称
 */
public record PlayerInfo(
    String userId,
    String username
) {}
