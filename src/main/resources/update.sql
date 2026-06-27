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
