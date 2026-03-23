# 前端 UI 重做 Phase 2: 排序 + 暗色模式 + 搜索筛选 + Sidebar 折叠 + 响应式

- 创建时间: 2026-03-24 12:30
- 完成时间: 进行中
- 状态: 进行中
- 关联 devlog: docs/devlog/029-frontend-ui-redesign-phase2.md

## 任务清单

### 1. 后端：任务列表按创建时间降序
- `TaskJpaRepository` 加 `findAllByOrderByCreatedAtDesc()`
- `JpaTaskRepository.findAll()` 改用新方法

### 2. 暗色模式切换
- `layout.tsx` 改为从 localStorage 读取 theme，设置 `<html class="dark">`
- 创建 `ThemeProvider` + `useTheme` hook
- Sidebar 底部加暗色模式切换按钮（Sun/Moon 图标）

### 3. 任务搜索筛选
- `TaskList` header 加搜索输入框
- 状态筛选标签（全部/执行中/已完成/失败）
- 前端过滤（不改后端）

### 4. Sidebar 折叠/展开动画
- 折叠态 ~60px（仅图标），展开态 ~200px
- 折叠按钮（ChevronLeft/Right）
- CSS transition 动画

### 5. 响应式移动端
- < 768px: 隐藏 Sidebar，TaskList 全宽，Detail 为覆盖层
- 768px-1024px: 折叠 Sidebar，TaskList + Detail 并排
- > 1024px: 完整三栏
