# EchoFlow MVP 计划

日期: 2026-03-10

## 核心场景

用户不再是和 AI 聊天，而是下达复杂的异步任务。

> "帮我调研一下 GitHub 上最近热门的 Java Agent 项目，写一份对比分析报告存为 Markdown，如果发现有超过 5000 Star 的，发邮件提醒我。"

用户在前端看到一个任务看板，Agent 的思考、调研、写作、邮件发送过程像流水线一样流转，用户可以随时干预或回溯。

## 三大核心能力

| 能力 | 描述 | 复杂度 |
|------|------|--------|
| **任务提交与分解** | 用户用自然语言下达任务，Agent 规划出执行步骤 | 高（依赖 LLM） |
| **步骤流式执行** | Agent 按步骤执行，每步的思考/行动/结果实时推送 | 中 |
| **任务看板与回溯** | 前端展示任务状态流转，可查看每步详情 | 中 |

**MVP 原则：先让管道跑通，再让 Agent 变聪明。**

---

## 领域模型

```
Task (Aggregate Root)
├── taskId: TaskId
├── description: String          ← 用户原始输入
├── status: TaskStatus           ← SUBMITTED → EXECUTING → COMPLETED / FAILED
├── createdAt: Instant
└── completedAt: Instant?

Execution (Aggregate Root)
├── executionId: ExecutionId
├── taskId: TaskId               ← 关联
├── status: ExecutionStatus      ← PLANNING → RUNNING → COMPLETED / FAILED
├── steps: List<ExecutionStep>   ← 有序步骤列表
├── startedAt: Instant
└── completedAt: Instant?

ExecutionStep (Entity, Execution 内部)
├── stepId: StepId
├── name: String                 ← "调研 GitHub 项目" / "撰写报告" / ...
├── type: StepType               ← RESEARCH / WRITE / NOTIFY / THINK
├── status: StepStatus           ← PENDING → RUNNING → COMPLETED / SKIPPED / FAILED
├── output: String?              ← 步骤产出（Markdown 报告等）
└── logs: List<StepLog>          ← 思考/行动/观察 的追加日志

StepLog (Value Object, 追加写入)
├── timestamp: Instant
├── type: LogType                ← THOUGHT / ACTION / OBSERVATION / ERROR
└── content: String
```

### DDD 边界决策

- `Task` 和 `Execution` 是两个独立聚合根 — Task 代表用户意图，Execution 代表一次执行过程。同一个 Task 未来可以重试产生新的 Execution。
- `ExecutionStep` 是 Execution 内部实体，不独立存在。
- `StepLog` 是不可变值对象，追加写入，满足 agent.md 中 "append-only in spirit" 的要求。

---

## MVP 三刀

### 第一刀：静态管道（不接 LLM）

**目标：** 后端能创建任务、硬编码步骤、通过 SSE 推送步骤流转；前端能提交任务、看到看板实时更新。

**后端：**
- Domain：`Task`、`Execution`、`ExecutionStep`、`StepLog` 及相关值对象/枚举
- Application：`SubmitTaskUseCase`、`ExecuteTaskUseCase`（步骤硬编码，用 `Thread.sleep` 模拟执行）
- Infrastructure：JPA 持久化、Flyway 建表
- Web：`POST /api/tasks`、`GET /api/tasks`、`GET /api/tasks/{id}`、`GET /api/tasks/{id}/execution/stream`（SSE）

**前端：**
- 任务提交表单
- 任务列表（看板雏形）
- 任务详情页 + SSE 实时步骤流转展示

**价值：** 全链路跑通，DDD 分层验证，SSE 管道验证，前后端联调完成。

### 第二刀：接入 LLM 做任务分解

**目标：** 用户输入自然语言 → LLM 规划出步骤列表 → 按规划执行。

- Infrastructure 接入 Spring AI Alibaba
- 新增 `TaskPlannerPort`（Application 层接口），Infrastructure 实现调用 LLM
- Prompt 模板放 `prompts/task-planner.st`
- 步骤执行仍然是模拟（或接简单 Tool，比如调 GitHub API 搜索）
- 验证 LLM 输出 → 必须校验步骤结构合法性

**价值：** Agent 从"假的"变成"能思考的"，但 Tool 能力还受限。

### 第三刀：Tool 执行 + 产出物

**目标：** Agent 的每个步骤真正调用 Tool（GitHub 搜索、Markdown 生成、邮件发送），产出物持久化。

- Infrastructure 实现具体 Tool（`GitHubSearchTool`、`MarkdownWriterTool`、`EmailNotifyTool`）
- Tool 遵循 agent.md 规范：类型化输入/输出、副作用文档化、allow-list
- 产出物（报告 Markdown）存储并可在前端查看
- 前端增加产出物预览

---

## API 契约（第一刀）

```
POST   /api/tasks                          → TaskDto          创建任务
GET    /api/tasks                           → List<TaskDto>    任务列表
GET    /api/tasks/{taskId}                  → TaskDetailDto    任务详情（含步骤快照）
GET    /api/tasks/{taskId}/execution/stream → SSE              实时执行流
```

### SSE 事件结构

遵循 agent.md 规范：

```json
{
  "executionId": "uuid",
  "type": "STEP_STARTED | STEP_LOG | STEP_COMPLETED | EXECUTION_COMPLETED | EXECUTION_FAILED",
  "timestamp": "2026-03-10T...",
  "payload": { ... }
}
```

---

## 数据库表（第一刀 Flyway 迁移）

```sql
-- V2__task_and_execution.sql

CREATE TABLE task (
    id           UUID PRIMARY KEY,
    description  TEXT         NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE TABLE execution (
    id           UUID PRIMARY KEY,
    task_id      UUID         NOT NULL REFERENCES task(id),
    status       VARCHAR(20)  NOT NULL,
    started_at   TIMESTAMPTZ  NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE TABLE execution_step (
    id           UUID PRIMARY KEY,
    execution_id UUID         NOT NULL REFERENCES execution(id),
    step_order   INT          NOT NULL,
    name         VARCHAR(100) NOT NULL,
    type         VARCHAR(20)  NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    output       TEXT,
    created_at   TIMESTAMPTZ  NOT NULL
);

CREATE TABLE step_log (
    id        UUID PRIMARY KEY,
    step_id   UUID         NOT NULL REFERENCES execution_step(id),
    type      VARCHAR(20)  NOT NULL,
    content   TEXT         NOT NULL,
    logged_at TIMESTAMPTZ  NOT NULL
);
```

---

## 实现顺序（第一刀，TDD）

1. Domain 层：Task 聚合根 + 状态机 + 单元测试
2. Domain 层：Execution 聚合根 + ExecutionStep + StepLog + 单元测试
3. Domain 层：Repository 端口接口
4. Application 层：SubmitTaskUseCase + 单元测试
5. Application 层：ExecuteTaskUseCase（模拟执行）+ 单元测试
6. Infrastructure 层：Flyway 迁移 + JPA 持久化实现
7. Web 层：Task REST API + SSE 端点
8. 前端：任务提交 + 看板 + 实时流
