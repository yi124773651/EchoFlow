# 018 — Phase 3.2B-1: 条件路由 — THINK 输出决定是否跳过 RESEARCH

## Progress

- `RoutingHint` record + `RoutingHintParser` 正则解析器 ✅
- `ResearchDecisionRouter` (EdgeAction 实现) — 从 OverAllState 读 THINK 输出，解析路由提示 ✅
- `ConditionalSkipNodeAction` (AsyncNodeAction 实现) — 复用 listener 回调跳过步骤 ✅
- `GraphOrchestrator` 重构：检测 THINK→RESEARCH 模式 → addConditionalEdges 条件路由 ✅
- `step-think.st` 提示模板追加 `[ROUTING]` 路由提示指令 ✅
- 新增测试 35 个：RoutingHintParserTest(11) + ResearchDecisionRouterTest(5) + ConditionalSkipNodeActionTest(4) + GraphOrchestratorTest 扩展(15) ✅
- 全量测试：112 tests, 0 failures, 0 errors, BUILD SUCCESS ✅
- DDD 边界检查：Application/Domain 层零 `com.alibaba.cloud.ai` import ✅

## DDD Decisions

- **条件路由完全封装在 Infrastructure**: `ResearchDecisionRouter`、`ConditionalSkipNodeAction`、`RoutingHintParser` 均为 package-private，Application 层通过 `GraphOrchestrationPort` 接口零感知。
- **零 Application/Domain 变更**: `GraphOrchestrationPort` 接口不变，`ExecuteTaskUseCase` 不变，Domain 模型不变。条件跳过复用已有的 `onStepStarting` + `onStepSkipped` 回调路径（与 degradation 完全一致）。
- **安全默认策略**: 所有解析失败（null、空、无 `[ROUTING]` 块、非法值）均默认执行 RESEARCH (`needsResearch=true`)。在提示模板中也强调 "When in doubt, answer YES"。
- **EdgeAction 而非 Function**: `ResearchDecisionRouter` 实现 `com.alibaba.cloud.ai.graph.action.EdgeAction` 而非 `Function<OverAllState, String>`，因 `addConditionalEdges` 需通过 `AsyncEdgeAction.edge_async()` 包装。

## Technical Notes

### 架构变更

```
Phase 3.2A:
  START → step_1 → step_2 → ... → END (线性链)

Phase 3.2B-1:
  START → think ─[addConditionalEdges]─→ research → write → ... → END
                       └──→ skip_research → write → ... → END
  (无 THINK→RESEARCH 模式时回退到线性链)
```

### 新增组件

| 组件 | 职责 | 层 |
|------|------|-----|
| `RoutingHint` | 路由决策值对象 (record) | Infrastructure |
| `RoutingHintParser` | 正则解析 `[ROUTING]` 块 | Infrastructure |
| `ResearchDecisionRouter` | EdgeAction 路由函数 | Infrastructure |
| `ConditionalSkipNodeAction` | 条件跳过节点动作 | Infrastructure |

### GraphOrchestrator 模式检测

`findResearchRange()` 检测步骤列表中 THINK→RESEARCH 模式：
1. 找第一个 THINK 步骤
2. 找 THINK 之后连续的 RESEARCH 步骤范围
3. 无模式 → 返回 null → 回退到线性链

### 条件图拓扑 (多个连续 RESEARCH)

```
think ─[conditional]─→ R1 → R2 → write → ... → END
            └──→ skip_R1 → skip_R2 → write → ... → END
```

两路径在第一个 post-RESEARCH 节点汇合 (fan-in)。

### API 发现

- `AsyncEdgeAction.edge_async(EdgeAction)` 将同步 `EdgeAction` 包装为 `AsyncEdgeAction`
- `StateGraph.addConditionalEdges(sourceNode, AsyncEdgeAction, Map<String, String>)` — 第三参数为路由键到节点 ID 的映射
- Fan-in（多条边指向同一节点）在 StateGraph 中正常工作

### 与计划的差异

| 计划 | 实际 | 原因 |
|------|------|------|
| `Function<OverAllState, String>` | `EdgeAction` 接口 | `AsyncEdgeAction.edge_async()` 需要 `EdgeAction` 而非 `Function` |

## Next Steps

1. Phase 3.2B-2: 并行 RESEARCH 执行（`addEdges` + Domain 模型并发安全改造）
2. Phase 3.2B-3: WRITE 评审循环（LoopAgent / 后向边）
3. Phase 3.2B-4: Human-in-the-loop（interruptBefore + 前端集成）
4. Phase 3.3: JpaSaver 替代 MemorySaver（持久化检查点）
