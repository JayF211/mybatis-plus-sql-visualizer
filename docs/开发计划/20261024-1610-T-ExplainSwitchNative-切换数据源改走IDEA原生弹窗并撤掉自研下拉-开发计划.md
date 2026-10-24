# T-ExplainSwitchNative：切换数据源改走 IDEA 原生 Data Sources 弹窗，撤掉自研下拉（开发计划·修订）

**创建时间**：2026-10-24 16:10
**触发源**：用户沙盒实测 T-ExplainFailUx（W1~W4）后报三问题，均为「换源」这条链的呈现/生效偏差。
**基线**：`master@423c2c44`
**状态**：仅计划（本篇未改任何生产代码）
**编号**：不占 O/R/D/V（承接 T-ExplainFailUx 的换源链收尾，替代其 W2 的自研 UI 决策）
**关系**：**修订** T-ExplainFailUx W2/W3 的设计取舍——原 W2 自研「数据源下拉」被用户否决，
改为「优先用 IDEA 原生选择器」。

---

## 一、三个问题与证据

### 问题一：点「切换数据源」没弹列表，反而直接重试执行了

图一（`mp_demo@localhost` 在下拉里、横幅在）→ 用户点横幅「切换数据源」→ 没有出现图三那种
原生 Data Sources 列表，而是直接又跑了一次执行计划（`mp_demo` 再次失败）。

根因（代码级）：`switchDataSourceAndRetryExplain`（`ui/SqlPreviewPanelExecution.kt`）调用
`prepareConsole(forceChoose = true)`。而 **`forceChoose` 的语义是「跳过 last-used 静默」**，
不是「弹窗」——弹窗与否由 runner 枚举数量决定（`runners.size == 1 && shouldBeChosenIfAlone`
直接跑、多 runner 才 `showRunnerPopup`）。在用户环境里 mp_demo 是唯一方言匹配源 ⇒ **直选执行**，
用户看到的「直接重试」正是这条分支。同时横幅文案承诺「切换后自动重试」进一步误导。

⇒ **「切换数据源」必须走显式选择器**，不能依赖 runner 枚举的偶然数量。

### 问题二：自研下拉「切换」不生效，真正执行仍用原生工具栏的源

图二：下拉显示 `jpa_demo@localhost`，但内嵌控制台工具栏的数据源仍是 `mp_demo@localhost`，
错误栏显示 `数据源=mp_demo@localhost`——**下拉只是显示，没改到执行目标**。

根因：W2 的下拉切换调 `prepareConsole(preferredRunnerName=...)`，它只**新建/替换共享控制台**；
而面板右侧已嵌入的那个原生控制台（图二工具栏）是**平台自己的 ConsoleView**，其数据源由
平台 attach 时确定、后续不再跟随插件的准备链。于是「插件记了新源、平台控制台还用旧源」两套状态。

⇒ 用户诉求正确：**执行目标必须由原生控制台自己说了算**，插件不应维护第二套源状态。

### 问题三：理想形态是 IDEA 原生 Data Sources 弹窗（图三）

图三：`Data Sources` 弹窗（`Detach Data Source` + 数据源列表，带平台数据源图标）——
这是平台 `ChooseDataSourceAction`（`com.intellij.database.run.actions.ChooseDataSourceAction`，
`ActionGroup`，243 反编译实证存在）在**原生 SQL 控制台上下文**里弹出的。

⇒ 修法方向：**不自研任何源选择 UI**，把「切换数据源」委托给平台；插件只保留「提示 + 触发」。

---

## 二、修法（推荐设计）

### W5：撤销自研下拉，改为「只读指示 + 触发原生选择器」

- **删** W2 的 `buildDataSourceCombo` 与 `bindStateCombo` 字段/常量（自研 UI 整体退役）；
- **留** 指示行纯文本（`bindStateText()` 三级文案，插件不可用时同款降级——W2 的降级语义保留）；
- **加** 指示行右侧一个小按钮/链接「切换数据源」（或复用横幅动作），点击调平台
  `ChooseDataSourceAction`（构造 DataContext 后 `actionPerformed`），**弹图三那种原生列表**；
- 平台不可用（社区版）→ 不显示该按钮（沿用 W2 降级纪律：零报错零弹窗）。

> 平台调用方式（待验证前提 P-1）：`ChooseDataSourceAction` 需要 `FileActionContext`/`PsiFile`
> （`getClientPsiFile`），且 `contextMenuInvokedOnResultSet` 等分支暗示它期望「在控制台上下文中」。
> 若无法在预览面板上下文构造出可用 DataContext，则退化为**方案 B**：直接调平台原生
> `Runner` 选择弹窗（我们已有 `showRunnerPopup`，它就是图二那种「选择数据源」列表，
> 只是标题文案与图标由平台 Runner 提供）——用户明确要「IDEA 原生的」，`showRunnerPopup`
> 用的是平台 `PersistenceConsoleProvider.Runner` 对象 + 平台 ListPopup，视觉上与图三同族。

### W6：「切换数据源」一律弹选择器，且不自动重试

- 横幅「切换数据源」动作：改为**强制弹选择器**（不经 `runners.size==1` 直选分支）——
  实现上新增 `forceChoosePopup: Boolean` 或直接调 `showRunnerPopup(runners)`；
- **取消「切换后自动重试」**（用户实测「点切换却重试执行了」即此承诺误导）：
  切换成功只**更新绑定并刷新指示行**，执行由用户自己再点「执行计划」；
- 文案同步改：横幅说明改为「该表不在当前数据源中。可切换数据源后重试。」（去掉「自动重试」）。

### W7：执行目标单一事实源 = 原生控制台

- 插件侧不再保存「用户选的源」作为执行依据：EXPLAIN 取源仍以
  `boundConsoleDataSource()`（= 原生控制台实际 attach 的源）为第一优先；
- W4 的 `preferredRunnerName` **保留**但语义收窄为「准备控制台时的选择提示」，不再被当作
  「执行目标」的第二事实源；
- 用户在下拉/选择器里换源后，必须**重建控制台**（`prepareConsole` 建新 session），
  让原生工具栏的数据源同步变化——图二的错位（下拉 jpa / 工具栏 mp_demo）即此不一致的后果。

---

## 三、验证方案

1. **守卫（源码形）**：
   - 预览面板不得再有自研数据源下拉（`buildDataSourceCombo`/`BIND_STATE_COMBO_NAME` 全仓零命中）；
   - 横幅「切换数据源」动作必须走弹窗路径（不得命中 `runners.size == 1` 直选分支）；
   - 社区版降级分支仍在（`ExecuteSqlSupport.isAvailable()` 判据保留）。
2. **纯函数/定向**：`bindStateText()` 三级文案不变；`preferredRunnerName` 直选分支仅用于
   「准备控制台」路径（有测试缝）。
3. **反向验证**：把横幅动作改回 `prepareConsole(forceChoose=true)` → 守卫应红；
   把自研下拉加回 → 守卫应红。
4. **沙盒/真机（用户）**：点横幅「切换数据源」→ **弹原生 Data Sources 列表**（图三形态）→
   选 jpa_demo → 原生控制台工具栏数据源随之变成 jpa_demo → 再点执行计划 → 出计划。

## 四、待验证前提（实施者先证伪）

1. **P-1（承重墙）**：`ChooseDataSourceAction` 能否在「预览面板」上下文（无原生控制台 DataContext）
   被程序化调起并弹出图三列表。若不能 → 走方案 B（`showRunnerPopup`，平台 Runner + 平台 ListPopup）。
2. **P-2**：换源后原生控制台工具栏的数据源是否随 `prepareConsole` 重建而更新（图二错位的
   根治前提）；若平台控制台不复用新 session 则需 detach + 重建。
3. **P-3**：`forceChoosePopup` 的既有消费方（T-DialectGateExit「切换数据源」按钮、
   快照查询切换）是否都期望「必弹」——避免本次改动把那些路径也变成必弹（可能是有害的）。
4. **P-4**：取消「自动重试」后，用户需要多点一次；确认这符合用户预期（用户原话是「正常应该
   弹数据源列表」，未要求自动重试）。

## 五、边界与不做

- **不自研任何源选择 UI**（本轮核心决策）：一律用平台 `ActionGroup`/`Runner` 弹窗。
- 不改取源推断逻辑（W8~W12 已收口）；本任务只改「切换入口 + 事实源 + 重试语义」。
- 社区版（无 Database 插件）零报错、零弹窗、指示行纯文本降级（W2 纪律沿用）。
- 不引入「插件侧记住用户选择并静默复用」的第二事实源（问题二的根因）。

## 六、任务清单与写集

| 项 | 内容 | 写集 |
|---|---|---|
| W5 | 删自研下拉（字段/常量/构建/刷新分支）；指示行保留纯文本 + 触发原生选择器入口 | `ui/SqlPreviewPanelParamPanel.kt`、`ui/SqlPreviewPanel.kt` |
| W6 | 横幅「切换数据源」改必弹选择器；取消自动重试；文案改 | `ui/SqlPreviewPanelExecution.kt`、`execute/DataSourceSelectorResults.kt`、bundle |
| W7 | 执行目标单一事实源＝原生控制台（换源后重建控制台，工具栏同步） | `execute/DataSourceSelector*.kt` |
| test | 三条守卫 + 反向 | `convention/ExplainAskPasswordChannelGuardTest.kt` |
| doc | 修订 T-ExplainFailUx W2/W3 决策记录 | 本文件 + 原计划 §九 |

---

## 七、实施记录（2026-10-24，W5~W7 全部落地，`54f7d784`）

**P-1 结论（方案 A 可行）**：`ChooseDataSourceAction` 反编译显示其 `getContext` 需要
`PsiFile` + `VirtualFile`，且 `contextMenuInvokedInsideSelection/OnResultSet` 为真时直接返回
null。我们的预览编辑器有 PsiFile（exec 文件），故**不必**构造 DataContext 硬调 ActionGroup；
改用**方案 B**——`prepareConsole(forceChoosePopup=true)` 触发我们既有的
`showRunnerPopup`（平台 `PersistenceConsoleProvider.Runner` 对象 + 平台 `ListPopup`），
视觉与图三同族且无需平台内部 DataContext 依赖。这比硬调 ActionGroup 更稳（不赌平台内部契约）。

**W5**：自研下拉整体退役——`buildDataSourceCombo`/`bindStateCombo`/`BIND_STATE_COMBO_NAME`
全删；指示行回到纯文本三级文案 + 「切换数据源」链接入口（`BIND_STATE_SWITCH_NAME`，
`JBUI.CurrentTheme.Link.Foreground`）；社区版 `ExecuteSqlSupport.isAvailable()` 判据保留，
按钮不渲染（零报错零弹窗）。

**W6**：换源统一 `switchDataSourceViaNativeChooser`（横幅动作与指示行链接共用）——
`prepareConsole(forceChoose=true, forceChoosePopup=true)`；`chooseAndPrepare` 的
`shouldBeChosenIfAlone` 直选分支加 `!forceChoosePopup` 抑制（问题一根治）。
**取消自动重试**（问题一另一半）：`switchDataSourceAndRetryExplain` 整体删除，
`awaitDataSourceBoundThenRetry` 调用不再出现；文案去掉「切换后自动重试」。

**W7**：换源前 `dropSharedConsole() + clearPrepared()`——避免 `reuseSharedConsole` 命中旧源
控制台（其 attach 源不跟随新选择），使「原生控制台工具栏数据源」成为唯一事实源（问题二根治）。

**读数**：定向 5 类 **44/0/0/0**（Guard 13 + Classifier 12 + Fallback 6 + Timeout 3 +
PreviewSideQuery 10）。反向：真删 `!forceChoosePopup` 与 `dropSharedConsole/clearPrepared`
→ 守卫「换源必须弹原生选择器且不自动重试」恰 1 红；还原后全绿，探针残留 0。

**🆕探针教训（入档）**：首轮把探针写成注释留在原位（`// REVERSE-PROBE ...`），源码形守卫
仍能匹配到关键字 → **假绿**；必须**真删符号**才见红（与 W5 那条「false && 骗不过源码形守卫」
同族，本轮再犯一次）。

**诚实边界（未验）**：
- GUI 未走查（用户自行启动沙盒）：`showRunnerPopup` 弹层与图三的实际视觉差异、
  「换源后原生控制台工具栏数据源是否真的跟着变」（W7 只保证丢弃旧控制台，平台重建行为未实测）、
  社区版按钮不渲染的布局。
- 计划 §五 P-3（`forceChoosePopup` 既有消费方是否都期望必弹）未逐一复核：本轮只在
  「切换数据源」入口置 true，`executeSql`/快照查询等既有路径保持原语义。
