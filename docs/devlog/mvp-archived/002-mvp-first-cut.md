# 开发日志 #2 — MVP 第一刀 + 第二刀

日期: 2026-03-10

## 概述

完成 MVP 第一刀（静态管道）和第二刀（LLM 任务分解）。
- 第一刀：端到端的任务提交 → 步骤流转 → SSE 实时推送管道，步骤执行为硬编码模拟。
- 第二刀：引入 Spring AI + OpenAI 兼容接口，由 LLM 动态分解用户任务为执行步骤。

## 进度

### Domain 层
- **Task 聚合根**: `TaskId`(record), `TaskStatus`(enum), `Task`(聚合根), `IllegalTaskStateException`
  - 状态机: `SUBMITTED → EXECUTING → COMPLETED / FAILED`
  - 15 个单元测试覆盖创建、状态转换、非法转换
- **Execution 聚合根**: `ExecutionId`, `StepId`, `ExecutionStatus`, `StepType`, `StepStatus`, `LogType`(enums), `StepLog`(record), `ExecutionStep`(内部实体), `Execution`(聚合根), `IllegalExecutionStateException`
  - Execution 状态机: `PLANNING → RUNNING → COMPLETED / FAILED`
  - Step 状态机: `PENDING → RUNNING → COMPLETED / SKIPPED / FAILED`
  - 20 个单元测试覆盖步骤管理、日志追加、完成/失败
- **Repository 端口**: `TaskRepository`, `ExecutionRepository`（接口在 Domain，实现在 Infrastructure）

### Application 层
- **SubmitTaskUseCase**: 创建 Task + 持久化，返回 TaskResult
- **TaskQueryService**: 列表查询 + 详情查询（含 Execution 快照）
- **ExecuteTaskUseCase**: 编排执行流程
  - **第一刀**: 硬编码 3 个步骤：分析任务(THINK) → 调研资料(RESEARCH) → 撰写报告(WRITE)
  - **第二刀**: 通过 `TaskPlannerPort` 注入 LLM 规划，动态生成步骤
  - 每步通过 `Thread.sleep(1000)` 模拟执行
  - 通过 `ExecutionEventPublisher` 端口发布 SSE 事件
- **TaskPlannerPort** _(第二刀新增)_: Application 层端口接口
  - `planSteps(taskDescription)` → `List<PlannedStep>`
  - `PlannedStep(name, StepType)` — 内嵌 record，含 compact constructor 校验
- **TaskPlanningException** _(第二刀新增)_: 规划失败异常
- **ExecutionEvent**: sealed interface，7 种事件类型
- 6 个单元测试（mock Repository + mock EventPublisher + mock TaskPlannerPort）

### Infrastructure 层
- **Flyway 迁移** `V2__task_and_execution.sql`: task, execution, execution_step, step_log 四张表
- **JPA 实体**: `TaskEntity`, `ExecutionEntity`, `ExecutionStepEntity`, `StepLogEntity`
  - StepLog 使用双向 `@ManyToOne` 映射修复 null FK 问题
- **Repository 实现**: `JpaTaskRepository`, `JpaExecutionRepository`（含 Domain ↔ Persistence 映射）
- **AiTaskPlanner** _(第二刀新增)_: `TaskPlannerPort` 的 Infrastructure 实现
  - 使用 `ChatClient.prompt().user(...).call().entity()` 获取结构化 JSON 输出
  - LLM 输出校验：空检查、步骤数上限(10)、类型白名单(THINK/RESEARCH/WRITE/NOTIFY)
  - 重试机制：最多 2 次尝试，失败抛 `TaskPlanningException`
- **Prompt 模板** `prompts/task-planner.st`: 指导 LLM 将任务分解为 2-6 个有序步骤

### Web 层
- **TaskController**: 4 个端点
  - `POST /api/tasks` — 创建任务 + 触发异步执行（Virtual Thread）
  - `GET /api/tasks` — 任务列表
  - `GET /api/tasks/{taskId}` — 任务详情（含执行快照）
  - `GET /api/tasks/{taskId}/execution/stream` — SSE 实时流
- **SseExecutionEventPublisher**: 基于 `SseEmitter` 的事件路由，自动追踪 executionId→taskId 映射
- **ClockConfig**: 注入 `Clock.systemUTC()`，方便测试替换
- **GlobalExceptionHandler**: 已有，复用

### 前端
- **类型定义** `types/task.ts`: TaskDto, TaskDetailDto, 所有 SSE 事件类型
- **服务层** `services/task-service.ts`: create, list, detail, streamExecution
- **Hook** `hooks/use-execution-stream.ts`: SSE 连接管理 + 状态聚合
- **组件**:
  - `features/tasks/task-submit-form.tsx` — 任务提交表单
  - `features/tasks/execution-timeline.tsx` — 步骤卡片 + 日志实时展示
  - `features/tasks/task-board.tsx` — 任务看板（左列任务列表 + 右列执行详情）
- 首页 `app/page.tsx` 集成 TaskBoard

## DDD 决策

1. **Task 和 Execution 分离为两个聚合根** — Task 代表用户意图（可重试），Execution 代表一次执行过程。这样 Task 的生命周期不会被 Execution 的复杂性污染。
2. **ExecutionStep 作为 Execution 的内部实体** — 步骤不独立存在，总是在 Execution 上下文中操作。
3. **StepLog 为不可变值对象** — 满足 "append-only in spirit" 的 audit 要求。
4. **ExecutionEventPublisher 作为 Application 层端口** — Web 层的 SSE 实现可被替换（WebSocket、消息队列等）。
5. **Port/Adapter 隔离 LLM** — `TaskPlannerPort` 在 Application 层定义，`AiTaskPlanner` 在 Infrastructure 层实现。Application 层不知道底层用的是哪个 LLM Provider，可随时替换。
6. **LLM 输出为不可信输入** — 所有 LLM 返回数据经过白名单校验、边界检查后才进入 Domain。

## 技术笔记

- **Virtual Threads**: 任务执行通过 `Thread.startVirtualThread()` 异步触发，利用 Java 21 虚拟线程。
- **SseEmitter**: 0L timeout（无超时），事件名使用类名（`ExecutionStarted`, `StepLogAppended` 等）。
- **sealed interface**: `ExecutionEvent` 使用 sealed interface 封闭事件类型层次。
- **record**: `TaskId`, `ExecutionId`, `StepId`, `StepLog`, `SubmitTaskCommand`, `TaskResult` 等均为 record。
- **switch expression + pattern matching**: `ExecuteTaskUseCase.simulateStepExecution` 使用 switch 表达式按步骤类型分派。

### 第二刀新增技术笔记
- **Spring AI 1.0.0 GA**: 从 milestone `1.0.0-M7` 升级到 GA 稳定版，使用 `spring-ai-starter-model-openai`。
- **OpenAI 兼容模式**: 通过 `spring.ai.openai.base-url` 配置任意 OpenAI 兼容端点（DashScope、DeepSeek、OpenRouter 等），`api-key` 和 `model` 通过环境变量注入。
- **ChatClient 结构化输出**: `chatClient.prompt().user(...).call().entity(ParameterizedTypeReference<List<LlmStep>>)` 让 LLM 直接返回 JSON 数组并自动反序列化。
- **Spring AI Alibaba 与 OpenAI starter 关系**: Spring AI Alibaba 的增强功能（Graph、Agent 框架、MCP）是独立模块，不绑定 DashScope 模型提供者。MVP 阶段直接使用 Spring AI OpenAI starter，后续按需引入 Alibaba 增强模块。
- **版本选择理由**: Spring AI Alibaba 最新版 1.1.2.1 基于 Spring AI 1.1.2 + Spring Boot 3.5.8，与当前项目 Spring Boot 3.4.4 不兼容。选择 Spring AI 1.0.0 GA 保持兼容性。

## 测试统计

| 层 | 测试数 |
|---|---|
| Domain | 35 |
| Application | 6 |
| **合计** | **41** |

## 已知问题修复

1. **JPA StepLog null FK**: `@OneToMany @JoinColumn` 单向映射 + `orphanRemoval` 导致 Hibernate 先 INSERT null FK 再 UPDATE。修复：改为双向 `@ManyToOne` 映射。
2. **Neon DB 连接**: 初始 host 名错误（`ep-odd-frost-fooler` → `ep-odd-frost-adtnxz4n-pooler`）。
3. **Port 8080 冲突**: 旧进程未关闭，需 `taskkill` 后重启。

## 下一步

- 端到端验证第二刀（设置 `AI_BASE_URL` + `AI_API_KEY` + `AI_MODEL` 环境变量启动后端）
- 实现真实 Tool（第三刀：GitHub 搜索、报告生成、邮件通知）
- 添加 Infrastructure 层集成测试（Testcontainers）
- 前端任务列表自动刷新
- 评估后续是否升级 Spring Boot 3.5 + Spring AI Alibaba 增强模块
