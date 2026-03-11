# 015 — ReactAgent 全量迁移 (Phase 3.1)

## Progress

- Phase 3.1: 将 RESEARCH/WRITE/NOTIFY 全部迁移到 ReactAgent 驱动 ✅
- 提取 `ReactAgentStepExecutor` 抽象基类，统一 retry/validate/truncate 逻辑 ✅
- 创建 `ReactAgentResearchExecutor`（带 GitHubSearchTool + ToolRetryInterceptor）✅
- 创建 `ReactAgentWriteExecutor`（纯文本生成，最简实现）✅
- 创建 `ReactAgentNotifyExecutor`（带 WebhookNotifyTool + ToolRetryInterceptor）✅
- 重构 `ReactAgentThinkExecutor` 继承基类 ✅
- 重构 `StepExecutorRouter` — 统一路由所有 StepType → ReactAgent（主路径）→ LlmExecutor（fallback）✅
- 测试统计：55 → 64 个（+9），全部 GREEN
- DDD 边界检查：Domain/Application 零 Agent Framework import ✅

## DDD Decisions

- **方案 A 延续**: ReactAgent 全量迁移验证了方案 A 的可行性。所有 4 种 StepType 均通过 ReactAgent 执行，`StepExecutorPort` 接口无任何变更，Application/Domain 零改动。
- **抽象基类边界**: `ReactAgentStepExecutor` 是 package-private 的，与 `LlmStepExecutor` 平级。只有 `StepExecutorRouter` 对外暴露为 `@Component`。
- **Fallback 双层保护**: ReactAgent executor (primary) → LlmStepExecutor + fallbackClient (fallback)。同 model 时跳过 fallback 避免重复失败。

## Technical Notes

### Builder API 修正

POC 阶段的 API 认知在全量迁移中被修正：

| 认知 | 实际 |
|------|------|
| `ReactAgent.Builder` 内部类 | `com.alibaba.cloud.ai.graph.agent.Builder` 独立抽象类 |
| `.tools(Object...)` 注册 @Tool 对象 | `.methodTools(Object...)` — 内部调用 `ToolCallbacks.from()` |
| `.tools(ToolCallback...)` | 接受预构建的 ToolCallback 实例 |

### ReactAgentStepExecutor 架构

```
ReactAgentStepExecutor (abstract, package-private)
├── chatClient: ChatClient            ← 从 ChatClientProvider 解析
├── promptContent: String             ← 读取 .st 模板原文
├── execute(ctx) → StepOutput         ← retry loop + validate + truncate
├── buildAgent() → ReactAgent         ← per-call 构建，protected for test override
├── configureAgent(Builder) → Builder ← 子类添加 tools/interceptors
├── formatUserMessage(ctx) → String   ← 占位符替换，THINK override 跳过 previousContext
└── buildPreviousContext(List) → String ← 静态方法，格式化前序输出
```

### 子类定制

| Executor | agentName | configureAgent | formatUserMessage |
|----------|-----------|---------------|-------------------|
| Think | `think_executor` | 默认（无 tools） | override: 不替换 `{previousContext}` |
| Research | `research_executor` | `.methodTools(gitHubSearchTool).interceptors(ToolRetryInterceptor)` | 默认 |
| Write | `write_executor` | 默认（无 tools） | 默认 |
| Notify | `notify_executor` | `.methodTools(webhookNotifyTool).interceptors(ToolRetryInterceptor)` | 默认 |

### StepExecutorRouter 架构变化

```
Phase 2 (POC):
  execute() → THINK? → ReactAgentThinkExecutor [内置 ChatClient]
           → 其他   → LlmStepExecutor.execute(ctx, chatClient)

Phase 3.1 (全量迁移):
  execute() → ReactAgentStepExecutor.execute(ctx)  [ALL step types]
           → catch StepExecutionException
           → fallbackClient == primaryClient? → re-throw (同 model 不 fallback)
           → LlmStepExecutor.execute(ctx, fallbackClient)
```

### 测试策略

- `ReactAgentStepExecutorTest` (13 tests): 通过匿名子类 override `buildAgent()` 注入 mock ReactAgent，测试基类 retry/validate/truncate/formatting
- `ReactAgentThinkExecutorTest` (6 tests): 同样 override `buildAgent()`，测试 THINK 特有的 formatUserMessage 行为
- `StepExecutorRouterTest` (12 tests): mock `ReactAgentStepExecutor` 抽象类，测试路由 + fallback

### 已知问题

- `ReactAgent.builder()` 返回的 `Builder` 存在一个 deprecation 警告（中文编码的废弃提示），与 POC 阶段一致。这是 Spring AI Alibaba 1.1.2.2 的已知问题，不影响功能。

## Next Steps

1. Phase 3.2: StateGraph 并行执行（多个 RESEARCH step 并行 + 聚合）
2. Phase 3.2: SSE 适配层（`ReactAgent.stream()` → `StreamingOutput` → `SseExecutionEventPublisher`）
3. Phase 3.3: 自实现 JpaSaver 替代 MemorySaver（Checkpoint 持久化）
4. Phase 3 Backlog: Human-in-the-loop（WRITE step 前暂停等用户确认）
5. 排查 Builder deprecation 警告来源并评估修复方案
