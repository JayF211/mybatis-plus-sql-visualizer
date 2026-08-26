---
name: project-settings-no-nested-configurable
description: 设置页不再注册 projectConfigurable——移除嵌套的"MyBatis-Plus SQL
  Visualizer"，保留后端服务供 SqlPreviewService 读取项目级覆盖
metadata:
  node_type: memory
  type: project
  originSessionId: sess_6e264e53-f910-4ea0-a8c4-9500f6e4f5eb
---

2026-08-26：用户发现 Settings → Tools → MyBatis-Plus SQL Visualizer 下嵌套了一个同名的子项（"套娃"），要求移除外层嵌套。同时反馈输入框/下拉框宽度过长。

**嵌套问题根因**：`plugin.xml` 中 `projectConfigurable` 的 `parentId` 指向了 `applicationConfigurable` 的 ID，且两者 `key` 相同（`configurable.settings.displayName`），导致设置树中出现嵌套的同名节点。处理：直接移除 `projectConfigurable` 的 XML 注册。`ProjectPluginSettings` 后端服务和 `SqlPreviewService` 的项目级覆盖逻辑不受影响。

**输入框宽度修复**：`PluginSettingsPanel.addRow` 中组件列 `fill` 从 `HORIZONTAL` 改为 `NONE`（`weightx=1.0` 保持列宽分配但组件不再拉伸）；`ProjectPluginSettingsConfigurable` 方言面板同步修复。

**Why:** 同名嵌套造成困惑，项目级覆盖使用频率极低；输入框拉伸到全宽不美观。
**How to apply:** 若未来需要恢复项目级设置 UI，重新在 plugin.xml 中注册 `projectConfigurable`，但应考虑用不同的 displayName（如 "MyBatis-Plus SQL Visualizer (Project)"）或改 parentId 为 "tools" 使其成为同级节点而非嵌套。新增输入框/下拉框行时保持 `fill=NONE` 避免拉伸。