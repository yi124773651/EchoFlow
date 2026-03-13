# 020 — Phase 3.2B-3: WRITE 评审循环（Evaluator-Optimizer 模式）

## Progress

- Application: `StepProgressListener.onStepProgress(stepName, logType, content)` — 新增 default 方法支持循环内中间日志 ✅
- Application: `ExecuteTaskUseCase` 实现 `onStepProgress` 回调，同步追加日志并发布 SSE 事件 ✅ (1 test)
- Infrastructure: `ReviewResult` record — 评审结果值对象（score, approved, feedback）✅
- Infrastructure: `ReviewResultParser` — 解析 LLM 输出中的 `[REVIEW]` 块 ✅ (8 tests)
- Infrastructure: `LlmWriteReviewer` — LLM-as-Judge 评估器，失败时安全降级为 DEFAULT_APPROVED ✅ (3 tests)
- Infrastructure: `ReviewableWriteNodeAction` — WRITE 节点动作，延迟 `onStepCompleted` 到评审门控 ✅ (3 tests)
- Infrastructure: `WriteReviewGateAction` — 评审门控节点，路由 approve/revise ✅ (4 tests)
- Infrastructure: `WriteReviseAction` — 修订节点，构建 `[REVISION FEEDBACK]` 上下文并重新执行 WRITE ✅ (2 tests)
- Infrastructure: `GraphOrchestrator` — 集成评审循环（后向边 + 条件路由）✅ (6 new tests, 25 existing pass)
- Web: `WriteReviewProperties` `@ConfigurationProperties` + `WriteReviewConfig` 条件化 Bean + `review-write.st` prompt 模板 ✅
- Web: `application.yml` 新增 `echoflow.review.*` 配置段（默认 disabled）✅

## DDD Decisions

- **Zero Domain Changes**: 整个评审循环完全在 Infrastructure 层实现，Domain 层没有任何改动。`ReviewResult`、`ReviewResultParser`、`LlmWriteReviewer` 等全部 package-private。
- **StepProgressListener 扩展**: 在 Application Port 接口中新增 `onStepProgress` default 方法（不破坏现有实现），为循环内中间日志提供通道。这是唯一的 Application 层变更。
- **Deferred Completion 模式**: `ReviewableWriteNodeAction` 调用 `onStepStarting` 但不调用 `onStepCompleted`，由 `WriteReviewGateAction` 在审批通过后负责调用。这保持了 Domain `ExecutionStep` 生命周期的正确性：每个步骤只有一次 complete。
- **REPLACE vs APPEND 策略**: 评审循环使用 `REPLACE` 策略的独立状态键（`writeOutput`, `writeAttempts`, `reviewDecision`, `reviewFeedback`），避免 `APPEND` 策略导致无界状态增长。只有最终审批通过的输出才通过 `outputs` 键（APPEND）累积。

## Technical Notes

- **StateGraph 后向边**: 通过 `graph.addEdge(REVISE_NODE_ID, REVIEW_GATE_ID)` 创建从 `revise_write` 到 `review_gate` 的后向边，配合 `graph.addConditionalEdges(REVIEW_GATE_ID, ...)` 形成图循环。这与 Spring AI Alibaba `LoopGraphBuildingStrategy` 内部实现一致。
- **`AsyncEdgeAction.edge_async`**: 条件路由函数读取 `reviewDecision` 状态键返回 `"approve"` 或 `"revise"` 路由键。
- **安全降级链**: LLM 失败 → `ReviewResult.DEFAULT_APPROVED` → 解析失败 → `DEFAULT_APPROVED` → WRITE 抛异常 → `reviewDecision="approve"` 跳过评审 → maxAttempts 达到 → 强制 approve。任何环节失败都不会阻塞流水线。
- **Spring 注入**: `GraphOrchestrator` 公共构造函数通过 `ObjectProvider<LlmWriteReviewer>` 实现可选注入。`WriteReviewConfig` 使用 `@ConditionalOnProperty(prefix="echoflow.review", name="enabled", havingValue="true")` 条件化创建。
- **Java 21**: 无新的 Java 21 特性使用（已有的 record、switch expression、pattern matching 延续前几期风格）。

## Graph Topology

```
LINEAR (review enabled):
START → THINK → WRITE → review_gate ─[approve]─→ NOTIFY → END
                            │                        ↑
                            └─[revise]─→ revise_write ┘

CONDITIONAL (review enabled):
START → THINK ─[parallel]─→ R1 ──┐
                            R2 ──┤─→ WRITE → review_gate ─[approve]─→ NOTIFY → END
          └──→ skip_research ────┘       │                       ↑
                                         └─[revise]─→ revise_write ┘
```

## Test Summary

| Module          | New Tests | Total Tests |
|-----------------|-----------|-------------|
| Domain          | 0         | 43          |
| Application     | 1         | 12          |
| Infrastructure  | 26        | 143         |
| **Total**       | **27**    | **198**     |

New test classes:
- `ReviewResultParserTest` (8)
- `LlmWriteReviewerTest` (3)
- `ReviewableWriteNodeActionTest` (3)
- `WriteReviewGateActionTest` (4)
- `WriteReviseActionTest` (2)
- `GraphOrchestratorTest.WriteReviewLoop` (6)
- `ExecuteTaskUseCaseTest.execute_onStepProgress_*` (1)

## Next Steps

- Phase 3.3: End-to-end 冒烟测试 — 验证完整 THINK → RESEARCH → WRITE(review) → NOTIFY 流水线
- 考虑评审 prompt 调优（当前是通用评审，后续可按任务类型定制）
- 考虑评审历史持久化（当前仅通过 StepLog 记录，后续可能需要结构化存储）
