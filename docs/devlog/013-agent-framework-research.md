# 013 — Agent Framework 调研与 API 验证

## Progress

- Spring AI Alibaba Agent Framework 源码级 API 验证完成
- 对照本地源码 `D:\sorce_code_learning\spring-ai-alibaba-main\` 逐一验证 18 个关键 API
- 验证通过率 94.4% (17/18)
- 依赖 `spring-ai-alibaba-agent-framework` 已添加到 `echoflow-infrastructure/pom.xml`，编译通过
- 整理研究文档: `spring-ai-alibaba-agent-framework-guide.md` + `spring-ai-alibaba-practical-examples.md`
- 重写 Phase 2 计划: 从"7 周渐进式集成"改回"POC 先行验证"路线

## DDD Decisions

- **Phase 2 维持 POC 路线**: Haiku 之前的计划跳过了 POC 验证直接做全量集成，风险太高。恢复为"先验证再迁移"的两步走策略。
- **领域模型待决方案保留**: 方案 A (领域模型为主) vs 方案 B (Agent State 为主)，需要 POC 实际验证后决策。当前倾向方案 A。
- **Agent Framework 限制在 Infrastructure 层**: 所有 ReactAgent / Hook / Interceptor / StateGraph 类型不得出现在 Domain 或 Application 层。`StepExecutorPort` 接口保持不变。

## Technical Notes

### API 验证核心发现

| API | 包路径 | 状态 |
|-----|--------|------|
| `ReactAgent.builder()` | `com.alibaba.cloud.ai.graph.agent` | ✅ 15+ 配置项 |
| `AgentHook` | `com.alibaba.cloud.ai.graph.agent.hook` | ✅ 6 个 HookPosition |
| `MessagesModelHook` | `com.alibaba.cloud.ai.graph.agent.hook.messages` | ✅ beforeModel/afterModel |
| `AgentCommand` / `UpdatePolicy` | `com.alibaba.cloud.ai.graph.agent.hook.messages` | ✅ APPEND/REPLACE |
| `ModelCallLimitHook` | `com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit` | ✅ builder().runLimit() |
| `ModelInterceptor` | `com.alibaba.cloud.ai.graph.agent.interceptor` | ✅ interceptModel() |
| `ToolInterceptor` | `com.alibaba.cloud.ai.graph.agent.interceptor` | ✅ interceptToolCall() |
| `ToolCallResponse.of()` | `com.alibaba.cloud.ai.graph.agent.interceptor` | ✅ |
| `ToolCallResponse.error()` | — | ❌ **不存在** |
| `ModelResponse.of()` | `com.alibaba.cloud.ai.graph.agent.interceptor` | ✅ |
| `OverAllState` | `com.alibaba.cloud.ai.graph` | ✅ value() / data() |
| `RunnableConfig` | `com.alibaba.cloud.ai.graph` | ✅ builder + threadId/context |
| `StateGraph` | `com.alibaba.cloud.ai.graph` | ✅ addNode/addEdge/compile |
| `AgentTool` | `com.alibaba.cloud.ai.graph.agent` | ✅ getFunctionToolCallback() |
| `ToolContextHelper` | `com.alibaba.cloud.ai.graph.agent.tools` | ✅ getState/getStateForUpdate |
| `MemorySaver` | `com.alibaba.cloud.ai.graph.checkpoint.savers` | ✅ |
| `RedisSaver` | — | ❌ **不存在** |
| `SubAgentSpec` | `com.alibaba.cloud.ai.graph.agent.extension.interceptor` | ✅ |
| `StreamingOutput` | `com.alibaba.cloud.ai.graph.streaming` | ✅ chunk() |
| `NodeOutput` | `com.alibaba.cloud.ai.graph` | ✅ node() / state() |

### Haiku 文档纠正项

1. `ToolCallResponse.error()` — 不存在独立的 error 工厂方法，错误通过 `.of(id, name, errorMsg)` 传递
2. `RedisSaver` — 源码中不存在，需自行实现 `Saver` 接口
3. `StepExecutorRouter` 中引用的 `ReactAgentStepExecutor` — 类不存在，导致编译失败，已回退

### 编译修复

回退了 Haiku 对 `StepExecutorRouter.java` 和 `StepExecutorRouterTest.java` 的改动（引用了不存在的 `ReactAgentStepExecutor`），恢复到 Phase 1 完成后的状态。`application.yml` 中新增的 `agent-framework.enabled: false` 配置也已回退。

## Next Steps

1. 创建 `feature/agent-framework-poc` 分支
2. 按 POC 计划验证 6 个验证项（POC-1 到 POC-6）
3. 撰写 POC 报告 → Go/No-Go 决策
4. 如 Go → 进入 Phase 3 全量迁移
