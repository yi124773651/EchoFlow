# 026 — 前端 SSE 可靠性 + 步骤折叠 + 任务列表同步

## Progress

- SSE 竞态条件修复（前后端协同）：前端 SSE-first 连接 + REST reconcile，后端 ExecutionStarted 事件缓冲与 replay
- StepCard 折叠/展开：RUNNING/FAILED/WAITING_APPROVAL 自动展开，其余折叠，用户可手动切换
- 任务列表实时状态同步：提交后乐观插入 + SSE 驱动状态徽章更新
- ESLint 零问题，TypeScript 编译通过，131 后端测试全绿

## DDD Decisions

- 后端变更局限于 Web 层的 `SseExecutionEventPublisher`（SSE 基础设施），不涉及 Domain/Application 层
- 新增的 `pendingStartEvents` 是 SSE 连接管理的内部机制，不影响领域模型

## Technical Notes

### SSE 竞态根因

`TaskController.create` 中 `Thread.startVirtualThread(() -> executeTaskUseCase.execute(taskId))` 在 HTTP 响应返回前启动执行。后端可能在前端 SSE 连接建立前就发出 `ExecutionStarted` 事件，导致事件丢失。

### 三层防御

1. **后端事件缓冲**：`ExecutionStarted` 在 emitter 不存在时暂存到 `pendingStartEvents`，`register()` 时立即 replay
2. **前端 SSE-first**：颠倒连接顺序为 SSE connect -> REST detail，确保 emitter 尽早注册
3. **REST reconcile**：SSE 连接后用 REST 快照对账，`reconcile()` 函数按状态进度合并

### React 19 兼容

- 用 `prevTaskId` 状态跟踪 + 渲染期间重置替代 effect 内同步 setState（符合 `react-hooks/set-state-in-effect` 规则）
- 步骤折叠使用已有的 `@base-ui/react/collapsible` 组件

### 步骤折叠设计

- `manualOverride: boolean | null`：null 跟随自动规则，非 null 为用户手动控制
- 步骤状态变化时自动重置 manualOverride

## Next Steps

- 端到端验证（启动后端 + 前端，提交任务观察完整流程）
- 考虑 SSE 断连自动重连机制
