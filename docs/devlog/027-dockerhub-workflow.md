# 027 — DockerHub 推送 Workflow

## Progress

- 添加多阶段 Dockerfile（Node 前端构建 → Maven 后端构建 → JRE 运行镜像）
- 创建 GitHub Actions workflow，push to main 自动构建并推送到 DockerHub
- 前端 `next.config.ts` 条件化：`STATIC_EXPORT=true` 时启用 `output: 'export'`，本地 dev 保持 `rewrites` 不受影响
- 添加 `.dockerignore` 排除无关文件

## DDD Decisions

- 本次改动不涉及 Domain/Application/Infrastructure 层，仅新增 CI/CD 基础设施文件和前端构建配置调整
- Spring Boot 通过 `spring.web.resources.static-locations` 命令行参数 serve 前端静态资源，不需要后端代码变更

## Technical Notes

### 单镜像方案

前端所有组件均为 `'use client'`，数据全走客户端 fetch + SSE，本质是 SPA。选择 `output: 'export'` 静态导出 + Spring Boot serve，避免引入 Node 运行时，单进程单端口。

### Maven Reactor 排除

根 `pom.xml` 声明了 `echoflow-frontend` 模块（用于 `frontend-maven-plugin`）。Docker 构建中不需要此模块，用 `-pl !echoflow-frontend` 排除，避免 frontend-maven-plugin 因缺少 `package.json` 解析失败。

### Docker Layer 缓存优化

后端构建阶段先 COPY 所有 `pom.xml` 运行 `dependency:go-offline`，再 COPY 源码编译。源码变更不会重新下载 Maven 依赖。GitHub Actions 使用 `cache-from: type=gha` 进一步加速。

### rewrites 与 static export 不兼容

Next.js `output: 'export'` 禁止使用 `rewrites()`（构建会报错）。通过 `STATIC_EXPORT` 环境变量条件化解决，Docker 构建和本地开发两种模式互不影响。

## Next Steps

- 配置 GitHub Secrets（`DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`）后合并到 main 验证 workflow
- 后续可考虑添加 docker-compose 编排（含 PostgreSQL）方便本地部署
- 如前端增加子路由，需在 Spring Boot 添加 SPA fallback controller
