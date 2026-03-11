# Agent Framework POC 验证计划

**创建时间**: 2026-03-11 22:30 CST
**完成时间**: 进行中
**状态**: ⏳ 进行中
**关联 devlog**: `docs/devlog/013-agent-framework-research.md`

---

## 背景

Phase 0 (版本升级) 和 Phase 1 (多模型路由) 已完成。在进入 Agent Framework 全量迁移之前，需要通过 POC 验证其能力边界，产出 Go/No-Go 决策。

### 前置调研成果

已完成 `spring-ai-alibaba-agent-framework` 源码级 API 验证（对照 `D:\sorce_code_learning\spring-ai-alibaba-main\`），17/18 个关键 API 确认存在。唯一缺失项为 `RedisSaver`（需自行实现）。

详见:
- `docs/research/spring-ai-alibaba-agent-framework-guide.md`
- `docs/research/spring-ai-alibaba-practical-examples.md`

---

## 目标

验证 Agent Framework 与 EchoFlow 现有架构的兼容性，产出：
1. **Go/No-Go 决策** — 是否用 Agent Framework 替代自研执行链路
2. **领域模型方案推荐** — 方案 A (领域模型为主) vs 方案 B (Agent State 为主)
3. **风险清单** — 已识别的依赖冲突、API 限制、性能瓶颈

---

## POC 验证项

### POC-1: 依赖引入与编译验证

**目标**: 确认 `spring-ai-alibaba-agent-framework` JAR 能与现有依赖共存。

**步骤**:
1. 在 `echoflow-infrastructure/pom.xml` 添加依赖（已完成）
2. `./mvnw compile -pl echoflow-backend -am` 全量编译
3. 检查是否有类冲突（fastjson、httpclient4、agentscope）

**成功标准**: 编译通过，无运行时类加载警告。

**当前状态**: 依赖已添加，编译通过（StepExecutorRouter 已回退到无 Agent Framework 引用的版本）。

---

### POC-2: ReactAgent 替代 THINK Executor

**目标**: 将 `LlmThinkExecutor` 内部改为 ReactAgent 驱动，验证基本可行性。

**关键 API** (已源码验证):
```java
// com.alibaba.cloud.ai.graph.agent.ReactAgent
ReactAgent thinkAgent = ReactAgent.builder()
    .name("think_executor")
    .model(chatModel)                    // ChatModel 实例
    .instruction(thinkPromptContent)     // prompt 内容
    .hooks(
        ModelCallLimitHook.builder()
            .runLimit(5)
            .build()
    )
    .saver(new MemorySaver())
    .build();

// 调用
Optional<OverAllState> result = thinkAgent.invoke("用户任务描述");
// 或
AssistantMessage response = thinkAgent.call("用户任务描述");
```

**验证点**:
- [ ] ReactAgent 能接受现有 ChatModel (Spring AI OpenAI) 实例
- [ ] ReactAgent 的输出格式与现有 `chatClient.prompt().call().content()` 一致
- [ ] `StepExecutorPort` 接口无需变更（Agent 封装在 Adapter 内部）
- [ ] 现有 81 个单元测试全部通过

**成功标准**: THINK 步骤输出与改动前行为一致。

---

### POC-3: Hook/Interceptor 可用性

**目标**: 验证 Hook/Interceptor 机制的实际效果。

**验证内容**:

**a) MessagesModelHook — 消息裁剪**
```java
// com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook
public class MessageTrimmingHook extends MessagesModelHook {
    private final int maxMessages;

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        if (previousMessages.size() > maxMessages) {
            List<Message> trimmed = previousMessages.subList(
                previousMessages.size() - maxMessages, previousMessages.size());
            return new AgentCommand(trimmed, UpdatePolicy.REPLACE);
        }
        return new AgentCommand(previousMessages);
    }
}
```

**b) ToolInterceptor — 工具重试**
```java
// com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor
public class ToolRetryInterceptor extends ToolInterceptor {
    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        // 重试逻辑...
        ToolCallResponse response = handler.call(request);
        // 注意: 错误通过 ToolCallResponse.of(id, name, errorMsg) 返回
        // 不存在 ToolCallResponse.error() 方法
        return response;
    }
}
```

**验证点**:
- [ ] Hook 的 `beforeModel` / `afterModel` 确实被调用（通过日志验证）
- [ ] Interceptor 的 `interceptToolCall` 确实拦截工具调用
- [ ] Hook 和 Interceptor 可以独立启用/禁用
- [ ] 多个 Hook/Interceptor 按注册顺序执行

**成功标准**: Hook 和 Interceptor 按预期工作，无副作用。

---

### POC-4: DDD 兼容性验证

**目标**: 确认 Agent Framework 所有类型限制在 Infrastructure 层。

**验证方法**:
```bash
# 检查 domain 模块不引入 agent-framework
grep -r "com.alibaba.cloud.ai.graph" echoflow-backend/echoflow-domain/
# 应返回空

# 检查 application 模块不引入 agent-framework
grep -r "com.alibaba.cloud.ai.graph" echoflow-backend/echoflow-application/
# 应返回空
```

**成功标准**: Domain 和 Application 模块无任何 Agent Framework import。

---

### POC-5: SSE 集成验证

**目标**: Agent 执行过程中可发出与现有格式兼容的 SSE 事件。

**关键 API** (已源码验证):
```java
// com.alibaba.cloud.ai.graph.streaming.StreamingOutput
// com.alibaba.cloud.ai.graph.NodeOutput
Flux<NodeOutput> stream = thinkAgent.stream("用户任务描述", config);
stream.subscribe(output -> {
    if (output instanceof StreamingOutput<?> streamingOutput) {
        String chunk = streamingOutput.chunk();
        // 转换为现有 SSE 事件格式
    }
});
```

**验证点**:
- [ ] `ReactAgent.stream()` 返回 `Flux<NodeOutput>`
- [ ] `StreamingOutput.chunk()` 包含可用文本
- [ ] 可以适配为现有 `SseExecutionEventPublisher` 的事件格式
- [ ] 前端 `useExecutionStream` 能消费转换后的事件

**成功标准**: 前端看到与改动前格式一致的 SSE 事件。

---

### POC-6: 并行执行验证 (可选)

**目标**: 验证 StateGraph 并行节点能力。

**关键 API** (已源码验证):
```java
// com.alibaba.cloud.ai.graph.StateGraph
StateGraph graph = new StateGraph(() -> {
    Map<String, KeyStrategy> strategies = new HashMap<>();
    strategies.put("messages", new AppendStrategy(false));
    strategies.put("results", new ReplaceStrategy());
    return strategies;
});

// 并行边
graph.addEdges(StateGraph.START, List.of("research_1", "research_2", "research_3"));
graph.addEdge("research_1", "aggregator");
graph.addEdge("research_2", "aggregator");
graph.addEdge("research_3", "aggregator");
graph.addEdge("aggregator", StateGraph.END);

CompiledGraph compiled = graph.compile(CompileConfig.builder().build());
```

**验证点**:
- [ ] 3 个并行节点确实并行执行（通过时间戳验证）
- [ ] 聚合节点能获取所有并行节点的结果
- [ ] 异常处理：某个并行节点失败不影响其他节点

**成功标准**: 并行执行时间 ≈ 最慢节点的时间（而非三者之和）。

---

## 领域模型待决方案

POC 完成后需要做出的关键决策：

| 方案 | 描述 | 适用条件 |
|------|------|----------|
| **A: 领域模型为主** | Task → Execution → ExecutionStep 保留不变。Agent Framework 的 ReactAgent 仅在 `StepExecutorRouter` 内部使用，对外接口 `StepExecutorPort` 不变。`OverAllState` 不持久化到数据库。 | Agent 能良好封装在 Adapter 中 |
| **B: Agent State 为主** | Agent Framework 的 `OverAllState` 成为执行的真实状态模型。`Execution` 聚合根简化为结果归档。`StepExecutorPort` 改为 `AgentExecutionPort`。 | Graph 编排覆盖所有场景，State 可持久化 |

**当前倾向**: 方案 A — 风险最低，DDD 边界最清晰，且不需要解决 RedisSaver 不存在的问题。

---

## 实施步骤

### Step 1: 创建 POC 分支
```bash
git checkout -b feature/agent-framework-poc
```

### Step 2: 按顺序验证
1. POC-1 (依赖) → POC-2 (ReactAgent 替代 THINK) → POC-3 (Hook/Interceptor)
2. POC-4 (DDD) 贯穿全程
3. POC-5 (SSE) 在 POC-2 通过后验证
4. POC-6 (并行) 视前 5 项结果决定是否执行

### Step 3: 撰写报告
- `docs/research/agent-framework-poc-report.md`
- 包含: Go/No-Go 决策、领域模型方案推荐、发现的问题和限制

---

## 已知风险

| 风险 | 可能性 | 影响 | 缓解 |
|------|--------|------|------|
| agent-framework 与 spring-ai-starter-model-openai 类冲突 | 中 | 编译失败 | exclusion 或 shade |
| ReactAgent 内部使用的 ChatModel 接口与 OpenAI starter 不兼容 | 低 | POC-2 失败 | 回退到自研方案 |
| Hook/Interceptor 机制不够灵活 | 低 | 功能受限 | 自实现 wrapper |
| RedisSaver 不存在，MemorySaver 不支持持久化 | 已确认 | Checkpoint 功能受限 | 延后到 Phase 3，自实现 JpaSaver |
| StreamingOutput 格式与现有 SSE 不兼容 | 中 | 前端需改造 | 在 Adapter 层做格式转换 |

---

## 参考

- Spring AI Alibaba Agent Framework 源码: `D:\sorce_code_learning\spring-ai-alibaba-main\`
- API 验证报告: `docs/research/spring-ai-alibaba-agent-framework-guide.md` (已标注验证状态)
- 实践案例: `docs/research/spring-ai-alibaba-practical-examples.md` (已标注验证状态)
- overall-plan: `docs/plan/overall-plan.md` Phase 2 节
