package com.myai.gateway.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT 工具类
 * 用于生成和验证 JWT Token
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    @Value("${app.jwt.secret:my-ai-gateway-jwt-secret-key-2024-change-in-production}")
    private String jwtSecret;

    @Value("${app.jwt.expiration:28800000}") // 默认 8 小时 = 8 * 60 * 60 * 1000
    private long jwtExpiration;

    /**
     * 生成 JWT Token
     *
     * @param username 用户名
     * @param extraClaims 额外的声明
     * @return JWT Token 字符串
     */
    public String generateToken(String username, Map<String, Object> extraClaims) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpiration);

        return Jwts.builder()
                .claims(extraClaims)
                .subject(username)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 生成简单的 JWT Token（仅包含用户名）
     *
     * @param username 用户名
     * @return JWT Token 字符串
     */
    public String generateToken(String username) {
        return generateToken(username, Map.of());
    }

    /**
     * 验证 JWT Token 是否有效
     *
     * @param token JWT Token 字符串
     * @return true 如果 token 有效，否则 false
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (SecurityException ex) {
            log.debug("JWT 签名无效: {}", ex.getMessage());
        } catch (MalformedJwtException ex) {
            log.debug("JWT 格式错误: {}", ex.getMessage());
        } catch (ExpiredJwtException ex) {
            log.debug("JWT 已过期: {}", ex.getMessage());
        } catch (UnsupportedJwtException ex) {
            log.debug("不支持的 JWT: {}", ex.getMessage());
        } catch (IllegalArgumentException ex) {
            log.debug("JWT 参数无效: {}", ex.getMessage());
        }
        return false;
    }

    /**
     * 从 JWT Token 中获取用户名
     *
     * @param token JWT Token 字符串
     * @return 用户名
     */
    public String getUsernameFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }

    /**
     * 获取 Token 过期时间（毫秒）
     */
    public long getExpiration() {
        return jwtExpiration;
    }

    /**
     * 获取签名密钥
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}