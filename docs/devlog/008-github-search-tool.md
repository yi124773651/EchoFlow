# 开发日志 #8 — P3：真实 RESEARCH Tool（GitHub 搜索 API）

日期: 2026-03-10

## 概述

为 RESEARCH 步骤接入 GitHub Repository Search API 作为真实工具。利用 Spring AI 1.0 的 `@Tool` 注解 + `ChatClient.tools()` API，LLM 在执行 RESEARCH 步骤时可自主决定是否调用 GitHub 搜索获取真实的开源项目数据。

## 进度

### Infrastructure 层

- **`GitHubSearchTool`**（新建）：Package-private 类，单个 `@Tool` 方法 `searchRepositories(query)`。内部使用 `RestClient`（JDK HttpClient）调用 `GET /search/repositories`，解析 JSON 响应并格式化为 LLM 可读文本（repo 名、描述、stars、语言、URL）。所有异常 catch 后返回兜底文字，不抛出。
- **`LlmResearchExecutor`**（修改）：新增 `GitHubSearchTool` 构造参数，override `callLlm()` 在 ChatClient 链中添加 `.tools(gitHubSearchTool)`。
- **`StepExecutorRouter`**（修改）：构造函数新增 5 个 `@Value` 参数（`github.api-base-url`、`github.token`、`github.connect-timeout`、`github.read-timeout`、`github.max-results`），构造 `GitHubSearchTool` 实例传入 `LlmResearchExecutor`。
- **新增 5 个测试**：
  - `GitHubSearchToolTest`：`searchRepositories_rejects_blank_query`、`searchRepositories_rejects_null_query`、`searchRepositories_returns_fallback_on_connection_error`、`constructor_accepts_blank_token`。
  - `StepExecutorRouterTest`：`research_step_registers_tools`（验证 RESEARCH 步骤注册了工具）。

### Web 层

- **`application.yml`**：新增 `echoflow.github.*` 配置块（api-base-url、token、connect-timeout、read-timeout、max-results），全部有默认值。
- **`step-research.st`**：更新 prompt，新增工具使用引导（何时搜索、如何搜索、可多次调用、可结合自身知识）。

## DDD 决策

1. **不新增 Port 接口** — GitHub 搜索工具是 Infrastructure 内部实现细节。Application 层通过已有的 `StepExecutorPort` 交互，对新工具完全无感知。这遵循了"工具是 Infrastructure 的实现细节"原则。
2. **工具内部兜底而非抛异常** — `GitHubSearchTool.searchRepositories()` 在 API 失败时返回友好文字而非抛出异常。这让 LLM 可以继续用自身知识生成结果，与已有的降级策略（devlog #6）互补：工具级别的 graceful fallback + 步骤级别的 skip 降级。
3. **Per-request tool 注册** — 仅 RESEARCH executor 在 `.tools()` 中注册工具，THINK 和 WRITE 步骤不受影响。这保持了各 executor 的职责单一性。

## 技术笔记

- **Spring AI 1.0 `@Tool` API**：类上的方法用 `@Tool(description=...)` 注解，参数用 `@ToolParam(description=...)` 注解。在 `ChatClient` 链中用 `.tools(toolInstance)` 注册，Spring AI 自动处理 function calling 协议。
- **独立 `RestClient`**：GitHub API 使用独立超时（connect 5s, read 10s），不复用 AI 的 60s 读超时。使用 `JdkClientHttpRequestFactory` 保持 Virtual Thread 兼容。
- **GitHub Token 可选**：通过 `${GITHUB_TOKEN:}` 配置，空值时 `GitHubSearchTool` 不发送 `Authorization` 头，走 GitHub 匿名访问（10 req/min）。
- **结果格式化**：搜索结果按 stars 降序，每个 repo 显示全名、描述（截断 200 字符）、star 数、语言和 URL，便于 LLM 理解和引用。

## 测试统计

| 层 | 测试数 | 变化 |
|---|---|---|
| Domain | 39 | 不变 |
| Application | 11 | 不变 |
| Infrastructure | 14 | +5（GitHubSearchToolTest ×4, StepExecutorRouterTest ×1） |
| **合计** | **64** | **+5** |

## 文件清单

| 操作 | 文件 |
|------|------|
| 新建 | `echoflow-infrastructure/.../ai/GitHubSearchTool.java` |
| 修改 | `echoflow-infrastructure/.../ai/LlmResearchExecutor.java` |
| 修改 | `echoflow-infrastructure/.../ai/StepExecutorRouter.java` |
| 修改 | `echoflow-web/.../resources/application.yml` |
| 修改 | `echoflow-web/.../resources/prompts/step-research.st` |
| 修改 | `echoflow-infrastructure/src/test/.../StepExecutorRouterTest.java` |
| 新建 | `echoflow-infrastructure/src/test/.../GitHubSearchToolTest.java` |
| 新建 | `docs/plans/2026-03-10-1825-p3-github-search-tool.md` |
| 新建 | `docs/devlog/008-github-search-tool.md` |

## E2E 验证

提交任务 "Find popular Kotlin coroutine libraries on GitHub"，RESEARCH 步骤成功调用 GitHub Search Tool。

### 日志证据

```
GitHubSearchTool : GitHub search: query='coroutine library language:Kotlin stars:>500'
GitHubSearchTool : GitHub search: query='kotlin coroutine library stars:>500'
GitHubSearchTool : GitHub search: query='kotlin flow library stars:>500 language:Kotlin'
GitHubSearchTool : GitHub search: query='kotlin coroutine extensions stars:>500 language:Kotlin'
GitHubSearchTool : GitHub search: query='ktor language:Kotlin stars:>1000'
GitHubSearchTool : GitHub search: query='"coroutine" language:Kotlin stars:>500'
GitHubSearchTool : GitHub search: query='Kotlin/kotlinx.coroutines'
GitHubSearchTool : GitHub search: query='repo:Kotlin/kotlinx.coroutines'
GitHubSearchTool : GitHub search: query='repo:Kotlin/kotlinx.coroutines forks:>0'
GitHubSearchTool : GitHub search: query='repo:rickclephas/KMP-NativeCoroutines'
```

### 观察

- LLM 自主发起 **10 次** GitHub 搜索，使用了多种查询策略（按 stars 筛选、按语言筛选、按具体仓库搜索）。
- 全部在 `virtual-63` 虚拟线程上执行，间隔约 2-3 秒。
- 任务完整执行：THINK → RESEARCH → THINK → WRITE → NOTIFY，全部 COMPLETED。
- RESEARCH 步骤的输出包含真实的 GitHub 仓库数据（star 数、描述、URL）。

### 注意事项

- `mvnw spring-boot:run` 的 forked JVM 输出存在缓冲问题，运行时日志不会实时刷新到管道。需使用 `java -jar` 直接运行 fat jar 才能看到完整日志。
- 当前未设置 `GITHUB_TOKEN`，走匿名访问（10 req/min）。高频使用时需配置 Token。

## 下一步

- P3: 真实 NOTIFY Tool（邮件/Webhook）
- P3: Infrastructure 集成测试（Testcontainers）
- 可选：增加更多工具（代码搜索 `/search/code`、Web 搜索等）
- 可选：限制单次 RESEARCH 步骤的最大 tool 调用次数，避免 token 消耗过高
