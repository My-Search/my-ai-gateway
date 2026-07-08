# MAG 协议转换层（Protocol Translation Layer）分析报告

> 分析日期：2026-07-07
> 分析人：架构师-NDMB
> 版本：v1.0

---

## 1. 概述

MAG 网关的协议转换层承担着 **OpenAI Chat Completion API** 与 **Anthropic Messages API** 之间的双向协议桥接职责。它使得用户可以使用任一种 API 格式发起请求，网关自动转换后转发到另一协议的 AI 提供商，并将响应按原始请求格式返回。

核心能力：

- **请求转换**：客户端 → 内部统一模型 → 目标渠道格式
- **非流式响应转换**：上游响应 → 内部统一模型 → 客户端格式
- **流式 SSE 响应转换**：逐 chunk 转换，带跨 chunk 状态机的双向事件映射

---

## 2. 架构设计与分层

### 2.1 模块文件结构

```text
transformer/
├── InternalMessage.java              # 内部统一消息模型
├── InternalRequest.java              # 内部统一请求模型
├── InternalResponse.java             # 内部统一响应模型
├── MessageTransformer.java           # 【外观／Facade】统一入口，兼容旧调用方式
│
├── registry/                         # 翻译器注册与管理
│   ├── ProtocolTranslator.java       # 协议翻译器接口（SPI / 策略契约）
│   ├── TranslatorRegistry.java       # 注册表，按 source→target 查找
│   └── StreamTranslateState.java     # 流式跨 chunk 状态标记接口
│
└── protocol/                         # 方向性翻译器实现
    ├── anthropic_to_openai/
    │   ├── AnthropicToOpenAiTranslator.java   # 实现：Anthropic → OpenAI
    │   └── AnthropicToOpenAiState.java        # 流式跨 chunk 状态
    └── openai_to_anthropic/
        ├── OpenAiToAnthropicTranslator.java   # 实现：OpenAI → Anthropic
        └── OpenAiToAnthropicState.java        # 流式跨 chunk 状态
```

### 2.2 三层转换模型（客户端 → 内部 → 渠道）

整个转换流程遵循**三层架构**：

```
┌─────────────┐     parseOpenAiRequest()      ┌──────────────────┐
│  客户端请求  │  ──────────────────────────→  │                  │
│  (OpenAI)   │                               │   InternalRequest │
└─────────────┘                               │   （内部统一模型） │
                                              │                  │
┌─────────────┐     parseAnthropicRequest()    │   model          │
│  客户端请求  │  ──────────────────────────→  │   messages[]     │
│  (Anthropic)│                               │   systemPrompt   │
└─────────────┘                               │   maxTokens      │
                                              │   temperature    │
                                              │   topP           │
                                              │   stream         │
                                              │   stop           │
                                              │   tools          │
                                              │   reasoningEffort│
                                              │   ...            │
                                              └───────┬──────────┘
                                                       │
                          ┌─────────────────────────────┤
                          │ buildOpenAiRequest()        │ buildAnthropicRequest()
                          ▼                             ▼
              ┌──────────────────────┐      ┌──────────────────────┐
              │  上游渠道请求        │      │  上游渠道请求        │
              │  (OpenAI 格式)       │      │  (Anthropic 格式)    │
              └──────────────────────┘      └──────────────────────┘
```

**响应方向的流程**类似，但经过 `transformOpenAiResponseToClient()` / `transformAnthropicResponseToClient()`，通过 `TranslatorRegistry` 查找方向性翻译器做格式转换。

---

## 3. `ProtocolTranslator` 接口设计

### 3.1 接口定义

```java
public interface ProtocolTranslator {

    // 协议标识
    String sourceFormat();                     // 如 "openai"
    String targetFormat();                     // 如 "anthropic"

    // 请求翻译
    String translateRequest(InternalRequest request, String targetModel);

    // 非流式响应翻译
    String translateResponse(JsonNode providerResponse, String originalModel);

    // 流式响应翻译（逐事件）
    String translateStreamEvent(String eventType, String eventData,
                                String originalModel, StreamTranslateState state);

    // 流式结束（刷终结事件）
    String translateStreamEnd(String originalModel, StreamTranslateState state);

    // 创建流式状态实例
    StreamTranslateState createStreamState();
}
```

### 3.2 设计考量

| 维度 | 设计选择 | 理由 |
|------|---------|------|
| **方向性** | 每个实现固定一个方向（如 Anthropic→OpenAI） | 逻辑内聚，避免双向耦合在一个类中 |
| **协议标识** | String 标识（"openai"/"anthropic"） | 简单可扩展，新增协议只加新标识 |
| **请求翻译** | 接受 `InternalRequest` 返回 String | 请求已在 MessageTransformer 中转为内部模型，翻译器只管序列化 |
| **流式状态** | 通过 `StreamTranslateState` 接口 + 具体状态类 | 类型安全，每个方向有独立的状态字段 |
| **跳过机制** | 返回 null 表示跳过该事件 | 允许翻译器在中间事件（如 content_block_stop）上透明处理 |
| **终结回调** | `translateStreamEnd` 单独方法 | 应对异常断开、超时等情况刷出未完成事件 |

---

## 4. `TranslatorRegistry` 注册表

### 4.1 注册机制

```java
@Component
public class TranslatorRegistry {
    private final Map<String, ProtocolTranslator> translatorMap = new ConcurrentHashMap<>();

    @Autowired  // Spring 自动注入所有 ProtocolTranslator 实现
    public TranslatorRegistry(List<ProtocolTranslator> translators) {
        for (ProtocolTranslator t : translators) {
            register(t);
        }
    }
}
```

- **Key 规则**: `sourceFormat + "→" + targetFormat`，如 `"anthropic→openai"`
- **自动注册**: Spring Boot 扫描 `@Component` 实现类，通过构造器注入集合
- **查找逻辑**: `find(source, target)` 当 source==target 时返回 null（透传优化）
- **覆盖保护**: `register()` 在覆盖已有翻译器时打印 warn 日志

### 4.2 设计亮点

- **开放闭合原则**: 新增协议方向只需添加一个 `@Component` 翻译器实现即可，无需修改注册逻辑
- **错误隔离**: 查找不到时由调用方降级（model 替换兜底），注册表不做决策
- **线程安全**: 使用 `ConcurrentHashMap`，注册和查找无锁竞争

---

## 5. 双向协议转换的核心逻辑

### 5.1 请求转换映射

| OpenAI Chat Completion | 内部模型 | Anthropic Messages |
|------------------------|---------|-------------------|
| `model` | model | `model` |
| `messages[].role` | role | `messages[].role`（system→顶层） |
| `messages[].content` (string) | content | `messages[].content`（string） |
| `messages[].content` (array) | contentParts → 遍历 | `messages[].content` 多类型 block |
| `messages[].tool_calls` | toolCalls | `messages[].content` 中 `tool_use` block |
| `messages[].tool_call_id` | toolCallId | `messages[].content` 中 `tool_result.tool_use_id` |
| — | systemPrompt | `system` 顶层字段 |
| `max_tokens` / `max_completion_tokens` | maxTokens | `max_tokens` |
| `temperature` | temperature | `temperature` |
| `top_p` | topP | `top_p` |
| `stop` (string/array) | stop | `stop_sequences` |
| `tools[].function` | tools | `tools[].input_schema` |
| `tool_choice` | toolChoice | `tool_choice` |
| `reasoning_effort` | reasoningEffort | `reasoning_effort` |
| — | extraParams | — |

### 5.2 关键差异处理

1. **System Prompt 位置不同**
   - OpenAI：`messages` 数组中 role=system 的消息
   - Anthropic：顶层 `system` 字段
   - 方案：`parseOpenAiRequest()` 从 messages 中提取移除再设为 systemPrompt；`buildAnthropicRequest()` 将 systemPrompt 输出为顶层字段

2. **Tool 定义格式不同**
   - OpenAI：`tools[].function.{name,description,parameters}`
   - Anthropic：`tools[].{name,description,input_schema}`
   - 方案：`parseAnthropicRequest()` 中手动转为 OpenAI 的 function 格式

3. **Tool 调用在消息中的表示不同**
   - OpenAI：`messages[].tool_calls` 独立字段
   - Anthropic：`messages[].content` 中的 `tool_use` block
   - 方案：双向互转时在 content blocks 和 tool_calls 字段间转换

4. **Tool 结果表示不同**
   - OpenAI：role=tool, tool_call_id, content
   - Anthropic：role=user, content[] 中的 `tool_result` block
   - 方案：`buildAnthropicRequest()` 将 tool 消息转为 user 角色 + tool_result block

5. **图片编码格式**
   - OpenAI：`image_url` block
   - Anthropic：`image` block + base64 source
   - 方案：双向 base64 data URI ↔ media_type+data 互转

6. **停止序列格式不同**
   - OpenAI：`stop`（支持 string 或 array）
   - Anthropic：`stop_sequences`（仅 array）
   - 方案：buildAnthropicRequest 将 stop 转为 `stop_sequences`

7. **流式 options**
   - OpenAI：`stream_options.include_usage` 支持使用量在最终 chunk 返回
   - Anthropic：usage 在 `message_delta` 事件中

### 5.3 非流式响应转换

**Anthropic → OpenAI** (`convertAnthropicResponseToOpenAi`):

```
Anthropic response                  OpenAI response
──────────────────────────────────────────────────────
id                                → id
type=message                      → object=chat.completion
role=assistant                    → choices[0].message.role
content[].text                    → choices[0].message.content
content[].tool_use                → choices[0].message.tool_calls[]
stop_reason                       → choices[0].finish_reason
usage.{input,output}_tokens       → usage.{prompt,completion,total}_tokens
```

**OpenAI → Anthropic** (`convertOpenAiResponseToAnthropic`):

```
OpenAI response                   Anthropic response
──────────────────────────────────────────────────────
id                                → id
object=chat.completion            → type=message
choices[0].message.role           → role=assistant
choices[0].message.content        → content[].text block
choices[0].message.tool_calls[]   → content[].tool_use block
choices[0].finish_reason          → stop_reason
usage.{prompt,completion}_tokens  → usage.{input,output}_tokens
```

**Stop reason 映射**:

| OpenAI finish_reason | Anthropic stop_reason | 含义 |
|---------------------|----------------------|------|
| stop | end_turn | 正常结束 |
| length | max_tokens | token 上限 |
| tool_calls | tool_use | 工具调用 |
| content_filter | 无映射 | 内容过滤（暂保持原值） |

---

## 6. 流式状态机实现

### 6.1 设计模式

流式转换采用**逐 chunk 状态机**模式：

- 每个流式请求开始时通过 `createStreamState()` 创建一个状态实例
- 每个 SSE 事件到达时调用 `translateStreamEvent()`，传入当前状态
- 翻译器**原地修改**状态对象以追踪跨 chunk 上下文
- 流结束时调用 `translateStreamEnd()` 刷出未完成的终结事件
- 异常场景：若 `message_delta` 尚未发出（意外断开），在 `translateStreamEnd()` 中补发

### 6.2 Anthropic → OpenAI 流式状态机

#### 6.2.1 状态类 `AnthropicToOpenAiState`

```java
class AnthropicToOpenAiState implements StreamTranslateState {
    String responseId;                  // 从 message_start 提取
    String model;                       // 从 message_start 提取
    boolean messageStartSent;           // 首 chunk 是否已发
    int pendingToolCallIndex;           // 当前累积的 tool_call 索引
    String pendingToolCallId;           // 当前 tool_call id
    String pendingToolCallName;         // 当前 tool_call name
    StringBuilder pendingArguments;     // 跨 input_json_delta 累积
    String finishReason;                // 从 message_delta 提取
    boolean messageDeltaSent;           // 终结 chunk 是否已发
    int promptTokens, completionTokens; // usage 累积
}
```

#### 6.2.2 事件映射

| Anthropic 事件 | 处理逻辑 | OpenAI chunk |
|---------------|---------|-------------|
| `message_start` | 保存 responseId 和 model，发首 chunk | data: {"id":"...","object":"chat.completion.chunk","choices":[{"delta":{},"finish_reason":null}]} |
| `content_block_start` (text) | 跳过（文本通过 delta 增量到达） | 不输出 |
| `content_block_start` (tool_use) | 保存 id/name，发 tool_calls 首 chunk | data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"...","type":"function","function":{"name":"...","arguments":""}}]}}]} |
| `content_block_delta` (text_delta) | 发文本增量 chunk | data: {"choices":[{"delta":{"content":"..."}}]} |
| `content_block_delta` (input_json_delta) | 累积 + 发参数增量 chunk | data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"..."}}]}}]} |
| `content_block_stop` | 跳过 | 不输出 |
| `message_delta` | 提取 stop_reason 和 usage，发终结 chunk | data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{...}} |
| `message_stop` | 跳过（由调用方发 [DONE]） | 不输出 |

#### 6.2.3 状态流转图

```
message_start
  │
  ├── content_block_start (tool_use)
  │     └── content_block_delta (input_json_delta) ×N
  │           └── content_block_stop
  │
  └── content_block_start (text)
        └── content_block_delta (text_delta) ×N
              └── content_block_stop
  
  message_delta → message_stop → [DONE]
```

> 注意：Anthropic 的 text block start 不产生单独的 OpenAI chunk；文本内容完全通过 text_delta 增量送达。tool_use 的 arguments 也是增量送达，状态中累积但不发完整 JSON。

### 6.3 OpenAI → Anthropic 流式状态机

#### 6.3.1 状态类 `OpenAiToAnthropicState`

```java
class OpenAiToAnthropicState implements StreamTranslateState {
    int contentBlockIndex;                  // 当前 content block 索引
    boolean textContentStarted;             // 文本 block 是否已开始
    Map<Integer, ToolCallAccumulator> toolCallAccumulators; // tool_calls 按 index 累积
    boolean finishReasonSeen;               // 是否遇到 finish_reason
    boolean messageDeltaSent;               // message_delta 是否已发
    int promptTokens, completionTokens;      // usage 累积
}

class ToolCallAccumulator {
    int index;
    String id, name;
    StringBuilder arguments;
    boolean contentBlockStarted;
    boolean contentBlockStopped;
}
```

#### 6.3.2 事件映射

| OpenAI chunk 特征 | 处理逻辑 | Anthropic 事件 |
|------------------|---------|---------------|
| 首 chunk（delta 含内容，无 finish_reason） | 发 content_block_start (text) | event: content_block_start, data: {"index":0,"content_block":{"type":"text","text":""}} |
| 中间 chunk（delta.content 非空） | 发文本 delta | event: content_block_delta, data: {"index":0,"delta":{"type":"text_delta","text":"..."}} |
| delta.tool_calls[].id 非空 | 发 tool_use start | event: content_block_start, data: {"content_block":{"type":"tool_use","id":"...","name":"...","input":{}}}} |
| delta.tool_calls[].function.arguments 非空 | 发参数 delta | event: content_block_delta, data: {"delta":{"type":"input_json_delta","partial_json":"..."}} |
| finish_reason 非 null | 发 message_delta | event: message_delta, data: {"delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{...}} |
| [DONE] | translateStreamEnd 补发 message_stop | 不输出（由调用方处理） |

#### 6.3.3 状态流转图

```
首 chunk（含文本）
  ├── content_block_start (index=0, text)
  └── content_block_delta (text_delta) ×N

中间 chunk（含 tool_calls.id）
  └── content_block_start (index=1, tool_use)
        └── content_block_delta (input_json_delta) ×N

最终 chunk（含 finish_reason）
  └── message_delta + message_stop → [DONE]
```

### 6.4 流式状态机的关键设计决策

| 决策 | 理由 |
|------|------|
| **状态实例由调用方管理** | 翻译器无状态，调用方（如 RelayService）持有每个请求的状态 |
| **translateStreamEnd 兜底** | 防止异常断开导致客户端收不到 finish_reason 和 usage |
| **text block start 不先发空内容** | 简化实现，实际首个 text_delta 中已携带内容 |
| **tool_calls 的 arguments 增量发送** | 匹配 OpenAI 标准 chunk 格式，支持大参数流式显示 |
| **返回 null 跳过事件** | content_block_stop 等中间事件无需输出 |

---

## 7. `MessageTransformer` 外观层

### 7.1 职责

`MessageTransformer` 是一个 `@Component`，作为整个协议转换层的**统一入口**，同时为**向后兼容**保留旧 API。它的核心职责：

1. **请求解析**：`parseOpenAiRequest()` / `parseAnthropicRequest()` → `InternalRequest`
2. **请求构建**：`buildOpenAiRequest()` / `buildAnthropicRequest()` ← `InternalRequest`
3. **响应转换**：委托给 `TranslatorRegistry` 查找方向性翻译器
4. **流式转换**：向后兼容的无状态版本，内部仍然通过 registry 创建新状态实例
5. **错误响应构建**：`buildErrorResponse()` 按客户端格式输出错误

### 7.2 桥接逻辑

```java
// 非流式响应转换
public String transformOpenAiResponseToClient(JsonNode openAiResp, String clientFormat, String originalModel) {
    if ("openai".equals(clientFormat)) return replaceModelInJson(openAiResp, originalModel); // 透传
    ProtocolTranslator translator = translatorRegistry.find("openai", "anthropic");
    if (translator != null) return translator.translateResponse(openAiResp, originalModel);
    // 降级：仅替换 model 字段
    return replaceModelInJson(openAiResp, originalModel);
}
```

### 7.3 降级策略

当找不到对应方向的翻译器时：
1. 打印 warn 日志
2. 降级为仅替换响应中的 model 字段（`replaceModelInJson`）
3. 返回原始的 JSON 结构（客户端可能解析失败，但数据不丢失）

---

## 8. 扩展性分析

### 8.1 支持新协议

要增加一个新的协议（如 Google Gemini），需要：

1. **新增 `ProtocolTranslator` 实现**（如 `GeminiToOpenAiTranslator`）
2. **在 `MessageTransformer` 中新增解析/构建方法**（或直接使用 `@Component` 自动注册）
3. **若需要新的流式状态**，实现 `StreamTranslateState` 接口

### 8.2 新增协议的改动量

- **最小改动**：只需加一个新的 `ProtocolTranslator @Component`（自动注册）
- **全量支持**：还可能需要新的解析器、新的构建器、新的错误映射

### 8.3 当前限制

- `translateRequest()` 在两个实现中都 `throw UnsupportedOperationException`，请求转换完全由 `MessageTransformer` 的 `buildOpenAiRequest()`/`buildAnthropicRequest()` 完成
- 这意味着**协议适配器**与**请求构建逻辑**之间有代码重复的风险（`MessageTransformer` 和 `ProtocolTranslator.translateRequest()` 都做请求序列化）

---

## 9. 总结

| 维度 | 评价 |
|------|------|
| **模块化** | 高。接口/注册表/实现三层分离，方向隔离 |
| **内聚性** | 高。每个方向翻译器内聚自己的映射逻辑 |
| **可测试性** | 高。ProtocolTranslator 可独立 mock 测试，状态机逻辑可逐事件验证 |
| **扩展性** | 高。新增协议只需加实现类 + 标识字符串 |
| **流式处理** | 健壮。状态机 + 终结兜底 + null 跳过机制 |
| **向后兼容** | 好。MessageTransformer 保留旧方法签名 |
| **性能** | 每请求创建状态对象，轻量无锁 |
