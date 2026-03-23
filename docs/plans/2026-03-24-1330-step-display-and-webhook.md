# 步骤展示优化 + Webhook 通知配置

- 创建时间: 2026-03-24 13:30
- 完成时间: 进行中
- 状态: 进行中
- 关联 devlog: docs/devlog/030-step-display-and-webhook.md

## 目标

1. 步骤内容展示优化：THINK/RESEARCH 折叠原始日志仅显示 output 摘要，WRITE 显示 markdown + 复制按钮
2. 提交任务时可勾选 webhook 通知，Application 层根据 webhookUrl 追加/移除 NOTIFY 步骤

## 任务清单

### Task 1: Domain — Task 增加 webhookUrl (TDD)
- `Task.java` 增加 `webhookUrl` 字段
- `Task.submit()` 接受可选 webhookUrl
- `Task.reconstitute()` 映射

### Task 2: Application — SubmitTaskCommand + ExecuteTaskUseCase (TDD)
- `SubmitTaskCommand` 增加 `webhookUrl`（nullable）
- `SubmitTaskUseCase` 传递 webhookUrl
- `TaskResult` 增加 webhookUrl
- `ExecuteTaskUseCase.planExecution()`: AI 规划后根据 webhookUrl 追加/移除 NOTIFY 步骤

### Task 3: Infrastructure — TaskEntity + Migration
- `TaskEntity` 增加 `webhook_url` 字段
- `JpaTaskRepository` 映射
- Flyway migration: `ALTER TABLE task ADD COLUMN webhook_url VARCHAR(500)`

### Task 4: Web — CreateTaskRequest + TaskController
- `CreateTaskRequest` 增加 `webhookUrl`（optional）
- `TaskController.create()` 传递到 command
- `TaskResponse` 增加 webhookUrl

### Task 5: Frontend — 提交表单 webhook 配置
- `TaskSubmitDialog` 增加复选框 + URL 输入
- `task-service.ts` create 方法传递 webhookUrl
- `types/task.ts` TaskDto 增加 webhookUrl

### Task 6: Frontend — 步骤内容展示优化
- `step-node.tsx` 重构展示逻辑：
  - RUNNING: 实时日志流（保持不变）
  - COMPLETED 的 THINK/RESEARCH: 显示 output 纯文本摘要，原始日志折叠在"查看详情"
  - COMPLETED 的 WRITE: markdown 渲染 + 复制按钮
  - COMPLETED 的 NOTIFY: 简洁结果展示
- 复制按钮：点击复制 output 到剪贴板

### Task 7: 验证 + devlog
