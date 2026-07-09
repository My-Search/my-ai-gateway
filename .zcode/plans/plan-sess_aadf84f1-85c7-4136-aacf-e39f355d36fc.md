# 修复方案：6 项高/中危问题

基于已完成的代码审查与探索，修复 6 项问题。原则：最小侵入、保留向后兼容、每项修复配套测试。

---

## 修复 #1：`/v1/*` AI 接口鉴权缺失（高危）

**方案**：在 RelayService 各入口校验 `gatewayApiKeyId`。

**改动文件**：
- `RelayService.java`

**改动点**：在 `chatCompletions`(行123)、`messages`(行136)、`chatCompletionsStream`(行154)、`messagesStream`(行196) 四个入口方法中，在 `logOriginalRequest(...)` 返回后、`parseRequest` 之前，插入鉴权检查：

```java
if (gatewayApiKeyId == null) {
    requestLogService.logComplete(traceId, null, null, null, null, null,
            "fail", "auth", "无效的 API Key", 0, 0);
    return Mono.just(messageTransformer.buildErrorResponse(
            clientFormat, "无效或缺失的 API Key", "authentication_error", 401));
}
```
- 非流式入口：返回 `Mono.just(...)` 错误响应（含正确的 clientFormat "openai"/"anthropic"）
- 流式入口：通过 `sendSseError(emitter, "无效或缺失的 API Key")` 后 `return emitter`

**为何安全**：探索确认所有 `internalClient=true` 调用方（AdminApi Playground 行1696、ShareApi 行213）都用 DB 真实 Key 构造 `Bearer` 头，`resolveIdFromAuthHeader` 会正常解析出 id，不受影响。

**注意**：clientFormat 在 parseRequest 前未知，但入口方法已知是 openai/anthropic（由调用者决定），直接用对应字符串即可。

---

## 修复 #2：JWT 弱默认密钥（高危）

**改动文件**：
- `application.yml`（改默认值）
- `JwtTokenProvider.java`（加启动校验）

**改动点**：
1. `application.yml:58`：将密钥占位为环境变量，保留默认值用于开发但标注生产必改：
   ```yaml
   secret: ${APP_JWT_SECRET:my-ai-gateway-jwt-secret-key-2024-change-in-production}
   ```
2. `JwtTokenProvider.java`：新增 `@PostConstruct` 校验方法，密钥长度 < 32 字节 或 等于默认值且非 dev profile 时，记录 WARN 日志。不直接 fail-fast（避免破坏现有部署），但强烈提示。如果用户希望强制 fail-fast 可在审查时提出。

---

## 修复 #3：流式请求用「全局超时」误杀长响应（中危）

**改动文件**：
- `RelayService.java`（`invokeStreamCandidateWithRetries` 行654、`callProviderStream` 行763）

**改动点**：将流式的 `.timeout(Duration.ofMillis(timeoutMs))` 改为「空闲超时」语义——使用 Reactor 的 `Flux.timeout(Duration, fallbackPublisher)` 按每个 chunk 间隔计时。

由于原生 `.timeout(Duration)` 是"自订阅起无任何 onNext 的超时"，对持续出 chunk 的流不会触发，因此实际上当前实现**在持续出 chunk 时不会误杀**。真正的问题是：首个 chunk 前的等待 + 总时长。

**重新评估后的精确方案**：保留首 chunk 前的连接超时（responseTimeout 已在 HttpClient 层 60s），但移除流式 Flux 上的全局 `.timeout()`，改为基于"空闲间隔"的超时：用 `Flux.timeout(Duration)` 配合一个每收到元素就重置的调度。具体实现采用 `timeout(Duration, fallback)` + 在每个 onNext 后用 `switchOnFirst`/或更简单的方案：

实际最简且正确的改法：将 `.timeout(Duration.ofMillis(timeoutMs))` 替换为 `.timeout(Duration.ofMillis(idleTimeoutMs), Flux.empty())`，其中 `idleTimeoutMs` 取 `Math.max(timeoutMs, 120_000)`（至少 2 分钟空闲才断）。这样持续出 chunk 的长流不会被中断，真正卡死的连接（无任何输出）会在 idleTimeout 后断开。

**改动**：
```java
// invokeStreamCandidateWithRetries 行667 & callProviderStream
long idleTimeoutMs = Math.max(timeoutMs, 120_000L); // 空闲至少2分钟
.timeout(Duration.ofMillis(idleTimeoutMs), Flux.empty())
```
注意 `Flux.timeout(Duration, fallback)` 中 fallback 为 empty 会触发 onComplete 而非 onError，需调整为用 `Flux.error(...)` 作为 fallback 以保留重试语义：
```java
.timeout(Duration.ofMillis(idleTimeoutMs), Flux.error(new RuntimeException("Stream idle timeout")))
```

---

## 修复 #4：reasoning_content 污染上下文续写（中危）

**改动文件**：
- `RelayService.java`（`extractTextContentFromRawData` 行1197）
- `RelayServiceTest.java`（新增测试用例）

**改动点**：`extractTextContentFromRawData` 当前同时返回 `content` 和 `reasoning_content`（行1219-1222）。由于该方法唯一用途是「累积流式内容用于候选切换上下文」，而 reasoning_content 是思考过程不应作为 assistant 已输出正文。

**方案**：移除该方法的 `reasoning_content` 提取分支（行1219-1222）。8 处现有测试不涉及 reasoning_content，不受影响；新增 1 个测试验证 reasoning_content 不被提取。

```java
// 移除这段（行1219-1222）
if (delta.has("reasoning_content")) {
    return delta.get("reasoning_content").asText();
}
```

---

## 修复 #5：`testChannelModel` JSON 字符串拼接（中危）

**改动文件**：
- `RelayService.java`（`testChannelModel` 行814，构造 requestBody 处 行829-831）

**改动点**：用 `ObjectMapper` 构造 JSON 替代字符串拼接：
```java
ObjectNode reqNode = objectMapper.createObjectNode();
reqNode.put("model", channelModel.getModelName());
reqNode.put("max_tokens", 100);
ArrayNode messages = reqNode.putArray("messages");
ObjectNode userMsg = messages.addObject();
userMsg.put("role", "user");
userMsg.put("content", message);
String requestBody = objectMapper.writeValueAsString(reqNode);
```
openai/anthropic 两种 provider 的请求体格式此场景相同（都是标准 messages 结构），可合并为一个构造逻辑。

---

## 修复 #6：重复 DB 查询（中危）

**改动文件**：
- `RelayService.java`

**改动点**：两部分优化，均**不改 `tryCandidates`/`tryStreamCandidates` 公开签名**（避免破坏 6 处测试）：

1. **模型配置缓存**：在 `executeRelay`(行250) 和 `executeStreamRelay`(行449) 入口处，一次性解析 `modelId`、`strategy`、`maxAttempts` 并存入局部变量，通过新私有方法 `resolveModelRouting(String modelName)` 返回一个小的 record（modelId/strategy/maxAttempts），传入 tryCandidates 时使用 `final` 捕获的局部变量替代每次递归的 DB 查询。

   具体做法：由于 `tryCandidates` 是递归且签名固定，改为在 `tryCandidates` 方法体内首次调用时缓存到 `InternalRequest` 新增的 transient 字段，或更简洁地——将解析结果存在一个 `RoutingContext` record 中，通过方法参数外的闭包捕获。

   **最简洁且不破坏测试的方案**：新增私有方法 `resolveModelRouting(modelName)`，在 `executeRelay`/`executeStreamRelay` 调用一次，把结果作为 `final` 局部变量；`tryCandidates` 内部的 `resolveModelStrategy`/`resolveModelId`/`getMaxAttempts` 调用改为引用外层捕获的变量。由于 `tryCandidates` 是包级可见方法（测试直接调），需保留其内部调用 `resolveModelStrategy` 等的能力——因此保留这些方法，仅在外层缓存，内部递归用闭包。

   **实际实现**：将 `tryCandidates` 的递归改为不重新解析：在 `executeRelay` 中先 `final RoutingContext ctx = resolveModelRouting(req.getModel())`，然后让 `tryCandidates` 接收 ctx——但这要改签名。权衡后：**保留公开 8 参 `tryCandidates` 签名（测试用），内部新增带 ctx 的私有重载**，公开版内部委托给带 ctx 版（首次解析一次 ctx）。流式 `tryStreamCandidates` 无测试直接调用，可直接加 ctx 参数。

2. **`logSkippedCandidates` 复用**：`logSkippedCandidates`(行1978) 与 `getAvailableCandidates`(行1663) 重复查询。改为：`getAvailableCandidates` 时同步收集"被跳过的候选及原因"，返回一个 `CandidatesResult{candidates, skippedLogEntries}`，`logSkippedCandidates` 改为接收该结果而非重新查询。需同步调整 `executeRelay`/`executeStreamRelay` 的调用顺序。

   由于此改动牵涉日志记录语义（被跳过候选的日志时机），且 `logSkippedCandidates` 当前在 `getAvailableCandidates` 之前调用（行254→255），改为复用需调整顺序：先 `getAvailableCandidates` 收集结果，再从结果中取 skipped 日志。这是合理的——skipped 日志记录的是"为何这些候选没进可用列表"，在构建完候选后记录符合语义。

---

## 测试计划

- **#1**：新增 `RelayServiceTest` 测试，mock `apiKeyService.resolveIdFromAuthHeader` 返回 null，验证返回 401 错误响应且不调用 `executeRelay`。需调整现有测试 setup（现有测试 `tryCandidates` 直接调用，绕过入口鉴权，不受影响）。
- **#3**：新增流式空闲超时测试（验证持续出 chunk 不超时）。
- **#4**：新增 `extractTextContentFromRawData_openaiReasoningContent_returnsNull` 测试。
- **#5**：新增/调整 `testChannelModel` 测试验证含特殊字符 message 的 JSON 正确性（如现有无测试则新增）。
- **#6**：验证现有 `tryCandidates` 6 处测试仍通过（签名不变）。
- 运行 `mvn test` 确认全部通过。

## 文件改动清单

| 文件 | 改动 |
|------|------|
| `src/main/java/com/myai/gateway/relay/RelayService.java` | #1 #3 #4 #5 #6 |
| `src/main/java/com/myai/gateway/config/JwtTokenProvider.java` | #2 |
| `src/main/resources/application.yml` | #2 |
| `src/test/java/com/myai/gateway/relay/RelayServiceTest.java` | #1 #3 #4 新增测试 |

## 执行顺序

1. #2 JWT（最简单、独立）
2. #5 JSON 拼接（独立、简单）
3. #4 reasoning_content（独立、简单）
4. #1 鉴权（独立，需配套测试）
5. #6 重复查询（需重构 tryCandidates，最后做以降低风险）
6. #3 流式超时（依赖对 Flux 的理解，独立改）
7. 运行 `mvn test` 全量验证