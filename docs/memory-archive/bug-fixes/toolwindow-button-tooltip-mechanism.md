---
name: toolwindow-button-tooltip-mechanism
description: 右侧面板按钮 tooltip 机制与 F-41 修复：init() 中 setStripeTitle 确保首次 hover 显示可读名称（非内部 ID）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_8697e69e-2205-4083-9399-9f6eceee7ded
---

右侧面板（right-side panel）按钮 tooltip 显示工具窗口 ID（如 `cn.song.mybatisplus.visualizer.log`）而非可读名称的根因与修复（F-41，2026-08-25）：

**机制**：`ActivateToolWindowAction.updatePresentation()` 通过 `toolWindow.getStripeTitleProvider()?.get()` 读取 tooltip 文本，空值回退到 `toolWindow.getId()`。该 action 由 platform 自动为每个 `<toolWindow>` 注册，无需显式 `<action>` 声明。

**`ToolWindowFactory` 两个回调的调用时序**：
- `init(ToolWindow)`：tool window 注册时立即调用（插件加载后），**早于用户任何交互**
- `createToolWindowContent(Project, ToolWindow)`：用户**点击**按钮后才调用（内容创建阶段）

**之前的问题**：工厂在 `createToolWindowContent()` 中调用 `setStripeTitle()`，但用户首次 hover 时（点击前）provider 尚未设置，tooltip 显示 fallback（即 tool window ID）。

**修复**：在两个工厂（`SqlLogToolWindowFactory`、`SqlHistoryToolWindowFactory`）的 `init()` 方法中调用 `setStripeTitle(MpSqlVisualizerBundle.message(TITLE_KEY))`，确保首次 hover 前 provider 已就绪。

**插件 XML 层面**：`<toolWindow>` 标签**不支持 `title` 属性**（IntelliJ 忽略），`$bundle:` 语法也不支持。`bundle`/`key` 模式仅适用于 `<notificationGroup>` 和 `<action>` 等特定扩展点。右侧面板按钮文案只能通过代码 `setStripeTitle()` 设置。

**`<action>` 不能替代 tooltip**：尝试在 plugin.xml 中声明 `<action id="<toolWindow-id>" text="...">` 来覆盖 tooltip —— 失败。原因：(1) `<action>` 必须指定 `class` 属性，否则 `invalid plugin descriptor`；(2) 即使有 class，`ActivateToolWindowAction` 由 platform 自动注册，自定义 action 不会替代它。tooltip 只能通过 `setStripeTitle()` 代码设置。

**教训**：任何需要通过代码设置 tool window stripe title 的工厂，`init()` 是最可靠的时机。`createToolWindowContent()` 仅作内容创建，不应承担 title 初始化。
