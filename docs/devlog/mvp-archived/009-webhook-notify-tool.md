# 开发日志 #9 — P3：真实 NOTIFY Tool（Webhook）

日期: 2026-03-10

## 概述

将 NOTIFY 步骤从日志模拟升级为真实的 Webhook 通知。利用 Spring AI 1.0 的 `@Tool` 注解 + `ChatClient.tools()` API，LLM 在执行 NOTIFY 步骤时根据前序步骤输出提炼通知内容，并通过 Webhook Tool 发送 HTTP POST。

## 进度

### Infrastructure 层

- **`WebhookNotifyTool`**（新建）：Package-private 类，单个 `@Tool` 方法 `sendNotification(title, summary)`。内部使用 `RestClient`（JDK HttpClient）发送 JSON payload `{title, summary, timestamp}` 到配置的 Webhook URL。三层降级：参数校验失败返回错误提示 → URL 未配置返回降级消息 → HTTP 调用失败返回友好文字。所有异常 catch 后返回兜底文字，不抛出。
- **`LlmNotifyExecutor`**（新建）：继承 `LlmStepExecutor`，override `callLlm()` 在 ChatClient 链中添加 `.tools(webhookNotifyTool)`。完全复刻 `LlmResearchExecutor` 模式，获得基类的重试（MAX_RETRIES=2）、输出验证、截断等能力。
- **`LogNotifyExecutor`**（删除）：旧的日志模拟实现，无可复用代码。
- **`StepExecutorRouter`**（修改）：构造函数新增 `notifyPrompt` Resource + 3 个 `@Value` 参数（`webhook.url`、`webhook.connect-timeout`、`webhook.read-timeout`）。字段类型从 `LogNotifyExecutor` 变为 `LlmNotifyExecutor`。
- **新增 6 个测试，修改 1 个**：
  - `WebhookNotifyToolTest`：`sendNotification_rejects_blank_title`、`sendNotification_rejects_blank_summary`、`sendNotification_returns_fallback_when_url_not_configured`、`sendNotification_returns_fallback_on_connection_error`、`constructor_accepts_blank_url`（×5）。
  - `StepExecutorRouterTest`：新增 `notify_step_registers_tools`（×1），改写 `routes_notify_step_without_llm` → `routes_notify_step_to_llm`。

### Web 层

- **`application.yml`**：新增 `echoflow.webhook.*` 配置块（url、connect-timeout、read-timeout），全部有默认值。
- **`step-notify.st`**（新建）：Prompt 模板，明确要求 LLM 必须调用 `sendNotification` Tool，引导提炼标题和摘要，plain text 格式。

## DDD 决策

1. **不新增 Port 接口** — Webhook Tool 是 Infrastructure 内部实现细节。Application 层通过已有的 `StepExecutorPort` 交互，对新工具完全无感知。
2. **Webhook URL 全局配置** — 不修改 Domain/Application 层。`echoflow.webhook.url` 为空时降级为日志记录，与已有的降级策略互补。
3. **删除而非修改** — `LogNotifyExecutor` 全部逻辑被替代，新建 `LlmNotifyExecutor` 保持命名一致性（`LlmThinkExecutor` / `LlmResearchExecutor` / `LlmWriteExecutor` / `LlmNotifyExecutor`）。

## 技术笔记

- **Webhook Payload 格式**：通用 JSON `{title, summary, timestamp}`。不做平台特化（Slack/Lark/Discord），用户通过中间件（n8n、Zapier）或自定义服务转换格式。
- **独立 `RestClient`**：Webhook 使用独立超时（connect 5s, read 10s），不复用 AI 或 GitHub 的超时配置。使用 `JdkClientHttpRequestFactory` 保持 Virtual Thread 兼容。
- **WEBHOOK_URL 环境变量**：可选配置，空值时 `WebhookNotifyTool.sendNotification()` 返回降级消息，不发送 HTTP 请求。
- **Prompt 强制调用**：`step-notify.st` 中使用 "You MUST use it" 和 "Always call the tool exactly once" 引导 LLM 执行真实通知。

## 测试统计

| 层 | 测试数 | 变化 |
|---|---|---|
| Domain | 39 | 不变 |
| Application | 11 | 不变 |
| Infrastructure | 20 | +6（WebhookNotifyToolTest ×5, StepExecutorRouterTest ×1） |
| **合计** | **70** | **+6** |

## 文件清单

| 操作 | 文件 |
|------|------|
| 新建 | `echoflow-infrastructure/.../ai/WebhookNotifyTool.java` |
| 新建 | `echoflow-infrastructure/.../ai/LlmNotifyExecutor.java` |
| 删除 | `echoflow-infrastructure/.../ai/LogNotifyExecutor.java` |
| 修改 | `echoflow-infrastructure/.../ai/StepExecutorRouter.java` |
| 新建 | `echoflow-web/.../resources/prompts/step-notify.st` |
| 修改 | `echoflow-web/.../resources/application.yml` |
| 新建 | `echoflow-infrastructure/src/test/.../WebhookNotifyToolTest.java` |
| 修改 | `echoflow-infrastructure/src/test/.../StepExecutorRouterTest.java` |
| 新建 | `docs/plans/2026-03-10-2000-p3-webhook-notify-tool.md` |
| 新建 | `docs/devlog/009-webhook-notify-tool.md` |

## E2E 验证

提交任务 "Summarize the top 3 benefits of using virtual threads in Java 21 and notify me with a brief summary"，全部 4 个步骤成功完成。

### 步骤执行结果

| 步骤 | 类型 | 状态 |
|------|------|------|
| Understand task | THINK | COMPLETED |
| Gather benefits | RESEARCH | COMPLETED |
| Write summary | WRITE | COMPLETED |
| Send notification | NOTIFY | COMPLETED |

### Webhook 接收到的 Payload

```json
{
  "title": "Task Completed: Top 3 Benefits of Java 21 Virtual Threads",
  "summary": "The analysis identified the three most compelling advantages of Java 21's virtual threads (JEP 444):\n1. Massive concurrency with minimal overhead – virtual threads have tiny (~1 KB) stacks and are managed by the JVM, allowing millions of concurrent tasks without exhausting OS thread resources.\n2. Simplified programming model for I/O‑bound work – developers can write straightforward blocking code while the JVM transparently parks and resumes threads, eliminating the need for complex reactive or callback‑based approaches.\n3. Improved resource utilization and throughput – the scheduler multiplexes many virtual threads onto a small pool of carrier threads, reducing context‑switch costs and delivering higher CPU and memory efficiency, especially for I/O‑heavy services.\n\nThese benefits together enable developers to build highly scalable, easier‑to‑maintain applications with lower memory footprints and better performance.",
  "timestamp": "2026-03-10T11:50:13.106679Z"
}
```

### 观察

- LLM 自动从前序步骤输出中提炼了精炼的标题和 3 点核心发现摘要，未照搬全文。
- `sendNotification` Tool 被调用恰好 1 次，符合 prompt 引导（"Always call the tool exactly once"）。
- Webhook 接收端成功收到 JSON payload，`Content-Type: application/json`，结构符合 `{title, summary, timestamp}` 设计。
- 任务完整执行：THINK → RESEARCH → WRITE → NOTIFY，全部 COMPLETED，taskStatus 为 COMPLETED。

## 下一步

- P3: Infrastructure 集成测试（Testcontainers）
- 可选：增加更多 Tool（代码搜索、Web 搜索等）
- 可选：限制单次步骤的最大 tool 调用次数
- 可选：Webhook 平台适配（Slack/Lark 特化 payload）
