# 前端 UI 重做 Phase 1：布局骨架 + SSE 执行体验优化

- 创建时间: 2026-03-24 11:30
- 完成时间: 进行中
- 状态: 进行中
- 关联 devlog: docs/devlog/028-frontend-ui-redesign-phase1.md

## Context

当前前端 UI 过于简陋，布局不合理，执行过程的 SSE 展示不够直观。Phase 1 重做布局骨架并优化 SSE 执行体验，视觉风格参考 Linear/Notion。

## 决策记录

| 决策项 | 选择 | 理由 |
|--------|------|------|
| 布局 | 三栏式（Sidebar + TaskList + Detail） | Linear 风格，信息密度合理 |
| 视觉风格 | Linear/Notion | 简洁现代，大量留白，细腻动画 |
| 执行体验 | 竖线时间线 + 流式日志 + 进度条 + 自动聚焦 | 解决"看不到过程"的核心痛点 |
| 阶段 | Phase 1 先做骨架和体验，Phase 2 做细节 | 分阶段交付，先解决最痛的点 |

## 布局设计

```
┌─────────┬──────────────────┬───────────────────────────┐
│ Sidebar  │  Task List       │  Execution Detail         │
│          │                  │                           │
│ EchoFlow │  筛选(占位)      │  任务标题 + 状态           │
│ ──────── │  ──────────      │  ──────────               │
│ 导航     │  TaskCard        │  Progress Bar (3/5)       │
│ · 任务   │  TaskCard ← 选中 │  ──────────               │
│ · (预留) │  TaskCard        │  Step 1 ✓ THINK     2s   │
│          │  TaskCard        │  Step 2 ● RESEARCH  ...   │
│ ──────── │                  │    ├ 💭 正在分析...        │
│ 提交任务 │                  │    ├ ⚡ 调用搜索工具       │
│          │                  │    └ 👁 返回 3 条结果      │
│          │                  │  Step 3 ○ WRITE           │
│          │                  │  Step 4 ○ NOTIFY          │
└─────────┴──────────────────┴───────────────────────────┘
```

### Sidebar（~60px 折叠 / ~200px 展开）
- 品牌 Logo/名称
- 导航项：任务（当前唯一）
- 底部：提交任务按钮（点击弹出模态框或侧面板）
- 可折叠

### Task List（~320px 固定宽度）
- 卡片式任务列表
- 每个卡片：描述摘要（2 行截断）+ 状态徽章 + 创建时间
- 选中态：左侧蓝色边框 + 浅背景
- 顶部：筛选区（Phase 1 仅占位，不实现逻辑）

### Execution Detail（剩余空间）
- 顶部：任务描述全文 + 状态
- 进度条：`已完成 2/5 步骤`
- 步骤时间线（竖线连接）
- 空状态：未选中任务时显示引导

## SSE 执行体验优化

### 1. 流式日志视觉反馈
- 当前步骤 RUNNING 时，最后一条日志后显示脉冲动画（三个点跳动）
- 日志追加时有淡入动画

### 2. 自动聚焦当前步骤
- `StepStarted` 事件到达时：
  - 自动展开当前步骤
  - 自动收起上一个已完成步骤
  - 平滑滚动到当前步骤

### 3. 整体进度条
- 位于执行详情顶部
- 显示 `已完成 N/M 步骤`
- 细长进度条，绿色填充，带平滑动画

### 4. 日志分层显示
- THOUGHT：灰色小字，斜体
- ACTION：蓝色，mono 字体
- OBSERVATION：正常前景色
- ERROR：红色背景高亮

### 5. 步骤时间线视觉
- 左侧竖线连接各步骤节点
- 已完成：实线 + 绿色圆点 + 对勾图标
- 进行中：实线 + 蓝色脉冲圆点
- 等待审批：实线 + 琥珀色圆点
- 待执行：虚线 + 灰色空心圆点
- 失败：实线 + 红色圆点 + X 图标
- 跳过：虚线 + 灰色圆点 + 横杠图标

## 修改文件清单

| Action | File | 说明 |
|--------|------|------|
| Create | `features/layout/sidebar.tsx` | 侧边栏组件 |
| Create | `features/layout/app-layout.tsx` | 三栏布局壳 |
| Create | `features/tasks/task-list.tsx` | 独立任务列表组件（从 task-board 拆出） |
| Create | `features/tasks/task-card.tsx` | 任务卡片组件 |
| Create | `features/tasks/execution-detail.tsx` | 执行详情面板（从 task-board 拆出） |
| Create | `features/tasks/step-timeline.tsx` | 步骤时间线组件（替代原 StepCard 列表） |
| Create | `features/tasks/step-node.tsx` | 单个步骤节点 |
| Create | `features/tasks/step-logs.tsx` | 步骤日志展示（分层样式） |
| Create | `features/tasks/progress-bar.tsx` | 执行进度条 |
| Rewrite | `app/page.tsx` | 改为使用 AppLayout |
| Delete | `features/tasks/task-board.tsx` | 拆分为 task-list + execution-detail |
| Rewrite | `features/tasks/execution-timeline.tsx` | 重构为使用新的 step-timeline |
| Rewrite | `features/tasks/task-submit-form.tsx` | 适配新布局（模态框或侧面板触发） |

## 不在 Phase 1 范围

- 暗色模式切换按钮
- 流程图/DAG 可视化
- 任务搜索/筛选逻辑
- 响应式移动端适配
- Sidebar 折叠/展开动画（先做固定展开态）
