package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.ChannelModel;
import com.myai.gateway.entity.MultiModalRule;
import com.myai.gateway.mapper.ChannelModelMapper;
import com.myai.gateway.mapper.MultiModalRuleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * 多模态规则服务
 * 管理模型名称匹配规则，用于自动标记渠道模型支持的输入模态类型
 */
@Service
public class MultiModalRuleService {

    private static final Logger log = LoggerFactory.getLogger(MultiModalRuleService.class);

    private final MultiModalRuleMapper ruleMapper;
    private final ChannelModelMapper channelModelMapper;

    public MultiModalRuleService(MultiModalRuleMapper ruleMapper, ChannelModelMapper channelModelMapper) {
        this.ruleMapper = ruleMapper;
        this.channelModelMapper = channelModelMapper;
    }

    /**
     * 获取所有多模态规则
     */
    public List<MultiModalRule> listAll() {
        return ruleMapper.selectList(
                new LambdaQueryWrapper<MultiModalRule>().orderByAsc(MultiModalRule::getCreatedAt));
    }

    /**
     * 根据 ID 获取规则
     */
    public MultiModalRule getById(Long id) {
        return ruleMapper.selectById(id);
    }

    /**
     * 创建规则
     * 创建后自动重新对所有渠道模型应用规则
     */
    @Transactional
    public MultiModalRule create(MultiModalRule rule) {
        validatePattern(rule.getPattern());
        ruleMapper.insert(rule);
        log.info("多模态规则已创建: id={}, pattern={}, appendType={}", rule.getId(), rule.getPattern(), rule.getAppendType());
        reapplyAllRules();
        return rule;
    }

    /**
     * 更新规则
     * 更新后自动重新对所有渠道模型应用规则
     */
    @Transactional
    public MultiModalRule update(MultiModalRule rule) {
        validatePattern(rule.getPattern());
        MultiModalRule existing = ruleMapper.selectById(rule.getId());
        if (existing == null) {
            throw new RuntimeException("规则不存在");
        }
        existing.setPattern(rule.getPattern());
        existing.setAppendType(rule.getAppendType());
        existing.setUpdatedAt(LocalDateTime.now());
        ruleMapper.updateById(existing);
        log.info("多模态规则已更新: id={}, pattern={}, appendType={}", existing.getId(), existing.getPattern(), existing.getAppendType());
        reapplyAllRules();
        return existing;
    }

    /**
     * 删除规则
     * 删除后自动重新对所有渠道模型应用规则
     */
    @Transactional
    public void delete(Long id) {
        ruleMapper.deleteById(id);
        log.info("多模态规则已删除: id={}", id);
        reapplyAllRules();
    }

    /**
     * 测试正则表达式
     *
     * @param pattern  正则表达式
     * @param testData 测试数据列表
     * @return 每条测试数据的匹配结果（true=匹配通过）
     */
    public List<Map<String, Object>> testPattern(String pattern, List<String> testData) {
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            Pattern compiled = Pattern.compile(pattern);
            for (String data : testData) {
                boolean matched = compiled.matcher(data).find();
                results.add(Map.of(
                        "data", data,
                        "matched", matched
                ));
            }
        } catch (PatternSyntaxException e) {
            throw new RuntimeException("正则表达式语法错误: " + e.getMessage());
        }
        return results;
    }

    /**
     * 验证正则表达式是否有效
     */
    private void validatePattern(String pattern) {
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new RuntimeException("正则表达式语法错误: " + e.getMessage());
        }
    }

    /**
     * 根据模型名称匹配规则，返回匹配到的附加模态类型列表
     *
     * @param modelName 模型名称
     * @return 匹配到的模态类型列表（如 ["image"]）
     */
    public List<String> matchTypes(String modelName) {
        List<MultiModalRule> rules = listAll();
        return rules.stream()
                .filter(rule -> {
                    try {
                        return Pattern.compile(rule.getPattern()).matcher(modelName).find();
                    } catch (PatternSyntaxException e) {
                        log.warn("多模态规则正则无效: id={}, pattern={}", rule.getId(), rule.getPattern());
                        return false;
                    }
                })
                .map(MultiModalRule::getAppendType)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 根据模型名称计算 input 字段值
     * 基础值为 "text"，如果匹配到规则则追加匹配的模态类型
     *
     * @param modelName 模型名称
     * @return 如 "text" 或 "text,image"
     */
    public String computeInput(String modelName) {
        List<String> types = matchTypes(modelName);
        if (types.isEmpty()) {
            return "text";
        }
        return "text," + String.join(",", types);
    }

    /**
     * 重新对所有渠道模型应用多模态规则。
     * 遍历所有 channel_models，根据当前规则重新计算 input 字段值，
     * 仅当值有变化时才更新数据库。
     * <p>
     * 在规则创建/更新/删除后自动调用，确保已有渠道模型同步更新。
     * </p>
     */
    public void reapplyAllRules() {
        List<ChannelModel> allModels = channelModelMapper.selectList(
                new LambdaQueryWrapper<ChannelModel>());
        int updatedCount = 0;
        for (ChannelModel model : allModels) {
            if (model.getModelName() == null) continue;
            String newInput = computeInput(model.getModelName());
            String oldInput = model.getInput();
            if (oldInput == null || !oldInput.equals(newInput)) {
                model.setInput(newInput);
                channelModelMapper.updateById(model);
                updatedCount++;
                log.debug("多模态规则重新应用: modelId={}, modelName={}, input: {} -> {}",
                        model.getId(), model.getModelName(), oldInput, newInput);
            }
        }
        log.info("多模态规则重新应用完成: 共 {} 个渠道模型，更新 {} 个", allModels.size(), updatedCount);
    }
}
