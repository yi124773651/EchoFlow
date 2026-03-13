# Phase 3.2: StateGraph 动态编排 — SequentialAgent 等价替换

**创建时间**: 2026-03-12 01:20 CST
**完成时间**: 2026-03-14 03:40 CST
**状态**: ✅ 已完成（Phase 3.2A ✅，Phase 3.2B-1 ✅，Phase 3.2B-2 ✅，Phase 3.2B-3 ✅，Phase 3.3 E2E ✅）
**关联 devlog**: `docs/devlog/016-dynamic-orchestration-research.md`, `docs/devlog/017-phase3-2a-stategraph-linear-chain.md`, `docs/devlog/018-phase3-2b1-conditional-routing.md`, `docs/devlog/019-phase3-2b2-parallel-research.md`, `docs/devlog/020-phase3-2b3-write-review-loop.md`, `docs/devlog/021-phase3-3-e2e-smoke-test.md`
**关联调研**: `docs/research/dynamic-orchestration-research.md`

---

## 背景

Phase 3.1 已完成 ReactAgent 全量迁移（4 种 StepType 均由 ReactAgent 驱动）。但执行编排仍是 `ExecuteTaskUseCase.runExecution()` 中的 `while` 循环 + 手动 `previousOutputs` 累积。

通过动态编排调研（2026-03-12），确认 Spring AI Alibaba Agent Framework 的 StateGraph/FlowAgent 可以替代当前固定编排，且当前固定序列是 `SequentialAgent` 的特例。

本阶段（Phase 3.2）目标：用 StateGraph 替代 `runExecution()` 的 while 循环，获得自动状态传递、检查点恢复、原生流式输出能力，同时保持 DDD 边界不变。

---

## 核心设计决策

1. **Phase A 先行**：先用 SequentialAgent（或等价 StateGraph 线性链）替代 while 循环，行为完全等价，最小改动
2. **OverAllState 替代 previousOutputs**：节点间状态通过 `OverAllState` 的 `AppendStrategy("outputs")` 自动传递
3. **领域模型不变**：Execution / ExecutionStep / StepType 保持现有结构，StateGraph 作为 Infrastructure 内部实现
4. **ExecuteTaskUseCase 保持编排权**：Application 层仍然控制 plan → execute → complete/fail 流程，只是 execute 内部委托给 StateGraph
5. **事件桥接**：StateGraph 的 `GraphLifecycleListener` 桥接到现有的 `ExecutionEventPublisher`

---

## 迁移路线（三阶段渐进）

### Phase 3.2A: SequentialAgent 等价替换（本次实施）

**范围**: 用 StateGraph 线性链替代 `ExecuteTaskUseCase.runExecution()` 的 while 循环

**步骤**:

1. 创建 `GraphOrchestrator`（Infrastructure 层）
   - 接受 `List<PlannedStep>` + `taskDescription`
   - 构建 StateGraph: `START → step1 → step2 → ... → END`
   - 每个节点包装一个 `ReactAgentStepExecutor` 的调用
   - `OverAllState` 定义: `outputs`(APPEND), `taskDescription`(REPLACE), `currentStep`(REPLACE)
   - 注册 `GraphLifecycleListener` 桥接 SSE 事件

2. 定义 `GraphOrchestrationPort`（Application 层）
   - `Flux<StepResult> executeGraph(String taskDescription, List<PlannedStep> steps)`
   - 替代直接在 UseCase 中循环调用 `StepExecutorPort`

3. 重构 `ExecuteTaskUseCase.runExecution()`
   - 内部委托给 `GraphOrchestrationPort`
   - 消费 `Flux<StepResult>` 更新 Execution 聚合根状态
   - 事务边界不变：每个 step 完成后短事务保存

4. SSE 适配
   - `GraphLifecycleListener.before/after` → `ExecutionEvent.StepStarted/StepCompleted`
   - `StreamingOutput.chunk()` → `ExecutionEvent.StepLogAppended`

5. 添加 Checkpoint（MemorySaver 先行，JpaSaver 后续）
   - 每个节点执行完自动保存检查点
   - 为后续断点恢复奠定基础

**验证标准**:
- ✅ 行为与当前 while 循环完全等价
- ✅ 全部现有测试 GREEN（77 tests, 0 failures, 0 errors）
- ✅ DDD 边界检查通过（Domain/Application 零 StateGraph import）
- ✅ SSE 事件格式不变，前端无感

**实际实施差异**（详见 devlog 017）:
- 使用回调模式（StepProgressListener）替代计划中的 `Flux<StepResult>` 返回流，保持 SSE 实时性
- 未使用 `GraphLifecycleListener` 桥接，回调模式更简洁直接
- OverAllState 仅需 `taskDescription` + `outputs` 两个 key，简化了计划中的 4 key 设计

### Phase 3.2B: 条件路由 + 并行（后续）

- ✅ **Phase 3.2B-1**: THINK 输出决定是否跳过 RESEARCH（`addConditionalEdges` + `RoutingHintParser`）— 已完成
- ✅ **Phase 3.2B-2**: 多源 RESEARCH 并行执行（`addParallelConditionalEdges` + `MultiCommand` + 线程安全回调）— 已完成
- WRITE 后增加评审循环（LoopAgent）
- Human-in-the-loop: WRITE 前暂停等用户确认

### Phase 3.2C: LLM-Driven 编排（远期）

- `LlmRoutingAgent` / `SupervisorAgent` 让 LLM 决定编排
- `TaskPlannerPort` 输出 StateGraph 定义而非有序列表
- 当前固定序列退化为"预设模板"

---

## 关键文件清单（Phase 3.2A）

| 文件 | 操作 | 说明 |
|------|------|------|
| `application/execution/GraphOrchestrationPort.java` | **新建** | 图编排 Port 接口 |
| `application/execution/StepResult.java` | **新建** | 图编排步骤结果 record |
| `infrastructure/ai/GraphOrchestrator.java` | **新建** | StateGraph 构建与执行 |
| `infrastructure/ai/StepNodeAction.java` | **新建** | 将 ReactAgentStepExecutor 包装为 NodeAction |
| `infrastructure/ai/ExecutionEventBridge.java` | **新建** | GraphLifecycleListener → ExecutionEventPublisher |
| `application/execution/ExecuteTaskUseCase.java` | **重构** | runExecution() 委托给 GraphOrchestrationPort |
| `infrastructure/ai/StepExecutorRouter.java` | **保留** | 仍作为单步执行器，被 StepNodeAction 内部调用 |
| `domain/**` | **不动** | Domain 零改动 |

---

## 风险

| 风险 | 可能性 | 影响 | 缓解 |
|------|--------|------|------|
| StateGraph 执行内部隐式开启事务 | 低 | 违反 Rule 5 | 单测验证无事务上下文 |
| SSE 事件顺序变化 | 中 | 前端显示异常 | 对比现有事件流 snapshot |
| OverAllState 序列化问题 | 低 | Checkpoint 失败 | Phase 3.2A 先用 MemorySaver |
| FlowAgent API 版本不匹配 | 低 | 编译失败 | 已在 Phase 2 验证依赖兼容 |

---

## 与原 Phase 3.2 计划的变更

原 devlog 015 Next Steps 中的 Phase 3.2 计划为：
1. StateGraph 并行执行
2. SSE 适配层

**调整为**：
1. **Phase 3.2A**: SequentialAgent 等价替换（新增，优先级最高）
2. **Phase 3.2A 含 SSE 适配**（合并进来）
3. **Phase 3.2B**: 并行执行 + 条件路由（原 3.2 并行部分后移）
4. **Phase 3.3**: JpaSaver（不变）
5. **Phase 3.2C**: LLM-Driven 编排（新增远期目标）

**理由**: 先做等价替换获得基础设施（OverAllState + Checkpoint + 事件桥接），再在此基础上增加动态能力，风险更可控。
