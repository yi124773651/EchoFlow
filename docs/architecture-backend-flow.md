# EchoFlow 后端业务主流程与类拓扑图

> 生成时间：2026-03-14
> 基于 commit: `36fc5ee` (feature/agent-framework-poc)

---

## 一、端到端请求流程

```
用户 POST /api/tasks
  │
  ▼
┌──────────────────────── Web 层 ────────────────────────┐
│  TaskController.create()                               │
│    ├─ submitTaskUseCase.execute(command)  ← 同步，短 TX │
│    └─ Thread.startVirtualThread(                       │
│         () -> executeTaskUseCase.execute(taskId))      │
│                                                        │
│  TaskController.stream()  ← SSE 长连接                  │
│    └─ ssePublisher.register(taskId)                    │
└────────────────────────────────────────────────────────┘
```

---

## 二、两阶段执行架构

### Phase 1: 规划（同步 + 短事务）

```
ExecuteTaskUseCase.planExecution(taskId)
  │
  ├─ 1. taskRepository.findById(taskId)          ← 读取 Task 聚合
  │
  ├─ 2. taskPlanner.planSteps(description)       ← LLM 调用（事务外）
  │     │
  │     └─ AiTaskPlanner                         [infrastructure/ai/planner]
  │          └─ chatClient.prompt()...call()
  │               └─ 返回 List<PlannedStep>
  │                  每步包含 name + StepType(THINK|RESEARCH|WRITE|NOTIFY)
  │
  ├─ 3. 构建领域对象（内存）
  │     ├─ task.markExecuting()
  │     ├─ Execution.create(...)
  │     └─ execution.addStep(...)  × N
  │
  ├─ 4. tx { taskRepository.save(); executionRepository.save() }  ← 短事务
  │
  └─ 5. eventPublisher.publish(ExecutionStarted)  ← 事务提交后
```

### Phase 2: 执行（异步 Virtual Thread + StateGraph）

```
ExecuteTaskUseCase.runExecution(execution)
  │
  └─ graphOrchestrator.executeSteps(
        taskDescription, steps, listener)
       │
       └─ GraphOrchestrator                      [infrastructure/ai/graph]
            ├─ buildGraph(steps, listener)        ← 构建 StateGraph 拓扑
            ├─ compileGraph(graph)                ← 编译为 CompiledGraph
            └─ compiled.invoke(initialState)      ← 执行图
```

---

## 三、StateGraph 拓扑结构

GraphOrchestrator 根据步骤列表的**模式**动态选择两种拓扑之一。

### 拓扑 A：线性链（无 THINK→RESEARCH 模式时）

```
START → step_1 → step_2 → ... → step_N → END
```

每个 `step_i` 是一个 `StepNodeAction`（或 `ReviewableWriteNodeAction`）。

### 拓扑 B：条件并行路由（THINK 后跟 RESEARCH 时）

```
START → [pre-THINK 线性] → think_node
                              │
                  ┌── ParallelResearchRouter ──┐
                  │     (MultiCommandAction)    │
                  ▼                             ▼
          ┌── R1 (StepNodeAction) ──┐    skip_research
          ├── R2 (StepNodeAction) ──┤    (ConditionalSkipNodeAction)
          └── ...                   │         │
                  │                 │         │
                  └─── 汇聚 ────────┘─────────┘
                          │
                          ▼
                 [post-RESEARCH 线性]
                          │
                  write_node ──→ review_gate ──→ ...
                          │              │
                          └──── END ─────┘
```

**路由决策**：THINK 步骤输出中嵌入 `[ROUTING_HINT]` 标记，由 `RoutingHintParser` 解析为 `RoutingHint(needsResearch, reason)`，`ParallelResearchRouter` 据此决定**并行扇出**（运行所有 RESEARCH）还是**跳过**（走 skip_research 节点）。

### WRITE Review 循环（条件启用）

当 `echoflow.review.enabled=true` 时，WRITE 步骤被包装为 review 循环：

```
write_node (ReviewableWriteNodeAction)
     │
     ▼
review_gate (WriteReviewGateAction)
     │
     ├── "approve" ──→ 下一个节点 / END
     │
     └── "revise"  ──→ revise_write (WriteReviseAction)
                            │
                            └──→ review_gate   ← 反向边，形成循环
```

**审核决策**：`LlmWriteReviewer`（LLM-as-Judge）评估 WRITE 输出质量，返回 `ReviewResult(score, approved, feedback)`。循环在以下条件终止：
- `approved = true`（质量达标）
- `attempts >= maxAttempts`（达到最大尝试次数，强制通过）
- 输出为空（WRITE 步骤降级跳过）

---

## 四、完整类拓扑图

### 调用链全景

```
┌─────────────────────────────── Web 层 ───────────────────────────────┐
│                                                                      │
│  TaskController ──→ SubmitTaskUseCase ──→ TaskRepository             │
│       │                                                              │
│       ├──→ ExecuteTaskUseCase ──→ TaskPlannerPort ──────────┐        │
│       │         │                                           │        │
│       │         └──→ GraphOrchestrationPort ────────┐       │        │
│       │                    │                        │       │        │
│       └──→ SseExecutionEventPublisher               │       │        │
│                    ▲                                │       │        │
│                    │ publish()                      │       │        │
└────────────────────│────────────────────────────────│───────│────────┘
                     │                                │       │
          ExecutionEventPublisher (port)              │       │
                                                     │       │
┌────────────────── Infrastructure 层 ──────────────│───────│────────┐
│                                                   │       │        │
│  ┌─ ai/planner/ ─────────────────────────────────│───────│──┐     │
│  │  AiTaskPlanner ←─────────────────────────────────────┘   │     │
│  │    └─ chatClient.prompt()...call()                        │     │
│  └───────────────────────────────────────────────────────────┘     │
│                                                                    │
│  ┌─ ai/graph/ ───────────────────────────────────────────────┐    │
│  │  GraphOrchestrator ←─────────────────────────────────┘     │    │
│  │    ├─ StepNodeAction ──→ StepExecutorPort                  │    │
│  │    ├─ ConditionalSkipNodeAction                            │    │
│  │    ├─ ParallelResearchRouter ──→ RoutingHintParser         │    │
│  │    │                               └─ RoutingHint          │    │
│  │    ├─ ReviewableWriteNodeAction ──→ StepExecutorPort       │    │
│  │    ├─ WriteReviewGateAction ──→ LlmWriteReviewer           │    │
│  │    │                               └─ ReviewResultParser   │    │
│  │    │                                   └─ ReviewResult     │    │
│  │    └─ WriteReviseAction ──→ StepExecutorPort               │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                    │
│  ┌─ ai/executor/ ────────────────────────────────────────────┐    │
│  │  StepExecutorRouter (implements StepExecutorPort)          │    │
│  │    │                                                       │    │
│  │    ├─ [primary] ReactAgentStepExecutor                     │    │
│  │    │    ├─ ReactAgentThinkExecutor                         │    │
│  │    │    ├─ ReactAgentResearchExecutor ──→ GitHubSearchTool │    │
│  │    │    ├─ ReactAgentWriteExecutor                         │    │
│  │    │    └─ ReactAgentNotifyExecutor ──→ WebhookNotifyTool  │    │
│  │    │         内部注册: MessageTrimmingHook                  │    │
│  │    │         内部注册: ToolRetryInterceptor                 │    │
│  │    │                                                       │    │
│  │    └─ [fallback] LlmStepExecutor                           │    │
│  │         ├─ LlmThinkExecutor                                │    │
│  │         ├─ LlmResearchExecutor ──→ GitHubSearchTool        │    │
│  │         ├─ LlmWriteExecutor                                │    │
│  │         └─ LlmNotifyExecutor ──→ WebhookNotifyTool         │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                    │
│  ┌─ ai/config/ ──────────────────────────────────────────────┐    │
│  │  ChatClientProvider ──→ 按 alias 解析 ChatClient           │    │
│  │  MultiModelProperties ──→ StepType → model alias 映射      │    │
│  │  WriteReviewConfig ──→ 条件创建 LlmWriteReviewer bean      │    │
│  │  WriteReviewProperties ──→ maxAttempts 等配置               │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                    │
│  ┌─ ai/tool/ ────────────────────────────────────────────────┐    │
│  │  GitHubSearchTool ──→ GitHub Code Search API               │    │
│  │  WebhookNotifyTool ──→ 外部 Webhook URL                    │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                    │
│  ┌─ persistence/ ────────────────────────────────────────────┐    │
│  │  JpaTaskRepository ←── TaskRepository (domain port)        │    │
│  │  JpaExecutionRepository ←── ExecutionRepository            │    │
│  └────────────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────────┘
```

### Port 接口与实现对照

| Port 接口（Application 层） | 实现类（Infrastructure 层） | 包 |
|---|---|---|
| `TaskPlannerPort` | `AiTaskPlanner` | `ai/planner/` |
| `GraphOrchestrationPort` | `GraphOrchestrator` | `ai/graph/` |
| `StepExecutorPort` | `StepExecutorRouter` | `ai/executor/` |
| `ExecutionEventPublisher` | `SseExecutionEventPublisher` | `web/task/` |
| `TaskRepository` | `JpaTaskRepository` | `persistence/task/` |
| `ExecutionRepository` | `JpaExecutionRepository` | `persistence/execution/` |

---

## 五、步骤执行的双层 Fallback

```
StepExecutorRouter.execute(context)
  │
  ├── [1] ReactAgentStepExecutor.execute(context)         ← 主路径
  │        └─ ReactAgent.call(userMessage)
  │             ├─ ModelCallLimitHook (限制最多 5 次模型调用)
  │             ├─ MessageTrimmingHook (保留最近 20 条消息)
  │             └─ ToolRetryInterceptor (工具调用重试 + 指数退避)
  │
  │   如果主路径抛出 StepExecutionException:
  │
  └── [2] LlmStepExecutor.execute(context, fallbackClient) ← 降级路径
           └─ chatClient.prompt()...call().content()
                ├─ 使用 fallbackClient（可能不同于 primary model）
                └─ 如果 fallbackClient == primaryClient，跳过降级直接抛异常
```

**按 StepType 的工具配置**：

| StepType | ReactAgent 工具 | LLM 工具 | 说明 |
|----------|----------------|---------|------|
| THINK | 无 | 无 | 纯推理，无外部调用 |
| RESEARCH | `GitHubSearchTool` + `ToolRetryInterceptor` | `GitHubSearchTool` | GitHub 代码搜索 |
| WRITE | 无 | 无 | 纯文本生成 |
| NOTIFY | `WebhookNotifyTool` + `ToolRetryInterceptor` | `WebhookNotifyTool` | Webhook 通知 |

---

## 六、SSE 事件流时序

```
Frontend                   Controller              UseCase                 GraphOrchestrator
  │                           │                       │                          │
  ├── GET /stream ──────────→ │                       │                          │
  │                           ├── register(taskId)    │                          │
  │← SseEmitter ─────────────┤                       │                          │
  │                           │                       │                          │
  │                           │   POST /tasks ──────→ │                          │
  │                           │                       ├── planExecution()        │
  │← ExecutionStarted ───────│←──────────────────────┤                          │
  │                           │                       ├── runExecution() ───────→│
  │                           │                       │                          ├─ StepNodeAction
  │← StepStarted ────────────│←──── listener ────────│←──── onStepStarting ────┤
  │← StepLogAppended ────────│←──── listener ────────│←──── onStepCompleted ───┤
  │← StepCompleted ──────────│←──── listener ────────│                          │
  │                           │                       │                          ├─ (next step...)
  │← ... ─────────────────────│                       │                          │
  │                           │                       │                          │
  │← ExecutionCompleted ─────│←──────────────────────┤                          │
  │                           │                       │                          │
```

---

## 七、迭代产物演进与替代关系

### 执行引擎演进（3 代）

```
MVP (devlog 001-010)
│  ExecuteTaskUseCase 内部 while 循环
│  直接调用 LlmStepExecutor 子类
│
├── Phase 3.1 (devlog 014-015) — ReactAgent 迁移
│   引入 ReactAgentStepExecutor 系列
│   LlmStepExecutor 系列降级为 fallback
│
└── Phase 3.2 (devlog 017-020) — StateGraph 图编排
    引入 GraphOrchestrator + NodeAction 系列
    ExecuteTaskUseCase 的 while 循环被完全替代
```

### 类级替代关系

| 原始类/模式 | 引入阶段 | 当前状态 | 替代者 | 说明 |
|---|---|---|---|---|
| `ExecuteTaskUseCase` 内 while 循环 | MVP | **已删除** | `GraphOrchestrator` + `StepNodeAction` | Phase 3.2A 替代。UseCase 现在委托给 `GraphOrchestrationPort` |
| `LlmStepExecutor`（抽象基类） | MVP | **保留为 fallback** | `ReactAgentStepExecutor` | Phase 3.1 迁移。ReactAgent 为主路径，LLM 为降级路径 |
| `LlmThinkExecutor` | MVP | **保留为 fallback** | `ReactAgentThinkExecutor` | 同上 |
| `LlmResearchExecutor` | MVP | **保留为 fallback** | `ReactAgentResearchExecutor` | 同上 |
| `LlmWriteExecutor` | MVP | **保留为 fallback** | `ReactAgentWriteExecutor` | 同上 |
| `LlmNotifyExecutor` | MVP | **保留为 fallback** | `ReactAgentNotifyExecutor` | 同上 |
| `StepNodeAction`（普通 WRITE） | Phase 3.2A | **WRITE 场景被替代** | `ReviewableWriteNodeAction` | Phase 3.2B-3。当 review 启用时，WRITE 步骤使用 ReviewableWriteNodeAction；非 WRITE 步骤仍用 StepNodeAction |
| `HookInterceptorPocTest` | Phase 3.1 POC | **已删除** (022) | 测试覆盖合并到 `ReactAgentStepExecutorTest` | POC 验证测试，与生产测试重叠 |
| `SseIntegrationPocTest` | Phase 3.1 POC | **已删除** (022) | 无直接替代 | 验证 `ReactAgent.stream()` 框架 API，生产未使用该功能 |

### 保留但不再作为主路径的类

以下类虽然不再是主执行路径，但作为**降级容灾**机制仍在生产中使用：

```
StepExecutorRouter.execute(context)
  │
  ├── ReactAgent*Executor.execute()   ← 主路径（Phase 3.1+）
  │     失败时 ↓
  └── Llm*Executor.execute()          ← 降级路径（MVP 遗留，仍活跃）
        └─ 使用 fallbackClient
```

**不建议删除 LlmStepExecutor 系列**：它们提供了独立于 Agent Framework 的纯 ChatClient 降级能力。当 ReactAgent 的 `spring-ai-alibaba-graph` 库出现 bug 或不兼容升级时，LLM 路径可自动接管，保证服务可用性。

---

## 八、关键设计决策索引

| 决策 | 位置 | Devlog |
|------|------|--------|
| Domain 纯 Java，零框架依赖 | CLAUDE.md Rule 5 | — |
| LLM 调用在事务外 | `ExecuteTaskUseCase.planExecution()` | 004 |
| 回调模式（StepProgressListener）替代批量返回 | `GraphOrchestrationPort` | 017 |
| 并行回调通过 `synchronized(execution)` 串行化 | `ExecuteTaskUseCase.runExecution()` | 019 |
| THINK 输出嵌入路由提示决定 RESEARCH 是否执行 | `RoutingHintParser` | 018 |
| WRITE Review 循环通过 StateGraph 反向边实现 | `GraphOrchestrator.addWriteReviewLoop()` | 020 |
| 双层 Fallback: ReactAgent → LlmStepExecutor | `StepExecutorRouter.execute()` | 015 |
| 多模型路由：StepType → model alias → ChatClient | `ChatClientProvider` + `MultiModelProperties` | 012 |
