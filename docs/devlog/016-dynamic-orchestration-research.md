# 016 — 动态编排调研与方向决策

## Progress

- 动态编排可行性调研完成 ✅
- 确认当前固定序列（THINK → RESEARCH → WRITE → NOTIFY）是 `SequentialAgent` 的特例 ✅
- 确认 Spring AI Alibaba 的 StateGraph/FlowAgent 可替代 `ExecuteTaskUseCase.runExecution()` 的 while 循环 ✅
- 确认 `OverAllState` + `KeyStrategy` 替代手动 `previousOutputs` 的可行性 ✅
- 调研文档归档: `docs/research/dynamic-orchestration-research.md` ✅
- Phase 3.2 计划重新调整: `docs/plans/2026-03-12-0120-phase3-2-dynamic-orchestration.md` ✅

## DDD Decisions

- **方案 A 继续延续**: StateGraph/FlowAgent 封装在 Infrastructure 层，通过新的 `GraphOrchestrationPort` 接口暴露给 Application 层。Domain 模型（Execution / ExecutionStep / StepType）零改动。
- **OverAllState ↔ Domain 映射**: OverAllState 是 Infrastructure 内部类型，不泄漏到 Application。通过 `GraphOrchestrator` 内部完成 OverAllState → `StepResult` record 的转换。
- **StepExecutorPort 保留**: 作为单步执行器接口保留，被 StateGraph 节点内部调用。新增 `GraphOrchestrationPort` 负责整体编排。

## Technical Notes

### 编排模式对照

| Anthropic 模式 | Spring AI Alibaba | EchoFlow 当前 | EchoFlow 演化方向 |
|---------------|-------------------|-------------|------------------|
| Prompt Chaining | `SequentialAgent` | while 循环 + previousOutputs | Phase 3.2A |
| Routing | `LlmRoutingAgent` | 无 | Phase 3.2B |
| Parallelization | `ParallelAgent` | 无 | Phase 3.2B |
| Evaluator-Optimizer | `LoopAgent` | 无 | Phase 3.2B |
| Orchestrator-Workers | `SupervisorAgent` | 无 | Phase 3.2C |

### OverAllState 状态设计（预设）

```java
KeyStrategy.builder()
    .addKey("taskDescription", KeyStrategy.REPLACE)
    .addKey("outputs", KeyStrategy.APPEND)       // 替代 previousOutputs
    .addKey("currentStep", KeyStrategy.REPLACE)
    .addKey("messages", KeyStrategy.APPEND)       // agent 消息历史
    .build();
```

### Phase 3.2 路线调整

原 devlog 015 Next Steps:
1. Phase 3.2: StateGraph 并行执行
2. Phase 3.2: SSE 适配层
3. Phase 3.3: JpaSaver

调整后:
1. **Phase 3.2A**: SequentialAgent 等价替换 + SSE 适配（新增，最高优先级）
2. **Phase 3.2B**: 条件路由 + 并行执行 + 评审循环（扩展）
3. **Phase 3.2C**: LLM-Driven 编排（新增远期目标）
4. **Phase 3.3**: JpaSaver（不变）

### 调研参考

- Spring AI Alibaba Agent Framework 源码解析（`spring-ai-alibaba-agent-framework-源码讲解.md`）
- Spring AI Alibaba Graph Core 源码解析（`spring-ai-alibaba-graph-core-源码解析.md`）
- Anthropic "Building Effective Agents" — 5 种 workflow 模式
- Grok Search 调研结果: FlowAgent 系列（SequentialAgent / ParallelAgent / LlmRoutingAgent / LoopAgent / SupervisorAgent）均构建在 StateGraph 之上

## Next Steps

1. ~~进入 Phase 3.2A 实施: 创建 `GraphOrchestrator` + `GraphOrchestrationPort`~~ ✅ 完成（devlog 017）
2. ~~实现 StateGraph 线性链，替代 `ExecuteTaskUseCase.runExecution()` 的 while 循环~~ ✅ 完成
3. ~~实现 `ExecutionEventBridge`（GraphLifecycleListener → SSE 事件）~~ → 改用 StepProgressListener 回调模式 ✅
4. ~~全量测试验证行为等价~~ ✅ 77 tests GREEN
5. Phase 3.2B: 条件路由 + 并行执行
6. Phase 3.3: JpaSaver 持久化检查点
