package com.gomoku.model.entity;

import com.gomoku.model.enums.FinishReason;
import com.gomoku.model.enums.GameStatus;
import com.gomoku.model.enums.PieceColor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 完整房间聚合根模型 (GameRoom Aggregate Root)
 * 每个房间实例独占 ReentrantLock 并维护单调递增的 stateVersion
 */
public class GameRoom {

    public static final int BOARD_SIZE = 15;
    public static final int TOTAL_CELLS = BOARD_SIZE * BOARD_SIZE;

    // 基础元数据
    private final String roomId;
    private String gameId;                        // 对局唯一标识 (重开对局刷新)
    private long stateVersion;                    // 房间状态版本号 (单调递增，每次状态变更 +1)
    private GameStatus status;
    private FinishReason finishReason;
    private PieceColor winner;                    // 胜利方 (null 表示未决或平局)
    private boolean isAiMode;                     // 是否为人机模式
    private String aiDifficulty;                  // AI 难度 (EASY / MEDIUM / HARD)

    // 席位与连接管理
    private String playerBlackId;                 // 黑方用户ID
    private String playerBlackName;               // 黑方昵称
    private String playerWhiteId;                 // 白方用户ID
    private String playerWhiteName;               // 白方昵称
    private final Map<String, Set<String>> userActiveSessions = new ConcurrentHashMap<>(); // userId -> active sessionIds
    private final Set<String> spectatorUserIds = ConcurrentHashMap.newKeySet();           // 观战用户ID集合

    // 棋局核心状态
    private PieceColor currentTurn;               // 当前行动方
    private final int[][] board = new int[BOARD_SIZE][BOARD_SIZE];
    private int occupiedCount;                    // 当前盘面落子数 (0..225)
    private final List<Move> moveHistory = new ArrayList<>(); // 落子历史

    // 倒计时与时效控制
    private long turnDeadline;                    // 当前回合截止时间戳
    private long disconnectDeadline;              // 掉线重连截止时间戳
    private ScheduledFuture<?> currentTurnTimer;  // 当前回合超时定时器引用
    private ScheduledFuture<?> currentDisconnectTimer; // 当前断线重连定时器引用

    // 聊天有界缓存 (最大保留 50 条)
    private final Deque<ChatMessage> recentChats = new ArrayDeque<>(50);

    // 生命周期审计时间戳
    private final long createdAt;
    private Long startedAt;
    private Long finishedAt;
    private volatile long lastActivityAt;

    // 房间实例并发控制锁
    private final ReentrantLock roomLock = new ReentrantLock();

    public GameRoom(String roomId) {
        this.roomId = Objects.requireNonNull(roomId, "roomId cannot be null");
        this.status = GameStatus.WAITING;
        this.stateVersion = 0;
        this.createdAt = System.currentTimeMillis();
        this.lastActivityAt = this.createdAt;
    }

    /**
     * 版本自增与活跃时间刷新 (锁内执行)
     *
     * @return 自增后的版本号
     */
    public long nextVersion() {
        this.lastActivityAt = System.currentTimeMillis();
        return ++this.stateVersion;
    }

    /**
     * 重置棋局状态为初始对战盘面
     */
    public void resetGame(String newGameId) {
        this.gameId = newGameId;
        this.occupiedCount = 0;
        this.moveHistory.clear();
        for (int[] row : board) {
            Arrays.fill(row, 0);
        }
        this.winner = null;
        this.finishReason = null;
        this.currentTurn = PieceColor.BLACK;
        this.status = GameStatus.PLAYING;
        this.startedAt = System.currentTimeMillis();
        this.finishedAt = null;
    }

    /**
     * 校验用户是否为房间成员 (选手或观战者)
     */
    public boolean isUserInRoom(String userId) {
        if (userId == null) return false;
        return userId.equals(playerBlackId)
                || userId.equals(playerWhiteId)
                || spectatorUserIds.contains(userId);
    }

    /**
     * 获取指定用户的选手棋色 (黑/白)，若为观战者或未加入则返回 null
     */
    public PieceColor getPlayerColor(String userId) {
        if (userId == null) return null;
        if (userId.equals(playerBlackId)) return PieceColor.BLACK;
        if (userId.equals(playerWhiteId)) return PieceColor.WHITE;
        return null;
    }

    /**
     * 记录指定用户的会话
     */
    public void addSession(String userId, String sessionId) {
        if (userId != null && sessionId != null) {
            userActiveSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
        }
    }

    /**
     * 移除指定用户的会话
     */
    public void removeSession(String userId, String sessionId) {
        if (userId != null && sessionId != null) {
            Set<String> sessions = userActiveSessions.get(userId);
            if (sessions != null) {
                sessions.remove(sessionId);
            }
        }
    }

    /**
     * 判断用户是否仍有活跃连接会话
     */
    public boolean hasActiveSessions(String userId) {
        Set<String> sessions = userActiveSessions.get(userId);
        return sessions != null && !sessions.isEmpty();
    }

    // --- Getters & Setters ---

    public String getRoomId() { return roomId; }
    public String getGameId() { return gameId; }
    public void setGameId(String gameId) { this.gameId = gameId; }
    public long getStateVersion() { return stateVersion; }
    public GameStatus getStatus() { return status; }
    public void setStatus(GameStatus status) { this.status = status; }
    public FinishReason getFinishReason() { return finishReason; }
    public void setFinishReason(FinishReason finishReason) { this.finishReason = finishReason; }
    public PieceColor getWinner() { return winner; }
    public void setWinner(PieceColor winner) { this.winner = winner; }
    public boolean isAiMode() { return isAiMode; }
    public void setAiMode(boolean aiMode) { isAiMode = aiMode; }
    public String getAiDifficulty() { return aiDifficulty; }
    public void setAiDifficulty(String aiDifficulty) { this.aiDifficulty = aiDifficulty; }

    public String getPlayerBlackId() { return playerBlackId; }
    public void setPlayerBlackId(String playerBlackId) { this.playerBlackId = playerBlackId; }
    public String getPlayerBlackName() { return playerBlackName; }
    public void setPlayerBlackName(String playerBlackName) { this.playerBlackName = playerBlackName; }

    public String getPlayerWhiteId() { return playerWhiteId; }
    public void setPlayerWhiteId(String playerWhiteId) { this.playerWhiteId = playerWhiteId; }
    public String getPlayerWhiteName() { return playerWhiteName; }
    public void setPlayerWhiteName(String playerWhiteName) { this.playerWhiteName = playerWhiteName; }

    public Map<String, Set<String>> getUserActiveSessions() { return userActiveSessions; }
    public Set<String> getSpectatorUserIds() { return spectatorUserIds; }

    public PieceColor getCurrentTurn() { return currentTurn; }
    public void setCurrentTurn(PieceColor currentTurn) { this.currentTurn = currentTurn; }
    public int[][] getBoard() { return board; }
    public int getOccupiedCount() { return occupiedCount; }
    public void setOccupiedCount(int occupiedCount) { this.occupiedCount = occupiedCount; }
    public List<Move> getMoveHistory() { return moveHistory; }

    public long getTurnDeadline() { return turnDeadline; }
    public void setTurnDeadline(long turnDeadline) { this.turnDeadline = turnDeadline; }
    public long getDisconnectDeadline() { return disconnectDeadline; }
    public void setDisconnectDeadline(long disconnectDeadline) { this.disconnectDeadline = disconnectDeadline; }

    public ScheduledFuture<?> getCurrentTurnTimer() { return currentTurnTimer; }
    public void setCurrentTurnTimer(ScheduledFuture<?> currentTurnTimer) { this.currentTurnTimer = currentTurnTimer; }
    public ScheduledFuture<?> getCurrentDisconnectTimer() { return currentDisconnectTimer; }
    public void setCurrentDisconnectTimer(ScheduledFuture<?> currentDisconnectTimer) { this.currentDisconnectTimer = currentDisconnectTimer; }

    public Deque<ChatMessage> getRecentChats() { return recentChats; }

    public long getCreatedAt() { return createdAt; }
    public Long getStartedAt() { return startedAt; }
    public void setStartedAt(Long startedAt) { this.startedAt = startedAt; }
    public Long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Long finishedAt) { this.finishedAt = finishedAt; }
    public long getLastActivityAt() { return lastActivityAt; }

    public ReentrantLock getRoomLock() { return roomLock; }
}
