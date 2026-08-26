---
name: project-settings-layout-optimization
description: 设置页布局优化（Phase 1-3 已完成）——折叠面板 + SQL 日志 Tab 拆分 + 搜索过滤
metadata:
  node_type: memory
  type: project
  originSessionId: sess_6e264e53-f910-4ea0-a8c4-9500f6e4f5eb
---

2026-08-26：设置页布局优化，分三阶段实施。

**Phase 1 结构重组**：
- 6 个 `TitledSeparator` 组 → 5 个可折叠面板（`collapsiblePanel` 局部函数，toggle ▶/▼）
- 代码生成 2 行合并到 SQL 预览组（不值得独立成组）
- SQL 日志 17 行拆为 3 个 `JTabbedPane` Tab（MyBatis 核心 / ORM 框架 / 自动保存）
- 底部工具区归入「关于」折叠面板
- 折叠状态持久化到 `PluginSettings.State.collapsedGroups: MutableSet<String>`

**Phase 2 视觉对齐**：
- 标签列等宽（`GridBagLayout.columnWidths` 按内容计算 maxLabelWidth + 8px padding）
- SQL 日志 Tab 内标签列统一等宽
- 折叠面板标题使用粗体字体（最初加了 `customLine` 灰色边框，后因割裂感强移除，commit 15d118d）

**Phase 3 搜索过滤**：
- 顶部搜索框（`JBTextField` + 🔍 图标），`DocumentListener` 实时触发 `filterRows`
- `filterRows`：空查询恢复全部 + 恢复折叠状态；非空查询按标签文本 + 组件 tooltip 匹配，隐藏不匹配行，自动展开含匹配行的面板
- `scanRows` 递归扫描 `Container`（支持 `JTabbedPane` 嵌套）
- `collapsedGroups` 在自动展开时同步更新（保持持久化一致）

**Why:** 单页 44 行平铺滚动距离长，SQL 日志组过大无二级分组，搜索无法定位。优化后默认可见约 14 行，其余折叠按需展开。
**How to apply:** `rebuild()` 会重新调用 `buildSections`，需先 `collapsiblePanelRefs.clear()` 避免重复注册。新增配置项应归入对应折叠面板，高频项放 SQL 预览/UI，低频项放关于。