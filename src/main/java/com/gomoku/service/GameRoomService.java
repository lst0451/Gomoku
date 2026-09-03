package com.gomoku.service;

import com.gomoku.ai.AiDifficulty;
import com.gomoku.ai.BoardSnapshot;
import com.gomoku.ai.GomokuAiService;
import com.gomoku.dto.request.AiSwitchRequest;
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
import com.gomoku.scheduler.GameTimerService;
import com.gomoku.scheduler.TimeoutContext;
import com.gomoku.security.DefaultRoomMembershipValidator;
import com.gomoku.security.RoomMembershipValidator;
import com.gomoku.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 房间管理与对战串行化调度服务 (GameRoomService)
 * 1. 维护并发房间池 ConcurrentHashMap<String, GameRoom>
 * 2. 所有写操作（加入、落子、认输、AI切换、掉线）在房间独占 ReentrantLock 内执行校验与状态流转
 * 3. 递增单调版本号 stateVersion，锁外异步广播事件与单播错误
 * 4. 集成 GameTimerService 防竞态倒计时与 SessionDisconnect 掉线保留期 (45s)
 * 5. 集成 GomokuAiService 进程内传统博弈树 AI 对战与版本过期丢弃机制
 */
@Service
@Primary
public class GameRoomService implements RoomMembershipValidator {

    private static final Logger log = LoggerFactory.getLogger(GameRoomService.class);

    public static final long DEFAULT_TURN_TIMEOUT_MS = 30_000L;       // 30秒落子倒计时
    public static final long DEFAULT_DISCONNECT_GRACE_MS = 45_000L;   // 45秒掉线保留期
    public static final String SYSTEM_AI_ID = "SYSTEM_AI";

    private final ConcurrentHashMap<String, GameRoom> rooms = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messagingTemplate;
    private final DefaultRoomMembershipValidator defaultRoomMembershipValidator;
    private final GameTimerService gameTimerService;
    private final GomokuAiService aiService;

    public GameRoomService(@Lazy SimpMessagingTemplate messagingTemplate,
                           DefaultRoomMembershipValidator defaultRoomMembershipValidator,
                           @Lazy GameTimerService gameTimerService,
                           @Lazy GomokuAiService aiService) {
        this.messagingTemplate = messagingTemplate;
        this.defaultRoomMembershipValidator = defaultRoomMembershipValidator;
        this.gameTimerService = gameTimerService;
        this.aiService = aiService;
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
     * 获取全部房间映射
     */
    public Map<String, GameRoom> getAllRooms() {
        return rooms;
    }

    /**
     * 移除房间
     */
    public void removeRoom(String roomId) {
        rooms.remove(roomId);
    }

    /**
     * 用户进入/重连房间业务处理
     */
    public void joinRoom(JoinRoomRequest request, UserPrincipal principal, String sessionId) {
        String roomId = request.roomId();
        GameRoom room = getOrCreateRoom(roomId);

        RoomStateUpdateEvent stateEvent;
        TimeoutContext timerContextToSchedule = null;

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
                long turnDeadline = System.currentTimeMillis() + DEFAULT_TURN_TIMEOUT_MS;
                room.setTurnDeadline(turnDeadline);
                log.info("Assigned PLAYER_WHITE and started game: userId={}, roomId={}, gameId={}",
                        userId, roomId, newGameId);

                // 启动先手 (黑方) 首步 30 秒落子倒计时
                long nextVer = room.getStateVersion() + 1;
                timerContextToSchedule = new TimeoutContext(newGameId, PieceColor.BLACK, nextVer, turnDeadline);
            } else {
                // 席位已满，自动转为观战者
                room.getSpectatorUserIds().add(userId);
                log.info("Assigned SPECTATOR: userId={}, roomId={}", userId, roomId);
            }

            // 若房间原本处于 PAUSED 状态且掉线选手已重连归来，恢复 PLAYING
            if (room.getStatus() == GameStatus.PAUSED) {
                boolean blackActive = room.hasActiveSessions(room.getPlayerBlackId());
                boolean whiteActive = room.hasActiveSessions(room.getPlayerWhiteId());
                if (blackActive && (room.isAiMode() || whiteActive)) {
                    gameTimerService.cancelDisconnectTimer(room);
                    room.setStatus(GameStatus.PLAYING);
                    long turnDeadline = System.currentTimeMillis() + DEFAULT_TURN_TIMEOUT_MS;
                    room.setTurnDeadline(turnDeadline);
                    long nextVer = room.getStateVersion() + 1;
                    timerContextToSchedule = new TimeoutContext(room.getGameId(), room.getCurrentTurn(), nextVer, turnDeadline);
                    log.info("Room {} resumed from PAUSED to PLAYING after player {} reconnected", roomId, userId);
                }
            }

            room.nextVersion();
            stateEvent = buildStateUpdateEvent(room);
        } finally {
            room.getRoomLock().unlock();
        }

        // 注册定时任务
        if (timerContextToSchedule != null) {
            gameTimerService.scheduleTurnTimeout(roomId, timerContextToSchedule, DEFAULT_TURN_TIMEOUT_MS);
        }

        // 锁外广播最新状态
        broadcastRoomState(roomId, stateEvent);
    }

    /**
     * 开启人机对战模式 (仅房主且处于 WAITING 状态时有权触发)
     */
    public void switchAi(AiSwitchRequest request, UserPrincipal principal) {
        String roomId = request.roomId();
        GameRoom room = rooms.get(roomId);

        if (room == null) {
            sendError(principal.getUserId(), request.commandId(), ErrorCode.GAME_NOT_PLAYING,
                    "房间不存在", 0);
            return;
        }

        RoomStateUpdateEvent stateEvent = null;
        TimeoutContext timerContext = null;

        room.getRoomLock().lock();
        try {
            long currentVersion = room.getStateVersion();
            // 仅黑方房主且处于 WAITING 状态下可切换 AI
            if (room.getStatus() != GameStatus.WAITING || !principal.getUserId().equals(room.getPlayerBlackId())) {
                sendError(principal.getUserId(), request.commandId(), ErrorCode.UNAUTHORIZED_ACTION,
                        "仅房间黑方选手在等待状态下有权开启人机对战", currentVersion);
                return;
            }

            room.setAiMode(true);
            room.setAiDifficulty(request.difficulty());
            room.setPlayerWhiteId(SYSTEM_AI_ID);
            room.setPlayerWhiteName("AlphaGomoku (" + request.difficulty() + ")");

            String newGameId = "G-AI-" + roomId + "-" + System.currentTimeMillis();
            room.resetGame(newGameId);
            long turnDeadline = System.currentTimeMillis() + DEFAULT_TURN_TIMEOUT_MS;
            room.setTurnDeadline(turnDeadline);
            long newVer = room.nextVersion();

            timerContext = new TimeoutContext(newGameId, PieceColor.BLACK, newVer, turnDeadline);
            stateEvent = buildStateUpdateEvent(room);
            log.info("AI mode switched: roomId={}, difficulty={}, gameId={}", roomId, request.difficulty(), newGameId);
        } finally {
            room.getRoomLock().unlock();
        }

        if (timerContext != null) {
            gameTimerService.scheduleTurnTimeout(roomId, timerContext, DEFAULT_TURN_TIMEOUT_MS);
        }
        if (stateEvent != null) {
            broadcastRoomState(roomId, stateEvent);
        }
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
        TimeoutContext nextTimerContext = null;
        BoardSnapshot aiSnapshot = null;
        String aiDifficulty = null;

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

            // --- 校验全部通过，取消上一回合倒计时 ---
            gameTimerService.cancelTurnTimer(room);

            // 执行落子写入
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
                gameTimerService.cancelAllTimers(room);
            } else {
                boolean isDraw = GomokuReferee.checkDraw(room.getOccupiedCount(), false);
                if (isDraw) {
                    isGameOver = true;
                    finishReason = FinishReason.DRAW;
                    room.setStatus(GameStatus.FINISHED);
                    room.setWinner(null);
                    room.setFinishReason(finishReason);
                    room.setFinishedAt(now);
                    gameTimerService.cancelAllTimers(room);
                } else {
                    nextTurn = userColor.opposite();
                    room.setCurrentTurn(nextTurn);
                    turnDeadline = now + DEFAULT_TURN_TIMEOUT_MS;
                    room.setTurnDeadline(turnDeadline);
                }
            }

            long newVersion = room.nextVersion();

            if (!isGameOver) {
                nextTimerContext = new TimeoutContext(room.getGameId(), nextTurn, newVersion, turnDeadline);

                // 若为人机模式且下一手为 AI (白方)，构建快照准备异步计算
                if (room.isAiMode() && nextTurn == PieceColor.WHITE) {
                    aiSnapshot = new BoardSnapshot(room.getBoard(), room.getOccupiedCount(), PieceColor.WHITE, newVersion);
                    aiDifficulty = room.getAiDifficulty();
                }
            }

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

        // 调度下一回合倒计时
        if (nextTimerContext != null) {
            gameTimerService.scheduleTurnTimeout(roomId, nextTimerContext, DEFAULT_TURN_TIMEOUT_MS);
        }

        // 锁外异步广播事件
        if (moveEvent != null) {
            messagingTemplate.convertAndSend("/topic/room/" + roomId + "/gameplay", moveEvent);
        }
        if (finishedEvent != null) {
            messagingTemplate.convertAndSend("/topic/room/" + roomId + "/gameplay", finishedEvent);
        }

        // 锁外拉起异步 AI 计算
        if (aiSnapshot != null && aiService != null) {
            final BoardSnapshot currentSnapshot = aiSnapshot;
            AiDifficulty diff = AiDifficulty.fromString(aiDifficulty);
            aiService.computeBestMoveAsync(currentSnapshot, diff, Duration.ofMillis(200))
                    .thenAccept(bestMove -> {
                        UserPrincipal aiPrincipal = new UserPrincipal(SYSTEM_AI_ID, "AlphaGomoku", false);
                        MoveRequest aiMoveRequest = new MoveRequest(
                                "ai-cmd-" + UUID.randomUUID(),
                                roomId,
                                currentSnapshot.baseStateVersion(), // 传递捕获时的版本号
                                bestMove.row(),
                                bestMove.col()
                        );
                        handleMove(aiMoveRequest, aiPrincipal);
                    })
                    .exceptionally(ex -> {
                        log.error("AI computation failed for room {}: {}", roomId, ex.getMessage());
                        return null;
                    });
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
            gameTimerService.cancelAllTimers(room);

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
     * 回合落子超时回调处理 (由 GameTimerService 触发)
     */
    public void handleTurnTimeout(String roomId, TimeoutContext context) {
        GameRoom room = rooms.get(roomId);
        if (room == null) {
            return;
        }

        GameFinishedEvent finishedEvent = null;
        room.getRoomLock().lock();
        try {
            // 防竞态四元组校验 (消除 ABA 竞态)
            if (room.getStatus() != GameStatus.PLAYING ||
                    !Objects.equals(room.getGameId(), context.gameId()) ||
                    room.getCurrentTurn() != context.expectedTurn() ||
                    room.getStateVersion() != context.expectedStateVersion()) {
                log.debug("Turn timeout discarded for room {}: version or turn mismatch (ABA avoided). expected={}, actualVer={}",
                        roomId, context, room.getStateVersion());
                return;
            }

            // 确认为真实超时，判定对方获胜
            PieceColor winner = context.expectedTurn().opposite();
            long now = System.currentTimeMillis();
            room.setStatus(GameStatus.FINISHED);
            room.setWinner(winner);
            room.setFinishReason(FinishReason.TURN_TIMEOUT);
            room.setFinishedAt(now);
            gameTimerService.cancelAllTimers(room);

            long newVersion = room.nextVersion();

            finishedEvent = new GameFinishedEvent(
                    GameFinishedEvent.EVENT_TYPE,
                    roomId,
                    room.getGameId(),
                    newVersion,
                    winner,
                    FinishReason.TURN_TIMEOUT,
                    null,
                    now
            );
            log.info("Turn timeout triggered for room {}: loser={}, winner={}",
                    roomId, context.expectedTurn(), winner);
        } finally {
            room.getRoomLock().unlock();
        }

        if (finishedEvent != null) {
            messagingTemplate.convertAndSend("/topic/room/" + roomId + "/gameplay", finishedEvent);
        }
    }

    /**
     * 处理 WebSocket 会话断开
     */
    public void handleSessionDisconnect(String sessionId, String userId) {
        for (GameRoom room : rooms.values()) {
            String roomId = room.getRoomId();
            RoomStateUpdateEvent stateEvent = null;
            long disconnectVer = 0;
            boolean scheduleGrace = false;

            room.getRoomLock().lock();
            try {
                if (!room.isUserInRoom(userId)) {
                    continue;
                }

                room.removeSession(userId, sessionId);

                // 若该用户是棋手且其全部 Session 均已离线
                boolean isPlayer = userId.equals(room.getPlayerBlackId()) || userId.equals(room.getPlayerWhiteId());
                if (isPlayer && !room.hasActiveSessions(userId)) {
                    if (room.getStatus() == GameStatus.PLAYING) {
                        room.setStatus(GameStatus.PAUSED);
                        gameTimerService.cancelTurnTimer(room);

                        long deadline = System.currentTimeMillis() + DEFAULT_DISCONNECT_GRACE_MS;
                        room.setDisconnectDeadline(deadline);
                        disconnectVer = room.nextVersion();
                        scheduleGrace = true;

                        stateEvent = buildStateUpdateEvent(room);
                        log.info("Player {} disconnected all sessions from room {}. Room PAUSED for 45s grace period.",
                                userId, roomId);
                    }
                }
            } finally {
                room.getRoomLock().unlock();
            }

            if (scheduleGrace) {
                gameTimerService.scheduleDisconnectTimeout(roomId, userId, disconnectVer, DEFAULT_DISCONNECT_GRACE_MS);
            }
            if (stateEvent != null) {
                broadcastRoomState(roomId, stateEvent);
            }
        }
    }

    /**
     * 掉线保留期超时处理 (由 GameTimerService 触发)
     */
    public void handleDisconnectTimeout(String roomId, String disconnectedUserId, long expectedStateVersion) {
        GameRoom room = rooms.get(roomId);
        if (room == null) {
            return;
        }

        GameFinishedEvent finishedEvent = null;
        room.getRoomLock().lock();
        try {
            // 核验：状态仍为 PAUSED、版本匹配、用户依然未重连
            if (room.getStatus() != GameStatus.PAUSED ||
                    room.getStateVersion() != expectedStateVersion ||
                    room.hasActiveSessions(disconnectedUserId)) {
                log.debug("Disconnect timeout discarded for room {}: user reconnected or state changed", roomId);
                return;
            }

            PieceColor loserColor = room.getPlayerColor(disconnectedUserId);
            PieceColor winner = loserColor != null ? loserColor.opposite() : null;
            long now = System.currentTimeMillis();

            room.setStatus(GameStatus.FINISHED);
            room.setWinner(winner);
            room.setFinishReason(FinishReason.DISCONNECT_TIMEOUT);
            room.setFinishedAt(now);
            gameTimerService.cancelAllTimers(room);

            long newVersion = room.nextVersion();

            finishedEvent = new GameFinishedEvent(
                    GameFinishedEvent.EVENT_TYPE,
                    roomId,
                    room.getGameId(),
                    newVersion,
                    winner,
                    FinishReason.DISCONNECT_TIMEOUT,
                    null,
                    now
            );
            log.info("Disconnect grace timeout expired for room {}: user {} failed to reconnect. Winner={}",
                    roomId, disconnectedUserId, winner);
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
