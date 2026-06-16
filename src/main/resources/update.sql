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
