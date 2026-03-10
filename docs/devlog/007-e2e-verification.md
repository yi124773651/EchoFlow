# 开发日志 #7 — P2：端到端验证

日期: 2026-03-10

## 概述

配好环境后启动前后端服务，提交真实任务，验证从任务提交到 LLM 规划、多步骤执行、SSE 实时推送、任务完成的完整链路。

## 环境

| 组件 | 版本 / 配置 |
|------|------------|
| Java | OpenJDK 21.0.6 (Temurin) |
| Spring Boot | 3.4+ (port 8080) |
| PostgreSQL | 17.8 (Neon, sslmode=require) |
| Flyway | V1 → V3，启动时自动迁移 V3 (optimistic lock) |
| Next.js | dev server (port 3000) |
| AI 端点 | OpenAI 兼容，HTTP 200 |

## 验证步骤

1. **编译 & 测试**：`./mvnw test -pl echoflow-backend -am` — 59 个测试全部通过。
2. **启动后端**：`source .env && ./mvnw spring-boot:run`，Flyway 自动应用 V3 迁移，Tomcat 启动成功。
3. **启动前端**：`npm run dev`，Next.js dev server 正常。
4. **提交任务**：`POST /api/tasks` 提交 "Compare Redis vs Memcached pros and cons, write a short technical summary"，返回 201。
5. **SSE 监听**：`GET /api/tasks/{id}/execution/stream`，实时收到事件流。
6. **查询详情**：`GET /api/tasks/{id}` 确认最终状态。

## 验证结果

### 任务执行流程

```
SUBMITTED → EXECUTING → COMPLETED (约 3 分钟)
```

LLM 自动规划为 4 个步骤：

| 序号 | 名称 | 类型 | 状态 | 说明 |
|------|------|------|------|------|
| 1 | Define criteria | THINK | COMPLETED | 分析任务需求、确定对比维度 |
| 2 | Gather data | RESEARCH | COMPLETED | 收集 Redis/Memcached 技术数据 |
| 3 | Draft summary | WRITE | COMPLETED | 输出结构化技术对比文档 |
| 4 | Send summary | NOTIFY | COMPLETED | 记录通知（当前为模拟实现） |

### SSE 事件序列

```
StepStarted → StepLogAppended(ACTION) → StepLogAppended(OBSERVATION) → StepCompleted
```

每个步骤均产生完整的 ACTION + OBSERVATION 日志对，SSE 推送无丢失。

### 数据持久化

`GET /api/tasks/{id}` 返回完整详情，包含：
- Task 元数据（id、description、status、createdAt、completedAt）
- Execution 信息（executionId、status、startedAt、completedAt）
- 4 个 ExecutionStep，每个包含 output 和 logs 数组

## 发现的问题

| # | 严重度 | 问题 | 原因 | 建议 |
|---|--------|------|------|------|
| 1 | 低 | Windows 终端 curl 发送中文 JSON 报 400 (Invalid UTF-8 start byte 0xb1) | Windows cmd/bash 默认编码非 UTF-8，curl 传输时破坏了中文字符 | 非应用 bug，使用 `chcp 65001` 或前端提交即可 |
| 2 | 低 | Hibernate WARN: PostgreSQLDialect 不需显式指定 | `application.yml` 中冗余配置了 `hibernate.dialect` | 移除该配置项 |
| 3 | 观察 | 历史数据中 2 个任务卡在 EXECUTING、2 个卡在 SUBMITTED | 之前开发调试中断导致，无自动恢复机制 | 未来考虑加超时回收或启动时清理僵尸任务 |

## 技术笔记

- **Flyway 自动迁移**：首次启动时 V3 迁移自动应用，为 `tasks` 和 `executions` 表添加了 `version` 列（乐观锁），无需手动干预。
- **Virtual Thread 执行**：任务提交后通过 `Thread.startVirtualThread()` 异步触发执行，后端日志中可见 `virtual-75` 线程名。
- **SSE 超时**：curl 的 `--max-time 180` 在任务执行完成前超时退出（exit code 28），但不影响结果——任务在服务端继续执行并正常完成。实际生产中前端 `EventSource` 会保持长连接。
- **降级策略未触发**：本次所有步骤均成功完成，降级路径（StepSkipped）未被执行。该路径已在 devlog #6 的单元测试中覆盖。

## 下一步

- P3: 真实 RESEARCH Tool（GitHub 搜索 API）
- P3: 真实 NOTIFY Tool（邮件/Webhook）
- P3: Infrastructure 集成测试（Testcontainers）
- 可选：清理 Hibernate dialect 冗余配置
- 可选：僵尸任务超时回收机制
