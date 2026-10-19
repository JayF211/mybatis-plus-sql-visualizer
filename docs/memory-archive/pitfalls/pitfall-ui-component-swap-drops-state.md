---
name: pitfall-ui-component-swap-drops-state
description: 自绘组件换成平台组件时漏掉被替换组件显式设置过的状态（背景/不透明/边框）——深色主题下才显形，症状是某块区域变黑
metadata:
  node_type: memory
  type: project
  originSessionId: sess_598c1639-3398-416c-ac31-3c129bbc5bca
---

20260917 用户报「预览左侧参数面板下方是黑色、应该整片灰色」。根因是**提交级可查**的组件替换漏项：T-UiNative P0a（`0e857ec`）把预览左右分栏从自绘 `ThemedSplitPane` 换成平台 `OnePixelSplitter` 时，丢了原类里 `background = UIUtil.getPanelBackground()` 这一行；而参数区整条链自 M36.5 起刻意透明（`paramScroll.isOpaque=false` + `viewport.isOpaque=false` + `paramTopAlign.isOpaque=false`，为与父容器融为一体），底色一直靠祖先容器提供——于是空区直接透出分栏自身背景（深色主题近黑）。

**Why**：这类丢失在浅色主题下几乎隐形（两种颜色都接近白），只在深色主题截图里暴露；而「谁负责给这块底色」是隐式契约，读任一侧代码都看不出来。

**How to apply**：
- 替换自绘组件为平台组件时，**逐项核对被替换组件显式设置过的状态**：`background` / `isOpaque` / `border` / `dividerSize` / 自定义 UI 类。定位手法一条命令列丢失项：
  `git show <替换提交> -- <文件> | grep -E "^[-+].*(Opaque|background|border|dividerSize)"`（本次即这么锁定的）。
- 修法做**两层**：①恢复祖先容器该有的背景（回归修复）；②让透明链末端自己成不透明并持命名色（今后再换容器不复发）。本次=分栏补 `UIUtil.getPanelBackground()` + 参数滚动区自身不透明面板灰。
- 落一条**结构守卫用例**把「该区域组件不透明且背景==`UIUtil.getPanelBackground()`」钉住，别再依赖「祖先会给底色」（`SqlPreviewPanelTest.testParamsAreaCarriesPanelBackground`）。
- 相关：[[project-pending-plans]]（T-PreviewBgFit 条目）、[[pitfall-log-first-not-code-reasoning]]（用户截图就是证据，别去猜主题差异）。
