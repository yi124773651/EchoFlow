# 动态编排调研：用 Agent Framework 替代固定步骤序列

> **调研日期**: 2026-03-12
> **调研背景**: 当前 EchoFlow 的执行步骤 THINK → RESEARCH → WRITE → NOTIFY 为固定顺序编排，探索是否可以利用 Spring AI Alibaba Agent Framework 的图编排能力实现更灵活的动态编排
> **结论**: **可行**，当前固定序列是 `SequentialAgent` 的特例，可渐进式演化到动态 DAG 编排

---

## 1. 当前架构的固定编排分析

### 1.1 固定在哪里

| 层面 | 位置 | 固定方式 |
|------|------|----------|
| **步骤规划** | `TaskPlannerPort.planSteps()` | LLM 返回 `List<PlannedStep>`，每个 step 有固定 `StepType` 枚举 |
| **步骤执行** | `ExecuteTaskUseCase.runExecution()` | `while (execution.hasPendingSteps())` 严格顺序遍历 |
| **记忆传递** | `ArrayList<String> previousOutputs` | 手动累积、手动传递，仅 output 字符串 |

### 1.2 问题

1. **步骤类型**是固定的 4 种枚举，无法扩展（如增加 CODE_REVIEW、TRANSLATE 等）
2. **执行顺序**严格顺序，无法跳过（简单任务不需要 RESEARCH）、并行（多源 RESEARCH）或循环（WRITE 后迭代优化）
3. **记忆传递**仅 `List<String>`，粒度粗，不支持结构化数据传递
4. **无检查点**，执行中断后无法恢复

---

## 2. Spring AI Alibaba Agent Framework 编排能力

### 2.1 三层编排体系

```
层次 3: FlowAgent（高级编排模式）
  SequentialAgent / ParallelAgent / LoopAgent / LlmRoutingAgent / SupervisorAgent

层次 2: StateGraph（底层图引擎）
  节点 + 条件边 + 并行边 + 检查点 + 人机交互

层次 1: ReactAgent（单节点内 ReAct 循环）
  AgentLlmNode ↔ AgentToolNode 循环 — 已在 Phase 3.1 采用
```

### 2.2 StateGraph 核心能力

| 能力 | 说明 | 对标 EchoFlow 痛点 |
|------|------|-------------------|
| **条件路由** | `addConditionalEdges()` — 根据状态决定下一个节点 | 可跳过不必要的步骤 |
| **并行执行** | `addEdge(source, List.of(a, b, c))` — 扇出并行 | 多源 RESEARCH 并行 |
| **状态自动传递** | `OverAllState` + `KeyStrategy` | 替代手动 `previousOutputs` |
| **检查点恢复** | Checkpoint + PostgresSaver | 执行中断后可恢复 |
| **人机交互** | `interruptBefore/After` | WRITE 前暂停等用户确认 |
| **循环** | 边可回指，LoopAgent 封装 | 写作后自动评审迭代 |

### 2.3 FlowAgent 与 Anthropic 模式的对应关系

| FlowAgent | Anthropic 模式 | 适用场景 |
|-----------|---------------|---------|
| `SequentialAgent` | Prompt Chaining | 固定流水线 ← **当前 EchoFlow** |
| `ParallelAgent` | Parallelization | 多源 RESEARCH 并行 |
| `LlmRoutingAgent` | Routing | 根据任务类型选处理链 |
| `LoopAgent` | Evaluator-Optimizer | WRITE 后自动评审迭代 |
| `SupervisorAgent` | Orchestrator-Workers | 主管 Agent 动态派发 |

### 2.4 OverAllState vs 当前 previousOutputs

| 对比项 | 当前方式 | StateGraph 方式 |
|--------|---------|----------------|
| 传递载体 | `ArrayList<String>` | `OverAllState`（Map + KeyStrategy） |
| 数据粒度 | 仅 output 字符串 | 任意结构化数据 |
| 传递方式 | 手动 `add()` | 节点返回 partial state，框架自动合并 |
| 合并策略 | 无 | APPEND / REPLACE / MERGE |
| 持久化 | 不支持 | Checkpoint + PostgresSaver |
| 恢复能力 | 不支持 | 从任意检查点恢复 |

---

## 3. 当前固定序列 = SequentialAgent 特例

```
当前 EchoFlow:
  START → THINK → RESEARCH → WRITE → NOTIFY → END
  （while 循环 + 手动 previousOutputs）

等价 SequentialAgent:
  SequentialAgent.builder()
      .agents(thinkAgent, researchAgent, writeAgent, notifyAgent)
      .build()
  （OverAllState 自动传递，框架驱动顺序执行）
```

引入 StateGraph 后可演化为动态编排：

```
START → [THINK] ──条件边──→ 需要调研？──是──→ [RESEARCH] ──→ [WRITE]
                                │                              │
                                否                         评审质量够？
                                │                          │      │
                                └──→ [WRITE] ──→           否     是
                                                ↑          │      │
                                                └──────────┘   [NOTIFY] → END
```

---

## 4. DDD 兼容性评估

- **Domain 层零改动**：`Execution`、`ExecutionStep`、`StepType` 保持不变
- **Application 层**：`ExecuteTaskUseCase.runExecution()` 内部替换为 StateGraph/FlowAgent 调用，`StepExecutorPort` 接口不变
- **Infrastructure 层**：`StepExecutorRouter` 演变为 StateGraph 的节点工厂
- **约束**：所有 StateGraph、FlowAgent、OverAllState 类型限制在 Infrastructure 层

devlog 014 POC 已验证："ReactAgent 可完全封装在 Infrastructure 层，StepExecutorPort 接口无需变更"。

---

## 5. 渐进式迁移方案

### Phase A: SequentialAgent 等价替换（最小改动）

- 用 `SequentialAgent` 替代 `ExecuteTaskUseCase.runExecution()` 的 while 循环
- 每个 `ReactAgentStepExecutor` 通过 `asNode()` 成为图节点
- `OverAllState` 替代手动 `previousOutputs`
- **获得**: 自动状态传递 + 检查点恢复 + 原生流式输出

### Phase B: 引入条件边，实现动态路由

- THINK 输出决定是否跳过 RESEARCH
- WRITE 后增加评审循环（LoopAgent / Evaluator-Optimizer）
- RESEARCH 支持并行（ParallelAgent / 并行边）
- `StepType` 从固定枚举演变为可扩展类型

### Phase C: LLM-Driven 编排（最终形态）

- `LlmRoutingAgent` 或 `SupervisorAgent` 让 LLM 自己决定编排
- `TaskPlannerPort` 不再输出有序列表，而是输出 StateGraph 定义（或由 Supervisor 动态派发）
- 当前固定序列退化为一种"预设模板"

---

## 6. 风险与注意事项

1. **事务边界**：StateGraph 执行节点时不能在 DB 事务内（Rule 5），需确保图引擎不隐式开启事务
2. **Execution 聚合根适配**：引入并行/条件跳过后，domain 模型需适配（step 状态 SKIPPED 已支持）
3. **SSE 适配**：StateGraph 产出 `Flux<NodeOutput>`，需适配到 `SseExecutionEventPublisher` — POC-5 已验证可行
4. **版本依赖**：FlowAgent 是 v1.1.2.0+ 特性，需确认依赖版本
5. **Domain 纯净性**：OverAllState ↔ Domain 模型之间需要 mapping，不能让 OverAllState 泄漏到 Domain/Application

---

## 7. 参考资料

- Spring AI Alibaba Agent Framework 源码解析（本地: `D:\sorce_code_learning\spring-ai-alibaba-agent-framework-源码讲解.md`）
- Spring AI Alibaba Graph Core 源码解析（本地: `D:\sorce_code_learning\spring-ai-alibaba-graph-core-源码解析.md`）
- Anthropic "Building Effective Agents"（5 种 workflow 模式: Prompt Chaining / Routing / Parallelization / Orchestrator-Workers / Evaluator-Optimizer）
- EchoFlow devlog 013（Agent Framework 调研）、014（POC 验证）、015（ReactAgent 全量迁移）
