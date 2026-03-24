# 033 RESEARCH runLimit 与 Checkpoint JSONB 修复

## Progress

- 修复 RESEARCH 步骤因 `ModelCallLimitHook.runLimit(5)` 过低导致搜索结果丢失的问题
- 修复 `CheckpointEntity.state` 字段 JSONB 类型不匹配导致 checkpoint 持久化全部失败的问题

## 问题分析

### 主要问题：WRITE 步骤收不到 RESEARCH 搜索结果

**现象**：WRITE 步骤生成的报告声称"缺少真实搜索结果"，但 RESEARCH 步骤日志显示 Tavily 搜索成功执行了 4 次。

**根因追踪**：
1. RESEARCH ReactAgent 配置 `ModelCallLimitHook.runLimit(5)`
2. Agent 执行了 4 次 `search` 工具调用（均成功）+ 1 次 LLM 幻觉的 `search.exec`（失败）
3. 5 次 model call 全部耗尽，Agent 无法进行第 6 次调用来汇总搜索结果
4. Agent 被强制终止，RESEARCH 步骤无有效输出
5. WRITE 步骤的 `previousOutputs` 中只有 THINK 输出（规划信息），没有搜索数据

**修复**：为 RESEARCH 步骤设置独立的 `maxModelCalls=15`，其余步骤保持默认值 5。

### 次要问题：Checkpoint JSONB 写入失败

**现象**：所有 checkpoint 持久化均报错 `column "state" is of type jsonb but expression is of type character varying`。

**根因**：`CheckpointEntity.state` 仅有 `columnDefinition = "JSONB"`，Hibernate 仍按 `varchar` 绑定 JDBC 参数。

**修复**：添加 `@JdbcTypeCode(SqlTypes.JSON)` 注解。

## DDD Decisions

- `maxModelCalls` 属于 Infrastructure 层的 AI 执行配置，不涉及 Domain 或 Application 层
- `CheckpointEntity` 是 Infrastructure 层 JPA 实体，修改不影响 Domain 纯净性

## Technical Notes

- `ReactAgentStepExecutor` 新增 4 参数构造函数，保持 3 参数向后兼容（默认 `maxModelCalls=5`）
- 仅 `ReactAgentResearchExecutor` 使用 4 参数构造函数，其余子类沿用默认值
- Hibernate 6 的 `@JdbcTypeCode(SqlTypes.JSON)` 是处理 PostgreSQL JSONB 列的标准方式

## Next Steps

- 观察生产环境 RESEARCH 步骤是否稳定产出搜索结果
- 考虑将 `maxModelCalls` 提取为配置项（当前硬编码为常量，YAGNI）
- 验证 checkpoint 持久化恢复正常后，startup recovery 流程是否可靠
