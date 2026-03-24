# 032 - StepToolRegistry 重构

## Progress
- 新增 StepToolRegistry：按 StepType 聚合工具列表
- ReactAgentStepExecutor 基类接管 configureAgent() 工具注册逻辑
- 四个 Executor 子类删除工具字段和 configureAgent override，构造器统一为 (ChatClient, String, List<Object>)
- StepExecutorRouter 构造器从 14+ 参数降至 7 个
- LLM fallback executor 也清理了工具字段（降级路径无工具）
- 工具创建集中到 StepToolRegistryConfig，每个工具一个 @Bean
- 删除 WebSearchToolConfig（合并）

## DDD Decisions
- StepToolRegistry 保留在 Infrastructure executor 包内
  它是执行器的内部协调机制，不是领域概念
- 构造器改为 public 以支持 Web 模块 config 跨包创建

## Technical Notes
- 未来加工具流程：创建工具类 → StepToolRegistryConfig 加 @Bean → stepToolRegistry() 加映射
- 不需要改任何 Executor 或 Router 代码
- LLM fallback executor 清理后不再持有工具——降级路径保持简单

## Next Steps
- 评估是否需要将 GitHub/Webhook 配置也改为 @ConfigurationProperties（目前用 @Value）
