# 014 — Agent Framework POC 验证

## Progress

- POC-1 (依赖编译) ✅ — `agent-framework` + `graph-core` 与现有依赖无冲突
- POC-2 (ReactAgent 替代 THINK) ✅ — 创建 `ReactAgentThinkExecutor`，20 个测试
- POC-3 (Hook/Interceptor) ✅ — `MessageTrimmingHook` + `ToolRetryInterceptor`，11 个测试
- POC-4 (DDD 兼容性) ✅ — Domain/Application 零 Agent Framework import
- POC-5 (SSE 集成) ✅ — `stream()` → `Flux<NodeOutput>` → `StreamingOutput.chunk()`，7 个测试
- POC-6 (并行执行) ⏭️ — 延后到 Phase 3
- **Go 决策**：推荐进入 Phase 3 全量迁移
- **领域模型方案**：方案 A（领域模型为主）
- 测试统计：原 31 → 55 个（+24），全部 GREEN

## DDD Decisions

- **方案 A 确认**: POC 验证了 ReactAgent 可完全封装在 Infrastructure 层，`StepExecutorPort` 接口无需变更，Application 层零改动。
- **Agent Framework 类型边界**: 所有 `ReactAgent`、`Hook`、`Interceptor`、`StreamingOutput` 类型不得出现在 Domain 或 Application 层。通过 `grep` 确认零泄漏。
- **Fallback 设计**: ReactAgent 失败 → 退化到 `LlmThinkExecutor` + fallback ChatClient。双层 fallback 提供最高可靠性。

## Technical Notes

### ReactAgent Builder API
- `.chatClient(ChatClient)` — 直接接受 Spring AI ChatClient，无需暴露底层 ChatModel
- `.instruction(String)` — 设置系统指令
- `.hooks(Hook...)` / `.interceptors(Interceptor...)` — 注册 Hook 和 Interceptor
- `.call(String)` → `AssistantMessage` — 同步调用
- `.stream(String)` → `Flux<NodeOutput>` — 流式调用

### 调研纠正
- `ToolCallResponse.error()` **实际存在**（之前调研认为不存在）
- `AgentCommand.getMessages()` 是 package-private（之前调研未提及）
- Hook/Interceptor 必须实现 `getName()` 抽象方法（之前调研未提及）

### StreamingOutput 泛型歧义
- `StreamingOutput<String>` 的 4 参数构造器存在泛型擦除歧义
- 解决方案：使用 5 参数构造器或 `StreamingOutput<Object>`
- 生产影响：无（生产代码消费而非创建 StreamingOutput）

### StepExecutorRouter 架构变化
```
原: execute() → resolveExecutor() → LlmStepExecutor.execute(ctx, chatClient)
新: execute() → THINK? → ReactAgentThinkExecutor.execute(ctx) [内置 ChatClient]
             → 其他  → LlmStepExecutor.execute(ctx, chatClient)  [不变]
```

### 新增依赖
- `reactor-test` (test scope) — 用于 `StepVerifier` 验证 Flux 行为

## Next Steps

1. 进入 Phase 3: 全量迁移（RESEARCH → WRITE → NOTIFY）
2. 排查 StepExecutorRouter deprecation 警告
3. 实现 StateGraph 并行执行（POC-6 补全）
4. 实现 SSE 适配层（StreamingOutput → SseExecutionEventPublisher）
5. 自实现 JpaSaver 替代 MemorySaver
