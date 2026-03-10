# 开发日志 #10 — P3：Infrastructure 集成测试（Testcontainers）

日期: 2026-03-10

## 概述

为 Infrastructure 持久层添加 Testcontainers 集成测试。`JpaTaskRepository` 和 `JpaExecutionRepository` 两个 Repository Adapter 的 Domain ↔ JPA Entity 映射逻辑、更新路径、乐观锁全部通过真实 PostgreSQL 验证。项目从 70 个纯单元测试增长到 93 个（含 23 个集成测试）。

## 进度

### 测试基础设施

- **`application.yml`**（测试资源，新建）：禁用 Flyway（迁移在 web 模块中），`ddl-auto=create-drop` 从 JPA 注解生成 schema。
- **`TestJpaConfig`**（新建）：Package-private `@SpringBootApplication`，位于 `persistence` 包下。`@DataJpaTest` 自动发现此配置，只加载 JPA 相关 bean，不触发 `ai/` 包的 Spring AI 组件。
- **`AbstractPostgresIntegrationTest`**（新建）：Public 抽象基类，集中 `@Testcontainers` + `@DataJpaTest` + `@AutoConfigureTestDatabase(replace=NONE)` 注解。静态单例 `PostgreSQLContainer<>("postgres:16-alpine")` + `@ServiceConnection` 自动配置数据源，整个测试套件共享一个容器。

### JpaTaskRepositoryIntegrationTest（10 个测试）

| # | 测试方法 | 验证点 |
|---|---------|--------|
| 1 | `save_and_findById_roundTrips_task` | 保存 + 查找，全字段映射正确 |
| 2 | `save_persists_new_task` | `TestEntityManager.find()` 验证实际写入 |
| 3 | `findById_returns_empty_for_nonexistent_id` | 不存在 ID 返回 empty |
| 4 | `findAll_returns_all_saved_tasks` | 3 条保存 + findAll 校验 |
| 5 | `findAll_returns_empty_when_no_tasks` | 空表返回空列表 |
| 6 | `save_updates_existing_task_status` | 更新路径（`if existing.isPresent()` 分支） |
| 7 | `save_updates_completedAt_on_completion` | SUBMITTED→EXECUTING→COMPLETED 完整流转 |
| 8 | `save_and_findById_preserves_unicode_description` | 中文描述 UTF-8 保真 |
| 9 | `roundTrip_preserves_all_task_states` | 四种 TaskStatus 枚举映射 |
| 10 | `concurrent_save_throws_optimistic_lock_exception` | `@Version` 乐观锁 |

### JpaExecutionRepositoryIntegrationTest（13 个测试）

| # | 测试方法 | 验证点 |
|---|---------|--------|
| 1 | `save_and_findById_roundTrips_execution` | 基本映射（无步骤） |
| 2 | `findById_returns_empty_for_nonexistent_id` | 空结果 |
| 3 | `findByTaskId_returns_execution` | 按 taskId 查询 |
| 4 | `findByTaskId_returns_empty_when_no_execution` | 空结果 |
| 5 | `save_and_findById_roundTrips_execution_with_steps` | 三级嵌套步骤映射 |
| 6 | `roundTrip_preserves_step_order` | `@OrderBy("stepOrder ASC")` 排序 |
| 7 | `roundTrip_preserves_step_output` | 步骤 output TEXT 字段 |
| 8 | `roundTrip_preserves_step_logs` | Execution→Step→Log 三级 cascade |
| 9 | `roundTrip_preserves_log_types` | 四种 LogType 枚举映射 |
| 10 | `save_updates_execution_status` | 更新路径 |
| 11 | `save_rebuilds_steps_on_update` | `updateEntity()` clear+rebuild 验证 |
| 12 | `roundTrip_preserves_completed_execution_with_all_step_states` | 完整生命周期（含 SKIPPED 降级） |
| 13 | `concurrent_save_throws_optimistic_lock_exception` | `@Version` 乐观锁 |

## DDD 决策

1. **不修改生产代码** — 所有变更仅在 `src/test/` 目录下，零生产代码改动。
2. **`@DataJpaTest` 而非 `@SpringBootTest`** — 只加载 JPA 相关 slice，避免触发 Spring AI 自动配置。测试聚焦持久层映射，不测试 HTTP 或 AI。
3. **Hibernate DDL 代替 Flyway** — Flyway 迁移在 web 模块的 classpath 上，infrastructure 模块测试不应依赖 web。`create-drop` 从 `@Entity` 注解生成 schema，足以验证映射逻辑。

## 技术笔记

- **`@ServiceConnection`（Spring Boot 3.1+）**：自动从 Testcontainers 容器提取连接参数，无需 `@DynamicPropertySource`。
- **单例容器模式**：`static final PostgreSQLContainer<?>` 由 `@Container` 生命周期管理，整个 JVM 共享一个 PostgreSQL 实例。两个测试类共享同一容器，总启动时间约 60 秒。
- **乐观锁测试方法**：使用 `TestEntityManager.persistAndFlush()` → `detach()` → 修改并 flush 新副本 → 尝试 `merge()` 旧副本触发 `OptimisticLockException`。在单个 `@Transactional` 测试内完成，无需 `TransactionTemplate`。
- **`@BeforeEach` 清理**：由于 `@DataJpaTest` 每个测试方法都在事务中并回滚，大多数测试不需要手动清理。但 `setUp()` 中的 DELETE 语句确保在回滚机制失效时仍有测试隔离保障。
- **Docker 依赖**：Testcontainers 需要 Docker Desktop 运行。当前环境使用 Docker Desktop 28.5.1 + WSL2。

## Q&A：这些测试到底是怎么跑起来的？

### Q1: 测试里用了 `@Autowired`，Spring 上下文是哪来的？

测试类本身 **没有** 启动完整的 Spring Boot 应用。关键在注解链的层层展开：

```
JpaTaskRepositoryIntegrationTest
  extends AbstractPostgresIntegrationTest          ← 继承注解
    @DataJpaTest                                    ← 触发 Spring Test Slice
```

`@DataJpaTest` 是 Spring Boot 提供的 **测试切片**（Test Slice），它做了三件事：

1. **寻找 `@SpringBootConfiguration`** — 从测试类所在包向上扫描，找到了 `TestJpaConfig`（标注了 `@SpringBootApplication`，其元注解包含 `@SpringBootConfiguration`）。
2. **启动精简的 Spring 上下文** — 只注册 JPA 相关的自动配置（`DataSource`、`EntityManagerFactory`、`TransactionManager`、Spring Data JPA repositories），**不会** 加载 `@Controller`、`@Service`、Spring AI 等无关组件。
3. **开启 `@Autowired` 注入** — Spring TestContext Framework 的 `SpringExtension`（JUnit 5）在测试实例创建后执行依赖注入。

那 `@Autowired JpaTaskRepository` 是怎么注入的？`JpaTaskRepository` 是我们写的 adapter 类（标注 `@Repository`），但 `@DataJpaTest` 默认只自动注册 Spring Data JPA 接口，不扫描普通的 `@Repository` bean。所以测试类上的 `@Import(JpaTaskRepository.class)` 显式告诉 Spring 把这个 bean 注册进来：

```
@DataJpaTest                          → 自动注册 TaskJpaRepository (Spring Data 接口)
@Import(JpaTaskRepository.class)      → 手动注册 JpaTaskRepository (我们的 adapter)
@Autowired JpaTaskRepository          → Spring 注入: new JpaTaskRepository(taskJpaRepository)
```

### Q2: PostgreSQL 容器是在哪里启动的？

容器启动由 **两个注解** 协作完成，一个管生命周期，一个管连接：

```java
// AbstractPostgresIntegrationTest.java
@Testcontainers                           // ← JUnit 5 扩展，管理 @Container 生命周期
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
public abstract class AbstractPostgresIntegrationTest {

    @Container                             // ← 标记这个字段需要生命周期管理
    @ServiceConnection                     // ← 告诉 Spring Boot 从这里提取连接信息
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");
}
```

完整启动时序：

```
1. JUnit 5 发现测试类继承了 AbstractPostgresIntegrationTest
2. @Testcontainers (= @ExtendWith(TestcontainersExtension.class)) 注册 JUnit 扩展
3. TestcontainersExtension 在 @BeforeAll 阶段扫描 @Container 字段
4. 发现 static 字段 postgres → 调用 postgres.start()
5. Testcontainers 库通过 Docker API:
   a. docker pull postgres:16-alpine     （首次拉取，后续用本地缓存）
   b. docker run -d -p <随机端口>:5432 postgres:16-alpine
   c. 等待 PostgreSQL 就绪              （内置 JDBC readiness probe 反复尝试连接）
6. 容器就绪后，@ServiceConnection 生效:
   Spring Boot 的 ServiceConnectionContextCustomizer 读取容器的
   host、mappedPort(5432)、username、password、databaseName，
   自动设置 spring.datasource.url/username/password
7. Spring 上下文启动，DataSource 连接到 Testcontainers 的 PostgreSQL
```

因为字段是 `static`，容器在整个测试类生命周期内只启动一次。又因为 `@DataJpaTest` 的上下文缓存机制，两个测试类（Task 和 Execution）实际 **共享同一个 Spring ApplicationContext 和同一个容器** — Spring 发现注解组合相同（都继承自同一个基类），所以复用了上下文。

### Q3: 表是怎么自动建出来的？Docker 里的 PostgreSQL 初始是空库啊

Testcontainers 启动的 PostgreSQL 确实是空库（只有默认的 `test` 数据库，零表）。建表由 **Hibernate 的 `ddl-auto=create-drop`** 完成，不走 Flyway。

完整流程：

```
容器启动 → 空的 PostgreSQL（只有 test 数据库，无任何表）
       ↓
Spring 上下文启动
       ↓
EntityManagerFactory 初始化
       ↓
Hibernate 扫描 classpath 上所有 @Entity 类:
  ┌─ TaskEntity          → CREATE TABLE task (id UUID PK, description TEXT, status VARCHAR(20), version BIGINT, ...)
  ├─ ExecutionEntity      → CREATE TABLE execution (id UUID PK, task_id UUID, status VARCHAR(20), version BIGINT, ...)
  ├─ ExecutionStepEntity  → CREATE TABLE execution_step (id UUID PK, execution_id UUID, step_order INT, ...)
  └─ StepLogEntity        → CREATE TABLE step_log (id UUID PK, step_id UUID, type VARCHAR(20), ...)
       ↓
Hibernate 向 PostgreSQL 发送 CREATE TABLE DDL（含外键约束、@Version 列等）
       ↓
测试方法执行（每个方法在 @Transactional 内，方法结束自动回滚）
       ↓
所有测试跑完 → EntityManagerFactory 关闭 → Hibernate 发送 DROP TABLE DDL
       ↓
容器关闭 → docker stop + docker rm（整个数据库消失）
```

控制这个行为的配置在 `src/test/resources/application.yml`：

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: create-drop    # Hibernate 启动时建表，关闭时删表
  flyway:
    enabled: false             # 不走 Flyway 迁移
```

**为什么不用 Flyway？** 两个原因：
1. Flyway 的迁移脚本在 `echoflow-web` 模块的 classpath 上，infrastructure 模块的测试看不到它们。
2. V1 迁移需要 `CREATE EXTENSION vector`（pgvector 扩展），标准 `postgres:16-alpine` 镜像没有安装这个扩展，会直接报错。

用 `create-drop` 完全够用 — 我们测的是 Domain ↔ JPA Entity 的映射逻辑，不是迁移脚本本身是否正确。

## 测试统计

| 层 | 测试数 | 变化 |
|---|---|---|
| Domain | 39 | 不变 |
| Application | 11 | 不变 |
| Infrastructure (unit) | 20 | 不变 |
| Infrastructure (integration) | 23 | +23（新增） |
| **合计** | **93** | **+23** |

## 文件清单

| 操作 | 文件 |
|------|------|
| 新建 | `echoflow-infrastructure/src/test/resources/application.yml` |
| 新建 | `echoflow-infrastructure/src/test/java/.../persistence/TestJpaConfig.java` |
| 新建 | `echoflow-infrastructure/src/test/java/.../persistence/AbstractPostgresIntegrationTest.java` |
| 新建 | `echoflow-infrastructure/src/test/java/.../persistence/task/JpaTaskRepositoryIntegrationTest.java` |
| 新建 | `echoflow-infrastructure/src/test/java/.../persistence/execution/JpaExecutionRepositoryIntegrationTest.java` |
| 新建 | `docs/plans/2026-03-10-2020-p3-testcontainers-integration-tests.md` |
| 新建 | `docs/devlog/010-testcontainers-integration-tests.md` |

## 下一步

- 可选：Application 层集成测试（使用 `@SpringBootTest` 测试完整用例流程）
- 可选：增加更多 Tool（代码搜索、Web 搜索等）
- 可选：限制单次步骤的最大 tool 调用次数
- 可选：Webhook 平台适配（Slack/Lark 特化 payload）
