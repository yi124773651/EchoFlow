# RAG 在 Java 生态的方案研究与 EchoFlow 适用性分析

- 创建时间: 2026-03-21 (研究报告)
- 关联项目: EchoFlow (Spring AI 1.1.2 + Spring AI Alibaba 1.1.2.2)
- 关联报告: `docs/research/persistent-memory-java-ecosystem.md`

---

## 一、RAG 概念与核心流水线

### 1.1 什么是 RAG

RAG（Retrieval-Augmented Generation）是一种将**外部知识检索**与**LLM 生成**结合的模式。核心思路：在 LLM 回答问题前，先从知识库中检索相关文档片段，注入到 prompt 上下文中，让 LLM 基于真实数据生成答案。

```
用户问题 → [检索相关文档] → [拼接到 Prompt] → [LLM 生成] → 回答
```

### 1.2 RAG vs 工具调用（Tool Calling）

| 维度 | RAG | Tool Calling |
|------|-----|-------------|
| 数据来源 | 预先索引的向量库（离线准备） | 实时 API 调用（在线获取） |
| 延迟 | 低（向量相似度搜索，毫秒级） | 高（网络请求，秒级） |
| 数据新鲜度 | 取决于索引频率 | 实时 |
| 适用场景 | 大量静态/半静态文档 | 动态数据、外部系统交互 |
| 上下文质量 | 语义相关，可能有噪声 | 精确查询，结果可控 |
| LLM 控制权 | 框架自动注入，LLM 被动接收 | LLM 主动决定调用时机和参数 |

**关键洞察**：RAG 和 Tool Calling 不是互斥的。最佳实践是组合使用——RAG 提供背景知识，Tool Calling 获取实时数据。

---

## 二、Spring AI 的 RAG 体系（1.1.x）

### 2.1 整体架构

Spring AI 提供了完整的 RAG 流水线，分为两大阶段：

```
阶段一：离线索引（ETL Pipeline）
  DocumentReader → DocumentTransformer → DocumentWriter(VectorStore)

阶段二：在线检索增强（Advisor Chain）
  用户问题 → QueryTransformer → DocumentRetriever → ContextAugmentor → LLM
```

### 2.2 ETL Pipeline（离线索引）

```java
// 三个核心接口
DocumentReader    implements Supplier<List<Document>>       // 读取
DocumentTransformer implements Function<List<Document>, List<Document>>  // 转换
DocumentWriter    implements Consumer<List<Document>>       // 写入

// 典型流水线
vectorStore.write(tokenTextSplitter.split(pdfReader.read()));
```

**内置 DocumentReader**：
- `JsonReader` — JSON 文件
- `TextReader` — 纯文本
- `PagePdfDocumentReader` — PDF（按页）
- `TikaDocumentReader` — 通用格式（Word、HTML、PPT 等，基于 Apache Tika）

**内置 DocumentTransformer**：
- `TokenTextSplitter` — 按 token 数切分（最常用）
- `ContentFormatTransformer` — 格式化内容
- `KeywordMetadataEnricher` — 自动提取关键词元数据
- `SummaryMetadataEnricher` — 自动生成摘要元数据（需调用 LLM）

### 2.3 VectorStore（向量存储）

Spring AI 提供统一的 `VectorStore` 接口和多种实现：

| 实现 | 特点 | 本项目适用性 |
|------|------|-------------|
| **PgVectorStore** | 复用 PostgreSQL + pgvector | **最佳选择**（零额外基础设施） |
| ChromaVectorStore | 独立向量数据库 | 过重 |
| MilvusVectorStore | 高性能向量数据库 | 过重 |
| PineconeVectorStore | 托管云服务 | 过重 |
| SimpleVectorStore | 内存，开发用 | 仅测试 |
| Redis | Redis Stack | 需额外组件 |

**PgVectorStore 配置**：

```yaml
spring:
  ai:
    vectorstore:
      pgvector:
        index-type: HNSW           # 近似最近邻索引
        distance-type: COSINE_DISTANCE
        dimensions: 1536           # 取决于 embedding 模型
        initialize-schema: false   # 必须 false，用 Flyway 管理
        table-name: vector_store
```

```sql
-- Flyway 迁移脚本（遵循项目 Rule 10）
CREATE TABLE IF NOT EXISTS vector_store (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content   TEXT,
    metadata  JSONB,
    embedding vector(1536)
);
CREATE INDEX ON vector_store USING HNSW (embedding vector_cosine_ops);
```

### 2.4 Advisor 体系（在线检索增强）

Spring AI 提供两种 RAG Advisor：

#### QuestionAnswerAdvisor（简单模式）

```java
var qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
    .searchRequest(SearchRequest.builder()
        .similarityThreshold(0.8)
        .topK(6)
        .build())
    .build();

String answer = chatClient.prompt()
    .advisors(qaAdvisor)
    .user(question)
    .call()
    .content();
```

- 自动检索、自动拼接、开箱即用
- 适合简单 Q&A 场景

#### RetrievalAugmentationAdvisor（高级模式）

```java
Advisor advisor = RetrievalAugmentationAdvisor.builder()
    .queryTransformers(RewriteQueryTransformer.builder()
        .chatClientBuilder(chatClient.mutate())
        .build())
    .documentRetriever(VectorStoreDocumentRetriever.builder()
        .vectorStore(vectorStore)
        .similarityThreshold(0.50)
        .topK(5)
        .filterExpression(new FilterExpressionBuilder()
            .eq("source", "internal-docs")
            .build())
        .build())
    .build();
```

- 支持查询改写（Query Rewriting）
- 支持元数据过滤（Filter Expression）
- 支持动态过滤（基于租户等上下文）
- 可组合多个 Retriever

### 2.5 Embedding 模型选择

| 模型 | 维度 | 来源 | 备注 |
|------|------|------|------|
| text-embedding-ada-002 | 1536 | OpenAI | 通用，成熟 |
| text-embedding-3-small | 1536 | OpenAI | 更便宜 |
| text-embedding-3-large | 3072 | OpenAI | 更精确 |
| text-embedding-v2 | 1536 | DashScope (通义) | 中文优化 |
| bge-large-zh-v1.5 | 1024 | 开源 | 中文最佳开源 |

**注意**：项目当前使用 OpenAI-compatible 接口 + DashScope，embedding 模型选择需与 chat 模型提供商对齐。

---

## 三、Spring AI Alibaba 的 RAG 能力

### 3.1 与 Spring AI 的关系

Spring AI Alibaba 在 RAG 方面**完全复用 Spring AI 的接口体系**（`VectorStore`、`Advisor`、`DocumentReader` 等），额外提供：

- **DashScope Embedding**：`text-embedding-v2`（中文优化）
- **DashScope 知识库服务**：托管式 RAG（文档上传、自动索引、检索 API）

### 3.2 DashScope Embedding 配置

```yaml
spring:
  ai:
    dashscope:
      embedding:
        enabled: true
        options:
          model: text-embedding-v2
```

### 3.3 评估

对于 EchoFlow，**不建议使用 DashScope 托管知识库**——违反"PostgreSQL 为唯一真相源"原则（CLAUDE.md Rule 5）。应使用本地 PgVectorStore + DashScope/OpenAI Embedding 模型的组合。

---

## 四、EchoFlow 现状分析

### 4.1 已就位的基础设施

| 组件 | 状态 | 详情 |
|------|------|------|
| pgvector 扩展 | 已启用 | `V1__init.sql: CREATE EXTENSION IF NOT EXISTS vector` |
| 向量表 | 未创建 | 无向量列、无 HNSW 索引 |
| Embedding 依赖 | 未引入 | pom.xml 中无 embedding starter |
| VectorStore 配置 | 无 | application.yml 中无相关配置 |
| RAG Advisor | 未使用 | 无 QuestionAnswerAdvisor / RetrievalAugmentationAdvisor |
| ETL Pipeline | 未实现 | 无 DocumentReader / Transformer / Writer |
| 向量规范 | 已就位 | agent.md Rule 7 + ops.md Rule 2 已定义约束 |

### 4.2 现有 RESEARCH 步骤的工作方式

当前 RESEARCH 步骤采用**纯 Tool Calling 模式**：

```
用户任务 → THINK(分析) → RESEARCH(GitHub API 搜索) → WRITE(合成输出)
```

- 工具：`GitHubSearchTool`（REST API 搜索 GitHub 仓库）
- 优点：实时、精确
- 局限：
  - 仅能搜索 GitHub 仓库，无法检索私有文档/内部知识
  - 每次 RESEARCH 都是"从零开始"，不积累知识
  - 无法利用历史执行的研究成果

---

## 五、EchoFlow 是否需要 RAG？

### 5.1 判断框架

引入 RAG 的前提是存在以下至少一个条件：

1. **大量静态/半静态文档需要被检索** — 如产品文档、技术规范、FAQ
2. **LLM 需要领域特定知识** — 超出通用训练数据的专业内容
3. **重复性问题** — 相似问题反复出现，每次都从头搜索太浪费
4. **上下文窗口不够** — 文档太多放不进 prompt，需要精准检索子集

### 5.2 EchoFlow 当前阶段的评估

| 条件 | 当前是否满足 | 说明 |
|------|-------------|------|
| 大量静态文档 | **否** | 项目是任务执行引擎，不是知识问答系统 |
| 领域特定知识 | **部分** | 用户任务可能涉及特定领域，但当前 RESEARCH 步骤通过 Tool Calling 满足 |
| 重复性问题 | **否** | 每个 Task 通常是独立的 |
| 上下文窗口瓶颈 | **否** | 当前 step outputs 通过 StateGraph 的 APPEND 策略传递，尚未遇到瓶颈 |

**结论：当前阶段 RAG 不是刚需，但存在 3 个可预见的关键场景值得提前规划。**

### 5.3 三个可预见的 RAG 关键场景

#### 场景 A：执行经验复用（Experience Retrieval）

**问题**：同一用户提交的多个 Task 之间没有知识传递。如果用户之前研究过"Java 21 virtual threads 最佳实践"，下次再问相关问题时，Agent 无法利用之前的研究成果。

**RAG 方案**：
```
每次执行完成 → 将 WRITE 步骤的输出摘要 + 元数据 embedding → 写入 vector_store
下次 RESEARCH 步骤 → 先检索 vector_store 中的历史成果 → 补充到 prompt
```

**架构集成点**：
```java
// GraphOrchestrator 执行完成后，触发索引
@Component
class ExecutionKnowledgeIndexer {
    void onExecutionCompleted(Execution execution) {
        var summary = execution.getWriteStepOutput();
        var doc = new Document(summary, Map.of(
            "taskId", execution.getTaskId().toString(),
            "topic", execution.getTask().getDescription(),
            "completedAt", Instant.now().toString()
        ));
        vectorStore.add(List.of(doc));
    }
}

// RESEARCH 步骤执行前，检索历史经验
// 方式一：通过 Advisor 自动注入
var advisor = RetrievalAugmentationAdvisor.builder()
    .documentRetriever(VectorStoreDocumentRetriever.builder()
        .vectorStore(vectorStore)
        .similarityThreshold(0.7)
        .topK(3)
        .build())
    .build();

// 方式二：通过 Tool 让 Agent 主动检索
@Tool(description = "Search past execution results for relevant knowledge")
String searchPastExecutions(String query) { ... }
```

**价值评估**：中高。随着使用量增长，历史经验的复用价值显著。
**推荐时机**：Phase 2（Chat Memory 之后）。

#### 场景 B：私有文档/知识库检索（Knowledge Base RAG）

**问题**：当前 RESEARCH 步骤只能搜索 GitHub 公开仓库，无法检索用户上传的私有文档（如内部技术规范、产品 PRD、API 文档）。

**RAG 方案**：
```
用户上传文档 → ETL Pipeline（读取→分块→embedding）→ vector_store
RESEARCH 步骤 → 同时检索 vector_store + 调用 GitHub 工具
```

**架构集成点**：
```java
// 新增 Application 层用例
class IndexDocumentUseCase {
    void execute(IndexDocumentCommand cmd) {
        var reader = resolveReader(cmd.fileType());      // PDF/TXT/Markdown
        var docs = reader.read(cmd.inputStream());
        var chunks = tokenTextSplitter.split(docs);
        // 添加元数据：用户、项目、文档类型
        chunks.forEach(d -> d.getMetadata().putAll(Map.of(
            "userId", cmd.userId(),
            "docType", cmd.fileType(),
            "uploadedAt", Instant.now().toString()
        )));
        vectorStore.write(chunks);
    }
}

// RESEARCH 步骤新增检索工具
@Tool(description = "Search uploaded documents and internal knowledge base")
String searchKnowledgeBase(
    @ToolParam(description = "Semantic search query") String query) {
    var results = vectorStore.similaritySearch(
        SearchRequest.builder().query(query).topK(5).build());
    return formatResults(results);
}
```

**价值评估**：高，但需要前端配合（文档上传 UI）。
**推荐时机**：Phase 3（作为独立功能模块）。

#### 场景 C：长上下文压缩（Context Compression）

**问题**：随着 Execution 步骤增多（特别是并行 RESEARCH + Review 循环），StateGraph 的 `outputs` 累积可能超过 LLM 上下文窗口。当前通过 `APPEND` 策略无差别累加所有步骤输出。

**RAG 方案**：
```
outputs 过长时 → 将早期步骤输出写入临时向量索引
后续步骤 → 按需检索相关片段，而非全量注入
```

**架构集成点**：
```java
// StepNodeAction 中，当累积 outputs 超过阈值时
if (totalTokenCount(outputs) > CONTEXT_THRESHOLD) {
    // 将早期 outputs 转存到执行级临时向量索引
    indexToTempVectorStore(executionId, earlyOutputs);
    // 后续步骤从向量库检索 + 保留最近 N 个步骤的完整输出
    state.put("outputs", recentOutputs);
    state.put("ragEnabled", true);
}
```

**价值评估**：中。当前步骤数 2-6 个，尚未触及窗口限制。但如果未来支持更复杂的任务（>10 步），会成为瓶颈。
**推荐时机**：按需（观察到上下文溢出再实施）。

---

## 六、实施路径建议

### 6.1 分阶段路线图

```
Phase 0 (当前)：无 RAG，纯 Tool Calling
    ↓ 条件：Chat Memory 实施后 + 有复用历史经验的需求
Phase 1：执行经验复用（场景 A）
    - 引入 PgVectorStore + Embedding 模型
    - 完成时自动索引执行摘要
    - RESEARCH 步骤增加 @Tool 检索
    ↓ 条件：有用户上传文档的产品需求
Phase 2：私有知识库（场景 B）
    - 引入 ETL Pipeline（DocumentReader + TokenTextSplitter）
    - 文档上传 API + 索引用例
    - 前端文档管理 UI
    ↓ 条件：观察到上下文溢出
Phase 3：长上下文压缩（场景 C）
    - 动态 RAG vs 全量注入
    - 执行级临时索引
```

### 6.2 Phase 1 技术清单（执行经验复用）

| 项目 | 内容 |
|------|------|
| **Maven 依赖** | `spring-ai-starter-vectorstore-pgvector` + embedding starter |
| **Flyway 迁移** | 创建 `vector_store` 表 + HNSW 索引 |
| **配置** | `spring.ai.vectorstore.pgvector.*` + embedding 模型配置 |
| **Domain** | 无变更（向量存储是 Infrastructure 关注点） |
| **Application** | 新增 `ExecutionSummaryPort`（port 接口） |
| **Infrastructure** | `PgVectorExecutionIndexer`（实现 port，监听执行完成事件） |
| **Infrastructure** | `KnowledgeSearchTool`（`@Tool` 注解，供 RESEARCH 步骤使用） |
| **Prompt** | 更新 `step-research.st`，提示 Agent 可使用知识检索工具 |
| **测试** | Mock VectorStore 的单元测试 + Testcontainers 集成测试 |

### 6.3 关键架构约束（对齐 CLAUDE.md）

| 约束 | 对齐方式 |
|------|----------|
| Rule 5: Domain 纯 Java | Domain 不感知 RAG，知识检索是 Infrastructure 实现细节 |
| Rule 5: 依赖方向 | Application 定义 Port → Infrastructure 实现 Adapter |
| Rule 7: Vector Rules | 向量存储仅作检索支持，非真相源（agent.md Rule 7） |
| Rule 9: AI Rules | 向量检索结果作为 untrusted input 验证后使用 |
| Rule 10: Data Rules | `initialize-schema=false`，表结构通过 Flyway 管理 |
| ops.md Rule 2 | 向量列和索引在迁移脚本中显式定义 |

---

## 七、Tool Calling vs RAG Advisor：EchoFlow 的选择

### 7.1 两种集成方式对比

| 方式 | 原理 | 优点 | 缺点 |
|------|------|------|------|
| **RAG Advisor** | 框架自动在每次 LLM 调用前检索并注入上下文 | 零代码改动，透明增强 | Agent 无法控制检索时机，可能注入无关内容 |
| **RAG as Tool** | 将向量检索封装为 `@Tool`，由 Agent 自主决定是否调用 | Agent 可判断是否需要检索，更精准 | 需显式工具定义，Agent 可能不调用 |

### 7.2 推荐：RAG as Tool（对齐 ReactAgent 模式）

EchoFlow 的 StepExecutor 已采用 ReactAgent 模式（Thought → Action → Observation），将 RAG 封装为 `@Tool` 更自然：

```java
@Tool(description = "Search past execution results and indexed knowledge for relevant context. "
    + "Use this when the task is similar to something previously researched or when internal "
    + "documentation might contain relevant information.")
String searchKnowledge(
    @ToolParam(description = "Semantic search query describing what you're looking for")
    String query) {
    var results = vectorStore.similaritySearch(
        SearchRequest.builder().query(query).topK(5).similarityThreshold(0.7).build());
    return results.stream()
        .map(doc -> "---\n" + doc.getContent() + "\nSource: " + doc.getMetadata())
        .collect(Collectors.joining("\n\n"));
}
```

**理由**：
1. 与现有 `GitHubSearchTool` 模式一致
2. Agent 可根据任务性质决定是否检索（非每次强制）
3. 可与 Tool Calling 的 GitHub 搜索组合使用
4. 可观测性更好（工具调用记录在 ExecutionLog 中）

---

## 八、技术风险与注意事项

### 8.1 Embedding 模型与 Chat 模型的一致性

- 如果 chat 用 OpenAI 兼容接口，embedding 也应使用同系列模型
- 如果 chat 用 DashScope，embedding 推荐 `text-embedding-v2`（中文优化）
- **不要混用不同厂商的 embedding 和 chat 模型** —— embedding 向量空间不兼容

### 8.2 索引维护成本

- 每次执行完成都会写入向量库，需要考虑数据增长
- 建议添加 TTL 或定期清理策略（如保留最近 90 天）
- 向量维度选择影响存储和检索性能：1536 维是通用选择

### 8.3 检索质量

- `similarityThreshold` 设置过低会引入噪声，过高会漏掉有价值的结果
- 建议从 0.7 开始，基于实际效果调优
- 考虑添加元数据过滤（如按用户、按主题分类）

### 8.4 与 Flyway 的协调

- **必须** `initialize-schema=false`（禁止 PgVectorStore 自动建表）
- 向量表、索引全部通过 Flyway 迁移脚本管理
- 这与项目已有 `JpaCheckpointSaver` 绕过 `PostgresSaver` 的设计决策一致

---

## 九、结论

| 问题 | 回答 |
|------|------|
| **EchoFlow 当前是否需要 RAG？** | **不是刚需**，但有 3 个可预见的关键场景 |
| **最有价值的场景是什么？** | 场景 A：执行经验复用（跨 Task 知识传递） |
| **最大的潜在价值是什么？** | 场景 B：私有知识库（让 Agent 检索用户文档） |
| **何时引入？** | Chat Memory（persistent-memory 报告的 Phase 1）之后，作为 Phase 2 |
| **用什么技术栈？** | PgVectorStore + OpenAI/DashScope Embedding + `@Tool` 封装 |
| **架构影响大吗？** | 不大。RAG 完全在 Infrastructure 层，不影响 Domain/Application |
| **需要新增基础设施吗？** | 不需要。复用 PostgreSQL + pgvector（已启用），零额外组件 |

**一句话总结**：RAG 在 EchoFlow 中是"锦上添花"而非"雪中送炭"。当前阶段优先完成 Chat Memory，后续随着使用量增长和产品需求演进（特别是"历史经验复用"和"私有文档检索"），再按场景渐进式引入 RAG。架构已为此预留了清晰的扩展路径。
