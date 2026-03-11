# 017 — Phase 3.2A: StateGraph 线性链等价替换

## Progress

- StateGraph 线性链替代 `ExecuteTaskUseCase.runExecution()` 的 while 循环 ✅
- `GraphOrchestrationPort`（Application 层 Port 接口）+ `StepProgressListener` 回调接口 ✅
- `StepNodeAction`（Infrastructure 层，AsyncNodeAction 实现）✅
- `GraphOrchestrator`（Infrastructure 层，@Component，构建并执行 StateGraph 线性链）✅
- `ExecuteTaskUseCase.runExecution()` 重构：委托给 `GraphOrchestrationPort` ✅
- 移除 UseCase 对 `StepExecutorPort` 的直接依赖（由 GraphOrchestrator 内部持有）✅
- 新增测试：StepNodeActionTest (6 tests) + GraphOrchestratorTest (7 tests) ✅
- 更新测试：ExecuteTaskUseCaseTest（Mock GraphOrchestrationPort 替代 StepExecutorPort）✅
- 全量测试：77 tests, 0 failures, 0 errors, BUILD SUCCESS ✅
- DDD 边界检查：Application/Domain 层零 StateGraph import ✅

## DDD Decisions

- **回调模式 (StepProgressListener) 替代批量返回**: UseCase 提供匿名 `StepProgressListener` 实现，NodeAction 在执行前后调用回调。这保持了与 while 循环完全相同的 SSE 事件发布时序。批量返回方案（`List<StepResult>`）会延迟所有事件到执行完毕才发布，破坏实时性。
- **GraphOrchestrationPort 作为纯 Java 接口**: Application 层仅依赖纯 Java 接口（`GraphOrchestrationPort` + 嵌套 `StepProgressListener`），无任何框架依赖。所有 StateGraph/OverAllState/KeyStrategy 类型限制在 Infrastructure 层。
- **StepExecutorPort 依赖迁移**: 从 `ExecuteTaskUseCase` 移至 `GraphOrchestrator`。UseCase 不再直接调用单步执行器，改为通过 Port 接口委托给图编排引擎。
- **不使用 GraphLifecycleListener**: 调研阶段计划的 `ExecutionEventBridge` 未实现。回调模式（StepProgressListener）更简洁，直接在 NodeAction 中调用 listener，无需额外桥接层。

## Technical Notes

### 架构变更

```
Phase 3.1:  UseCase.runExecution() → while 循环 → stepExecutor.execute()
Phase 3.2A: UseCase.runExecution() → GraphOrchestrationPort → StateGraph 线性链 → StepNodeAction → stepExecutor.execute()
```

### OverAllState 状态设计（最终实现）

```java
KeyStrategy.builder()
    .addStrategy("taskDescription", KeyStrategy.REPLACE)
    .addStrategy("outputs", KeyStrategy.APPEND)
    .build();
```

- `taskDescription`: REPLACE 策略，初始化时设置一次
- `outputs`: APPEND 策略，每个成功步骤追加输出，跳过的步骤返回空 Map 不追加
- 未使用 `currentStep` 和 `messages` key（调研阶段预设但实际不需要）

### Node ID 策略

使用索引 ID（`step_1`, `step_2`, ...）而非步骤名称，避免 LLM 生成的步骤名包含特殊字符或重复导致 ID 冲突。

### 与调研阶段计划的差异

| 计划 | 实际 | 原因 |
|------|------|------|
| `Flux<StepResult>` 返回流 | `void` + 回调 StepProgressListener | 保持 SSE 事件实时发布时序 |
| `ExecutionEventBridge` (GraphLifecycleListener) | 直接在 StepNodeAction 中回调 | 回调模式更简洁，无需额外桥接 |
| `currentStep` + `messages` state keys | 仅 `taskDescription` + `outputs` | 简化设计，其他信息通过回调传递 |
| `StepResult` record | 不需要 | 回调模式下结果直接传给 listener |

### 关键文件清单

| 文件 | 操作 | 层 |
|------|------|-----|
| `application/execution/GraphOrchestrationPort.java` | 新建 | Application |
| `infrastructure/ai/StepNodeAction.java` | 新建 | Infrastructure |
| `infrastructure/ai/GraphOrchestrator.java` | 新建 | Infrastructure |
| `application/execution/ExecuteTaskUseCase.java` | 重构 | Application |
| `infrastructure/ai/StepNodeActionTest.java` | 新建 | Test |
| `infrastructure/ai/GraphOrchestratorTest.java` | 新建 | Test |
| `application/execution/ExecuteTaskUseCaseTest.java` | 更新 | Test |

### SSE 事件序列（验证通过）

```
ExecutionStarted
  → StepStarted → StepLogAppended(ACTION) → StepLogAppended(OBSERVATION) → StepCompleted
  → StepStarted → StepLogAppended(ACTION) → StepLogAppended(OBSERVATION) → StepCompleted
  → ...
ExecutionCompleted
```

与 Phase 3.1 while 循环完全一致，前端无感。

## Next Steps

1. Phase 3.2B: 条件路由 + 并行执行（`addConditionalEdges` / 并行节点）
2. Phase 3.3: JpaSaver 替代 MemorySaver（持久化检查点，支持断点恢复）
3. Phase 3.2C: LLM-Driven 编排（远期 — SupervisorAgent / LlmRoutingAgent）
