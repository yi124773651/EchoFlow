# Phase 3.2B-4: Human-in-the-Loop (WRITE 步骤审批)

- 创建时间: 2026-03-18 20:00 CST
- 完成时间: 2026-03-19 02:45 CST
- 状态: ✅ 已完成
- 关联 devlog: docs/devlog/024-phase3-2b4-human-in-the-loop.md

## Context

Phase 3.2B 系列已完成条件路由、并行 RESEARCH、WRITE Review 循环。路线图中下一项是 **Human-in-the-Loop**：WRITE 步骤执行前暂停，等待用户审批后再继续。这是 Agent 安全性的关键特性 — 防止 LLM 生成的内容未经审核直接输出。

**设计选择**：不使用 Spring AI Alibaba 的 `interruptBefore` 框架机制，而是在 Application 层通过 `CompletableFuture` 实现阻塞式审批门。原因：
- `executeSteps()` 保持阻塞式调用语义，不需要重构整个执行模型
- 不需要缓存 `CompiledGraph` + `RunnableConfig` 的生命周期
- Virtual Thread 上的阻塞成本极低
- 所有 StateGraph 类型仍然封闭在 Infrastructure 层

## 实现计划

### Sub-phase 1: Domain 层（纯 Java）

- `StepStatus` 新增 `WAITING_APPROVAL`
- `ExecutionStatus` 新增 `WAITING_APPROVAL`
- `ExecutionStep`: 新增 `markWaitingApproval()` / `resumeFromApproval()`，修改 `markSkipped()` / `appendLog()`
- `Execution`: 新增 `markWaitingApproval()` / `resumeRunning()` / `requireRunningOrWaiting()`
- `ApprovalDecision` 值对象（新建 record）

### Sub-phase 2: Application 层

- `GraphOrchestrationPort.StepProgressListener` 新增 `onStepAwaitingApproval()` default 方法
- `ExecutionEvent` 新增 `StepAwaitingApproval` / `StepApprovalDecided`
- `ApprovalGateService`（新建）: CompletableFuture 管理
- `ExecuteTaskUseCase`: 实现审批回调（阻塞 virtual thread）
- `ApproveStepUseCase`（新建）: REST → gate 桥接

### Sub-phase 3: Infrastructure 层

- `StepNodeAction.apply()`: 插入审批门（onStepStarting 之后、execute 之前）
- `ReviewableWriteNodeAction.apply()`: 同上
- `HumanApprovalProperties`（新建 @ConfigurationProperties）

### Sub-phase 4: Web 层

- `TaskController`: `POST /{taskId}/execution/approve` / `reject`
- `application.yml`: `echoflow.approval.enabled=false`, `timeout-minutes=30`

### Sub-phase 5: Frontend

- 类型定义、API 服务、SSE Hook、执行时间线审批按钮

## 关键设计点

| 设计点 | 决策 |
|--------|------|
| 暂停机制 | Application 层 CompletableFuture 阻塞 virtual thread |
| 审批范围 | 仅 WRITE 步骤（Application 层判断） |
| 可配置性 | echoflow.approval.enabled + timeout-minutes |
| 超时处理 | 自动 APPROVED（安全降级） |
| 拒绝处理 | 跳过步骤（SKIPPED），不终止执行 |
