# JpaSaver — 持久化检查点 + WAITING_APPROVAL 启动恢复

**创建时间**: 2026-03-20 17:00 CST
**完成时间**: 2026-03-20 18:20 CST
**状态**: ✅ 已完成
**关联 devlog**: [025-jpa-checkpoint-saver](../devlog/025-jpa-checkpoint-saver.md)

---

## Context

Phase 3.2B-4 实现了 human-in-the-loop 审批，但使用 `MemorySaver`（内存检查点）+ `ConcurrentHashMap`（审批门）。进程重启后 WAITING_APPROVAL 的执行会成为孤儿——无法恢复。本阶段解决两个问题：

1. **检查点持久化**: 替换 MemorySaver 为 JPA 驱动的持久化 Saver（审计 + 调试能力）
2. **启动恢复**: 进程重启后自动恢复 WAITING_APPROVAL 执行，标记 RUNNING 孤儿为 FAILED

## 设计决策

**不使用框架自带的 PostgresSaver**，理由：
- PostgresSaver 用 raw JDBC，不符合项目 JPA 模式
- 会自行建表，违反 Flyway 规范 (Rule 10)
- 表结构与框架版本耦合

**采用 Domain-based recovery（不依赖 StateGraph 检查点恢复）**，理由：
- 域模型已持有所有恢复所需状态（step outputs, statuses）
- 避免依赖框架未文档化的 checkpoint resume 语义
- JpaCheckpointSaver 仍有价值：审计 + 调试 + 为未来复杂恢复场景做准备

---

## 实施步骤

### Sub-phase 1: Domain 层（纯 Java）

- `ExecutionRepository`: 新增 `findByStatus(ExecutionStatus)` 方法
- `Execution`: 新增 `findWaitingApprovalStep()` 和 `pendingSteps()` 查询方法
- Domain 测试先行 — `ExecutionTest` 中新增 6 个用例

### Sub-phase 2: Flyway 迁移

- `V4__graph_checkpoint.sql`: `graph_checkpoint` 表（UUID PK, thread_id, JSONB state）

### Sub-phase 3: JPA 检查点基础设施

- `CheckpointEntity` — JPA entity
- `CheckpointJpaRepository` — Spring Data 接口
- `JpaCheckpointSaver` — extends MemorySaver，覆盖 4 个 protected hook
- 6 个单元测试

### Sub-phase 4: 接入 GraphOrchestrator

- `GraphOrchestrationPort`: 新增 `ExecutionId` 参数 + `releaseCheckpoints()` 默认方法
- `GraphOrchestrator`: 注入依赖，使用 JpaCheckpointSaver，RunnableConfig 传入 threadId，finally 清理
- `ExecuteTaskUseCase`: 传入 `execution.id()`
- 级联测试修改

### Sub-phase 5: 提取 ExecutionProgressListener

- 从 `ExecuteTaskUseCase` 匿名 listener 提取为命名类
- 支持 `recoveredStepName` 恢复模式
- 重构 `ExecuteTaskUseCase.runExecution()` 使用新 listener

### Sub-phase 6: 启动恢复服务

- `ExecutionRecoveryService`: RUNNING→FAILED + WAITING_APPROVAL→恢复
- `ExecutionRecoveryConfig`: ApplicationReadyEvent 触发
- 5 个单元测试

### Sub-phase 7: 集成测试 + 收尾

- 全量 218 测试回归 GREEN
- Devlog 撰写 + CLAUDE.md 更新

---

## 文件清单

### 新建文件（7 个）

| 文件 | 层 | 说明 |
|------|----|------|
| `V4__graph_checkpoint.sql` | Web/migration | 检查点表 DDL |
| `CheckpointEntity.java` | Infrastructure/persistence | JPA entity |
| `CheckpointJpaRepository.java` | Infrastructure/persistence | Spring Data repo |
| `JpaCheckpointSaver.java` | Infrastructure/ai/graph | MemorySaver 子类 |
| `ExecutionProgressListener.java` | Application | 提取的可复用 listener |
| `ExecutionRecoveryService.java` | Application | 启动恢复服务 |
| `ExecutionRecoveryConfig.java` | Web/config | ApplicationReadyEvent 触发 |

### 修改文件（7 个）

| 文件 | 变更 |
|------|------|
| `ExecutionRepository.java` (Domain) | 新增 `findByStatus()` |
| `Execution.java` (Domain) | 新增 `findWaitingApprovalStep()`, `pendingSteps()` |
| `GraphOrchestrationPort.java` (Application) | 新增 `ExecutionId` 参数, `releaseCheckpoints()` |
| `ExecuteTaskUseCase.java` (Application) | 传入 executionId, 使用提取的 listener |
| `JpaExecutionRepository.java` (Infrastructure) | 实现 `findByStatus` |
| `ExecutionJpaRepository.java` (Infrastructure) | 新增 `findByStatus(String)` |
| `GraphOrchestrator.java` (Infrastructure) | 注入依赖, 使用 JpaCheckpointSaver |
