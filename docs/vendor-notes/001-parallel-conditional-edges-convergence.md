# addParallelConditionalEdges — routeMap value 节点出边必须统一 convergence

**发现版本**: spring-ai-alibaba-graph-core 1.1.2.2
**影响 API**: `StateGraph.addParallelConditionalEdges(sourceId, AsyncMultiCommandAction, routeMap)`
**严重程度**: 高（静默错误行为，无编译/运行时异常）

---

## 隐式约束

`routeMap` 中所有 value 节点（即条件路由的目标节点）的出边必须指向同一个 convergence 节点。

违反此约束时，框架不会抛异常，而是产生不确定的运行时行为。

## 根因

`CompiledGraph` 编译阶段创建 `ConditionalParallelNode` 时，调用 `findParallelNodeTargets(mappedNodeIds)` 收集 routeMap 中所有 value 节点的出边目标：

```java
// CompiledGraph.java
var parallelNodeTargets = findParallelNodeTargets(mappedNodeIds);
if (!parallelNodeTargets.isEmpty()) {
    edges.put(conditionalParallelNode.id(),
              new EdgeValue(parallelNodeTargets.iterator().next()));
}
```

`findParallelNodeTargets` 返回一个 `Set<String>`。当 value 节点的出边指向不同目标时，set 包含多个元素，`iterator().next()` 取到哪个取决于 `HashSet` 内部顺序 — 不确定。

## 触发场景

当 `MultiCommandAction` 的路由结果包含"并行执行"和"跳过"两条路径，且跳过路径是串行链时：

```
routeMap:
  run_1 → nodeA    (出边 → convergence)
  run_2 → nodeB    (出边 → convergence)
  skip  → skipNode1 (出边 → skipNode2，再 → convergence)
```

`skipNode1` 的直接出边是 `skipNode2`（不是 convergence），导致 `findParallelNodeTargets` 返回 `{convergence, skipNode2}`。

## 可能的症状

- 本应被跳过的节点在"执行"路径下也被触发
- 并行节点执行后跳转到错误的后续节点
- 测试间歇性失败（HashSet 顺序不稳定）

## 正确用法

确保 routeMap 中每个 value 节点的直接出边都指向同一个目标：

```java
// 所有 value 节点 → 同一 convergence
graph.addEdge(nodeA, convergence);
graph.addEdge(nodeB, convergence);
graph.addEdge(skipNode, convergence);  // 直接指向 convergence，不经过中间节点
```

如果跳过路径需要执行多个操作，应聚合到单一节点内部处理，而非用串行链：

```java
// ✅ 单一聚合节点，内部循环处理多个跳过操作
graph.addNode("skip_all", new AggregatedSkipAction(stepsToSkip));
graph.addEdge("skip_all", convergence);

// ❌ 串行链，中间节点出边不指向 convergence
graph.addNode("skip_1", new SkipAction(step1));
graph.addNode("skip_2", new SkipAction(step2));
graph.addEdge("skip_1", "skip_2");
graph.addEdge("skip_2", convergence);
```

## 建议框架改进

1. 编译时校验：`findParallelNodeTargets` 返回多个目标时应抛 `GraphStateException`
2. 或支持异构后续路径：允许不同 value 节点有不同的 convergence 目标
