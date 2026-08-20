package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.myai.gateway.entity.AdminConfig;
import com.myai.gateway.mapper.AdminConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员配置服务
 * 管理管理员账号等配置
 */
@Service
public class AdminConfigService {

    private static final Logger log = LoggerFactory.getLogger(AdminConfigService.class);

    public static final String KEY_USERNAME = "username";
    public static final String KEY_PASSWORD = "password";

    /** 日志保留天数 */
    public static final String KEY_LOG_RETENTION_DAYS = "log_retention_days";
    /** 日志定时清理开关 */
    public static final String KEY_LOG_CLEANUP_ENABLED = "log_cleanup_enabled";

    /** 原始请求数据保留时长（小时），0=永久保留 */
    public static final String KEY_REQUEST_BODY_TTL_HOURS = "request_body_ttl_hours";
    /** retry/fail request data TTL (hours), 0=forever */
    public static final String KEY_RETRY_FAIL_TTL_HOURS = "retry_fail_ttl_hours";

    /** 原始请求数据保存级别：info=全部保存, warn=仅重试/错误时保存, error=仅失败时保存 */
    public static final String KEY_REQUEST_DATA_SAVE_LEVEL = "request_data_save_level";

    /** 渠道模型请求超时最小/最大时间（秒） */
    public static final String KEY_TIMEOUT_MIN_SECONDS = "timeout_min_seconds";
    public static final String KEY_TIMEOUT_MAX_SECONDS = "timeout_max_seconds";

    /** 熔断到期记录全量探测间隔（分钟），默认 30 */
    public static final String KEY_CIRCUIT_BREAKER_PROBE_INTERVAL_MINUTES = "circuit_breaker_probe_interval_minutes";

    /** 熔断触发式探测节流（秒），默认 6；0=不节流 */
    public static final String KEY_CIRCUIT_BREAKER_PROBE_THROTTLE_SECONDS = "circuit_breaker_probe_throttle_seconds";

    /** 渠道模型自动刷新间隔（分钟），默认 30 */
    public static final String KEY_CHANNEL_MODEL_REFRESH_INTERVAL_MINUTES = "channel_model_refresh_interval_minutes";

    private final AdminConfigMapper adminConfigMapper;

    public AdminConfigService(AdminConfigMapper adminConfigMapper) {
        this.adminConfigMapper = adminConfigMapper;
    }

    /**
     * 检查是否已配置管理员账号
     */
    public boolean hasAdminAccount() {
        String username = getValueByKey(KEY_USERNAME);
        String password = getValueByKey(KEY_PASSWORD);
        return username != null && !username.isEmpty()
                && password != null && !password.isEmpty();
    }

    /**
     * 获取用户名
     */
    public String getUsername() {
        return getValueByKey(KEY_USERNAME);
    }

    /**
     * 获取密码
     */
    public String getPassword() {
        return getValueByKey(KEY_PASSWORD);
    }

    /**
     * 根据 key 获取配置值
     */
    public String getValueByKey(String key) {
        return adminConfigMapper.getValueByKey(key);
    }

    /**
     * 获取所有配置
     */
    public List<AdminConfig> listAll() {
        return adminConfigMapper.selectList(
                new LambdaQueryWrapper<AdminConfig>().orderByAsc(AdminConfig::getId));
    }

    /**
     * 设置管理员账号
     * 如果已存在账号，则设置失败
     *
     * @param username 用户名
     * @param password 密码（明文，会加密存储）
     * @return 是否设置成功
     */
    @Transactional
    public boolean setAdminAccount(String username, String password) {
        // 检查是否已有账号
        if (hasAdminAccount()) {
            return false;
        }

        // 加密密码
        String encryptedPassword = encryptPassword(password);

        // 更新用户名
        LambdaUpdateWrapper<AdminConfig> usernameWrapper = new LambdaUpdateWrapper<AdminConfig>()
                .eq(AdminConfig::getConfigKey, KEY_USERNAME)
                .set(AdminConfig::getConfigValue, username)
                .set(AdminConfig::getUpdatedAt, LocalDateTime.now());
        adminConfigMapper.update(null, usernameWrapper);

        // 更新密码
        LambdaUpdateWrapper<AdminConfig> passwordWrapper = new LambdaUpdateWrapper<AdminConfig>()
                .eq(AdminConfig::getConfigKey, KEY_PASSWORD)
                .set(AdminConfig::getConfigValue, encryptedPassword)
                .set(AdminConfig::getUpdatedAt, LocalDateTime.now());
        adminConfigMapper.update(null, passwordWrapper);

        return true;
    }

    /**
     * 更新管理员账号
     *
     * @param username 用户名
     * @param password 密码（明文，会加密存储）
     * @return 是否更新成功
     */
    @Transactional
    public boolean updateAdminAccount(String username, String password) {
        String encryptedPassword = encryptPassword(password);

        // 更新用户名
        LambdaUpdateWrapper<AdminConfig> usernameWrapper = new LambdaUpdateWrapper<AdminConfig>()
                .eq(AdminConfig::getConfigKey, KEY_USERNAME)
                .set(AdminConfig::getConfigValue, username)
                .set(AdminConfig::getUpdatedAt, LocalDateTime.now());
        adminConfigMapper.update(null, usernameWrapper);

        // 更新密码
        LambdaUpdateWrapper<AdminConfig> passwordWrapper = new LambdaUpdateWrapper<AdminConfig>()
                .eq(AdminConfig::getConfigKey, KEY_PASSWORD)
                .set(AdminConfig::getConfigValue, encryptedPassword)
                .set(AdminConfig::getUpdatedAt, LocalDateTime.now());
        adminConfigMapper.update(null, passwordWrapper);

        return true;
    }

    /**
     * 验证账号密码
     *
     * @param username 用户名
     * @param password 密码（明文）
     * @return 是否验证通过
     */
    public boolean verify(String username, String password) {
        String storedUsername = getUsername();
        String storedPassword = getPassword();

        if (storedUsername == null || storedUsername.isEmpty()
                || storedPassword == null || storedPassword.isEmpty()) {
            return false;
        }

        return storedUsername.equals(username) && verifyPassword(password, storedPassword);
    }

    /**
     * 获取系统配置项（批量）
     * <p>
     * 返回所有系统运行相关的配置，如日志管理、通知、超时配置等。
     * </p>
     *
     * @return 系统配置项的 key-value 映射
     */
    public Map<String, String> getSystemConfig() {
        String retentionDays = getValueByKey(KEY_LOG_RETENTION_DAYS);
        String cleanupEnabled = getValueByKey(KEY_LOG_CLEANUP_ENABLED);
        String requestBodyTtlHours = getValueByKey(KEY_REQUEST_BODY_TTL_HOURS);
        String retryFailTtlHours = getValueByKey(KEY_RETRY_FAIL_TTL_HOURS);
        String timeoutMinSeconds = getValueByKey(KEY_TIMEOUT_MIN_SECONDS);
        String timeoutMaxSeconds = getValueByKey(KEY_TIMEOUT_MAX_SECONDS);
        String requestDataSaveLevel = getValueByKey(KEY_REQUEST_DATA_SAVE_LEVEL);
        String probeIntervalMinutes = getValueByKey(KEY_CIRCUIT_BREAKER_PROBE_INTERVAL_MINUTES);
        String probeThrottleSeconds = getValueByKey(KEY_CIRCUIT_BREAKER_PROBE_THROTTLE_SECONDS);
        String modelRefreshIntervalMinutes = getValueByKey(KEY_CHANNEL_MODEL_REFRESH_INTERVAL_MINUTES);
        if (retentionDays == null) retentionDays = "7";
        if (cleanupEnabled == null) cleanupEnabled = "1";
        if (requestBodyTtlHours == null) requestBodyTtlHours = "4";
        if (retryFailTtlHours == null) retryFailTtlHours = "48";
        if (timeoutMinSeconds == null) timeoutMinSeconds = "20";
        if (timeoutMaxSeconds == null) timeoutMaxSeconds = "60";
        if (requestDataSaveLevel == null) requestDataSaveLevel = "info";
        if (probeIntervalMinutes == null) probeIntervalMinutes = "30";
        if (probeThrottleSeconds == null) probeThrottleSeconds = "6";
        if (modelRefreshIntervalMinutes == null) modelRefreshIntervalMinutes = "30";

        Map<String, String> config = new LinkedHashMap<>();
        config.put(KEY_LOG_RETENTION_DAYS, retentionDays);
        config.put(KEY_LOG_CLEANUP_ENABLED, cleanupEnabled);
        config.put(KEY_REQUEST_BODY_TTL_HOURS, requestBodyTtlHours);
        config.put(KEY_RETRY_FAIL_TTL_HOURS, retryFailTtlHours);
        config.put(KEY_TIMEOUT_MIN_SECONDS, timeoutMinSeconds);
        config.put(KEY_TIMEOUT_MAX_SECONDS, timeoutMaxSeconds);
        config.put(KEY_REQUEST_DATA_SAVE_LEVEL, requestDataSaveLevel);
        config.put(KEY_CIRCUIT_BREAKER_PROBE_INTERVAL_MINUTES, probeIntervalMinutes);
        config.put(KEY_CIRCUIT_BREAKER_PROBE_THROTTLE_SECONDS, probeThrottleSeconds);
        config.put(KEY_CHANNEL_MODEL_REFRESH_INTERVAL_MINUTES, modelRefreshIntervalMinutes);
        return config;
    }

    /**
     * 获取渠道模型自动刷新间隔（分钟），解析失败或非法时返回默认值 30。
     */
    public int getChannelModelRefreshIntervalMinutes() {
        String value = getValueByKey(KEY_CHANNEL_MODEL_REFRESH_INTERVAL_MINUTES);
        if (value != null) {
            try {
                int minutes = Integer.parseInt(value.trim());
                if (minutes >= 1) {
                    return minutes;
                }
            } catch (NumberFormatException e) {
                log.warn("渠道模型刷新间隔配置非法，使用默认值: {}", value);
            }
        }
        return 30;
    }

    /**
     * 获取熔断触发式探测节流（秒），解析失败或非法时返回默认值 6；0 表示不节流。
     */
    public double getCircuitBreakerProbeThrottleSeconds() {
        String value = getValueByKey(KEY_CIRCUIT_BREAKER_PROBE_THROTTLE_SECONDS);
        if (value != null) {
            try {
                double seconds = Double.parseDouble(value.trim());
                if (seconds >= 0) {
                    return seconds;
                }
            } catch (NumberFormatException e) {
                log.warn("熔断探测节流配置非法，使用默认值: {}", value);
            }
        }
        return 6;
    }

    /**
     * 获取熔断到期记录全量探测间隔（分钟），解析失败或非法时返回默认值 30。
     */
    public int getCircuitBreakerProbeIntervalMinutes() {
        String value = getValueByKey(KEY_CIRCUIT_BREAKER_PROBE_INTERVAL_MINUTES);
        if (value != null) {
            try {
                int minutes = Integer.parseInt(value.trim());
                if (minutes >= 1) {
                    return minutes;
                }
            } catch (NumberFormatException e) {
                log.warn("熔断探测间隔配置非法，使用默认值: {}", value);
            }
        }
        return 30;
    }

    /**
     * 更新系统配置项
     * <p>
     * 支持批量更新多个配置项，仅更新传入的 key。
     * 配置行不存在时执行插入（新配置项首次保存），存在时执行更新。
     * </p>
     *
     * @param config 配置项的 key-value 映射
     */
    @Transactional
    public void updateSystemConfig(Map<String, String> config) {
        for (Map.Entry<String, String> entry : config.entrySet()) {
            LambdaUpdateWrapper<AdminConfig> wrapper = new LambdaUpdateWrapper<AdminConfig>()
                    .eq(AdminConfig::getConfigKey, entry.getKey())
                    .set(AdminConfig::getConfigValue, entry.getValue())
                    .set(AdminConfig::getUpdatedAt, LocalDateTime.now());
            int updated = adminConfigMapper.update(null, wrapper);
            if (updated == 0) {
                // 配置行不存在：插入新行（新配置项首次保存）
                AdminConfig newConfig = new AdminConfig();
                newConfig.setConfigKey(entry.getKey());
                newConfig.setConfigValue(entry.getValue());
                adminConfigMapper.insert(newConfig);
                log.info("新增系统配置项: {}={}", entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * 简单密码加密（实际生产环境应使用更强的加密方式）
     */
    private String encryptPassword(String password) {
        // 使用简单的 Base64 编码作为占位，实际生产应使用 BCrypt 等
        return java.util.Base64.getEncoder().encodeToString(password.getBytes());
    }

    /**
     * 验证密码
     */
    private boolean verifyPassword(String rawPassword, String encryptedPassword) {
        try {
            String decoded = new String(java.util.Base64.getDecoder().decode(encryptedPassword));
            return decoded.equals(rawPassword);
        } catch (Exception e) {
            return false;
        }
    }
}
