-- ========================================
-- VERSION:v1.0.0
-- 初始版本 - 创建基础表结构
-- ========================================

-- 渠道表
CREATE TABLE IF NOT EXISTS channels (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT UNIQUE NOT NULL,
    channel_type TEXT NOT NULL DEFAULT 'openai',
    api_key TEXT DEFAULT '',
    base_url TEXT DEFAULT '',
    enabled INTEGER DEFAULT 1,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 渠道模型表（渠道下的具体模型）
CREATE TABLE IF NOT EXISTS channel_models (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    channel_id INTEGER NOT NULL,
    model_name TEXT NOT NULL,
    display_name TEXT DEFAULT '',
    enabled INTEGER DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (channel_id) REFERENCES channels(id) ON DELETE CASCADE,
    UNIQUE(channel_id, model_name)
);

-- 自定义/对外模型表
CREATE TABLE IF NOT EXISTS models (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    model_name TEXT UNIQUE NOT NULL,
    description TEXT DEFAULT '',
    strategy TEXT DEFAULT 'failover',
    enabled INTEGER DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 模型与渠道模型关联表
CREATE TABLE IF NOT EXISTS model_channel_rels (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    model_id INTEGER NOT NULL,
    channel_model_id INTEGER NOT NULL,
    weight INTEGER DEFAULT 1,
    enabled INTEGER DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (model_id) REFERENCES models(id) ON DELETE CASCADE,
    FOREIGN KEY (channel_model_id) REFERENCES channel_models(id) ON DELETE CASCADE,
    UNIQUE(model_id, channel_model_id)
);

-- 熔断配置表
CREATE TABLE IF NOT EXISTS circuit_breaker_configs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    model_id INTEGER NOT NULL,
    retry_count INTEGER DEFAULT 3,
    circuit_break_duration INTEGER DEFAULT 60,
    circuit_break_scope TEXT DEFAULT 'model',
    enabled INTEGER DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (model_id) REFERENCES models(id) ON DELETE CASCADE,
    UNIQUE(model_id)
);

-- 熔断状态表（记录当前熔断状态）
CREATE TABLE IF NOT EXISTS circuit_breaker_states (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    channel_id INTEGER,
    channel_model_id INTEGER,
    is_open INTEGER DEFAULT 0,
    fail_count INTEGER DEFAULT 0,
    opened_at TIMESTAMP,
    expire_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- API密钥表（网关层面的API密钥）
CREATE TABLE IF NOT EXISTS api_keys (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    key_name TEXT NOT NULL,
    key_value TEXT NOT NULL UNIQUE,
    enabled INTEGER DEFAULT 1,
    last_used_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 请求日志表
CREATE TABLE IF NOT EXISTS request_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    trace_id TEXT NOT NULL,
    api_key_name TEXT DEFAULT '',
    model_name TEXT DEFAULT '',
    channel_model_name TEXT DEFAULT '',
    channel_name TEXT DEFAULT '',
    phase TEXT NOT NULL,
    status TEXT DEFAULT 'pending',
    message TEXT DEFAULT '',
    response_time_ms INTEGER DEFAULT 0,
    first_byte_ms INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 数据库版本表
CREATE TABLE IF NOT EXISTS db_schema_version (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    description TEXT DEFAULT ''
);

-- ========================================
-- VERSION:v1.1.0
-- 添加索引优化查询性能
-- ========================================

CREATE INDEX IF NOT EXISTS idx_channel_models_channel_id ON channel_models(channel_id);
CREATE INDEX IF NOT EXISTS idx_model_channel_rels_model_id ON model_channel_rels(model_id);
CREATE INDEX IF NOT EXISTS idx_model_channel_rels_channel_model_id ON model_channel_rels(channel_model_id);
CREATE INDEX IF NOT EXISTS idx_request_logs_trace_id ON request_logs(trace_id);
CREATE INDEX IF NOT EXISTS idx_request_logs_created_at ON request_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_circuit_breaker_states_channel ON circuit_breaker_states(channel_id);
CREATE INDEX IF NOT EXISTS idx_circuit_breaker_states_model ON circuit_breaker_states(channel_model_id);

-- ========================================
-- VERSION:v1.2.0
-- 请求日志添加重试索引字段（用于缩进显示重试链路）
-- ========================================

ALTER TABLE request_logs ADD COLUMN retry_index INTEGER DEFAULT 0;

-- ========================================
-- VERSION:v1.3.0
-- 添加管理员配置表
-- ========================================

CREATE TABLE IF NOT EXISTS admin_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    config_key TEXT NOT NULL UNIQUE,
    config_value TEXT NOT NULL,
    description TEXT DEFAULT '',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 初始化管理员账号配置（username 和 password 两条记录）
INSERT OR IGNORE INTO admin_config (config_key, config_value, description) VALUES ('username', '', '管理员用户名');
INSERT OR IGNORE INTO admin_config (config_key, config_value, description) VALUES ('password', '', '管理员密码(加密存储)');

-- ========================================
-- VERSION:v1.5.0
-- 模型关联渠道模型支持自定义排序
-- ========================================

-- 修改 model_channel_rels 表，添加 sort_order 字段（越小越优先）
ALTER TABLE model_channel_rels ADD COLUMN sort_order INTEGER DEFAULT 0;

-- 初始化现有关联的 sort_order 为创建时间的倒序（新创建的排前面）
-- 通过子查询更新：将现有数据按创建时间倒序排，然后设置 sort_order
-- 注意：SQLite 不支持 UPDATE ... ORDER BY，这里用临时方案：不处理历史数据，默认都是0，按创建时间正序（最旧的最小）

-- ========================================
-- VERSION:v1.4.0
-- 渠道支持多 API Key
-- ========================================

-- 删除旧表（如存在）
DROP TABLE IF EXISTS channel_api_keys;

-- 渠道 API Keys 表（一个渠道可以有多个 API Key）
CREATE TABLE IF NOT EXISTS channel_api_keys (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    channel_id INTEGER NOT NULL,
    key_name TEXT NOT NULL,
    api_key TEXT NOT NULL,
    enabled INTEGER DEFAULT 1,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (channel_id) REFERENCES channels(id) ON DELETE CASCADE,
    UNIQUE(channel_id, key_name)
);

-- 修改 channels 表，移除旧的 api_key 字段（SQLite 不支持 DROP COLUMN，用其他方式）
-- 注意：SQLite 原生不支持 DROP COLUMN，但我们可以通过重建表来实现
-- 这里我们保留原字段但不使用它，新逻辑全部走 channel_api_keys 表

-- 修改 channel_models 表，添加关联到 channel_api_keys（SQLite 不支持 ALTER TABLE ADD CONSTRAINT）
ALTER TABLE channel_models ADD COLUMN channel_api_key_id INTEGER;

-- 修改熔断状态表，添加 channel_api_key_id 字段
ALTER TABLE circuit_breaker_states ADD COLUMN channel_api_key_id INTEGER;

-- 索引优化
CREATE INDEX IF NOT EXISTS idx_channel_api_keys_channel_id ON channel_api_keys(channel_id);
CREATE INDEX IF NOT EXISTS idx_channel_models_api_key ON channel_models(channel_api_key_id);
CREATE INDEX IF NOT EXISTS idx_circuit_breaker_states_api_key ON circuit_breaker_states(channel_api_key_id);

-- ========================================
-- VERSION:v1.6.0
-- 熔断层级重构为两级：渠道级（按 API Key）/ 模型级
-- ========================================

-- 1. 将熔断配置表中旧的 'apikey' 范围归一化为 'channel'
UPDATE circuit_breaker_configs SET circuit_break_scope = 'channel' WHERE circuit_break_scope = 'apikey';

-- 2. 将旧的 API Key 级熔断状态补充 channel_id（按 API Key 熔断的新语义需要 channel_id + channel_api_key_id）
UPDATE circuit_breaker_states
SET channel_id = (
    SELECT channel_id FROM channel_api_keys WHERE channel_api_keys.id = circuit_breaker_states.channel_api_key_id
)
WHERE channel_id IS NULL AND channel_api_key_id IS NOT NULL;

-- 3. 添加联合索引以加速新的两级熔断查询
CREATE INDEX IF NOT EXISTS idx_circuit_breaker_states_channel_key_model
    ON circuit_breaker_states(channel_id, channel_api_key_id, channel_model_id);

-- ========================================
-- VERSION:v1.7.0
-- 请求日志添加 token 用量字段，用于统计渠道/模型的 token 消耗
-- ========================================

ALTER TABLE request_logs ADD COLUMN prompt_tokens INTEGER DEFAULT 0;
ALTER TABLE request_logs ADD COLUMN completion_tokens INTEGER DEFAULT 0;
ALTER TABLE request_logs ADD COLUMN total_tokens INTEGER DEFAULT 0;

-- 为 token 统计查询添加索引
CREATE INDEX IF NOT EXISTS idx_request_logs_channel_name ON request_logs(channel_name);
CREATE INDEX IF NOT EXISTS idx_request_logs_channel_model ON request_logs(channel_model_name);

-- ========================================
-- VERSION:v1.8.0
-- API密钥添加分享码字段，用于生成不可预测的分享链接
-- ========================================

-- 添加分享码字段
ALTER TABLE api_keys ADD COLUMN share_code TEXT;

-- 为分享码添加唯一索引
CREATE UNIQUE INDEX IF NOT EXISTS idx_api_keys_share_code ON api_keys(share_code);

-- ========================================
-- VERSION:v1.9.0
-- API密钥添加分享开关字段，支持可撤销分享
-- ========================================

-- 添加分享开关字段（1=启用分享，0=关闭分享）
ALTER TABLE api_keys ADD COLUMN shared INTEGER DEFAULT 1;

-- 为历史数据设置默认值（已有记录的 shared 设为 0，需要手动开启）
UPDATE api_keys SET shared = 0 WHERE shared IS NULL;

-- ========================================
-- VERSION:v1.10.0
-- channel_models 添加 last_used_at 字段，支持 LRU 轮询（按模型最后的最后使用时间排序）
-- ========================================

-- ========================================
-- VERSION:v1.11.0
-- channel_models 添加 source 字段，区分手动添加 / API 获取的模型
-- ========================================

ALTER TABLE channel_models ADD COLUMN source TEXT DEFAULT 'manual';

-- 为历史数据设置默认值：手动创建的设为 'manual'，自动获取的这里不做覆盖
-- 新版本创建/加载时会正确设置 source 字段

-- ========================================
-- VERSION:v1.12.0
-- 修复请求日志 response_time_ms 默认值为 0 导致前端显示异常的问题
-- phase 为 start/retry/skip/reroute 的记录本无时间数据，不应显示响应时间
-- ========================================

-- 清除 phase 日志（非 success/fail 终结记录）中误存的 0 值，设为 NULL
-- 这些记录本无响应时间，SQLite 默认值 0 导致前端错误显示 "0ms"
UPDATE request_logs SET response_time_ms = NULL WHERE phase NOT IN ('success', 'fail') AND response_time_ms = 0;
-- 同时清理 success/fail 记录中可能误存的 0（真实响应时间至少 >0ms）
UPDATE request_logs SET response_time_ms = NULL WHERE phase IN ('success', 'fail') AND response_time_ms = 0;

-- ========================================
-- VERSION:v1.13.0
-- 模型关联支持「继承」模式：入口模型可继承另一个入口模型的关联
-- ========================================

-- rel_mode: 'self_add' (默认) | 'inherit'
--   self_add: 使用本模型在 model_channel_rels 中的自有关联
--   inherit: 关联列表实时映射自 inherit_from_model_id 所指向的源模型（只读）
ALTER TABLE models ADD COLUMN rel_mode TEXT DEFAULT 'self_add';

-- inherit_from_model_id: 仅在 rel_mode='inherit' 时有效，指向被继承的源入口模型
-- 注意：使用外键约束时 SQLite 会强制引用完整性，源模型被删除需由应用层处理（阻止删除）
ALTER TABLE models ADD COLUMN inherit_from_model_id INTEGER;

-- 为历史数据确保默认模式为 self_add
UPDATE models SET rel_mode = 'self_add' WHERE rel_mode IS NULL;

-- 为继承查询添加索引（按源模型查找继承者）
CREATE INDEX IF NOT EXISTS idx_models_inherit_from ON models(inherit_from_model_id);

-- ========================================
-- VERSION:v1.15.0
-- 模型关联支持默认 reasoning_effort（思考强度）
-- 每个关联的渠道模型可独立配置默认 reasoning_effort，
-- 如果请求中传了 reasoning_effort 则覆盖默认值
-- ========================================

ALTER TABLE model_channel_rels ADD COLUMN reasoning_effort TEXT;

-- ========================================
-- VERSION:v1.14.0
-- 系统配置：日志管理配置项（保留天数、定时清理开关）
-- ========================================

-- 日志保留天数（默认30天）
INSERT OR IGNORE INTO admin_config (config_key, config_value, description) VALUES ('log_retention_days', '7', '日志保留天数，超过此天数的日志将被自动清理');
-- 日志定时清理开关（1=开启，0=关闭）
INSERT OR IGNORE INTO admin_config (config_key, config_value, description) VALUES ('log_cleanup_enabled', '1', '日志定时清理开关，1=开启，0=关闭');

-- ========================================
-- VERSION:v1.16.0
-- 添加 (created_at, phase) 复合索引优化 Dashboard 统计查询性能
-- 所有 Dashboard 聚合查询均按 created_at 范围过滤 + phase 条件筛选，
-- 复合索引可大幅减少索引扫描范围
-- ========================================

CREATE INDEX IF NOT EXISTS idx_request_logs_created_at_phase ON request_logs(created_at, phase);

-- ========================================
-- VERSION:v1.17.0
-- 多模态规则表 + 渠道模型 input 类型字段
-- ========================================

-- 多模态规则表：通过正则匹配模型名称，自动标记模型支持的输入类型
CREATE TABLE IF NOT EXISTS multimodal_rules (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    pattern TEXT NOT NULL,
    append_type TEXT NOT NULL DEFAULT 'image',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 渠道模型 input 类型字段：标记模型支持的输入模态，如 'text' / 'text,image'
ALTER TABLE channel_models ADD COLUMN input TEXT DEFAULT 'text';

-- ========================================
-- VERSION:v1.18.0
-- request_logs 新增 request_headers / request_body 字段，用于查看原始请求
-- ========================================

ALTER TABLE request_logs ADD COLUMN request_headers TEXT;
ALTER TABLE request_logs ADD COLUMN request_body TEXT;

-- ========================================
-- VERSION:v1.19.0
-- 入口模型添加多模态失效会话数字段（图片/视频/音频独立配置）
-- ========================================

ALTER TABLE models ADD COLUMN image_invalidate_count INTEGER DEFAULT 0;
ALTER TABLE models ADD COLUMN video_invalidate_count INTEGER DEFAULT 0;
ALTER TABLE models ADD COLUMN audio_invalidate_count INTEGER DEFAULT 0;

-- ========================================
-- VERSION:v1.20.0
-- 系统配置：原始请求数据保留时长（request_headers / request_body 的 TTL）
-- request_body_ttl_hours: 超过此小时数的原始请求数据将被定时清理（request_headers/body 置 NULL）
-- 0=跟随日志保留天数（日志删除时一并清理），>0 表示保留 N 小时后清理
-- ========================================

INSERT OR IGNORE INTO admin_config (config_key, config_value, description) VALUES ('request_body_ttl_hours', '4', '请求数据保留时长（小时），超过此时间的 request_headers/body 将被清理，0=跟随日志保留天数');

-- ========================================
-- VERSION:v1.21.0
-- request_logs 新增 gateway_api_key_id 字段（网关 API Key 主键）
-- 用途：让"使用历史"图表和"请求日志"按网关 API Key 过滤时，按 id 精确匹配，
--      避免网关 API Key 名称与渠道 API Key 名称同名时筛选错乱。
-- 历史数据该列为 NULL（无法回填网关 Key），过滤时不命中历史记录。
-- ========================================

ALTER TABLE request_logs ADD COLUMN gateway_api_key_id INTEGER;

-- 为按网关 Key 过滤的查询加索引（图表聚合 / 日志列表）
CREATE INDEX IF NOT EXISTS idx_request_logs_gateway_api_key_id ON request_logs(gateway_api_key_id);

-- ========================================
-- VERSION:v1.22.0
-- 系统配置：重试/失败请求数据保留时长（retry_fail_ttl_hours）
-- 普通请求数据保留时长为 request_body_ttl_hours（默认 4 小时）
-- 重试/失败请求数据保留时长为 retry_fail_ttl_hours（默认 空 = 跟随请求数据保留时长，0 = 跟随日志保留天数）
-- ========================================

INSERT OR IGNORE INTO admin_config (config_key, config_value, description) VALUES ('retry_fail_ttl_hours', '', '重试/失败请求数据保留时长（小时），超过此时间的失败/重试记录的 request_headers/body 将被清理，留空=跟随请求数据保留时长，0=跟随日志保留天数');

-- ========================================
-- VERSION:v1.23.0
-- 入口模型添加 hidden（隐藏）字段
-- hidden=1 表示该模型在获取模型列表时不可见，但通过模型ID仍可直接调用
-- 仅影响对外 API 的模型列表（/v1/models、分享接口），管理后台始终可见
-- ========================================

ALTER TABLE models ADD COLUMN hidden INTEGER DEFAULT 0;
UPDATE models SET hidden = 0 WHERE hidden IS NULL;
  
  
-- ========================================  
-- VERSION:v1.24.0  
-- Prompt ע  
-- ְ֧ģԶע system/user/assistant Ϣ  
-- ========================================  
  
CREATE TABLE IF NOT EXISTS prompt_injections (  
    id INTEGER PRIMARY KEY AUTOINCREMENT,  
    model_id INTEGER NOT NULL,  
    name TEXT NOT NULL DEFAULT '',  
    inject_role TEXT NOT NULL DEFAULT 'system',  
    inject_position TEXT NOT NULL DEFAULT 'prepend',  
    content TEXT NOT NULL DEFAULT '',  
    enabled INTEGER DEFAULT 1,  
    priority INTEGER DEFAULT 0,  
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,  
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,  
    FOREIGN KEY (model_id) REFERENCES models(id) ON DELETE CASCADE  
);  
  
CREATE INDEX IF NOT EXISTS idx_prompt_injections_model_id ON prompt_injections(model_id);

-- ========================================
-- VERSION:v1.25.0
-- 系统配置：渠道模型请求超时上下限（可配置）
-- 默认最小 20 秒，最大 60 秒，替代 LatencyTracker 中的硬编码常量
-- ========================================

INSERT OR IGNORE INTO admin_config (config_key, config_value, description) VALUES ('timeout_min_seconds', '20', '渠道模型请求最小超时时间（秒），自适应超时的下限，默认 20 秒');
INSERT OR IGNORE INTO admin_config (config_key, config_value, description) VALUES ('timeout_max_seconds', '60', '渠道模型请求最大超时时间（秒），自适应超时的上限，默认 60 秒');

-- ========================================
-- VERSION:v1.26.0
-- 系统配置：原始请求数据保存级别
-- info=全部保存, warn=仅重试/错误时保存, error=仅失败时保存, none=从不保存
-- ========================================

INSERT OR IGNORE INTO admin_config (config_key, config_value, description) VALUES ('request_data_save_level', 'info', '原始请求数据保存级别：info=全部保存，warn=重试/错误时保存，error=仅失败时保存，none=从不保存');

-- ========================================
-- VERSION:v1.27.0
-- 入口模型添加 force_override_reasoning_effort（强制覆盖思考强度）字段
-- force_override_reasoning_effort=1 时，忽略客户端请求中携带的 reasoning_effort，
-- 强制使用关联关系中配置的默认思考强度
-- ========================================

ALTER TABLE models ADD COLUMN force_override_reasoning_effort INTEGER DEFAULT 0;
UPDATE models SET force_override_reasoning_effort = 0 WHERE force_override_reasoning_effort IS NULL;

-- ========================================
-- VERSION:v1.28.0
-- 渠道模型自动刷新：channels 支持按渠道开关自动刷新模型
-- model_refresh_enabled=1 定时任务按系统配置间隔刷新（默认 30 分钟），0=不刷新
-- 刷新间隔在 admin_config 的 channel_model_refresh_interval_minutes 配置（默认 30 分钟）
-- ========================================

ALTER TABLE channels ADD COLUMN model_refresh_enabled INTEGER DEFAULT 1;
UPDATE channels SET model_refresh_enabled = 1 WHERE model_refresh_enabled IS NULL;

INSERT OR IGNORE INTO admin_config (config_key, config_value, description) VALUES ('channel_model_refresh_interval_minutes', '30', '渠道模型自动刷新间隔（分钟），默认 30 分钟');

-- ========================================
-- VERSION:v1.29.0
-- 请求日志新增首字节响应时间字段
-- first_byte_ms：从请求发出到收到首个响应字节的毫秒数（未收到响应字节时为 NULL）
-- "模型关联/渠道模型"列表的"首字节平均响应"基于该字段统计
-- ========================================

ALTER TABLE request_logs ADD COLUMN first_byte_ms INTEGER;

-- ========================================
-- VERSION:v1.30.0
-- 统计查询性能优化：新增覆盖索引
-- idx_request_logs_created_at_phase_trace：
--   覆盖 Dashboard 各类按时间范围 + phase 过滤的 trace 去重计数聚合
--   （COUNT(DISTINCT CASE WHEN phase=... THEN trace_id END)），纯索引扫描零回表
--   同时覆盖原 idx_request_logs_created_at 与 idx_request_logs_created_at_phase 的场景，后两者删除以降低写入放大
-- idx_request_logs_channel_phase：
--   覆盖渠道维度用量聚合（phase='success' + channel_name 过滤的 GROUP BY channel_model_name）
-- retry_fail_ttl_hours 语义变更：0 从"永久保留"改为"跟随日志保留天数"，
--   同步更新存量库中的配置描述（INSERT OR IGNORE 对已存在的行不会生效）
-- ========================================

CREATE INDEX IF NOT EXISTS idx_request_logs_created_at_phase_trace ON request_logs(created_at, phase, trace_id);
CREATE INDEX IF NOT EXISTS idx_request_logs_channel_phase ON request_logs(channel_name, phase);
DROP INDEX IF EXISTS idx_request_logs_created_at;
DROP INDEX IF EXISTS idx_request_logs_created_at_phase;

UPDATE admin_config SET description = '重试/失败请求数据保留时长（小时），超过此时间的失败/重试记录的 request_headers/body 将被清理，0=跟随日志保留天数' WHERE config_key = 'retry_fail_ttl_hours';

-- ========================================
-- VERSION:v1.31.0
-- 请求日志新增思考强度字段：请求开始记录客户端值，路由阶段记录实际值
-- ========================================
ALTER TABLE request_logs ADD COLUMN reasoning_effort TEXT;

-- ========================================
-- VERSION:v1.33.0
-- 渠道支持自定义请求头：channels 新增 custom_headers TEXT 字段（JSON 键值对）
-- ========================================
ALTER TABLE channels ADD COLUMN custom_headers TEXT;

-- ========================================
-- VERSION:v1.35.0
-- 界面合并展示：request_body_ttl_hours 与 retry_fail_ttl_hours 合并为一行双输入（普通 / 重试失败）。
-- 语义变更：request_body_ttl_hours 的 0 从"永久保留"改为"跟随日志保留天数"（日志删除时一并清理）；
--   retry_fail_ttl_hours：留空=跟随请求数据保留时长，0=跟随日志保留天数。
--   存量已保存的值保持不变，仅同步更新配置描述（INSERT OR IGNORE 对已存在的行不会生效）
-- ========================================
UPDATE admin_config SET description = '请求数据保留时长（小时），超过此时间的 request_headers/body 将被清理，0=跟随日志保留天数' WHERE config_key = 'request_body_ttl_hours';
UPDATE admin_config SET description = '重试/失败请求数据保留时长（小时），超过此时间的失败/重试记录的 request_headers/body 将被清理，留空=跟随请求数据保留时长，0=跟随日志保留天数' WHERE config_key = 'retry_fail_ttl_hours';

-- ========================================
-- VERSION:v1.36.0
-- 性能优化：为请求日志 trace 分页/分组极值查询新增 (trace_id, created_at) 复合索引。
--   request_logs 的"日志列表按 trace 分页""按 trace 分组取最新时间"等查询原先基于
--   idx_request_logs_trace_id(trace_id) 单列索引，GROUP BY trace_id 后仍需回表取
--   created_at 做 MAX/ORDER BY，数据量大时回表随机 IO 显著（每行一次 B-Tree 查找）。
--   复合索引使 MAX(created_at) / ORDER BY created_at 直接在索引内完成（覆盖索引，零回表）。
-- ========================================
CREATE INDEX IF NOT EXISTS idx_request_logs_trace_id_created_at ON request_logs(trace_id, created_at);

-- ========================================
-- VERSION:v1.37.0
-- 熔断探测信息展示：circuit_breaker_states 新增最近一次探测结果字段（供入口模型关联页气泡展示）。
--   last_probe_at：最近一次探测时间。熔断记录不会自动过期，探测失败会续期并写入；
--                  探测成功时记录即被删除，因此存续记录上的探测结果通常为失败信息。
--   last_probe_status：探测响应的 HTTP 状态码（连接失败/超时等无响应时为 NULL）。
--   last_probe_detail：探测失败详情（响应体摘要或网络错误信息，原样展示）。
-- ========================================
ALTER TABLE circuit_breaker_states ADD COLUMN last_probe_at TIMESTAMP;
ALTER TABLE circuit_breaker_states ADD COLUMN last_probe_status INTEGER;
ALTER TABLE circuit_breaker_states ADD COLUMN last_probe_detail TEXT;

-- ========================================
-- VERSION:v1.38.0
-- 性能优化：新增 log_stats_hourly 预聚合表，用于 Dashboard 小时级统计查询，
-- 避免每次仪表盘加载都对 request_logs 执行 6~8 条全表范围聚合扫描。
-- ========================================
CREATE TABLE IF NOT EXISTS log_stats_hourly (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    hour_bucket TEXT NOT NULL,                -- 聚合小时桶 "yyyy-MM-ddTHH:00:00"（UTC）
    model_name TEXT NOT NULL DEFAULT '',
    channel_name TEXT NOT NULL DEFAULT '',
    channel_model_name TEXT NOT NULL DEFAULT '',
    phase TEXT NOT NULL DEFAULT '',           -- 统计阶段：'start' / 'success'
    requests INTEGER NOT NULL DEFAULT 0,      -- 该桶该维度的 trace_id 去重计数
    first_byte_ms_sum REAL NOT NULL DEFAULT 0, -- first_byte_ms 总和（用于重新计算均值）
    first_byte_ms_count INTEGER NOT NULL DEFAULT 0, -- first_byte_ms 非空计数
    response_time_ms_sum REAL NOT NULL DEFAULT 0,  -- response_time_ms 总和（用于计算生成速度）
    completion_tokens_sum INTEGER NOT NULL DEFAULT 0, -- completion_tokens 总和
    total_tokens_sum INTEGER NOT NULL DEFAULT 0,      -- total_tokens 总和
    UNIQUE(hour_bucket, model_name, channel_name, channel_model_name, phase)
);

CREATE INDEX IF NOT EXISTS idx_log_stats_hourly_bucket ON log_stats_hourly(hour_bucket);

-- ========================================
-- VERSION:v1.39.0
-- 系统配置：原始请求数据保存级别新增 none（从不保存原始请求头/请求体）
-- v1.26.0 的种子行使用 INSERT OR IGNORE，已迁移的库不会重跑，故此处显式刷新描述文案
-- ========================================

UPDATE admin_config SET description = '原始请求数据保存级别：info=全部保存，warn=重试/错误时保存，error=仅失败时保存，none=从不保存' WHERE config_key = 'request_data_save_level';

-- ========================================
-- VERSION:v1.40.0
-- 性能优化：「使用历史」图表聚合查询改造 + 清理无用的 log_stats_hourly 预聚合表
--
-- 背景：该图表原先的谓词写作 datetime(created_at) >= datetime(?)，函数包裹列导致
--   SQLite 无法使用 created_at 索引，每次请求都退化为全表扫描（实测 600 万行下单月
--   视图约 6.6 秒），而前端每 15 秒静默轮询一次、翻月/切筛选又各触发一次。
--   查询已改为裸列范围比较（sargable），并新增下面的部分覆盖索引消除回表。
--
-- 索引设计：列顺序 created_at -> model_name -> phase -> total_tokens -> trace_id 使范围
--   扫描 + GROUP BY DATE(created_at), model_name + 聚合所需的列全部落在索引内，
--   产生 COVERING INDEX 计划，彻底消除回表（这是数据量大时的主要开销来源）。
--
--   部分索引条件必须是查询 WHERE 能"蕴含"的谓词，否则 SQLite 不会使用该索引。
--   这里刻意选 model_name IS NOT NULL AND model_name != ''（查询本身就有这一条），
--   而 NOT 选 phase='success' —— 虽然聚合只看 success 行，但 phase 仅出现在 CASE
--   内部而不在 WHERE 中，带 phase 条件的部分索引对本查询完全不可用；若把
--   phase='success' 提到 WHERE 里又会改变结果语义（只失败过的模型当前会产出一行
--   0 token / 0 请求，属刻意保留的 Java 对齐行为，不能被过滤掉）。
--
-- 注意：索引仅服务于入口模型查询。渠道模型模式通过 trace 子查询聚合，
--   仅靠谓词改造即可获得约 2 倍提升，额外加索引会显著加重写入放大，故不添加。
--
-- 清理：log_stats_hourly 由 v1.38.0 引入，曾计划供 Dashboard 小时级统计使用，
--   但代码库中从无任何查询读取它（仅后台任务每小时写入），属于无效预聚合，
--   在此移除该表与对应后台任务，省掉每小时一次的全范围聚合扫描。
-- ========================================

DROP TABLE IF EXISTS log_stats_hourly;

CREATE INDEX IF NOT EXISTS idx_usage_entry
    ON request_logs(created_at, model_name, phase, total_tokens, trace_id)
    WHERE model_name IS NOT NULL AND model_name != '';

-- 刷新查询规划器统计信息。缺省情况下从未执行过 ANALYZE，sqlite_stat1 不存在，
-- 规划器只能按经验猜测索引代价，在数据量增长后容易误选索引或退化为全表扫描。
ANALYZE;