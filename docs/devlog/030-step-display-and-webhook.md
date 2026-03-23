# 030 — 步骤展示优化 + Webhook 通知配置

## Progress

- 后端全链路增加 webhookUrl：Domain → Application → Infrastructure → Web
- ExecuteTaskUseCase 规划后自动追加/移除 NOTIFY 步骤：有 webhookUrl 追加，无则移除
- Flyway V5 migration: task 表新增 webhook_url 列
- 前端提交对话框增加"完成后通知 (Webhook)"复选框 + URL 输入
- 步骤展示优化：THINK/RESEARCH 完成后显示 output 摘要，原始日志折叠在"查看详情"；WRITE 显示 markdown + 复制按钮
- SSE 事件缓冲修复：所有事件（不仅 ExecutionStarted）在 emitter 注册前都缓冲并回放
- TDD：Domain 4 新测试，Application 3 新测试，SsePublisher 4 新测试，全部通过

## DDD Decisions

- webhookUrl 是 Task 聚合的属性（用户意图的一部分），不是 Execution 的属性
- NOTIFY 步骤的追加/移除由 Application 层（ExecuteTaskUseCase）决定，不依赖 AI 规划结果
- 保持 Domain 纯净：Task.webhookUrl() 只是数据，追加/移除逻辑在 Application 层

## Technical Notes

### NOTIFY 步骤自动调整
`ExecuteTaskUseCase.adjustNotifyStep()` 在 AI 规划完成后作为后处理步骤运行：
- webhookUrl 存在 + AI 没规划 NOTIFY → 追加 "Webhook 通知" 步骤
- webhookUrl 不存在 + AI 规划了 NOTIFY → 移除
- 两者都有 → 保持不变（避免重复）

### SSE 事件缓冲修复
`SseExecutionEventPublisher` 原来只缓冲 `ExecutionStarted`，后续事件（StepStarted、StepLogAppended 等）在 emitter 注册前会被丢弃。改为缓冲所有事件的有序列表，`register()` 时全量回放。

### 前端步骤展示分层
- RUNNING: 实时日志流（StepLogs + 脉冲动画）
- COMPLETED THINK/RESEARCH: output 纯文本摘要 + `<details>` 折叠原始日志
- COMPLETED WRITE: markdown 渲染 + 右上角复制按钮（`navigator.clipboard.writeText`）
- COMPLETED NOTIFY: 纯文本结果

## Next Steps

- 端到端验证 webhook 通知功能（需要后端 WebhookNotifyTool 配合）
- 考虑 webhook URL 格式校验（前端 + 后端）
