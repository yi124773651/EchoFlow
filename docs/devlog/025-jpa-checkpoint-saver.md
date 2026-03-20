# 025 — JpaSaver 持久化检查点 + WAITING_APPROVAL 启动恢复

## Progress

- StateGraph 检查点从内存 (MemorySaver) 升级为 JPA 持久化 (JpaCheckpointSaver) ✅
- 进程重启后自动恢复 WAITING_APPROVAL 执行，RUNNING 孤儿标记为 FAILED ✅
- 从 `ExecuteTaskUseCase` 提取可复用 `ExecutionProgressListener`，支持 normal + recovery 模式 ✅
- 218 后端测试全绿（新增 11 测试：6 Domain + 5 RecoveryService + 6 JpaCheckpointSaver — 减去 6 因为 ExecutionTest 原有基线已含新增）
- 实际新增：6 ExecutionTest + 5 ExecutionRecoveryServiceTest + 6 JpaCheckpointSaverTest = 17 新测试

## 实现摘要

### 设计选择

**不使用**框架自带的 `PostgresSaver`，改为自定义 `JpaCheckpointSaver extends MemorySaver`。理由：
- `PostgresSaver` 用 raw JDBC + 自行建表，违反项目 Flyway 规范 (Rule 10)
- 表结构与框架版本强耦合
- 自定义 JPA 方案与项目现有 persistence 模式一致

**采用 Domain-based recovery**（不依赖 StateGraph 检查点恢复）。理由：
- 域模型已持有所有恢复所需状态（step outputs, statuses）
- 避免依赖框架未文档化的 checkpoint resume 语义
- JpaCheckpointSaver 仍有审计/调试价值，为未来复杂恢复做准备

### 各层变更

**Domain**（纯 Java）：
- `ExecutionRepository`: 新增 `findByStatus(ExecutionStatus)` 方法
- `Execution`: 新增 `findWaitingApprovalStep()` 和 `pendingSteps()` 查询方法

**Application**：
- `GraphOrchestrationPort`: 新增 `ExecutionId` 参数 + `releaseCheckpoints()` 默认方法
- `ExecutionProgressListener`（新建）：从 `ExecuteTaskUseCase` 提取的可复用 listener，支持 `recoveredStepName` 恢复模式
- `ExecutionRecoveryService`（新建）：启动恢复服务，处理 RUNNING→FAILED 和 WAITING_APPROVAL→恢复
- `ExecuteTaskUseCase`: 重构 `runExecution()` 使用提取的 listener，传入 `executionId`

**Infrastructure**：
- `JpaCheckpointSaver`（新建）：extends `MemorySaver`，覆盖 4 个 protected hook 实现 JPA 持久化
- `CheckpointEntity` + `CheckpointJpaRepository`（新建）：检查点 JPA entity 和 Spring Data repo
- `GraphOrchestrator`: 注入 `CheckpointJpaRepository` + `ObjectMapper`，使用 `JpaCheckpointSaver` 替代 `MemorySaver`，执行完自动清理检查点
- `JpaExecutionRepository`: 实现 `findByStatus()` 映射
- `ExecutionJpaRepository`: 新增 `findByStatus(String)` 查询

**Web**：
- `V4__graph_checkpoint.sql`: Flyway 迁移创建 `graph_checkpoint` 表（UUID PK, thread_id, JSONB state）
- `ExecutionRecoveryConfig`（新建）：`ApplicationReadyEvent` 监听器触发恢复

## DDD Decisions

- `findByStatus` 是 Domain 层的 repository 接口方法 — 按状态查询是合理的领域查询
- `findWaitingApprovalStep()` 和 `pendingSteps()` 是 Execution 聚合根上的纯查询方法 — 不改变状态
- `ExecutionProgressListener` 在 Application 层（package-private）— 协调域模型更新和 SSE 事件
- `ExecutionRecoveryService` 在 Application 层 — 是恢复的用例编排
- `JpaCheckpointSaver` 在 Infrastructure 层（package-private）— 检查点持久化是基础设施关注
- `CheckpointEntity` 在 Infrastructure/persistence — 不需要 Domain 对应物（检查点不是领域概念）

## Technical Notes

- `JpaCheckpointSaver` 的 `insertedCheckpoint()` / `updatedCheckpoint()` 都 append 新行（不可变审计 trail），`releasedCheckpoints()` 清理全部
- 检查点持久化失败仅 `log.warn`，不中断执行 — 审计功能非关键路径
- `ExecutionProgressListener.forRecovery()` 通过 `recoveredStepName` 识别恢复步骤，跳过 PENDING→RUNNING 转换避免状态机异常
- RUNNING 孤儿在恢复时直接标记 FAILED — 无法安全恢复半途中断的 AI 调用
- 恢复的 WAITING_APPROVAL 执行在新 virtual thread 上阻塞，同正常流程一致
- Jackson `ObjectMapper` 序列化 `Map<String, Object>` 到 JSONB — 图状态只含字符串和列表
- `GraphOrchestrator` 通过 `RunnableConfig.builder().threadId(executionId.toString()).build()` 将执行 ID 绑定为检查点线程 ID

## Next Steps

- ~~更新 CLAUDE.md 中的 Key Patterns 和公共类列表~~ ✅ 已完成
- 提交代码
