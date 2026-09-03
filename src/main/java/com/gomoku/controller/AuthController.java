package com.gomoku.controller;

import com.gomoku.security.TokenProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 认证与令牌获取控制器 (AuthController)
 * 为前端演示页面提供自签发 Token 与匿名访客 Token
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final TokenProvider tokenProvider;

    public AuthController(TokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    /**
     * 为指定用户生成测试/正式 JWT
     */
    @GetMapping("/token")
    public ResponseEntity<Map<String, String>> getToken(
            @RequestParam(defaultValue = "Alice") String username,
            @RequestParam(required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            userId = "U-" + username.toLowerCase();
        }
        String token = tokenProvider.generateToken(userId, username);
        return ResponseEntity.ok(Map.of(
                "token", token,
                "userId", userId,
                "username", username
        ));
    }

    /**
     * 生成匿名访客 Token
     */
    @GetMapping("/guest")
    public ResponseEntity<Map<String, String>> getGuestToken(
            @RequestParam(defaultValue = "Guest") String prefix) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 4);
        String token = tokenProvider.generateGuestToken(username);
        String userId = tokenProvider.validateAndExtractPrincipal(token).getUserId();
        return ResponseEntity.ok(Map.of(
                "token", token,
                "userId", userId,
                "username", username
        ));
    }
}
