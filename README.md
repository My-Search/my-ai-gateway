# My AI Gateway（MAG）

**English** | [中文文档](README.zh-CN.md)

<img width="2560" height="1187" alt="image" src="https://github.com/user-attachments/assets/9481f804-dc09-4d27-a3e8-e57e828c9f8e" />

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
| Deployment | Docker Compose (Go backend as unified entry) |

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
│   └── Dockerfile                    # Legacy standalone frontend container (not used by Compose)
├── data/                             # SQLite data file (auto-created at runtime)
├── .agents/                          # Agent workspace
├── pom.xml                           # Maven project configuration
├── Dockerfile                        # Combined frontend/backend image (multi-stage)
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

The single-file console is embedded in the backend binary, so no front-end build is needed:

- `http://localhost:1399/` or `http://localhost:1399/console`
- `/` prefers the Vue bundle only when `MAG_STATIC_DIR` is set and contains `index.html`; the embedded console then stays reachable at `/console`.

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

The default configuration works as-is; modify `FRONTEND_PORT` to change the public port.

### 2. Build and Start

```bash
docker compose up -d --build
```

When upgrading from the previous two-container deployment, remove the obsolete
frontend service first so it cannot keep occupying `FRONTEND_PORT`:

```bash
docker compose down --remove-orphans
docker rm -f my-ai-frontend 2>/dev/null || true
docker compose up -d --build
```

The second command handles an old container whose Compose project metadata no
longer matches the current checkout. `update.sh` performs this cleanup
automatically.

### 3. Access

- Admin Console: `http://localhost:3990`
- AI API: `http://localhost:3990/v1/chat/completions`

The Vue frontend is built in the multi-stage image and served directly by the Go backend.
There is no separate Nginx frontend container. The public entry remains the existing
`FRONTEND_PORT` (3990 by default), and all requests share that single port.

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

### Database Maintenance (Disk Space Is Reclaimed Automatically)

SQLite never returns freed pages to the OS unless the database is built in an auto-vacuum mode. The gateway now
switches the database to `auto_vacuum=INCREMENTAL` on startup: the first start after upgrading performs a one-time
`VACUUM` that both enables the mode and returns every page stranded by past log cleanups to the OS. From then on the
30-minute log cleanup releases the pages it frees via `PRAGMA incremental_vacuum`, so the file no longer grows
unbounded. No manual action is required.

If the available disk space is smaller than the current database file, the one-time conversion is skipped with a
warning (a `VACUUM` needs temporary space roughly equal to the file). Free some space and restart, or run it manually
while the backend is stopped:

```bash
docker compose down
sqlite3 data/gateway.db "PRAGMA auto_vacuum=INCREMENTAL; VACUUM;"
docker compose up -d
```

The startup conversion is a disk-space optimization; it is idempotent and skipped once the database is already in
incremental mode. Back up `data/gateway.db` first as a precaution.

### Common Commands

```bash
docker compose logs -f          # View logs
docker compose up -d --build    # Rebuild the combined image
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

### Embeddings

`POST /v1/embeddings` follows the OpenAI Embeddings API. Create an entry model
whose name maps to your upstream embedding model (e.g. `text-embedding-3-small`),
then call it as usual — the gateway routes to the upstream `{base_url}/embeddings`
and returns the vectors unchanged (`model` is rewritten back to the entry model).

```bash
curl http://localhost:3990/v1/embeddings \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -d '{
    "model": "your-embedding-model",
    "input": "The quick brown fox"
  }'
```

`input` accepts a string, an array of strings, or token arrays, exactly like the
upstream API. Embeddings are non-streaming. To keep an embedding-only model out
of `GET /v1/models`, mark it as hidden in the admin console.

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
