# Spring AI Alibaba Agent Framework 实践案例集锦

**文档时间**: 2026-03-11
**参考源**: D:\sorce_code_learning\spring-ai-alibaba-main\examples\
**框架版本**: Spring AI Alibaba 1.1.2+
**整理目的**: 深度学习 Spring AI Alibaba Agent 实践，指导 EchoFlow 项目集成

> **⚠️ API 验证状态 (2026-03-11 22:30 CST)**
>
> 本文档由 Haiku 基于 examples 目录整理。核心 API 已通过源码交叉验证（17/18 通过）。
>
> **已确认的纠正项**:
> - `ToolCallResponse.error()` → **不存在**，应使用 `ToolCallResponse.of(id, name, errorMsg)`
> - `RedisSaver` → **不存在**，仅有 `MemorySaver`
> - `ModelResponse.of(AssistantMessage)` → ✅ 存在
> - `ReactAgent.builder().methodTools()` → 源码中存在此方法
>
> **使用建议**: 代码示例仅供架构参考，编码前请以实际源码为准。

---

## 目录

1. [实践案例总览](#实践案例总览)
2. [各案例深度解析](#各案例深度解析)
3. [核心架构模式](#核心架构模式)
4. [关键组件详解](#关键组件详解)
5. [针对 EchoFlow 的集成建议](#针对-echoflow-的集成建议)

---

## 实践案例总览

Spring AI Alibaba examples 包含 **7 大类 40+ 个实践案例**，覆盖从基础到高级的完整 Agent 开发生态：

### 案例分类表

| 类别 | 位置 | Agent数 | 复杂度 | 主要特点 | 文件数 |
|------|------|--------|-------|--------|-------|
| **Chatbot** | `/chatbot/` | 1 | ⭐ | 基础 ReAct、工具调用、文件访问 | 3 |
| **DeepResearch** | `/deepresearch/` | 多(1+2子) | ⭐⭐⭐⭐⭐ | 复杂拦截器、子Agent、任务规划 | 10+ |
| **HandoffsMulti** | `/multiagent-patterns/handoffs-multiagent/` | 2+ | ⭐⭐⭐ | 状态图、Agent切换、工具转接 | 8 |
| **HandoffsSingle** | `/multiagent-patterns/handoffs-singleagent/` | 1 | ⭐⭐ | 单Agent多角色、Hook改变行为 | 5 |
| **Supervisor** | `/multiagent-patterns/supervisor/` | 3+ | ⭐⭐⭐ | 监督者模式、Agent包装为工具 | 6 |
| **Pipeline** | `/multiagent-patterns/pipeline/` | 多 | ⭐⭐⭐ | 顺序/并行/循环执行 | 9 |
| **Routing** | `/multiagent-patterns/routing/` | 多 | ⭐⭐ | 动态路由、能力匹配 | 4 |
| **Skills** | `/multiagent-patterns/skills/` | 多 | ⭐⭐ | 技能库共享、组合编程 | 5 |
| **SubAgent** | `/multiagent-patterns/subagent/` | 多 | ⭐⭐⭐ | 主Agent委托、任务分解 | 6 |
| **Workflow** | `/multiagent-patterns/workflow/` | 多 | ⭐⭐⭐⭐ | RAG、SQL、知识图谱集成 | 12+ |
| **Documentation** | `/documentation/` | 多 | 变化 | 完整教程、示例代码 | 25+ |
| **Multimodal** | `/multimodal/` | 1 | ⭐⭐ | 文本/图像/音频、工具集成 | 6 |
| **VoiceAgent** | `/voice-agent/` | 1 | ⭐⭐⭐ | 实时语音、WebSocket、流式响应 | 8 |
| **AgentScope** | `/agentscope/handoffs/` | 2 | ⭐⭐⭐ | 跨框架互操作 | 4 |

---

## 各案例深度解析

### 1. Chatbot 案例（最基础）

**定位**: 快速上手 Spring AI Agent
**源文件**: `/chatbot/ChatbotApplication.java`, `ChatbotAgent.java`

#### 架构

```
用户输入
  ↓
ChatbotAgent (单个ReAct Agent)
  ├─ ShellTool (执行shell命令)
  ├─ PythonTool (执行Python代码)
  └─ ReadFileTool (读取文本文件)
  ↓
MemorySaver (保持对话历史)
  ↓
Web UI 响应
```

#### 核心代码

```java
@Configuration
public class ChatbotAgent {
    private static final String INSTRUCTION = """
        You are a helpful assistant named SAA.
        You have access to tools to execute shell commands,
        run Python code, and view text files.
        """;

    @Bean
    public ReactAgent chatbotReactAgent(ChatModel chatModel,
            ToolCallback executeShellCommand,
            ToolCallback executePythonCode,
            ToolCallback viewTextFile,
            MemorySaver memorySaver) {
        return ReactAgent.builder()
                .name("SAA")
                .model(chatModel)
                .instruction(INSTRUCTION)
                .enableLogging(true)
                .saver(memorySaver)  // 启用对话记忆
                .hooks(ShellToolAgentHook.builder()
                    .shellToolName(executeShellCommand.getToolDefinition().name())
                    .build())
                .tools(executeShellCommand, executePythonCode, viewTextFile)
                .build();
    }
}
```

#### 工具定义示例

```java
public class PythonTool {
    @Tool(
        name = "execute_python_code",
        description = "执行 Python 代码片段并返回输出"
    )
    public String executePythonCode(
            @ToolParam(description = "Python代码") String code) {
        // 实现逻辑
        return executeViaJython(code);
    }
}
```

#### 学习点

✅ 基础工具注册与调用
✅ MemorySaver 对话历史管理
✅ Hook 生命周期控制
✅ Web 聊天界面集成

---

### 2. DeepResearch 案例（复杂架构）

**定位**: 企业级 Agent，展示高级特性
**源文件**: `/deepresearch/DeepResearchAgent.java`, `Application.java`

#### 架构设计

```
主Agent (DeepResearchAgent)
├─ 子Agent 1: ResearchAgent (网络研究)
├─ 子Agent 2: CritiqueAgent (批评反馈)
│
├─ Interceptors (6个)
│  ├─ TodoListInterceptor (任务规划)
│  ├─ FilesystemInterceptor (文件访问)
│  ├─ LargeResultEvictionInterceptor (大结果卸载)
│  ├─ ContextEditingInterceptor (上下文压缩)
│  ├─ ToolRetryInterceptor (工具重试)
│  └─ SubAgentInterceptor (子Agent协调)
│
├─ Hooks (3个)
│  ├─ SummarizationHook (摘要触发)
│  ├─ HumanInTheLoopHook (人工审批)
│  └─ ToolCallLimitHook (调用限制)
│
└─ Tools
   ├─ search_web (网络搜索)
   ├─ file_ops (文件操作)
   ├─ code_tools (代码工具)
   └─ MCP工具 (Jina, etc.)
```

#### 子Agent定义

```java
// 研究Agent - 分解大任务为并行子任务
SubAgentSpec researchAgent = SubAgentSpec.builder()
        .name("research-agent")
        .description("""
            Used to research in-depth questions.
            Break down large topics into components and call
            multiple research agents in parallel for each sub-question.
            """)
        .systemPrompt("""
            你是一个深度研究专家。
            当面对大问题时：
            1. 分解为可管理的子问题
            2. 并行搜索各子问题
            3. 整合结果为连贯报告
            """)
        .enableLoopingLog(true)  // 记录推理过程
        .build();

// 批评Agent - 质量检查
SubAgentSpec critiqueAgent = SubAgentSpec.builder()
        .name("critique-agent")
        .description("""
            Used to critique the final report.
            Provide feedback on accuracy, completeness, and clarity.
            """)
        .systemPrompt("""
            你是一个批评家。评审报告并提供建设性反馈。
            关注：准确性、完整性、清晰度、偏见检测
            """)
        .enableLoopingLog(true)
        .build();
```

#### 关键Interceptors（核心学习点）

**1. TodoListInterceptor - 任务规划**

```java
// 自动生成任务列表，追踪完成状态
public class TodoListInterceptor extends ModelInterceptor {
    @Override
    public ModelResponse interceptModel(ModelRequest request,
            ModelCallHandler handler) {
        // 前置：从消息中提取TODO
        extractTodosFromMessages(request.getMessages());

        // 执行
        ModelResponse response = handler.call(request);

        // 后置：更新已完成的任务
        updateCompletedTodos(response);

        return response;
    }
}
```

**2. FilesystemInterceptor - 文件系统安全**

```java
// 限制 Agent 对文件的访问范围
public class FilesystemInterceptor extends ToolInterceptor {
    private static final Path ALLOWED_ROOT = Path.of("/tmp/research");

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request,
            ToolCallHandler handler) {

        // 检查是否为文件操作工具
        if (isFilesystemOperation(request)) {
            Path targetPath = extractPath(request);

            // 确保路径在允许范围内
            if (!isWithinAllowedRoot(targetPath)) {
                return ToolCallResponse.error(request.getToolCall(),
                    "文件访问被拒绝: " + targetPath);
            }
        }

        return handler.call(request);
    }
}
```

**3. LargeResultEvictionInterceptor - 自动卸载**

```java
// 自动将大结果卸载到文件，节省内存
public class LargeResultEvictionInterceptor extends ToolInterceptor {
    private static final int SIZE_THRESHOLD = 50_000;  // 50KB

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request,
            ToolCallHandler handler) {

        ToolCallResponse response = handler.call(request);

        String result = response.getTextContent();
        if (result.length() > SIZE_THRESHOLD) {
            // 保存到文件
            File tempFile = saveLargeResultToFile(result);

            // 返回文件引用而非全内容
            return ToolCallResponse.of(request.getToolCall(),
                "结果已保存到: " + tempFile.getAbsolutePath());
        }

        return response;
    }
}
```

**4. ContextEditingInterceptor - 自动压缩**

```java
// 当上下文接近限制时自动压缩
public class ContextEditingInterceptor extends ModelInterceptor {
    private static final int TOKEN_THRESHOLD = 10_000;

    @Override
    public ModelResponse interceptModel(ModelRequest request,
            ModelCallHandler handler) {

        int totalTokens = countTokens(request.getMessages());

        if (totalTokens > TOKEN_THRESHOLD) {
            // 提取关键摘要，移除冗余消息
            List<Message> compressed = compressMessages(request.getMessages());

            ModelRequest optimized = ModelRequest.builder(request)
                .messages(compressed)
                .build();

            return handler.call(optimized);
        }

        return handler.call(request);
    }
}
```

#### 学习点

✅ 多Agent协调与子Agent委托
✅ 6个拦截器的分层控制逻辑
✅ 任务规划与进度追踪
✅ 内存和上下文管理优化
✅ 文件系统安全访问控制

---

### 3. MultiAgent Pattern - Handoffs（多Agent协作）

#### 3.1 多Agent切换 (handoffs-multiagent)

**架构**

```
用户输入
  ↓
StateGraph 路由
  ├─ sales_agent (销售Assistant)
  │   └─ [工具: transfer_to_support]
  │       ↓
  └─ support_agent (支持Assistant)
      └─ [工具: transfer_to_sales]
```

**核心配置**

```java
@Configuration
public class MultiAgentHandoffsConfig {

    // Sales Agent
    @Bean
    public ReactAgent multiAgentSalesAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("sales_agent")
                .model(chatModel)
                .systemPrompt("""
                    你是销售助手。处理产品查询和报价。
                    如果客户需要技术支持，使用 transfer_to_support 工具转接。
                    """)
                .methodTools(new SalesTools())
                .build();
    }

    // Support Agent
    @Bean
    public ReactAgent multiAgentSupportAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("support_agent")
                .model(chatModel)
                .systemPrompt("""
                    你是技术支持助手。处理故障排查和技术问题。
                    如果需要销售信息，使用 transfer_to_sales 工具转接。
                    """)
                .methodTools(new SupportTools())
                .build();
    }

    // 状态图
    @Bean
    public CompiledGraph multiAgentHandoffsGraph(
            ReactAgent multiAgentSalesAgent,
            ReactAgent multiAgentSupportAgent) {

        StateGraph graph = new StateGraph("multi_agent_handoffs", () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put("messages", new AppendStrategy(false));
            strategies.put(ACTIVE_AGENT, new ReplaceStrategy());
            return strategies;
        });

        // 添加节点
        graph.addNode(SALES_AGENT, multiAgentSalesAgent.asNode());
        graph.addNode(SUPPORT_AGENT, multiAgentSupportAgent.asNode());

        // 条件路由
        graph.addConditionalEdges(START,
            new RouteInitialAction(),  // 初始路由到sales_agent
            Map.of("sales_agent", SALES_AGENT, "support_agent", SUPPORT_AGENT));

        graph.addConditionalEdges(SALES_AGENT,
            new RouteAfterSalesAction(),  // Sales内部路由
            Map.of("transfer", SUPPORT_AGENT, "end", END));

        graph.addConditionalEdges(SUPPORT_AGENT,
            new RouteAfterSupportAction(),  // Support内部路由
            Map.of("transfer", SALES_AGENT, "end", END));

        return graph.compile();
    }
}
```

**转接工具实现**

```java
public class SalesTools {
    @Tool(name = "transfer_to_support", returnDirect = true)
    public String transferToSupport(
            @ToolParam(description = "转接原因") String reason,
            ToolContext toolContext) {

        // 更新Agent状态
        ToolContextHelper.getStateForUpdate(toolContext)
            .ifPresent(update -> {
                update.put(ACTIVE_AGENT, "support_agent");
                update.put("transfer_reason", reason);
            });

        return "已转接至技术支持团队。";
    }
}
```

**路由类示例**

```java
public class RouteInitialAction implements Function<OverAllState, String> {
    @Override
    public String apply(OverAllState state) {
        // 根据用户输入决定初始Agent
        String input = (String) state.value("input").orElse("");

        if (input.contains("技术") || input.contains("故障")) {
            return "support_agent";
        } else {
            return "sales_agent";  // 默认销售
        }
    }
}
```

#### 3.2 单Agent多角色 (handoffs-singleagent)

**特点**: 通过Hook在单个Agent内实现角色转换

```java
@Configuration
public class HandoffsConfig {

    @Bean
    public ReactAgent supportAgent(ChatModel chatModel,
            MemorySaver memorySaver) {

        List<ToolCallback> tools = List.of(
            SupportTools.recordWarrantyStatusTool(),
            SupportTools.recordIssueTypeTool(),
            SupportTools.provideSolutionTool(),
            SupportTools.escalateToHumanTool()
        );

        return ReactAgent.builder()
                .name("support_agent")
                .model(chatModel)
                .tools(tools)
                .saver(memorySaver)
                .hooks(new HandoffsSupportHook())  // 动态改变行为的Hook
                .build();
    }
}

// Hook在工具调用时改变Agent的system prompt
@HookPositions({HookPosition.BEFORE_MODEL})
public class HandoffsSupportHook extends MessagesModelHook {
    @Override
    public AgentCommand beforeModel(List<Message> messages,
            RunnableConfig config) {

        // 检查之前是否有升级操作
        boolean escalated = (Boolean) config.context()
            .getOrDefault("escalated", false);

        if (escalated) {
            // 改变系统Prompt为"人工转接模式"
            String newSystemPrompt = """
                你已被转接给人工客服。
                请确认客户的问题并收集信息。
                """;

            List<Message> modified = new ArrayList<>(messages);
            modified.set(0, new SystemMessage(newSystemPrompt));

            return new AgentCommand(modified, UpdatePolicy.REPLACE);
        }

        return new AgentCommand(messages);
    }
}
```

---

### 4. Supervisor 模式（监督者模式）

**定位**: 中央控制多个专门Agent

```
用户请求 "安排会议并发送邮件通知"
  ↓
Supervisor Agent
  ├─ [识别]: 需要日历和邮件服务
  ├─ [调用]: CalendarAgent.schedule(...)
  ├─ [调用]: EmailAgent.send(...)
  └─ 综合结果返回用户
```

**核心实现**

```java
@Configuration
public class SupervisorConfig {

    // 专门Agent 1: 日历管理
    @Bean
    public ReactAgent calendarAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("calendar_agent")
                .systemPrompt("你是日历助手。只处理日程相关操作。")
                .instruction("{input}")
                .model(chatModel)
                .methodTools(CalendarStubTools.INSTANCE)
                .inputType(String.class)  // 接收字符串输入
                .build();
    }

    // 专门Agent 2: 邮件管理
    @Bean
    public ReactAgent emailAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("email_agent")
                .systemPrompt("你是邮件助手。只处理邮件相关操作。")
                .instruction("{input}")
                .model(chatModel)
                .methodTools(EmailStubTools.INSTANCE)
                .inputType(String.class)
                .build();
    }

    // 监督者Agent
    @Bean
    public ReactAgent supervisorAgent(ChatModel chatModel,
            ReactAgent calendarAgent,
            ReactAgent emailAgent) {

        // 将子Agent包装为工具
        ToolCallback scheduleEventTool =
            AgentTool.getFunctionToolCallback(calendarAgent);
        ToolCallback manageEmailTool =
            AgentTool.getFunctionToolCallback(emailAgent);

        return ReactAgent.builder()
                .name("supervisor_agent")
                .systemPrompt("""
                    你是个人助手。根据用户请求协调多个助手：
                    - 使用 schedule_event 处理日程
                    - 使用 manage_email 处理邮件
                    - 整合结果并汇报用户
                    """)
                .model(chatModel)
                .tools(scheduleEventTool, manageEmailTool)  // Agent作为工具
                .build();
    }
}
```

**Agent包装为工具的关键**

```java
// 关键API: AgentTool.getFunctionToolCallback()
ToolCallback agentAsTool = AgentTool.getFunctionToolCallback(agent);

// 等价于：
// - Agent.call(input) 被包装为一个工具
// - 工具的输入为 String，输出为 String
// - Supervisor 可以通过工具调用语法调用子Agent
```

#### 学习点

✅ Agent 包装为工具的模式
✅ `inputType(String.class)` 单一输入接口
✅ Supervisor 智能路由和组织
✅ 专业化 Agent 设计

---

### 5. Pipeline 模式（管道编排）

**三种类型**

#### 5.1 Sequential Pipeline（顺序执行）

```
输入 → SQL Generator → SQL Rater → 最终评分
       (生成SQL)     (评分0-1)
```

```java
@Configuration
public class SequentialPipelineConfig {

    @Bean
    public CompiledGraph sequentialPipeline(
            ReactAgent sqlGeneratorAgent,
            ReactAgent sqlRaterAgent) {

        StateGraph graph = new StateGraph("sequential_pipeline", () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put("messages", new AppendStrategy(false));
            strategies.put("sql_score", new ReplaceStrategy());
            return strategies;
        });

        // 顺序连接
        graph.addNode("sql_generator", sqlGeneratorAgent.asNode());
        graph.addNode("sql_rater", sqlRaterAgent.asNode());

        graph.addEdge(START, "sql_generator");
        graph.addEdge("sql_generator", "sql_rater");
        graph.addEdge("sql_rater", END);

        return graph.compile();
    }
}
```

#### 5.2 Parallel Pipeline（并行执行）

```
         ┌─ Tech Researcher ─┐
输入 ────┼─ Finance Researcher ┼─ 合并结果
         └─ Market Researcher ─┘
```

```java
// 并行Node定义
graph.addNode("tech_researcher", techAgent.asNode());
graph.addNode("finance_researcher", financeAgent.asNode());
graph.addNode("market_researcher", marketAgent.asNode());

// 从START同时指向三个Agent
graph.addEdges(START, List.of("tech_researcher", "finance_researcher",
    "market_researcher"));

// 三个Agent都完成后执行汇总
graph.addEdge("tech_researcher", "synthesize");
graph.addEdge("finance_researcher", "synthesize");
graph.addEdge("market_researcher", "synthesize");
graph.addEdge("synthesize", END);
```

#### 5.3 Loop Pipeline（循环执行）

```
         ┌─ SQL Generator ─┐
输入 ────┤                 ├─ 如果评分 < 0.5 则循环
         └─ SQL Rater ─────┘
                 │
          评分 >= 0.5 ?
                 │ Yes
                 ↓
               END
```

```java
// 条件路由
graph.addConditionalEdges("sql_rater",
    new Function<OverAllState, String>() {
        @Override
        public String apply(OverAllState state) {
            Double score = (Double) state.value("sql_score").orElse(0.0);
            return score >= 0.5 ? END : "sql_generator";
        }
    },
    Map.of("sql_generator", "sql_generator", END, END)
);
```

---

### 6. 其他重要模式

#### 6.1 Routing 模式（动态选择）

```java
// 根据输入类型选择不同Agent
public class InputRouter implements Function<OverAllState, String> {
    @Override
    public String apply(OverAllState state) {
        String input = (String) state.value("input").orElse("");

        if (input.contains("技术")) {
            return "tech_agent";
        } else if (input.contains("业务")) {
            return "business_agent";
        } else {
            return "general_agent";
        }
    }
}
```

#### 6.2 SubAgent 模式（任务委托）

```java
// 在主Agent中生成和调用子Agent
public class SubAgentInterceptor extends ModelInterceptor {
    @Override
    public ModelResponse interceptModel(ModelRequest request,
            ModelCallHandler handler) {

        ModelResponse response = handler.call(request);

        // 如果模型决定需要子Agent
        String content = response.getAssistantMessage().getText();
        if (content.contains("[DELEGATE]")) {
            // 动态创建子Agent
            ReactAgent subAgent = createSubAgentFor(extractTask(content));
            String subResult = subAgent.call(extractInput(content)).getText();

            // 将结果集成回响应
            return enhanceResponse(response, subResult);
        }

        return response;
    }
}
```

#### 6.3 Workflow 模式（复杂流程）

- **RAG Workflow**: 检索增强生成 (Retrieval + Generation)
- **SQL Workflow**: 自然语言转SQL查询
- **KG Workflow**: 知识图谱查询和推理

---

## 核心架构模式

### A. 工具系统

#### 三种工具定义方式

**1. MethodTools（方法工具）- 最常用**

```java
public class MyTools {
    @Tool(name = "search", description = "搜索...")
    public String search(@ToolParam String query) {
        return "结果...";
    }

    @Tool(name = "fetch", description = "获取...")
    public String fetch(@ToolParam String url) {
        return "内容...";
    }
}

// 使用
ReactAgent agent = ReactAgent.builder()
    .methodTools(new MyTools())  // 自动扫描@Tool
    .build();
```

**2. ToolCallback（显式回调）**

```java
ToolCallback customTool = FunctionToolCallback.builder(
    "my_tool",
    (input) -> {
        // 工具逻辑
        return "结果";
    }
)
.description("工具描述")
.inputType(String.class)
.build();

ReactAgent agent = ReactAgent.builder()
    .tools(customTool)
    .build();
```

**3. Agent作为工具**

```java
// 子Agent
ReactAgent childAgent = ReactAgent.builder()
    .name("child")
    .model(chatModel)
    .build();

// 包装为工具
ToolCallback agentTool = AgentTool.getFunctionToolCallback(childAgent);

// 在主Agent中使用
ReactAgent mainAgent = ReactAgent.builder()
    .tools(agentTool)
    .build();

// 调用时
mainAgent.call("使用child_agent工具完成任务");
```

### B. 状态管理

#### KeyStrategy（键策略）

```java
// 如何合并来自不同Agent的状态
Map<String, KeyStrategy> strategies = new HashMap<>();

// 1. AppendStrategy - 追加消息列表
strategies.put("messages", new AppendStrategy(false));

// 2. ReplaceStrategy - 替换状态
strategies.put("active_agent", new ReplaceStrategy());

// 3. AddValuesStrategy - 累加数值
strategies.put("scores", new AddValuesStrategy());

StateGraph graph = new StateGraph("my_graph", () -> strategies);
```

#### 访问和更新状态

```java
@Tool(name = "update_status")
public String updateStatus(ToolContext context) {
    // 读取状态
    OverAllState state = ToolContextHelper.getState(context).orElse(null);
    String currentAgent = (String) state.value("active_agent").orElse("none");

    // 更新状态
    ToolContextHelper.getStateForUpdate(context)
        .ifPresent(update -> {
            update.put("active_agent", "new_agent");
            update.put("timestamp", System.currentTimeMillis());
        });

    return "状态已更新";
}
```

### C. Hooks 执行生命周期

```
Agent 开始
  ↓
[AgentHook.beforeAgent] ← beforeAgent钩子
  ↓
ReAct 循环 (多轮)
  ├─ [MessagesModelHook.beforeModel] ← 消息修剪、过滤
  ├─ 调用 ChatModel
  ├─ [MessagesModelHook.afterModel] ← 响应后处理
  ├─ 解析工具调用
  ├─ 执行工具
  └─ 循环条件检查
  ↓
[AgentHook.afterAgent] ← afterAgent钩子
  ↓
Agent 结束，返回结果
```

#### 常见Hook组合

```java
ReactAgent agent = ReactAgent.builder()
    .name("optimized_agent")
    .model(chatModel)
    .hooks(
        // 1. 消息优化
        new MessageTrimmingHook(10),  // 保留最近10条消息

        // 2. 循环限制
        ModelCallLimitHook.builder().runLimit(5).build(),

        // 3. 人工在循环
        new HumanInTheLoopHook()
    )
    .build();
```

### D. Interceptors 请求拦截

```
ModelRequest → [ModelInterceptor] → ChatModel
ToolCallRequest → [ToolInterceptor] → Tool

拦截器可以：
- 前处理 (Pre): 修改请求
- 后处理 (Post): 修改响应
- 重试逻辑: 失败重试
- 验证: 输入/输出验证
```

#### 常见Interceptor

```java
ReactAgent agent = ReactAgent.builder()
    .name("guarded_agent")
    .model(chatModel)
    .interceptors(
        // 1. 内容安全检查
        new GuardrailInterceptor(),

        // 2. 工具监控
        new ToolMonitoringInterceptor(),

        // 3. 错误恢复
        new ErrorRecoveryInterceptor()
    )
    .build();
```

---

## 关键组件详解

### 1. ReactAgent 完整API

```java
ReactAgent agent = ReactAgent.builder()
    // 基础配置
    .name("my_agent")
    .model(chatModel)

    // 角色定义
    .systemPrompt("你是...")
    .instruction("按以下步骤处理: {input}")

    // 工具注册
    .tools(tool1, tool2)
    .methodTools(new MyTools())

    // 输出配置
    .outputType(MyOutputClass.class)
    .outputSchema(jsonSchema)

    // 记忆管理
    .saver(new MemorySaver())  // 或 RedisSaver

    // 执行控制
    .hooks(hook1, hook2)
    .interceptors(interceptor1, interceptor2)

    // 日志
    .enableLogging(true)

    .build();

// 调用方式
// 1. 简单调用 - 获取最终答案
AssistantMessage response = agent.call("用户输入");

// 2. 完整状态 - 获取中间过程
Optional<OverAllState> state = agent.invoke("输入");

// 3. 流式调用 - 实时输出
Flux<NodeOutput> stream = agent.stream("输入");

// 4. 带配置调用 - 传递context
RunnableConfig config = RunnableConfig.builder()
    .threadId("user_123")  // 对话ID
    .context(Map.of("custom_key", "value"))  // 自定义上下文
    .build();
AssistantMessage response = agent.call("输入", config);
```

### 2. StateGraph 完整API

```java
// 构建有状态的多Agent系统
StateGraph graph = new StateGraph("graph_name", () -> {
    // 定义状态管理策略
    Map<String, KeyStrategy> strategies = new HashMap<>();
    strategies.put("messages", new AppendStrategy(false));
    strategies.put("active_agent", new ReplaceStrategy());
    return strategies;
});

// 添加节点
graph.addNode("agent_1", agent1.asNode());
graph.addNode("agent_2", agent2.asNode());

// 添加边（直接连接）
graph.addEdge(START, "agent_1");
graph.addEdge("agent_1", "agent_2");
graph.addEdge("agent_2", END);

// 条件边（动态路由）
graph.addConditionalEdges(
    "agent_1",  // 源节点
    new MyRouter(),  // 路由函数
    Map.of(
        "route_to_2", "agent_2",
        "end", END
    )
);

// 编译为可执行图
CompiledGraph compiled = graph.compile();

// 调用
var result = compiled.invoke(
    Map.of("input", "用户输入"),
    RunnableConfig.builder().threadId("123").build()
);
```

### 3. ToolContext 上下文访问

```java
@Tool(name = "context_aware_tool")
public String contextAwareTool(
        @ToolParam String input,
        ToolContext toolContext) {

    // 1. 获取完整状态
    Optional<OverAllState> state =
        ToolContextHelper.getState(toolContext);

    // 2. 获取可更新的状态
    Optional<Map<String, Object>> updateableState =
        ToolContextHelper.getStateForUpdate(toolContext);

    // 3. 获取配置信息
    RunnableConfig config = toolContext.getRunnableConfig();
    String threadId = config.threadId();
    Map<String, Object> context = config.context();

    // 4. 使用状态和上下文
    if (state.isPresent()) {
        OverAllState overallState = state.get();
        List<Message> messages = (List<Message>)
            overallState.value("messages").orElse(List.of());

        // 基于历史消息做决策
        boolean hasContext = !messages.isEmpty();
    }

    if (updateableState.isPresent()) {
        updateableState.get().put("tool_executed", true);
    }

    return "处理完成";
}
```

---

## 针对 EchoFlow 的集成建议

### 现状分析

EchoFlow 已有的基础：

```
✅ StepExecutorRouter - 根据StepType路由到不同Executor
✅ Spring AI 1.1.2 集成
✅ MultiModelProperties - 多模型配置
✅ LLM函数调用支持 (Tools)
✅ SSE 流式输出
```

### 集成方向

#### 方向1: 从 Simple 到 Complex（渐进式）

**Phase 1 (当前)**: 基础工具调用
```
Task → StepExecutor → LLM 工具调用 → 结果
```

**Phase 2 (建议)**: 添加 Hooks 和 Interceptors
```
Task → StepExecutor → [MessageTrimmingHook]
  → [ToolMonitoringInterceptor] → LLM 工具调用
```

**Phase 3**: 多Agent协作
```
Task → MainExecutor (ReAct Agent)
  ├─ 调用 ThinkAgent
  ├─ 调用 ResearchAgent
  ├─ 调用 WriteAgent
  └─ 调用 NotifyAgent (通过工具委托)
```

**Phase 4**: 复杂管道
```
Task → Pipeline
  ├─ Parallel: Research + Analysis
  └─ Sequential: Synthesis → Validation
```

---

#### 方向2: 学习DeepResearch的架构

**建议采用的 Interceptors**：

```java
@Configuration
public class EchoFlowAgentConfig {

    @Bean
    public ReactAgent thinkExecutorAgent(ChatModel chatModel) {
        return ReactAgent.builder()
            .name("think_executor")
            .model(chatModel)
            .systemPrompt(thinkPrompt())

            // 关键: 添加这些Interceptors
            .interceptors(
                // 1. 结果验证
                new ExecutionResultValidationInterceptor(),

                // 2. 工具重试
                new ToolRetryInterceptor(3),  // 最多重试3次

                // 3. 超时控制
                new TimeoutInterceptor(30_000)  // 30秒超时
            )

            // 关键: 添加这些Hooks
            .hooks(
                // 1. 执行日志
                new ExecutionLoggingHook(),

                // 2. 消息优化
                new MessageTrimmingHook(20),  // 保留最近20条

                // 3. 循环限制
                ModelCallLimitHook.builder().runLimit(10).build()
            )

            .saver(new MemorySaver())  // 或改为 RedisSaver 用于生产
            .build();
    }
}
```

**关键Interceptor实现**：

```java
// 针对EchoFlow的执行结果验证
public class ExecutionResultValidationInterceptor extends ModelInterceptor {
    @Override
    public ModelResponse interceptModel(ModelRequest request,
            ModelCallHandler handler) {

        ModelResponse response = handler.call(request);
        String result = response.getAssistantMessage().getText();

        // 验证结果格式
        if (!isValidExecutionOutput(result)) {
            log.warn("无效的执行输出: {}", result);

            // 可选: 自动重试或返回默认值
            return ModelResponse.of(AssistantMessage.builder()
                .content("执行失败: 输出格式无效")
                .build());
        }

        return response;
    }

    private boolean isValidExecutionOutput(String result) {
        // 根据EchoFlow的需求验证
        return result != null && !result.trim().isEmpty();
    }

    @Override
    public String getName() {
        return "ExecutionResultValidationInterceptor";
    }
}

// 工具重试（针对网络/API故障）
public class ToolRetryInterceptor extends ToolInterceptor {
    private final int maxRetries;
    private final long backoffMs;

    public ToolRetryInterceptor(int maxRetries) {
        this(maxRetries, 1000);
    }

    public ToolRetryInterceptor(int maxRetries, long backoffMs) {
        this.maxRetries = maxRetries;
        this.backoffMs = backoffMs;
    }

    @Override
    public ToolCallResponse interceptToolCall(
            ToolCallRequest request, ToolCallHandler handler) {

        int retryCount = 0;
        Exception lastException = null;

        while (retryCount < maxRetries) {
            try {
                return handler.call(request);
            } catch (Exception e) {
                lastException = e;
                retryCount++;

                if (retryCount < maxRetries) {
                    try {
                        // 指数退避
                        long waitMs = backoffMs * (long) Math.pow(2, retryCount - 1);
                        Thread.sleep(waitMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        return ToolCallResponse.error(request.getToolCall(),
            "工具在 " + maxRetries + " 次重试后仍失败: " + lastException.getMessage());
    }

    @Override
    public String getName() {
        return "ToolRetryInterceptor";
    }
}
```

---

#### 方向3: 向 DeepResearch 模式升级

当EchoFlow需要处理**更复杂的长链推理任务**时，参考DeepResearch的架构：

```java
@Configuration
public class AdvancedEchoFlowAgentConfig {

    @Bean
    public ReactAgent researchAgent(ChatModel chatModel) {
        return ReactAgent.builder()
            .name("research_agent")
            .systemPrompt("""
                你是一个任务研究专家。
                对于复杂任务：
                1. 分解为子任务
                2. 并行收集信息
                3. 综合为连贯方案
                """)
            .model(chatModel)
            .methodTools(new ResearchTools())
            .enableLogging(true)
            .build();
    }

    @Bean
    public ReactAgent reviewAgent(ChatModel chatModel) {
        return ReactAgent.builder()
            .name("review_agent")
            .systemPrompt("""
                你是一个质量评审专家。
                检查：准确性、完整性、安全性
                """)
            .model(chatModel)
            .methodTools(new ReviewTools())
            .enableLogging(true)
            .build();
    }

    @Bean
    public ReactAgent mainExecutor(
            ChatModel chatModel,
            ReactAgent researchAgent,
            ReactAgent reviewAgent) {

        // 使用子Agent
        return ReactAgent.builder()
            .name("main_executor")
            .model(chatModel)

            // 关键: Interceptors处理Agent协调
            .interceptors(
                new SubAgentDispatchInterceptor(researchAgent, reviewAgent)
            )

            // 关键: Hooks处理整体流程
            .hooks(
                new TaskProgressTrackingHook(),
                ModelCallLimitHook.builder().runLimit(15).build()
            )

            .saver(new RedisSaver(redisTemplate))  // 使用Redis持久化
            .build();
    }
}
```

---

#### 方向4: 多Agent Pipeline（如果需要多步骤工作流）

```java
@Configuration
public class EchoFlowPipelineConfig {

    @Bean
    public CompiledGraph executionPipeline(
            ReactAgent thinkAgent,
            ReactAgent researchAgent,
            ReactAgent writeAgent,
            ReactAgent notifyAgent) {

        StateGraph graph = new StateGraph("execution_pipeline", () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put("messages", new AppendStrategy(false));
            strategies.put("current_step", new ReplaceStrategy());
            strategies.put("step_results", new AddValuesStrategy());
            return strategies;
        });

        // 构建顺序流程
        graph.addNode("think", thinkAgent.asNode());
        graph.addNode("research", researchAgent.asNode());
        graph.addNode("write", writeAgent.asNode());
        graph.addNode("notify", notifyAgent.asNode());

        // 顺序连接
        graph.addEdge(START, "think");
        graph.addEdge("think", "research");
        graph.addEdge("research", "write");
        graph.addEdge("write", "notify");
        graph.addEdge("notify", END);

        return graph.compile();
    }
}
```

---

### 具体实施步骤

#### Step 1: 添加Hook和Interceptor基础设施

```java
// 1. 在 infrastructure/ai 下创建 hooks 和 interceptors 包
//    infrastructure/ai/hooks/
//    ├── MessageTrimmingHook.java
//    ├── ExecutionLoggingHook.java
//    └── TimeoutHook.java
//
//    infrastructure/ai/interceptors/
//    ├── ToolRetryInterceptor.java
//    ├── ExecutionResultValidationInterceptor.java
//    └── ToolMonitoringInterceptor.java
```

#### Step 2: 升级 StepExecutorRouter

```java
// 修改后的StepExecutorRouter
@Component
public class StepExecutorRouter {

    // 使用ReactAgent替代简单的if-else
    private final Map<StepType, ReactAgent> executors;

    @Autowired
    public StepExecutorRouter(
            ChatClientProvider chatClientProvider,
            ExecutionEventPublisher eventPublisher) {
        this.executors = Map.ofEntries(
            Map.entry(StepType.THINK, buildThinkAgent(chatClientProvider)),
            Map.entry(StepType.RESEARCH, buildResearchAgent(chatClientProvider)),
            Map.entry(StepType.WRITE, buildWriteAgent(chatClientProvider)),
            Map.entry(StepType.NOTIFY, buildNotifyAgent(chatClientProvider))
        );
    }

    private ReactAgent buildThinkAgent(ChatClientProvider provider) {
        ChatModel chatModel = provider.getChatClientForStepType(StepType.THINK);

        return ReactAgent.builder()
            .name("think_executor")
            .model(chatModel)
            .systemPrompt("""
                你是一个分析型思维工具。
                分析任务需求，制定执行计划。
                """)
            .methodTools(new ThinkTools())
            .hooks(
                new MessageTrimmingHook(10),
                ModelCallLimitHook.builder().runLimit(5).build()
            )
            .interceptors(
                new ToolRetryInterceptor(2)
            )
            .saver(new MemorySaver())
            .build();
    }

    // 类似buildResearchAgent, buildWriteAgent, buildNotifyAgent

    public ExecutionStepResult executeStep(ExecutionStep step) {
        ReactAgent executor = executors.get(step.getType());

        if (executor == null) {
            throw new UnsupportedOperationException(
                "不支持的步骤类型: " + step.getType());
        }

        // 调用Agent
        String input = step.getInput();
        AssistantMessage response = executor.call(input);

        return ExecutionStepResult.builder()
            .stepId(step.getId())
            .output(response.getText())
            .status(ExecutionStatus.COMPLETED)
            .build();
    }
}
```

#### Step 3: 集成到现有的SseExecutionEventPublisher

```java
// 利用Agent的stream方法实现真实流式输出
@Service
public class StreamingStepExecutor {

    public void executeStepWithStreaming(
            ExecutionStep step,
            ReactAgent executor,
            SseEmitter emitter) {

        Flux<NodeOutput> stream = executor.stream(step.getInput());

        stream.subscribe(
            output -> {
                // 发送到前端
                try {
                    String json = objectMapper.writeValueAsString(output);
                    emitter.send(SseEmitter.event()
                        .data(json)
                        .id(UUID.randomUUID().toString())
                        .name("agent_output")
                        .build());
                } catch (IOException e) {
                    log.error("SSE写入失败", e);
                }
            },
            error -> {
                log.error("Agent执行出错", error);
                try {
                    emitter.send(SseEmitter.event()
                        .data(error.getMessage())
                        .name("error")
                        .build());
                } catch (IOException io) {
                    log.error("SSE错误消息发送失败", io);
                }
            },
            () -> {
                try {
                    emitter.complete();
                } catch (IOException e) {
                    log.error("SSE完成信号发送失败", e);
                }
            }
        );
    }
}
```

---

### 优先级路线图

```
Q1 2026 (现在)
├─ Phase 1A: 理解examples中的模式 ✓
├─ Phase 1B: 添加基础Hook和Interceptor
└─ Phase 1C: 升级StepExecutorRouter为ReactAgent

Q2 2026
├─ Phase 2A: 实现工具重试和超时控制
├─ Phase 2B: 添加消息优化和日志跟踪
└─ Phase 2C: 实现子Agent协调（THINK参考ResearchAgent）

Q3 2026
├─ Phase 3A: 支持简单的Agent间通信（工具委托）
├─ Phase 3B: 添加对复杂推理的支持（DeepResearch style）
└─ Phase 3C: 性能优化（Token限制、上下文压缩）

Q4 2026+
├─ Phase 4A: 支持多Agent Pipeline
├─ Phase 4B: 支持实时语音交互（VoiceAgent style）
└─ Phase 4C: 知识图谱和RAG集成
```

---

## 参考资源汇总

### 推荐学习顺序

1. **快速理解** (2小时)
   - 读本文 [实践案例总览](#实践案例总览) 和 [核心架构模式](#核心架构模式)
   - 运行 `/examples/chatbot/` 本地体验

2. **深入学习** (1天)
   - 研究 `/examples/documentation/tutorials/` 下的所有示例
   - 对比分析 `/examples/multiagent-patterns/` 的8种模式

3. **实战应用** (3-5天)
   - 阅读 `/examples/deepresearch/` 的完整架构
   - 提取与EchoFlow相关的Interceptor和Hook实现

4. **集成EchoFlow** (2周)
   - Phase 1B-1C: 升级StepExecutorRouter
   - 参考 [具体实施步骤](#具体实施步骤)

### 关键文件清单

```
examples/
├── chatbot/ChatbotAgent.java                    ← 入门
├── documentation/tutorials/AgentsExample.java   ← 基础API
├── documentation/advanced/                      ← 高级特性
│   ├── MultiAgentExample.java
│   ├── HumanInTheLoopExample.java
│   └── ToolSelectionExample.java
├── deepresearch/DeepResearchAgent.java          ← 企业级架构
│   └── interceptors/                            ← 核心学习点
├── multiagent-patterns/                         ← 8种模式
│   ├── handoffs-multiagent/
│   ├── supervisor/SupervisorConfig.java         ← Agent作为工具
│   └── pipeline/                                ← 管道编程
└── voice-agent/VoiceAgentConfig.java            ← 流式交互
```

### 代码仓库

- **官方文档**: https://java2ai.com/docs/frameworks/agent-framework/
- **Spring AI Alibaba**: https://github.com/alibaba/spring-ai-alibaba
- **Examples**: 见本文开头路径

---

## 总结

Spring AI Alibaba examples 提供了**从简单到复杂、从单Agent到多Agent、从文本到多模态**的完整学习路径。

**对EchoFlow的最大价值**：
- ✅ **Hooks和Interceptor** 模式用于细粒度控制
- ✅ **DeepResearch架构** 适合处理复杂长链推理
- ✅ **多Agent Pattern** 为future扩展提供清晰路线
- ✅ **StateGraph** 支持复杂的工作流编排
- ✅ **ToolContext** 实现Agent间状态共享

**立即可用的技术**：
- Hook生命周期管理 → 添加到StepExecutor
- Interceptor工具重试 → 提高可靠性
- MessageTrimmingHook → 优化Token使用
- ToolMonitoring → 增强可观测性

**6个月内可支持**：
- 多Agent协作（基于Agent作为工具的模式）
- 任务分解与并行处理
- 自适应Agent选择

---

*本文档将与 `spring-ai-alibaba-agent-framework-guide.md` 配套使用，前者为理论指南，本文为实践案例参考。*
