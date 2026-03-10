# Plan: Fix P0 Issues — Transaction Boundary & AI Timeout

- **创建时间**: 2026-03-10 12:45 CST
- **完成时间**: 2026-03-10 12:55 CST
- **状态**: ✅ 已完成
- **关联 devlog**: [004-tx-boundary-and-ai-timeout](../devlog/004-tx-boundary-and-ai-timeout.md)

---

## Context

vibe-check (2026-03-10) 发现两个 CRITICAL 问题：

1. **`ExecuteTaskUseCase.planExecution()` 的 `@Transactional` 包裹了 LLM 调用** — 违反 Rule 4（"Never keep a DB transaction open across remote I/O, AI calls, or streaming"）。
2. **ChatClient 调用无 timeout** — 违反 Rule 8.6（"Every model call must define timeout"）。

两者叠加时，LLM 无响应会导致数据库连接被无限期持有，最终耗尽连接池。

---

## P0-1: 拆分事务边界（ExecuteTaskUseCase）

### 问题分析

- `planExecution()` 上的 `@Transactional` 包裹了 `taskPlanner.planSteps()` LLM 调用。
- 所有 6 个 `@Transactional protected` 方法都通过 self-invocation 调用，Spring proxy 不会拦截 —— **注解实际是 no-op**，但形成隐患：一旦有人重构为外部调用，LLM 就会在真实事务内执行。
- `completeExecution()` / `failExecution()` 更新两个聚合（Execution + Task），当前**缺乏原子性**。

### 方案

注入 `TransactionOperations`（`TransactionTemplate` 的接口），用编程式事务替代声明式。

#### 修改 `ExecuteTaskUseCase.java`

1. 新增字段 `private final TransactionOperations tx;`，作为第 7 个构造参数。
2. **删除所有 6 个 `@Transactional`** 注解及 import。
3. 重构 `planExecution()`：
   ```
   读 task (隐式 repo 事务) → LLM 调用 (无事务) → 校验 → tx.executeWithoutResult { save task + save execution } → publish 事件
   ```
4. 重构 `completeExecution()` / `failExecution()`：用 `tx.executeWithoutResult` 包裹多聚合写操作。
5. 简化 `completeStep()` / `failStep()` / `saveExecution()`：去掉 `@Transactional`，单次 `save()` 依赖 repo 隐式事务。
6. 所有 helper 从 `protected` 改为 `private`；`planExecution()` 保持 package-private 供测试访问。
7. SSE 事件发布移到事务提交之后。

#### 修改 `ExecuteTaskUseCaseTest.java`

- `setUp()` 构造函数增加第 7 个参数 `TransactionOperations.withoutTransaction()`。
- 7 个现有测试**无需其他改动**，语义不变。

### 涉及文件

| 文件 | 操作 |
|------|------|
| `echoflow-application/.../execution/ExecuteTaskUseCase.java` | 修改 |
| `echoflow-application/.../execution/ExecuteTaskUseCaseTest.java` | 修改 |

---

## P0-2: AI 调用添加 HTTP Timeout

### 问题分析

- `AiTaskPlanner` 和 `StepExecutorRouter` 构建 `ChatClient` 时无 timeout 配置。
- `application.yml` 的 `spring.ai.openai` 下无 timeout 属性（标准 OpenAI starter 不支持）。
- Spring AI 1.0.0 的 `OpenAiChatAutoConfiguration` 接受 `ObjectProvider<RestClient.Builder>`，可注入自定义 builder。

### 方案

在 `echoflow-web` 创建配置类，提供带超时的 `RestClient.Builder` bean。

#### 新建 `AiClientConfig.java`（echoflow-web/config/）

```java
@Configuration
public class AiClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder(
            @Value("${echoflow.ai.connect-timeout:10s}") Duration connectTimeout,
            @Value("${echoflow.ai.read-timeout:60s}") Duration readTimeout) {

        var httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder().requestFactory(requestFactory);
    }
}
```

- 使用 `JdkClientHttpRequestFactory`，兼容虚拟线程。
- Spring AI 的 `OpenAiChatAutoConfiguration` 会自动注入此 builder。
- 默认值：connect 10s、read 60s，可通过 `application.yml` 或环境变量覆盖。

#### 修改 `application.yml`

```yaml
echoflow:
  ai:
    connect-timeout: 10s
    read-timeout: 60s
```

### 涉及文件

| 文件 | 操作 |
|------|------|
| `echoflow-web/.../config/AiClientConfig.java` | 新建 |
| `echoflow-web/.../resources/application.yml` | 修改 |

---

## 实施顺序

1. **P0-1**：修改 `ExecuteTaskUseCase` + 测试 → `./mvnw test -pl echoflow-backend/echoflow-application`
2. **P0-2**：新建 `AiClientConfig` + 改 yml → `./mvnw clean install -pl echoflow-backend -am`
3. **Devlog**：编写 `docs/devlog/004-tx-boundary-and-ai-timeout.md`

## 验证

- `./mvnw test -pl echoflow-backend -am` — 全部 53+ 测试通过
- 代码中不再有 `@Transactional` 包裹 LLM 调用
- `application.yml` 包含 timeout 配置
- `AiClientConfig` 提供带超时的 `RestClient.Builder`
