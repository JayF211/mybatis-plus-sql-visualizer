---
name: t-shortcut-settings
description: T-ShortcutSettings 快捷键统一设置与 MCP 独立子页已交付（aed3b07 + 38566f4 + fe6f859）——5
  个插件快捷键收归独立设置节 + MCP 工具独立子设置页位列关于上方且主目录页可见
metadata:
  node_type: memory
  type: project
  originSessionId: sess_4b4e37d2-b3af-49ad-952c-fcb8b5ed69e2
---

T-ShortcutSettings（2026-09-15，aed3b07 主体 + 38566f4 修正 + fe6f859 完善，基于第四轮七任务交付后的 dev）。用户反馈驱动：①快捷键冲突提示中找不到修改入口；②MCP 已交付但插件设置页无入口；③MCP 应在关于上方且主目录页可见；④文案「IDEA MCP Server 设置【点击前往】」。

**已交付**：
- 独立「快捷键」设置节（`SECTION_SHORTCUTS` + `ShortcutsConfigurable` + plugin.xml `groupWeight=45` 位于 ui 与 about 之间）
- 5 个插件快捷键统一管理：ConvertToSql / ConvertSqlToJava（既有，从 UI 节迁入）+ Explain / Count / Sample（新增，复用 `PluginSettingsPanel.ShortcutEditor` 内部类）
- `SelectedTableSqlTemplateActions.kt` 三个子类加 `ACTION_ID` 常量
- MCP 工具独立子设置页 `McpConfigurable.kt`：`groupWeight=15`（位列关于上方）；加入 `PluginSettingsFolderConfigurable.SUB_PAGES`（主设置目录搜索列表可见）；标签「IDEA MCP Server 设置」+ 按钮「点击前往」（`settings.mcp.label` 与 `settings.mcp.open` 拆分）
- 启动冲突检测（`SqlTemplateShortcutConflictCheck`）缩减：新增 `DEFAULT_KEYSTROKES` 常量（Alt+Shift+E/C/T），仅当当前键位==默认值才换绑，用户已配置的不覆盖

**MCP 平台 Configurable 类名（262 mcpserver.jar 常量池 javap 实证）**：
- 正确 = `com.intellij.mcpserver.settings.McpServerSettingsConfigurable`（注意：包名全小写 `mcpserver`，子包 `.settings.`）
- 错误 = `com.intellij.mcpServer.McpServerConfigurable`（首字母大写 + 无 .settings → ClassNotFoundException）
- 平台 MCP 设置相关类均在 `com.intellij.mcpserver.settings.*` 包下（含 `McpServerSettingsConfigurable` / `McpToolFilterConfigurable` / `McpToolDisallowListSettings` / `McpToolFilterSettings`）

**编译踩坑沉淀**（全局适用）：
- `Class.forName("...")` 返回 `Class<*>`，传 `ShowSettingsUtil.showSettingsDialog` 需强转 `Class<out com.intellij.openapi.options.Configurable>`
- `runCatching { showSettingsDialog(...) }` 泛型方法返回类型推断失败 → 显式 `runCatching<Unit> { ... }`
- `FormBuilder.addSeparator()` 不接受字符串参数（无 String 重载）→ 改用 `JBLabel` 做分组标题 或 `addSeparator()` 空参
- `JBTextField` 构造参数是列数（Int）不是初始文本

**架构要点**：插件快捷键两路管理收敛为一路——设置页 ShortcutEditor 为主、启动冲突检测为安全网（仅兜底未配置项）。MCP 工具本身是平台 EP 注册（mcpServer.mcpToolset），不在插件 SettingsState 内管理，跳转是唯一入口；已提升为独立子设置页（树位置 groupWeight 高于 about、主目录 SUB_PAGES 列表可见）。