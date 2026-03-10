# 开发日志 #5 — P1：聚合根乐观锁

日期: 2026-03-10

## 概述

为 `Task` 和 `Execution` 两个聚合根的 JPA 实体添加乐观锁（`@Version`），防止并发修改导致数据不一致。同时在 `GlobalExceptionHandler` 中处理 `OptimisticLockingFailureException`，返回 HTTP 409 Conflict。

## 进度

### Flyway 迁移

- **V3__add_optimistic_lock_version.sql**：`task` 和 `execution` 表各增加 `version BIGINT NOT NULL DEFAULT 0` 列。

### JPA 实体

- **TaskEntity**：新增 `@Version private Long version;` 字段。
- **ExecutionEntity**：新增 `@Version private Long version;` 字段。
- `version` 不进入构造函数，由 JPA 自动管理（首次 persist 初始化为 0，每次 update 自增）。

### 异常处理

- **GlobalExceptionHandler** 新增 `OptimisticLockingFailureException` → HTTP 409 Conflict 映射，返回固定提示信息。

## DDD 决策

1. **Version 不进入 Domain 层** — 乐观锁版本号是持久化关注点，不属于业务概念。Domain 模型（`Task`、`Execution`）保持纯净，不添加 version 字段。
2. **Version 仅在聚合根级别** — `ExecutionStep` 和 `StepLog` 不需要独立的乐观锁，因为它们通过 `Execution` 聚合根的 `CascadeType.ALL` 管理，Execution 的 version 已经覆盖了整个聚合的并发保护。
3. **Repository 映射无需改动** — 现有的 `JpaTaskRepository.save()` 和 `JpaExecutionRepository.save()` 在更新时都是先 `findById` 加载 managed entity 再修改保存，JPA 自动检查 version。

## 技术笔记

- **`@Version` 工作原理**：JPA 在 UPDATE 时自动将 `WHERE version = ?` 加入 SQL。如果行已被其他事务修改（version 不匹配），抛出 `OptimisticLockException`，Spring 包装为 `OptimisticLockingFailureException`。
- **DEFAULT 0**：Flyway 迁移使用 `DEFAULT 0` 确保现有数据行获得初始版本号，不影响已有记录。
- **不影响单元测试**：Domain 和 Application 层测试使用 mock repository，不涉及 JPA 实体，因此 53 个测试全部通过无需改动。

## 测试统计

| 层 | 测试数 |
|---|---|
| Domain | 35 |
| Application | 9 |
| Infrastructure | 9 |
| **合计** | **53** |

## 文件清单

| 操作 | 文件 |
|------|------|
| 新建 | `echoflow-web/.../db/migration/V3__add_optimistic_lock_version.sql` |
| 修改 | `echoflow-infrastructure/.../persistence/task/TaskEntity.java` |
| 修改 | `echoflow-infrastructure/.../persistence/execution/ExecutionEntity.java` |
| 修改 | `echoflow-web/.../GlobalExceptionHandler.java` |
| 新建 | `docs/devlog/005-optimistic-locking.md` |

## 下一步

- P1: LLM 降级策略（重试耗尽后 degraded 而非整体失败）
- P2: 端到端验证（配好 `.env` 启动后端，提交真实任务）
- P2: 前端任务列表自动刷新
- P3: 真实 RESEARCH Tool（GitHub 搜索 API）
- P3: Infrastructure 集成测试（Testcontainers）
