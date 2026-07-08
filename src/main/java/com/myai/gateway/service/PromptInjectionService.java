package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.PromptInjection;
import com.myai.gateway.mapper.PromptInjectionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Prompt 注入规则服务
 * 管理按入口模型配置的自动消息注入规则
 */
@Service
public class PromptInjectionService {

    private static final Logger log = LoggerFactory.getLogger(PromptInjectionService.class);

    private final PromptInjectionMapper promptInjectionMapper;

    public PromptInjectionService(PromptInjectionMapper promptInjectionMapper) {
        this.promptInjectionMapper = promptInjectionMapper;
    }

    /**
     * 获取指定模型的所有 Prompt 注入规则（按优先级排序）
     */
    public List<PromptInjection> listByModelId(Long modelId) {
        return promptInjectionMapper.selectList(
                new LambdaQueryWrapper<PromptInjection>()
                        .eq(PromptInjection::getModelId, modelId)
                        .orderByAsc(PromptInjection::getPriority)
                        .orderByAsc(PromptInjection::getId));
    }

    /**
     * 获取指定模型的已启用注入规则（按优先级排序）
     */
    public List<PromptInjection> listEnabledByModelId(Long modelId) {
        return promptInjectionMapper.selectList(
                new LambdaQueryWrapper<PromptInjection>()
                        .eq(PromptInjection::getModelId, modelId)
                        .eq(PromptInjection::getEnabled, 1)
                        .orderByAsc(PromptInjection::getPriority)
                        .orderByAsc(PromptInjection::getId));
    }

    /**
     * 根据 ID 获取规则
     */
    public PromptInjection getById(Long id) {
        return promptInjectionMapper.selectById(id);
    }

    /**
     * 创建规则
     */
    @Transactional
    public PromptInjection create(PromptInjection rule) {
        validate(rule);
        promptInjectionMapper.insert(rule);
        log.info("Prompt 注入规则已创建: id={}, modelId={}, name={}, role={}, position={}",
                rule.getId(), rule.getModelId(), rule.getName(), rule.getInjectRole(), rule.getInjectPosition());
        return rule;
    }

    /**
     * 更新规则
     */
    @Transactional
    public PromptInjection update(PromptInjection rule) {
        validate(rule);
        PromptInjection existing = promptInjectionMapper.selectById(rule.getId());
        if (existing == null) {
            throw new RuntimeException("Prompt 注入规则不存在");
        }
        existing.setName(rule.getName());
        existing.setInjectRole(rule.getInjectRole());
        existing.setInjectPosition(rule.getInjectPosition());
        existing.setContent(rule.getContent());
        existing.setEnabled(rule.getEnabled());
        existing.setPriority(rule.getPriority());
        existing.setUpdatedAt(LocalDateTime.now());
        promptInjectionMapper.updateById(existing);
        log.info("Prompt 注入规则已更新: id={}, modelId={}, name={}", existing.getId(), existing.getModelId(), existing.getName());
        return existing;
    }

    /**
     * 删除规则
     */
    @Transactional
    public void delete(Long id) {
        promptInjectionMapper.deleteById(id);
        log.info("Prompt 注入规则已删除: id={}", id);
    }

    /** 注入内容最大长度 */
    private static final int MAX_CONTENT_LENGTH = 10000;

    /**
     * 校验规则字段
     */
    private void validate(PromptInjection rule) {
        if (rule.getModelId() == null) {
            throw new RuntimeException("modelId 不能为空");
        }
        if (rule.getContent() == null || rule.getContent().isBlank()) {
            throw new RuntimeException("content 不能为空");
        }
        if (rule.getContent().length() > MAX_CONTENT_LENGTH) {
            throw new RuntimeException("content 超出最大长度���制（" + MAX_CONTENT_LENGTH + " 字符）");
        }
        String role = rule.getInjectRole();
        if (role == null || (!"system".equals(role) && !"user".equals(role) && !"assistant".equals(role))) {
            throw new RuntimeException("injectRole 必须为 system / user / assistant");
        }
        String pos = rule.getInjectPosition();
        if (pos == null || (!"prepend".equals(pos) && !"append".equals(pos) && !"replace_system".equals(pos))) {
            throw new RuntimeException("injectPosition 必须为 prepend / append / replace_system");
        }
        // replace_system 规则每个模型只允许一条（多条在语义上会相互覆盖）
        if ("replace_system".equals(pos)) {
            Long existingId = rule.getId(); // 更新时排除自身
            Long count = promptInjectionMapper.selectCount(
                    new LambdaQueryWrapper<PromptInjection>()
                            .eq(PromptInjection::getModelId, rule.getModelId())
                            .eq(PromptInjection::getInjectPosition, "replace_system")
                            .ne(existingId != null, PromptInjection::getId, existingId));
            if (count != null && count > 0) {
                throw new RuntimeException("每个模型只能有一条 replace_system 规则");
            }
        }
    }
}
