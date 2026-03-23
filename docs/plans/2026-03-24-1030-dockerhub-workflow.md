# DockerHub 推送 Workflow

- 创建时间: 2026-03-24 10:30
- 完成时间: 2026-03-24 11:00
- 状态: 已完成
- 关联 devlog: docs/devlog/027-dockerhub-workflow.md

## Context

为 EchoFlow 项目添加 Docker 构建和 DockerHub 推送的 CI 流水线。单镜像方案（前后端合一），前端静态导出由 Spring Boot serve。

## 决策记录

| 决策项 | 选择 | 理由 |
|--------|------|------|
| 镜像数量 | 单镜像（All-in-one） | 部署简单，一个进程一个端口 |
| 前端服务方式 | Spring Boot serve 静态资源 | 前端本质是 SPA，无 SSR 依赖，不需要 Node 运行时 |
| 触发策略 | push to main | PR 合并自动构建，免手动打 tag |
| 镜像 tag | `latest` + `sha-<short-sha>` | latest 方便拉取，sha tag 可追溯 |
| DockerHub 配置 | GitHub Secrets + 硬编码仓库名 | 只需配 2 个 secrets，仓库名固定为 echoflow |

## 构建流水线

```
push to main
  └─ GitHub Actions
       ├─ Checkout
       ├─ Docker Buildx setup
       ├─ DockerHub login (secrets)
       ├─ Docker build (multi-stage)
       │    ├─ Stage 1: Node 22 alpine → npm ci → next build (output: 'export') → out/
       │    ├─ Stage 2: Temurin JDK 21 alpine → mvnw package -DskipTests → fat JAR
       │    └─ Stage 3: Temurin JRE 21 alpine → JAR + static files → 最终镜像
       └─ Docker push → DockerHub (latest + sha-xxxxxx)
```

## 修改文件清单

| Action | File | 说明 |
|--------|------|------|
| Create | `Dockerfile` | 三阶段构建：Node 前端 → Maven 后端 → JRE 运行 |
| Create | `.dockerignore` | 排除 .git, node_modules, target, docs 等 |
| Create | `.github/workflows/docker-publish.yml` | push to main 触发构建推送 |
| Modify | `echoflow-frontend/next.config.ts` | `STATIC_EXPORT` 环境变量条件化 `output: 'export'` |

## Dockerfile 关键设计

- **非 root 用户**: `echoflow` 用户运行 Java 进程
- **Maven 依赖分层缓存**: 先 COPY pom.xml 运行 `dependency:go-offline`，再 COPY 源码编译
- **`-pl !echoflow-frontend`**: 从 Maven reactor 中排除前端模块
- **JAR rename**: `mv` 将 fat JAR 重命名为固定路径，避免 glob 匹配多文件
- **`NEXT_PUBLIC_API_BASE=""`**: 前端请求走同源 `/api`

## GitHub 配置项

| Secret | 说明 |
|--------|------|
| `DOCKERHUB_USERNAME` | DockerHub 用户名 |
| `DOCKERHUB_TOKEN` | DockerHub Access Token |

## next.config.ts 条件化

`rewrites()` 与 `output: 'export'` 不兼容。用 `STATIC_EXPORT` 环境变量条件化：Docker 构建时设 `STATIC_EXPORT=true` 启用静态导出，本地 dev 不设此变量保持 `rewrites` 生效。

## 不在范围内

- docker-compose 编排（含 PostgreSQL）
- CI 测试 job（应独立 workflow）
- 多架构构建（linux/amd64 + linux/arm64）
- SPA 路由 fallback（当前仅根路径，无需）
