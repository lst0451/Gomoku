package com.gomoku.observability;

import com.gomoku.model.entity.GameRoom;
import com.gomoku.model.enums.GameStatus;
import com.gomoku.service.GameRoomService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Micrometer 监控指标配置 (MetricsConfig)
 * 注册活跃房间、在线用户、落子计数及 AI 耗时监控指标
 */
@Configuration
public class MetricsConfig {

    private final MeterRegistry meterRegistry;
    private final GameRoomService gameRoomService;

    public MetricsConfig(MeterRegistry meterRegistry, GameRoomService gameRoomService) {
        this.meterRegistry = meterRegistry;
        this.gameRoomService = gameRoomService;
        registerGauges();
    }

    private void registerGauges() {
        // 1. 活跃房间数 (未终局房间)
        Gauge.builder("gomoku.rooms.active", gameRoomService, service ->
                service.getAllRooms().values().stream()
                        .filter(r -> r.getStatus() != GameStatus.FINISHED)
                        .count()
        ).description("当前未终局的活跃对战房间总数").register(meterRegistry);

        // 2. 当前在线用户总数
        Gauge.builder("gomoku.players.online", gameRoomService, service -> {
            Set<String> uniqueUsers = service.getAllRooms().values().stream()
                    .flatMap(r -> r.getUserActiveSessions().keySet().stream())
                    .collect(Collectors.toSet());
            return uniqueUsers.size();
        }).description("当前活跃的在线玩家与观战者总数").register(meterRegistry);
    }

    @Bean
    public Counter totalMovesCounter() {
        return Counter.builder("gomoku.moves.total")
                .description("平台累计有效落子总步数")
                .register(meterRegistry);
    }

    @Bean
    public Counter rejectedMovesCounter() {
        return Counter.builder("gomoku.moves.rejected")
                .description("平台被拒绝的非法落子请求总数")
                .register(meterRegistry);
    }

    @Bean
    public Timer aiComputeTimer() {
        return Timer.builder("gomoku.ai.compute.time")
                .description("AI 决策耗时分布 (P50, P95, P99)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }
}
