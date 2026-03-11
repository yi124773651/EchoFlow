# Phase 3.1: RESEARCH/WRITE/NOTIFY 全量迁移到 ReactAgent

**创建时间**: 2026-03-11 23:55 CST
**完成时间**: 2026-03-12 00:30 CST
**状态**: ✅ 已完成
**关联 devlog**: `docs/devlog/015-reactagent-full-migration.md`

---

## 背景

Phase 2 POC 已完成，确认 Go 决策和方案 A（领域模型为主）。POC 中仅将 THINK 步骤迁移到 ReactAgent，RESEARCH/WRITE/NOTIFY 仍走旧的 `LlmStepExecutor` → `ChatClient` 路径。Phase 3.1 将剩余三个执行器统一迁移到 ReactAgent，获得 Hook/Interceptor 能力，并保留旧路径作为 fallback。

StateGraph 并行、SSE 适配、JpaSaver 不在本次范围内（Phase 3.2+）。

---

## 核心设计决策

1. **ReactAgent per-call 构建**：每次 `execute()` 新建 ReactAgent，避免 `ModelCallLimitHook` 计数累积和内存状态泄漏。LLM 调用本身远慢于对象构建，开销可忽略。

2. **提取公共基类 `ReactAgentStepExecutor`**：统一 retry 循环、输出校验、截断逻辑。当前 `ReactAgentThinkExecutor` 与 `LlmStepExecutor` 存在大量重复代码。

3. **Prompt 处理**：读取现有 `.st` 模板，手动替换 `{taskDescription}`/`{stepName}`/`{previousContext}` 占位符，整体作为 user message 传入 `agent.call()`。不设 `instruction()`，保持与旧路径行为一致。

4. **Tools 注册**：通过 ReactAgent `.methodTools()` 注册（RESEARCH: `GitHubSearchTool`，NOTIFY: `WebhookNotifyTool`），替代旧路径的 `chatClient.prompt().tools()`。

5. **Hook/Interceptor 配置**：
   - 所有 executor: `ModelCallLimitHook(runLimit=5)` + `MessageTrimmingHook(maxMessages=20)`
   - 带 Tool 的 executor (RESEARCH/NOTIFY): 额外加 `ToolRetryInterceptor(maxRetries=2)`

6. **Fallback 保留**：ReactAgent 失败 → 退化到对应 `LlmXxxExecutor` + `fallbackClient`。`LlmStepExecutor` 及其子类不做修改。

7. **Application/Domain 零改动**：`StepExecutorPort` 接口不变，所有变更限定在 Infrastructure 层。

---

## 实施步骤

### Step 1: 创建 `ReactAgentStepExecutor` 基类 ✅
### Step 2: 重构 `ReactAgentThinkExecutor` 继承基类 ✅
### Step 3: 创建 `ReactAgentResearchExecutor` ✅
### Step 4: 创建 `ReactAgentWriteExecutor` ✅
### Step 5: 创建 `ReactAgentNotifyExecutor` ✅
### Step 6: 重构 `StepExecutorRouter` — 统一 ReactAgent 路由 ✅
### Step 7: 更新测试 ✅
### Step 8: 编译 & 全量测试 ✅ (64 tests GREEN)
### Step 9: DDD 边界检查 ✅ (Domain/Application 零 AI 框架 import)

---

## 关键文件清单

| 文件 | 操作 |
|------|------|
| `infrastructure/ai/ReactAgentStepExecutor.java` | **新建** — 抽象基类 |
| `infrastructure/ai/ReactAgentThinkExecutor.java` | **重构** — 继承基类 |
| `infrastructure/ai/ReactAgentResearchExecutor.java` | **新建** |
| `infrastructure/ai/ReactAgentWriteExecutor.java` | **新建** |
| `infrastructure/ai/ReactAgentNotifyExecutor.java` | **新建** |
| `infrastructure/ai/StepExecutorRouter.java` | **重构** — 统一 ReactAgent 路由 |
| `test/.../ReactAgentStepExecutorTest.java` | **新建** — 13 tests |
| `test/.../ReactAgentThinkExecutorTest.java` | **重构** — 6 tests |
| `test/.../StepExecutorRouterTest.java` | **重构** — 12 tests |
| `infrastructure/ai/LlmStepExecutor.java` | **不动** — 作为 fallback |
| `infrastructure/ai/Llm*Executor.java` | **不动** — 作为 fallback |
| `application/execution/StepExecutorPort.java` | **不动** |
| `domain/**` | **不动** |

---

## 验证结果

1. **编译**: `./mvnw compile -pl echoflow-backend -am` — ✅ 零 error
2. **单元测试**: 64 tests GREEN（排除 2 个 Testcontainers 测试需 Docker 环境）
3. **DDD 边界检查**: `grep` 搜索 `com.alibaba.cloud.ai.graph`、`com.alibaba.cloud.ai`、`org.springframework.ai`、`ReactAgent` — 在 echoflow-domain 和 echoflow-application 中均返回空
4. **测试数量**: 从 55 增至 64 (+9 净增)
