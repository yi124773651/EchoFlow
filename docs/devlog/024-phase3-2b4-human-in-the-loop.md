# 024 — Phase 3.2B-4: Human-in-the-Loop (WRITE 步骤审批)

## Progress

- 实现 WRITE 步骤执行前暂停，等待用户 approve/reject 后再继续 ✅
- 全栈实现：Domain → Application → Infrastructure → Web → Frontend ✅
- 125 后端测试全绿（新增 23 测试：12 Domain + 8 ApprovalGateService + 3 ExecuteTaskUseCase）
- TypeScript 编译无错误 ✅

## 实现摘要

### 设计选择

**不使用** Spring AI Alibaba 的 `interruptBefore` 框架机制，改为 Application 层 `CompletableFuture` 阻塞式审批门。理由：
- `executeSteps()` 保持阻塞式语义，无需重构执行模型
- 无需管理 `CompiledGraph` + `RunnableConfig` 跨中断生命周期
- Virtual Thread 上的阻塞成本极低
- StateGraph 类型仍完全封闭在 Infrastructure 层

### 各层变更

**Domain**（纯 Java）：
- `StepStatus` / `ExecutionStatus` 新增 `WAITING_APPROVAL`
- `ExecutionStep`: `markWaitingApproval()` / `resumeFromApproval()`，`markSkipped()` / `appendLog()` 扩展支持 WAITING_APPROVAL
- `Execution`: `markWaitingApproval()` / `resumeRunning()` / `markStepWaitingApproval()` / `resumeStepFromApproval()` / `requireRunningOrWaiting()`
- `ApprovalDecision` 值对象（新建 record）

**Application**：
- `GraphOrchestrationPort.StepProgressListener.onStepAwaitingApproval()` default 方法（返回 APPROVED）
- `ExecutionEvent.StepAwaitingApproval` / `StepApprovalDecided` 新增 SSE 事件
- `ApprovalGateService`（新建）：`ConcurrentHashMap<ExecutionId, CompletableFuture<ApprovalDecision>>` 管理
- `ApproveStepUseCase`（新建）：REST → gate 桥接
- `ExecuteTaskUseCase`: `waitForApproval()` 实现阻塞式审批，`@Value` 注入配置

**Infrastructure**：
- `StepNodeAction.apply()` / `ReviewableWriteNodeAction.apply()`: 在 `onStepStarting` 后、`execute` 前插入审批门
- `HumanApprovalProperties`（新建 `@ConfigurationProperties`）
- 所有 mock `StepProgressListener` 的测试添加 `lenient().when(listener.onStepAwaitingApproval(...)).thenReturn(APPROVED)`

**Web**：
- `TaskController`: `POST /{taskId}/execution/approve` / `reject`
- `AiClientConfig`: 启用 `HumanApprovalProperties`
- `application.yml`: `echoflow.approval.enabled=false`, `timeout-minutes=30`

**Frontend**：
- `task.ts`: 新增 `WAITING_APPROVAL` 状态、`StepAwaitingApprovalEvent` / `StepApprovalDecidedEvent`
- `use-execution-stream.ts`: 监听 `StepAwaitingApproval` / `StepApprovalDecided` 事件
- `task-service.ts`: `approveStep()` / `rejectStep()`
- `execution-timeline.tsx`: WAITING_APPROVAL 暂停图标 + 批准/拒绝按钮

## DDD Decisions

- `ApprovalDecision` 是 Domain 值对象 — 审批决策是业务概念
- `ApprovalGateService` 在 Application 层 — 管理跨请求的 Future 生命周期，不涉及外部系统
- 审批门插入点在 Infrastructure 的 `StepNodeAction` / `ReviewableWriteNodeAction` — 因为这是 StateGraph 节点动作，属于基础设施编排
- Application 层 `onStepAwaitingApproval` 回调决定 **哪些步骤需要审批**（只有 WRITE 且 enabled），Infrastructure 层只负责 **调用回调**

## Technical Notes

- `CompletableFuture.get(timeout, MINUTES)` 阻塞 virtual thread — Java 21 virtual thread 在 park 时释放 carrier thread，成本极低
- Mockito `@Mock` 会覆盖 Java `default` 接口方法返回 `null`，需要 `lenient().when(...)` 显式 stub
- `pom.xml` 新增 `slf4j-api` 到 application 模块（用于 `ExecuteTaskUseCase` 日志）
- 已知限制：进程重启后 WAITING_APPROVAL 状态的执行将失去恢复能力（JpaSaver 阶段解决）

## Next Steps

- ~~更新 CLAUDE.md 中的 Key Patterns 和公共类列表~~ ✅ 已完成
- ~~提交代码~~ ✅ 已完成（32 files changed, +949/-34）
