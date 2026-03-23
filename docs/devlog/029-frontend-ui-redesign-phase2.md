# 029 — 前端 UI 重做 Phase 2: 排序 + 暗色模式 + 搜索 + Sidebar 折叠 + 响应式

## Progress

- 后端任务列表按 `createdAt DESC` 排序（最新在最上）
- 暗色模式切换：`useSyncExternalStore` + localStorage 持久化 + Sidebar 切换按钮
- 任务搜索：TaskList header 搜索框，按描述文本前端过滤
- 状态筛选：全部 / 执行中 / 已完成 / 失败 标签切换
- Sidebar 折叠/展开：60px ↔ 200px，CSS transition 动画，图标模式
- 响应式适配：移动端隐藏 Sidebar，TaskList 全宽，Detail 覆盖层 + 返回按钮
- ESLint 零问题，TypeScript 编译通过

## DDD Decisions

- 后端排序改动仅涉及 Infrastructure 层的 JPA 查询方法，不影响 Domain 接口
- `TaskRepository.findAll()` 的返回顺序语义从"未定义"变为"按创建时间降序"

## Technical Notes

### 后端排序

`TaskJpaRepository` 新增 `findAllByOrderByCreatedAtDesc()` 方法名派生查询。`JpaTaskRepository.findAll()` 改用此方法。无需 `@Query` 注解，Spring Data JPA 自动解析。

### 暗色模式 useSyncExternalStore

React 19 的 `react-hooks/set-state-in-effect` 规则禁止在 effect 内同步 setState。改用 `useSyncExternalStore` 订阅 localStorage，避免 lint 报错同时保证 SSR 兼容（`getServerSnapshot` 返回 "light"）。

### Sidebar 折叠

用 `sidebar-wrapper.tsx` 替代原 `sidebar.tsx`，将折叠状态 + 暗色模式切换集成在一个组件中。折叠态 60px 仅显示图标，展开态 200px 显示图标 + 文字。`transition-[width] duration-200` 实现平滑过渡。

### 响应式

- `< md (768px)`: 隐藏 Sidebar，TaskList 全宽。选中任务时 Detail 覆盖整屏，顶部"返回列表"按钮。
- `>= md`: 三栏正常展示。
- `mobileShowDetail` 状态提升到 page.tsx，通过 props 传入 AppLayout。

## Next Steps

- 连接后端验证 SSE 端到端流式体验
- 考虑 Sidebar 移动端抽屉模式（当前直接隐藏）
