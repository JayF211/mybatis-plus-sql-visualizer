---
name: feedback-settings-navigation-new-window
description: 设置页导航新开窗口 + 定位不准的完整根因与修复——Settings.KEY.getData(手工 DataContext) 取不到 settings.editor，须用 DataManager.getDataContext(component) 或反射；SectionConfigurable 须缓存面板
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_6e264e53-f910-4ea0-a8c4-9500f6e4f5eb
---

## 问题

在 `PluginSettingsFolderConfigurable` 中点击子页面/搜索结果跳转时，`showSettingsDialog` 每次都**新开一个设置窗口**；修复后不再新开窗口，但**没有跳转到正确位置**（目标控件未滚动可见 + 闪烁高亮找不到控件）。

## 根因（三阶段教训）

1. **第一版修复不够**：仅用 `DataManager.getInstance().getDataContext(searchField).getData(CommonDataKeys.PROJECT)` 拿正确 project 再调 `showSettingsDialog` —— 用户实测**仍然新开窗口**。

2. **第二版修复（同对话框切换）**：通过 `Settings.KEY.select(settings.find(configurableClass))` 在同一对话框内切换 —— 不再新开窗口，但**定位不准**。

3. **定位不准的根因**：
   - `Settings.KEY.getData(DataContext { dataId -> if (dataId == PROJECT.name) project else null })` 用**手工构造的 DataContext**（只含 PROJECT）取 `Settings.KEY`，取不到 `settings.editor` 数据 → `settings == null` → 回退 `showSettingsDialog` → 新开窗口。
   - `SectionConfigurable.createComponent()` 每次**新建面板**，`focusTarget` 里 `target.createComponent()` 拿到的是非显示面板 → `focusAndFlash` 搜索不到控件。

## 最终方案

### 取 Settings 实例（双保险）

```kotlin
private fun currentSettings(): Settings? {
    // 主方案：从 searchField 的真实组件 DataContext 取（沿组件树收集 UiDataProvider 数据）
    runCatching { Settings.KEY.getData(DataManager.getInstance().getDataContext(searchField)) }
        .getOrNull()?.let { return it }
    // 兜底：沿组件树反射读取 SettingsEditor.settings 字段
    var comp: Component? = searchField
    while (comp != null) {
        if (comp is SettingsEditor) {
            return runCatching {
                val field = SettingsEditor::class.java.getDeclaredField("settings")
                field.isAccessible = true
                field.get(comp) as? Settings
            }.getOrNull()
        }
        comp = comp.parent
    }
    return null
}
```

关键：`SettingsEditor` 实现 `UiDataProvider`，其 `uiDataSnapshot` 会 `DataSink.set(Settings.KEY, settings)`。`DataManager.getDataContext(Component)` 通过 `IdeUiService.createUiDataContext` 沿组件树收集 `UiDataProvider` 数据，从而拿到 `Settings.KEY`。

### 同对话框切换 + 定位

```kotlin
val settings = currentSettings() ?: run { /* 回退 showSettingsDialog */ return }
val target = settings.find(configurableClass) ?: return
val callback = settings.select(target)
if (focusHint != null) callback.doWhenDone { focusTarget(target, focusHint) }
```

### SectionConfigurable 缓存面板

```kotlin
override fun createComponent(): JComponent {
    if (!::panel.isInitialized) {
        panel = PluginSettingsPanel(PluginSettings.getInstance(), sections)
    }
    return panel.getRoot()
}
```

### 滚动 + 闪烁高亮

```kotlin
private fun focusTarget(target: Configurable, focusHint: String) {
    SwingUtilities.invokeLater {
        SwingUtilities.invokeLater {  // 双层 invokeLater 确保布局完成
            val root = target.createComponent() ?: return@invokeLater
            val matched = findComponent(root, hint)
            if (matched != null) {
                matched.scrollRectToVisible(matched.bounds)  // 滚动到可见区域
                flashHighlight(matched)
            }
        }
    }
}
```

闪烁效果：极浅米黄底（`JBColor(0xFFFAE6, 0x3A3320)`）+ 柔和琥珀边框（`JBColor(0xE6C068, 0x6B5B2E)`, 2px），Timer 250ms ×6 次 ≈ 1.5 秒。使用 `JBColor`（非 `java.awt.Color`）以自动适配亮/暗主题。

## 关键 API 验证（2024.3.7.1 构建基准）

- `Settings.KEY` = `DataKey.create("settings.editor")`，位于 `app-client.jar`（2024.3 模块划分，非 app.jar）
- `SettingsEditor implements UiDataProvider`，`extends AbstractEditor extends JPanel`（在组件树中，可向上遍历到）
- `SettingsEditor` 有私有字段 `private final Settings settings`（字段名稳定，可反射读）
- `Settings.select(Configurable)` 返回 `ActionCallback`，内部调 `treeView.select` + `editor.select`
- `DataManagerImpl.getDataContext(Component)` 用 `IdeUiService.createUiDataContext` 收集 UiDataProvider 数据

## 提交

- e885e7d：第一版（DataContext 拿 project）——不彻底
- f5c8bea：**最终修复**（Settings.KEY.select + 目标控件聚焦闪烁高亮）
- 本次修复：`currentSettings()` 双保险 + SectionConfigurable 缓存 + scrollRectToVisible

## How to apply

设置页在已打开对话框内切换子页面：
1. 取 Settings 用 `DataManager.getDataContext(component)` 或反射读 `SettingsEditor.settings` 字段，**禁用手工构造 DataContext**
2. `settings.find(clazz)` 拿真实实例 → `settings.select(instance)` 同对话框切换
3. `SectionConfigurable.createComponent()` 必须缓存面板，否则 `focusTarget` 拿到非显示面板
4. 聚焦时 `scrollRectToVisible` 滚动到可见区域 + 橙边框黄底闪烁

## 相关

- [[project-settings-tree-nav]] — 左侧树导航实施细节
- [[project-settings-layout]] — 布局优化完整历史
- [[feedback-settings-titledborder-pitfall]] — TitledBorder 白底凹陷 + toggle 错位
- [[feedback-settings-editor-internal-dep]] — SettingsEditor(@ApiStatus.Internal) 反射消除编译期依赖