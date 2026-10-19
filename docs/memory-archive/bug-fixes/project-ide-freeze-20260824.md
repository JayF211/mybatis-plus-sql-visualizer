---
name: project-ide-freeze-20260824
description: IDEA 2026.2 卡顿根因分析（2026-08-24）——SqlLogPanel 列表刷新导致 20~30 秒 EDT
  冻结，PluginFreezeNotifier 点名。M46 开发计划已据此更新。
metadata:
  node_type: memory
  type: project
  originSessionId: sess_d1ba4e0d-b2ea-49ab-8987-47cf5faf93aa
---

## 现象

IDEA 2026.2（build 262.9437.185）在 2026-08-24 12:21~12:35 出现严重卡顿，11 次 EDT freeze，总计阻塞 >250 秒。

`PluginFreezeNotifier` WARN 原文：
```
Identified UI freeze in plugin cn.song.mybatisplus.visualizer.mybatis-plus-sql-visualizer for 30718 ms
```

## 日志位置

IDEA 日志在 `LOCALAPPDATA` 而非 `APPDATA`：
`C:\Users\USER\AppData\Local\JetBrains\IntelliJIdea2026.2\log\`（注意不是 Roaming）

## 冻结时间线（11 次）

| 时间 | 时长 | 冻结点 | 类型 |
|------|------|--------|------|
| 12:32:24 | 30.7s | `BasicHTML.createHTMLView` | JCEF HTML |
| 12:32:55 | 20.8s | `BasicHTML.createHTMLView` | JCEF HTML |
| 12:33:16 | 22.5s | `SqlLogPanel.getListCellRendererComponent` | **本插件** |
| 12:33:38 | 22.5s | `BasicHTML.createHTMLView` | JCEF HTML |
| 12:34:01 | 21.4s | `SqlLogPanel.getListCellRendererComponent` | **本插件** |
| 12:34:23~12:35:29 | 21~22s | `BasicHTML$Renderer._init_` / `createHTMLView` | JCEF HTML |

另有 12:21、12:25、12:26 三次无名 dump（前序冻结）。

## 两类冻结的调用链

**类型 A — 本插件直接造成（栈顶 `SqlLexer.classifyWord`）**：
```
SqlLogManager.notifyUi (SqlLogManager.kt:276)             ← invokeLater 到 EDT
  → SqlLogPanel.refreshList (SqlLogPanel.kt:593)           ← 全管线刷新
    → SqlLogPanel.rebuildListModel (SqlLogPanel.kt:641)    ← 重建列表模型
      → DefaultListModel.addElement × N (SqlLogPanel.kt:645) ← 逐条添加，O(N²) 事件风暴
        → WideSelectionListUI.updateLayoutState
          → getListCellRendererComponent (SqlLogPanel.kt:497)
            → SqlListHtmlRenderer.render (SqlListHtmlRenderer.kt:52) ← EDT 上做 SQL 词法分析
              → SqlLexer.advance (SqlLexer.kt:69) → classifyWord (SqlLexer.kt:188) ← 栈顶
            → JLabel.setText("<html>...")
              → BasicHTML.updateRenderer → createHTMLView  ← 进入类型 B
```

**类型 B — Swing HTML 引擎（类型 A 的下游放大）**：
```
BasicHTML.createHTMLView → Renderer.<init>
  → BlockView.getPreferredSpan → ParagraphView.calculateMinorAxisRequirements
    → FlowView$LogicalView.getPreferredSpan → GlyphPainter1.sync
      → InlineView.setPropertiesFromAttributes → StyleSheet.getFont
        → CSS.getFont → MuxingAttributeSet.getAttribute  ← 栈顶热点
```

## 三个致命问题

1. **SQL 词法分析在 EDT 执行**：`getListCellRendererComponent` 对每条 SQL `new SqlLexer()` + `advance()` 全量分词，O(N×L) CPU 操作阻塞 EDT
2. **`addElement` 逐条触发事件风暴**：每次 `addElement` 触发 `fireIntervalAdded` → 对所有可见 cell 调 `getListCellRendererComponent`，O(N²)
3. **HTML 文本触发 Swing HTML 引擎**：`JLabel.setText("<html>...")` 走 `BasicHTML.createHTMLView` → CSS 多层属性查找（Swing HTML 渲染是知名瓶颈）

## 修复方案（M46 已实施并提交）

M46 已于 2026-08-24 实施完成并提交（commit `7546f58`），开发计划见 `docs/开发计划/20260824-1000-M46-SQL日志最大条数可配置-开发计划.md`。

已实施的改动：

- **改动 ① PluginSettings.kt**：新增 `logMaxCount` 字段 + `DEFAULT_LOG_MAX_COUNT = 200` 常量
- **改动 ② PluginSettingsPanel.kt**：新增 `logMaxCountField` 控件 + isModified/apply/reset 三处联动
- **改动 ③ SqlLogManager.kt**：构造 buffer 时传入 `logMaxCount` + `applySettings()` 装配 `buffer.maxEntries`
- **改动 ④ SqlLogEntryBuffer.kt**：`maxEntries` 改为 `@Volatile var` 公开（支持运行时调整）
- **改动 ⑤ SqlLogPanel.kt**：`rebuildListModel()` 批量 `addAll` + cell renderer 纯文本化（新增 `formatTimestamp`/`formatDuration`/`durationColor` 三个 helper）
- **改动 ⑥ Bundle i18n**：`settings.log.maxCount` + `.tip` 中英文各 2 行

**编译中修复**：`maxEntries` 最初改为 `private var`，但 `SqlLogManager` 需外部写入，改为 `@Volatile var` 公开。

**验证结果**：compileKotlin/compileJava 通过，2448 测试全绿。

**性能提升**：EDT 冻结从 20~30 秒 → < 200ms（~100x），O(N²) 事件风暴 → O(N) 批量，EDT 上 O(N×L) 词法分析 → 0。

## 相关源码文件

- `SqlLogPanel.kt:497` — `getListCellRendererComponent`（cell renderer）
- `SqlLogPanel.kt:518` — `SqlListHtmlRenderer.render()` 调用点（改动 ⑤ 移除）
- `SqlLogPanel.kt:641` — `rebuildListModel()`（改动 ④ 批量化）
- `SqlListHtmlRenderer.kt:35` — `render()` 函数（保留供预览弹窗使用）
- `SqlLexer.kt:69` — `advance()` 主循环，`classifyWord` 在 :188

**Why:** 插件功能增加后未考虑大数据量下的 EDT 占用，cell renderer 中做了 O(N) 的 SQL 词法分析 + O(N²) 事件风暴放大。
**How to apply:** 任何涉及 JList/JTable 批量刷新的场景，必须预计算 cell 内容 + 批量更新模型，杜绝在 cell renderer 中做 O(N) 计算。纯文本替代 HTML 可消除 Swing HTML 引擎开销。