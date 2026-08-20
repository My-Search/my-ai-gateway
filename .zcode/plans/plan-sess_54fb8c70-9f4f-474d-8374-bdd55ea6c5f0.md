## 功能:渠道模型自动刷新模式 + 可配置刷新频率

### 需求
1. 创建/编辑渠道时可选模型刷新模式:「自动刷新」(默认)/「不刷新」
2. 刷新频率在「系统配置」中可配置,默认 30 分钟
3. 行为语义:选择「不刷新」的渠道,新建时不自动拉取模型、定时任务跳过;表单中「获取模型」手动拉取始终可用

### 后端改动 (Java Spring Boot)

1. **实体 `Channel.java`**:新增字段 `modelRefreshEnabled` (Integer, 1=自动刷新 0=不刷新) + getter/setter
2. **数据库迁移 `update.sql`**:新增 `VERSION:v1.28.0` 块:
   - `ALTER TABLE channels ADD COLUMN model_refresh_enabled INTEGER DEFAULT 1;`
   - `UPDATE channels SET model_refresh_enabled = 1 WHERE model_refresh_enabled IS NULL;`(现有渠道默认自动刷新)
   - `INSERT OR IGNORE INTO admin_config (config_key, config_value, description) VALUES ('channel_model_refresh_interval_minutes', '30', '渠道模型自动刷新间隔（分钟），默认 30 分钟');`
3. **`AdminConfigService`**:
   - 新增常量 `KEY_CHANNEL_MODEL_REFRESH_INTERVAL_MINUTES`
   - `getSystemConfig()` 读取该 key(缺省默认 "30")并放入返回 map
   - 新增 `getChannelModelRefreshIntervalMinutes()`:非法/缺失回退 30(仿照 `getCircuitBreakerProbeIntervalMinutes`)
4. **`AdminConfigController`** PUT `/config/system`:新增该校验(1-1440 分钟)
5. **`ChannelCrud`**:
   - `create()`:当 `modelRefreshEnabled == 0` 时跳过创建后的自动加载模型(默认 1 保留现状)
   - 新增 `listAutoRefreshChannels()`:`enabled=1 AND model_refresh_enabled=1`(供定时任务查询)
6. **`ChannelService`**:暴露 `listAutoRefreshChannels()` 门面方法
7. **`AdminChannelController`** create/update:读取 `model_refresh_enabled` 字段,缺省默认 1
8. **新增 `schedule/ChannelModelRefreshTask.java`**(仿照 `CircuitBreakerRecoveryTask`):
   - 60 秒 tick + 从 `admin_config` 读取间隔(默认 30 分钟)判定是否到点
   - 到点遍历 `listAutoRefreshChannels()` 逐个调用 `channelService.reloadModels(id)`,每个渠道单独 try-catch 防中断
   - `AtomicBoolean` 防上一轮刷新未完成时重叠执行;每个渠道结果打一条日志

### 前端改动 (Vue 3)

1. **`api/channel.ts`**:`Channel` 接口加 `modelRefreshEnabled?: number`
2. **`api/system.ts`**:`SystemConfig` 接口加 `channel_model_refresh_interval_minutes: string`
3. **`views/channel/Form.vue`**:
   - `form` 初始值加 `modelRefreshEnabled: 1`(默认自动刷新)
   - 在「状态」下拉旁新增「模型刷新」select:自动刷新 / 不刷新,hint 注明频率可在系统配置中调整
   - `handleSave` payload 经 `...form.value` 自动携带该字段
4. **`views/setting/SystemConfig.vue`**:
   - `form` 加 `channel_model_refresh_interval_minutes: 30`
   - 新增「渠道模型刷新」section(含 desc):分钟数输入 `:min="1" :max="1440"`
   - `loadConfig` 加载、`handleSave` 校验(≥1)+ 以字符串提交
5. **i18n** `locales/zh-CN.ts` 与 `en-US.ts`(逐行对齐):
   - `channel.form.modelRefresh / modelRefreshAuto / modelRefreshNone / modelRefreshHint`
   - `systemConfig.modelRefreshManagement / modelRefreshManagementDesc / modelRefreshInterval / modelRefreshIntervalHint / modelRefreshIntervalInvalid`

### 测试
轻量补充:为 `ChannelCrud.listAutoRefreshChannels` 过滤逻辑或 `ChannelModelRefreshTask` 的防重叠/skip 逻辑加一个简单单元测试(遵循现有 `CircuitBreakerServiceTest` 模式)。

### 验证
- `mvn test` 后端测试通过
- 前端 `vue-tsc`/构建通过