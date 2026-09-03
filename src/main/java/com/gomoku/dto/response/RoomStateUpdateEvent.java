package com.gomoku.dto.response;

import com.gomoku.model.enums.GameStatus;
import com.gomoku.model.enums.PieceColor;

/**
 * 房间状态同步广播事件
 */
public record RoomStateUpdateEvent(
    String type,
    String roomId,
    String gameId,
    long stateVersion,
    GameStatus status,
    String ruleSet,
    PlayerInfo playerBlack,
    PlayerInfo playerWhite,
    PieceColor currentTurn,
    int occupiedCount,
    long turnDeadline,
    long serverTimestamp
) {
    public static final String EVENT_TYPE = "ROOM_STATE_UPDATE";
}
