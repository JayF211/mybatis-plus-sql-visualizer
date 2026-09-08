# T-DsCover：双击预览无数据源时 Database 工具窗抢占侧边栏 开发计划

> **创建日期**：2026-09-08
> **分支**：master（当前 HEAD `466407a`）
> **优先级**：P1（无数据源环境下**每次**双击预览都会把 Database 工具窗拉到前台盖掉插件侧边栏，预览是高频动作，体验持续受损；用户原话「每次插件侧边栏都会被数据源侧边栏覆盖掉，导致体验很差」）
> **预估工时**：1~2h（改动集中在 DataSourceSelector 两个分支的条件门控与参数透传；runIde 真机人工验证另计）
> **来源**：用户反馈原话——「现在双击预览，如果idea还没配置数据源或数据源驱动没安装等问题，每次插件侧边栏都会被数据源侧边栏覆盖掉，导致体验很差，分析问题原因生成开发计划文件」。追问「这个问题是不是没修复」——确认：**从未修过**，本计划为该问题首次立项。

---

## 一、问题与症状

**症状**：IDE 尚未配置任何数据源（或数据源驱动未下载）时，每次双击日志打开 SQL 预览，Database 工具窗口都会被激活并覆盖插件自身的侧边栏工具窗。用户双击看的是 SQL，结果视线里的插件面板被 Database 面板顶掉，每双击一次发生一次。

**复现条件**（runIde 沙盒即可，无需真数据源）：
1. 打开一个没有任何数据源的项目；
2. 双击 SQL 日志条目打开预览弹窗；
3. Database 工具窗立即被拉起（若插件侧边栏与 Database 同条带且当前可见，则被覆盖）；
4. 关闭预览再双击另一条，再次被拉起——每次预览都触发。

**两种触发形态**：
- 形态一（无任何数据源）：Database 工具窗被无条件激活，插件侧静默（无错误提示）；
- 形态二（有数据源但驱动未下载）：错误对话框弹出（「数据源初始化失败…」），且 Database 插件原生 Download Driver 流程可能同时拉起数据源相关 UI。

## 二、根因分析

### 2.1 无数据源链路（主链路，全实证）

双击 → `SqlPreviewService.showResult:1340` → `SqlPreviewDialog:33` → `SqlPreviewPanel.showResult`。该方法是预览弹窗的总入口，末尾一行是无条件的默认控制台准备（SqlPreviewPanel.kt:699）：

```kotlin
// M36.5：默认发起原生控制台准备（Ultimate 即刻展示原生控制台；Community 静默回退）
prepareDefaultConsole()
```

`prepareDefaultConsole`（SqlPreviewPanel.kt:467-481）→ `SqlExecutor.prepareConsole:68` → `DataSourceSelector.prepareConsole:147`（`executeAfterPrepare = false`）→ `chooseAndPrepare:209`。关键分支在 DataSourceSelector.kt:265-272：

```kotlin
when {
    runners.isEmpty() -> {
        LOG.warn("[M36] runners empty -> open Database tool window")
        openDatabaseToolWindow(project)          // ← :268，无数据源时每次预览都执行
        onError?.invoke(MpSqlVisualizerBundle.message("execute.datasource.empty"))
        callback?.invoke(false)
    }
    ...
```

`openDatabaseToolWindow`（DataSourceSelector.kt:2700-2707）：

```kotlin
private fun openDatabaseToolWindow(project: Project) {
    runCatching {
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("Database")
            ?.show()
    }
}
```

`ToolWindow.show()` 会激活同条带工具窗（等价用户点击条带按钮）——这就是「覆盖插件侧边栏」的直接机制。

两点值得注意：
- `prepareDefaultConsole` 传 `onError = null`（SqlPreviewPanel.kt:479，注释「默认准备失败（无数据源等）静默——不打扰预览；执行时再走全链提示」）——设计意图是预览路径静默，但 `openDatabaseToolWindow` 在 `onError` 之前就执行了：静默了错误提示，没静默工具窗抢占，与设计意图自相矛盾；
- 共享控制台复用（`reuseSharedConsole:229`）在无数据源时必然未命中（`sharedConsole == null`），`getAttachDataSourceRunners` 反射返回空列表，路径直达 `runners.isEmpty()` 分支。

### 2.2 驱动缺失链路（副链路）

有数据源但驱动未下载时：`runners.size == 1 && shouldBeChosenIfAlone` → `runRunner:2523` 反射调用原生 `Runner.run()` 抛异常（典型为驱动类缺失）→ `Messages.showErrorDialog:2535`（`execute.datasource.failed` 文案，引导用户去 Database 工具窗手动下载驱动）。代码注释 :2533 已注记「原生 Download Driver 弹窗由 Database 插件触发」——原生流程自身还可能拉起数据源 UI，这层不受插件控制。

预览路径同样会走到这里：`prepareConsole`（executeAfterPrepare=false）在 last-used 静默复用（:252-263）与单数据源直选（:273-278）两个分支都调 `runRunner`。即 last-used 数据源驱动损坏时，**每次预览弹一个模态错误对话框**——与形态一同属「预览被打扰」，本计划一并处理。

### 2.3 为什么至今未修：设计史错位

`openDatabaseToolWindow` 是 M36.3 时代为**显式执行路径**设计的引导——用户主动点「执行」时无数据源，把用户带到 Database 工具窗配置数据源，这是合理 UX。M36.5 引入「预览即默认 prepareConsole」（M36.5.25g：用户明确要求预览阶段也显示 DBN 原生工具栏，故 `prepareConsole` 调用必须保留），该引导被隐式带进了预览路径：引导语义只适配低频显式动作，却被挂到了高频被动动作上。每次 `showResult` 都触发，即用户看到的「每次都覆盖」。

**设计约束（修复红线）**：`prepareDefaultConsole` 的调用不能删、成功路径不能动——有数据源时预览阶段必须照常内嵌原生控制台与 DBN 工具栏（M36.5.25g）。修复只作用于**失败路径**的抢占行为。

### 2.4 激活面排查结论

全库 grep `getToolWindow(`/`ToolWindow.show`/`.activate(`：插件侧唯一的工具窗激活调用就是 DataSourceSelector.kt:2705。`attachResultsHook:745+`（RunnerLayoutUi ContentManager 监听）只做结果区组件换入，无激活调用；`embedConsole` 为面板内组件操作。Database 插件自身在驱动缺失时的 UI 拉起属原生行为，插件侧无法拦截，列入不处理边界。

## 三、修复方案

### 3.1 原则

失败路径去抢占，成功路径零改动。判断依据就用现成的 `executeAfterPrepare`：true = 显式执行（用户主动跑 SQL，引导合理，保留现状）；false = 预览默认准备（被动动作，静默）。

### 3.2 改动点一：工具窗抢占门控（主修复）

`chooseAndPrepare` 的 `runners.isEmpty()` 分支（DataSourceSelector.kt:268）：

```kotlin
runners.isEmpty() -> {
    LOG.warn("[M36] runners empty (execute=$executeAfterPrepare)")
    // T-DsCover：仅显式执行引导开 Database 工具窗；预览默认准备静默（M36.5 预览路径 onError=null 的设计意图补全）
    if (executeAfterPrepare) openDatabaseToolWindow(project)
    onError?.invoke(MpSqlVisualizerBundle.message("execute.datasource.empty"))
    callback?.invoke(false)
}
```

不需要新参数——`executeAfterPrepare` 已在签名里且语义精确对位。显式执行路径（`selectAndExecute`）行为不变。

### 3.3 改动点二：预览路径 runRunner 失败去模态弹窗

`runRunner` 增加 `silent: Boolean` 参数（或直接传 `executeAfterPrepare`，见实施时取舍）：

- `silent = true`（预览默认准备路径）：失败仅 `LOG.warn`，不弹 `showErrorDialog`——预览是被动展示，模态对话框打断正在看 SQL 的用户；错误信息日志留痕即可，用户点执行时走显式路径仍会得到完整弹窗引导；
- `silent = false`（显式执行、runner 弹窗中用户主动选中）：保持现有 `showErrorDialog` 行为。

调用点三处同步：last-used 静默复用（:258，仅 prepareConsole 路径，传 silent=true）、单数据源直选（:276，按 executeAfterPrepare 传）、runner 弹窗选中（:289，用户刚做了主动选择，传 silent=false）。

附带优化（一并做）：silent 失败时清除 `LAST_RUNNER_KEY` 记忆（`PropertiesComponent.unsetValue`）——驱动损坏的 last 数据源若不清记忆，每次预览都失败一次并 warn；清掉后下次走 runners 分支，执行成功时会重新记忆。

### 3.4 可选增强（本次不做，留档）

预览面板空态轻量提示：`prepareDefaultConsole` 失败（onError 现被丢弃）后，在 `resultPanel` 空态文案附一句「未检测到可用数据源，执行功能需先在 Database 工具窗配置」+ 打开 Database 工具窗的文字链接（用户主动点击才拉起，不算抢占）。真正实现时注意只在**状态切换**时提示一次，避免每次预览重复刷。本次以最小改动为目标，此项留待用户反馈是否需要。

显式执行路径是否也降级为通知（非抢占式 notify + 超链接），同样留档待用户意见——本次保持现状。

### 3.5 不处理边界

- Database 插件原生 Download Driver 流程（`Runner.run()` 内部触发）不可拦截；
- Community 版 `ExecuteSqlSupport.isAvailable()` 早退路径已静默（:219-224），不受影响；
- 多数据源时预览弹 runner 选择框是 M36.5 既有设计（必须让用户选），保持。

## 四、实施步骤

1. **DataSourceSelector.kt**：
   - `runners.isEmpty()` 分支加 `if (executeAfterPrepare)` 门控（§3.2）；
   - `runRunner` 加 `silent` 参数，silent 分支只 LOG.warn；三处调用点按 §3.3 传参；
   - silent 失败清 `LAST_RUNNER_KEY`（§3.3 附带优化）；
   - 注释同步：`openDatabaseToolWindow` KDoc 补「仅显式执行路径调用」；`prepareConsole` KDoc 中「无数据源 → 打开 Database 工具窗口 + onError」改为「无数据源 → 显式执行路径打开 Database 工具窗口，预览路径静默 onError」。
2. **SqlPreviewPanel.kt**：无代码改动（`prepareDefaultConsole` 调用与 onError=null 保持——M36.5.25g 红线）。
3. **测试**（§五）。
4. **全量回归**：`parallel_tasks.py check` → `gradlew test`，3130 基线不回退。
5. **runIde 真机人工验证**（§五.3 清单）。

## 五、测试与验收

### 5.1 既有测试影响面

- `DataSourceSelectorIntegrationTest:207`（无数据源路径：断言 callback false + onError 非空）——不涉及工具窗断言，无破坏；
- `ExecuteSqlErrorHandlingTest:240`（插件不可用环境静默回退）——走 `isAvailable()` 早退分支，在门控之前，无影响。

### 5.2 新增 headless 用例

`openDatabaseToolWindow` 为 private 且操作真实 ToolWindowManager，headless 断言思路：`ToolWindowManager` 是项目级服务，测试中可 `project.replaceService(ToolWindowManager, mock)` 后调 `DataSourceSelector.prepareConsole`（无数据源项目），断言 mock 的 `getToolWindow("Database")` **未被调用**；对照调 `selectAndExecute` 断言**被调用**。结束后恢复服务（try/finally）。若 mock 替换在测试框架下不稳定，退化为弱断言（`getToolWindow("Database")` 为 null 且无异常抛出）+ 代码走查，弹窗静默逻辑以 runIde 人工验证兜底——按项目惯例（T-LogListFreeze04）headless 优先但不强求。

### 5.3 runIde 真机验收清单

1. 无数据源项目：双击预览 ×3（不同 SQL），Database 工具窗**不**被拉起，插件侧边栏保持可见；
2. 配置可用数据源后：预览照常内嵌原生控制台 + DBN 工具栏（M36.5.25g 回归——红线项）；
3. 无数据源 + 点执行按钮：Database 工具窗引导**保留**（显式路径行为不变）；
4. 驱动未下载的数据源（last-used）：双击预览**不弹**模态错误框，idea.log 有 `[M36] runRunner FAILED` warn；再点执行按钮：错误弹窗引导照常；
5. 多数据源：预览弹 runner 选择框行为不变。

## 六、风险与回退

- **M36.5.25g 回归风险**（最高关注）：改动全部位于失败分支（runners.isEmpty / runRunner.onFailure），成功路径（reuseSharedConsole 命中、session 建立、attachConsole、嵌入）一行不动；验收清单第 2 条专门回归。
- **显式执行语义**：执行路径引导保留，若用户后续反馈执行时也不想被抢，走 §3.4 通知降级，属增量改动。
- **last-runner 记忆清除的误伤**：驱动临时网络问题导致的失败也会清记忆，代价仅是下次多一步选择（执行成功即重新记忆），可接受。
- **回退**：单文件小改动，revert 即回旧版行为。

## 七、交付时同步项

- 本文档 §八 实施记录（实施日期、提交哈希、验证结论）；
- `CHANGELOG.md`（2026.9.8 或下一版本 Fixed 条目）；
- `docs/变更记录.md` 顶插一行；
- `docs/MEMORY.md` 变更记录行；
- runIde 真机验证清单更新（§5.3）。

## 八、实施记录

- **2026-09-08** 实施完成，fix `5e75c12`：
  - `chooseAndPrepare` 无数据源分支 `openDatabaseToolWindow` 改 `if (executeAfterPrepare)` 门控（SqlPreviewPanel/DataSourceSelector 注释同步）；
  - `runRunner` 加 `silent: Boolean = false`，预览路径三处调用点（last-used 静默复用 `silent=true`、单数据源直选 `silent=!executeAfterPrepare`、弹窗主动选中 `silent=false`）——silent 失败仅 LOG.warn + `PropertiesComponent.unsetValue(LAST_RUNNER_KEY)` 清损坏记忆，不弹模态错误框；
  - `PropertiesComponent.unsetValue` 定位：类在 `util-8.jar`（非 app-client），`javap` 核验存在 `public abstract void unsetValue(String)`；
  - 全量回归 3130/0/0/6 绿（基线不变，无新增测试用例——gate 逻辑属私有工具窗激活面，headless 断言成本高，留 runIde 真机验收）。
- **runIde 真机验收**（§五.3 五项清单）留人工跟进。
