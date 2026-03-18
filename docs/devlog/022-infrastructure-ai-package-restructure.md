# 022 — Infrastructure AI 包结构重构

## Progress

- 将 `infrastructure/ai/` 平铺的 25 个源文件 + 17 个测试文件按职责拆分为 5 个子包 ✅
- 更新所有包声明、跨包 import、可见性修饰符 ✅
- 清理 POC 测试文件（`HookInterceptorPocTest`、`SseIntegrationPocTest`）✅
- 清理生产代码中残留的 "POC-3" Javadoc 前缀 ✅
- 测试统计：207 个测试全部通过，0 失败

## DDD Decisions

### 子包划分原则

按**单一职责**将原 `com.echoflow.infrastructure.ai` 拆分为：

| 子包 | 职责 | 文件数（源/测试） |
|------|------|------------------|
| `config/` | 多模型配置、ChatClient 提供、Review 配置 | 4 / 1 |
| `executor/` | 步骤执行器（ReactAgent 主路径 + LLM fallback 路径）+ Hook/拦截器 | 13 / 3 |
| `graph/` | StateGraph 编排、节点 Action、Review 循环 | 12 / 10 |
| `planner/` | LLM 驱动的任务规划 | 1 / 0 |
| `tool/` | 外部工具（GitHub 搜索、Webhook 通知） | 2 / 2 |

### 可见性变更

拆包后需跨包访问的类从 package-private 改为 `public`：

| 类 | 原因 |
|----|------|
| `GitHubSearchTool` | executor/ 包的 `StepExecutorRouter` 构造时使用 |
| `WebhookNotifyTool` | executor/ 包的 `StepExecutorRouter` 构造时使用 |
| `LlmWriteReviewer` | config/ 包的 `WriteReviewConfig` 创建 @Bean |
| `WriteReviewConfig` | @Configuration 类应为 public |
| `ChatClientProvider.resolve()` / `.defaultClient()` | executor/ 和 planner/ 跨包调用 |

其余 package-private 类**均在各自子包内部**被引用，无需变更，保持了最小暴露面。

### MessageTrimmingHook / ToolRetryInterceptor 归入 executor/

这两个类仅被 `ReactAgentStepExecutor`（基类注册 hook）和 `ReactAgentResearchExecutor`/`ReactAgentNotifyExecutor`（注册 interceptor）使用，放入 executor/ 包可保持 package-private，无需暴露。

## Technical Notes

### 拆包后的结构

```
infrastructure/ai/
├── config/          ChatClientProvider, MultiModelProperties,
│                    WriteReviewConfig, WriteReviewProperties
├── executor/        StepExecutorRouter (public @Component),
│                    ReactAgent{Step,Think,Research,Write,Notify}Executor,
│                    Llm{Step,Think,Research,Write,Notify}Executor,
│                    MessageTrimmingHook, ToolRetryInterceptor
├── graph/           GraphOrchestrator (public @Component),
│                    StepNodeAction, ConditionalSkipNodeAction,
│                    ParallelResearchRouter, RoutingHint, RoutingHintParser,
│                    ReviewableWriteNodeAction, WriteReviewGateAction,
│                    WriteReviseAction, LlmWriteReviewer,
│                    ReviewResult, ReviewResultParser
├── planner/         AiTaskPlanner (public @Component)
└── tool/            GitHubSearchTool, WebhookNotifyTool
```

### 清理的 POC 代码

| 文件 | 原因 |
|------|------|
| `HookInterceptorPocTest` | POC-3 阶段验证框架机制，测试内容与 `ReactAgentStepExecutorTest` 重叠 |
| `SseIntegrationPocTest` | POC-5 阶段验证 `ReactAgent.stream()`，不测试生产代码 |

### Spring Component Scan

`@SpringBootApplication` 默认扫描 `com.echoflow` 及其所有子包，子包拆分不影响 Bean 发现。`@ComponentScan(basePackages = "com.echoflow")` 显式声明在 `EchoFlowApplication` 中，覆盖所有新子包。

## Next Steps

- ~~更新 CLAUDE.md 中 Infrastructure 模块的包结构描述~~ ✅ 已完成
- ~~考虑 `executor/` 中 LLM fallback 路径是否仍有必要保留（目前作为降级策略仍在使用）~~ ✅ 已评估，决策：保留（见 devlog 023）
