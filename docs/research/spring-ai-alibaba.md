# Spring AI Alibaba 调研报告

> 调研时间: 2026-03-11
> 调研目标: 获取最新稳定版本、沉淀常用使用模式、评估 EchoFlow 项目接入方案

---

## 1. 版本信息

| 字段 | 值 |
|------|----|
| 最新稳定版 | **1.1.2.2** (2026-03-10 发布) |
| 上游 Spring AI 版本 | 1.1.2 |
| Spring Boot 基线 | 3.5.8 |
| Java 最低要求 | 17 (推荐 21) |
| Maven GroupId | `com.alibaba.cloud.ai` |
| 官方文档 | https://java2ai.com |
| GitHub 仓库 | https://github.com/alibaba/spring-ai-alibaba |

### 近期版本线

| 版本 | 发布日期 | 说明 |
|------|----------|------|
| 1.1.2.2 | 2026-03-10 | Bug-fix patch，修复 1.1.2.1 已知问题 |
| 1.1.2.1 | 2026-03-09 | 已被 .2 取代 |
| 1.1.2.0 | 2026-02-02 | Agent Framework 大幅增强（并行执行、条件边、聚合策略） |
| 1.1.0.0 | 2025-12-30 | 1.1 系列起点 |
| 1.0.x GA | 2025 年中 | 首个生产就绪版本 |

### 重要提示

- Spring AI Alibaba 1.1.x 基于 **Spring AI 1.1.2**，而 EchoFlow 当前使用 **Spring AI 1.0.0**
- Spring AI 从 1.0 到 1.1 存在 API 变更，升级需评估兼容性
- 上游 Spring AI 2.0 尚处于 milestone 阶段，Spring AI Alibaba 暂未适配

---

## 2. 模块结构

```
spring-ai-alibaba/
├── spring-ai-alibaba-bom                          # BOM (版本管理)
├── spring-ai-alibaba-graph-core                   # 底层 Graph 引擎 (workflow 编排)
├── spring-ai-alibaba-agent-framework              # Agent 框架 (ReactAgent, SupervisorAgent 等)
├── spring-ai-alibaba-studio                       # Admin UI / 可视化调试
├── spring-ai-alibaba-sandbox                      # 沙箱执行环境
├── spring-boot-starters/
│   ├── spring-ai-alibaba-starter-a2a-nacos        # A2A 多 Agent 注册发现
│   ├── spring-ai-alibaba-starter-config-nacos     # Nacos 配置中心
│   ├── spring-ai-alibaba-starter-graph-observation # 可观测性
│   └── spring-ai-alibaba-starter-builtin-nodes    # 内置 Graph 节点
└── (spring-ai-extensions 独立仓库)
    └── spring-ai-alibaba-starter-dashscope        # DashScope 模型集成
```

### 核心依赖关系

```
spring-ai-alibaba-agent-framework
  └── spring-ai-alibaba-graph-core
       └── spring-ai (core abstractions: ChatModel, ChatClient, ToolCall)
            └── spring-ai-alibaba-starter-dashscope (或 spring-ai-starter-model-openai)
```

---

## 3. 常用使用模式

### 3.1 基础 Chat — DashScope Starter

**Maven 依赖:**

```xml
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
    <version>1.1.2.2</version>
</dependency>
```

**配置 (application.yml):**

```yaml
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      chat:
        options:
          model: qwen-max-latest   # 或 qwen-plus, qwen-turbo
          temperature: 0.8
          max-tokens: 2000
```

**代码:**

```java
@RestController
public class ChatController {
    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @GetMapping("/chat")
    public String chat(@RequestParam String input) {
        return chatClient.prompt()
                .user(input)
                .call()
                .content();
    }
}
```

> **关键**: `ChatClient` API 与 OpenAI starter 完全一致，底层自动切换为 DashScope 实现。

### 3.2 Tool Calling (函数调用)

**定义工具 (@Tool 注解):**

```java
@Component
public class WeatherTools {

    @Tool(description = "Get current weather for a city")
    public String getWeather(
            @ToolParam(description = "City name") String city) {
        // 调用真实天气 API
        return "Sunny, 25°C in " + city;
    }
}
```

**传递工具给 ChatClient:**

```java
chatClient.prompt()
    .user("What's the weather in Hangzhou?")
    .tools(weatherTools)
    .call()
    .content();
```

> DashScope 的 Qwen 系列原生支持 tool calling，语法与 OpenAI function calling 一致。

### 3.3 Structured Output (结构化输出)

```java
// 方式一: ChatClient entity() — 推荐
List<PlannedStep> steps = chatClient.prompt()
        .user(u -> u.text(promptTemplate)
                .param("taskDescription", desc))
        .call()
        .entity(new ParameterizedTypeReference<List<PlannedStep>>() {});

// 方式二: BeanOutputConverter (低级 API)
BeanOutputConverter<ActorsFilms> converter = new BeanOutputConverter<>(ActorsFilms.class);
String format = converter.getFormat();
// 将 format 嵌入 prompt 模板中...
```

### 3.4 Chat Memory (对话记忆)

```java
ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultSystem("You are a helpful assistant")
    .defaultAdvisors(
        new MessageChatMemoryAdvisor(new InMemoryChatMemory())
    )
    .build();
```

### 3.5 DashScope 特有选项

```java
ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultOptions(
        DashScopeChatOptions.builder()
            .withTopP(0.7)
            .build()
    )
    .build();
```

### 3.6 ReactAgent (ReAct 推理-行动循环)

**Maven 依赖:**

```xml
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-agent-framework</artifactId>
    <version>1.1.2.2</version>
</dependency>
```

**代码:**

```java
ReactAgent agent = ReactAgent.builder()
    .name("research_agent")
    .model(chatModel)
    .description("Research agent that searches and analyzes")
    .instruction("You are a research assistant. Use tools when needed: {input}")
    .tools(searchTool, calculatorTool)
    .outputKey("research_output")
    .build();

Optional<OverAllState> result = agent.invoke("Find popular Kotlin coroutine libraries");
```

### 3.7 SupervisorAgent (多 Agent 编排)

```java
// 定义子 Agent
ReactAgent writerAgent = ReactAgent.builder()
    .name("writer_agent").model(chatModel)
    .description("Creates original content")
    .instruction("Write content based on: {input}")
    .outputKey("writer_output").build();

ReactAgent reviewerAgent = ReactAgent.builder()
    .name("reviewer_agent").model(chatModel)
    .description("Reviews and improves content")
    .instruction("Review this: {writer_output}")
    .outputKey("reviewed_output").build();

// 组合为顺序执行流
SequentialAgent writingWorkflow = SequentialAgent.builder()
    .name("writing_workflow")
    .subAgents(List.of(writerAgent, reviewerAgent))
    .build();

// Supervisor 协调
SupervisorAgent supervisor = SupervisorAgent.builder()
    .name("content_supervisor")
    .model(chatModel)
    .systemPrompt("Coordinate: writer, reviewer, translator...")
    .subAgents(List.of(writerAgent, translatorAgent, writingWorkflow))
    .build();
```

### 3.8 Graph Workflow (底层图编排)

Agent Framework 底层基于 Graph Core。直接使用 Graph Core 可获得更大灵活性：
- 条件边 (Conditional Edge)
- 并行节点
- 聚合策略 (allOf / anyOf)
- 中断与恢复 (Human-in-the-loop)
- Checkpoint (Redis / MongoDB 持久化)

---

## 4. 与 OpenAI Starter 的兼容性

### 两种接入方式

| 方式 | 依赖 | 优缺点 |
|------|------|--------|
| **A. 原生 DashScope starter** | `spring-ai-alibaba-starter-dashscope` | 支持 DashScope 全部特性，阿里巴巴官方维护。需改配置 key 前缀 |
| **B. OpenAI 兼容模式** | 保留 `spring-ai-starter-model-openai` | 改动最小，仅改 `base-url` 和 model 名。不支持 DashScope 专有特性 |

### 方式 B: OpenAI 兼容模式 (零代码改动)

```yaml
spring:
  ai:
    openai:
      api-key: ${DASHSCOPE_API_KEY}
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      chat:
        options:
          model: qwen-max
```

> DashScope 提供 OpenAI 兼容的 `/chat/completions` 端点，可直接替换 `base-url`。

### 方式 A: 原生 DashScope Starter

移除 `spring-ai-starter-model-openai`，替换为 `spring-ai-alibaba-starter-dashscope`。代码层面的 `ChatClient` / `ChatModel` 注入和调用保持不变。

---

## 5. EchoFlow 接入评估

### 5.1 当前状态

| 项目 | EchoFlow 现状 | Spring AI Alibaba 要求 |
|------|--------------|----------------------|
| Spring Boot | 3.4.4 | 3.5.8 (1.1.x 基线) |
| Spring AI | 1.0.0 | 1.1.2 |
| Java | 21 | 17+ |
| AI Starter | `spring-ai-starter-model-openai` | `spring-ai-alibaba-starter-dashscope` 或保留 OpenAI starter |
| AI 配置前缀 | `spring.ai.openai.*` | `spring.ai.dashscope.*` |

### 5.2 接入方案对比

#### 方案一: 最小改动 — OpenAI 兼容模式 (推荐起步方案)

**改动范围**: 仅 `.env` 文件

```bash
# .env 修改
AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
AI_API_KEY=sk-your-dashscope-key
AI_MODEL=qwen-max
```

**优点**:
- 零代码改动，零依赖改动
- 不需要升级 Spring Boot 或 Spring AI 版本
- 可立即验证 DashScope 模型效果
- 保持与 OpenAI 的可切换性

**缺点**:
- 无法使用 DashScope 专有特性 (如多模态增强)
- 不能使用 Agent Framework / Graph Core

**适用**: 快速验证阶段，或仅需基础 Chat + Tool Calling 场景

#### 方案二: 中等改动 — 原生 DashScope Starter

**改动范围**: pom.xml + application.yml + .env

1. 升级 Spring AI BOM 到 1.1.2（需验证与 Spring Boot 3.4.4 的兼容性，可能需要升级 Boot 到 3.5.x）
2. 替换依赖: `spring-ai-starter-model-openai` → `spring-ai-alibaba-starter-dashscope`
3. 修改 application.yml 配置前缀

**优点**:
- 使用 DashScope 原生能力
- 获得 Alibaba 官方优化和支持
- 可以逐步引入 Agent Framework

**缺点**:
- 需要 Spring Boot 版本升级评估
- Spring AI 1.0 → 1.1 可能有 API break

**适用**: 确定使用 DashScope 作为主力模型提供商

#### 方案三: 深度集成 — Agent Framework

**改动范围**: 架构调整

引入 `spring-ai-alibaba-agent-framework`，将 EchoFlow 的自定义 step executor 逻辑迁移到 Agent Framework 的 ReactAgent / SequentialAgent 模式。

**优点**:
- 获得成熟的 Agent 编排能力
- 内置 Human-in-the-loop、上下文工程、重试等企业级特性
- 减少自研 agent 逻辑的维护成本

**缺点**:
- 需要大幅重构 Infrastructure AI 层
- EchoFlow 现有的 StepExecutorRouter → LlmXxxExecutor 链路需要适配
- Agent Framework 引入较重依赖 (fastjson, agentscope, httpclient4 等)
- 与 EchoFlow 的 DDD 分层可能产生摩擦 (Framework 是 Spring-aware 的)

**适用**: 未来演进方向，当 Agent 需求复杂度大幅提升时

### 5.3 推荐路线

```
Phase 1 (当前) → 方案一: OpenAI 兼容模式
    仅改 .env，零风险验证 DashScope (qwen-max/qwen-plus) 模型效果
    ↓
Phase 2 (需要时) → 方案二: 原生 DashScope Starter
    升级 Spring AI 到 1.1.x，获得 DashScope 专有特性
    需要同步评估 Spring Boot 版本升级
    ↓
Phase 3 (远期) → 方案三: Agent Framework
    当多 Agent 编排、复杂 workflow、Human-in-the-loop 成为刚需时
    需架构评审，确保 DDD 边界不被破坏
```

### 5.4 DDD 边界注意事项

如果采用方案二或方案三，需遵守以下规则:

1. **Domain 层保持纯净**: `spring-ai-alibaba` 的任何类型不得出现在 `echoflow-domain` 模块
2. **Port 接口不变**: `TaskPlannerPort` 和 `StepExecutorPort` 继续定义在 Application 层
3. **Adapter 实现替换**: Infrastructure 层的 `AiTaskPlanner`、`StepExecutorRouter` 等类内部实现更换为 DashScope API，但对外接口不变
4. **Agent Framework 封装**: 如引入 Agent Framework，需在 Infrastructure 层封装为 Port 的实现，禁止 Application 层直接依赖 Agent Framework 类型
5. **配置隔离**: DashScope 相关配置项 (`spring.ai.dashscope.*`) 和自定义配置 (`echoflow.ai.*`) 在 Web 层管理

---

## 6. DashScope 模型选型参考

| 模型 | 适用场景 | 上下文长度 | 特点 |
|------|----------|-----------|------|
| `qwen-max-latest` | 复杂推理、写作 | 32K | 最强智力，成本较高 |
| `qwen-plus` | 通用任务、工具调用 | 128K | 性价比高，推荐默认选择 |
| `qwen-turbo` | 简单对话、分类 | 128K | 速度快、成本最低 |
| `qwen-long` | 超长文档处理 | 1M | 专为长上下文优化 |
| `deepseek-r1` | 数学/代码推理 | 64K | DashScope 托管的 DeepSeek 模型 |

---

## 7. 关键参考链接

- 官方文档: https://java2ai.com
- Quick Start: https://java2ai.com/docs/quick-start
- DashScope 模型集成: https://java2ai.com/integration/chatmodels/dashscope
- Agent Framework: https://java2ai.com/docs/frameworks/agent-framework
- GitHub 仓库: https://github.com/alibaba/spring-ai-alibaba
- Maven Central: https://mvnrepository.com/artifact/com.alibaba.cloud.ai
- DashScope OpenAI 兼容模式: https://www.alibabacloud.com/help/en/model-studio/compatibility-of-openai-with-dashscope
- DashScope API Key 获取: https://dashscope.console.aliyun.com/

---

## 8. 总结

Spring AI Alibaba 已发展为成熟的 Java AI 框架，提供从基础 Chat 到多 Agent 编排的完整能力栈。对于 EchoFlow 项目:

- **短期**: 通过 OpenAI 兼容模式 (`base-url` 指向 DashScope)，零改动即可接入 Qwen 系列模型
- **中期**: 升级到原生 DashScope starter 以获取更好的模型特性支持
- **长期**: 评估 Agent Framework 替代自研 step executor 链路，降低维护成本

核心原则: **渐进式接入，每一步都可回退，DDD 边界不可妥协**。

---

## 9. 确定需求 (2026-03-11 头脑风暴结论)

### 9.1 核心驱动力

| 驱动力 | 说明 |
|--------|------|
| **Agent Framework** | 获得并行执行+聚合、Human-in-the-loop、断点恢复能力 |
| **多模型路由** | 按 StepType 选模型 + 用户可选 Provider + 自动降级/Fallback |

### 9.2 现有痛点

1. **编排能力不足**: 当前 `StepExecutorRouter` 仅支持顺序执行，无法表达条件分支、并行聚合
2. **工具扩展不便**: 新增 Tool 或 StepType 需修改 Router switch、新增 Executor 类、新增 prompt 模板，改动点过多
3. **上下文管理弱**: 跨 Step 的上下文传递靠手动拼接 `previousOutputs` 字符串，无结构化的 Memory/State 机制

### 9.3 关键决策

| 决策点 | 结论 | 备注 |
|--------|------|------|
| 改动范围 | **全栈升级** | Spring Boot + Spring AI + 新依赖 |
| 领域模型去留 | **先 POC 再决定** | Execution 聚合根可能被弱化，需验证后定 |
| 优先级顺序 | **版本升级 → 多模型 → Agent POC → 全量迁移** | 版本升级是一切的先决条件 |
| SSE 流式展示 | **可暂时降级** | Agent 迁移完成后再补全 |
| Agent 接入策略 | **全面拥抱** | 但通过 POC 验证后才全量迁移 |

### 9.4 执行路线图

```
Phase 0: 版本升级 (先决条件)
├── Spring Boot 3.4.4 → 3.5.x
├── Spring AI 1.0.0 → 1.1.2
├── 引入 spring-ai-alibaba-bom 1.1.2.x
├── 保留 spring-ai-starter-model-openai (多模型共存)
├── 修复 Spring AI 1.0 → 1.1 的 API break
└── 确保现有全部测试通过
       │
       ▼
Phase 1: 多模型路由层
├── Application 层: 定义 ModelRouterPort (按 StepType / 用户偏好 / Fallback 策略选模型)
├── Infrastructure 层: 实现多 ChatModel 实例注册 + 路由逻辑
├── 支持 Provider: DashScope (qwen-max/plus/turbo) + OpenAI + DeepSeek
├── 按 StepType 路由: THINK→强模型, RESEARCH/NOTIFY→快模型
├── 用户可选: Task 提交时可指定 provider 偏好
└── 自动降级: 主模型超时/异常时 fallback 到备用模型
       │
       ▼
Phase 2: Agent Framework POC
├── 创建独立分支 (feature/agent-framework-poc)
├── 用 Agent Framework 实现一个完整的 Task 执行 workflow
├── 验证目标:
│   ├── 并行执行 + 聚合 (多个 RESEARCH step 并行)
│   ├── Human-in-the-loop (关键步骤暂停等待确认)
│   ├── Checkpoint 断点恢复 (失败后从断点继续)
│   └── SSE 事件集成可行性评估
├── 评估维度:
│   ├── DDD 兼容性 — Agent Framework 类型能否限制在 Infrastructure 层
│   ├── 依赖体积 — fastjson, agentscope, httpclient4 等对项目的影响
│   └── 调试体验 — 错误追踪、日志可观测性
└── 产出: Go / No-Go 决策文档
       │
       ▼ (if POC pass)
Phase 3: Agent Framework 全量迁移
├── 基于 POC 结论确定领域模型调整方案
│   ├── 方案 A: 领域模型为主, Agent 为执行引擎 (Port/Adapter 封装)
│   └── 方案 B: Agent State 为主, 领域简化为归档记录
├── 迁移 StepExecutorRouter → Agent Framework Graph/ReactAgent
├── 迁移 AiTaskPlanner → Agent Framework 内置规划能力
├── 补全 SSE 实时流式展示
└── 更新前端 execution-timeline 组件适配新事件结构
```

### 9.5 DDD 硬约束 (全阶段适用)

无论哪个 Phase，以下规则不可违反:

1. `echoflow-domain` 模块 **不引入任何 Spring AI / Spring AI Alibaba 依赖**
2. `echoflow-application` 模块 **只通过 Port 接口与 AI 能力交互**，不直接依赖 `ChatModel`、`ReactAgent` 等具体类型
3. `echoflow-infrastructure` 模块作为 **唯一的 AI 适配层**，封装所有框架细节
4. 多模型路由的 **策略配置** 属于 Web 层 (`application.yml`)，**路由逻辑** 属于 Infrastructure 层
5. Agent Framework 的 Graph State **不得泄漏到 Domain 层**，如需持久化必须转换为领域值对象

### 9.6 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| Spring AI 1.0→1.1 API break | Phase 0 编译失败 | 升级前逐一比对 ChatClient / ToolCall / Advisor API 变更 |
| Agent Framework 依赖冲突 | fastjson / httpclient4 与现有 Jackson / JdkHttpClient 冲突 | POC 阶段验证，必要时用 exclusion 或 shading |
| Agent Framework 成熟度 | 1.1.x 仍在快速迭代，API 可能不稳定 | POC 验证 + 封装为 Port 适配器，降低耦合 |
| 多模型路由复杂度 | 配置膨胀，运维负担 | 提供合理默认值，仅必要时覆盖 |
| SSE 降级期间用户体验下降 | 前端无实时反馈 | Phase 3 补全前，提供 polling fallback 或 loading 状态 |
