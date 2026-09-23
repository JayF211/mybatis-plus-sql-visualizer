---
name: pitfall-keymap-conflicts-never-resolve
description: 本仓「默认键位冲突自动改绑」是结构性死路——三个默认键与备选键全落在平台既有键位上；附四处同源缺陷与「默认键位该不该设」的产品口径
metadata:
  node_type: memory
  type: project
  originSessionId: sess_b5cde80a-ac48-46e7-884d-8f11955f33d5
---

2026-09-21 用户贴出真机通知：COUNT / Sample 两个动作报「快捷键冲突，自动改绑失败，请在 设置 > Keymap 手动调整」。逐平台实测键位表后确认：**这不是环境偶发，是结构性必然**——两个默认键与全部备选键都落在平台既有键位上。

| 键位 | 2024.3.7.1（最低支持版） | 2026.2.3（日常 IDE） |
|---|---|---|
| `Alt+Shift+C`（COUNT 默认） | RecentChanges（`lib/product.jar!META-INF/plugin.xml`） | RecentChanges（`intellij.idea.ultimate.customization.jar`） |
| `Alt+Shift+T`（Sample 默认） | tasks.switch（`plugins/tasks/lib/tasks-core.jar!META-INF/plugin.xml`） | 空闲（262 的 tasks 插件已摘键） |
| `Alt+Shift+E`（EXPLAIN 默认） | 空闲 | 空闲 |
| `Ctrl+Alt+Shift+C`（插件备选） | Copy Reference（`keymaps/$default.xml`） | Copy Reference |
| `Ctrl+Alt+Shift+T`（插件备选） | Refactor This（`$default.xml`） | Refactor This |

备选键被占 → `conflictOwner(fallback) != null` → `rebound=false` → 必然走 manual 文案（「自动改绑失败」）。**备选键位推导（`fallbackOf` 叠 Ctrl+Alt+Shift）从设计上就落回平台 `$default.xml` 的常用区，换绑机制注定失败。**

同源缺陷（均在 `actions/SqlTemplateShortcutConflictCheck.kt`，**未修，用户未拍板**）：
1. **KDoc 归因失真**：把失败原因写成「当前 scheme 不可写等环境差异」；平台源码实测 `addShortcut` 无 `canModify` 守卫、不抛异常（见 [[reference-platform-artifact-keymap-lookup]]）。用户按提示去 Keymap 手调仍会撞同一占用者。
2. **`addShortcut` 只加不改**：原冲突键位不摘除，换绑成功也只是多一个入口，冲突没被解决（KDoc 自述「只加不改」）。
3. **只检测新三动作**：同族两个老动作从来没查，且都撞车——`ConvertSqlToJava` 的 `Alt+Shift+J` 撞平台 `UnselectPreviousOccurrence`，`ConvertToSql` 的 `Alt+Shift+Q` 撞 webDeployment 的 `Upload All Open Files`（两个平台版本都占）。
4. **平台对同键多动作不做仲裁**：该键的 actionIds 全部进候选、执行顺序无保证 → 无选中时 `Alt+Shift+C` 归平台 Recent Changes、有选中时两者同时可用，**同一键在两种状态下归属不同**（三动作是条件启用的，无选中即禁用）。

产品口径（2026-09-21 会话结论）：三动作主入口是右键菜单（`EditorPopupMenu` 的「SQL 快捷模板」组），设置页另有独立「快捷键」节可录制绑定，**默认键位是糖不是能力**；COUNT(*) / LIMIT 10 这类模板手打比记快捷键快。**推荐三个动作都不设默认键位**，随之删掉 `SqlTemplateShortcutConflictCheck` + 测试类 + 中英各 3 条 `sqlTemplate.conflict.*` 文案（启动告警噪音一并消失）；保守方案只留空闲的 `Alt+Shift+E`。影响面：`plugin.xml` 三行 `keyboard-shortcut`、检测类与测试、双语 bundle 3 键、四处文档表述（CHANGELOG / `docs/变更记录.md` / `docs/MEMORY.md` / 第四轮调研报告与 T-PreviewUx-Pack 计划）；设置页 3 个 ShortcutEditor 保留不动。

相关：[[reference-platform-artifact-keymap-lookup]]（怎么查键位归属）、[[feedback-ui-match-existing]]（文案须与既有实现对齐）。
