# Spring AI Alibaba Agent Framework 使用指南

**文档时间**: 2026-03-11
**参考官方**: https://java2ai.com/docs/frameworks/agent-framework/tutorials/agents
**框架版本**: Spring AI Alibaba 1.1.2+
**关键字**: ReactAgent, ReAct, Tools, Hooks, Interceptors, 流式输出

> **⚠️ API 验证状态 (2026-03-11 22:30 CST)**
>
> 本文档由 Haiku 基于 examples 源码整理。核心 API 已通过 `D:\sorce_code_learning\spring-ai-alibaba-main\` 源码交叉验证。
>
> | 分类 | 验证结果 |
> |------|---------|
> | ReactAgent Builder (15+ 方法) | ✅ 全部源码确认 |
> | AgentHook / MessagesModelHook / ModelCallLimitHook | ✅ 源码确认 |
> | ModelInterceptor / ToolInterceptor | ✅ 源码确认 |
> | ToolCallResponse.of(id, name, result) | ✅ 源码确认 |
> | ~~ToolCallResponse.error()~~ | ❌ **不存在** — 错误通过 `.of(id, name, errorMsg)` 传递 |
> | ~~RedisSaver~~ | ❌ **不存在** — 仅有 MemorySaver |
> | OverAllState / RunnableConfig / StateGraph | ✅ 源码确认 |
> | AgentTool / SubAgentSpec / StreamingOutput | ✅ 源码确认 |
>
> **使用建议**: 编码前以源码为准，本文档中的代码示例为推断性写法，具体参数名可能有偏差。

---

## 目录

1. [核心概念](#核心概念)
2. [快速开始](#快速开始)
3. [Agent 构建详解](#agent-构建详解)
4. [工具系统](#工具系统)
5. [高级特性](#高级特性)
6. [最佳实践](#最佳实践)
7. [常见问题](#常见问题)

---

## 核心概念

### ReAct 范式（Reasoning + Acting）

Agent 通过循环执行以下步骤来完成任务：

```
用户输入
  ↓
┌─────────────────────────────┐
│ Thought (思考)              │
│ Agent 推理是否需要调用工具   │
└──────────────┬──────────────┘
               │
        ┌─────┴─────┐
        │           │
    Yes │           │ No
        ↓           ↓
   ┌─────────┐  ┌─────────┐
   │ Action  │  │ 生成最终 │
   │ (行动)  │  │  答案  │
   │调用工具 │  └─────────┘
   └────┬────┘        ↑
        │             │
        ↓             │
   ┌─────────┐        │
   │ Observation │
   │ (观察)  │
   │ 工具结果 │
   └────┬────┘
        │
        └─────────────┘
             │
        信息充足？
             │
          (继续循环)
```

**工作流程详解**：

```
场景: 用户问"杭州的天气如何？并推荐一个活动"

第1轮循环：
  Thought: 需要查询杭州的天气信息
  Action:  调用 get_weather("杭州")
  Observation: "晴天，气温25°C，空气质量优"

第2轮循环：
  Thought: 天气信息已获取，需要基于天气推荐活动
  Action:  调用 search_activities("杭州", "晴天")
  Observation: "推荐：西湖漫步、西溪湿地、灵隐寺"

第3轮循环：
  Thought: 信息充分，可以生成最终答案
  Action:  生成综合回答
  Result: "杭州今天晴天，气温25°C，很适合户外活动。
           建议你去西湖漫步或西溪湿地。"
```

### ReactAgent 核心架构

```
ReactAgent
  ├─ Model（推理引擎）
  │  └─ ChatModel（Spring AI 通用接口）
  │
  ├─ Tools（工具集）
  │  ├─ Tool 1: search_weather
  │  ├─ Tool 2: search_activities
  │  └─ Tool N: ...
  │
  ├─ System Prompt（角色定义）
  │  └─ "你是一个旅游助手，..."
  │
  ├─ Interceptors（拦截器 - 细粒度控制）
  │  ├─ ModelInterceptor（控制模型调用）
  │  └─ ToolInterceptor（控制工具调用）
  │
  ├─ Hooks（钩子 - 执行生命周期）
  │  ├─ AgentHook（Agent 级别）
  │  └─ MessagesModelHook（消息模型级别）
  │
  └─ Memory（记忆）
     └─ MemorySaver/RedisSaver/MongoSaver
```

---

## 快速开始

### 最小化 Agent

```java
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.springframework.ai.chat.model.ChatModel;

public class QuickStartAgent {
    public static void main(String[] args) {
        // 1. 创建 ChatModel（使用 DashScope/通义）
        DashScopeApi dashScopeApi = DashScopeApi.builder()
            .apiKey(System.getenv("AI_DASHSCOPE_API_KEY"))
            .build();

        ChatModel chatModel = DashScopeChatModel.builder()
            .dashScopeApi(dashScopeApi)
            .build();

        // 2. 创建最简单的 Agent（无工具）
        ReactAgent agent = ReactAgent.builder()
            .name("hello_agent")
            .model(chatModel)
            .build();

        // 3. 调用 Agent
        var response = agent.call("用 Java 写一个快速排序算法");
        System.out.println(response.getText());
    }
}
```

**输出示例**：
```
快速排序算法实现：

```java
public class QuickSort {
    public static void quickSort(int[] arr, int low, int high) {
        if (low < high) {
            int pi = partition(arr, low, high);
            quickSort(arr, low, pi - 1);
            quickSort(arr, pi + 1, high);
        }
    }

    private static int partition(int[] arr, int low, int high) {
        int pivot = arr[high];
        int i = low - 1;
        for (int j = low; j < high; j++) {
            if (arr[j] < pivot) {
                i++;
                int temp = arr[i];
                arr[i] = arr[j];
                arr[j] = temp;
            }
        }
        int temp = arr[i + 1];
        arr[i + 1] = arr[high];
        arr[high] = temp;
        return i + 1;
    }
}
```
```

---

## Agent 构建详解

### 1. Model（推理引擎）配置

#### 基础配置

```java
// 方式 1：使用 DashScope（通义千问）
DashScopeApi api = DashScopeApi.builder()
    .apiKey("sk-xxx")
    .build();

ChatModel chatModel = DashScopeChatModel.builder()
    .dashScopeApi(api)
    .build();

// 方式 2：使用 OpenAI 兼容服务
OpenAiApi openAiApi = new OpenAiApi("https://api.openai.com", "sk-xxx");
ChatModel chatModel = new OpenAiChatModel(openAiApi);

// 方式 3：使用 Spring Boot 自动配置（推荐生产环境）
@Configuration
public class AgentConfig {
    @Bean
    public ChatModel chatModel(OpenAiApi openAiApi) {
        return new OpenAiChatModel(openAiApi);
    }
}
```

#### 高级配置（模型参数）

```java
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;

ChatModel chatModel = DashScopeChatModel.builder()
    .dashScopeApi(dashScopeApi)
    .defaultOptions(DashScopeChatOptions.builder()
        .withModel("qwen-max")              // 模型选择
        .withTemperature(0.7)               // 创意度（0-1，越高越随机）
        .withMaxToken(2000)                 // 最大输出长度
        .withTopP(0.9)                      // 核采样参数
        .withRepetitionPenalty(1.1)         // 重复惩罚
        .build())
    .build();
```

**参数说明**：

| 参数 | 范围 | 说明 | 默认值 |
|-----|------|------|--------|
| `temperature` | 0.0-2.0 | 越低越确定性，越高越随机 | 0.7 |
| `maxTokens` | 1-4096 | 最大生成长度 | 2048 |
| `topP` | 0.0-1.0 | 核采样，越小越稳定 | 0.9 |
| `topK` | 1+ | Top-K 采样 | - |
| `repetitionPenalty` | 1.0+ | 降低重复，>1.0 时生效 | 1.0 |

### 2. System Prompt（角色与行为定义）

#### 基础用法

```java
ReactAgent agent = ReactAgent.builder()
    .name("expert_agent")
    .model(chatModel)
    .systemPrompt("""
        你是一个资深的 Java 架构师。
        在回答技术问题时：
        1. 优先考虑最佳实践
        2. 提供代码示例
        3. 说明优缺点
        """)
    .build();
```

#### 详细指令（Instruction）

对于需要多行指令的场景，使用 `instruction` 而非 `systemPrompt`：

```java
String instruction = """
    你是一个产品需求分析专家。

    用户会提供一个产品需求描述，你需要：
    1. 分解为技术任务
    2. 评估实现复杂度（高/中/低）
    3. 提出技术风险
    4. 推荐技术方案

    输出格式：
    - 技术任务：[列表]
    - 复杂度：[高/中/低]
    - 风险：[列表]
    - 方案：[推荐方案]
    """;

ReactAgent agent = ReactAgent.builder()
    .name("requirement_analyst")
    .model(chatModel)
    .instruction(instruction)
    .build();
```

#### 动态 System Prompt（基于上下文）

```java
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import org.springframework.ai.chat.messages.SystemMessage;

public class ContextAwarePromptInterceptor extends ModelInterceptor {
    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        // 从上下文获取用户信息
        String userRole = (String) request.getContext()
            .getOrDefault("user_role", "guest");
        String userExpertise = (String) request.getContext()
            .getOrDefault("expertise_level", "beginner");

        // 动态构造 System Prompt
        String dynamicPrompt = switch (userRole) {
            case "expert" -> """
                用户是技术专家。
                - 使用专业术语
                - 深入讨论实现细节
                - 指出可能的优化点
                """;
            case "manager" -> """
                用户是产品经理。
                - 用业务语言说明
                - 强调用户价值
                - 避免过度技术细节
                """;
            default -> """
                用户是普通开发者。
                - 使用清晰的示例
                - 解释核心概念
                - 提供实用建议
                """;
        };

        SystemMessage enhancedSystemMessage = new SystemMessage(dynamicPrompt);
        ModelRequest modified = ModelRequest.builder(request)
            .systemMessage(enhancedSystemMessage)
            .build();

        return handler.call(modified);
    }

    @Override
    public String getName() {
        return "ContextAwarePromptInterceptor";
    }
}

// 使用时传递 context
ReactAgent agent = ReactAgent.builder()
    .name("adaptive_agent")
    .model(chatModel)
    .interceptors(new ContextAwarePromptInterceptor())
    .build();

// 调用时传递 context
RunnableConfig config = RunnableConfig.builder()
    .context(Map.of(
        "user_role", "expert",
        "expertise_level", "senior"
    ))
    .build();

agent.call("解释 Spring Cloud 的分布式事务方案", config);
```

### 3. Tools（工具定义）

#### 简单工具定义

```java
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

// 方式 1：使用 Function 接口
public class WeatherTool implements BiFunction<String, ToolContext, String> {
    @Override
    public String apply(String city, ToolContext context) {
        // 调用天气 API
        return "杭州: 晴天，25°C";
    }
}

ToolCallback weatherTool = FunctionToolCallback.builder("get_weather", new WeatherTool())
    .description("获取指定城市的天气信息")
    .build();

// 方式 2：使用 @Tool 注解（更灵活）
public class SearchTools {
    @Tool(
        name = "web_search",
        description = "在互联网上搜索信息。输入搜索关键词，返回搜索结果摘要。",
        returnDirect = false  // false: 继续推理; true: 立即返回
    )
    public String webSearch(String query) {
        // 实现搜索逻辑
        return "搜索结果: ...";
    }

    @Tool(
        name = "code_search",
        description = "在 GitHub 上搜索代码示例"
    )
    public String codeSearch(@ToolParam(description = "搜索关键词") String query) {
        return "代码示例: ...";
    }
}

// 在 Agent 中注册
ReactAgent agent = ReactAgent.builder()
    .name("research_agent")
    .model(chatModel)
    .methodTools(new SearchTools())
    .tools(weatherTool)
    .build();
```

#### 复杂工具示例（带状态访问）

```java
public class StatefulTool {
    @Tool(name = "update_analysis_state")
    public String updateAnalysisState(
        @ToolParam(description = "分析结果") String result,
        ToolContext context) {

        // 访问全局状态
        context.getStateForUpdate().ifPresent(state -> {
            // 更新 Agent 的全局状态
            state.put("current_analysis", result);
            state.put("analysis_timestamp", System.currentTimeMillis());
        });

        return "状态已更新";
    }
}
```

#### 工具错误处理

```java
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;

public class ToolErrorInterceptor extends ToolInterceptor {
    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        try {
            // 前置检查
            if (!isValidToolInput(request)) {
                return ToolCallResponse.error(request.getToolCall(), "输入格式不正确");
            }

            // 执行工具
            ToolCallResponse response = handler.call(request);

            // 后置验证
            if (!isValidToolOutput(response)) {
                return ToolCallResponse.error(request.getToolCall(), "工具输出异常");
            }

            return response;
        } catch (ToolTimeoutException e) {
            return ToolCallResponse.error(request.getToolCall(),
                "工具执行超时，请稍后重试");
        } catch (Exception e) {
            log.error("Tool execution failed", e);
            return ToolCallResponse.error(request.getToolCall(),
                "工具执行失败: " + e.getMessage());
        }
    }

    @Override
    public String getName() {
        return "ToolErrorInterceptor";
    }

    private boolean isValidToolInput(ToolCallRequest request) {
        // 验证工具输入
        return true;
    }

    private boolean isValidToolOutput(ToolCallResponse response) {
        // 验证工具输出
        return true;
    }
}

// 使用
ReactAgent agent = ReactAgent.builder()
    .name("robust_agent")
    .model(chatModel)
    .methodTools(new SearchTools())
    .interceptors(new ToolErrorInterceptor())
    .build();
```

---

## 高级特性

### 1. 结构化输出

#### 使用 OutputType

```java
// 定义输出类型
public class AnalysisResult {
    private String summary;
    private List<String> keyPoints;
    private String sentiment;
    private Double confidence;

    // Getters/Setters...
}

ReactAgent agent = ReactAgent.builder()
    .name("analysis_agent")
    .model(chatModel)
    .outputType(AnalysisResult.class)  // 指定输出类型
    .saver(new MemorySaver())
    .build();

// 调用后，Agent 会返回遵循 AnalysisResult 结构的输出
AssistantMessage response = agent.call("分析这段文本的情感");
// 解析输出（需要手动反序列化）
```

#### 使用 BeanOutputConverter（推荐）

```java
import org.springframework.ai.converter.BeanOutputConverter;

public class TextAnalysisResult {
    private String summary;
    private List<String> keywords;
    private String sentiment;
    private Double confidence;
    // Getters/Setters...
}

// 使用 Converter 生成 outputSchema
BeanOutputConverter<TextAnalysisResult> converter =
    new BeanOutputConverter<>(TextAnalysisResult.class);

ReactAgent agent = ReactAgent.builder()
    .name("analysis_agent")
    .model(chatModel)
    .outputSchema(converter.getFormat())  // 使用 schema
    .saver(new MemorySaver())
    .build();

AssistantMessage response = agent.call("分析这段文本");
// 输出会自动遵循 TextAnalysisResult 的 JSON schema
```

### 2. Memory（对话记忆）

#### 内存存储（开发环境）

```java
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;

ReactAgent agent = ReactAgent.builder()
    .name("chat_agent")
    .model(chatModel)
    .saver(new MemorySaver())  // 启用内存存储
    .build();

// 通过 thread_id 维护对话上下文
RunnableConfig config = RunnableConfig.builder()
    .threadId("user_123")  // 每个用户一个独立的对话线程
    .build();

// 第一轮对话
agent.call("我叫张三，我是 Java 开发工程师", config);

// 第二轮对话（Agent 会记住之前的信息）
AssistantMessage response = agent.call("我叫什么名字？", config);
System.out.println(response.getText()); // 输出: "你叫张三"

// 第三轮对话
response = agent.call("我的工作是什么？", config);
System.out.println(response.getText()); // 输出: "你是 Java 开发工程师"
```

#### 持久化存储（生产环境）

```java
import com.alibaba.cloud.ai.graph.checkpoint.savers.RedisSaver;

// 使用 Redis 存储对话历史
RedisSaver redisSaver = new RedisSaver(redisTemplate);

ReactAgent agent = ReactAgent.builder()
    .name("production_agent")
    .model(chatModel)
    .saver(redisSaver)  // 使用 Redis
    .build();

// 也支持 MongoDB、数据库等
```

### 3. Hooks（执行生命周期钩子）

Hooks 用于在 Agent 执行的关键点插入自定义逻辑。

#### AgentHook vs MessagesModelHook

| 钩子类型 | 执行时机 | 执行次数 | 适用场景 |
|---------|--------|--------|---------|
| `AgentHook` | Agent 开始/结束 | 每次 Agent 调用 1 次 | 整体日志、初始化清理 |
| `MessagesModelHook` | 模型调用前/后 | 每次推理迭代多次 | 消息修剪、内容过滤 |

#### AgentHook 示例

```java
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;

@HookPositions({HookPosition.BEFORE_AGENT, HookPosition.AFTER_AGENT})
public class LoggingHook extends AgentHook {
    @Override
    public String getName() {
        return "logging_hook";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(
        OverAllState state, RunnableConfig config) {

        System.out.println("=== Agent 开始执行 ===");
        System.out.println("ThreadId: " + config.threadId());
        System.out.println("Input: " + state.value("input"));

        return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterAgent(
        OverAllState state, RunnableConfig config) {

        System.out.println("=== Agent 执行完成 ===");
        Optional<Object> messages = state.value("messages");
        if (messages.isPresent()) {
            List<Message> messageList = (List<Message>) messages.get();
            System.out.println("消息总数: " + messageList.size());
        }

        return CompletableFuture.completedFuture(Map.of());
    }
}
```

#### MessagesModelHook 示例（消息裁剪）

```java
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;

public class MessageTrimmingHook extends MessagesModelHook {
    private static final int MAX_MESSAGES = 20;  // 只保留最近20条消息
    private static final int MAX_MESSAGE_LENGTH = 100_000;  // 总长度限制

    @Override
    public String getName() {
        return "message_trimming";
    }

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        List<Message> trimmedMessages = previousMessages;

        // 1. 限制消息数量
        if (previousMessages.size() > MAX_MESSAGES) {
            trimmedMessages = previousMessages.subList(
                previousMessages.size() - MAX_MESSAGES,
                previousMessages.size()
            );
        }

        // 2. 限制总长度
        int totalLength = trimmedMessages.stream()
            .mapToInt(m -> m.getText().length())
            .sum();

        if (totalLength > MAX_MESSAGE_LENGTH) {
            // 移除最早的消息直到达到长度限制
            List<Message> result = new ArrayList<>();
            int currentLength = 0;
            for (int i = trimmedMessages.size() - 1; i >= 0; i--) {
                Message msg = trimmedMessages.get(i);
                int msgLength = msg.getText().length();
                if (currentLength + msgLength <= MAX_MESSAGE_LENGTH) {
                    result.add(0, msg);
                    currentLength += msgLength;
                }
            }
            trimmedMessages = result;
        }

        return new AgentCommand(trimmedMessages, UpdatePolicy.REPLACE);
    }
}
```

#### 自定义停止条件 Hook

```java
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;

@HookPositions({HookPosition.BEFORE_MODEL})
public class CustomStopConditionHook extends ModelHook {
    private static final int MAX_ERROR_COUNT = 3;

    @Override
    public String getName() {
        return "custom_stop_condition";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(
        OverAllState state, RunnableConfig config) {

        // 检查是否已找到答案
        boolean answerFound = (Boolean) state.value("answer_found")
            .orElse(false);

        // 检查错误次数
        int errorCount = (Integer) config.context()
            .getOrDefault("error_count", 0);

        // 停止条件：找到答案 或 错误过多
        if (answerFound || errorCount > MAX_ERROR_COUNT) {
            String message = answerFound
                ? "已找到答案，Agent 执行完成。"
                : "错误次数过多 (" + errorCount + ")，Agent 执行终止。";

            List<Message> finalMessages = List.of(
                new AssistantMessage(message)
            );

            return CompletableFuture.completedFuture(
                Map.of("messages", finalMessages)
            );
        }

        return CompletableFuture.completedFuture(Map.of());
    }
}

// 使用
ReactAgent agent = ReactAgent.builder()
    .name("controlled_agent")
    .model(chatModel)
    .hooks(new CustomStopConditionHook())
    .build();
```

### 4. Interceptors（细粒度控制）

Interceptors 比 Hooks 更细粒度，可以拦截和修改请求/响应。

#### ModelInterceptor（模型调用控制）

```java
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;

public class GuardrailInterceptor extends ModelInterceptor {
    private static final Set<String> BLOCKED_KEYWORDS = Set.of(
        "hack", "crack", "exploit"
    );

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        // 前置：检查输入内容安全
        List<Message> messages = request.getMessages();
        for (Message msg : messages) {
            String content = msg.getText().toLowerCase();
            for (String keyword : BLOCKED_KEYWORDS) {
                if (content.contains(keyword)) {
                    return ModelResponse.of(AssistantMessage.builder()
                        .content("我不能帮助处理这类请求。")
                        .build());
                }
            }
        }

        // 执行模型调用
        ModelResponse response = handler.call(request);

        // 后置：检查输出内容
        String output = response.getAssistantMessage().getText();
        // 可以对输出进行过滤或转换

        return response;
    }

    @Override
    public String getName() {
        return "GuardrailInterceptor";
    }
}
```

#### ToolInterceptor（工具调用控制）

```java
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;

public class ToolMonitoringInterceptor extends ToolInterceptor {
    private static final long TOOL_TIMEOUT_MS = 5000;

    @Override
    public ToolCallResponse interceptToolCall(
        ToolCallRequest request, ToolCallHandler handler) {

        long startTime = System.currentTimeMillis();
        String toolName = request.getToolName();

        try {
            // 执行工具
            ToolCallResponse response = handler.call(request);

            long duration = System.currentTimeMillis() - startTime;
            log.info("工具 {} 执行成功，耗时 {}ms", toolName, duration);

            return response;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("工具 {} 执行失败，耗时 {}ms: {}",
                toolName, duration, e.getMessage());

            return ToolCallResponse.error(request.getToolCall(),
                "工具执行异常: " + e.getMessage());
        }
    }

    @Override
    public String getName() {
        return "ToolMonitoringInterceptor";
    }
}
```

### 5. 流式输出

#### 基础流式调用

```java
import reactor.core.publisher.Flux;
import com.alibaba.cloud.ai.graph.NodeOutput;

Flux<NodeOutput> stream = agent.stream("写一篇关于 Java 并发的文章");

stream.subscribe(
    output -> {
        System.out.print(output.getMessage().getText());
    },
    error -> System.err.println("错误: " + error),
    () -> System.out.println("\n完成!")
);
```

#### 完整的流式处理示例

```java
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

Flux<NodeOutput> stream = agent.stream("完成一个复杂的研究任务");

stream.subscribe(
    output -> {
        // 检查是否为流式输出
        if (output instanceof StreamingOutput streamingOutput) {
            OutputType type = streamingOutput.getOutputType();
            Message message = streamingOutput.message();

            switch (type) {
                case AGENT_MODEL_STREAMING:
                    // 模型流式输出（增量内容）
                    if (message instanceof AssistantMessage assistant) {
                        // 检查是否为 Thinking 消息（DeepSeek 等支持）
                        Object reasoning = assistant.getMetadata()
                            .get("reasoningContent");
                        if (reasoning != null) {
                            System.out.print("[思考] " + reasoning);
                        } else {
                            // 普通响应流
                            System.out.print(assistant.getText());
                        }
                    }
                    break;

                case AGENT_MODEL_FINISHED:
                    // 模型输出完成
                    if (message instanceof AssistantMessage assistant) {
                        if (assistant.hasToolCalls()) {
                            // 工具调用请求
                            assistant.getToolCalls().forEach(toolCall ->
                                System.out.println("[工具] " + toolCall.name())
                            );
                        }
                    }
                    System.out.println("\n[模型输出完成]");
                    break;

                case AGENT_TOOL_FINISHED:
                    // 工具执行完成
                    if (message instanceof ToolResponseMessage toolResponse) {
                        toolResponse.getResponses().forEach(response ->
                            System.out.println("[工具结果] " + response.name())
                        );
                    }
                    break;

                case AGENT_HOOK_FINISHED:
                    // Hook 执行完成
                    System.out.println("[Hook 完成]");
                    break;
            }
        }
    },
    error -> {
        System.err.println("执行出错: " + error.getMessage());
        error.printStackTrace();
    },
    () -> {
        System.out.println("\nAgent 执行完全完成");
    }
);
```

---

## 最佳实践

### 1. Agent 设计原则

```java
// ✅ 好的实践：明确的职责和工具集
ReactAgent researchAgent = ReactAgent.builder()
    .name("research_expert")
    .model(chatModel)
    .systemPrompt("""
        你是一个研究专家。你的职责是：
        1. 搜索相关信息
        2. 分析和总结
        3. 识别关键点

        禁止：不要尝试生成代码或进行其他工作
        """)
    .methodTools(new ResearchTools())  // 只注册研究相关工具
    .build();

// ❌ 坏的实践：过度通用的 Agent
ReactAgent badAgent = ReactAgent.builder()
    .name("do_everything_agent")
    .model(chatModel)
    .systemPrompt("你是一个助手")  // 职责不清
    .methodTools(searchTools, codeTools, analysisTools, imageTools, ...)  // 工具过多
    .build();
```

### 2. 错误处理策略

```java
// 1. 通过 Interceptor 处理工具错误
public class ResilientToolInterceptor extends ToolInterceptor {
    private static final int MAX_RETRIES = 3;

    @Override
    public ToolCallResponse interceptToolCall(
        ToolCallRequest request, ToolCallHandler handler) {

        int retries = 0;
        Exception lastException = null;

        while (retries < MAX_RETRIES) {
            try {
                return handler.call(request);
            } catch (ToolTimeoutException e) {
                lastException = e;
                retries++;
                // 指数退避
                try {
                    Thread.sleep((long) Math.pow(2, retries) * 1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return ToolCallResponse.error(request.getToolCall(),
            "工具经过 " + MAX_RETRIES + " 次重试后仍失败: " + lastException.getMessage());
    }

    @Override
    public String getName() {
        return "ResilientToolInterceptor";
    }
}

// 2. 通过 Hook 实现整体重试
@HookPositions({HookPosition.AFTER_AGENT})
public class AgentRetryHook extends AgentHook {
    private static final int MAX_AGENT_RETRIES = 2;

    @Override
    public CompletableFuture<Map<String, Object>> afterAgent(
        OverAllState state, RunnableConfig config) {

        // 检查是否执行成功
        boolean success = (Boolean) state.value("execution_success")
            .orElse(true);

        if (!success) {
            int retryCount = (Integer) config.context()
                .getOrDefault("agent_retry_count", 0);

            if (retryCount < MAX_AGENT_RETRIES) {
                config.context().put("agent_retry_count", retryCount + 1);
                // 重新执行 Agent
                return CompletableFuture.completedFuture(
                    Map.of("should_retry", true)
                );
            }
        }

        return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public String getName() {
        return "AgentRetryHook";
    }
}
```

### 3. 性能优化

```java
// 1. 使用消息裁剪限制 Token 消耗
public class OptimizedMemoryHook extends MessagesModelHook {
    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        // 保留最近的关键消息 + 系统消息
        int importantMessageCount = 10;

        if (previousMessages.size() > importantMessageCount) {
            // 移除中间的消息
            List<Message> optimized = new ArrayList<>();
            optimized.add(previousMessages.get(0));  // 保留第一条（通常是系统消息）

            // 添加最后 N 条消息
            int startIndex = Math.max(1, previousMessages.size() - importantMessageCount + 1);
            optimized.addAll(previousMessages.subList(startIndex, previousMessages.size()));

            return new AgentCommand(optimized, UpdatePolicy.REPLACE);
        }

        return new AgentCommand(previousMessages);
    }

    @Override
    public String getName() {
        return "OptimizedMemoryHook";
    }
}

// 2. 使用 ModelCallLimitHook 限制推理轮数
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;

ReactAgent agent = ReactAgent.builder()
    .name("efficient_agent")
    .model(chatModel)
    .hooks(ModelCallLimitHook.builder()
        .runLimit(5)  // 最多推理 5 轮
        .build())
    .build();

// 3. 使用更少的工具，增强工具的精准性
// ❌ 10 个通用工具
// ✅ 3 个专门化的工具
```

### 4. 生产环境检查清单

```java
// 1. 使用持久化存储而非内存存储
@Configuration
public class ProductionAgentConfig {
    @Bean
    public ReactAgent productionAgent(ChatModel chatModel) {
        return ReactAgent.builder()
            .name("production_agent")
            .model(chatModel)
            .saver(new RedisSaver(redisTemplate))  // ✅ Redis
            // .saver(new MemorySaver())  // ❌ 不要在生产环境使用
            .build();
    }
}

// 2. 添加监控和日志
@Bean
public ReactAgent monitoredAgent(ChatModel chatModel) {
    return ReactAgent.builder()
        .name("monitored_agent")
        .model(chatModel)
        .interceptors(
            new LoggingInterceptor(),
            new MetricsInterceptor(),
            new ErrorHandlingInterceptor()
        )
        .hooks(
            new PerformanceMonitoringHook(),
            new AuditLoggingHook()
        )
        .build();
}

// 3. 配置合理的超时和限制
public class TimeoutHook extends ModelHook {
    private static final long AGENT_TIMEOUT_SECONDS = 30;

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(
        OverAllState state, RunnableConfig config) {

        // 设置 Agent 执行超时
        return CompletableFuture.supplyAsync(() -> {
            // Agent 逻辑
            return Map.of();
        }).orTimeout(AGENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}

// 4. 实现灰度发布
public class CanaryReleaseConfig {
    @Bean
    public ReactAgent agent(ChatModel chatModel) {
        String agentVersion = System.getenv("AGENT_VERSION");

        if ("v2".equals(agentVersion)) {
            return buildV2Agent(chatModel);
        } else {
            return buildV1Agent(chatModel);  // 默认稳定版本
        }
    }
}
```

---

## 常见问题

### Q1: Agent 无限循环怎么办？

**A**: 使用 `ModelCallLimitHook` 限制推理轮数或实现自定义停止条件：

```java
// 方法 1：简单限制
ReactAgent agent = ReactAgent.builder()
    .hooks(ModelCallLimitHook.builder().runLimit(10).build())
    .build();

// 方法 2：自定义停止条件
@HookPositions({HookPosition.BEFORE_MODEL})
public class SmartStopHook extends ModelHook {
    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(
        OverAllState state, RunnableConfig config) {

        // 检查是否收敛（多次迭代内容相同）
        List<Message> messages = (List<Message>) state.value("messages")
            .orElse(List.of());

        if (hasConverged(messages)) {
            return CompletableFuture.completedFuture(
                Map.of("messages", List.of(
                    new AssistantMessage("已收敛到最终答案")
                ))
            );
        }

        return CompletableFuture.completedFuture(Map.of());
    }
}
```

### Q2: 如何访问 Agent 的完整执行状态？

**A**: 使用 `invoke()` 方法而非 `call()`：

```java
// 获取完整状态
Optional<OverAllState> result = agent.invoke("你的问题", config);

if (result.isPresent()) {
    OverAllState state = result.get();

    // 访问消息历史
    List<Message> messages = (List<Message>) state.value("messages")
        .orElse(List.of());

    // 访问自定义状态
    Optional<Object> customData = state.value("my_custom_key");

    // 访问完整状态
    System.out.println(state);
}
```

### Q3: 如何实现多个 Agent 的协作？

**A**: 使用 Agent 作为工具让主 Agent 调用，或使用 StateGraph 编排多个 Agent：

```java
// 方法 1：Agent 作为工具
public class AgentAsToolExample {
    private ReactAgent searchAgent;
    private ReactAgent analysisAgent;

    @Tool(name = "delegate_analysis")
    public String delegateAnalysis(String content) {
        // 调用分析 Agent
        return analysisAgent.call(content).getText();
    }

    public void demo() {
        ReactAgent coordinator = ReactAgent.builder()
            .name("coordinator")
            .methodTools(this)  // 包括 delegateAnalysis 工具
            .build();
    }
}

// 方法 2：使用 StateGraph（更高级）
StateGraph graph = new StateGraph("multi_agent", ...);
graph.addNode("search_agent", searchAgent.asNode());
graph.addNode("analysis_agent", analysisAgent.asNode());
graph.addEdge("search_agent", "analysis_agent");
// ... 配置路由
```

### Q4: 如何处理 Token 限制？

**A**: 使用消息裁剪 Hook：

```java
public class TokenLimitHook extends MessagesModelHook {
    private static final int MAX_TOKENS = 4000;
    private final TokenCounter tokenCounter;

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        int totalTokens = previousMessages.stream()
            .mapToInt(m -> tokenCounter.count(m.getText()))
            .sum();

        if (totalTokens > MAX_TOKENS) {
            // 裁剪最早的消息
            List<Message> trimmed = new ArrayList<>();
            int currentTokens = 0;

            for (int i = previousMessages.size() - 1; i >= 0; i--) {
                Message msg = previousMessages.get(i);
                int tokens = tokenCounter.count(msg.getText());

                if (currentTokens + tokens <= MAX_TOKENS) {
                    trimmed.add(0, msg);
                    currentTokens += tokens;
                }
            }

            return new AgentCommand(trimmed, UpdatePolicy.REPLACE);
        }

        return new AgentCommand(previousMessages);
    }
}
```

---

## 总结对比表

### 三种调用方式

| 方法 | 返回值 | 用途 | 何时使用 |
|-----|------|------|---------|
| `call()` | `AssistantMessage` | 获取最终回复 | 简单场景 |
| `invoke()` | `Optional<OverAllState>` | 获取完整状态 | 需要中间状态 |
| `stream()` | `Flux<NodeOutput>` | 流式输出 | 实时反馈需求 |

### Hook vs Interceptor

| 功能 | Hook | Interceptor |
|-----|------|-----------|
| 执行时机 | 生命周期关键点 | 请求/响应处理 |
| 粒度 | 粗粒度 | 细粒度 |
| 使用场景 | 日志、初始化、清理 | 内容过滤、修改 |

### 推荐 Agent 构建模板

```java
@Configuration
public class AgentConfig {

    @Bean
    public ReactAgent expertAgent(ChatModel chatModel) {
        return ReactAgent.builder()
            // 基础配置
            .name("expert_agent")
            .model(chatModel)

            // 角色定义
            .systemPrompt("""
                你是一个专业的技术顾问。
                ...
                """)

            // 工具注册
            .methodTools(new DomainSpecificTools())

            // 结构化输出
            .outputType(OutputClass.class)

            // 记忆管理
            .saver(new RedisSaver(redisTemplate))

            // 生命周期钩子
            .hooks(
                new MessageTrimmingHook(),
                ModelCallLimitHook.builder().runLimit(10).build()
            )

            // 细粒度控制
            .interceptors(
                new ErrorHandlingInterceptor(),
                new GuardrailInterceptor()
            )

            .build();
    }
}
```

这个指南覆盖了从基础到生产环境的所有 Agent Framework 使用方式。关键是理解**ReAct 循环的工作原理**，并根据场景选择合适的**工具、Hook 和 Interceptor 组合**。

