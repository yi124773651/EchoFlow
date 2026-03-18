# 023 — LLM Fallback 路径评估

## Progress

- 完成 `executor/` 包双层执行架构（ReactAgent 主路径 + LLM fallback）的全面评估 ✅
- 分析代码量、耦合度、测试覆盖、风险面 ✅
- 决策：**保留 LLM fallback 路径**，不做删除或重构 ✅

## 评估结论

### 决策：保留（KEEP）

### 代码规模

| 组件 | 文件数 | 代码行数 |
|------|--------|---------|
| ReactAgent 主路径 | 5 | ~246 行 |
| LLM fallback 路径 | 5 | ~198 行 |
| Hook/Interceptor（仅 ReactAgent） | 2 | ~149 行 |
| StepExecutorRouter（调度） | 1 | ~140 行 |
| **总计** | **13** | **~733 行** |

LLM fallback 路径仅占 **27%**（198/733），维护成本低。

### 保留理由

1. **框架风险对冲**：Spring AI Alibaba 的 ReactAgent 框架仍处于快速迭代期（1.1.x），API 可能出现 breaking change 或 bug。LLM 路径使用标准 Spring AI ChatClient API，稳定性更高。当框架升级导致 ReactAgent 不可用时，LLM 路径可自动接管。

2. **维护成本极低**：5 个文件共 198 行，几乎无需主动维护。`LlmStepExecutor` 基类和 4 个子类逻辑简单，不依赖外部框架 hook/interceptor。

3. **行为一致性已验证**：两层路径具有完整的行为对等性：
   - THINK 步骤在两层都排除 `previousContext`
   - RESEARCH/NOTIFY 步骤在两层都注册相同的工具
   - 输出验证和截断逻辑相同
   - 重试次数相同（MAX_RETRIES = 2）

4. **测试覆盖充分**：`StepExecutorRouterTest` 中有 8 个测试专门验证 fallback 行为（4 种 StepType × fallback + guard condition + 工具注册验证 + 双层失败传播）。

5. **生产可观测**：每次 fallback 触发都有 `log.warn` 记录，包含 step 名称、类型和失败原因。

### 不删除的理由

- **删除节省极少**：仅减少 198 行和 5 个文件，不会显著降低复杂度。
- **删除增加风险**：失去独立于 Agent Framework 的降级能力，任何 ReactAgent 级别的 bug 都会导致步骤直接失败。
- **无代码腐化迹象**：LLM 路径通过 `StepExecutorRouter` 被间接使用，不是死代码。

### 可选的未来改进（非当前优先级）

| 改进 | 收益 | 优先级 |
|------|------|--------|
| 为 LLM fallback 子类添加独立单元测试 | 补充测试覆盖缺口 | P3 |
| 提取 `buildPreviousContext()` 到共享工具类 | 消除 ~10 行重复 | P4（不建议，为保持独立性） |
| 添加 fallback 触发的 metrics（计数器） | 生产监控 | P2 |

## DDD Decisions

无 DDD 变更。LLM fallback 路径完全在 Infrastructure 层内部，不影响 Domain 或 Application 的边界。

## Technical Notes

### 双层 fallback 触发条件

```java
// StepExecutorRouter.execute()
try {
    return reactExecutor.execute(context);           // 主路径
} catch (StepExecutionException e) {
    if (fallbackClient == primaryClient) throw e;    // 同一模型，不 fallback
    return llmExecutor.execute(context, fallbackClient); // 降级路径
}
```

- 仅捕获 `StepExecutionException`（ReactAgent 基类在重试耗尽后抛出）
- 通过对象引用比较判断 fallback 是否可用（primaryClient ≠ fallbackClient）
- 如果两层都失败，异常直接传播给调用方（`StepNodeAction`），由 StateGraph 做 graceful degradation

### 与 Hook/Interceptor 的关系

`MessageTrimmingHook` 和 `ToolRetryInterceptor` 仅在 ReactAgent 路径中使用。LLM fallback 路径不需要它们：
- LLM 调用是单次 prompt，无需消息裁剪
- 工具失败在 `LlmStepExecutor.execute()` 的重试循环中处理

## Next Steps

- 无后续行动。此评估为最终决策，LLM fallback 路径保持现状。
- 更新 devlog 022 的 Next Steps 标记此项为已完成。
