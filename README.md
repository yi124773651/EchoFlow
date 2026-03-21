# EchoFlow

English | [中文](README.zh-CN.md)

An AI Agent orchestration platform for complex async task execution. Submit a natural language task, and the system automatically decomposes it into a multi-step plan (THINK → RESEARCH → WRITE → NOTIFY), executes each step via autonomous AI agents, and streams real-time progress to a modern web UI.

## Highlights

- **StateGraph Dynamic Orchestration** — Three graph topologies: linear chain, conditional parallel routing (THINK output drives RESEARCH fan-out/skip), and WRITE review loop (LLM-as-Judge scoring → auto-revision, up to 3 rounds)
- **Dual-Layer Execution with Auto-Degradation** — Primary path: ReAct Agents with tool calling (GitHub search, webhooks). Fallback path: direct LLM execution. 10 executor classes across 4 step types, transparent failover via `StepExecutorRouter`
- **Multi-Model Per-Step Routing** — Configuration-driven StepType → Model mapping. Supports OpenAI-compatible + DashScope (via Spring AI Alibaba) dual channels with cross-model fallback
- **Human-in-the-Loop Approval** — WRITE steps pause in `WAITING_APPROVAL` state. Virtual Thread + CompletableFuture for non-blocking wait. Approve/reject via UI, configurable timeout auto-approval
- **SSE Real-Time Streaming** — 10+ event types (sealed interface). Three-layer reliability: event buffering + replay, SSE-first connection strategy, REST snapshot reconciliation
- **Persistent Checkpoints & Startup Recovery** — Custom `JpaCheckpointSaver` stores StateGraph checkpoints in PostgreSQL (JSONB). On restart: orphaned executions fail-safe, WAITING_APPROVAL executions resume automatically

## Architecture

```
echoflow/
├── echoflow-backend/
│   ├── echoflow-domain/           Pure Java, zero framework dependencies
│   ├── echoflow-application/      Use cases, ports, transaction boundaries
│   ├── echoflow-infrastructure/   JPA, AI clients, StateGraph, adapters
│   └── echoflow-web/              Controllers, SSE publisher, Flyway, prompts
└── echoflow-frontend/             Next.js App Router, SSE consumption
```

**Strict dependency direction**: `web → application → domain` ← `infrastructure`

Enforced at compile time via Maven module boundaries. Domain has zero Spring/JPA/AI SDK imports.

### Domain Model

Two aggregate roots:

- **Task** — User intent. States: `SUBMITTED → EXECUTING → COMPLETED | FAILED`
- **Execution** — One run of a task, containing ordered `ExecutionStep` entities with append-only `StepLog` value objects. Step types: `THINK`, `RESEARCH`, `WRITE`, `NOTIFY`

### Key Patterns

| Pattern | Implementation |
|---------|---------------|
| Port/Adapter | Application defines `TaskPlannerPort`, `StepExecutorPort`, `GraphOrchestrationPort`; Infrastructure implements |
| Transaction Isolation | LLM calls execute outside transactions: Read TX → AI Call (no TX) → Write TX |
| Event-Driven SSE | `SseExecutionEventPublisher` → frontend `useExecutionStream` hook. Polling is forbidden |
| Conditional Routing | THINK prompt returns `[ROUTING]` hint → `RoutingHintParser` → GraphOrchestrator builds conditional edges |
| Review Loop | WRITE → `WriteReviewGateAction` (LLM scoring) → `WriteReviseAction` (revision) → backward edge. Max-attempts force approval |
| Optimistic Locking | Version columns on Task and Execution aggregates |

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 21 (Virtual Threads, Records, Sealed Types, Pattern Matching) |
| Framework | Spring Boot 3.5, Spring MVC, Spring Data JPA |
| AI | Spring AI 1.1, Spring AI Alibaba 1.1 (StateGraph, ReAct Agent) |
| Database | PostgreSQL 16+, pgvector, Flyway migrations |
| Frontend | Next.js 16, React 19, TypeScript (strict), Tailwind CSS v4, ShadcnUI |
| Testing | JUnit 5, Mockito, AssertJ, Testcontainers |
| Build | Maven 3.9+ (wrapper included) |

## Quick Start

### Prerequisites

- Java 21+
- Node.js 22+
- PostgreSQL 16+ with `pgvector` extension
- An OpenAI-compatible API key (or DashScope)

### Environment Setup

```bash
cp .env.example .env
# Edit .env with your database and AI provider credentials:
# DB_URL, DB_USERNAME, DB_PASSWORD, AI_BASE_URL, AI_API_KEY, AI_MODEL
```

### Build & Run

```bash
# Full build (backend + frontend)
./mvnw clean install

# Start backend
source .env && ./mvnw spring-boot:run -pl echoflow-backend/echoflow-web

# Start frontend dev server (in another terminal)
cd echoflow-frontend && npm run dev
```

Backend runs on `localhost:8080`, frontend on `localhost:3000`.

### Run Tests

```bash
# All backend tests
./mvnw test -pl echoflow-backend -am

# Single test class
./mvnw test -pl echoflow-backend/echoflow-domain -Dtest=TaskTest
```

## Project Metrics

| Metric | Count |
|--------|-------|
| Backend Java classes | 120+ |
| Backend test methods | 230+ |
| Flyway migrations | 8 |
| Step executor implementations | 10 (5 ReAct + 5 LLM fallback) |
| Graph topologies | 3 (linear, conditional parallel, review loop) |
| SSE event types | 10+ |
| Development logs | 16 entries documenting every architectural decision |

## Engineering Practices

- **TDD**: Red → Green → Refactor. Every behavior change starts with a test
- **Testcontainers**: Integration tests run against real PostgreSQL containers
- **Flyway-only migrations**: `spring.jpa.hibernate.ddl-auto=validate`, all schema changes via versioned scripts
- **Development logs**: 16 devlog entries in `docs/devlog/` recording architectural decisions, trade-offs, and implementation details for each phase

## License

[MIT](LICENSE)
