# 031 - Web Search Tool

## Progress
- 新增 WebSearchProvider 接口 + TavilyWebSearchProvider 实现（Infrastructure 层内部）
- 新增 WebSearchTool（@Tool 封装，支持摘要/全文智能切换）
- THINK 和 RESEARCH 步骤接入联网搜索能力
- TavilyProperties 配置绑定
- 完整的单元测试覆盖

## DDD Decisions
- WebSearchProvider 接口保留在 Infrastructure 层 ai/tool/ 包内（package-private），
  不提升到 Application 层——因为没有 Use Case 直接调用搜索，它是工具内部的抽象
- 与 GitHubSearchTool 的自包含模式区别：引入 Provider 抽象是因为搜索引擎
  可能被替换（Tavily -> SearXNG 等），而 GitHub API 不太可能换

## Technical Notes
- Tavily API: POST /search, Bearer auth, basic/advanced depth
- rawContent 截断至 8000 字符防止 LLM 上下文溢出
- HTTP 429 尊重 retry-after header，最多重试 2 次
- HTTP 5xx 重试 1 次，4xx 不重试
- 降级路径（LLM fallback executor）不注入搜索工具，保持简单

## Next Steps
- 配置 TAVILY_API_KEY 后进行端到端验证
- 观察实际使用中 THINK 步骤的搜索频率，评估是否需要调整
- 未来考虑将 StepExecutorRouter 构造器重构（参数膨胀技术债务）
