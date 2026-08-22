---
name: pitfall-settings-persist-inplace
description: PluginSettingsPanel.apply() 原地修改 state 属性导致 IntelliJ XmlSerializer 不触发 XML 持久化
metadata:
  node_type: memory
  type: project
  originSessionId: sess_ae46e3ae-998d-4c94-8f7d-ef4de5a7bd45
---

**问题**：用户在设置页勾选 checkbox 后点 Apply，`panel.apply()` 原地修改 `settings.state` 属性（如 `s.injectTenantLine = true`），但 IntelliJ `XmlSerializer` 检测不到对象引用变化，不触发 XML 写入 → 重启后设置丢失。

**实证**：沙盒 `mp-sql-visualizer.xml` 中只有 `logAutoCaptureOnStart=true`，缺少 `injectTenantLine=true`（2026-08-20 用户反馈"开启租户但不拼接"时发现）。

**根因**：`PersistentStateComponent.getState()` 返回同一对象引用时，框架的 `XmlSerializer` 只在引用替换（`loadState` 调用）时重新序列化。原地修改属性不替换引用 → 序列化缓存命中旧值 → 不写入 XML。

**修复**：`panel.apply()` 末尾加 `settings.loadState(s.copy())`——用 `data class copy()` 创建新实例替换引用，强制 `XmlSerializer` 检测到"状态变更"并持久化。

**如何应用**：所有通过 `PersistentStateComponent` 持久化、且在外部（非 `loadState` 路径）修改 `state` 属性的场景，修改后都应调用 `settings.loadState(state.copy())` 替换引用。不要依赖"框架自动检测属性级变化"。
