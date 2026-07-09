package com.myai.gateway.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
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

    /** 内置默认密钥，用于启动时检测是否仍为默认值 */
    private static final String DEFAULT_SECRET = "my-ai-gateway-jwt-secret-key-2024-change-in-production";

    /** HS256 所需的最小密钥长度（字节），低于此长度 JJWT 会抛 WeakKeyException */
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * 启动时校验 JWT 密钥强度。
     * <p>不强制 fail-fast 以避免破坏现有部署，但对以下情况打印 WARN 日志强烈提示：</p>
     * <ul>
     *   <li>密钥仍为内置默认值（生产环境可被伪造 Token 接管后台）</li>
     *   <li>密钥长度不足 32 字节（无法满足 HS256 安全强度要求）</li>
     * </ul>
     */
    @PostConstruct
    public void validateSecret() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            log.warn("⚠️ app.jwt.secret 为空！JWT 签名将失败，请通过环境变量 APP_JWT_SECRET 配置密钥");
            return;
        }
        if (DEFAULT_SECRET.equals(jwtSecret)) {
            log.warn("⚠️ 检测到 JWT 密钥仍为内置默认值！生产环境存在被伪造管理后台 Token 的风险，"
                    + "请通过环境变量 APP_JWT_SECRET 设置一个高熵随机密钥（建议 ≥ 64 字节）");
        }
        if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            log.warn("⚠️ app.jwt.secret 长度不足 {} 字节，无法满足 HS256 安全强度要求，"
                    + "请设置更长的密钥", MIN_SECRET_BYTES);
        }
    }

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