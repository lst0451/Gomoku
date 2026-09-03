package com.gomoku.scheduler;

import com.gomoku.model.enums.PieceColor;

/**
 * 倒计时调度竞态快照四元组 (TimeoutContext)
 * 封装任务注册时的对局上下文，消除 ABA 误判竞态
 *
 * @param gameId               对局唯一标识
 * @param expectedTurn         期望行动方
 * @param expectedStateVersion 期望房间状态版本号
 * @param expectedDeadline     期望落子截止绝对时间戳
 */
public record TimeoutContext(
    String gameId,
    PieceColor expectedTurn,
    long expectedStateVersion,
    long expectedDeadline
) {}
