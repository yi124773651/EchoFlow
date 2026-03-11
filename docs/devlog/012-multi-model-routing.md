# 012 — Phase 1: 多模型路由层

## Progress

- 多模型路由层实现完成
- `MultiModelProperties` — 配置 record，支持多 Provider 模型定义 + StepType 路由映射
- `ChatClientProvider` — 利用 Spring AI 1.1.2 `mutate()` API 创建/缓存多 ChatClient 实例
- `LlmStepExecutor` 重构 — ChatClient 从构造函数字段改为 `execute()`/`callLlm()` 方法参数
- `StepExecutorRouter` 改造 — 按 StepType 路由到不同 ChatClient + 跨模型 fallback
- `AiTaskPlanner` 改造 — 使用 ChatClientProvider 解析模型
- 81 个单元测试全部 GREEN（Domain 39 + Application 11 + Infrastructure 31）
- 新增 12 个测试（ChatClientProviderTest 8 + StepExecutorRouterTest 路由/fallback 4）
- Testcontainers 集成测试因当前环境无 Docker 未执行（与 Phase 0 一致，非本次改动问题）

## DDD Decisions

- **不引入 `ModelRouterPort`** — 按 StepType 路由是纯基础设施关注点，StepType 已通过 `StepExecutionContext` 传入。新增一个 Application 层不直接调用的 Port 是过度抽象。若未来需要用户偏好选模型，再引入。
- **Domain 层零改动** — 多模型路由完全封装在 Infrastructure 层
- **Application 层零改动** — `StepExecutorPort` / `TaskPlannerPort` 接口不变，`ExecuteTaskUseCase` 无感知
- `MultiModelProperties` 放在 Infrastructure 层（消费者），`@EnableConfigurationProperties` 在 Web 层注册

## Technical Notes

### Spring AI 1.1.2 `mutate()` API

利用官方 `OpenAiApi.mutate()` 和 `OpenAiChatModel.mutate()` 从自动配置的基础 bean 派生新实例。所有目标 Provider（DashScope、OpenAI、DeepSeek）均支持 OpenAI 兼容协议，无需引入原生 DashScope starter。

```java
// 从自动配置的 bean 派生 DashScope 实例
var api = baseApi.mutate()
        .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
        .apiKey(config.apiKey())
        .build();
var model = baseChatModel.mutate()
        .openAiApi(api)
        .defaultOptions(OpenAiChatOptions.builder().model("qwen-max").build())
        .build();
```

### Java `notify` 关键字冲突

`RoutingConfig` 原设计为命名字段 (`think`, `research`, `write`, `notify`, `fallback`)，但 Java record 组件不能命名为 `notify`（与 `Object.notify()` 冲突）。改用 `Map<String, String> stepAliases` + `aliasFor(String stepType)` 方法，更灵活且避免冲突。

### 两级容错机制

1. **同模型重试**（现有）: `LlmStepExecutor.execute()` 内 2 次重试
2. **跨模型 fallback**（新增）: `StepExecutorRouter.execute()` 捕获 `StepExecutionException`，如果 fallback 与 primary 不是同一实例则用 fallback 再调一轮

### 向后兼容

空配置时（`routing.step-aliases: {}`, `models: {}`），`ChatClientProvider.resolve("")` 返回默认 ChatClient，行为与改动前完全一致。

### 改动文件

| 文件 | 改动 |
|------|------|
| `MultiModelProperties.java` | **新增** — `@ConfigurationProperties` record |
| `ChatClientProvider.java` | **新增** — 创建/缓存多 ChatClient 实例 |
| `LlmStepExecutor.java` | **修改** — ChatClient 改为方法参数 |
| `LlmThinkExecutor.java` | **修改** — 适配新签名 |
| `LlmResearchExecutor.java` | **修改** — 适配新签名 |
| `LlmWriteExecutor.java` | **修改** — 适配新签名 |
| `LlmNotifyExecutor.java` | **修改** — 适配新签名 |
| `StepExecutorRouter.java` | **修改** — 注入 ChatClientProvider + 路由 + fallback |
| `AiTaskPlanner.java` | **修改** — 注入 ChatClientProvider |
| `AiClientConfig.java` | **修改** — `@EnableConfigurationProperties` |
| `application.yml` | **修改** — 添加 routing + models 配置节 |
| `ChatClientProviderTest.java` | **新增** — 8 个测试 |
| `StepExecutorRouterTest.java` | **修改** — mock 重构 + 4 个路由/fallback 测试 |

### 测试对比

| 模块 | 升级前 | 升级后 | 变化 |
|------|--------|--------|------|
| Domain | 39 | 39 | 不变 |
| Application | 11 | 11 | 不变 |
| Infrastructure (单元) | 19 | 31 | +12 |
| Infrastructure (集成) | 23 | 23 | 不变 (无 Docker 未执行) |
| **总计** | **70** | **81** (单元) | **+11 净增** |

## Next Steps

- Phase 2: Agent Framework POC（独立分支验证）
- 可选: 为 `ChatClientProvider` 添加 per-model 超时配置
- 可选: 添加模型选择日志/指标（可观测性）
