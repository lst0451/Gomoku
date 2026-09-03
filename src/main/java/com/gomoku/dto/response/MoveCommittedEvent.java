package com.gomoku.dto.response;

import com.gomoku.model.enums.FinishReason;
import com.gomoku.model.enums.PieceColor;

/**
 * 落子成功同步广播事件
 */
public record MoveCommittedEvent(
    String type,
    String roomId,
    String gameId,
    long stateVersion,
    String commandId,
    int stepNumber,
    PieceColor color,
    int row,
    int col,
    PieceColor nextTurn,
    long turnDeadline,
    boolean isGameOver,
    PieceColor winner,
    FinishReason finishReason,
    long serverTimestamp
) {
    public static final String EVENT_TYPE = "MOVE_COMMITTED";
}
