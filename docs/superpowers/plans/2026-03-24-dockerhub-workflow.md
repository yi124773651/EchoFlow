# DockerHub Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Dockerfile + GitHub Actions workflow to build a single all-in-one Docker image and push to DockerHub on every push to main.

**Architecture:** Multi-stage Dockerfile (Node build → Maven build → JRE runtime). Frontend static export served by Spring Boot. GitHub Actions triggers on push to main, builds via Buildx, pushes `latest` + `sha-xxx` tags.

**Tech Stack:** Docker multi-stage, GitHub Actions, Next.js static export, Spring Boot, Maven

**Spec:** `docs/superpowers/specs/2026-03-24-dockerhub-workflow-design.md`

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `Dockerfile` | Multi-stage build: frontend → backend → runtime |
| Create | `.dockerignore` | Exclude .git, node_modules, target, docs, etc. |
| Create | `.github/workflows/docker-publish.yml` | Build & push on push to main |
| Modify | `echoflow-frontend/next.config.ts` | Conditional `output: 'export'` via `STATIC_EXPORT` env |

---

### Task 1: Modify next.config.ts for conditional static export

**Files:**
- Modify: `echoflow-frontend/next.config.ts`

- [ ] **Step 1: Update next.config.ts**

Replace the entire file with:

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

- [ ] **Step 2: Verify local dev still works**

Run from `echoflow-frontend/`:
```bash
npm run build
```
Expected: build succeeds without `output: 'export'` (STATIC_EXPORT not set), `rewrites` still active.

- [ ] **Step 3: Verify static export works**

```bash
STATIC_EXPORT=true npm run build
```
Expected: build succeeds, `out/` directory created with static HTML/CSS/JS files. Verify `out/index.html` exists.

- [ ] **Step 4: Commit**

```bash
git add echoflow-frontend/next.config.ts
git commit -m "feat: conditional static export in next.config.ts for Docker build"
```

---

### Task 2: Create .dockerignore

**Files:**
- Create: `.dockerignore`

- [ ] **Step 1: Create .dockerignore in project root**

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

- [ ] **Step 2: Commit**

```bash
git add .dockerignore
git commit -m "feat: add .dockerignore for Docker build"
```

---

### Task 3: Create multi-stage Dockerfile

**Files:**
- Create: `Dockerfile`

- [ ] **Step 1: Create Dockerfile in project root**

```dockerfile
# ---- Stage 1: Frontend build ----
FROM node:22-alpine AS frontend-build
WORKDIR /app/frontend
COPY echoflow-frontend/package.json echoflow-frontend/package-lock.json ./
RUN npm ci
COPY echoflow-frontend/ ./
ENV NEXT_PUBLIC_API_BASE=""
ENV STATIC_EXPORT="true"
RUN npm run build

# ---- Stage 2: Backend build ----
FROM eclipse-temurin:21-jdk-alpine AS backend-build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY echoflow-backend/pom.xml echoflow-backend/pom.xml
COPY echoflow-backend/echoflow-domain/pom.xml echoflow-backend/echoflow-domain/pom.xml
COPY echoflow-backend/echoflow-application/pom.xml echoflow-backend/echoflow-application/pom.xml
COPY echoflow-backend/echoflow-infrastructure/pom.xml echoflow-backend/echoflow-infrastructure/pom.xml
COPY echoflow-backend/echoflow-web/pom.xml echoflow-backend/echoflow-web/pom.xml
RUN chmod +x mvnw && ./mvnw dependency:go-offline -pl echoflow-backend/echoflow-web -am -pl !echoflow-frontend
COPY echoflow-backend/ echoflow-backend/
RUN ./mvnw clean package -pl echoflow-backend/echoflow-web -am -pl !echoflow-frontend -DskipTests \
    && mv echoflow-backend/echoflow-web/target/echoflow-web-*.jar /app/app.jar

# ---- Stage 3: Runtime image ----
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

- [ ] **Step 2: Local Docker build smoke test**

```bash
docker build -t echoflow:local .
```
Expected: build completes successfully through all 3 stages. Final image is based on `eclipse-temurin:21-jre-alpine`.

- [ ] **Step 3: Verify image runs (optional, requires DB)**

If a local PostgreSQL is available:
```bash
docker run --rm -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/echoflow \
  -e DB_USERNAME=echoflow \
  -e DB_PASSWORD=echoflow \
  -e AI_BASE_URL=http://host.docker.internal:11434 \
  -e AI_API_KEY=test \
  -e AI_MODEL=test \
  echoflow:local
```
Expected: Spring Boot starts on port 8080. Hitting `http://localhost:8080/` serves the frontend `index.html`. Hitting `http://localhost:8080/actuator/health` returns health status.

Skip this step if no local DB is configured — the CI build test in Step 2 is sufficient.

- [ ] **Step 4: Commit**

```bash
git add Dockerfile
git commit -m "feat: add multi-stage Dockerfile for all-in-one image"
```

---

### Task 4: Create GitHub Actions workflow

**Files:**
- Create: `.github/workflows/docker-publish.yml`

- [ ] **Step 1: Create workflow file**

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

- [ ] **Step 2: Validate YAML syntax**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/docker-publish.yml'))" && echo "YAML OK"
```
Expected: `YAML OK` (no parse errors).

If `python3` or `pyyaml` not available, use:
```bash
npx yaml-lint .github/workflows/docker-publish.yml
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/docker-publish.yml
git commit -m "ci: add GitHub Actions workflow for DockerHub push"
```

---

### Task 5: Final verification and cleanup

- [ ] **Step 1: Verify all files exist**

```bash
ls -la Dockerfile .dockerignore .github/workflows/docker-publish.yml echoflow-frontend/next.config.ts
```
Expected: all 4 files exist.

- [ ] **Step 2: Verify git status is clean**

```bash
git status
```
Expected: working tree clean, all changes committed.

- [ ] **Step 3: Review commit log**

```bash
git log --oneline -4
```
Expected: 4 commits in order:
1. `ci: add GitHub Actions workflow for DockerHub push`
2. `feat: add multi-stage Dockerfile for all-in-one image`
3. `feat: add .dockerignore for Docker build`
4. `feat: conditional static export in next.config.ts for Docker build`

---

## GitHub Setup Reminder (Manual)

After merging to main, ensure these GitHub repository secrets are configured:

| Secret | Value |
|--------|-------|
| `DOCKERHUB_USERNAME` | Your DockerHub username |
| `DOCKERHUB_TOKEN` | DockerHub Access Token (create at hub.docker.com → Account Settings → Security) |
