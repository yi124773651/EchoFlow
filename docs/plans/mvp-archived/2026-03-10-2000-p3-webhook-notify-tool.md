# 实施计划：P3 — Webhook NOTIFY Tool

- 创建时间: 2026-03-10 20:00
- 完成时间: 2026-03-10 19:40
- 状态: ✅ 已完成
- 关联 devlog: [009-webhook-notify-tool](../devlog/009-webhook-notify-tool.md)

## Context

当前 NOTIFY 步骤使用 `LogNotifyExecutor`，仅打日志返回固定字符串，不发送真实通知。MVP 第三刀要求 Agent 能真正发送通知。本次将 NOTIFY 步骤升级为 LLM + Webhook Tool calling 模式：LLM 根据前序步骤输出提炼通知内容，通过 Webhook Tool 发送 HTTP POST 到用户配置的 URL。

## 架构决策

1. **删除 `LogNotifyExecutor`，新建 `LlmNotifyExecutor extends LlmStepExecutor`** — 旧类无可复用代码，新类需要 LLM 调用 + Tool calling，复刻 `LlmResearchExecutor` 模式。命名与其他 Executor 保持一致。
2. **新建 `WebhookNotifyTool`** — 复刻 `GitHubSearchTool` 模式：package-private、`@Tool` 注解、独立 `RestClient`、异常内部 catch 返回友好文字。
3. **Webhook URL 全局配置** — `echoflow.webhook.url`，默认空。空时降级为日志记录。不修改 Domain/Application 层。
4. **不新增 Port 接口** — 工具是 Infrastructure 内部实现细节。

## 变更清单

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

## 测试结果

70 个测试全部通过（Domain 39 + Application 11 + Infrastructure 20）。
