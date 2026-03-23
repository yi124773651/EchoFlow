# 028 — 前端 UI 重做 Phase 1: 布局骨架 + SSE 执行体验

## Progress

- 三栏布局重做：Sidebar + TaskList + ExecutionDetail（Linear/Notion 风格）
- 任务列表改为卡片式，状态圆点 + 相对时间 + 左边框选中态
- 执行详情面板：进度条 + 竖线时间线 + 状态节点（脉冲/对勾/叉号）
- SSE 体验优化：流式日志脉冲动画、自动滚动到当前步骤、日志分层样式
- 提交表单改为模态对话框，从 Sidebar 底部按钮触发
- 删除旧组件 task-board.tsx 和 execution-timeline.tsx
- Build + ESLint 全部通过

## DDD Decisions

- 本次改动仅涉及前端展示层，不影响 Domain/Application/Infrastructure
- 服务层（api.ts, task-service.ts）和 SSE hook（use-execution-stream.ts）完全不变
- 类型定义（types/task.ts）不变

## Technical Notes

### 组件拆分

旧的 `task-board.tsx`（135 行）承担了状态管理 + 任务列表 + 执行详情的所有职责。拆分为：
- `app/page.tsx` — 状态管理和组件编排
- `features/layout/` — 布局壳（sidebar, app-layout）
- `features/tasks/task-list.tsx` + `task-card.tsx` — 任务列表
- `features/tasks/execution-detail.tsx` — 执行详情顶层
- `features/tasks/step-node.tsx` — 单步骤时间线节点
- `features/tasks/step-logs.tsx` — 日志分层展示
- `features/tasks/progress-bar.tsx` — 进度条

### SSE 体验改进

1. **流式日志反馈**: RUNNING 状态时显示三点脉冲动画
2. **自动聚焦**: StepStarted 触发 scrollIntoView + 自动展开
3. **进度条**: 顶部 `completed/total` + 细长绿色条
4. **日志分层**: THOUGHT 灰斜体, ACTION 蓝色 mono, OBSERVATION 正常, ERROR 红色高亮
5. **时间线视觉**: 竖线连接，实线/虚线区分完成/待执行，彩色状态圆点

### 提交表单

从内联表单改为模态对话框，由 Sidebar 底部"提交任务"按钮触发。对话框打开时自动 focus textarea。

## Next Steps

- Phase 2: 暗色模式切换、任务搜索筛选、响应式移动端、Sidebar 折叠动画
- 验证 SSE 端到端流式体验（需要后端运行）
