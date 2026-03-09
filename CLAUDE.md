# CLAUDE.md

Hard constraints in this file override all subfiles.

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
- Backend: Java 21, Spring Boot 3.4+, Spring MVC, Virtual Threads
- AI: Spring AI Alibaba
- Build: Maven 3.9+
- DB: PostgreSQL 16+, pgvector, Flyway
- Persistence: Spring Data JPA by default
- Frontend: Next.js 14+ App Router, React 18+, TypeScript, Tailwind, ShadcnUI
- Testing: JUnit 5, Mockito, Spring Boot Test, Testcontainers

## 4. Architecture Rules
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

## 5. Coding Rules
- Prefer `record` for DTOs, commands, queries, and immutable value objects.
- Prefer `sealed interface` / `sealed class` for bounded states/events.
- Prefer `switch` expressions and pattern matching when clearer.
- Prefer immutability; avoid unnecessary setters.
- Do not force entities into `record`.
- Do not introduce new frameworks without clear need.

## 6. TDD Rules
- Follow Red -> Green -> Refactor.
- Behavior change starts with a test.
- Bug fix starts with a failing test.
- Domain/Application tests must not require real external services.
- AI/external integrations must be mocked or stubbed in unit tests.

## 7. Backend Rules
- Controllers must stay thin.
- Application orchestrates use cases and transactions.
- Domain owns business rules and invariants.
- Infrastructure handles persistence, AI, HTTP clients, tools, storage, messaging.
- Domain exceptions must not contain HTTP details.
- Web errors must be mapped centrally using `@RestControllerAdvice` and `ProblemDetail`.

## 8. AI Rules
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
  - 所有 MCP 调用视为 `External Integration`，必须遵循 **Rule 4**（严禁在数据库事务中调用）。
  - MCP 返回的非结构化数据在进入 Domain 前必须经过验证并转换为 `record` DTO。
  - 严禁通过 MCP 直接执行未经审计的写操作（如 `rm`, `push`）。

## 9. Data Rules
- All schema changes go through Flyway.
- `spring.jpa.hibernate.ddl-auto=update` is forbidden.
- `pgvector` extension, columns, and indexes must be created by migration scripts.
- Use optimistic locking where aggregate concurrency matters.

## 10. Frontend Rules
- Use Next.js App Router.
- RSC First: default to Server Components.
- Use `'use client'` only when truly necessary.
- TypeScript must stay strict; `any` is forbidden.
- API contracts between backend and frontend must stay aligned.
- Streaming UIs must use real streaming/SSE; polling is forbidden.

## 11. Commands
- `/vibe-check`:让 AI 对当前项目进行“全局扫描”。它会检查你的代码是否背离了约束。
- `/tdd-step [feature]`:启动 Rule 6 定义的 TDD 流程。
- `/document-domain`:自动化文档化。AI 会扫描 domain 包，提取聚合根（Aggregate）、实体（Entity）和值对象（Value Object）。
- `/sync-api [entity|endpoint]`:确保 Rule 10（API 契约一致性）。
- `/check-lock`:专门针对 Rule 4 和 Rule 9 的并发安全检查。
- `/mcp-research [topic]` : 调用 Exa/Grok 获取最佳实践，输出为 `docs/research/` 下的 markdown。
- `/mcp-scan` : 调用 Morph-MCP 扫描当前项目，检查是否违反 DDD 依赖方向（Rule 4）。
- `/mcp-audit-logs` : 检查 `ExecutionLog` 中最近的 MCP 调用耗时与异常。

## 12. Details
See:
- `docs/claude/backend.md`
- `docs/claude/agent.md`
- `docs/claude/frontend.md`
- `docs/claude/ops.md`

## 13. Workflow & Devlog Rules 
- Context Sync: Before any feature implementation, read the latest entries in docs/devlog/.
- Documentation Debt: No code is "done" until the corresponding devlog entry is written.
- Log Format: Use XXX-description.md (e.g., 002-auth-impl.md) containing:
  - Progress: Tasks completed.
  - DDD Decisions: Why specific boundaries or patterns were chosen.
  - Technical Notes: Java 21 features used, AI prompt tweaks, etc.
  - Next Steps: Immediate pending items.