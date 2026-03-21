# EchoFlow

[English](README.md) | 中文

一个面向复杂异步任务的 AI Agent 编排平台。用户提交自然语言任务后，系统自动将其分解为多步骤执行计划（THINK → RESEARCH → WRITE → NOTIFY），由 AI Agent 自主执行每个步骤，并通过现代 Web UI 实时流式展示执行进度。

## 核心亮点

- **StateGraph 动态编排引擎** — 三种图拓扑：线性链、条件并行路由（THINK 输出驱动 RESEARCH 扇出/跳过）、WRITE 审阅循环（LLM-as-Judge 评分 → 自动修订，最多 3 轮）
- **双层执行引擎 + 自动降级** — 主路径：具备工具调用能力的 ReAct Agent（GitHub 搜索、Webhook 通知）。降级路径：直接 LLM 调用。4 种步骤类型共 10 个执行器类，通过 `StepExecutorRouter` 透明切换
- **多模型按步骤路由** — 配置驱动的 StepType → Model 映射。支持 OpenAI 兼容接口 + DashScope（通过 Spring AI Alibaba）双通道，跨模型自动 fallback
- **Human-in-the-Loop 审批门控** — WRITE 步骤执行前暂停，进入 `WAITING_APPROVAL` 状态。基于 Virtual Thread + CompletableFuture 实现非阻塞等待。支持 UI 审批/拒绝，可配置超时自动放行
- **SSE 实时流式推送** — 10+ 种事件类型（sealed interface）。三层可靠性保障：事件缓冲 + 重放、SSE-first 连接策略、REST 快照对账补齐
- **检查点持久化与启动恢复** — 自定义 `JpaCheckpointSaver` 将 StateGraph 检查点存储到 PostgreSQL（JSONB）。重启时：孤儿执行安全失败，WAITING_APPROVAL 执行自动恢复

## 架构

```
echoflow/
├── echoflow-backend/
│   ├── echoflow-domain/           纯 Java，零框架依赖
│   ├── echoflow-application/      用例、端口接口、事务边界
│   ├── echoflow-infrastructure/   JPA、AI 客户端、StateGraph、适配器
│   └── echoflow-web/              控制器、SSE 推送、Flyway 迁移、Prompt 模板
└── echoflow-frontend/             Next.js App Router，SSE 消费
```

**严格依赖方向**：`web → application → domain` ← `infrastructure`

通过 Maven 模块边界在编译期强制执行。Domain 层零 Spring/JPA/AI SDK 导入。

### 领域模型

两个聚合根：

- **Task（任务）** — 用户意图。状态：`SUBMITTED → EXECUTING → COMPLETED | FAILED`
- **Execution（执行）** — 任务的一次运行，包含有序的 `ExecutionStep` 实体和只追加的 `StepLog` 值对象。步骤类型：`THINK`、`RESEARCH`、`WRITE`、`NOTIFY`

### 关键模式

| 模式 | 实现方式 |
|------|---------|
| 端口/适配器 | Application 层定义 `TaskPlannerPort`、`StepExecutorPort`、`GraphOrchestrationPort`；Infrastructure 层实现 |
| 事务隔离 | LLM 调用在事务外执行：读事务 → AI 调用（无事务）→ 写事务 |
| 事件驱动 SSE | `SseExecutionEventPublisher` → 前端 `useExecutionStream` hook。禁止轮询 |
| 条件路由 | THINK prompt 返回 `[ROUTING]` 提示 → `RoutingHintParser` 解析 → GraphOrchestrator 构建条件边 |
| 审阅循环 | WRITE → `WriteReviewGateAction`（LLM 评分）→ `WriteReviseAction`（修订）→ 反向边。达到最大次数强制通过 |
| 乐观锁 | Task 和 Execution 聚合根上的 version 列 |

## 技术栈

| 层次 | 技术 |
|------|------|
| 语言 | Java 21（Virtual Threads、Records、Sealed Types、Pattern Matching） |
| 框架 | Spring Boot 3.5、Spring MVC、Spring Data JPA |
| AI | Spring AI 1.1、Spring AI Alibaba 1.1（StateGraph、ReAct Agent） |
| 数据库 | PostgreSQL 16+、pgvector、Flyway 迁移 |
| 前端 | Next.js 16、React 19、TypeScript（strict）、Tailwind CSS v4、ShadcnUI |
| 测试 | JUnit 5、Mockito、AssertJ、Testcontainers |
| 构建 | Maven 3.9+（内置 wrapper） |

## 快速开始

### 前置条件

- Java 21+
- Node.js 22+
- PostgreSQL 16+（需启用 `pgvector` 扩展）
- OpenAI 兼容 API Key（或 DashScope）

### 环境配置

```bash
cp .env.example .env
# 编辑 .env，填入数据库和 AI 服务凭证：
# DB_URL, DB_USERNAME, DB_PASSWORD, AI_BASE_URL, AI_API_KEY, AI_MODEL
```

### 构建与运行

```bash
# 完整构建（后端 + 前端）
./mvnw clean install

# 启动后端
source .env && ./mvnw spring-boot:run -pl echoflow-backend/echoflow-web

# 启动前端开发服务器（另开终端）
cd echoflow-frontend && npm run dev
```

后端运行在 `localhost:8080`，前端运行在 `localhost:3000`。

### 运行测试

```bash
# 全部后端测试
./mvnw test -pl echoflow-backend -am

# 单个测试类
./mvnw test -pl echoflow-backend/echoflow-domain -Dtest=TaskTest
```

## 项目指标

| 指标 | 数量 |
|------|------|
| 后端 Java 类 | 120+ |
| 后端测试方法 | 230+ |
| Flyway 迁移脚本 | 8 |
| 步骤执行器实现 | 10（5 ReAct + 5 LLM 降级） |
| 图拓扑类型 | 3（线性、条件并行、审阅循环） |
| SSE 事件类型 | 10+ |
| 开发日志 | 16 篇，记录每个阶段的架构决策 |

## 工程实践

- **TDD**：Red → Green → Refactor，每个行为变更都从测试开始
- **Testcontainers**：集成测试使用真实 PostgreSQL 容器
- **Flyway 独占迁移**：`spring.jpa.hibernate.ddl-auto=validate`，所有 Schema 变更通过版本化脚本管理
- **开发日志**：`docs/devlog/` 下 16 篇 devlog，记录每个阶段的架构决策、技术权衡与实现细节

## 许可证

[MIT](LICENSE)
