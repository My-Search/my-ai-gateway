# My AI Gateway（MAG）

个人统一 AI 网关，提供多渠道聚合、模型路由、熔断保护与 API Key 管理能力。对外暴露 OpenAI / Anthropic 兼容接口，将多个 AI 供应商统一为单一入口。

## 功能特性

- **渠道管理** — 配置多个 AI 供应商（OpenAI、Anthropic 等），每个渠道支持多个 API Key
- **模型路由** — 自定义对外模型名称，关联多个渠道模型，支持 failover 等策略
- **熔断保护** — 渠道级 / 模型级两层熔断，自动隔离故障渠道
- **API Key 管理** — 网关层面的密钥管控，控制访问权限
- **请求日志** — 全链路请求追踪，支持重试链路缩进展示
- **模型测试** — Playground 在线调试模型响应
- **管理后台** — Web 控制台，渠道 / 模型 / 密钥一站式管理

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 17、Spring Boot 3.2、MyBatis-Plus |
| 数据库 | SQLite（零配置，文件级存储） |
| 前端 | Vue 3、Vite 5、Pinia、TypeScript |
| 部署 | Docker Compose（Nginx 统一入口） |

## 项目结构

```
my-ai-api/
├── src/                        # 后端源码
│   └── main/java/com/myai/gateway/
│       ├── controller/         # REST API
│       │   ├── api/            # /v1 (OpenAI/Anthropic 兼容)、/admin/api (管理后台)
│       ├── service/            # 业务逻辑
│       ├── relay/              # AI 接口转发与熔断
│       ├── entity/             # 数据实体
│       ├── mapper/             # MyBatis-Plus Mapper
│       └── config/             # 配置类
├── frontend/                   # 前端源码
│   ├── src/
│   │   ├── api/                # HTTP 请求封装
│   │   ├── views/              # 页面组件
│   │   ├── components/         # 公共组件
│   │   ├── router/             # 路由
│   │   └── stores/             # Pinia 状态管理
│   ├── nginx.conf              # 生产 Nginx 配置
│   └── Dockerfile              # 前端容器构建
├── Dockerfile                  # 后端容器构建
├── docker-compose.yml          # 服务编排
└── .env.compose.example        # 环境变量示例
```

## 本地开发

### 前置要求

- JDK 17+
- Maven 3.9+
- Node.js 20+

### 1. 启动后端

```bash
# 克隆项目后进入目录
cd my-ai-api

# 编译并启动（首次会自动下载依赖）
mvn spring-boot:run
```

后端运行在 `http://localhost:1399`，SQLite 数据库文件自动创建在 `data/gateway.db`。

### 2. 启动前端

```bash
cd frontend

# 安装依赖
npm install

# 启动开发服务器
npm run dev
```

前端运行在 `http://localhost:3990`，已配置 `/admin/api` 代理到后端 1399 端口。

### 3. 首次使用

1. 浏览器访问 `http://localhost:3990`
2. 首次进入会提示创建管理员账号
3. 登录后在「渠道管理」添加 AI 供应商渠道并配置 API Key
4. 在「模型管理」创建对外模型并关联渠道模型
5. 即可通过 `/v1/chat/completions` 等接口调用

## Docker Compose 部署

### 前置要求

- Docker 24+
- Docker Compose v2+

### 1. 准备环境变量

```bash
cp .env.compose.example .env.compose
```

默认配置即可，按需修改 `FRONTEND_PORT`。

### 2. 构建并启动

```bash
docker compose up -d --build
```

### 3. 访问

- 管理后台：`http://localhost:3990`
- AI 接口：`http://localhost:3990/v1/chat/completions`

所有请求通过 Nginx 统一入口（端口 3990），后端不暴露端口。

| 路径 | 用途 |
|------|------|
| `/admin/api/*` | 管理后台 API |
| `/v1/*` | OpenAI / Anthropic 兼容 AI 接口 |
| `/*` | 前端 SPA 页面 |

### 4. 停止

```bash
docker compose down
```

### 数据持久化

SQLite 数据库文件挂载在 `./data/gateway.db`，容器重建不会丢失数据。

### 常用命令

```bash
# 查看日志
docker compose logs -f

# 仅重建后端
docker compose up -d --build my-ai-gateway

# 仅重建前端
docker compose up -d --build my-ai-frontend
```

## API 使用示例

```bash
# 兼容 OpenAI 格式
curl http://localhost:3990/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -d '{
    "model": "your-model-name",
    "messages": [{"role": "user", "content": "Hello"}]
  }'
```
