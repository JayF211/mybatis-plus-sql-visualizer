# 历史二级 SQL 列表 SpeedSearch 修复 + 搜索占位文案调整-开发计划

- 日期：2026-09-16
- 关联：[20260916-0238-UI与功能原生化四批次实施-开发计划](20260916-0238-UI与功能原生化四批次实施-开发计划.md)（T-UN-05 SpeedSearch 真机走查发现）
- 分支：`t-uinative`（在既有 worktree 继续）
- 状态：已实施交付（见 §四）

## 一、问题

验收走查第 6 项「日志/历史两级列表不点搜索框直接打字」时发现：**历史 tab 点进文件后的二级 SQL 列表不能打字即搜**（主日志列表与历史一级文件列表正常）。

## 二、根因

T-UN-05 把 `ListSpeedSearch.installOn(this)` 写进了 `SqlEntryList` 的 `init {}`（`SqlLogPanel.kt`）。`SqlEntryList` 是主日志列表 `LogEntryList` 与历史二级 SQL 列表共用父类。

- 主日志列表构造时即加入可见组件树，installOn 成功挂载、正常生效。
- 历史二级 SQL 列表**构造于 CardLayout 隐藏卡**（面板初始化时 `cardLayout.show(cards, CARD_FILES)` 只显示一级，二级 `cards.add(sqlScroll, CARD_SQL)` 不显示）。反编译 `SpeedSearchSupply` 证实 `getSupply(comp, checkShowing=false)` 从组件 clientProperty 取 supply，而 `installSupplyTo` 正是把 supply 写进 clientProperty——**安装本身与可见性无直接依赖**，但真机打字命中依赖组件的 key 事件分发链：隐藏卡中该 JList 不在可见组件树、focus/keyListener/`processKeyEvent` 链路不达，故打字不触发。归因到「构造于隐藏卡 → 安装时机选择错误」。
- 附带：该方法还暴露了 T-UN-05 未覆盖的安装点设计缺陷——把安装塞进共用父类 init，隐式假设「构造即可见」，历史二级打破该假设。

## 三、修复方案

1. **主日志列表**：从 `SqlEntryList.init` 移出 installOn，改到 `SqlLogPanel.buildListPane()`（构造即可见的列表，此处安装正典）显式 `ListSpeedSearch.installOn(list)`。
2. **历史二级 SQL 列表**：`sqlList` 不再在字段初始化器安装；在 `showFileContent`（生产切卡）与 `loadFileContentSync`（测试缝）切卡可见后统一补装一次，`sqlSpeedSearchInstalled` 标记防重复（平台 installOn 对同一列表重复挂不幂等）。
3. 搜索条占位文案同步：二级不搜源文件，`log.history.search.empty`（"Search SQL or source file..."）改 `log.history.search.sql`（"Search SQL..."），zh/en 同步，bundle 键不新增不删除（仅改名+改值）。

## 四、实施记录

| 项 | 内容 |
|---|---|
| 提交 | 见分支 t-uinative 本次提交（修复+测试） |
| 主列表 | `SqlLogPanel.buildListPane()` 装 SpeedSearch（installOn 移出 SqlEntryList.init） |
| 历史二级 | `showFileContent`/`loadFileContentSync` 可见后装；`sqlSpeedSearchInstalled` 守卫 |
| 占位文案 | `log.history.search.empty` → `log.history.search.sql`（去「源文件」，en/zh） |
| 测试 | 新增 `HistorySqlLogPanelNavigationTest.二级SQL列表_安装速度搜索`（`SpeedSearchSupply.getSupply(comp, true)` 断言，两参版 bypass 单参的 `isPopupActive()` headless 过滤）；顺修既有 `test一级搜索过滤文件名` 的粘贴重复文件语句（与执行顺序耦合的假失败） |
| 门禁 | 全量 `--no-daemon` **4148/0/0/6** 绿（基线 4147） |

## 五、遗留说明

- 单参 `SpeedSearchSupply.getSupply(comp)` 在 headless 恒 null（含 `isPopupActive()` 过滤、无活动弹窗），安装断言须用两参 `getSupply(comp, true)`。
- 历史搜「源文件」能力极低频且与「SQL 含该串」高度重叠，本轮按用户意见移除占位提示；`applySqlSearch` 保留的 `entry.sourceFile?.contains` 分支（历史页特有检索目标，T-LogSearchRowTime 定案）**未删**——仅改了占位提示文案，未动搜索能力本身。若确认要去掉源文件检索能力，另行立项评估。