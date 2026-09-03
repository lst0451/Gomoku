package com.gomoku.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 令牌提供者 (TokenProvider)
 * 基于标准 HMAC-SHA256 算法签发与校验 JWT (JSON Web Token)
 * 支持正式用户 Token 与访客 Guest Token
 */
@Component
public class TokenProvider {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final long DEFAULT_TTL_MS = 24 * 60 * 60 * 1000L; // 24小时有效期
    private static final String HEADER_JSON_BASE64;

    static {
        // {"alg":"HS256","typ":"JWT"} 的 Base64Url 编码
        HEADER_JSON_BASE64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    }

    private final byte[] secretKeyBytes;
    private final ObjectMapper objectMapper;

    public TokenProvider(
            @Value("${gomoku.jwt.secret:gomoku-in-memory-hmac-sha256-secret-key-must-be-secure-and-long-enough}") String secret,
            ObjectMapper objectMapper) {
        this.secretKeyBytes = secret.getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    /**
     * 为正式用户签发标准 JWT
     *
     * @param userId   用户唯一标识
     * @param username 用户昵称
     * @return JWT 字符串
     */
    public String generateToken(String userId, String username) {
        return generateToken(userId, username, false, DEFAULT_TTL_MS);
    }

    /**
     * 为匿名访客签发 Guest Token
     *
     * @param username 访客指定或默认昵称
     * @return JWT 字符串
     */
    public String generateGuestToken(String username) {
        String guestId = "guest-" + UUID.randomUUID().toString().substring(0, 8);
        String name = (username != null && !username.isBlank()) ? username : "Guest_" + guestId.substring(6);
        return generateToken(guestId, name, true, DEFAULT_TTL_MS);
    }

    /**
     * 签发携带自定义参数的 JWT
     *
     * @param userId    用户ID
     * @param username  用户昵称
     * @param isGuest   是否访客
     * @param ttlMillis 有效期毫秒数
     * @return 签名的 JWT 字符串
     */
    public String generateToken(String userId, String username, boolean isGuest, long ttlMillis) {
        long now = System.currentTimeMillis();
        long exp = now + ttlMillis;

        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", userId);
        claims.put("username", username);
        claims.put("isGuest", isGuest);
        claims.put("iat", now);
        claims.put("exp", exp);

        try {
            String payloadJson = objectMapper.writeValueAsString(claims);
            String payloadBase64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

            String dataToSign = HEADER_JSON_BASE64 + "." + payloadBase64;
            String signature = sign(dataToSign);

            return dataToSign + "." + signature;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate JWT token", e);
        }
    }

    /**
     * 校验 Token 并提取安全主体 UserPrincipal
     *
     * @param rawToken 原始 Token 字符串（支持携带或不携带 "Bearer " 前缀）
     * @return 解析后的 UserPrincipal
     * @throws SecurityException 若 Token 缺失、格式错误、签名不匹配或已过期
     */
    public UserPrincipal validateAndExtractPrincipal(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new SecurityException("Missing authentication token");
        }

        String token = rawToken.trim();
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7).trim();
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new SecurityException("Malformed JWT token: expected 3 parts");
        }

        String headerBase64 = parts[0];
        String payloadBase64 = parts[1];
        String signature = parts[2];

        // 校验签名 (使用时间恒定比较防止时序侧信道攻击)
        String expectedSignature = sign(headerBase64 + "." + payloadBase64);
        if (!MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8),
                expectedSignature.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("Invalid JWT signature");
        }

        // 解析 Payload 内容
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(payloadBase64);
            Map<String, Object> claims = objectMapper.readValue(payloadBytes, new TypeReference<>() {});

            Number expNum = (Number) claims.get("exp");
            if (expNum == null || System.currentTimeMillis() > expNum.longValue()) {
                throw new SecurityException("JWT token has expired");
            }

            String userId = (String) claims.get("sub");
            if (userId == null || userId.isBlank()) {
                throw new SecurityException("JWT token missing subject (userId)");
            }

            String username = (String) claims.get("username");
            Boolean isGuest = (Boolean) claims.get("isGuest");

            return new UserPrincipal(userId, username, Boolean.TRUE.equals(isGuest));
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            throw new SecurityException("Failed to parse JWT payload", e);
        }
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec secretKey = new SecretKeySpec(secretKeyBytes, HMAC_ALGORITHM);
            mac.init(secretKey);
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256 signature", e);
        }
    }
}
