# Plan: P1 LLM 降级策略 + P2 前端任务列表刷新

- **创建时间**: 2026-03-10 13:30 CST
- **完成时间**: 2026-03-10 13:40 CST
- **状态**: ✅ 已完成
- **关联 devlog**: docs/devlog/006-llm-degradation.md

---

## Context

当前行为：LLM 步骤执行失败（重试 2 次耗尽后），`ExecuteTaskUseCase.runExecution()` 立即将整个 Execution 标记为 FAILED，剩余步骤不再执行。用户看到的是一个完全失败的任务。

期望行为：LLM 调用失败时，将该步骤标记为 SKIPPED（降级），继续执行剩余步骤。最终 Execution 完成（部分步骤降级），用户得到一个不完美但可用的结果。

P2"前端任务列表自动刷新"已通过 `onDone` 回调机制解决，不再需要额外改动。

---

## 实施步骤

### Step 1: Domain — 修改 `ExecutionStep.markSkipped()` 支持 RUNNING → SKIPPED

**文件**: `echoflow-domain/.../execution/ExecutionStep.java`

现有 `markSkipped()` 只允许 PENDING → SKIPPED 且无参数。需要：
- 改签名为 `markSkipped(String reason)`
- 允许 PENDING 或 RUNNING → SKIPPED
- 将 reason 存入 output 字段

```java
void markSkipped(String reason) {
    if (this.status != StepStatus.PENDING && this.status != StepStatus.RUNNING) {
        throw new IllegalExecutionStateException(
                "Step " + id + " cannot transition from " + status + " to SKIPPED");
    }
    this.status = StepStatus.SKIPPED;
    this.output = reason;
}
```

**测试** (在 `ExecutionTest.java` 中新增，因为 `ExecutionStep` 方法是 package-private，通过 `Execution` 调用):
- `skipStep_transitions_running_step_to_skipped`
- `markCompleted_succeeds_with_skipped_steps`
- `hasPendingSteps_false_after_skip`

### Step 2: Domain — 添加 `Execution.skipStep(StepId, String)`

**文件**: `echoflow-domain/.../execution/Execution.java`

```java
public void skipStep(StepId stepId, String reason) {
    requireRunning();
    findStep(stepId).markSkipped(reason);
}
```

### Step 3: Application — 添加 `StepSkipped` 事件

**文件**: `echoflow-application/.../execution/ExecutionEvent.java`

在 sealed interface 中新增：
```java
record StepSkipped(ExecutionId executionId, StepId stepId,
                   String reason, Instant timestamp) implements ExecutionEvent {}
```

### Step 4: Application — 修改 `ExecuteTaskUseCase.runExecution()` 降级逻辑

**文件**: `echoflow-application/.../execution/ExecuteTaskUseCase.java`

1. 添加 `skipStep()` 私有方法（同 `completeStep()`/`failStep()` 模式）
2. 修改 `runExecution()` 的 catch 块：
   - `StepExecutionException` → 降级（skipStep + continue）
   - 其他 `Exception` → 仍然 fail-fast

```java
} catch (StepExecutionException e) {
    appendLog(execution, step.id(), LogType.ERROR,
            "Step degraded: " + e.getMessage(), clock.instant());
    skipStep(execution, step.id(), e.getMessage());
    // 不将 skipped 步骤的输出加入 previousOutputs，继续下一步
} catch (Exception e) {
    appendLog(execution, step.id(), LogType.ERROR,
            e.getMessage(), clock.instant());
    failStep(execution, step.id(), e.getMessage());
    failExecution(execution, e.getMessage());
    return;
}
```

**注意**: `appendLog` 在 `skipStep` 之前调用（此时步骤仍是 RUNNING 状态），顺序正确。

**测试** (`ExecuteTaskUseCaseTest.java`):
- 修改现有 `execute_fails_when_step_executor_throws` → 改为抛 `RuntimeException` 验证非 LLM 异常仍 fail-fast
- 新增 `execute_degrades_when_step_execution_fails` — 3 步中第 2 步抛 `StepExecutionException`，验证任务 COMPLETED，第 2 步 SKIPPED
- 新增 `execute_skipped_step_output_not_in_previous_outputs` — 验证后续步骤的 previousOutputs 不含 skipped 步骤输出

### Step 5: Frontend — 支持 SKIPPED 状态

**文件**: `use-execution-stream.ts`
- `StepState.status` 联合类型加 `"SKIPPED"`
- `snapshotToState` 移除 `SKIPPED → FAILED` 映射
- 添加 `StepSkipped` SSE 事件监听

**文件**: `execution-timeline.tsx`
- `STATUS_ICON` 加 `SKIPPED: "⚠"`
- `statusColor` 加 `SKIPPED → text-yellow-600`
- 添加 SKIPPED 输出展示块（黄色警告文字）

**文件**: `types/task.ts`
- 添加 `StepSkippedEvent` 接口

### Step 6: SSE Publisher — 无需改动

`SseExecutionEventPublisher.publish()` 使用 `event.getClass().getSimpleName()` 作为事件类型名，新增的 `StepSkipped` record 会自动映射为 SSE 事件类型 `"StepSkipped"`。无需改动。

---

## 涉及文件总览

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
| 新建 | `docs/devlog/006-llm-degradation.md` |

---

## 验证

1. `./mvnw test -pl echoflow-backend/echoflow-domain` — Domain 测试全通过
2. `./mvnw test -pl echoflow-backend/echoflow-application` — Application 测试全通过
3. `./mvnw test -pl echoflow-backend -am` — 全量后端测试通过
4. `cd echoflow-frontend && npm run build` — TypeScript 编译通过
5. `cd echoflow-frontend && npm run lint` — ESLint 通过
