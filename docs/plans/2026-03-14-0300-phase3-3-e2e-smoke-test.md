# Phase 3.3: End-to-End 冒烟测试

**创建时间**: 2026-03-14 03:00 CST
**完成时间**: 2026-03-14 03:45 CST
**状态**: ✅ 已完成
**关联 devlog**: `docs/devlog/021-phase3-3-e2e-smoke-test.md`

---

## 目标

用 `@SpringBootTest` + Testcontainers PostgreSQL 验证完整的层间穿透：
Web → Application → Infrastructure(real StateGraph) → mocked AI → DB → HTTP GET 验证最终状态。

## Mocking 策略

Mock 两个 Port 接口（`TaskPlannerPort`, `StepExecutorPort`），其余全部真实 Bean。

## 测试场景

1. Happy Path — THINK → RESEARCH → WRITE → NOTIFY 完整流水线
2. 条件跳过 — THINK 路由提示跳过 RESEARCH
3. 步骤降级 — RESEARCH 失败降级，流水线继续
4. 致命失败 — THINK 致命异常，任务标记 FAILED

## 实施结果

- 4 个新测试全部 GREEN
- 发现并修复 `GraphOrchestrator` 多构造函数歧义（添加 `@Autowired`）
- 全部 202 测试通过，零回归
