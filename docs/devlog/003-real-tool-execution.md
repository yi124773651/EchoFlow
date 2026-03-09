# 开发日志 #3 — MVP 第三刀：真实 LLM 步骤执行

日期: 2026-03-10

## 概述

将 Cut 2 中 `ExecuteTaskUseCase.simulateStepExecution()` 的 `Thread.sleep(1000)` 模拟替换为真实 LLM 调用。每个步骤类型（THINK/RESEARCH/WRITE/NOTIFY）现在由对应的执行器处理，THINK/RESEARCH/WRITE 调用 LLM 生成真实内容，NOTIFY 仅做日志记录。前端为 WRITE 步骤输出渲染 Markdown。

## 进度

### Application 层

- **StepExecutionContext** (record): 包含 `taskDescription`, `stepName`, `stepType`, `previousOutputs`。compact constructor 校验非空，`previousOutputs` 做 defensive copy。
- **StepOutput** (record): 包含 `output`，compact constructor 校验非空。
- **StepExecutorPort** (接口): `execute(StepExecutionContext) → StepOutput`，与 `TaskPlannerPort` 相同的端口模式。
- **StepExecutionException**: 继承 `DomainException`，表示步骤执行失败。
- **ExecuteTaskUseCase 重构**:
  - 构造函数新增 `StepExecutorPort stepExecutor` 参数
  - 删除 `simulateStepExecution()` 和 `simulateWork()` 方法
  - `runExecution()` 重写：加载 task description → 循环中累积 `previousOutputs` → 每步构建 `StepExecutionContext` → appendLog(ACTION) → `stepExecutor.execute(context)` → appendLog(OBSERVATION) → completeStep
  - 异常时 appendLog(ERROR) → failStep → failExecution
- **测试新增 3 个**: `execute_delegates_each_step_to_step_executor`, `execute_passes_previous_outputs_to_subsequent_steps`, `execute_fails_when_step_executor_throws`

### Infrastructure 层

- **LlmStepExecutor** (abstract, package-private): 封装 ChatClient 调用、重试（MAX_RETRIES=2）、输出校验（非空）、截断（10000 chars）
- **LlmThinkExecutor**: THINK 步骤，不传 previousContext 避免偏见
- **LlmResearchExecutor**: RESEARCH 步骤，传递 previousContext 用于累积调研
- **LlmWriteExecutor**: WRITE 步骤，传递 previousContext 综合撰写 Markdown 报告
- **LogNotifyExecutor**: NOTIFY 步骤，不调 LLM，仅日志记录
- **StepExecutorRouter** (@Component): 实现 `StepExecutorPort`，用 switch expression 按 StepType 路由到对应执行器
- **Prompt 模板**: `step-think.st`, `step-research.st`, `step-write.st`
- **9 个单元测试**: 路由、NOTIFY 不调 LLM、空输出拦截、null 输出拦截、重试成功、最大重试失败、长输出截断

### 前端

- **react-markdown + remark-gfm**: WRITE 步骤 COMPLETED 后的 output 使用 `<ReactMarkdown>` 渲染
- **@tailwindcss/typography**: `prose` 类样式支持，通过 `@plugin` 指令加载（Tailwind v4）
- 其他步骤类型保持 `<pre>` 渲染

## DDD 决策

1. **StepExecutorPort 在 Application 层定义** — 与 TaskPlannerPort 相同的端口/适配器模式。Application 层不知道底层执行器实现。
2. **StepExecutionContext 累积 previousOutputs** — 步骤间通过 previousOutputs 传递上下文，实现步骤链式推理。Application 层负责累积，不泄漏到 Domain。
3. **LlmStepExecutor 继承体系 package-private** — 只有 `StepExecutorRouter` 对外可见（@Component），内部执行器封装在包内，遵循最小暴露原则。
4. **NOTIFY 不调 LLM** — 当前阶段仅日志记录，后续可替换为真实通知（邮件、Webhook 等），不影响 Application 层。

## 技术笔记

- **重试机制**: `LlmStepExecutor` 最多 2 次尝试，每次包含 LLM 调用 + 输出校验。失败抛 `StepExecutionException`。
- **输出截断**: 超过 10000 字符的 LLM 输出自动截断，防止数据库/前端过载。
- **LLM 输出校验**: 空输出（null 或 blank）在校验阶段被拦截，触发重试。
- **previousContext 构建**: `LlmStepExecutor.buildPreviousContext()` 将之前步骤的输出拼接为 `--- Step N output ---` 格式。
- **THINK 步骤不传 previousContext**: `LlmThinkExecutor` 重写 `callLlm()` 仅传 taskDescription 和 stepName，避免先入为主。
- **Tailwind v4 typography**: 使用 `@plugin "@tailwindcss/typography"` 而非 v3 的 `plugins: [require('@tailwindcss/typography')]`。
- **Mockito argThat null guard**: `argThat` 匹配器在不匹配时传 `null`，lambda 中需加 `ctx != null &&` 守卫。
- **Mockito lenient stubbing**: `setUp()` 中 `chatClient.prompt()` 需 `lenient()` 标记，因为 NOTIFY 测试不触发该 stub。

## 测试统计

| 层 | 测试数 |
|---|---|
| Domain | 35 |
| Application | 9 |
| Infrastructure | 9 |
| **合计** | **53** |

## 文件清单

| 操作 | 文件 |
|------|------|
| 新建 | `echoflow-application/.../execution/StepExecutionContext.java` |
| 新建 | `echoflow-application/.../execution/StepOutput.java` |
| 新建 | `echoflow-application/.../execution/StepExecutorPort.java` |
| 新建 | `echoflow-application/.../execution/StepExecutionException.java` |
| 修改 | `echoflow-application/.../execution/ExecuteTaskUseCase.java` |
| 修改 | `echoflow-application/src/test/.../ExecuteTaskUseCaseTest.java` |
| 新建 | `echoflow-infrastructure/.../ai/LlmStepExecutor.java` |
| 新建 | `echoflow-infrastructure/.../ai/LlmThinkExecutor.java` |
| 新建 | `echoflow-infrastructure/.../ai/LlmResearchExecutor.java` |
| 新建 | `echoflow-infrastructure/.../ai/LlmWriteExecutor.java` |
| 新建 | `echoflow-infrastructure/.../ai/LogNotifyExecutor.java` |
| 新建 | `echoflow-infrastructure/.../ai/StepExecutorRouter.java` |
| 新建 | `echoflow-infrastructure/src/test/.../StepExecutorRouterTest.java` |
| 新建 | `echoflow-web/.../resources/prompts/step-think.st` |
| 新建 | `echoflow-web/.../resources/prompts/step-research.st` |
| 新建 | `echoflow-web/.../resources/prompts/step-write.st` |
| 修改 | `echoflow-frontend/src/features/tasks/execution-timeline.tsx` |
| 修改 | `echoflow-frontend/src/app/globals.css` |
| 新建 | `docs/devlog/003-real-tool-execution.md` |

## 下一步

- 端到端验证（设置 `.env` 环境变量启动后端，提交任务观察 LLM 真实执行）
- 实现真实 RESEARCH Tool（GitHub 搜索 API 接入）
- 实现真实 NOTIFY Tool（邮件/Webhook）
- 添加 Infrastructure 层集成测试（Testcontainers）
- 前端任务列表自动刷新
- 评估 Spring Boot 3.5 + Spring AI Alibaba 增强模块
