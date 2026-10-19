---
name: pitfall-speedsearch-highlight-renderer
description: ListSpeedSearch 匹配青色高亮是渲染器职责——须在 cellRenderer 里调
  SpeedSearchUtil.applySpeedSearchHighlighting，默认仅灰置+选中跳转无高亮
metadata:
  node_type: memory
  type: project
  originSessionId: sess_f22647d8-8769-4437-8e2c-6d2684c1b887
---

`ListSpeedSearch` 的 speed search 默认只做「按键跳转选中匹配项 + 灰置不匹配行」，**匹配文本的青色 search-match 高亮不是平台自动画的**——是 cellRenderer 的职责。真机表现「打字即搜生效了、但蓝条高亮没有」即根因。

**修法**：cellRenderer（含双行结构 `LogEntryListCellRenderer`/`HistoryCellRenderer`）在 `getListCellRendererComponent` 里对每个 `SimpleColoredComponent`（metaLine 元信息行 + 各 SQL 行）调
```kotlin
SpeedSearchUtil.applySpeedSearchHighlighting(list, comp, false, useSelectionFill)
```
平台依 `SpeedSearchSupply.matchingFragments` 精确给匹配区间叠加 `STYLE_SEARCH_MATCH`（搜索匹配样式，青色光晕）。

**Why:** 起初误信 `setDynamicSearchMatchHighlighting(true)`——那是 `SimpleColoredComponent` 在「全列文字匹配」场景（DynamicSearchMatch）的方法，`ListSpeedSearch`/`SpeedSearchBase` 根本没有该方法（编译拦截证伪），必须回退到渲染器施加高亮。反编译 `SpeedSearchUtil` 证实：`applySpeedSearchHighlighting(SCC, Iterable<TextRange>, bool)` 内部逐片段 `ColoredIterator` 与匹配区间求交、partial/full 分支上 STYLE_SEARCH_MATCH。

**How to apply:** ① 双行渲染器要在所有子行（meta+sql）都 apply；② 匹配着色跟随 `list` 的 supply（同一 list 传入即可，方法内部 `SpeedSearchSupply.getSupply(component)`）；③ 叠加方式=append 片段后 apply，不改原文本。关联：挂载失效见 [[pitfall-speedsearch-cards-hidden]]，获取 supply 两参断言见该条。T-UiNative 提交 6159828。

**筛选边界（用户否决原生筛选）**：`ListSpeedSearch` 的单纯列表速度搜索**没有内置过滤/隐藏不匹配行**——带筛选的是 `FilteringSpeedSearch`（接对树 `FilteringTree` 专用）与 `ui/speedSearch/ListWithFilter`（弹层多选，非工具窗常驻列表惯例）。硬上过滤需 `SpeedSearchBase` 受保护钩子（`selectElement`/`getEnteredPrefix` 等）手写，且与既有顶部搜索框 `filterBySearch` 过滤管线（含类型过滤/排序的 `refreshList` 差分渲染）双过滤打架。定案：打字即搜=选中跳转+匹配高亮（已完成），真正过滤列表仍走顶部搜索框——用户 2026-09-16 明示「原生筛选不做」。
---

**已落码（20260916）**：SqlLogPanel/HistorySqlLogPanel 渲染器均调 SpeedSearchUtil.applySpeedSearchHighlighting（6159828）；本文件归档留档。
