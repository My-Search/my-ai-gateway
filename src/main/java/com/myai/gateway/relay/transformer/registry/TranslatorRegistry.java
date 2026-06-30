package com.myai.gateway.relay.transformer.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 协议翻译器注册表。
 *
 * <p>管理所有 {@link ProtocolTranslator} 实例，支持按 (sourceFormat, targetFormat) 查找。
 * 翻译器通过 {@link #register(ProtocolTranslator)} 注册。
 *
 * <p>CPA 参考：对应 Go 中 {@code sdk/translator/registry.go} 的注册表模式。
 *
 * <p>设计原则：
 * <ul>
 *   <li>key = {@code sourceFormat + "→" + targetFormat}，如 {@code "anthropic→openai"}</li>
 *   <li>同格式（如 openai→openai）的翻译器返回 null，由调用方做透传优化</li>
 *   <li>查找不到时返回 null，由调用方决定是否降级</li>
 * </ul>
 */
@Component
public class TranslatorRegistry {

    private static final Logger log = LoggerFactory.getLogger(TranslatorRegistry.class);

    private final Map<String, ProtocolTranslator> translatorMap = new ConcurrentHashMap<>();

    /**
     * 通过构造器自动注入所有 {@link ProtocolTranslator} 实现，完成注册。
     */
    @Autowired
    public TranslatorRegistry(List<ProtocolTranslator> translators) {
        for (ProtocolTranslator t : translators) {
            register(t);
        }
    }

    /**
     * 注册一个协议翻译器。
     *
     * @param translator 翻译器实例
     */
    public void register(ProtocolTranslator translator) {
        String key = buildKey(translator.sourceFormat(), translator.targetFormat());
        ProtocolTranslator old = translatorMap.put(key, translator);
        if (old != null) {
            log.warn("翻译器被覆盖注册: key={}, old={}, new={}", key,
                    old.getClass().getSimpleName(), translator.getClass().getSimpleName());
        } else {
            log.info("注册翻译器: {} → {} ({} )",
                    translator.sourceFormat(), translator.targetFormat(),
                    translator.getClass().getSimpleName());
        }
    }

    /**
     * 查找 (source → target) 方向的翻译器。
     *
     * @param sourceFormat 源协议格式
     * @param targetFormat 目标协议格式
     * @return 翻译器实例，或 null（不存在或 source=target 时）
     */
    public ProtocolTranslator find(String sourceFormat, String targetFormat) {
        if (sourceFormat.equals(targetFormat)) {
            return null; // 同格式透传
        }
        return translatorMap.get(buildKey(sourceFormat, targetFormat));
    }

    private static String buildKey(String source, String target) {
        return source + "→" + target;
    }
}
