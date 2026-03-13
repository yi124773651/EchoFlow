# 021 — Phase 3.3: End-to-End 冒烟测试

## Progress

- Web: `echoflow-web/pom.xml` 添加 Testcontainers + Awaitility 测试依赖 ✅
- Web: `application-smoke.yml` 测试 profile — dummy AI 配置 + Flyway enabled ✅
- Web: `AbstractSmokeTest` — `@SpringBootTest(RANDOM_PORT)` + Testcontainers PostgreSQL(pgvector) 基类 ✅
- Web: `EndToEndSmokeTest` — 4 个 E2E 场景（Happy Path / 条件跳过 / 步骤降级 / 致命失败）✅
- Infrastructure: `GraphOrchestrator` 公共构造函数添加 `@Autowired` — 修复多构造函数歧义 ✅

## DDD Decisions

- **测试边界选择**: Mock `TaskPlannerPort` 和 `StepExecutorPort`（Application 层 Port 接口），其余全部真实 Bean。这验证了 Web → Application → Infrastructure → DB 的完整布线，而不依赖外部 AI 服务。
- **GraphOrchestrator 保持真实**: 不 mock `GraphOrchestrationPort`，让真实的 StateGraph 编排在集成环境中运行。这是冒烟测试的核心价值所在。
- **Flyway 迁移验证**: 使用 `pgvector/pgvector:pg16` 镜像 + `jpa.hibernate.ddl-auto=validate`，确保迁移脚本在真实 PostgreSQL 上正确执行。

## Technical Notes

- **`@Autowired` 构造函数修复**: `GraphOrchestrator` 有两个构造函数（public + package-private）。Spring Framework 6.2 在发现多个构造函数且无 `@Autowired` 标注时，会回退到寻找无参构造函数。添加 `@Autowired` 到 public 构造函数解决了歧义。这是一个被冒烟测试发现的潜在生产 bug — 此前项目从未运行过 `@SpringBootTest` 级别的全上下文测试。
- **`@MockitoBean` (Spring Boot 3.5)**: 使用 `org.springframework.test.context.bean.override.mockito.MockitoBean` 替代已废弃的 `@MockBean`。`@MockitoBean` 在每个测试方法间自动重置 mock 状态，避免了 `@TestConfiguration` + `@Primary` 方案中 mock 状态在测试间泄漏的问题。
- **`pgvector/pgvector:pg16` 镜像**: V1 迁移脚本创建 `vector` 扩展（`CREATE EXTENSION IF NOT EXISTS vector`），标准 `postgres:16-alpine` 不包含 pgvector 扩展，必须使用专用镜像。
- **异步执行验证**: `TaskController.create()` 通过 `Thread.startVirtualThread()` 异步执行任务。测试使用 Awaitility 轮询 `GET /api/tasks/{id}` 等待终态（COMPLETED/FAILED），超时 15 秒。

## Test Summary

| Module          | New Tests | Total Tests |
|-----------------|-----------|-------------|
| Domain          | 0         | 43          |
| Application     | 0         | 12          |
| Infrastructure  | 0         | 143         |
| Web             | 4         | 4           |
| **Total**       | **4**     | **202**     |

New test methods:
- `full_pipeline_completes_with_all_steps` — THINK → RESEARCH → WRITE → NOTIFY 完整流水线
- `think_skips_research_when_routing_says_no` — THINK 路由提示跳过 RESEARCH
- `research_failure_degrades_but_pipeline_continues` — RESEARCH 失败降级，流水线继续
- `fatal_step_failure_marks_task_as_failed` — THINK 致命异常，任务标记 FAILED

## Next Steps

- Human-in-the-loop: WRITE 前暂停等用户确认（Phase 3.2B 剩余项）
- 评审 prompt 调优（按任务类型定制评审标准）
- Phase 3.2C: LLM-Driven 编排（远期）
