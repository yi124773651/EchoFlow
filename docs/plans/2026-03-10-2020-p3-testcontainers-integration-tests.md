# 实施计划：P3 — Infrastructure 集成测试（Testcontainers）

- 创建时间: 2026-03-10 20:20
- 完成时间: 2026-03-10 20:28
- 状态: ✅ 已完成
- 关联 devlog: [010-testcontainers-integration-tests](../devlog/010-testcontainers-integration-tests.md)

## Context

当前项目有 70 个测试全部是单元测试，Infrastructure 层的 20 个测试都在 `ai/` 包下。持久层（`JpaTaskRepository`、`JpaExecutionRepository`）包含非平凡的映射逻辑和 `@Version` 乐观锁，但零集成测试。Testcontainers 依赖已在 pom.xml 中声明但未使用。本次实施为这两个 Repository Adapter 添加真实 PostgreSQL 集成测试。

## 架构决策

1. **Schema 来源**: `ddl-auto=create-drop`（不用 Flyway），因为 Flyway 迁移在 web 模块中
2. **Docker 镜像**: `postgres:16-alpine`（当前实体未使用 vector 列）
3. **Test Slice**: `@DataJpaTest`（非 `@SpringBootTest`），聚焦持久层
4. **容器复用**: 单例静态容器 + `@ServiceConnection` 自动配置数据源
5. **乐观锁测试**: 在 JPA 实体层面使用 `TestEntityManager.detach()` + `merge()` + `flush()` 模拟并发冲突

## 变更清单

| 操作 | 文件 |
|------|------|
| 新建 | `echoflow-infrastructure/src/test/resources/application.yml` |
| 新建 | `echoflow-infrastructure/src/test/java/.../persistence/TestJpaConfig.java` |
| 新建 | `echoflow-infrastructure/src/test/java/.../persistence/AbstractPostgresIntegrationTest.java` |
| 新建 | `echoflow-infrastructure/src/test/java/.../persistence/task/JpaTaskRepositoryIntegrationTest.java` |
| 新建 | `echoflow-infrastructure/src/test/java/.../persistence/execution/JpaExecutionRepositoryIntegrationTest.java` |

## 测试结果

93 个测试全部通过（Domain 39 + Application 11 + Infrastructure 43）。
