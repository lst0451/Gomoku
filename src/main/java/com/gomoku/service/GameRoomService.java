package com.gomoku.service;

import com.gomoku.dto.request.JoinRoomRequest;
import com.gomoku.dto.request.MoveRequest;
import com.gomoku.dto.request.SurrenderRequest;
import com.gomoku.dto.response.*;
import com.gomoku.model.entity.GameRoom;
import com.gomoku.model.entity.Move;
import com.gomoku.model.enums.ErrorCode;
import com.gomoku.model.enums.FinishReason;
import com.gomoku.model.enums.GameStatus;
import com.gomoku.model.enums.PieceColor;
import com.gomoku.referee.GomokuReferee;
import com.gomoku.security.DefaultRoomMembershipValidator;
import com.gomoku.security.RoomMembershipValidator;
import com.gomoku.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 房间管理与对战串行化调度服务 (GameRoomService)
 * 1. 维护并发房间池 ConcurrentHashMap<String, GameRoom>
 * 2. 所有写操作（加入、落子、认输、掉线）在房间独占 ReentrantLock 内执行校验与状态流转
 * 3. 递增单调版本号 stateVersion，锁外异步广播事件与单播错误
 */
@Service
@Primary
public class GameRoomService implements RoomMembershipValidator {

    private static final Logger log = LoggerFactory.getLogger(GameRoomService.class);
    private static final long DEFAULT_TURN_TIMEOUT_MS = 30_000L; // 30秒落子倒计时

    private final ConcurrentHashMap<String, GameRoom> rooms = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messagingTemplate;
    private final DefaultRoomMembershipValidator defaultRoomMembershipValidator;

    public GameRoomService(@Lazy SimpMessagingTemplate messagingTemplate,
                           DefaultRoomMembershipValidator defaultRoomMembershipValidator) {
        this.messagingTemplate = messagingTemplate;
        this.defaultRoomMembershipValidator = defaultRoomMembershipValidator;
    }

    /**
     * 实现 RoomMembershipValidator 接口，校验用户是否已加入房间
     */
    @Override
    public boolean isUserInRoom(String roomId, String userId) {
        if (roomId == null || userId == null) {
            return false;
        }
        GameRoom room = rooms.get(roomId);
        if (room != null && room.isUserInRoom(userId)) {
            return true;
        }
        // 兼容 Phase 2 独立测试中的注册表
        return defaultRoomMembershipValidator.isUserInRoom(roomId, userId);
    }

    /**
     * 获取或创建房间聚合根
     */
    public GameRoom getOrCreateRoom(String roomId) {
        return rooms.computeIfAbsent(roomId, GameRoom::new);
    }

    /**
     * 根据 ID 获取房间
     */
    public GameRoom getRoom(String roomId) {
        return rooms.get(roomId);
    }

    /**
     * 用户进入/重连房间业务处理
     */
    public void joinRoom(JoinRoomRequest request, UserPrincipal principal, String sessionId) {
        String roomId = request.roomId();
        GameRoom room = getOrCreateRoom(roomId);

        RoomStateUpdateEvent stateEvent;
        room.getRoomLock().lock();
        try {
            String userId = principal.getUserId();
            String username = principal.getUsername();
            room.addSession(userId, sessionId);

            // 判定席位分配
            if (userId.equals(room.getPlayerBlackId())) {
                log.info("Player BLACK reconnected: userId={}, roomId={}", userId, roomId);
            } else if (userId.equals(room.getPlayerWhiteId())) {
                log.info("Player WHITE reconnected: userId={}, roomId={}", userId, roomId);
            } else if (room.getPlayerBlackId() == null) {
                // 首位加入者分配黑方 (房主)
                room.setPlayerBlackId(userId);
                room.setPlayerBlackName(username);
                room.setStatus(GameStatus.WAITING);
                log.info("Assigned PLAYER_BLACK: userId={}, roomId={}", userId, roomId);
            } else if (room.getPlayerWhiteId() == null && !room.isAiMode()) {
                // 第二位加入者分配白方，棋局正式开始
                room.setPlayerWhiteId(userId);
                room.setPlayerWhiteName(username);
                String newGameId = "G-" + roomId + "-" + System.currentTimeMillis();
                room.resetGame(newGameId);
                room.setTurnDeadline(System.currentTimeMillis() + DEFAULT_TURN_TIMEOUT_MS);
                log.info("Assigned PLAYER_WHITE and started game: userId={}, roomId={}, gameId={}",
                        userId, roomId, newGameId);
            } else {
                // 席位已满，自动转为观战者
                room.getSpectatorUserIds().add(userId);
                log.info("Assigned SPECTATOR: userId={}, roomId={}", userId, roomId);
            }

            room.nextVersion();
            stateEvent = buildStateUpdateEvent(room);
        } finally {
            room.getRoomLock().unlock();
        }

        // 锁外广播最新状态
        broadcastRoomState(roomId, stateEvent);
    }

    /**
     * 处理玩家落子
     */
    public void handleMove(MoveRequest request, UserPrincipal principal) {
        String roomId = request.roomId();
        GameRoom room = rooms.get(roomId);

        if (room == null) {
            sendError(principal.getUserId(), request.commandId(), ErrorCode.GAME_NOT_PLAYING,
                    "房间不存在", 0);
            return;
        }

        MoveCommittedEvent moveEvent = null;
        GameFinishedEvent finishedEvent = null;

        room.getRoomLock().lock();
        try {
            long currentVersion = room.getStateVersion();

            // 1. 校验对局是否在进行中
            if (room.getStatus() != GameStatus.PLAYING) {
                sendError(principal.getUserId(), request.commandId(), ErrorCode.GAME_NOT_PLAYING,
                        "对局不在进行状态", currentVersion);
                return;
            }

            // 2. 校验发起人是否为本局棋手
            PieceColor userColor = room.getPlayerColor(principal.getUserId());
            if (userColor == null) {
                sendError(principal.getUserId(), request.commandId(), ErrorCode.NOT_A_PLAYER,
                        "非对局选手无法落子", currentVersion);
                return;
            }

            // 3. 校验是否轮到当前行棋方
            if (userColor != room.getCurrentTurn()) {
                sendError(principal.getUserId(), request.commandId(), ErrorCode.NOT_YOUR_TURN,
                        "当前不是您的行棋回合", currentVersion);
                return;
            }

            // 4. 乐观版本号校验 (若客户端携带)
            if (request.expectedStateVersion() != null && request.expectedStateVersion() != currentVersion) {
                sendError(principal.getUserId(), request.commandId(), ErrorCode.STALE_STATE,
                        "客户端状态版本过期，请先同步最新盘面", currentVersion);
                return;
            }

            // 5. 坐标越界校验
            int row = request.row();
            int col = request.col();
            if (!GomokuReferee.isValidCoordinate(row, col)) {
                sendError(principal.getUserId(), request.commandId(), ErrorCode.INVALID_COORDINATES,
                        "落子坐标超出棋盘范围 [0, 14]", currentVersion);
                return;
            }

            // 6. 目标格子占位校验
            if (!GomokuReferee.isCellEmpty(room.getBoard(), row, col)) {
                sendError(principal.getUserId(), request.commandId(), ErrorCode.CELL_OCCUPIED,
                        "目标坐标已有落子", currentVersion);
                return;
            }

            // --- 校验全部通过，执行落子写入 ---
            room.getBoard()[row][col] = userColor.getValue();
            room.setOccupiedCount(room.getOccupiedCount() + 1);

            int stepNumber = room.getMoveHistory().size() + 1;
            long now = System.currentTimeMillis();
            Move move = new Move(stepNumber, userColor, row, col, now);
            room.getMoveHistory().add(move);

            // 裁决胜负与平局
            boolean isWin = GomokuReferee.checkWin(room.getBoard(), row, col, userColor);
            boolean isGameOver = false;
            PieceColor winner = null;
            FinishReason finishReason = null;
            PieceColor nextTurn = null;
            long turnDeadline = 0;

            if (isWin) {
                isGameOver = true;
                winner = userColor;
                finishReason = FinishReason.FIVE_IN_A_ROW;
                room.setStatus(GameStatus.FINISHED);
                room.setWinner(winner);
                room.setFinishReason(finishReason);
                room.setFinishedAt(now);
            } else {
                boolean isDraw = GomokuReferee.checkDraw(room.getOccupiedCount(), false);
                if (isDraw) {
                    isGameOver = true;
                    finishReason = FinishReason.DRAW;
                    room.setStatus(GameStatus.FINISHED);
                    room.setWinner(null);
                    room.setFinishReason(finishReason);
                    room.setFinishedAt(now);
                } else {
                    nextTurn = userColor.opposite();
                    room.setCurrentTurn(nextTurn);
                    turnDeadline = now + DEFAULT_TURN_TIMEOUT_MS;
                    room.setTurnDeadline(turnDeadline);
                }
            }

            long newVersion = room.nextVersion();

            moveEvent = new MoveCommittedEvent(
                    MoveCommittedEvent.EVENT_TYPE,
                    roomId,
                    room.getGameId(),
                    newVersion,
                    request.commandId(),
                    stepNumber,
                    userColor,
                    row,
                    col,
                    nextTurn,
                    turnDeadline,
                    isGameOver,
                    winner,
                    finishReason,
                    now
            );

            if (isGameOver) {
                finishedEvent = new GameFinishedEvent(
                        GameFinishedEvent.EVENT_TYPE,
                        roomId,
                        room.getGameId(),
                        newVersion,
                        winner,
                        finishReason,
                        null,
                        now
                );
            }
        } finally {
            room.getRoomLock().unlock();
        }

        // 锁外异步广播事件
        if (moveEvent != null) {
            messagingTemplate.convertAndSend("/topic/room/" + roomId + "/gameplay", moveEvent);
        }
        if (finishedEvent != null) {
            messagingTemplate.convertAndSend("/topic/room/" + roomId + "/gameplay", finishedEvent);
        }
    }

    /**
     * 处理玩家认输
     */
    public void handleSurrender(SurrenderRequest request, UserPrincipal principal) {
        String roomId = request.roomId();
        GameRoom room = rooms.get(roomId);

        if (room == null) {
            sendError(principal.getUserId(), request.commandId(), ErrorCode.GAME_NOT_PLAYING,
                    "房间不存在", 0);
            return;
        }

        GameFinishedEvent finishedEvent = null;

        room.getRoomLock().lock();
        try {
            long currentVersion = room.getStateVersion();
            if (room.getStatus() != GameStatus.PLAYING) {
                sendError(principal.getUserId(), request.commandId(), ErrorCode.GAME_NOT_PLAYING,
                        "当前对局未在进行中，无法认输", currentVersion);
                return;
            }

            PieceColor userColor = room.getPlayerColor(principal.getUserId());
            if (userColor == null) {
                sendError(principal.getUserId(), request.commandId(), ErrorCode.NOT_A_PLAYER,
                        "观战者无权发起认输", currentVersion);
                return;
            }

            // 对手直接判定获胜
            PieceColor winner = userColor.opposite();
            long now = System.currentTimeMillis();
            room.setStatus(GameStatus.FINISHED);
            room.setWinner(winner);
            room.setFinishReason(FinishReason.SURRENDER);
            room.setFinishedAt(now);

            long newVersion = room.nextVersion();

            finishedEvent = new GameFinishedEvent(
                    GameFinishedEvent.EVENT_TYPE,
                    roomId,
                    room.getGameId(),
                    newVersion,
                    winner,
                    FinishReason.SURRENDER,
                    null,
                    now
            );
        } finally {
            room.getRoomLock().unlock();
        }

        if (finishedEvent != null) {
            messagingTemplate.convertAndSend("/topic/room/" + roomId + "/gameplay", finishedEvent);
        }
    }

    /**
     * 构建房间状态同步事件
     */
    private RoomStateUpdateEvent buildStateUpdateEvent(GameRoom room) {
        PlayerInfo playerBlack = room.getPlayerBlackId() != null
                ? new PlayerInfo(room.getPlayerBlackId(), room.getPlayerBlackName())
                : null;
        PlayerInfo playerWhite = room.getPlayerWhiteId() != null
                ? new PlayerInfo(room.getPlayerWhiteId(), room.getPlayerWhiteName())
                : null;

        return new RoomStateUpdateEvent(
                RoomStateUpdateEvent.EVENT_TYPE,
                room.getRoomId(),
                room.getGameId(),
                room.getStateVersion(),
                room.getStatus(),
                "FREESTYLE",
                playerBlack,
                playerWhite,
                room.getCurrentTurn(),
                room.getOccupiedCount(),
                room.getTurnDeadline(),
                System.currentTimeMillis()
        );
    }

    /**
     * 广播房间状态
     */
    private void broadcastRoomState(String roomId, RoomStateUpdateEvent stateEvent) {
        messagingTemplate.convertAndSend("/topic/room/" + roomId + "/state", stateEvent);
    }

    /**
     * 向用户私有队列发送错误响应
     */
    private void sendError(String userId, String commandId, ErrorCode errorCode, String errorMessage, long currentVersion) {
        ErrorResponse errorResponse = new ErrorResponse(
                commandId,
                errorCode,
                errorMessage,
                currentVersion,
                System.currentTimeMillis()
        );
        messagingTemplate.convertAndSendToUser(userId, "/queue/errors", errorResponse);
    }
}
