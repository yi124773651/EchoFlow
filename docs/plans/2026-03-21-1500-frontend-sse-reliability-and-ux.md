# 前端 SSE 可靠性 + 步骤折叠 + 任务列表同步

- 创建时间: 2026-03-21 15:00
- 完成时间: 2026-03-21 15:45
- 状态: 已完成
- 关联 devlog: docs/devlog/026-frontend-sse-reliability-and-ux.md

## Context

用户反馈三个前端体验问题：
1. 提交任务后，右侧执行详情不显示刚提交的任务执行情况
2. 执行步骤全部展开，缺少折叠机制
3. SSE 传输存在问题，需要切换任务才能看到新内容和状态变更

**根因**：三个问题共享同一个核心缺陷——SSE 竞态条件。后端 `TaskController.create`（第 51 行）通过 `Thread.startVirtualThread()` 在 HTTP 响应返回前就启动执行，`ExecutionStarted` 事件在前端 SSE 连接建立前被发送并丢失。

## 修改文件清单

| 文件 | Phase | 变更 |
|------|-------|------|
| `echoflow-frontend/src/hooks/use-execution-stream.ts` | 1 | SSE-first 连接 + reconcile |
| `echoflow-backend/.../SseExecutionEventPublisher.java` | 1 | ExecutionStarted 事件缓冲 + replay |
| `echoflow-frontend/src/features/tasks/execution-timeline.tsx` | 2, 3 | StepCard 折叠 + onStatusChange |
| `echoflow-frontend/src/features/tasks/task-board.tsx` | 3 | 乐观更新 + 实时状态同步 |
| `echoflow-frontend/src/features/tasks/task-submit-form.tsx` | 3 | onCreated 签名改为传 TaskDto |

---

## Phase 1: SSE 可靠性修复（核心）

### 竞态时序（当前）

```
T1: POST /tasks -> 后端创建任务 + startVirtualThread 启动执行
T2: 后端 publish(ExecutionStarted) -> emitters.get(taskId) == null -> 事件丢失!
T3: 前端收到响应 -> setSelectedTaskId -> useExecutionStream 触发
T4: REST GET /tasks/{id} -> execution 可能为 null（刚创建）
T5: new EventSource() -> SSE 连接建立（为时已晚）
```

### 方案：前后端协同——SSE-first + 后端事件缓冲

#### 1a. 前端 `use-execution-stream.ts`：颠倒连接顺序

当前：REST detail -> SSE connect
改后：SSE connect -> REST detail -> reconcile merge

- 先建 SSE 连接确保不丢后续事件
- 再用 REST 获取快照，与 SSE 已收到的状态合并
- 新增 `reconcile(sseState, snapshot)` 函数：比较两者，取更"前进"的状态
- 提取 `attachSseHandlers(es, setState)` 函数封装所有 addEventListener

#### 1b. 后端 `SseExecutionEventPublisher.java`：最小事件缓冲

- 新增 `pendingStartEvents: Map<TaskId, ExecutionStarted>`
- `publish()` 时如果是 `ExecutionStarted` 且 emitter 不存在，暂存到 map
- `register()` 时检查 map，有暂存则立即 replay 发送
- 清理：emitter 的 onCompletion/onTimeout/onError 中清除对应条目

变更量：约 15 行 Java 代码。只缓冲 `ExecutionStarted` 这一种事件。

#### 修复后时序

```
正常: SSE 先建立 -> 后端 publish -> emitter 存在 -> 正常推送
竞态: 后端先 publish -> pendingStartEvents 暂存 -> SSE 建立时 replay
极端: replay 也错过 -> REST reconcile 兜底
```

---

## Phase 2: 步骤折叠

### 折叠规则

| 状态 | 默认 | 理由 |
|------|------|------|
| RUNNING | 展开 | 用户关注当前进度 |
| WAITING_APPROVAL | 展开 | 需要用户操作 |
| FAILED | 展开 | 需要关注的异常 |
| PENDING / COMPLETED / SKIPPED | 折叠 | 减少干扰 |

### StepCard 改造

- 使用 `@base-ui/react/collapsible`（已安装 v1.2.0）
- `manualOverride: boolean | null` 状态：null=跟随自动规则，true/false=用户手动控制
- 步骤状态变化时重置 manualOverride，让自动规则接管
- 折叠指示器用 `lucide-react` 的 ChevronRight/ChevronDown（已安装）
- 标题行始终可见（图标 + 步骤名 + 类型），可折叠内容包含 logs、output、审批按钮

---

## Phase 3: 任务列表状态同步

### 3a. 提交后乐观更新

- `onCreated` 签名从 `(taskId: string)` 改为 `(task: TaskDto)`
- `handleTaskCreated` 中立即将新任务插入列表顶部：`setTasks(prev => [task, ...prev])`
- 异步 `loadTasks()` 后台刷新真实数据

### 3b. 执行状态驱动列表刷新

- `ExecutionTimeline` 的 `onDone` 泛化为 `onStatusChange(taskId, status)`
- 执行状态变更时通知 TaskBoard，TaskBoard 乐观更新对应任务的 status 徽章
- 映射关系：`RUNNING -> EXECUTING`, `COMPLETED -> COMPLETED`, `FAILED -> FAILED`
- 终态时额外调一次 `loadTasks()` 确保数据准确

---

## 实施顺序

Phase 1 -> Phase 2 -> Phase 3（Phase 2 和 3 相互独立）

## 验证方式

1. **SSE 修复**：提交新任务后，不切换，观察执行详情是否实时显示步骤进度
2. **折叠**：多步骤执行时，已完成步骤自动折叠，当前步骤展开，可手动切换
3. **状态同步**：提交后左侧任务列表立即显示新任务，状态徽章随执行进度更新
4. **后端测试**：`./mvnw test -pl echoflow-backend -am` 全绿
