# 开发日志 #6 — P1：LLM 降级策略

日期: 2026-03-10

## 概述

当 LLM 步骤执行失败（重试 2 次耗尽后），不再立即终止整个 Execution，而是将该步骤标记为 SKIPPED（降级），继续执行剩余步骤。用户得到一个部分降级但仍然可用的结果，而非完全失败。

同时确认 P2"前端任务列表自动刷新"已通过 `onDone` 回调机制解决，无需额外改动。

## 进度

### Domain 层

- **`ExecutionStep.markSkipped(String reason)`**：修改签名从无参改为接受 reason 参数，允许 PENDING 或 RUNNING → SKIPPED 转换（原先仅允许 PENDING → SKIPPED），将 reason 存入 output 字段。
- **`Execution.skipStep(StepId, String)`**：新增委托方法，遵循 `completeStep()` / `failStep()` 的相同模式。
- **新增 4 个测试**：`skipStep_transitions_running_step_to_skipped`、`markCompleted_succeeds_with_skipped_steps`、`hasPendingSteps_false_after_skip`、`skipStep_from_completed_throws`。

### Application 层

- **`ExecutionEvent.StepSkipped`**：sealed interface 新增事件 record，包含 `executionId`、`stepId`、`reason`、`timestamp`。
- **`ExecuteTaskUseCase.runExecution()` 降级逻辑**：catch 块拆分为两级：
  - `StepExecutionException` → 降级（appendLog ERROR + skipStep），不加入 previousOutputs，继续下一步。
  - 其他 `Exception` → 仍然 fail-fast（failStep + failExecution + return）。
- **`ExecuteTaskUseCase.skipStep()`**：新增私有方法，与 `completeStep()` / `failStep()` 对称。
- **测试变更**：
  - 删除 `execute_fails_when_step_executor_throws`（行为已变）。
  - 新增 `execute_fails_on_unexpected_exception`（`RuntimeException` 仍导致 FAILED）。
  - 新增 `execute_degrades_when_step_execution_fails`（3 步中第 2 步降级，任务 COMPLETED）。
  - 新增 `execute_skipped_step_output_not_in_previous_outputs`（验证后续步骤不接收 skipped 输出）。

### Frontend

- **`use-execution-stream.ts`**：`StepState.status` 联合类型新增 `"SKIPPED"`，`snapshotToState` 移除 `SKIPPED → FAILED` 映射，新增 `StepSkipped` SSE 事件监听。
- **`execution-timeline.tsx`**：`STATUS_ICON` 新增 `SKIPPED: "⚠"`，`statusColor` 新增 `SKIPPED → text-yellow-600`，新增 SKIPPED 输出展示块（黄色警告文字）。
- **`types/task.ts`**：新增 `StepSkippedEvent` 接口。

### SSE Publisher

`SseExecutionEventPublisher` 无需改动 — 使用 `event.getClass().getSimpleName()` 动态获取事件类型名，新增的 `StepSkipped` record 自动映射为 `"StepSkipped"` SSE 事件。

## DDD 决策

1. **`StepExecutionException` 是降级信号** — 这是 `LlmStepExecutor` 重试耗尽后抛出的特定异常，代表"LLM 不可用但系统仍正常"。其他异常（`RuntimeException` 等）代表系统级错误，仍触发 fail-fast。
2. **Skipped 步骤输出不传递给后续步骤** — previousOutputs 仅累积 COMPLETED 步骤的输出。降级步骤没有有效输出，传递会污染后续步骤的上下文。
3. **Domain 层的 `markSkipped()` 允许 RUNNING → SKIPPED** — 原设计仅允许 PENDING → SKIPPED（用于条件跳过），现在扩展为也支持从 RUNNING 状态跳过（用于运行时降级），语义更完整。

## 技术笔记

- **`appendLog` 顺序安全**：降级时先 `appendLog`（步骤仍 RUNNING）再 `skipStep`（转 SKIPPED），日志追加在状态转换之前，不会触发 `appendLog` 的 RUNNING 状态检查异常。
- **Sealed interface 的 switch 完备性**：`SseExecutionEventPublisher` 不使用 switch 分发事件（而是 `instanceof` 判断终态），所以新增 `StepSkipped` 不会破坏编译。
- **`markCompleted()` 已兼容 SKIPPED**：`Execution.markCompleted()` 的条件是所有步骤为 COMPLETED 或 SKIPPED，无需改动。

## 测试统计

| 层 | 测试数 | 变化 |
|---|---|---|
| Domain | 39 | +4 |
| Application | 11 | +3, -1 改名 |
| Infrastructure | 9 | 不变 |
| **合计** | **59** | **+6** |

## 文件清单

| 操作 | 文件 |
|------|------|
| 修改 | `echoflow-domain/.../execution/ExecutionStep.java` |
| 修改 | `echoflow-domain/.../execution/Execution.java` |
| 修改 | `echoflow-domain/src/test/.../ExecutionTest.java` |
| 修改 | `echoflow-application/.../execution/ExecutionEvent.java` |
| 修改 | `echoflow-application/.../execution/ExecuteTaskUseCase.java` |
| 修改 | `echoflow-application/src/test/.../ExecuteTaskUseCaseTest.java` |
| 修改 | `echoflow-frontend/src/hooks/use-execution-stream.ts` |
| 修改 | `echoflow-frontend/src/features/tasks/execution-timeline.tsx` |
| 修改 | `echoflow-frontend/src/types/task.ts` |
| 新建 | `docs/plans/2026-03-10-1330-p1-llm-degradation.md` |
| 新建 | `docs/devlog/006-llm-degradation.md` |

## 下一步

- P2: 端到端验证（配好 `.env` 启动后端，提交真实任务）
- P3: 真实 RESEARCH Tool（GitHub 搜索 API）
- P3: 真实 NOTIFY Tool（邮件/Webhook）
- P3: Infrastructure 集成测试（Testcontainers）
