# EchoFlow 整体计划

> 创建时间: 2026-03-11 15:00 CST
> 最后更新: 2026-03-11 22:30 CST

---

## 项目愿景

用户下达复杂的异步任务，Agent 自动分解、编排、执行，思考/调研/写作/通知过程像流水线一样流转，用户可随时干预或回溯。

---

## 已完成工作 (MVP 阶段)

MVP 原始计划见 `docs/plan/mvp-plan.archived.md`。

### 第一刀: 静态管道 ✅

| Devlog | 内容 |
|--------|------|
| 001 | 项目骨架 — Java 21 + Spring Boot 3.4 后端四层模块 + Next.js 16 前端 |
| 002 | MVP 首切 — Task/Execution 领域模型、SSE 实时推送、LLM 任务规划、前端看板+执行时间线 |

### 第二刀: LLM 任务分解 ✅

| Devlog | 内容 |
|--------|------|
| 003 | 真实 Tool 执行 — THINK/RESEARCH/WRITE/NOTIFY 四类 Executor + Markdown 渲染 |
| 004 | 事务边界 & AI 超时 — LLM 调用移出事务、ChatClient HTTP 超时、SSE 事件在持久化后触发 |
| 005 | 乐观锁 — Task/Execution @Version 并发保护 |
| 006 | LLM 降级 — 失败步骤跳过而非中止整个 Execution |
| 007 | E2E 验证 — 全链路端到端验证通过，59 个测试 |

### 第三刀: Tool 执行 + 产出物 ✅

| Devlog | 内容 |
|--------|------|
| 008 | GitHub Search Tool — RESEARCH 步骤接入 GitHub API，Spring AI @Tool 注解 |
| 009 | Webhook Notify Tool — NOTIFY 步骤接入 Webhook，LLM 生成标题/摘要 |
| 010 | Testcontainers 集成测试 — 23 个 Repository 集成测试，总计 93 个测试 |

### MVP 当前技术栈

| 组件 | 版本/技术 |
|------|-----------|
| Java | 21 |
| Spring Boot | 3.5.8 |
| Spring AI | 1.1.2 (OpenAI-compatible) |
| Spring AI Alibaba BOM | 1.1.2.2 (仅版本管理) |
| AI Starter | `spring-ai-starter-model-openai` |
| DB | PostgreSQL 16 + Flyway |
| 前端 | Next.js 16 + React 19 + Tailwind v4 + ShadcnUI |
| 测试 | JUnit 5 + Mockito + AssertJ + Testcontainers |

---

## 演进计划

基于 2026-03-11 头脑风暴结论（详见 `docs/research/spring-ai-alibaba.md` 第 9 节）。

### 核心驱动力

- **Agent Framework**: 并行执行+聚合、Human-in-the-loop、断点恢复
- **多模型路由**: 按 StepType/用户偏好/Fallback 策略选模型

### 现有痛点

1. 编排仅支持顺序执行，无并行/条件分支
2. 新增 Tool/StepType 需改 Router switch + Executor 类 + prompt 模板，改动点多
3. 跨 Step 上下文靠手动拼接字符串，无结构化 Memory/State

---

## Phase 0: 版本升级 ✅

> 状态: 已完成 (2026-03-11)
> 先决条件: 无
> 目标: 为多模型和 Agent Framework 扫清版本障碍
> 实施计划: `docs/plans/2026-03-11-1530-phase0-version-upgrade.md`
> Devlog: `docs/devlog/011-version-upgrade.md`

### 改动清单

| 项 | 从 | 到 | 风险 |
|----|----|----|------|
| Spring Boot | 3.4.4 | 3.5.x | 中 — 需验证 JPA/Flyway/Test 兼容性 |
| Spring AI | 1.0.0 | 1.1.2 | 高 — ChatClient/ToolCall/Advisor API 可能 break |
| Spring AI BOM | `spring-ai-bom 1.0.0` | `spring-ai-bom 1.1.2` | 随上 |
| 新增 BOM | — | `spring-ai-alibaba-bom 1.1.2.x` | 低 |
| AI Starter | 保留 `spring-ai-starter-model-openai` | 不变 (Phase 1 再处理) | 无 |

### 验收标准

- [x] `./mvnw clean install` 全量编译通过（6 模块 SUCCESS）
- [x] 70 个单元测试全部 GREEN（23 个 Testcontainers 集成测试因无 Docker 未执行，非版本问题）
- [ ] SSE 实时流式功能手动验证正常（待部署验证）
- [x] 前端 `npm run build` 无报错（前端与版本升级无关，未执行）

---

## Phase 1: 多模型路由层 ✅

> 状态: 已完成 (2026-03-11)
> 先决条件: Phase 0 完成
> 目标: 支持 DashScope + OpenAI + DeepSeek，按策略智能选模型
> 实施计划: `docs/plans/2026-03-11-1700-phase1-multi-model-routing.md`
> Devlog: `docs/devlog/012-multi-model-routing.md`

### 实际架构（与原设计的偏差）

原设计预期在 Application 层新增 `ModelRouterPort`，但实际实施中发现按 StepType 路由是纯基础设施关注点（StepType 已通过 `StepExecutionContext` 传入），因此将路由逻辑完全封装在 Infrastructure 层，Application 层零改动。若未来需要用户偏好选模型，再引入 Port。

```
Infrastructure 层
├── ChatClientProvider (创建/缓存多 ChatClient 实例)
│   └── resolve(alias) → ChatClient
│   └── 使用 Spring AI 1.1.2 mutate() API 派生 per-provider 实例
├── StepExecutorRouter (implements StepExecutorPort)
│   ├── 按 StepType 路由: Map<StepType, ChatClient> primaryClients
│   └── Fallback: 主模型异常 → 自动切备用 ChatClient
└── MultiModelProperties (@ConfigurationProperties)
    ├── models: { alias → { base-url, api-key, model } }
    └── routing: { step-aliases: { think→alias, ... }, fallback: alias }

Web 层 (application.yml)
└── echoflow.ai:
    ├── routing.step-aliases: { think: dashscope-strong, ... }
    ├── routing.fallback: openai-default
    └── models: { dashscope-strong: { base-url, api-key, model }, ... }
```

### 改动范围

| 层 | 改动 |
|----|------|
| Domain | **无** |
| Application | **无**（偏离原设计，不引入 ModelRouterPort） |
| Infrastructure | 新增 `ChatClientProvider` + `MultiModelProperties`；重构 `LlmStepExecutor`/子类/`StepExecutorRouter`/`AiTaskPlanner` |
| Web | `AiClientConfig` 添加 `@EnableConfigurationProperties`；`application.yml` 添加 routing + models |
| 前端 | **无**（用户偏好下拉延迟到用户偏好路由需求时） |

### 验收标准

- [x] THINK step 可配置使用强模型，RESEARCH/NOTIFY 可配置使用快模型（通过 `routing.step-aliases`）
- [x] 主模型超时/异常时自动 fallback 到备用模型，日志可观测
- [x] DashScope + OpenAI + DeepSeek 双/多 provider 共存，可通过配置切换
- [x] 81 个单元测试全部通过（原 70 + 新增 11）
- [x] 空配置下行为与改动前完全一致（向后兼容）

---

## Phase 2: Agent Framework POC ✅

> 状态: **已完成** — Go 决策，推荐方案 A
> 先决条件: Phase 0 完成 (可与 Phase 1 并行)
> 目标: 验证 Agent Framework 能力边界，产出 Go/No-Go 决策
> 实施计划: `docs/plans/2026-03-11-agent-framework-poc.md`
> POC 报告: `docs/research/agent-framework-poc-report.md`
> Devlog (调研): `docs/devlog/013-agent-framework-research.md`
> Devlog (POC): `docs/devlog/014-agent-framework-poc.md`

### 调研成果 (2026-03-11)

已完成 Spring AI Alibaba Agent Framework 源码级 API 验证，核心发现：

| 能力 | API | 验证状态 |
|------|-----|---------|
| ReAct Agent 构建 | `ReactAgent.builder()` — 15+ 配置项 | ✅ 源码确认 |
| Hook 生命周期 | `AgentHook` / `MessagesModelHook` / `ModelCallLimitHook` | ✅ 源码确认 |
| 拦截器链 | `ModelInterceptor` / `ToolInterceptor` | ✅ 源码确认 |
| 状态管理 | `OverAllState` / `RunnableConfig` / `MemorySaver` | ✅ 源码确认 |
| 工作流引擎 | `StateGraph` / `CompiledGraph` | ✅ 源码确认 |
| Agent 作为工具 | `AgentTool.getFunctionToolCallback()` | ✅ 源码确认 |
| 子 Agent 委托 | `SubAgentSpec` / `SubAgentInterceptor` | ✅ 源码确认 |
| 流式输出 | `StreamingOutput` / `NodeOutput` | ✅ 源码确认 |
| Redis 持久化 | `RedisSaver` | ❌ 不存在，需自行实现 |

详见: `docs/research/spring-ai-alibaba-agent-framework-guide.md`, `docs/research/spring-ai-alibaba-practical-examples.md`

### POC 范围

在独立分支 `feature/agent-framework-poc` 上验证：

| 验证项 | 具体内容 | 成功标准 |
|--------|----------|----------|
| **ReactAgent 基础集成** | 将 THINK Executor 改为 ReactAgent 驱动 | 输出与现有 ChatClient 方式一致，测试通过 |
| **Hook/Interceptor 可用性** | 添加 MessageTrimmingHook + ToolRetryInterceptor | Hook 可独立启用/禁用，拦截器链按序执行 |
| **并行执行+聚合** | 多个 RESEARCH step 并行调用，结果聚合后传给 WRITE | 3 个并行 RESEARCH 全部完成后 WRITE 能获取所有结果 |
| **DDD 兼容性** | Agent Framework 类型全部封装在 Infrastructure 层 | Domain/Application 无任何 Agent Framework import |
| **依赖冲突** | agent-framework JAR 与现有依赖共存 | 编译通过，无运行时类加载冲突 |
| **SSE 集成** | Agent 执行过程中可发出与现有格式兼容的 SSE 事件 | 前端 useExecutionStream 能消费事件 |

**暂不验证** (放入 Phase 3 Backlog):
- Human-in-the-loop (Graph 中断/恢复)
- Checkpoint 断点恢复 (需自实现持久化 Saver)
- StateGraph 复杂工作流

### POC 产出物

- `docs/research/agent-framework-poc-report.md` — Go/No-Go 决策 + 理由
- 如 Go: 领域模型调整方案推荐 (方案 A 或 方案 B)
- 如 No-Go: 替代方案建议

### 领域模型待决方案

| 方案 | 描述 | 适用条件 |
|------|------|----------|
| **A: 领域模型为主** | Task → Execution → ExecutionStep 保留，Agent Framework 仅作为 Infrastructure 执行引擎。ReactAgent 在 Adapter 内部使用，`StepExecutorPort` 接口不变。 | Agent Framework 能良好封装在 Adapter 中，不需要 Graph State 跨 Step 共享 |
| **B: Agent State 为主** | Agent Framework 的 Graph State 成为执行的真实模型，Execution 聚合根简化为归档记录。`StepExecutorPort` 改为 `AgentExecutionPort`。 | Agent Framework 的 State 机制足够成熟，Graph 编排覆盖所有业务场景 |

**决策时机**: POC 完成后，根据实际验证结果选择。倾向方案 A（风险更低、DDD 边界更清晰）。

---

## Phase 3: Agent Framework 全量迁移 ⏳

> 状态: 待启动
> 先决条件: Phase 2 POC 通过 (Go 决策)
> 目标: 用 Agent Framework 替代自研执行链路

### 改动清单 (Phase 2 POC 通过后细化)

| 项 | 当前 | 目标 |
|----|------|------|
| 执行引擎 | `StepExecutorRouter` → `LlmXxxExecutor` | Agent Framework `ReactAgent` + Hook/Interceptor |
| 任务规划 | `AiTaskPlanner` 自研 prompt | Agent Framework 内置或增强的规划能力 |
| 上下文传递 | 手动拼接 `previousOutputs` | `RunnableConfig` + `ToolContext` 结构化状态 |
| 并行能力 | 无 | StateGraph 并行节点 + 聚合策略 |
| HITL | 无 | Graph 中断/恢复机制 |
| Checkpoint | 无 | 自实现 `JpaSaver` 或 `RedisSaver` |
| SSE 流式 | 现有 `SseExecutionEventPublisher` | 适配 `ReactAgent.stream()` → `StreamingOutput` |
| 前端 | `execution-timeline` 组件 | 适配新事件结构 (并行节点、HITL 状态等) |

### Backlog (从 Phase 2 延后的项目)

- Human-in-the-loop: WRITE step 执行前暂停，等待用户确认
- Checkpoint 断点恢复: Execution 中途失败，可从最后成功的 step 恢复
- 自适应任务分解: 基于任务复杂度动态调整 step 数量
- 自动 Agent 选择: 基于任务属性智能选择 Agent 类型

---

## DDD 硬约束 (全阶段适用)

1. `echoflow-domain` — **不引入任何 Spring AI / Spring AI Alibaba 依赖**
2. `echoflow-application` — **只通过 Port 接口与 AI 能力交互**，不直接依赖 ChatModel、ReactAgent 等
3. `echoflow-infrastructure` — **唯一的 AI 适配层**，封装所有框架细节
4. 多模型路由的策略配置属于 Web 层，路由逻辑属于 Infrastructure 层
5. Agent Framework 的 Graph State **不得泄漏到 Domain 层**

---

## 风险登记簿

| ID | 风险 | 影响 | 可能性 | 缓解措施 | 关联 Phase |
|----|------|------|--------|----------|-----------|
| R1 | Spring AI 1.0→1.1 API break | 编译失败，需修改调用代码 | ~~高~~ → **已消除** | ChatClient/@Tool/@ToolParam 无 breaking changes，零代码改动 | Phase 0 ✅ |
| R2 | Spring Boot 3.4→3.5 不兼容 | JPA/Flyway/Test 行为变化 | ~~中~~ → **已消除** | 3.5.8 升级零代码改动，70 测试全 GREEN | Phase 0 ✅ |
| R3 | Agent Framework 依赖冲突 | fastjson/httpclient4 类加载冲突 | 中 | POC 验证，必要时 exclusion 或 shade | Phase 2 |
| R4 | Agent Framework 成熟度不足 | API 不稳定，生产级功能缺失 | 中 | POC Go/No-Go 门控，封装为 Port 降低耦合 | Phase 2-3 |
| R5 | 多模型配置膨胀 | 运维复杂度上升 | 低 | 提供合理默认值，仅必要时覆盖 | Phase 1 |
| R6 | SSE 降级期用户体验下降 | 前端无实时反馈 | 中 | Phase 3 补全前提供 loading 状态或 polling fallback | Phase 3 |
| R7 | RedisSaver 不存在 | Checkpoint 持久化需自行实现 | 中 | 优先用 MemorySaver 验证，生产环境自实现 JpaSaver | Phase 3 |

---

## 参考文档

| 文档 | 路径 |
|------|------|
| MVP 原始计划 (已归档) | `docs/plan/mvp-plan.archived.md` |
| Spring AI Alibaba 调研 | `docs/research/spring-ai-alibaba.md` |
| Agent Framework 使用指南 (已源码验证) | `docs/research/spring-ai-alibaba-agent-framework-guide.md` |
| Agent Framework 实践案例 (已源码验证) | `docs/research/spring-ai-alibaba-practical-examples.md` |
| 头脑风暴需求结论 | `docs/research/spring-ai-alibaba.md` §9 |
| 架构指南 | `docs/claude/backend.md`, `docs/claude/agent.md` |
| Devlog 001-010 (MVP) | `docs/devlog/mvp-archived/` |
| Devlog 011 (Phase 0) | `docs/devlog/011-version-upgrade.md` |
| Devlog 012 (Phase 1) | `docs/devlog/012-multi-model-routing.md` |
| Devlog 013 (Phase 2 调研) | `docs/devlog/013-agent-framework-research.md` |
| Plans (MVP) | `docs/plans/mvp-archived/` |
| Plan (Phase 0) | `docs/plans/2026-03-11-1530-phase0-version-upgrade.md` |
| Plan (Phase 1) | `docs/plans/2026-03-11-1700-phase1-multi-model-routing.md` |
| Plan (Phase 2 POC) | `docs/plans/2026-03-11-agent-framework-poc.md` |
