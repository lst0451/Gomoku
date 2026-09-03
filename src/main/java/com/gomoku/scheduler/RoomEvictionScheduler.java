package com.gomoku.scheduler;

import com.gomoku.model.entity.GameRoom;
import com.gomoku.model.enums.GameStatus;
import com.gomoku.service.GameRoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 房间生命周期与内存防泄漏回收调度器 (RoomEvictionScheduler)
 * 定时回收超期空房间 (存活/无活跃会话 > 10 分钟) 与超期终局房间 (结束 > 5 分钟)
 */
@Component
public class RoomEvictionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RoomEvictionScheduler.class);

    public static final long DEFAULT_EMPTY_ROOM_TIMEOUT_MS = 10 * 60 * 1000L;   // 10 分钟
    public static final long DEFAULT_FINISHED_ROOM_TIMEOUT_MS = 5 * 60 * 1000L;  // 5 分钟

    private final GameRoomService gameRoomService;
    private final GameTimerService gameTimerService;

    public RoomEvictionScheduler(GameRoomService gameRoomService, GameTimerService gameTimerService) {
        this.gameRoomService = gameRoomService;
        this.gameTimerService = gameTimerService;
    }

    /**
     * 每分钟执行一次废弃房间清理扫描
     */
    @Scheduled(fixedRate = 60000)
    public void scheduleEvictionScan() {
        evictStaleRooms(DEFAULT_EMPTY_ROOM_TIMEOUT_MS, DEFAULT_FINISHED_ROOM_TIMEOUT_MS);
    }

    /**
     * 执行房间回收逻辑 (支持自定义超时阈值，便于单元与基准测试调用)
     *
     * @param emptyTimeoutMs    空房间最大闲置时间 (毫秒)
     * @param finishedTimeoutMs 终局房间最大保留时间 (毫秒)
     * @return 本次回收的房间数量
     */
    public int evictStaleRooms(long emptyTimeoutMs, long finishedTimeoutMs) {
        long now = System.currentTimeMillis();
        int evictedCount = 0;

        for (Map.Entry<String, GameRoom> entry : gameRoomService.getAllRooms().entrySet()) {
            String roomId = entry.getKey();
            GameRoom room = entry.getValue();

            boolean shouldEvict = false;
            room.getRoomLock().lock();
            try {
                // 1. 回收超期终局房间
                if (room.getStatus() == GameStatus.FINISHED) {
                    Long finishedAt = room.getFinishedAt();
                    if (finishedAt != null && (now - finishedAt >= finishedTimeoutMs)) {
                        shouldEvict = true;
                        log.info("Evicting FINISHED room {} (finished {}ms ago)", roomId, now - finishedAt);
                    }
                }
                // 2. 回收超期闲置/空房间 (无活跃 Session 且长时间无活动)
                else if (room.getUserActiveSessions().isEmpty() && (now - room.getLastActivityAt() >= emptyTimeoutMs)) {
                    shouldEvict = true;
                    log.info("Evicting EMPTY room {} (inactive {}ms ago)", roomId, now - room.getLastActivityAt());
                }

                if (shouldEvict) {
                    // 显式取消未完成的 Future 任务，杜绝内存与线程泄漏
                    gameTimerService.cancelAllTimers(room);
                }
            } finally {
                room.getRoomLock().unlock();
            }

            if (shouldEvict) {
                gameRoomService.removeRoom(roomId);
                evictedCount++;
            }
        }

        if (evictedCount > 0) {
            log.info("Room eviction cycle completed: removed {} stale room(s)", evictedCount);
        }
        return evictedCount;
    }
}
