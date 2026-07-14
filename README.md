# My AI Gateway（MAG）
 
<img width="2560" height="1187" alt="image" src="https://github.com/user-attachments/assets/c80b1224-b8b7-496b-bf6f-ef8083903cf1" />

个人统一 AI 网关，提供多渠道聚合、模型路由、负载均衡、熔断保护与 API Key 管理能力。对外暴露 OpenAI / Anthropic 兼容接口，将多个 AI 供应商统一为单一入口。

## 功能特性

- **渠道管理** — 配置多个 AI 供应商（OpenAI、Anthropic 等），每个渠道支持多个 API Key，支持拖拽排序
- **模型路由** — 自定义对外模型名称，关联多个渠道模型，支持 failover / random / round-robin 负载均衡策略
- **熔断保护** — 渠道级（按 API Key）+ 模型级两层熔断，自动隔离故障渠道，支持可配置重试次数与熔断时长
- **多模态规则** — 通过正则表达式匹配模型名称，自动标记模型支持的输入类型（text / image / audio / video），支持视频与音频内容的路由判断
- **模型继承** — 入口模型可继承另一个入口模型的渠道关联关系，减少重复配置
- **API Key 管理** — 网关层面的密钥管控，支持启用/禁用、分享链接生成与回收
- **请求日志** — 全链路请求追踪，支持重试链路缩进展示、SSE 实时推送、原始请求数据按需加载与 JSON 文件下载
- **聊天界面** — 内置聊天对话界面，支持图片上传与多模态输入展示
- **模型测试** — Playground 在线调试模型响应，支持流式输出与多模态输入
- **仪表盘** — 请求量、耗时、Token 消耗等统计图表，支持月度统计与排行榜周期筛选
- **管理后台** — Web 控制台，渠道/模型/密钥/多模态规则/系统配置一站式管理
- **分享功能** — 通过分享链接将 API Key 能力共享给他人
- **系统配置** — 日志保留天数、自动清理开关、原始请求数据 TTL 等可配置
- **国际化** — 支持简体中文与英文界面

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 17、Spring Boot 3.2、MyBatis-Plus 3.5 |
| 数据库 | SQLite（零配置，文件级存储，支持 WAL 模式） |
| 前端 | Vue 3、Vite 5、Pinia、TypeScript、Vue Router 4 |
| 部署 | Docker Compose（Nginx 统一入口） |

## 项目结构

```
my-ai-api/
├── src/                              # 后端源码
│   └── main/java/com/myai/gateway/
│       ├── MyAiGatewayApplication.java
│       ├── config/                   # 配置类（鉴权、JWT、MyBatis、Jackson、异步、时区等）
│       ├── controller/               # REST API
│       │   └── api/                  # /v1 (OpenAI/Anthropic 兼容)、管理后台、Share API
│       ├── entity/                   # 数据实体（13 个实体：渠道、模型、密钥、日志、熔断、多模态规则等）
│       ├── mapper/                   # MyBatis-Plus Mapper
│       ├── relay/                    # AI 接口转发核心
│       │   ├── balancer/             # 负载均衡（Failover / Random / RoundRobin）
│       │   ├── circuit/              # 熔断状态与配置管理
│       │   ├── transformer/          # 协议转换（OpenAI ↔ Anthropic 格式互转）
│       │   │   ├── protocol/         # 协议翻译器
│       │   │   └── registry/         # 翻译器注册与状态管理
│       │   ├── stream/               # SSE 流式响应管理
│       │   ├── LatencyTracker.java   # 延迟追踪
│       │   └── StreamContentManager.java # 流式内容拼接管理
│       ├── service/                  # 业务逻辑层（渠道、模型、密钥、日志、熔断、统计、多模态规则等）
│       ├── schedule/                 # 定时任务（日志清理）
│       └── dto/                      # 数据传输对象
├── src/test/                         # 单元测试
│   └── java/com/myai/gateway/
│       ├── config/
│       ├── controller/api/
│       ├── relay/
│       │   ├── balancer/
│       │   ├── LatencyTrackerTest.java
│       │   ├── RelayServiceTest.java
│       │   └── StreamContentManagerTest.java
│       └── service/
├── frontend/                         # 前端源码（Vue 3 + Vite 5）
│   ├── src/
│   │   ├── api/                      # HTTP 请求封装（12 个模块：渠道、模型、密钥、日志、认证、仪表盘、聊天、分享、多模态、系统、上传等）
│   │   ├── views/                    # 页面组件（Dashboard、渠道、模型、密钥、日志、Playground、设置、分享）
│   │   ├── components/               # 公共组件
│   │   │   ├── channel/              # 渠道相关组件
│   │   │   ├── chat/                 # 聊天对话组件
│   │   │   ├── common/               # 通用组件（对话框、搜索选择、JSON 查看器等）
│   │   │   └── layout/               # 布局组件
│   │   ├── composables/              # 组合式函数（认证、对话框、国际化）
│   │   ├── router/                   # 路由配置
│   │   ├── stores/                   # Pinia 状态管理（Loading、语言、主题）
│   │   ├── locales/                  # 国际化（zh-CN / en-US）
│   │   ├── assets/
│   │   │   ├── icons/                # SVG 图标库
│   │   │   └── styles/               # 全局样式
│   │   ├── types/                    # TypeScript 类型定义
│   │   └── utils/                    # 工具函数
│   ├── nginx.conf                    # 生产 Nginx 配置
│   └── Dockerfile                    # 前端容器构建
├── data/                             # SQLite 数据文件（运行时自动创建）
├── .agents/                          # Agent 工作区
├── pom.xml                           # Maven 项目配置
├── Dockerfile                        # 后端容器构建（多阶段构建）
├── docker-compose.yml                # 服务编排
└── .env.compose.example              # 环境变量示例
```

## 本地开发

### 前置要求

- JDK 17+
- Maven 3.9+
- Node.js 20+

### 1. 启动后端

```bash
cd my-ai-api
mvn spring-boot:run
```

后端运行在 `http://localhost:1399`，SQLite 数据库文件自动创建在 `data/gateway.db`。

### 2. 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端运行在 `http://localhost:3990`，已配置代理转发到后端 1399 端口。

### 3. 首次使用

1. 浏览器访问 `http://localhost:3990`
2. 首次进入会提示创建管理员账号
3. 登录后在「渠道管理」添加 AI 供应商渠道并配置 API Key
4. 在「模型管理」创建对外模型并关联渠道模型，设置路由策略
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
| `/api/share/*` | 分享接口 |
| `/uploads/*` | 文件上传 |
| `/*` | 前端 SPA 页面 |

### 4. 停止

```bash
docker compose down
```

### 数据持久化

SQLite 数据库文件挂载在 `./data/gateway.db`，容器重建不会丢失数据。

### 常用命令

```bash
docker compose logs -f          # 查看日志
docker compose up -d --build my-ai-gateway   # 仅重建后端
docker compose up -d --build my-ai-frontend  # 仅重建前端
```

## API 使用示例

### OpenAI 兼容格式

```bash
curl http://localhost:3990/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -d '{
    "model": "your-model-name",
    "messages": [{"role": "user", "content": "Hello"}]
  }'
```

### Anthropic 兼容格式

```bash
curl http://localhost:3990/v1/messages \
  -H "Content-Type: application/json" \
  -H "x-api-key: YOUR_API_KEY" \
  -H "anthropic-version: 2023-06-01" \
  -d '{
    "model": "your-model-name",
    "max_tokens": 1024,
    "messages": [{"role": "user", "content": "Hello"}]
  }'
```

## 数据库迁移

数据库迁移脚本位于 `src/main/resources/update.sql`，采用版本化增量迁移方式（v1.0.0 ~ v1.21.0）。
应用启动时 `DatabaseMigrationRunner` 会自动检测并执行未应用的迁移。

## 单元测试

```bash
mvn test
```

测试覆盖核心业务逻辑：负载均衡、熔断服务、模型路由、请求日志、统计服务、协议转换、流内容管理、延迟追踪、权限拦截等，不依赖外部服务。

## 许可证

个人项目，仅供学习参考。
