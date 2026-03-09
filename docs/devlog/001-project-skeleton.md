# 开发日志 #1 — 项目骨架初始化

日期: 2026-03-10

## 概述

完成 EchoFlow 全栈项目骨架搭建，包含后端 Maven 多模块和前端 Next.js 项目。

## 后端

### 技术栈

| 项 | 版本 |
|---|---|
| Java | 21 (Virtual Threads 已启用) |
| Spring Boot | 3.4.4 |
| Maven Wrapper | 3.9.9 |
| 数据库 | PostgreSQL 16+ / pgvector |
| Schema 管理 | Flyway |

### 模块结构

```
echoflow-backend/
├── echoflow-domain          纯 Java，零框架依赖
├── echoflow-application     用例编排 + 事务边界 (spring-tx)
├── echoflow-infrastructure  适配器 (JPA, Flyway, Testcontainers)
└── echoflow-web             Spring Boot 主应用 (端口 8080)
```

### 依赖方向

```
web → application → domain ← infrastructure
```

Domain 层保持纯 Java：无 Spring、无 JPA、无 HTTP、无 AI SDK。

### 已创建的关键文件

- `DomainException` / `EntityNotFoundException` — 领域异常基类
- `EchoFlowApplication` — Spring Boot 启动类
- `GlobalExceptionHandler` — 统一异常处理 (RFC 9457 ProblemDetail)
- `application.yml` — 配置 (8080 端口, PostgreSQL, Flyway, Virtual Threads)
- `V1__init.sql` — Flyway 初始迁移 (启用 pgvector 扩展)
- `prompts/` — AI prompt 模板预留目录

## 前端

### 技术栈

| 项 | 版本 |
|---|---|
| Next.js | 16 (App Router + Turbopack) |
| React | 19 |
| TypeScript | strict 模式 |
| Tailwind CSS | v4 |
| ShadcnUI | v4 |

### 目录结构

```
echoflow-frontend/src/
├── app/            Next.js App Router 页面
├── components/ui/  ShadcnUI 基础组件
├── features/       业务功能模块
├── hooks/          可复用 client hooks
├── lib/            工具函数 (cn 等)
├── services/       集中 API 访问层 (api.ts)
└── types/          共享类型定义
```

### 端口对齐

- 前端: `localhost:3000`
- 后端: `localhost:8080`
- `next.config.ts` 已配置 rewrite: `/api/*` → `http://localhost:8080/api/*`

## Maven 构建

前端通过 `frontend-maven-plugin` 集成到 Maven 构建，支持 `./mvnw compile` 一键编译全栈。

## 下一步

- 定义第一个业务领域模型
- 添加数据库表迁移脚本
- 实现首个 API 端点
- 接入 Spring AI Alibaba
