# DockerHub 推送 Workflow 设计

## 元信息

- 创建时间: 2026-03-24 (CST)
- 完成时间: 进行中
- 状态: 进行中
- 关联 devlog: TBD

---

## 1. 目标

为 EchoFlow 项目设计 GitHub Actions workflow，在 PR 合并到 main 分支时自动构建单镜像（前后端合一）并推送到 DockerHub。

## 2. 决策记录

| 决策项 | 选择 | 理由 |
|--------|------|------|
| 镜像数量 | 单镜像（All-in-one） | 部署简单，一个进程一个端口 |
| 前端服务方式 | Spring Boot serve 静态资源 | 前端本质是 SPA，无 SSR 依赖，不需要 Node 运行时 |
| 触发策略 | push to main | PR 合并自动构建，免手动打 tag |
| 镜像 tag | `latest` + `sha-<short-sha>` | latest 方便拉取，sha tag 可追溯 |
| DockerHub 配置 | GitHub Secrets + 硬编码仓库名 | 只需配 2 个 secrets，仓库名固定为 echoflow |

## 3. 架构

### 3.1 构建流水线

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

### 3.2 多阶段 Dockerfile

```dockerfile
# ---- Stage 1: 前端构建 ----
FROM node:22-alpine AS frontend-build
WORKDIR /app/frontend
COPY echoflow-frontend/package.json echoflow-frontend/package-lock.json ./
RUN npm ci
COPY echoflow-frontend/ ./
ENV NEXT_PUBLIC_API_BASE=""
ENV STATIC_EXPORT="true"
RUN npm run build
# output: 'export' 产出静态文件到 out/

# ---- Stage 2: 后端构建 ----
FROM eclipse-temurin:21-jdk-alpine AS backend-build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# 分层: 先复制 pom 解析依赖，再复制源码（利用 Docker layer cache）
COPY echoflow-backend/pom.xml echoflow-backend/pom.xml
COPY echoflow-backend/echoflow-domain/pom.xml echoflow-backend/echoflow-domain/pom.xml
COPY echoflow-backend/echoflow-application/pom.xml echoflow-backend/echoflow-application/pom.xml
COPY echoflow-backend/echoflow-infrastructure/pom.xml echoflow-backend/echoflow-infrastructure/pom.xml
COPY echoflow-backend/echoflow-web/pom.xml echoflow-backend/echoflow-web/pom.xml
RUN chmod +x mvnw && ./mvnw dependency:go-offline -pl echoflow-backend/echoflow-web -am -pl !echoflow-frontend
COPY echoflow-backend/ echoflow-backend/
RUN ./mvnw clean package -pl echoflow-backend/echoflow-web -am -pl !echoflow-frontend -DskipTests \
    && mv echoflow-backend/echoflow-web/target/echoflow-web-*.jar /app/app.jar

# ---- Stage 3: 运行镜像 ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S echoflow && adduser -S echoflow -G echoflow
WORKDIR /app
COPY --from=backend-build /app/app.jar app.jar
COPY --from=frontend-build /app/frontend/out/ /app/static/
RUN chown -R echoflow:echoflow /app
USER echoflow
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.web.resources.static-locations=file:/app/static/"]
```

关键设计点:
- **非 root 用户**: 创建 `echoflow` 用户运行 Java 进程
- **Maven 依赖分层缓存**: 先 COPY pom.xml 运行 `dependency:go-offline`，再 COPY 源码编译。源码变更不会重新下载依赖。
- **`-pl !echoflow-frontend`**: 从 Maven reactor 中排除前端模块，避免 frontend-maven-plugin 解析失败
- **JAR rename**: 构建阶段用 `mv` 将 fat JAR 重命名为固定路径 `/app/app.jar`，避免 glob 匹配多个文件
- **NEXT_PUBLIC_API_BASE=""**: 前端请求走同源 `/api`，Spring Boot 同端口提供 API
- **-DskipTests**: CI 构建跳过测试（测试应在独立 CI job 中完成）

### 3.3 GitHub Actions Workflow

文件: `.github/workflows/docker-publish.yml`

```yaml
name: Build & Push Docker Image

on:
  push:
    branches: [main]

permissions:
  contents: read

env:
  IMAGE_NAME: ${{ secrets.DOCKERHUB_USERNAME }}/echoflow

jobs:
  build-and-push:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Login to DockerHub
        uses: docker/login-action@v3
        with:
          username: ${{ secrets.DOCKERHUB_USERNAME }}
          password: ${{ secrets.DOCKERHUB_TOKEN }}

      - name: Extract short SHA
        id: sha
        run: echo "short=$(git rev-parse --short HEAD)" >> "$GITHUB_OUTPUT"

      - name: Build and push
        uses: docker/build-push-action@v6
        with:
          context: .
          push: true
          tags: |
            ${{ env.IMAGE_NAME }}:latest
            ${{ env.IMAGE_NAME }}:sha-${{ steps.sha.outputs.short }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
```

关键设计点:
- **docker/build-push-action@v6**: 官方 action，支持 Buildx
- **GHA cache**: `cache-from/cache-to` 使用 GitHub Actions cache backend，加速后续构建
- **IMAGE_NAME**: 由 `secrets.DOCKERHUB_USERNAME` + 硬编码仓库名 `echoflow` 拼接，无需额外 Variable

### 3.4 GitHub 配置项

| 类型 | 名称 | 说明 | 示例 |
|------|------|------|------|
| Secret | `DOCKERHUB_USERNAME` | DockerHub 用户名 | `myuser` |
| Secret | `DOCKERHUB_TOKEN` | DockerHub Access Token | `dckr_pat_xxx` |

镜像仓库名硬编码为 `<username>/echoflow`，由 workflow 中 `${{ secrets.DOCKERHUB_USERNAME }}/echoflow` 拼接。

## 4. 代码改动

### 4.1 新增文件

| 文件 | 说明 |
|------|------|
| `Dockerfile` | 项目根目录，多阶段构建 |
| `.dockerignore` | 排除不需要的文件 |
| `.github/workflows/docker-publish.yml` | GitHub Actions workflow |

### 4.2 修改文件

| 文件 | 改动 |
|------|------|
| `echoflow-frontend/next.config.ts` | 增加 `output: 'export'`，条件化 `rewrites()`（仅本地 dev 生效） |

### 4.3 next.config.ts 改动

`rewrites()` 与 `output: 'export'` 不兼容（Next.js 会报错）。使用环境变量条件化：

```ts
import type { NextConfig } from "next";

const isStaticExport = process.env.STATIC_EXPORT === "true";

const nextConfig: NextConfig = {
  ...(isStaticExport && { output: "export" }),
  ...(!isStaticExport && {
    async rewrites() {
      return [
        {
          source: "/api/:path*",
          destination: `${process.env.BACKEND_URL ?? "http://localhost:8080"}/api/:path*`,
        },
      ];
    },
  }),
};

export default nextConfig;
```

Dockerfile Stage 1 中增加 `ENV STATIC_EXPORT="true"` 触发静态导出。本地 `npm run dev` 不设此变量，`rewrites` 正常生效。

### 4.4 .dockerignore

```
.git
.github
.claude
.env
*.md
docs/
echoflow-frontend/node_modules/
echoflow-frontend/.next/
echoflow-frontend/out/
target/
**/target/
```

## 5. 运行时行为

- Spring Boot 监听 `8080` 端口
- `/api/*` 请求由 Spring MVC Controller 处理
- 其他请求 fallback 到 `/app/static/` 目录的静态文件
- 环境变量 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `AI_BASE_URL`, `AI_API_KEY`, `AI_MODEL` 通过 `docker run -e` 或 docker-compose 传入

## 6. SPA 路由 Fallback (未来)

当前前端只有根路径 `/`，无需 fallback。若将来增加子路由（如 `/tasks/:id`），需在 Spring Boot 增加一个简单的 fallback controller 将非 API、非静态资源的请求转发到 `index.html`。**当前不实现。**

## 7. 不在范围内

- 前端 Cloudflare Pages 部署（可后续独立做）
- docker-compose 编排（含 PostgreSQL）
- CI 测试 job（应独立 workflow）
- 多架构构建（linux/amd64 + linux/arm64）
- GitHub Container Registry (GHCR) 推送
