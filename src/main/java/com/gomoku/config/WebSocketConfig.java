package com.gomoku.config;

import com.gomoku.security.AuthChannelInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket 与 STOMP 协议基础配置类
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * 显式跨域允许源模式，杜绝使用通配符 "*"
     */
    public static final String[] ALLOWED_ORIGIN_PATTERNS = {
        "http://localhost:*",
        "http://127.0.0.1:*",
        "https://localhost:*",
        "https://127.0.0.1:*"
    };

    private final AuthChannelInterceptor authChannelInterceptor;

    public WebSocketConfig(@org.springframework.context.annotation.Lazy AuthChannelInterceptor authChannelInterceptor) {
        this.authChannelInterceptor = authChannelInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 注册标准 WebSocket 端点
        registry.addEndpoint("/ws-gomoku")
                .setAllowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS);

        // 注册支持 SockJS 降级协议的端点
        registry.addEndpoint("/ws-gomoku")
                .setAllowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS)
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 启用内存消息代理，广播使用 /topic，单播/队列使用 /queue
        registry.enableSimpleBroker("/topic", "/queue");

        // 客户端发往服务端的应用消息前缀
        registry.setApplicationDestinationPrefixes("/app");

        // 用户点对点单播前缀 (例如 /user/queue/errors)
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // 注册入站消息安全拦截器
        registration.interceptors(authChannelInterceptor);
    }
}
