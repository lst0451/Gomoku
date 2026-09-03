package com.gomoku.dto.response;

import com.gomoku.model.enums.FinishReason;
import com.gomoku.model.enums.PieceColor;

import java.util.List;

/**
 * 对局终局广播事件
 */
public record GameFinishedEvent(
    String type,
    String roomId,
    String gameId,
    long stateVersion,
    PieceColor winner,
    FinishReason finishReason,
    List<int[]> winningLine,
    long finishedAt
) {
    public static final String EVENT_TYPE = "GAME_FINISHED";
}
