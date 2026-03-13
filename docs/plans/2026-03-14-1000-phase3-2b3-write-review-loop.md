# Phase 3.2B-3: WRITE 评审循环（StateGraph 后向边 + LLM-as-Judge）

**创建时间**: 2026-03-14 10:00 CST
**完成时间**: 2026-03-14 02:40 CST
**状态**: ✅ 已完成
**关联 devlog**: `docs/devlog/020-phase3-2b3-write-review-loop.md`

---

## Context

Phase 3.2B-2 完成了 RESEARCH 步骤的并行扇出。下一步是在 WRITE 步骤之后引入**评审循环**（Evaluator-Optimizer 模式）：WRITE 产出初稿后，由独立的 LLM-as-Judge 评估质量；若未达标，自动修订并重新评审，直到通过或达到最大尝试次数。

这通过 StateGraph **条件后向边**（backward edge）实现图级循环，与 LoopAgent 内部使用的 `addConditionalEdges` 循环机制相同（见 `LoopGraphBuildingStrategy.buildCoreGraph()`），但直接在 EchoFlow 的 StateGraph 上操作，保持与现有回调模型的兼容。

## 目标拓扑变化

```
Phase 3.2B-2（当前）:
  ... → WRITE → NOTIFY → END

Phase 3.2B-3（目标）:
  ... → WRITE → review_gate ─[conditional]─→ revise_write → review_gate（后向边循环）
                              └─→ NOTIFY → END
```

## 核心设计决策

1. **StateGraph 后向边（非 LoopAgent）**: 直接用 `addConditionalEdges` 创建循环
2. **LLM-as-Judge 评估**: 独立 LLM 调用 + 专用 prompt（`review-write.st`）
3. **无新 StepType**: Domain 零改动
4. **延迟完成**: WRITE 节点不调用 `onStepCompleted`；`review_gate` 审批通过时调用
5. **可配置**: `echoflow.review.*` 属性控制
6. **优雅降级**: LLM 异常自动 approve

## TDD 实施步骤

1. Application — StepProgressListener.onStepProgress + ExecuteTaskUseCase
2. Infrastructure — ReviewResultParser + ReviewResult
3. Infrastructure — LlmWriteReviewer
4. Infrastructure — ReviewableWriteNodeAction
5. Infrastructure — WriteReviewGateAction
6. Infrastructure — WriteReviseAction
7. Infrastructure — GraphOrchestrator 评审循环
8. Web — review-write.st + WriteReviewProperties + 配置
9. Application — ExecuteTaskUseCase.onStepProgress 集成测试
10. 全量测试
