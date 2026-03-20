# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Hard constraints in this file override all subfiles.

## 0. Build & Dev Commands

### Backend (Maven, from project root)

```bash
# Full build (all modules, includes frontend via frontend-maven-plugin)
./mvnw clean install

# Backend only — skip frontend build
./mvnw clean install -pl echoflow-backend -am

# Run all backend tests
./mvnw test -pl echoflow-backend -am

# Run a single test class
./mvnw test -pl echoflow-backend/echoflow-domain -Dtest=TaskTest

# Run a single test method
./mvnw test -pl echoflow-backend/echoflow-domain -Dtest="TaskTest#should_transition_to_executing"

# Start the Spring Boot backend (requires env vars — see below)
source .env && ./mvnw spring-boot:run -pl echoflow-backend/echoflow-web
```

### Frontend (npm, from `echoflow-frontend/`)

```bash
npm run dev      # Dev server on localhost:3000
npm run build    # Production build
npm run lint     # ESLint
```

Frontend expects `BACKEND_URL` (server-side) and `NEXT_PUBLIC_API_BASE` (client-side) — see `echoflow-frontend/.env.example`.

### Environment

Backend reads env vars: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `AI_BASE_URL`, `AI_API_KEY`, `AI_MODEL`. A root `.env` file exists for local development — `source .env` before running backend.

## 1. Communication
- 默认使用中文沟通。
- 代码、类名、方法名、接口、表名使用英文。
- 不清楚就提问；禁止编造业务规则、字段、状态、外部接口。

## 2. Priority
Follow this order:
1. Protect DDD boundaries and domain purity
2. Keep code testable and follow TDD
3. Minimize change scope
4. Ensure security, auditability, and resilience
5. Apply Java 21 / Next.js best practices
6. Optimize performance only with evidence

## 3. Fixed Stack
- Backend: Java 21, Spring Boot 3.5+, Spring MVC, Virtual Threads
- AI: Spring AI 1.1+ (OpenAI-compatible + DashScope via Spring AI Alibaba)
- Build: Maven 3.9+ (wrapper included: `./mvnw`)
- DB: PostgreSQL 16+, pgvector, Flyway
- Persistence: Spring Data JPA by default
- Frontend: Next.js 16+, React 19+, TypeScript, Tailwind v4, ShadcnUI
- Testing: JUnit 5, Mockito, AssertJ, Spring Boot Test, Testcontainers

## 4. Architecture

### Module Structure

```
echoflow/                          (root aggregator pom)
├── echoflow-backend/              (backend aggregator pom)
│   ├── echoflow-domain/           (pure Java, zero framework deps)
│   ├── echoflow-application/      (use cases, ports, transactions — depends on domain only)
│   ├── echoflow-infrastructure/   (JPA, AI clients, adapters — implements ports)
│   └── echoflow-web/              (Spring Boot app, controllers, config, Flyway migrations, prompts)
└── echoflow-frontend/             (Next.js App Router, built via frontend-maven-plugin)
```

### Dependency Direction (strict, never invert)

`web → application → domain` ← `infrastructure` (implements domain ports)

- **Domain** (`com.echoflow.domain`): Aggregates (`Task`, `Execution`), entities (`ExecutionStep`), value objects (`TaskId`, `StepLog`, enums), repository interfaces. No Spring, no JPA, no HTTP.
- **Application** (`com.echoflow.application`): Use cases (`SubmitTaskUseCase`, `ExecuteTaskUseCase`, `ApproveStepUseCase`), port interfaces (`TaskPlannerPort`, `StepExecutorPort`, `GraphOrchestrationPort`, `ExecutionEventPublisher`), services (`ApprovalGateService`, `ExecutionRecoveryService`), internal listener (`ExecutionProgressListener`), commands/results as records. Owns transaction boundaries via `TransactionOperations` (programmatic) or `@Transactional` (declarative, only on methods called externally through Spring proxy).
- **Infrastructure** (`com.echoflow.infrastructure`): JPA entities + repository implementations in `persistence/`. AI integration in `ai/` organized by responsibility:
    - `ai/config/` — `ChatClientProvider` (per-provider ChatClients via Spring AI `mutate()`), `MultiModelProperties` (`@ConfigurationProperties` for StepType→model routing), `WriteReviewConfig`, `WriteReviewProperties`, `HumanApprovalProperties`.
    - `ai/executor/` — `StepExecutorRouter` (public, implements `StepExecutorPort`) dispatches to `ReactAgent*Executor` (primary) with automatic fallback to `Llm*Executor` (degradation). Includes `MessageTrimmingHook` and `ToolRetryInterceptor`.
    - `ai/graph/` — `GraphOrchestrator` (public, implements `GraphOrchestrationPort`) builds StateGraph topology: linear chain, conditional parallel routing (THINK→RESEARCH fan-out/skip), and WRITE review loop (`ReviewableWriteNodeAction` → `WriteReviewGateAction` → `WriteReviseAction`). Uses `JpaCheckpointSaver` (package-private, extends `MemorySaver`) for persistent checkpoint storage via JPA.
    - `ai/planner/` — `AiTaskPlanner` (public, implements `TaskPlannerPort`).
    - `ai/tool/` — `GitHubSearchTool`, `WebhookNotifyTool`.
  Public classes: `StepExecutorRouter`, `GraphOrchestrator`, `AiTaskPlanner`, `ChatClientProvider`, `MultiModelProperties`, `WriteReviewConfig`, `WriteReviewProperties`, `HumanApprovalProperties`. All other classes are package-private.
- **Web** (`com.echoflow.web`): Thin controllers, `GlobalExceptionHandler` (`@RestControllerAdvice` → `ProblemDetail`), `SseExecutionEventPublisher` (SSE streaming), `ClockConfig`, `ExecutionRecoveryConfig` (startup recovery trigger). Flyway migrations in `src/main/resources/db/migration/`. Prompt templates in `src/main/resources/prompts/*.st`.

### Domain Model (two aggregate roots)

- **Task** — user intent. States: `SUBMITTED → EXECUTING → COMPLETED | FAILED`.
- **Execution** — one run of a task (same Task may have future retries). Contains ordered `ExecutionStep` entities, each with append-only `StepLog` value objects. Step types: `THINK`, `RESEARCH`, `WRITE`, `NOTIFY`. Steps and execution can pause in `WAITING_APPROVAL` status for human-in-the-loop approval.

### Frontend Structure

- `app/` — Next.js App Router pages
- `features/tasks/` — business UI (task-board, task-submit-form, execution-timeline)
- `hooks/` — `use-execution-stream.ts` (SSE consumption)
- `services/` — centralized API access (`api.ts`, `task-service.ts`)
- `types/` — shared TypeScript types
- `components/ui/` — ShadcnUI base components only

### Key Patterns

- **Port/Adapter**: Application defines port interfaces; Infrastructure implements them.
- **SSE streaming**: `SseExecutionEventPublisher` → frontend `useExecutionStream` hook. Events include `executionId`, `type`, `timestamp`, `payload`. Polling is forbidden.
- **StateGraph orchestration**: `GraphOrchestrator` builds dynamic StateGraph topologies based on planned steps. Two modes: (1) linear chain for simple plans, (2) conditional parallel routing when THINK→RESEARCH pattern is detected (fan-out/skip based on `RoutingHint` embedded in THINK output). When review is enabled, WRITE steps are wrapped with a review loop (WRITE → review_gate → revise_write backward edge).
- **Dual-layer step execution**: `StepExecutorRouter` dispatches to `ReactAgentStepExecutor` (primary, with hooks/interceptors) → automatic fallback to `LlmStepExecutor` (degradation) if primary fails. Each StepType has dedicated executor subclasses in both layers.
- **Multi-model routing**: `ChatClientProvider` resolves per-StepType `ChatClient` from `MultiModelProperties` routing config. Supports per-step model selection and automatic fallback to a different model.
- **Human-in-the-loop**: When `echoflow.approval.enabled=true`, WRITE steps pause before execution via `StepProgressListener.onStepAwaitingApproval()`. The virtual thread blocks on a `CompletableFuture` in `ApprovalGateService` until the user approves/rejects via REST endpoint. Configurable timeout auto-approves on expiry.
- **Persistent checkpoints**: `JpaCheckpointSaver` (extends `MemorySaver`) persists StateGraph checkpoints to PostgreSQL via JPA (`graph_checkpoint` table, JSONB state). Each execution uses its `executionId` as the checkpoint `threadId`. Checkpoints are released (deleted) when execution completes or fails. Checkpoint persistence failures are non-fatal (log-only).
- **Startup recovery**: `ExecutionRecoveryService` runs on `ApplicationReadyEvent` via `ExecutionRecoveryConfig`. Orphaned RUNNING executions are marked FAILED. WAITING_APPROVAL executions are resumed: approval gate is re-created, SSE event re-published, and remaining steps executed via a new StateGraph on a virtual thread. Recovery uses domain-based state reconstruction (not StateGraph checkpoint resume).
- **JPA ↔ Domain mapping**: JPA entities (`TaskEntity`, `ExecutionEntity`) are separate from domain models; explicit mapping in repository implementations.

## 5. Architecture Rules
- Use DDD + Onion / Clean Architecture.
- Dependency direction must remain: `web -> application -> domain`.
- Infrastructure only implements adapters and external integrations.
- Domain must stay pure Java:
    - no Spring
    - no JPA
    - no HTTP
    - no AI SDK/provider code
- Repository interfaces belong to Domain; implementations belong to Infrastructure.
- Transaction boundaries belong to Application.
- Never keep a DB transaction open across remote I/O, AI calls, or streaming.
- PostgreSQL is the source of truth.
- Vector store is retrieval support only, never the source of truth.

## 6. Coding Rules
- Prefer `record` for DTOs, commands, queries, and immutable value objects.
- Prefer `sealed interface` / `sealed class` for bounded states/events.
- Prefer `switch` expressions and pattern matching when clearer.
- Prefer immutability; avoid unnecessary setters.
- Do not force entities into `record`.
- Do not introduce new frameworks without clear need.

## 7. TDD Rules
- Follow Red -> Green -> Refactor.
- Behavior change starts with a test.
- Bug fix starts with a failing test.
- Domain/Application tests must not require real external services.
- AI/external integrations must be mocked or stubbed in unit tests.

## 8. Backend Rules
- Controllers must stay thin.
- Application orchestrates use cases and transactions.
- Domain owns business rules and invariants.
- Infrastructure handles persistence, AI, HTTP clients, tools, storage, messaging.
- Domain exceptions must not contain HTTP details.
- Web errors must be mapped centrally using `@RestControllerAdvice` and `ProblemDetail`.

## 9. AI Rules
- Prompts must live under `src/main/resources/prompts/`.
- Tools must live in Infrastructure and be explicitly declared.
- Every agent run must have an `executionId`.
- Thought / Action / Observation / Error must be persisted in `ExecutionLog` or equivalent.
- LLM output is untrusted input and must be validated.
- Every model call must define timeout, bounded retry, and fallback/degradation.
- **MCP Tooling Strategy**:
  - **exa-mcp-server**: 优先用于深度技术调研、库文档检索及 RAG 增强。
  - **grok-search**: 优先用于获取最新的 Java/Spring 生态资讯、实时 Bug 追踪或 CVE 漏洞更新。
  - **morph-mcp**: 用于辅助分析当前项目的静态代码结构，辅助 DDD 边界检查。
  - **context7**: 用于在处理长会话或大规模重构时，检索历史决策和上下文片段。
- **MCP Execution**:
  - 所有 MCP 调用视为 `External Integration`，必须遵循 **Rule 5**（严禁在数据库事务中调用）。
  - MCP 返回的非结构化数据在进入 Domain 前必须经过验证并转换为 `record` DTO。
  - 严禁通过 MCP 直接执行未经审计的写操作（如 `rm`, `push`）。

## 10. Data Rules
- All schema changes go through Flyway. Migrations live in `echoflow-web/src/main/resources/db/migration/`.
- `spring.jpa.hibernate.ddl-auto=update` is forbidden (currently set to `validate`).
- `pgvector` extension, columns, and indexes must be created by migration scripts.
- Use optimistic locking where aggregate concurrency matters.

## 11. Frontend Rules
- Use Next.js App Router.
- RSC First: default to Server Components.
- Use `'use client'` only when truly necessary.
- TypeScript must stay strict; `any` is forbidden.
- API contracts between backend and frontend must stay aligned.
- Streaming UIs must use real streaming/SSE; polling is forbidden.

## 12. Commands
- `/vibe-check`:让 AI 对当前项目进行"全局扫描"。它会检查你的代码是否背离了约束。检查结果记录在`docs/vibe-check/`下。
- `/tdd-step [feature]`:启动 Rule 7 定义的 TDD 流程。
- `/document-domain`:自动化文档化。AI 会扫描 domain 包，提取聚合根（Aggregate）、实体（Entity）和值对象（Value Object）。
- `/sync-api [entity|endpoint]`:确保 Rule 11（API 契约一致性）。
- `/check-lock`:专门针对 Rule 5 和 Rule 10 的并发安全检查。
- `/mcp-research [topic]` : 调用 Exa/Grok 获取最佳实践，输出为 `docs/research/` 下的 markdown。
- `/mcp-scan` : 调用 Morph-MCP 扫描当前项目，检查是否违反 DDD 依赖方向（Rule 5）。
- `/mcp-audit-logs` : 检查 `ExecutionLog` 中最近的 MCP 调用耗时与异常。

## 13. Details
See:
- `docs/claude/backend.md`
- `docs/claude/agent.md`
- `docs/claude/frontend.md`
- `docs/claude/ops.md`

## 14. Implementation Plan Rules
- **Plan Archival**: Every implementation plan must be saved to `docs/plans/` before executing. Do NOT rely on `~/.claude/plans/` alone.
- **Naming**: `YYYY-MM-DD-HHmm-description.md` (e.g., `2026-03-10-1245-p0-tx-boundary-and-ai-timeout.md`). Timestamps use 24-hour CST (UTC+8).
- **Plan Header**: Every plan file must include:
  - 创建时间 (precise to minute)
  - 完成时间 (filled in after implementation, or "进行中")
  - 状态 (`⏳ 进行中` / `✅ 已完成` / `❌ 已废弃`)
  - 关联 devlog (link to the corresponding devlog entry)
- **Timing**: Save the plan to `docs/plans/` right after entering plan mode and finalizing the approach, before writing any code.
- **Update on Completion**: After implementation, update the plan's 完成时间 and 状态 fields.

## 15. Workflow & Devlog Rules
- Context Sync: Before any feature implementation, read the latest entries in `docs/devlog/` (MVP-era logs archived in `docs/devlog/mvp-archived/`).
- Documentation Debt: No code is "done" until the corresponding devlog entry is written.
- Log Format: Use XXX-description.md (e.g., 011-version-upgrade.md) containing:
  - Progress: Tasks completed.
  - DDD Decisions: Why specific boundaries or patterns were chosen.
  - Technical Notes: Java 21 features used, AI prompt tweaks, etc.
  - Next Steps: Immediate pending items.
