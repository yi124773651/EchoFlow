# Phase 3.2B-2: 并行 RESEARCH 执行

**创建时间**: 2026-03-12 22:00 CST
**完成时间**: 2026-03-13 00:35 CST
**状态**: ✅ 已完成
**关联 devlog**: `docs/devlog/019-phase3-2b2-parallel-research.md`

---

## 背景

Phase 3.2B-1 实现了条件路由：THINK 输出决定是否跳过 RESEARCH。但当有多个 RESEARCH 步骤时，它们仍然串行执行。这些独立的调研任务应该并行运行，显著减少执行时间。

## 目标拓扑变化

```
Phase 3.2B-1 (当前):
  THINK ─[conditional]─→ R1 → R2 → WRITE → END
              └──→ skip_R1 → skip_R2 → WRITE → END

Phase 3.2B-2 (目标):
  THINK ─[parallelConditional]─→ R1 ──┐
                                 R2 ──┤─→ WRITE → END
              └──→ skip_R1 → skip_R2 ─┘
```

## 核心设计决策

1. **StateGraph API**: 使用 `addParallelConditionalEdges` + `AsyncMultiCommandAction` + `MultiCommand` 实现并行扇出
2. **Domain**: 新增 `startStepByName(String)` 替代 `startNextStep()` 的串行假设
3. **线程安全**: 回调方法体用 `synchronized(execution)` 序列化域模型变更和 DB 保存
4. **Router 升级**: `ParallelResearchRouter` 返回 `MultiCommand`（并行 RUN keys 或单一 skip key）
5. **SKIP 路径**: 保持串行链（即时执行，无需并行）
6. **输出顺序**: WRITE 收到的 RESEARCH 输出顺序非确定，可接受

## 实施步骤

1. Domain — `Execution.startStepByName()` + 4 tests
2. Infrastructure — `ParallelResearchRouter` + 6 tests
3. Infrastructure — `GraphOrchestrator.buildConditionalGraph()` 并行化 + 4 new + 2 modified tests
4. Application — `ExecuteTaskUseCase` 线程安全回调 + 1 test
5. 清理 — 删除 `ResearchDecisionRouter` + tests
6. 全量测试验证

## 文件变更

| 文件 | 操作 |
|------|------|
| `domain/execution/Execution.java` | 修改 |
| `domain/execution/ExecutionTest.java` | 修改 |
| `infrastructure/ai/ParallelResearchRouter.java` | **新建** |
| `infrastructure/ai/ParallelResearchRouterTest.java` | **新建** |
| `infrastructure/ai/GraphOrchestrator.java` | 修改 |
| `infrastructure/ai/GraphOrchestratorTest.java` | 修改 |
| `application/execution/ExecuteTaskUseCase.java` | 修改 |
| `application/execution/ExecuteTaskUseCaseTest.java` | 修改 |
| `infrastructure/ai/ResearchDecisionRouter.java` | **删除** |
| `infrastructure/ai/ResearchDecisionRouterTest.java` | **删除** |

## 风险

| 风险 | 缓解 |
|------|------|
| StateGraph 并行节点死锁 | 框架自带测试已验证；节点无共享可变状态 |
| JPA save 并发 | `synchronized(execution)` 序列化回调内 save |
| 输出顺序非确定 | WRITE 提示模板聚合多源结果，顺序无关 |
