---
name: feedback-settings-titledborder-pitfall
description: 设置页折叠面板边框样式的已知问题与最终方案（无边框、无折叠、仅 TitledSeparator 横线）
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_6e264e53-f910-4ea0-a8c4-9500f6e4f5eb
---

## 问题历程

设置页折叠面板边框样式经过 6 次迭代：

1. `customLine` 四周边框 → 用户反馈"白色边框割裂感强"
2. 去掉边框 → 用户要求"用 TitledSeparator 做边框"
3. `customLine` + `TitledSeparator` → 用户要求"分割线样式四周边框，不是白色边框"
4. **`TitledBorder`**（`BorderFactory.createTitledBorder`）→ 两个已知问题：
   - **白底凹陷感**：标题区域背景与内容区不一致，形成"白色边框"的视觉割裂
   - **toggle 按钮错位**：toggle 放在 `BorderLayout.WEST` 会落入内容区（边框下方），而非覆盖在边框标题旁
5. 回退到 `customLine` + `TitledSeparator` → 用户要求"只保留分隔线，不要四周边框，不要折叠"
6. **最终方案**：去掉边框和折叠，仅保留 `TitledSeparator` 横线分隔

## 最终方案

`sectionGroup()` 函数：仅 `TitledSeparator` 横线 + 内容面板（无边框、无折叠、无 toggle）。

```kotlin
fun sectionGroup(titleKey: String, content: (JPanel, GridBagConstraints) -> Unit) {
    val contentPanel = JPanel(GridBagLayout())
    // ... 构建内容 ...
    panel.add(TitledSeparator(MpSqlVisualizerBundle.message(titleKey)), gbc)
    gbc.gridy++
    panel.add(contentPanel, gbc)
    gbc.gridy++
}
```

## How to apply

设置页分区标题避免用 `TitledBorder` 或 `customLine` 边框，用 `TitledSeparator` 横线分隔即可。参考 [[project-settings-layout]]。

## 相关

- [[project-settings-layout]] — 布局优化完整历史
- [[feedback-settings-navigation-new-window]] — showSettingsDialog 新开窗口修复