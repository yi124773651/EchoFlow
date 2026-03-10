# 实施计划：P3 — GitHub 搜索 Tool（RESEARCH 步骤）

- 创建时间: 2026-03-10 18:25
- 完成时间: 2026-03-10 18:32
- 状态: ✅ 已完成
- 关联 devlog: [008-github-search-tool](../devlog/008-github-search-tool.md)

## 目标

让 RESEARCH 步骤的 LLM 能通过 Spring AI tool calling 调用 GitHub Repository Search API，获取真实的开源项目数据。

## 架构决策

1. 不新增 Port 接口 — 工具是 Infrastructure 内部实现细节
2. `@Tool` + per-request `.tools()` — 仅 RESEARCH executor 注册工具
3. 独立 `RestClient` — GitHub API 独立超时（connect 5s, read 10s）
4. GitHub Token 可选 — 无 Token 时 10 req/min
5. 工具内部兜底 — API 失败返回友好文字，不抛异常

## 变更清单

| 操作 | 文件 |
|------|------|
| 新建 | `echoflow-infrastructure/.../ai/GitHubSearchTool.java` |
| 修改 | `echoflow-infrastructure/.../ai/LlmResearchExecutor.java` |
| 修改 | `echoflow-infrastructure/.../ai/StepExecutorRouter.java` |
| 修改 | `echoflow-web/.../resources/application.yml` |
| 修改 | `echoflow-web/.../resources/prompts/step-research.st` |
| 修改 | `echoflow-infrastructure/src/test/.../StepExecutorRouterTest.java` |
| 新建 | `echoflow-infrastructure/src/test/.../GitHubSearchToolTest.java` |

## 测试结果

64 个测试全部通过（Domain 39 + Application 11 + Infrastructure 14）。
