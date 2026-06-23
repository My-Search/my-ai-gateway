package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.myai.gateway.entity.AdminConfig;
import com.myai.gateway.mapper.AdminConfigMapper;
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

    public static final String KEY_USERNAME = "username";
    public static final String KEY_PASSWORD = "password";

    /** 日志保留天数 */
    public static final String KEY_LOG_RETENTION_DAYS = "log_retention_days";
    /** 日志定时清理开关 */
    public static final String KEY_LOG_CLEANUP_ENABLED = "log_cleanup_enabled";

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
     * 返回所有系统运行相关的配置，如日志管理、通知等。
     * 当前包含：日志保留天数、定时清理开关。
     * </p>
     *
     * @return 系统配置项的 key-value 映射
     */
    public Map<String, String> getSystemConfig() {
        String retentionDays = getValueByKey(KEY_LOG_RETENTION_DAYS);
        String cleanupEnabled = getValueByKey(KEY_LOG_CLEANUP_ENABLED);
        if (retentionDays == null) retentionDays = "7";
        if (cleanupEnabled == null) cleanupEnabled = "1";

        Map<String, String> config = new LinkedHashMap<>();
        config.put(KEY_LOG_RETENTION_DAYS, retentionDays);
        config.put(KEY_LOG_CLEANUP_ENABLED, cleanupEnabled);
        return config;
    }

    /**
     * 更新系统配置项
     * <p>
     * 支持批量更新多个配置项，仅更新传入的 key。
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
            adminConfigMapper.update(null, wrapper);
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