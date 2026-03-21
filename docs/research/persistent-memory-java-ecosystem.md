# 持久化记忆在 Java 生态的方案研究

- 创建时间: 2026-03-21 (研究报告)
- 关联项目: EchoFlow (Spring AI 1.1.2 + Spring AI Alibaba 1.1.2.2)

---

## 一、概念辨析：两种"记忆"

在 AI Agent 应用中，"持久化记忆"实际上包含**两个不同层次**的需求：

| 层次 | 是什么 | 典型场景 | 生命周期 |
|------|--------|----------|----------|
| **Chat Memory（对话记忆）** | 多轮对话的消息历史 | ChatBot 记住"我叫 Bob" | 跨请求，按 conversationId |
| **Graph Checkpoint（图执行检查点）** | StateGraph 执行到某节点时的完整状态快照 | 长流程中断后恢复、时间回溯 | 单次执行，按 threadId |

两者互补但不可混淆：Chat Memory 面向"对话连续性"，Graph Checkpoint 面向"工作流容错与恢复"。

---

## 二、Spring AI 官方 Chat Memory 方案

Spring AI 1.1.x 提供了统一的 `ChatMemory` + `ChatMemoryRepository` 体系：

### 2.1 核心架构

```
ChatClient
  └── MessageWindowChatMemory (or TokenWindowChatMemory)
        └── ChatMemoryRepository (SPI)
              ├── InMemoryChatMemoryRepository   — 内存，开发用
              ├── JdbcChatMemoryRepository       — JDBC（PostgreSQL/MySQL/...）
              ├── CassandraChatMemoryRepository  — Cassandra
              └── Neo4jChatMemoryRepository      — Neo4j 图数据库
```

### 2.2 JdbcChatMemoryRepository（最契合本项目）

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-chat-memory-repository-jdbc</artifactId>
</dependency>
```

```java
@Autowired
JdbcChatMemoryRepository chatMemoryRepository;

ChatMemory chatMemory = MessageWindowChatMemory.builder()
    .chatMemoryRepository(chatMemoryRepository)
    .maxMessages(10)
    .build();
```

- 自动建表（`ai_chat_memory`），支持 PostgreSQL dialect
- 按 `conversationId` 存储 `Message` 列表
- 支持滑动窗口（`maxMessages`）自动截断
- 可通过自定义 `Dialect` 扩展

### 2.3 其他实现

| 实现 | 适用场景 | 本项目适用性 |
|------|----------|-------------|
| **InMemory** | 开发/测试 | 仅测试用 |
| **JDBC** | 关系型数据库 | **最佳选择**（已有 PostgreSQL） |
| **Cassandra** | 超大规模、跨区域 | 过重，不适用 |
| **Neo4j** | 需要图关系查询的记忆 | 可能未来有价值（知识图谱），当前不需要 |

---

## 三、Spring AI Alibaba Graph Checkpoint 方案

Spring AI Alibaba 1.1.x 的 Graph 模块提供了 `CheckpointSaver` 体系：

### 3.1 核心架构

```
CompiledGraph
  └── CompileConfig
        └── SaverConfig
              └── CheckpointSaver (SPI)
                    ├── MemorySaver       — 内存（默认）
                    ├── RedisSaver        — Redis 持久化
                    └── PostgresSaver     — PostgreSQL（raw JDBC）
                    └── 自定义扩展         — 如 JpaCheckpointSaver（EchoFlow 已有）
```

### 3.2 三种内置 Saver 对比

| Saver | 持久化 | 优点 | 缺点 |
|-------|--------|------|------|
| **MemorySaver** | 内存 | 零配置，速度快 | 重启丢失 |
| **RedisSaver** | Redis | 高性能读写，TTL 自动过期 | 需额外 Redis 实例 |
| **PostgresSaver** | PostgreSQL | 与数据库统一 | 使用 raw JDBC 自行建表，绕过 Flyway |

### 3.3 高级能力

```java
// 时间旅行 — 获取状态历史
List<StateSnapshot> history = graph.getStateHistory(config);

// 从特定检查点回放
var replayConfig = RunnableConfig.builder()
    .threadId("demo")
    .checkPointId(snapshot.config().checkPointId().orElse(null))
    .build();
graph.invoke(Map.of(), replayConfig);

// 恢复中断的工作流（使用相同 threadId 空输入调用）
graph.call(Map.of(), config); // 从最后 checkpoint 恢复
```

---

## 四、EchoFlow 现有方案分析

### 4.1 当前实现：JpaCheckpointSaver

项目已实现 `JpaCheckpointSaver extends MemorySaver`，采用 hook 模式覆盖四个生命周期方法：

```
loadedCheckpoints()    → 启动时从 DB 加载
insertedCheckpoint()   → 节点执行后保存到 DB
updatedCheckpoint()    → 更新（实际是 append 新行）
releasedCheckpoints()  → 执行完成时清理
```

**数据库表**（`V4__graph_checkpoint.sql`）：

```sql
CREATE TABLE graph_checkpoint (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id      VARCHAR(100) NOT NULL,   -- executionId
    node_id        VARCHAR(100),            -- 当前节点
    next_node_id   VARCHAR(100),            -- 下一节点
    state          JSONB NOT NULL DEFAULT '{}',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 4.2 设计决策与权衡

| 决策 | 原因 |
|------|------|
| 继承 MemorySaver 而非 PostgresSaver | PostgresSaver 使用 raw JDBC 自行建表，违反 Flyway 规范（Rule 10） |
| 持久化失败仅 log warn | 非关键路径设计，避免 checkpoint 故障阻断正常执行 |
| Domain-based recovery 而非 checkpoint resume | 不依赖框架未文档化的 checkpoint resume 语义 |
| Append-only 设计 | 支持审计追踪，可回溯执行历史 |

### 4.3 现有方案的局限

1. **仅覆盖 Graph Checkpoint，不覆盖 Chat Memory** — 无法跨执行共享对话上下文
2. **清理策略过于简单** — 执行结束即删除，无法做事后分析或时间旅行
3. **未利用 StateGraph 原生 resume** — recovery 完全走 domain 重建，丢失了框架能力
4. **无跨任务的长期记忆** — 同一用户的不同 Task 之间无上下文传递

---

## 五、方案推荐：分层记忆架构

基于项目现状和 Spring AI 生态能力，推荐**三层记忆架构**：

### 5.1 架构总览

```
┌─────────────────────────────────────────────┐
│              Application Layer              │
│  ┌─────────────────────────────────────┐    │
│  │  MemoryAwareExecuteTaskUseCase      │    │
│  │  (编排记忆的加载与持久化时机)          │    │
│  └─────────────────────────────────────┘    │
├─────────────────────────────────────────────┤
│           Infrastructure Layer              │
│                                             │
│  Layer 1: Graph Checkpoint (已有)            │
│  ├── JpaCheckpointSaver                     │
│  └── 单次执行内的状态快照与恢复              │
│                                             │
│  Layer 2: Chat Memory (新增)                 │
│  ├── JdbcChatMemoryRepository (Spring AI)   │
│  └── 多轮对话历史，按 conversationId         │
│                                             │
│  Layer 3: Long-term Memory (未来)            │
│  ├── pgvector + 向量检索                     │
│  └── 跨任务的知识/偏好/经验积累              │
│                                             │
└─────────────────────────────────────────────┘
```

### 5.2 Layer 1: Graph Checkpoint — 保持现有方案，小幅优化

**现状**：已有 `JpaCheckpointSaver`，运行稳定。

**建议优化**：
- **保留 checkpoint 用于审计**：完成后不立即删除，而是标记 `released=true`，定期清理
- **可选**：评估是否启用 StateGraph 原生 `getStateHistory()` + checkpoint resume，替代部分 domain-based recovery

**改动量**：极小，非必须。

### 5.3 Layer 2: Chat Memory — 最高优先级，推荐立即实施

**目标**：让 Agent 在单次 Execution 的多个 Step 之间，以及同一 Task 的重试之间，拥有对话记忆。

**实施方案**：

```xml
<!-- pom.xml 新增依赖 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-chat-memory-repository-jdbc</artifactId>
</dependency>
```

```java
// Infrastructure 层 — ChatMemoryPort 适配器
@Component
class JdbcChatMemoryAdapter implements ChatMemoryPort {

    private final ChatMemory chatMemory;

    JdbcChatMemoryAdapter(JdbcChatMemoryRepository repository) {
        this.chatMemory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(repository)
            .maxMessages(20)  // 可配置
            .build();
    }

    @Override
    public void addMessages(String conversationId, List<Message> messages) {
        chatMemory.add(conversationId, messages);
    }

    @Override
    public List<Message> getMessages(String conversationId) {
        return chatMemory.get(conversationId);
    }

    @Override
    public void clear(String conversationId) {
        chatMemory.clear(conversationId);
    }
}
```

**conversationId 策略**：
- 选项 A：`executionId` — 单次执行内各 Step 共享上下文（最小变更）
- 选项 B：`taskId` — 同一 Task 的重试间共享上下文
- **推荐选项 A**，后续按需扩展到 B

**与现有 StepExecutor 的集成点**：
- `ReactAgentStepExecutor` 每次调用 LLM 时，通过 `ChatClient` 的 advisor 链注入 `MessageChatMemoryAdvisor`
- Step 产出的 summary 写入 ChatMemory，后续 Step 可自动获取前序上下文

### 5.4 Layer 3: Long-term Memory — 未来方向

**目标**：跨 Task 的长期知识积累（如用户偏好、项目经验、常见错误模式）。

**可选方案**：
- **pgvector 语义检索**：将完成的执行 summary 向量化存储，新任务时检索相关经验
- **Neo4j 知识图谱**：如果需要结构化的实体关系（如"用户 A 的项目 B 偏好 C 技术栈"）

**当前建议**：项目已有 pgvector 扩展支持（CLAUDE.md 提及），暂不实施，但架构预留扩展点。

---

## 六、推荐实施路径

| 阶段 | 内容 | 优先级 | 改动量 |
|------|------|--------|--------|
| **Phase 1** | 引入 `JdbcChatMemoryRepository`，在 StepExecutor 中注入 ChatMemory | P0 | 中等 |
| **Phase 2** | 优化 Checkpoint 清理策略，支持执行历史审计 | P1 | 小 |
| **Phase 3** | 基于 pgvector 的长期记忆，跨 Task 语义检索 | P2 | 较大 |

---

## 七、技术版本对照

| 组件 | 当前版本 | 记忆相关能力 |
|------|----------|-------------|
| Spring AI | 1.1.2 | `ChatMemory`, `JdbcChatMemoryRepository`, `MessageWindowChatMemory` |
| Spring AI Alibaba | 1.1.2.2 | `MemorySaver`, `RedisSaver`, `PostgresSaver`, `getStateHistory()`, checkpoint resume |
| PostgreSQL | 16+ | JSONB (checkpoint state), pgvector (future long-term memory) |

---

## 八、关键结论

1. **项目已有的 `JpaCheckpointSaver` 是一个优秀的 Graph Checkpoint 方案**，正确地绕过了 `PostgresSaver` 的 Flyway 违规问题，设计合理。

2. **当前最大的缺口是 Chat Memory 层**。Spring AI 官方的 `JdbcChatMemoryRepository` 是最自然的选择 —— 零额外基础设施（复用 PostgreSQL），Spring Boot 自动配置，与 `ChatClient` advisor 体系无缝集成。

3. **不建议引入 Redis 仅用于 checkpoint** —— 当前 PostgreSQL + JPA 方案已够用，Redis 增加运维复杂度但收益不大。如果未来有高频 checkpoint 场景或需要 TTL 自动过期，再考虑 `RedisSaver`。

4. **Spring AI Alibaba Graph 的原生 resume 能力值得关注** —— 当前 domain-based recovery 虽然可靠，但随着图拓扑复杂化（并行、条件分支、review 循环），纯 domain 重建的成本会上升。可在 Phase 2 评估是否部分采用 checkpoint resume。
