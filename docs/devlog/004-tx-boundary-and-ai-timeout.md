# 开发日志 #4 — P0 修复：事务边界 & AI 超时

日期: 2026-03-10

## 概述

修复 vibe-check 报告中的两个 CRITICAL 问题：
1. `ExecuteTaskUseCase.planExecution()` 的 `@Transactional` 包裹 LLM 远程调用（违反 Rule 4）
2. ChatClient 调用无 HTTP 超时配置（违反 Rule 8.6）

## 进度

### P0-1: 拆分事务边界

**问题**：`planExecution()` 上的 `@Transactional` 将 `taskPlanner.planSteps()` LLM 调用包在数据库事务内。此外，类中所有 6 个 `@Transactional protected` 方法通过 self-invocation 调用，Spring proxy 不拦截——注解实际是 no-op，但形成隐患。

**修复**：
- 注入 `TransactionOperations`（`TransactionTemplate` 的接口），替代所有声明式 `@Transactional`
- `planExecution()` 拆分为三阶段：读 task（隐式 repo 事务）→ LLM 调用（无事务）→ `tx.executeWithoutResult { save task + save execution }`（短事务）
- `completeExecution()` / `failExecution()` 的多聚合写操作用 `tx.executeWithoutResult` 包裹，保证原子性
- `completeStep()` / `failStep()` / `saveExecution()` 的单次 save 依赖 Spring Data JPA 隐式事务
- 所有 helper 从 `protected` 改为 `private`；`planExecution()` 保持 package-private 供测试访问
- SSE 事件发布移到事务提交之后，确保前端不会收到未持久化数据的通知

**测试**：构造函数新增第 7 个参数 `TransactionOperations.withoutTransaction()`，7 个现有测试全部通过，无需其他改动。

### P0-2: AI 调用添加 HTTP Timeout

**问题**：`AiTaskPlanner` 和 `StepExecutorRouter` 构建的 `ChatClient` 无超时配置。LLM 提供商无响应时线程会被无限期阻塞。

**修复**：
- 新建 `AiClientConfig`（echoflow-web/config/），提供带超时的 `RestClient.Builder` bean
- 使用 `JdkClientHttpRequestFactory`，兼容虚拟线程
- Spring AI 1.0.0 的 `OpenAiChatAutoConfiguration` 通过 `ObjectProvider<RestClient.Builder>` 自动注入
- `application.yml` 新增 `echoflow.ai.connect-timeout: 10s` 和 `echoflow.ai.read-timeout: 60s`
- 超时值可通过配置或环境变量覆盖

## DDD 决策

1. **`TransactionOperations` 在 Application 层注入** — 这是 Spring 事务抽象接口（属于 `spring-tx`），不违反 Application 层只依赖 Spring 核心抽象的原则。`TransactionTemplate` 的具体实例由 Web 层的 Spring Boot 自动配置提供。
2. **编程式事务 vs 声明式** — `@Transactional` 在 self-invocation 场景下失效，编程式事务让边界更显式、更可控。对于"LLM 调用绝不能在事务内"这种硬约束，显式代码比注解更安全。
3. **SSE 事件在事务提交后发布** — 避免前端收到未持久化数据的通知，保证最终一致性。
4. **`RestClient.Builder` bean 配置在 Web 层** — 遵循 Infrastructure 实现适配器、Web 层负责配置注入的分层原则。

## 技术笔记

- **`TransactionOperations.withoutTransaction()`**：Spring 5.2+ 内置的空实现，直接执行回调，不开启事务。完美适配单元测试场景。
- **Self-invocation 陷阱**：Spring proxy-based AOP 只拦截从外部调用 bean 方法的情况。`this.method()` 形式的内部调用不经过代理，`@Transactional` 不生效。这是 Spring 文档中明确警告的行为。
- **`JdkClientHttpRequestFactory`**：使用 JDK 21 HttpClient，与虚拟线程兼容（不像 Apache HttpClient 可能阻塞 carrier 线程）。`connectTimeout` 设在 HttpClient 上，`readTimeout` 设在 RequestFactory 上。
- **`failExecution()` 中的 `findById` 在事务内**：虽然一般原则是避免事务内做 I/O，但这是本地数据库主键查询（非远程调用），且需要与 save 操作原子化，可接受。

## 测试统计

| 层 | 测试数 |
|---|---|
| Domain | 35 |
| Application | 7 (原有，构造参数更新) |
| Infrastructure | 9 |
| **合计** | **51** |

## 文件清单

| 操作 | 文件 |
|------|------|
| 修改 | `echoflow-application/.../execution/ExecuteTaskUseCase.java` |
| 修改 | `echoflow-application/.../execution/ExecuteTaskUseCaseTest.java` |
| 新建 | `echoflow-web/.../config/AiClientConfig.java` |
| 修改 | `echoflow-web/.../resources/application.yml` |
| 新建 | `docs/devlog/004-tx-boundary-and-ai-timeout.md` |

## 下一步

- P1: 添加乐观锁（`@Version`）到 `TaskEntity` / `ExecutionEntity`
- P1: LLM 降级策略（重试耗尽后标记步骤为 degraded 而非整体失败）
- P2: 前端 `setTimeout` 替换为 SSE 或本地 state 更新
- P2: 端到端验证（配好 `.env` 启动后端，提交真实任务）
