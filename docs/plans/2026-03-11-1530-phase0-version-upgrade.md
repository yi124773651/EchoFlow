# Phase 0: 版本升级 — Spring Boot 3.5 + Spring AI 1.1.2

> 创建时间: 2026-03-11 15:30 CST
> 完成时间: 2026-03-11 16:30 CST
> 状态: ✅ 已完成
> 关联 devlog: `docs/devlog/011-version-upgrade.md`

## 目标

升级 Spring Boot 3.4.4 → 3.5.8，Spring AI 1.0.0 → 1.1.2，引入 Spring AI Alibaba BOM，为 Phase 1（多模型路由）和 Phase 2（Agent Framework POC）扫清版本障碍。

## 改动范围

| 文件 | 操作 |
|------|------|
| `pom.xml` (根目录) | 升级版本 + 新增 BOM |

## 验收标准

- [x] `./mvnw clean install -pl echoflow-backend -am` 编译通过，70 个单元测试全 GREEN（23 个 Testcontainers 集成测试因无 Docker 未执行）
- [ ] `npm run build` 前端构建无报错（未执行，前端与版本升级无关）
- [x] `dependency:tree` 确认 Spring AI 1.1.2 生效
