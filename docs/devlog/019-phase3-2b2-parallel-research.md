# 019 — Phase 3.2B-2: 并行 RESEARCH 执行

## Progress

- Domain: `Execution.startStepByName(String)` — 按名称启动步骤，支持并行启动 + 4 tests ✅
- Infrastructure: `ParallelResearchRouter` (MultiCommandAction) — 返回 MultiCommand 实现并行扇出 + 7 tests ✅
- Infrastructure: `GraphOrchestrator.buildConditionalGraph()` — `addParallelConditionalEdges` + 聚合 skip 节点 + 12 tests ✅
- Infrastructure: `ConditionalSkipNodeAction` 重构为聚合模式 — 单节点跳过所有 RESEARCH 步骤 + 4 tests ✅
- Application: `ExecuteTaskUseCase` 线程安全回调 — `synchronized(execution)` + `startStepByName` + `findStepByName` + 1 test ✅
- 清理: 删除旧 `ResearchDecisionRouter` + `ResearchDecisionRouterTest` ✅
- 全量测试: 119 tests, 0 failures, 0 errors (2 Docker/Testcontainers 跳过) ✅

## DDD Decisions

- **并行回调线程安全封装在 Application 层**: `ExecuteTaskUseCase` 的 `StepProgressListener` 回调用 `synchronized(execution)` 序列化所有域模型变更和 DB 保存。Infrastructure 层（StateGraph 并行节点）无需感知线程安全。
- **按名称查找步骤替代串行假设**: `startNextStep()` 假设步骤按顺序启动，并行场景下不成立。新增 `startStepByName(String)` 允许任意顺序启动，`findStepByName` 在后续回调中按名称定位步骤。
- **Domain 变更最小化**: 仅新增 `startStepByName`，不改变现有 `startNextStep` 语义（线性链仍可用）。

## Technical Notes

### 架构变更

```
Phase 3.2B-1:
  think ─[conditional]─→ R1 → R2 → write → END
              └──→ skip_R1 → skip_R2 → write → END

Phase 3.2B-2:
  think ─[parallelConditional]─→ R1 ──────────┐
                                 R2 ──────────┤─→ write → END
              └──→ skip_research ─────────────┘
```

### 关键 API

- `StateGraph.addParallelConditionalEdges(source, AsyncMultiCommandAction, routeMap)` — 并行条件边
- `AsyncMultiCommandAction.node_async(MultiCommandAction)` — 同步→异步包装
- `MultiCommand(List<String>)` — 返回多个路由 key 实现并行扇出
- `ConditionalParallelNode` — 框架内部节点，运行时只执行 MultiCommand 返回的 key 对应的节点

### 聚合 skip 节点设计

原设计为每个 RESEARCH 步骤创建独立 skip 节点并串行链接。但 `ConditionalParallelNode` 编译时 `findParallelNodeTargets` 要求 routeMap 中所有 value 节点的出边指向同一个 convergence 点。串行 skip 链的中间节点出边指向下一个 skip 节点而非 convergence 点，导致框架行为不确定。

解决方案：单一 `skip_research` 聚合节点，内部循环跳过所有 RESEARCH 步骤，出边直接指向 convergence 点。

### 线程安全模型

```
并行 RESEARCH 回调:
  Thread-1: onStepStarting("搜索1") → onStepCompleted("搜索1")
  Thread-2: onStepStarting("搜索2") → onStepCompleted("搜索2")

ExecuteTaskUseCase 回调体:
  synchronized(execution) {
      execution.startStepByName(stepName);  // 域模型变更
      executionRepository.save(execution);   // DB 保存
      eventPublisher.publish(...);           // SSE 事件
  }
```

### 与计划的差异

| 计划 | 实际 | 原因 |
|------|------|------|
| 每个 RESEARCH 独立 skip 节点 + 串行链 | 单一聚合 skip 节点 | `ConditionalParallelNode` 要求所有 routeMap value 出边指向同一 convergence |
| `ResearchDecisionRouter` (EdgeAction) | `ParallelResearchRouter` (MultiCommandAction) | 并行扇出需要 MultiCommand 而非单一路由 key |

## Next Steps

1. Phase 3.2B-3: WRITE 评审循环（LoopAgent / 后向边）
2. Phase 3.2B-4: Human-in-the-loop（interruptBefore + 前端集成）
3. Phase 3.3: JpaSaver 替代 MemorySaver（持久化检查点）
