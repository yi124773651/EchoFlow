# EchoFlow

AI 驱动的异步任务执行平台。用户通过自然语言下达复杂任务，AI Agent 自动将其分解为可执行步骤（思考 → 调研 → 写作 → 通知），以流水线方式逐步执行，并通过 SSE 实时推送进度到前端看板。

## 核心场景

> "帮我调研一下 GitHub 上最近热门的 Java Agent 项目，写一份对比分析报告存为 Markdown，如果发现有超过 5000 Star 的，发通知提醒我。"

用户提交任务后，EchoFlow 会：

1. **THINK** — AI 分析任务意图，制定执行策略
2. **RESEARCH** — 调用 GitHub Search 等工具进行调研
3. **WRITE** — 基于调研结果撰写 Markdown 格式报告
4. **NOTIFY** — 通过 Webhook 发送通知

整个过程在前端任务看板实时可见，每一步的思考、行动、观察日志均可回溯。

## 技术栈

### 后端

| 组件 | 版本 |
|------|------|
| Java | 21 (Virtual Threads) |
| Spring Boot | 3.4 |
| Spring AI | 1.0 (OpenAI 兼容端点) |
| PostgreSQL | 16+ (pgvector) |
| Flyway | 数据库迁移 |
| Maven | 3.9+ (含 wrapper) |

### 前端

| 组件 | 版本 |
|------|------|
| Next.js | 16 (App Router) |
| React | 19 |
| TypeScript | 5 (strict) |
| Tailwind CSS | 4 |
| ShadcnUI | 组件库 |

### 测试

| 组件 | 用途 |
|------|------|
| JUnit 5 + Mockito + AssertJ | 单元测试 |
| Testcontainers | 集成测试 (PostgreSQL) |

## 项目结构

```
echoflow/
├── echoflow-backend/
│   ├── echoflow-domain/           # 纯 Java 领域模型，零框架依赖
│   ├── echoflow-application/      # 用例、端口接口、事务边界
│   ├── echoflow-infrastructure/   # JPA 适配器、AI 客户端、Tool 实现
│   └── echoflow-web/              # Spring Boot 启动、控制器、Flyway 迁移、Prompt 模板
└── echoflow-frontend/             # Next.js 前端
```

### 依赖方向（严格单向）

```
web → application → domain ← infrastructure
```

- **Domain** — 聚合根（Task、Execution）、实体（ExecutionStep）、值对象（StepLog）、仓储接口。不依赖任何框架。
- **Application** — 用例编排（SubmitTaskUseCase、ExecuteTaskUseCase）、端口接口（TaskPlannerPort、StepExecutorPort）。拥有事务边界。
- **Infrastructure** — JPA 持久化实现、LLM 步骤执行器（StepExecutorRouter 按 StepType 分发）、GitHub Search / Webhook 工具。
- **Web** — 薄控制器、SSE 事件推送、全局异常处理（ProblemDetail）、配置。

## 领域模型

```
Task (聚合根)              Execution (聚合根)
├── status: SUBMITTED      ├── status: PLANNING
│         → EXECUTING      │         → RUNNING
│         → COMPLETED      │         → COMPLETED
│         → FAILED         │         → FAILED
│                          └── steps: List<ExecutionStep>
│                                     ├── type: THINK | RESEARCH | WRITE | NOTIFY
│                                     ├── status: PENDING → RUNNING → COMPLETED | SKIPPED | FAILED
│                                     └── logs: List<StepLog>  (append-only)
│                                               └── type: THOUGHT | ACTION | OBSERVATION | ERROR
```

## 快速开始

### 前置条件

- Java 21+
- Node.js 18+
- PostgreSQL 16+（启用 pgvector 扩展）
- OpenAI 兼容的 LLM API 端点

### 1. 克隆仓库

```bash
git clone https://github.com/your-org/EchoFlow.git
cd EchoFlow
```

### 2. 配置环境变量

在项目根目录创建 `.env` 文件：

```bash
# 数据库
export DB_URL="jdbc:postgresql://localhost:5432/echoflow"
export DB_USERNAME="echoflow"
export DB_PASSWORD="echoflow"

# AI（OpenAI 兼容端点）
export AI_BASE_URL="https://api.openai.com"
export AI_API_KEY="sk-..."
export AI_MODEL="gpt-4o-mini"

# GitHub（可选，用于 RESEARCH 步骤）
export GITHUB_TOKEN=""
export GITHUB_API_BASE_URL="https://api.github.com"

# Webhook（可选，用于 NOTIFY 步骤）
export WEBHOOK_URL="https://webhook.site/your-uuid"
```

### 3. 创建数据库

```bash
createdb echoflow
```

### 4. 构建与启动

```bash
# 全量构建（后端 + 前端）
./mvnw clean install

# 启动后端
source .env && ./mvnw spring-boot:run -pl echoflow-backend/echoflow-web

# 启动前端（另开终端）
cd echoflow-frontend
npm run dev
```

后端默认运行在 `http://localhost:8080`，前端在 `http://localhost:3000`。

### 仅构建后端

```bash
./mvnw clean install -pl echoflow-backend -am
```

## 运行测试

```bash
# 全部后端测试（93 个，含 Testcontainers 集成测试）
./mvnw test -pl echoflow-backend -am

# 单个测试类
./mvnw test -pl echoflow-backend/echoflow-domain -Dtest=TaskTest

# 单个测试方法
./mvnw test -pl echoflow-backend/echoflow-domain -Dtest="TaskTest#should_transition_to_executing"

# 前端 lint
cd echoflow-frontend && npm run lint
```

测试覆盖：

| 层 | 单元测试 | 集成测试 | 合计 |
|----|---------|---------|------|
| Domain | 39 | — | 39 |
| Application | 11 | — | 11 |
| Infrastructure | 20 | 23 | 43 |
| **总计** | **70** | **23** | **93** |

## API 概览

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/tasks` | 创建任务，立即触发异步执行 |
| GET | `/api/tasks` | 获取任务列表 |
| GET | `/api/tasks/{taskId}` | 获取任务详情（含执行快照） |
| GET | `/api/tasks/{taskId}/execution/stream` | SSE 实时执行流 |

### SSE 事件类型

- `ExecutionStarted` — 执行开始，包含规划的步骤列表
- `StepStarted` — 某步骤开始执行
- `StepLogAppended` — 步骤日志追加（THOUGHT / ACTION / OBSERVATION / ERROR）
- `StepCompleted` — 步骤执行完成
- `StepSkipped` — 步骤因失败被降级跳过
- `ExecutionCompleted` — 全部步骤完成
- `ExecutionFailed` — 执行中止

## 关键设计决策

- **双聚合根分离** — Task（用户意图）与 Execution（执行过程）分离，支持未来重试
- **Port/Adapter 模式** — Application 层定义端口，Infrastructure 实现，AI 和工具可替换
- **事务不跨远程 I/O** — LLM 调用、HTTP 请求绝不在数据库事务内执行
- **降级优于中止** — 单步失败标记为 SKIPPED，继续执行后续步骤，返回部分结果
- **Virtual Threads** — 异步执行利用 Java 21 虚拟线程
- **SSE 实时推送** — 禁止轮询，所有前端更新通过 Server-Sent Events
- **追加写入日志** — StepLog 不可变，确保审计轨迹完整性
- **乐观锁** — Task 和 Execution 使用版本号防止并发冲突

## 文档

- `docs/plan/mvp-plan.md` — MVP 整体规划
- `docs/devlog/` — 开发日志（001-010），记录每次迭代的进展、DDD 决策和技术细节
- `docs/claude/` — 架构指南（backend、agent、frontend、ops）

## License

MIT
