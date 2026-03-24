# 033 - Webhook URL Per-Task Passthrough

## Progress
- StepExecutionContext 新增 nullable webhookUrl 字段 + 向后兼容 4 参数构造器
- WebhookNotifyTool 新增 withUrl() 工厂方法（共享 RestClient、blank 归一化）
- ReactAgentStepExecutor 新增 protected tools() accessor
- ReactAgentNotifyExecutor override configureAgent()，按 context.webhookUrl() 动态替换 tool
- GraphOrchestrationPort.executeSteps() 签名新增 webhookUrl 参数
- ExecuteTaskUseCase + ExecutionRecoveryService 传 task.webhookUrl()
- GraphOrchestrator 将 webhookUrl 放入 OverAllState（REPLACE 策略）
- StepNodeAction / ReviewableWriteNodeAction / WriteReviseAction 全部从 state 读取 webhookUrl 传入 context

## DDD Decisions
- webhookUrl 走可信链路：Task (Domain) → UseCase (Application) → Port → Orchestrator/Executor (Infrastructure)
- URL 不暴露给 LLM，不作为 @ToolParam
- StepExecutionContext 在 Application 层（context carrier），不是 Domain 概念

## Technical Notes
- withUrl() 共享 RestClient 实例，避免每次创建连接池
- blank URL 归一化为 null，与 Task.submit() 行为一致
- ReactAgentNotifyExecutor 用 ThreadLocal 保存当前执行上下文
  configureAgent() 在 buildAgent() 内被调用，同一线程
  ThreadLocal 确保并发执行共享同一 executor 时不会竞态
- .env 的 WEBHOOK_URL 保留为全局兜底，可删可不删

## Next Steps
- 考虑在 Task.submit() 或 withUrl() 中添加 URL scheme 校验（仅允许 https://）
- 清理 .env 中的 WEBHOOK_URL（可选）
