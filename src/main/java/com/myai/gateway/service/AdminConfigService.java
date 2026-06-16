package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.myai.gateway.entity.AdminConfig;
import com.myai.gateway.mapper.AdminConfigMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理员配置服务
 * 管理管理员账号等配置
 */
@Service
public class AdminConfigService {

    public static final String KEY_USERNAME = "username";
    public static final String KEY_PASSWORD = "password";

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