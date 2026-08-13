# My AI Gateway（MAG）

**English** | [中文文档](README.zh-CN.md)

<img width="2560" height="1187" alt="image" src="https://github.com/user-attachments/assets/a98a9c66-c604-4de8-bed2-4d2047a9c238" />

A personal unified AI gateway that provides multi-channel aggregation, model routing, load balancing, circuit breaking, and API Key management. It exposes OpenAI / Anthropic compatible interfaces, unifying multiple AI providers behind a single entry point.

## Features

- **Channel Management** — Configure multiple AI providers (OpenAI, Anthropic, etc.), each channel supports multiple API Keys with drag-and-drop sorting
- **Model Routing** — Define custom public model names, associate multiple channel models, with failover / random / round-robin load balancing strategies
- **Circuit Breaking** — Two-level circuit breaking at channel level (per API Key) and model level, automatically isolating faulty channels, with configurable retry counts and breaker durations, plus breaker status visualization and manual recovery
- **Multimodal Rules** — Match model names via regular expressions, automatically mark supported input types (text / image / audio / video), supporting routing decisions for video and audio content
- **Model Inheritance** — Entry models can inherit channel associations from another entry model, reducing duplicate configuration
- **Prompt Injection** — Define per-model prompt injection rules (role, position, content, priority), automatically injected during request preprocessing
- **API Key Management** — Gateway-level key control with enable/disable, share link generation and revocation
- **Request Logs** — End-to-end request tracing with retry chain indentation, SSE real-time push, on-demand raw request payload loading and JSON file download
- **Chat Interface** — Built-in chat UI with image upload and multimodal input display, supporting direct connection via OpenAI / Anthropic protocols and multi-turn thinking block transmission, supporting direct connection via OpenAI / Anthropic protocols and multi-turn thinking block transmission
- **Model Testing** — Playground for online debugging of model responses, supporting streaming output, multimodal input, first-byte response time and accurate token statistics
- **Dashboard** — Statistics charts for request volume, latency, token consumption, etc., with monthly statistics and leaderboard period filtering
- **Admin Console** — Web console for one-stop management of channels, models, keys, multimodal rules, and system configuration
- **Sharing** — Share API Key capabilities with others via share links
- **System Configuration** — Configurable log retention days, auto-cleanup switch, raw request data TTL, etc.
- **Internationalization** — Supports Simplified Chinese and English UI

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 17, Spring Boot 3.2, MyBatis-Plus 3.5 |
| Database | SQLite (zero-config, file-based storage with WAL mode) |
| Frontend | Vue 3, Vite 5, Pinia, TypeScript, Vue Router 4 |
| Deployment | Docker Compose (Nginx as unified entry) |

## Project Structure

```
my-ai-api/
├── src/                              # Backend source code
│   └── main/java/com/myai/gateway/
│       ├── MyAiGatewayApplication.java
│       ├── config/                   # Config classes (auth, JWT, MyBatis, Jackson, async, timezone, etc.)
│       ├── controller/               # REST API
│       │   └── api/                  # /v1 (OpenAI/Anthropic compatible), Admin, Share API
│       ├── entity/                   # Data entities (12 entities: channels, models, keys, logs, circuits, multimodal rules, prompt injections, etc.)
│       ├── mapper/                   # MyBatis-Plus Mappers
│       ├── relay/                    # AI relay core
│       │   ├── balancer/             # Load balancing (Failover / Random / RoundRobin)
│       │   ├── circuit/              # Circuit breaker state & configuration management
│       │   ├── transformer/          # Protocol translation (OpenAI ↔ Anthropic)
│       │   │   ├── protocol/         # Protocol translators
│       │   │   └── registry/         # Translator registration & state management
│       │   ├── stream/               # SSE streaming response management
│       │   ├── LatencyTracker.java   # Latency tracking
│       │   └── StreamContentManager.java # Streaming content assembly
│       ├── service/                  # Business logic layer (channels, models, keys, logs, circuits, stats, multimodal rules, etc.)
│       ├── schedule/                 # Scheduled tasks (log cleanup)
│       └── dto/                      # Data transfer objects
├── src/test/                         # Unit tests
│   └── java/com/myai/gateway/
│       ├── config/
│       ├── controller/api/
│       ├── relay/
│       │   ├── balancer/
│       │   ├── LatencyTrackerTest.java
│       │   ├── RelayServiceTest.java
│       │   └── StreamContentManagerTest.java
│       └── service/
├── frontend/                         # Frontend source code (Vue 3 + Vite 5)
│   ├── src/
│   │   ├── api/                      # HTTP request wrappers (12 modules: channel, model, key, log, auth, dashboard, chat, share, multimodal, prompt injection, system, upload)
│   │   ├── views/                    # Page components (Dashboard, Channel, Model, Key, Log, Playground, Settings, Share)
│   │   ├── components/               # Shared components
│   │   │   ├── channel/              # Channel-related components
│   │   │   ├── chat/                 # Chat components
│   │   │   ├── common/               # Generic components (dialogs, search selects, JSON viewers, etc.)
│   │   │   └── layout/               # Layout components
│   │   ├── composables/              # Composable functions (auth, dialogs, i18n)
│   │   ├── router/                   # Route configuration
│   │   ├── stores/                   # Pinia state management (Loading, Locale, Theme)
│   │   ├── locales/                  # i18n (zh-CN / en-US)
│   │   ├── assets/
│   │   │   ├── icons/                # SVG icon library
│   │   │   └── styles/               # Global styles
│   │   ├── types/                    # TypeScript type definitions
│   │   └── utils/                    # Utility functions
│   ├── nginx.conf                    # Production Nginx configuration
│   └── Dockerfile                    # Frontend container build
├── data/                             # SQLite data file (auto-created at runtime)
├── .agents/                          # Agent workspace
├── pom.xml                           # Maven project configuration
├── Dockerfile                        # Backend container build (multi-stage)
├── docker-compose.yml                # Service orchestration
└── .env.compose.example              # Environment variable example
```

## Local Development

### Prerequisites

- JDK 17+
- Maven 3.9+
- Node.js 20+

### 1. Start the Backend

```bash
cd my-ai-api
mvn spring-boot:run
```

The backend runs at `http://localhost:1399`; the SQLite database file is auto-created at `data/gateway.db`.

### 2. Start the Frontend

```bash
cd frontend
npm install
npm run dev
```

The frontend runs at `http://localhost:3990` with a proxy configured to forward requests to the backend on port 1399.

### 3. First-Time Usage

1. Open `http://localhost:3990` in your browser
2. You will be prompted to create an admin account on first visit
3. After login, add an AI provider channel in **Channel Management** and configure API Keys
4. Create a public model in **Model Management**, associate channel models, and set routing strategy
5. You can then call endpoints such as `/v1/chat/completions`

## Docker Compose Deployment

### Prerequisites

- Docker 24+
- Docker Compose v2+

### 1. Prepare Environment Variables

```bash
cp .env.compose.example .env.compose
```

The default configuration works as-is; modify `FRONTEND_PORT` as needed.

### 2. Build and Start

```bash
docker compose up -d --build
```

### 3. Access

- Admin Console: `http://localhost:3990`
- AI API: `http://localhost:3990/v1/chat/completions`

All requests go through the Nginx unified entry (port 3990); the backend ports are not exposed.

| Path | Purpose |
|------|---------|
| `/admin/api/*` | Admin API |
| `/v1/*` | OpenAI / Anthropic compatible AI API |
| `/api/share/*` | Share API |
| `/uploads/*` | File uploads |
| `/*` | Frontend SPA pages |

### 4. Stop

```bash
docker compose down
```

### Data Persistence

The SQLite database file is mounted at `./data/gateway.db`; container rebuilds will not lose data.

### Common Commands

```bash
docker compose logs -f          # View logs
docker compose up -d --build my-ai-gateway   # Rebuild backend only
docker compose up -d --build my-ai-frontend  # Rebuild frontend only
```

## API Usage Examples

### OpenAI Compatible Format

```bash
curl http://localhost:3990/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -d '{
    "model": "your-model-name",
    "messages": [{"role": "user", "content": "Hello"}]
  }'
```

### Anthropic Compatible Format

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

## Database Migration

Migration scripts live in `src/main/resources/update.sql`, using versioned incremental migrations (v1.0.0 ~ v1.27.0).
On startup, `DatabaseMigrationRunner` automatically detects and applies pending migrations.

## Unit Tests

```bash
mvn test
```

Tests cover core business logic: load balancing, circuit breaking, model routing, request logs, stats service, protocol translation, streaming content management, latency tracking, permission interception, etc., with no external service dependencies.

## License

A personal project, for learning and reference only.
