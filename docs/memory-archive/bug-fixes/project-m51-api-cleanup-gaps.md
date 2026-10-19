---
name: project-m51-api-cleanup-gaps
description: T-M51 废弃API清理已实施完成（2026-08-19），双版本 0 Internal API，0 Scheduled-removal
  （ProjectListenerRegistrar 可删除以彻底消除最后一个 scheduled-for-removal）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_c219eaa6-0399-451c-bb66-5082ed34c3cc
---

T-M51（废弃 API 清理与兼容性加固）已实施完成（2026-08-19），双版本 0 Internal API。

## 最终 verifyPlugin 结果（minimal 243+262）

| 指标 | 2024.3（前→后） | 2026.2（前→后） |
|------|------------------|------------------|
| **Internal API** | 1→**0** ✅ | 2→**0** ✅ |
| **Scheduled-removal** | 1→**1**（可删除） | 3→**1**（可删除） |
| **Deprecated** | 14→**9** ✅ | 35→**27** ✅ |
| **Experimental** | 11→**8** ✅ | 15→**8** ✅ |

## 已实施项（12 项全部完成）

- **T-M51-00** Kotlin→Java 改写消除 synthetic bridge（`SqlLogToolWindowFactory.java`）
- **T-M51-01** `PluginManager.getPlugin()` → `ProtectionDomain` + `DeleteCommand`（完全消除）
- **T-M51-02** `ToolWindowManagerListener.stateChanged()` 三参版 → 两参版（`ToolWindowVisibilityListener.java`）
- **T-M51-03** `PluginId.findId()` → `PluginId.getId()`
- **T-M51-04** `projectOpened()` → 待删除（用户确认只需"IDEA 启动时"，不需要"项目打开时"）
- **T-M51-05** `Messages.showIdeaMessageDialog()` → `MessageDialogBuilder.yesNoCancel()`
- **T-M51-06** `updateActionsImmediately()` → `updateActionsAsync()`
- **T-M51-07** ~~`DaemonCodeAnalyzer.restart(PsiFile)` → `restart()`~~ — **方向搞反了**（见下方纠正）
- **T-M51-08** ~~`Notification.setListener()` → 确认无需修改~~ — **需要修改**（见下方纠正）
- **T-M51-09** `addDocumentListener()` → 两参版（3 文件 5 处）
- **T-M51-10** ~~`UIUtil.getHTMLEditorKit()` → `JBHtmlEditorKit()`~~ — **需要改为 `JBHtmlEditorKit(boolean)`**

## 关键发现：PluginManager.getPlugin() 替代方案

用标准 Java `ProtectionDomain` + `StartupActionScriptManager.DeleteCommand` 完全绕过：
- `ProtectionDomain.codeSource.location` 获取 jar 路径（三平台可用）
- `DeleteCommand` 替代 `uninstallAfterRestart()`（公开 API，243/262 皆存）
- `PluginDetailsService` 虽公开但返回的不是 `IdeaPluginDescriptor`，无法替代

## 待删除：ProjectListenerRegistrar（可消除最后一个 scheduled-for-removal）

**用户确认**：实际只需要"IDEA 启动时"触发，不需要"每个项目打开时"触发。
- `LogStartupActivity`（postStartupActivity）已覆盖 IDE 启动场景
- `ProjectListenerRegistrar`（projectOpened）是多余的保险
- **删除 `ProjectListenerRegistrar` 及其调用即可彻底消除 scheduled-for-removal**

排查结论（无公开替代 API）：
- `StartupActivity` / `Background` / `DumbAware` → 仅 IDE 启动时触发一次
- `ProjectLifecycleListener`（262）→ 只有 `afterProjectClosed()`，impl 包（内部）
- `ProjectManagerListener` → 262 无新方法，`projectOpened()` `@Deprecated(forRemoval=true)`

## 提交记录

- `31b5b43` — T-M51 主体（Java 改写 + 9 项 API 替换）
- `04fcf3a` — T-M51-01-ALT（`PluginManager.getPlugin()` 完全消除）

**Why:** Marketplace 阻断级修复完成，双版本 0 Internal API，可重新提交。

## ⚠️ T-M51 纠正（2026-08-19 新一轮调研）

verifyPlugin 全三版本（243/253/262）跑出 9/11/27 个 deprecated 遗留，经 jar 字节码实证：

| # | 错误决策 | 纠正 | 替代方案 |
|---|---------|------|---------|
| T-M51-07 | `restart(PsiFile)` → `restart()` | **反向** — 无参版才是废弃的 | `restart()` → `restart(PsiFile)` — 253 字节码 `Deprecated: true` 实证 |
| T-M51-08 | `setListener()` 不改 | **必须改** — 单参版已废弃 | `createNotification(title, content, type, NotificationListener)` 4参重载替代 |
| T-M51-10 | `JBHtmlEditorKit()` 无参 | **无参版已废弃** | `JBHtmlEditorKit(boolean)` — 253 字节码 `Deprecated: true` 实证 |

**尚未修复的 deprecated API（共 9 类）：**

1. `LineMarkerInfo` 6参构造 → 7参（+`Supplier<@Nls String> accessibleNameProvider`）— 3 文件
2. `JavaAnnotationIndex.get()` → `getAnnotations()` — 1 处
3. `ChooseByNameModel.getCheckBoxMnemonic()` → 删除覆写 — 1 文件
4. `JBHtmlEditorKit()` 无参 → `JBHtmlEditorKit(true)` — 1 文件
5. `TextAttributesKey.createTextAttributesKey(String, TextAttributes)` → `createTextAttributesKey(String, TextAttributesKey)` — 1 文件
6. `Notification.setListener()` → `createNotification` 4参重载 — 1 文件
7. `Document.addDocumentListener()` → 双参版(+Disposable) — 多处（部分已改）
8. `DaemonCodeAnalyzer.restart()` → `restart(PsiFile)` — 1 文件
9. `ReadAction.compute(ThrowableComputable)` → **仅 262 废弃** — 保持 + @Suppress（243/253 安全）

**How to apply:** 相关：[[pitfall-kotlin-synthetic-bridge-internal-api]]、[[pitfall-toolwindow-manager-listener]]、[[project-marketplace-publishing]]、[[pitfall-tm51-api-replacement-research]]