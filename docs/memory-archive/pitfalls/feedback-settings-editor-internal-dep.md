---
name: feedback-settings-editor-internal-dep
description: SettingsEditor 被 @ApiStatus.Internal 标记——须用 Class.forName
  反射消除编译期依赖，避免直接 import + is 类型引用
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_6e264e53-f910-4ea0-a8c4-9500f6e4f5eb
---

## 问题

`com.intellij.openapi.options.newEditor.SettingsEditor` 被 `@ApiStatus.Internal` 标记。原代码直接 `import` 它并用于 `comp is SettingsEditor` 类型判断 + `SettingsEditor::class.java.getDeclaredField("settings")` 反射读 `settings` 字段取当前 `Settings`，导致对 internal API 的编译期依赖（IDE 报不稳定警告，跨版本有被移除风险）。

## 最终方案

移除 import + 类型引用，全部改 `Class.forName` 反射：

```kotlin
// 反射类名，避免直接引用 internal 类型
val editorClass = runCatching { Class.forName("com.intellij.openapi.options.newEditor.SettingsEditor") }.getOrNull() ?: return null
var comp: Component? = searchField
while (comp != null) {
    if (editorClass.isInstance(comp)) {              // 替代 comp is SettingsEditor
        return runCatching {
            val field = editorClass.getDeclaredField("settings")
            field.isAccessible = true
            field.get(comp) as? Settings
        }.getOrNull()
    }
    comp = comp.parent
}
```

## How to apply

对 `@ApiStatus.Internal` 的平台类（仅反射读字段/判类型时），用 `Class.forName("全限定名")` + `isInstance` + `getDeclaredField` 反射，而非直接 `import` + `is` 类型引用，消除编译期依赖。背景：主设置页 `currentSettings()` 兜底逻辑，见 [[project-settings-tree-nav]]。

## 陷阱：修复后警告仍可能残留

用户修复后再次报 `@ApiStatus.Internal` 警告，贴出的是**旧的实现代码**（`comp is SettingsEditor`）。原因是：IDE/编译缓存保留旧 AST 或查看的是旧源码版本。实际代码已无任何 `SettingsEditor` 代码引用（仅注释文本 + `Class.forName` 字符串），`./gradlew clean compileKotlin` 无警告。排查顺序：先 `grep -rn SettingsEditor src/` 确认只剩注释，再 `clean` 排除缓存，不要在 IDE 未同步时下结论。

## 相关

- [[project-settings-tree-nav]] — 设置页左侧树导航
- [[feedback-settings-navigation-new-window]] — Settings.KEY.select() 同对话框切换