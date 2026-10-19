# T-PreviewBgFit 预览面板两处显示缺陷（参数区背景丢失 + JOIN 图默认不居中）— 开发计划

- 日期：2026-09-17 15:20
- 任务编号：T-PreviewBgFit
- 来源：用户报告两个新问题——①左侧参数面板下方应是灰色却是黑色；②JOIN 图默认没居中（等于点【调整内容】后的效果）
- 取证：真实 IDE 2024.3 截图 + 源码/提交历史比对 + 243 平台 jar 反编译

---

## 问题一：左侧参数面板下方变黑

### 根因（提交级实证）

`0e857ec T-UiNative P0a` 把预览左右分栏从自绘 `ThemedSplitPane` 换成平台 `OnePixelSplitter`：

```
- val split = ThemedSplitPane(paramScroll, editorHolder).apply {
-     dividerSize = 3
-     background = UIUtil.getPanelBackground()      ← 被丢掉的这一行
+ val split = OnePixelSplitter(false, "mpvs.preview.splitter", 0.3f).apply {
```

而左侧参数区整条链刻意是透明的（`paramScroll.isOpaque=false` + `viewport.isOpaque=false` +
`paramTopAlign.isOpaque=false`，M36.5 起，用于与父容器融合）。原来透出的是 `ThemedSplitPane`
显式设置的**面板灰**，换组件后透出 `OnePixelSplitter` 自己的背景——深色主题下接近黑，
于是「参数行下方一片黑」。

### 方案

1. 恢复被丢掉的那层背景：`split.background = UIUtil.getPanelBackground()`；
2. 防御性收口：让参数滚动区自身成不透明面板灰（`paramScroll.isOpaque = true` + 面板背景），
   不再依赖祖先容器给出正确底色（今后再换容器也不会复发）。

## 问题二：JOIN 图默认不居中

### 根因（平台 API 反编译实证）

平台对「图打开后自适应居中」有专门机制：`GraphSettings.isFitContentAfterLayout()/
setFitContentAfterLayout(boolean)`，工具栏那个开关就是它的 toggle 实现
（`FitContentAfterLayoutAction extends AbstractGraphToggleAction`，读的正是该设置），
而【调整内容】按钮 = `FitContentAction` → `GraphBuilder.fitContent(true)`（iconst_1，已核对字节码）。

这两个动作都只在**独立图编辑器标签页**路径被平台触发；我们把图嵌进预览面板
（`NativeJoinDiagramSupport.buildTabComponent` 手工构造 `UmlFileEditorImpl` 并取它的 component），
该开关从未被置位、也没有「布局完成后」的钩子 → 图停在默认缩放/位置（左上角），
与用户观察一致（点【调整内容】后正确）。

### 方案

嵌入路径补上平台缺失的两步（`NativeJoinDiagramSupport` 内，DBN/Diagrams 隔离边界之内）：
1. 置 `GraphSettings.fitContentAfterLayout = true`（经 `builder.graphPresentationModel.settings`）——
   此后任何布局自动自适应，与平台开关语义一致；
2. 首次显示且组件有尺寸后调用 `GraphBuilder.fitContent(true)`（**与【调整内容】按钮完全同参**，
   反编译实证），并在稍后再补一次以吸收异步布局收尾。

---

## 测试

- 问题一：面板结构守卫——参数滚动区应不透明且背景为平台面板色（防再次换容器时丢背景）。
- 问题二：`NativeJoinDiagramSupport` 依赖 Diagrams 插件（默认 test 无该插件、Diagrams 仅在
  Ultimate 沙盒），无法 headless 断言；依赖 API 全部反编译核实 + 真机走查（§真机清单）。

## 验收标准

1. 深色主题下左侧参数区自标题行到面板底部**整片面板灰**，无黑色空区（问题一）。
2. 打开 JOIN 图标签页即为自适应居中态（等于点【调整内容】后的效果），无需手工点击（问题二）。
3. 全量 `test` 绿 + `testDatabase` 绿（UI/图形面改动仍走双跑口径）。

## 真机走查清单（留人工）

1. 深色主题打开预览：参数区（含无参数、单参数、多参数、批量列勾选、XML 动态条件四种左栏形态）
   通体面板灰；拉伸窗口与拖动分隔条后同样正确。
2. 浅色主题复看一遍（防只按深色修）。
3. 打开 JOIN 图标签页：默认即居中自适应；切到 SQL 再切回仍正常；再次预览（图重建）后仍居中。
4. `nativefw` 降级路径（无 Diagrams 插件环境）不受影响：自绘 `DiagramPanel` 照常。

## 风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 背景修复只覆盖深色 | 只按截图主题修，浅色主题可能反白 | 显式用 `UIUtil.getPanelBackground()` 命名色（两主题各自解析），走查项 2 覆盖浅色 |
| 自适应打断用户手动的缩放/平移 | 每次布局都自动 fit | `fitContentAfterLayout` 是平台既有设置（用户可在图工具栏关闭其 toggle 语义）；首次 fit 仅一次 |
| 图未布局完就 fit | fit 在布局前执行会得到错误视口 | 开关必须在布局触发前置位（见下），另有尺寸兜底补拍 |
| 隔离边界 | `NativeJoinDiagramSupport` 已按 try/catch 兜底（Community/无 Diagrams） | 新增代码同处该边界内，异常仍走原降级（自绘图） |

## 九、第二轮修正（真机复验反馈：第一张图仍不居中、第二张正常）

真机反馈「第一个 SQL 的 join 图没居中，第二个正常」——**竞态**，定位如下：

`NativeJoinDiagramOpener.buildEmbeddableEditor` 的画布填充是
`builder.queryUpdate()...runAsync()`（**异步**），而首轮我只在它**返回之后**（`buildTabComponent` 里）
才置 `fitContentAfterLayout`。布局管线（`GraphLayoutServiceImpl` 字节码实证）只在**布局收尾那一刻**
读该开关并调 `GraphAnimationService.fitContent(view)`——异步布局若已越过判定点，开关就白置了；
第一张图恰好越过（不居中）、第二张赶上（正常），与观察完全吻合。

修正：
1. 新增 `NativeJoinDiagramOpener.enableAutoFit(builder)`，并把调用**前移到两条建图路径的
   `queryUpdate` 之前**（JOIN 路径 `buildEmbeddableEditor` 步骤 3.5；DATABASE 路径
   `NativeDatabaseDiagramSupport.buildEmbeddableEditor`）——开关先置位，异步布局收尾即自适应；
2. `NativeJoinDiagramSupport.installDefaultFitContent` 退为**视口尺寸兜底**：首次显示且有尺寸后
   补 `fitContent(true)`，最多 2 次（间隔递增，非轮询），吸收「布局收尾早于组件拿到最终尺寸」；
3. 守卫落到 `testDatabase` 的 `HeavyNativeDatabaseDiagramJoinChainTest`：建图组件后断言
   `settings.isFitContentAfterLayout == true` 与 `view.fitContentOnResize == true`。

**诚实口径**：该守卫用例在本机仍走绿跳（DB 集成链的驱动管线未通，见 T-ExecDocGate 计划 §七 记录的
平台侧断点），断言**已写入但本机未真正执行**；本机的有效验证仍是用户的真机复验。
