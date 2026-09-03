package com.gomoku.dto.response;

import com.gomoku.model.entity.ChatMessage;
import com.gomoku.model.entity.Move;
import com.gomoku.model.enums.GameStatus;
import com.gomoku.model.enums.PieceColor;

import java.util.List;

/**
 * 房间对局全量快照响应载荷
 * 新接入或重连用户拉取，完成客户端对局渲染重构
 */
public record GameSnapshotResponse(
    String roomId,
    String gameId,
    long stateVersion,
    GameStatus status,
    String ruleSet,
    PlayerInfo playerBlack,
    PlayerInfo playerWhite,
    PieceColor currentTurn,
    long turnDeadline,
    int occupiedCount,
    int[][] board,
    List<Move> recentMoves,
    List<ChatMessage> recentChats
) {}
