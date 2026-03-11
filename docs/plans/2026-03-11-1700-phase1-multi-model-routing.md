# Phase 1: 多模型路由层

> 创建时间: 2026-03-11 17:00 CST
> 完成时间: 2026-03-11 18:00 CST
> 状态: ✅ 已完成
> 关联 devlog: `docs/devlog/012-multi-model-routing.md`

## Context

当前 EchoFlow 所有 LLM 调用（TaskPlanner + 4 种 StepExecutor）共享同一个 ChatClient 实例，使用单一模型（由 `spring.ai.openai.*` 配置）。Phase 1 的目标是支持按 StepType 路由到不同模型（THINK→强模型，RESEARCH/NOTIFY→快模型），并在主模型失败时自动 fallback。

## 核心设计决策

### 1. 不新增 `ModelRouterPort`（偏离 overall-plan.md）

overall-plan.md 建议在 Application 层新增 `ModelRouterPort`，但 Phase 1 仅需按 `StepType` 路由。`StepType` 已通过 `StepExecutionContext` 传入 Infrastructure，路由决策属于"选哪个基础设施适配器"，不是业务逻辑。新增一个 Application 层永远不直接调用的 Port 是过度抽象。**若未来需要用户偏好选模型，再引入 Port。**

### 2. 使用 Spring AI 1.1.2 `mutate()` API 创建多 ChatClient

已确认 `OpenAiApi.mutate()` 和 `OpenAiChatModel.mutate()` 在 v1.1.2 中可用（官方文档有 multi-provider 示例）。所有目标 Provider（DashScope、OpenAI、DeepSeek）均支持 OpenAI 兼容协议，无需引入 DashScope 原生 starter。

### 3. ChatClient 改为按调用传入，不再按构造函数绑定

将 `LlmStepExecutor` 的 `chatClient` 从构造函数字段改为 `execute()`/`callLlm()` 的方法参数，使 `StepExecutorRouter` 能按 StepType 传入不同 ChatClient。

### 4. 两级容错：同模型重试 + 跨模型 fallback

现有：`LlmStepExecutor.execute()` 内 2 次重试（同一模型）。
新增：`StepExecutorRouter.execute()` 捕获 `StepExecutionException`，用 fallback ChatClient 再调一轮（含自己的 2 次重试）。

## 改动范围

| 层 | 文件 | 操作 |
|----|------|------|
| Domain | — | **无改动** |
| Application | — | **无改动** |
| Infrastructure | `MultiModelProperties.java` | **新增** — `@ConfigurationProperties` record |
| Infrastructure | `ChatClientProvider.java` | **新增** — 创建/缓存多 ChatClient 实例 |
| Infrastructure | `LlmStepExecutor.java` | **修改** — chatClient 改为方法参数 |
| Infrastructure | `LlmThinkExecutor.java` | **修改** — 适配新签名 |
| Infrastructure | `LlmResearchExecutor.java` | **修改** — 适配新签名 |
| Infrastructure | `LlmWriteExecutor.java` | **修改** — 适配新签名 |
| Infrastructure | `LlmNotifyExecutor.java` | **修改** — 适配新签名 |
| Infrastructure | `StepExecutorRouter.java` | **修改** — 注入 ChatClientProvider，按 StepType 路由 + fallback |
| Infrastructure | `AiTaskPlanner.java` | **修改** — 注入 ChatClientProvider 替代 ChatClient.Builder |
| Web | `AiClientConfig.java` | **修改** — 添加 `@EnableConfigurationProperties` |
| Web | `application.yml` | **修改** — 添加 `echoflow.ai.routing` 和 `echoflow.ai.models` |
| Test | `StepExecutorRouterTest.java` | **修改** — mock 改为 ChatClientProvider，新增路由/fallback 测试 |
| Test | `ChatClientProviderTest.java` | **新增** — 测试模型解析逻辑 |

## 实施顺序 (TDD)

| # | 任务 | 新增/修改文件 |
|---|------|--------------|
| 1 | 新增 `MultiModelProperties` record | `infrastructure/.../ai/MultiModelProperties.java` |
| 2 | 新增 `ChatClientProvider` + 单元测试 | `ChatClientProvider.java`, `ChatClientProviderTest.java` |
| 3 | 重构 `LlmStepExecutor` — ChatClient 改为方法参数 | `LlmStepExecutor.java` |
| 4 | 适配 4 个 Executor 子类 | `LlmThinkExecutor.java`, `LlmResearchExecutor.java`, `LlmWriteExecutor.java`, `LlmNotifyExecutor.java` |
| 5 | 修改 `StepExecutorRouter` — 路由 + fallback | `StepExecutorRouter.java` |
| 6 | 更新 `StepExecutorRouterTest` — 新 mock + 路由/fallback 测试 | `StepExecutorRouterTest.java` |
| 7 | 修改 `AiTaskPlanner` | `AiTaskPlanner.java` |
| 8 | 更新 `application.yml` + `AiClientConfig` | `application.yml`, `AiClientConfig.java` |
| 9 | 全量构建验证 | `./mvnw clean install -pl echoflow-backend -am` |
| 10 | 更新 overall-plan.md + 写 devlog | `docs/` |

## 验收标准

- [ ] `./mvnw clean install -pl echoflow-backend -am` — 全部单元测试 GREEN
- [ ] 新测试覆盖路由 + fallback 行为
- [ ] 空配置下行为与当前一致（向后兼容）

## 不做的事项（显式延迟）

- Application 层 `ModelRouterPort`（等用户偏好需求再引入）
- 前端模型偏好下拉
- DashScope 原生 starter
- 每模型独立超时配置
- 指标/可观测性（仅日志）
