package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.ApiKey;
import com.myai.gateway.mapper.ApiKeyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * API密钥服务
 * 管理网关的 API 密钥
 */
@Service
public class ApiKeyService {

    private static final String KEY_PREFIX = "sk-myai-";
    private static final int KEY_BYTE_LENGTH = 32;
    private static final SecureRandom secureRandom = new SecureRandom();

    private final ApiKeyMapper apiKeyMapper;

    public ApiKeyService(ApiKeyMapper apiKeyMapper) {
        this.apiKeyMapper = apiKeyMapper;
    }

    public List<ApiKey> listAll() {
        return apiKeyMapper.selectList(
                new LambdaQueryWrapper<ApiKey>().orderByDesc(ApiKey::getCreatedAt));
    }

    public ApiKey getById(Long id) {
        return apiKeyMapper.selectById(id);
    }

    /**
     * 根据密钥值查找（用于请求验证）
     */
    public ApiKey findByKeyValue(String keyValue) {
        return apiKeyMapper.selectOne(
                new LambdaQueryWrapper<ApiKey>()
                        .eq(ApiKey::getKeyValue, keyValue)
                        .eq(ApiKey::getEnabled, 1));
    }

    @Transactional
    public ApiKey create(ApiKey apiKey) {
        // 如果没有填写密钥值，自动生成
        if (apiKey.getKeyValue() == null || apiKey.getKeyValue().isBlank()) {
            apiKey.setKeyValue(generateApiKey());
        }
        // 检查密钥值是否已存在
        ApiKey existing = apiKeyMapper.selectOne(
                new LambdaQueryWrapper<ApiKey>().eq(ApiKey::getKeyValue, apiKey.getKeyValue()));
        if (existing != null) {
            // 如果冲突则重新生成
            apiKey.setKeyValue(generateApiKey());
        }
        apiKeyMapper.insert(apiKey);
        return apiKey;
    }

    @Transactional
    public ApiKey update(ApiKey apiKey) {
        apiKeyMapper.updateById(apiKey);
        return apiKey;
    }

    @Transactional
    public void delete(Long id) {
        apiKeyMapper.deleteById(id);
    }

    /**
     * 更新密钥最后使用时间
     */
    @Transactional
    public void updateLastUsed(Long id) {
        ApiKey key = apiKeyMapper.selectById(id);
        if (key != null) {
            key.setLastUsedAt(LocalDateTime.now());
            apiKeyMapper.updateById(key);
        }
    }

    /**
     * 验证 API Key 是否有效
     */
    public ApiKey validateKey(String keyValue) {
        if (keyValue == null || keyValue.isBlank()) {
            return null;
        }
        // 去除 Bearer 前缀
        if (keyValue.startsWith("Bearer ")) {
            keyValue = keyValue.substring(7).trim();
        }
        return findByKeyValue(keyValue);
    }

    /**
     * 自动生成 API Key
     * 生成格式: sk-myai-{32位随机字符}
     */
    private String generateApiKey() {
        byte[] randomBytes = new byte[KEY_BYTE_LENGTH];
        secureRandom.nextBytes(randomBytes);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        return KEY_PREFIX + encoded;
    }
}
