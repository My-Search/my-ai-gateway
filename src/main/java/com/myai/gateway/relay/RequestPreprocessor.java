package com.myai.gateway.relay;

import com.myai.gateway.entity.ChannelModel;
import com.myai.gateway.entity.Model;
import com.myai.gateway.entity.PromptInjection;
import com.myai.gateway.relay.balancer.RoutingCandidate;
import com.myai.gateway.relay.transformer.InternalMessage;
import com.myai.gateway.relay.transformer.InternalRequest;
import com.myai.gateway.relay.transformer.MessageTransformer;
import com.myai.gateway.service.ModelService;
import com.myai.gateway.service.PromptInjectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.IOException;
import java.util.*;

/**
 * 请求预处理器
 * <p>负责请求解析、Prompt 注入、多模态失效处理等请求前置处理逻辑。</p>
 */
public class RequestPreprocessor {

    private static final Logger log = LoggerFactory.getLogger(RequestPreprocessor.class);

    private final MessageTransformer messageTransformer;
    private final RouteResolver routeResolver;
    private final PromptInjectionService promptInjectionService;
    private final ModelService modelService;

    /** 多模态失效替换文本 */
    private static final String MEDIA_REPLACE_TEXT_IMAGE = "图片输入已失效已被系统移除";
    private static final String MEDIA_REPLACE_TEXT_VIDEO = "视频输入已失效已被系统移除";
    private static final String MEDIA_REPLACE_TEXT_AUDIO = "音频输入已失效已被系统移除";

    public RequestPreprocessor(MessageTransformer messageTransformer,
                               RouteResolver routeResolver,
                               PromptInjectionService promptInjectionService,
                               ModelService modelService) {
        this.messageTransformer = messageTransformer;
        this.routeResolver = routeResolver;
        this.promptInjectionService = promptInjectionService;
        this.modelService = modelService;
    }

    /**
     * 解析请求体为内部统一请求
     */
    public InternalRequest parseRequest(String requestBody, String clientFormat) throws IOException {
        com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(requestBody);
        InternalRequest req;
        if ("anthropic".equals(clientFormat)) {
            req = messageTransformer.parseAnthropicRequest(json);
        } else {
            req = messageTransformer.parseOpenAiRequest(json);
        }
        req.setClientApiFormat(clientFormat);
        return req;
    }

    /**
     * 应用 Prompt 注入规则
     */
    public void applyPromptInjections(InternalRequest req) {
        Long customModelId = routeResolver.resolveModelId(req.getModel());
        if (customModelId == null) return;

        List<PromptInjection> rules = promptInjectionService.listEnabledByModelId(customModelId);
        if (rules == null || rules.isEmpty()) return;

        if (req.getMessages() == null) {
            req.setMessages(new ArrayList<>());
        }
        List<InternalMessage> messages = req.getMessages();

        List<InternalMessage> prependMessages = new ArrayList<>();
        List<InternalMessage> appendMessages = new ArrayList<>();
        InternalMessage replaceSystemMsg = null;

        for (PromptInjection rule : rules) {
            InternalMessage injectMsg = new InternalMessage(rule.getInjectRole(), rule.getContent());
            log.info("应用 Prompt 注入规则: modelId={}, ruleName={}, role={}, position={}",
                    customModelId, rule.getName(), rule.getInjectRole(), rule.getInjectPosition());

            switch (rule.getInjectPosition()) {
                case "prepend" -> prependMessages.add(injectMsg);
                case "append" -> appendMessages.add(injectMsg);
                case "replace_system" -> replaceSystemMsg = injectMsg;
                default -> log.warn("未知的注入位置: {}", rule.getInjectPosition());
            }
        }

        if (replaceSystemMsg != null) {
            boolean foundSystem = false;
            for (int i = 0; i < messages.size(); i++) {
                if ("system".equals(messages.get(i).getRole())) {
                    messages.set(i, replaceSystemMsg);
                    foundSystem = true;
                    log.info("已替换系统消息 - 新角色={}, 内容长度={}",
                            replaceSystemMsg.getRole(), replaceSystemMsg.getContent().length());
                    break;
                }
            }
            if (!foundSystem) {
                prependMessages.add(replaceSystemMsg);
                log.info("未找到系统消息，将 replace_system 规则作为 prepend 注入");
            }
        }

        List<InternalMessage> newMessages = new ArrayList<>();
        newMessages.addAll(prependMessages);
        newMessages.addAll(messages);
        newMessages.addAll(appendMessages);

        req.setMessages(newMessages);

        if (!prependMessages.isEmpty() || !appendMessages.isEmpty()) {
            log.info("Prompt 注入完成 - prependCount={}, appendCount={}, 原始消息数={}, 最终消息数={}",
                    prependMessages.size(), appendMessages.size(), messages.size(), newMessages.size());
        }
    }

    /**
     * 多模态失效处理
     */
    public void preprocessMediaInvalidation(InternalRequest req) {
        if (req.getMessages() == null || req.getMessages().isEmpty()) return;

        Long customModelId = routeResolver.resolveModelId(req.getModel());
        if (customModelId == null) return;

        Model model = modelService.getById(customModelId);
        if (model == null) return;

        int imageN = model.getImageInvalidateCount() != null ? model.getImageInvalidateCount() : 0;
        int videoN = model.getVideoInvalidateCount() != null ? model.getVideoInvalidateCount() : 0;
        int audioN = model.getAudioInvalidateCount() != null ? model.getAudioInvalidateCount() : 0;

        if (imageN > 0) preprocessMediaInvalidationForType(req, "image", imageN, MEDIA_REPLACE_TEXT_IMAGE);
        if (videoN > 0) preprocessMediaInvalidationForType(req, "video", videoN, MEDIA_REPLACE_TEXT_VIDEO);
        if (audioN > 0) preprocessMediaInvalidationForType(req, "audio", audioN, MEDIA_REPLACE_TEXT_AUDIO);
    }

    /**
     * 强制覆盖思考强度
     * <p>当入口模型启用了 force_override_reasoning_effort 时，清除客户端请求中携带的
     * reasoning_effort，使其在后续 buildProviderRequestBody 中回退到关联关系配置的默认值。</p>
     * <p>处理流程：解析模型ID → 查询模型配置 → 判断是否启用强制覆盖 → 清除 reasoningEffort</p>
     *
     * @param req 内部统一请求
     */
    public void applyReasoningEffortOverride(InternalRequest req) {
        if (req.getReasoningEffort() == null || req.getReasoningEffort().isEmpty()) return;

        Long customModelId = routeResolver.resolveModelId(req.getModel());
        if (customModelId == null) return;

        Model model = modelService.getById(customModelId);
        if (model == null) return;

        if (model.getForceOverrideReasoningEffort() != null && model.getForceOverrideReasoningEffort() == 1) {
            log.info("强制覆盖思考强度: 清除客户端请求的 reasoning_effort={}, 将使用关联配置的默认值 - model={}",
                    req.getReasoningEffort(), req.getModel());
            req.setReasoningEffort(null);
        }
    }

    private void preprocessMediaInvalidationForType(InternalRequest req, String mediaTypePrefix,
                                                     int invalidateCount, String replaceText) {
        List<InternalMessage> messages = req.getMessages();
        List<InternalMessage> userMessages = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if ("user".equals(messages.get(i).getRole())) {
                userMessages.add(messages.get(i));
            }
        }

        int lastMediaUserIdx = -1;
        for (int i = userMessages.size() - 1; i >= 0; i--) {
            InternalMessage msg = userMessages.get(i);
            if (hasMediaType(msg, mediaTypePrefix)) {
                lastMediaUserIdx = i;
                break;
            }
        }

        if (lastMediaUserIdx < 0) return;

        int userMsgsAfter = userMessages.size() - 1 - lastMediaUserIdx;

        if (userMsgsAfter >= invalidateCount) {
            log.info("多模态失效触发: mediaType={}, invalidateCount={}, userMsgsAfter={}, model={}",
                    mediaTypePrefix, invalidateCount, userMsgsAfter, req.getModel());
            for (InternalMessage msg : messages) {
                replaceMediaParts(msg, mediaTypePrefix, replaceText);
            }
            req.setDetectedMediaTypes(null);
        }
    }

    private boolean hasMediaType(InternalMessage msg, String typePrefix) {
        if (msg.getContentParts() == null) return false;
        for (Map<String, Object> part : msg.getContentParts()) {
            Object type = part.get("type");
            if (type != null && type.toString().startsWith(typePrefix)) {
                return true;
            }
        }
        return false;
    }

    private void replaceMediaParts(InternalMessage msg, String typePrefix, String replaceText) {
        if (msg.getContentParts() == null) return;
        List<Map<String, Object>> newParts = new ArrayList<>();
        boolean replaced = false;
        for (Map<String, Object> part : msg.getContentParts()) {
            Object type = part.get("type");
            if (type != null && type.toString().startsWith(typePrefix)) {
                if (!replaced) {
                    Map<String, Object> textPart = new LinkedHashMap<>();
                    textPart.put("type", "text");
                    textPart.put("text", replaceText);
                    newParts.add(textPart);
                    replaced = true;
                }
            } else {
                newParts.add(part);
            }
        }
        msg.setContentParts(newParts);
    }

    /**
     * 检测请求中包含的所有媒体类型
     */
    public Set<String> detectRequestMediaTypes(InternalRequest req) {
        if (req.getDetectedMediaTypes() != null) {
            return req.getDetectedMediaTypes();
        }
        if (req.getMessages() == null) {
            req.setDetectedMediaTypes(Collections.emptySet());
            return Collections.emptySet();
        }
        Set<String> types = new HashSet<>();
        for (InternalMessage msg : req.getMessages()) {
            if (msg.getContentParts() == null) continue;
            for (Map<String, Object> part : msg.getContentParts()) {
                Object type = part.get("type");
                if (type != null) {
                    String t = type.toString();
                    if (t.startsWith("image")) types.add("image");
                    else if (t.startsWith("video")) types.add("video");
                    else if (t.startsWith("audio")) types.add("audio");
                }
            }
        }
        req.setDetectedMediaTypes(types);
        return types;
    }

    /**
     * 检查渠道模型是否支持指定的媒体类型
     */
    public boolean supportsMediaType(ChannelModel channelModel, String mediaType) {
        String input = channelModel.getInput();
        return input != null && input.contains(mediaType);
    }

    /**
     * 候选不支持请求中的媒体类型时返回 true
     */
    public boolean skipIfMediaTypeUnsupported(String traceId, Long gatewayApiKeyId,
                                               RoutingCandidate candidate,
                                               InternalRequest req, int retryIndex,
                                               RelayLogger relayLogger) {
        Set<String> types = detectRequestMediaTypes(req);
        if (types.isEmpty()) return false;

        List<String> unsupportedTypes = new ArrayList<>();
        for (String type : types) {
            if (!supportsMediaType(candidate.getChannelModel(), type)) {
                unsupportedTypes.add(type);
            }
        }

        if (!unsupportedTypes.isEmpty()) {
            relayLogger.logPhase(traceId, gatewayApiKeyId, candidate, req, "skip",
                    "当前模型不支持请求中含有的这些类型：" + String.join("、", unsupportedTypes)
                            + " 因此跳过 " + candidate.getChannel().getName()
                            + "/" + candidate.getChannelApiKey().getKeyName()
                            + "/" + candidate.getChannelModel().getModelName(), retryIndex);
            return true;
        }
        return false;
    }

    /**
     * 构建带有历史上下文的新内部请求
     */
    public InternalRequest buildRequestWithContext(InternalRequest originalReq, String accumulatedContent) {
        InternalRequest contextReq = new InternalRequest();
        contextReq.setModel(originalReq.getModel());
        contextReq.setStream(originalReq.isStream());
        contextReq.setMaxTokens(originalReq.getMaxTokens());
        contextReq.setTemperature(originalReq.getTemperature());
        contextReq.setTopP(originalReq.getTopP());
        contextReq.setStop(originalReq.getStop());
        contextReq.setTools(originalReq.getTools());
        contextReq.setToolChoice(originalReq.getToolChoice());
        contextReq.setSystemPrompt(originalReq.getSystemPrompt());
        contextReq.setOriginalRequestJson(originalReq.getOriginalRequestJson());
        contextReq.setClientApiFormat(originalReq.getClientApiFormat());
        contextReq.setExtraParams(originalReq.getExtraParams());
        contextReq.setReasoningEffort(originalReq.getReasoningEffort());
        contextReq.setContextRetry(true);

        List<InternalMessage> newMessages = new ArrayList<>();
        if (originalReq.getMessages() != null) {
            for (InternalMessage msg : originalReq.getMessages()) {
                InternalMessage copy = new InternalMessage(msg.getRole(), msg.getContent());
                copy.setContentParts(msg.getContentParts());
                copy.setToolCalls(msg.getToolCalls());
                copy.setToolCallId(msg.getToolCallId());
                copy.setName(msg.getName());
                copy.setReasoningContent(msg.getReasoningContent());
                newMessages.add(copy);
            }
        }

        newMessages.add(new InternalMessage("assistant", accumulatedContent));
        contextReq.setMessages(newMessages);
        log.debug("构建带上下文请求完成 - 原始消息数={}, 添加assistant消息长度={}",
                originalReq.getMessages() != null ? originalReq.getMessages().size() : 0,
                accumulatedContent.length());
        return contextReq;
    }
}
