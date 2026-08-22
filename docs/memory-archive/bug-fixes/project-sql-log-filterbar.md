---
name: project-sql-log-filterbar
description: SQL 日志面板筛选条 UI 开发——布局修复/按钮样式演进/设置显隐控制/MessageBus 实时通知
metadata:
  node_type: memory
  type: project
  originSessionId: sess_7119d26c-5527-4a74-915b-7ce311452472
---

**2026-08-20 最终版**：SQL 日志面板筛选条（全部/SELECT/INSERT/UPDATE/DELETE + 高频折叠 + 耗时排序）完整开发记录。

## 功能清单

| 功能 | 状态 | 说明 |
|------|------|------|
| 列表铺满 | ✅ | 从 `BorderLayout.SOUTH` → `CENTER`，工具栏+过滤条包到 `buildTopPanel()` 放 `NORTH` |
| 工具栏分割线 | ✅ | `JSeparator(HORIZONTAL)`，全宽，上下 3px 留白 |
| 多行 SQL 预览 | ✅ | `fixedCellHeight = -1`，行底 `MatteBorder` + `EmptyBorder(5,8,5,8)` |
| 筛选按钮文字化 | ✅ | `JToggleButton` + IntelliJ 原生 LaF（选中态 = 背景变色） |
| 过滤条显隐设置 | ✅ | `logFilterBarEnabled: Boolean = false`（默认关） |
| 设置变更实时生效 | ✅ | MessageBus 发布-订阅（`SETTINGS_CHANGE_TOPIC`） |

## ⚠️ 样式演进（踩坑记录）

### 方案 1：JToggleButton + 手动背景色 → ❌ 丑
```kotlin
// 手动设置 background/foreground → 覆盖原生 LaF，边框+背景都高亮，风格不原生
button.background = JBColor(0xD6E4F7, ...)
button.foreground = JBColor(0x2563EB, ...)
```

### 方案 2：ActionToolbar + ToggleAction → ❌ 空心圆
- `ToggleAction(text, desc, null)` 传 `icon = null`
- IntelliJ `ActionToolbar` 渲染无图标动作为**空心圆占位符**
- 即使重写 `update()` 设置 `presentation.text` 也无效
- **结论**：`ActionToolbar` 不适合文字按钮

### 方案 3：ToggleAction + 图标 → ❌ 用户不要图标
- 给每个动作设置 12px 图标（Execute/Add/Refresh/GC）
- 用户明确说「筛选相关按钮不需要图标，就用文字按钮」

### ✅ 最终方案：JToggleButton + 原生 LaF
```kotlin
allButton = JToggleButton(MpSqlVisualizerBundle.message("log.filter.all")).apply {
    isSelected = activeFilter.size >= 4
    addActionListener { ... }
}
```
- 不设置任何自定义样式（不碰 background/foreground/border）
- IntelliJ 原生 LaF 自动渲染选中态（Light=淡灰底，Darcula=淡蓝灰底）
- `isSelected = true` 时按钮有可见的选中效果

## ⚠️ Kotlin 属性初始化顺序（NPE 坑）

**错误**：`filterGroup` 声明在 `init` 块之后（line 335），但 `init` 调用 `buildFilterBar()` → `filterGroup.removeAll()` → NPE。

**规则**：Kotlin 属性按声明顺序初始化，`init` 块与属性声明交错执行。在 `init` 中使用的字段必须声明在 `init` 之前。

**修复**：将 `filterGroup` 和 `_filterToolbarRef` 声明移到 `init` 块之前。

## ⚠️ PluginSettings.getInstance() 在测试环境不可用

**错误**：`SqlLogPanel` 构造时调用 `refreshFilterBarVisibility()` → `PluginSettings.getInstance().state` → NPE（headless 测试环境无服务）。

**修复**：
```kotlin
fun refreshFilterBarVisibility() {
    val enabled = runCatching { PluginSettings.getInstance().state.logFilterBarEnabled }.getOrDefault(false)
    filterSeparator?.isVisible = enabled
    filterBarPanel?.isVisible = enabled
}
```

## 设置变更实时通知（MessageBus）

### 发布方（PluginSettingsPanel.apply()）
```kotlin
ApplicationManager.getApplication().messageBus
    .syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged()
```

### 订阅方（SqlLogPanel.init）
```kotlin
val settingsListener = SettingsChangeListener {
    ApplicationManager.getApplication().invokeLater { refreshFilterBarVisibility() }
}
project.messageBus.connect(this).subscribe(PluginSettings.SETTINGS_CHANGE_TOPIC, settingsListener)
```

### Topic 定义（PluginSettings.kt 顶部）
```kotlin
fun interface SettingsChangeListener {
    fun settingsChanged()
}

// companion object 内
@JvmField
val SETTINGS_CHANGE_TOPIC: Topic<SettingsChangeListener> = Topic.create(
    "MpSqlVisualizerSettingsChange",
    SettingsChangeListener::class.java,
)
```

**要点**：
- `SettingsChangeListener` 必须定义在 **companion object 外部**（否则外部无法引用类型）
- 使用 `project.messageBus.connect(this)` 自动管理订阅生命周期（`this` 是 `Disposable`）
- `syncPublisher` 同步通知，`invokeLater` 确保 UI 刷新在 EDT 执行

## 相关文件

| 文件 | 改动 |
|------|------|
| `log/SqlLogPanel.kt` | `buildTopPanel()` + `buildFilterBar()` + `refreshFilterBarVisibility()` + `updateFilterBar()` |
| `config/PluginSettings.kt` | +`logFilterBarEnabled` 配置项 + `SETTINGS_CHANGE_TOPIC` |
| `config/PluginSettingsPanel.kt` | +`logFilterBarCheck` 复选框 + `apply()` 发布事件 |
| `messages/MpSqlVisualizerBundle.properties` | +`settings.log.filterBar` / `.tip` |
| `messages/MpSqlVisualizerBundle_zh.properties` | +`settings.log.filterBar` / `.tip`（中文） |

**相关**：[[project-log-capture-lifecycle-design]]、[[pitfall-actiontoolbar-refresh-api]]