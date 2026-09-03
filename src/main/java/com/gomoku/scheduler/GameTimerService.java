package com.gomoku.scheduler;

import com.gomoku.model.entity.GameRoom;
import com.gomoku.service.GameRoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/**
 * 游戏倒计时调度中枢 (GameTimerService)
 * 基于独立的 ThreadPoolTaskScheduler 实现回合超时与断线保留期调度
 */
@Service
public class GameTimerService {

    private static final Logger log = LoggerFactory.getLogger(GameTimerService.class);

    private final ThreadPoolTaskScheduler taskScheduler;
    private final GameRoomService gameRoomService;

    public GameTimerService(@Lazy GameRoomService gameRoomService) {
        this.gameRoomService = gameRoomService;
        this.taskScheduler = new ThreadPoolTaskScheduler();
        this.taskScheduler.setPoolSize(4);
        this.taskScheduler.setThreadNamePrefix("game-timer-");
        this.taskScheduler.setRemoveOnCancelPolicy(true);
        this.taskScheduler.initialize();
    }

    /**
     * 注册当前回合落子超时倒计时任务 (30秒)
     *
     * @param roomId      房间ID
     * @param context     超时竞态快照四元组
     * @param delayMillis 延迟触发毫秒数
     * @return 调度任务句柄
     */
    public ScheduledFuture<?> scheduleTurnTimeout(String roomId, TimeoutContext context, long delayMillis) {
        GameRoom room = gameRoomService.getRoom(roomId);
        if (room == null) {
            return null;
        }

        cancelTurnTimer(room);

        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> {
                    try {
                        gameRoomService.handleTurnTimeout(roomId, context);
                    } catch (Exception e) {
                        log.error("Error executing turn timeout for room {}: {}", roomId, e.getMessage(), e);
                    }
                },
                Instant.ofEpochMilli(System.currentTimeMillis() + delayMillis)
        );

        room.setCurrentTurnTimer(future);
        log.debug("Scheduled turn timeout for room {}, delay={}ms, context={}", roomId, delayMillis, context);
        return future;
    }

    /**
     * 注册选手掉线重连保留期倒计时任务 (45秒)
     *
     * @param roomId               房间ID
     * @param disconnectedUserId   掉线选手用户ID
     * @param expectedStateVersion 期望房间状态版本号
     * @param delayMillis          延迟触发毫秒数
     * @return 调度任务句柄
     */
    public ScheduledFuture<?> scheduleDisconnectTimeout(String roomId, String disconnectedUserId,
                                                         long expectedStateVersion, long delayMillis) {
        GameRoom room = gameRoomService.getRoom(roomId);
        if (room == null) {
            return null;
        }

        cancelDisconnectTimer(room);

        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> {
                    try {
                        gameRoomService.handleDisconnectTimeout(roomId, disconnectedUserId, expectedStateVersion);
                    } catch (Exception e) {
                        log.error("Error executing disconnect timeout for room {}: {}", roomId, e.getMessage(), e);
                    }
                },
                Instant.ofEpochMilli(System.currentTimeMillis() + delayMillis)
        );

        room.setCurrentDisconnectTimer(future);
        log.info("Scheduled disconnect grace timeout for room {}, user={}, version={}, delay={}ms",
                roomId, disconnectedUserId, expectedStateVersion, delayMillis);
        return future;
    }

    /**
     * 安全取消当前回合倒计时任务
     */
    public void cancelTurnTimer(GameRoom room) {
        if (room != null && room.getCurrentTurnTimer() != null) {
            room.getCurrentTurnTimer().cancel(false);
            room.setCurrentTurnTimer(null);
        }
    }

    /**
     * 安全取消掉线保留期倒计时任务
     */
    public void cancelDisconnectTimer(GameRoom room) {
        if (room != null && room.getCurrentDisconnectTimer() != null) {
            room.getCurrentDisconnectTimer().cancel(false);
            room.setCurrentDisconnectTimer(null);
        }
    }

    /**
     * 取消房间关联的所有定时任务 (销毁或重置时使用)
     */
    public void cancelAllTimers(GameRoom room) {
        cancelTurnTimer(room);
        cancelDisconnectTimer(room);
    }
}
