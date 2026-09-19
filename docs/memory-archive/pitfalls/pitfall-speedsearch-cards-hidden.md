---
name: pitfall-speedsearch-cards-hidden
description: construct 即在 CardLayout 隐藏卡上的列表 installOn ListSpeedSearch
  不生效——只有构造时可见的列表打字即搜才激活
metadata:
  node_type: memory
  type: project
  originSessionId: sess_f22647d8-8769-4437-8e2c-6d2684c1b887
---

在共享 JBList 子类（如本项目 `SqlEntryList`，历史二级 SQL 列表复用主日志列表的同一父类）的 `init{}` 里直接调 `ListSpeedSearch.installOn(this)`，**只对「构造时已显示在组件树上」的列表生效**。主日志列表（始终可见）打字即搜正常；历史二级列表构造在 `CardLayout` 的隐藏卡上（showFileList 一级 / showFileContent 二级切换，切到二级前该卡不显示），`SpeedSearchBase.install` 依赖组件可感知/可见才挂事件钩子，隐藏卡上 installOn 未激活 → 真机「不点搜索框直接打字」无响应的边界。

**Why:** 真机验收 T-UiNative 时发现「日志列表、历史文件列表打字即搜 OK，唯独历史点进文件后的 SQL 二级列表不触发」——同一父类 installOn 的口径被隐藏卡布局打破。

**How to apply:** ① SpeedSearch 挂载改为「按需/延迟挂载」——在 CardLayout 切到该卡后再 `installOn`（本项目已修：`SqlSpeedSearchInstalled` 守卫，`showFileContent`/`loadFileContentSync` 切卡可见后补装），不移进 `SqlEntryList.init`；② 真机验收列表类交互务必覆盖「初始化时不在当前显示卡」的列表；③ 共用父类 fixture 的单测对隐藏卡列表天然盲区（headless 全绿≠真机生效），见 [[pitfall-test-fixture-shape-mismatch]]。

**2026-09-16 已修（t-uinative 5b0e2c3 等）**——修法=installOn 移出共享父类 `SqlEntryList.init`：主日志列表在 `buildListPane`（构造成可就见）装；历史二级在 `showFileContent`/`loadFileContentSync` 切卡后按需装。

**配套 API 事实（headless 安装断言）**：`SpeedSearchSupply.getSupply(comp)` 单参含 `isPopupActive()` 过滤、headless 恒 null；断言「已安装」须用两参 `getSupply(comp, true)`（checkShowing=false 直读组件 clientProperty 的 SPEED_SEARCH_COMPONENT_MARKER）。关联：匹配高亮见 [[pitfall-speedsearch-highlight-renderer]]。

**2026-09-16 追加（t-uinative 后续真机）**：修复后「打字即搜」生效但青色高亮仍无 → 高亮是渲染器职责，见 [[pitfall-speedsearch-highlight-renderer]]；用户**否决原生筛选**（`ListSpeedSearch` 无内置过滤/隐藏不匹配行，带筛选的是树专用 `FilteringSpeedSearch` 与弹层 `ListWithFilter`，硬上需 `SpeedSearchBase` 受保护钩子手写，且与顶部搜索框 `filterBySearch` 过滤管线双过滤打架）——打字即搜负责选中跳转+高亮，真正过滤列表仍走顶部搜索框。补装高亮后已 `git merge origin/master` 进 t-uinative（提交 0330cb8，解 SqlLogPanel/HistoryCellRendererTest 两文件冲突）。
---

**已修复（20260916，t-uinative 合 master 3fc2ad9）**：安装移出共享父类 init、切卡可见后按需装（`sqlSpeedSearchInstalled` 守卫，提交 5b0e2c3）；本文件归档留档，修复全录见 docs/开发计划/20260916-1400 计划文档。
