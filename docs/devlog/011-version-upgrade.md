# 011 — Phase 0: 版本升级

## Progress

- Spring Boot 3.4.4 → 3.5.8 升级完成
- Spring AI 1.0.0 → 1.1.2 升级完成
- Spring AI Alibaba Extensions BOM 1.1.2.2 引入（仅版本管理，未引入 starter）
- 全部 6 个模块编译通过
- 70 个单元测试全部 GREEN（Domain 39 + Application 11 + Infrastructure 20）
- Testcontainers 集成测试因当前环境无 Docker 未执行（23 个，非版本问题）
- CLAUDE.md 同步更新：§3 版本号、§4 类名修正、§15 devlog 路径

## DDD Decisions

- 无架构变更。所有改动限于 root `pom.xml` 版本号和 BOM 声明
- Domain 层未引入任何新依赖（保持纯 Java）
- Infrastructure 层的 Spring AI API 调用（ChatClient / @Tool / @ToolParam）在 1.1.2 下完全兼容，零代码改动

## Technical Notes

### Spring Boot 3.4 → 3.5 兼容性

已确认无影响的变更点：
- `spring.threads.virtual.enabled: true` — 合规（3.5 要求 `.enabled` 值严格为 true/false）
- `spring-boot-starter-parent` — 继续使用（3.5 移除的是 `spring-boot-parent`，非 starter-parent）
- Profile 名称校验 — 项目未使用自定义 profile
- `taskExecutor` bean 移除 — 项目未依赖该 bean 名称

### Spring AI 1.0 → 1.1 兼容性

已确认无影响的变更点：
- ChatClient fluent API（`.prompt().user().call().content()`）— 无 breaking changes
- `@Tool` / `@ToolParam` 注解 — 无 breaking changes
- `.tools()` 方法 — 无 breaking changes
- `ChatClient.Builder` 自动配置 — 无 breaking changes
- TTS API 变更 — 项目未使用
- MCP SDK 变更 — 项目未使用

### 依赖版本对比

| 依赖 | 升级前 | 升级后 |
|------|--------|--------|
| Spring Boot | 3.4.4 | 3.5.8 |
| Spring Framework | 6.2.x | 6.2.14 |
| Spring AI | 1.0.0 | 1.1.2 |
| Spring AI Alibaba BOM | — | 1.1.2.2 (仅版本管理) |
| Hibernate | 6.6.x | 6.7.x (由 Boot BOM 管理) |
| JUnit | 5.11.x | 5.12.2 |
| Mockito | 5.14.x | 5.17.0 |
| Testcontainers | 1.20.x | 1.21.3 |

### 改动文件

| 文件 | 改动 |
|------|------|
| `pom.xml` | Spring Boot 3.5.8 + Spring AI 1.1.2 + Alibaba BOM |
| `CLAUDE.md` | §3 版本号、§4 类名修正、§15 devlog 路径 |

## Next Steps

- Phase 1: 多模型路由层（ModelRouterPort + 多 Provider 支持）
- Phase 2: Agent Framework POC（独立分支验证）
