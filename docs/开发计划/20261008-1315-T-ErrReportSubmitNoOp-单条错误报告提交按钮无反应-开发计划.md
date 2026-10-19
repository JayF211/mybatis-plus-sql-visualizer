# T-ErrReportSubmitNoOp：单条错误上报「提交」按钮无反应（批次容器越界 AIOOBE）——开发计划

> 编号占用：O-125 / R71 / D111~D112 / V135~V136（20261008 13:09 grep 实测：O 最高 O-124、D 最高 D110、V 最高 V134、R 最高 R70〔R100 系他处数字串，非本系列〕，本组五号在 `docs/` 全 0 命中）
> 来源：用户指令 2026-10-08 ——「我在日常 idea 里报告问题，点击确认没有反应，入口是 idea 提示的『插件可能会拖慢 idea』里的『报告问题』，现在分析问题原因生成开发计划文件」（附冻结通知与报告对话框两张截图）
> 前序：`fa1cde9`（2026-09-15 T-FeedbackBatch2，**缺陷引入笔**）、`c406063`/`d192216`/`9a5091f`（2026-09-14 T-FeedbackErr 提交状态机三轮真机走查）、`6600605`（2026-09-19 T-ErrorReportRedact，本文件最后一次改动）、`d85473c`（T-SyncK2JMaster176 译入笔，Java 端口侧同函数**已带界守卫**）
> 状态：计划待确认（用户点名的为「分析问题原因 + 生成计划文件」，未授权动工）

## 一、现象与实测证据

### 1.1 用户现场

- 入口：性能冻结通知「插件 'SQL Visualizer' 可能会减慢速度」→ 点「报告问题」。
- 落到的对话框就是本插件的 `ErrorReportDialog`（标题键 `error.report.dialog.title=报告到需求池`）：标题预填 `Freeze: Freeze in EDT for 70 seconds`、类型 `Bug`、分类 `其他`、描述区为冻结消息（`Freeze in EDT for 70 seconds` + `Sampled time: 37700ms, …`）、已勾选「附带异常消息全文」与「附带异常堆栈（.txt）」、堆栈预览有完整栈。
- 用户点「提交」：**按钮不转「提交中…」、底部状态行不出现任何文字、窗不关、无通知**——即 T-FeedbackErr 定论的「失败必须可见」完全失效，用户视角是「点了没反应」。用户连点了 5 次（见 1.2 时间线）。

### 1.2 用户机 idea.log 铁证（原始栈）

日志路径：`C:\Users\USER\AppData\Local\JetBrains\IntelliJIdea2026.2\log\idea.2.log`（分析时该文件尚为 `idea.1.log`，本 IDE 日志约 30 分钟一轮转，**按时间戳检索比按文件名可靠**）：

```
2026-10-08 13:02:06,775 [1131052] SEVERE - #c.i.i.p.PluginManager - No such child: 0
java.lang.ArrayIndexOutOfBoundsException: No such child: 0
	at java.desktop/java.awt.Container.getComponent(Container.java:352)
	at cn.song.mybatisplus.visualizer.feedback.ErrorReportDialog$Companion.selectedBatchItemsFor(ErrorReportDialog.kt:665)
	at cn.song.mybatisplus.visualizer.feedback.ErrorReportDialog.selectedBatchItems(ErrorReportDialog.kt:337)
	at cn.song.mybatisplus.visualizer.feedback.ErrorReportDialog.doOKAction(ErrorReportDialog.kt:321)
	at com.intellij.openapi.ui.DialogWrapper$OkAction.doAction(DialogWrapper.java:2126)
	at com.intellij.openapi.ui.DialogWrapper$DialogWrapperAction.actionPerformed(DialogWrapper.java:2077)
	at … javax.swing.AbstractButton.fireActionPerformed …
	at java.desktop/java.awt.EventQueue.dispatchEventImpl(EventQueue.java:732)
…
2026-10-08 13:02:06,777 SEVERE - #c.i.i.p.PluginManager - Plugin to blame: SQL Visualizer version: 2026.10.9
2026-10-08 13:02:06,794 SEVERE - #c.i.o.a.i.ExceptionsKt - IntelliJ IDEA 2026.2.3  Build #IU-262.10968.63 / JDK: 25.0.4 / OS: Windows 11
2026-10-08 13:02:10,665 WARN - Suppressed a frequent exception logged for the 2nd time: No such child: 0
2026-10-08 13:02:26,226 WARN - Suppressed a frequent exception logged for the 5th time: No such child: 0
```

要点：异常在 **EDT 点击处理器内**抛出（`DialogWrapper$OkAction` → `doOKAction`），未被任何 catch 兜住，直接外溢到 `IdeEventQueue` → 平台只记 SEVERE 日志。用户看不到任何提示，对话框原地不动 = 截图现场。三条 suppressed 计数说明用户点到了第 2 次、第 5 次，**每次点击都走同一条栈**。

### 1.3 行号自洽：发行 jar 与仓库源码同源

栈里的三个行号与当前工作树逐一对齐：`ErrorReportDialog.kt:321` = `selectedBatchItems(),`（`composeSubmission` 的第二个实参）、`:337` = `else selectedBatchItemsFor(`、`:665` = 头部复选框镜像行。另有佐证：

- 用户机已装版本 = `%APPDATA%\JetBrains\IntelliJIdea2026.2\plugins\mybatis-plus-sql-visualizer\lib\mybatis-plus-sql-visualizer-2026.10.9.jar`（20261008 12:43 落盘），与日志 `version: 2026.10.9` 一致；
- `git diff 2026.10.9 HEAD -- src/main/kotlin/cn/song/mybatisplus/visualizer/feedback/` 输出为空，`feedback/` 目录自 `6600605`（09-19）后未再改动。

即：用户遇到的就是仓库里的这份代码，不是历史版本或构建产物漂移。

### 1.4 触发条件精确刻画：**恰好 1 条**上报事件

```kotlin
// ErrorReportDialog.kt:610-613  渲染批次分节
internal fun applyBatchItemsToFields(fields: ReportFormFields, items: List<BatchSubmitter.BatchItem>) {
    fields.batchSection.removeAll()
    fields.batchSection.isVisible = items.size >= 2
    if (items.size < 2) return          // ← 单条：早退，batchList 保持 0 组件（T-FeedbackBatch2 契约）
    …
}
```

```kotlin
// ErrorReportDialog.kt:654-669  收集勾选（真凶）
val head = all.firstOrNull() ?: return emptyList()      // ← 只挡「0 条目」，挡不住「0 组件」
head.attachStack = headAttachStack
all.forEach { it.includeMessage = includeMessages }
(batchList.getComponent(0) as? JCheckBox)?.isSelected = headAttachStack   // :665  ← 容器空 → AIOOBE
return all.filterIndexed { index, _ ->
    (batchList.getComponent(index) as? JCheckBox)?.isSelected ?: false    // :667  ← 同一缺陷第二处
}
```

`Container.getComponent(int)` 在 `n >= componentCount` 时抛 `ArrayIndexOutOfBoundsException`；`:665` 是**裸调用**，后面的 `as?` 安全转换救不了它（`as?` 判的是类型，不是下标）。`:667` 同病。

于是「单条事件（最常见的单错误/单冻结上报）」的路径必然为：单条 → 不出分节 → `batchList` 空 → 收集时 `getComponent(0)` 越界 → 点击无反应。

不触发的三种情形（已逐一核对）：

| 情形 | 为什么不触发 |
|---|---|
| 同一波 ≥2 条事件 | 分节可见、`batchList` 有组件，索引合法（这正是它能通过既有测试的原因） |
| 手动形态（设置页「报告问题」按钮 / 帮助菜单 `FeedbackAction`） | `selectedBatchItems()` 首行 `if (prefill == null) emptyList()` 提前返回，不碰容器 |
| 事件数组为空 | `all.firstOrNull() ?: return emptyList()` 挡住 |

### 1.5 影响面与非回归

- **影响面（P0）**：本插件「报告到需求池」的**主路径整体不可用**——用户从 IDE 错误/冻结通知进来的单条上报，点提交永远没反应；多选/报告全部（≥2 条）与手动形态仍可用。
- **受影响的发行版**：缺陷笔 `fa1cde9`（2026-09-15）之后的全部 tag 都带它 —— `2026.9.18-beta.2/.3/.4`、`2026.9.23`、`2026.10.6`、`2026.10.7`、`2026.10.9`（`git tag --contains fa1cde9` 实测）。用户装的 2026.10.9 就是当前最新发行版，**Marketplace 上所有用户的这条路径都是坏的**。
- **为什么此前没暴露**：T-FeedbackErr 的三轮真机走查（`c406063`/`d192216`/`9a5091f`，09-14）都发生在缺陷引入笔（09-15）**之前**；引入之后本文件再无真机走查，只剩 headless 用例（09-19 的 `6600605`）。
- **无服务端副作用**：崩溃点（`:321`）在 `beginSubmitting()`（`:323`）与 `onSubmit(...)`（`:326`）之前，这一次点击没有发出任何请求 —— 未产生帖子、未消耗服务端「5 次/小时/IP」额度、未记 `ErrorFingerprint`（指纹记录在 `submit` 回调内）。副作用只有一处：`collectRequest()` 按求值顺序先执行，昵称/邮箱被写入 State（无害）。

## 二、根因

### 2.1 直接根因：容器索引无界 vs 渲染侧故意留空

`selectedBatchItemsFor` 假定「`all` 里的第 i 条在 `batchList` 里有第 i 个复选框」，这个不变量在**单条**时被渲染侧主动打破（T-FeedbackBatch2：单条 = 主预填自己，不出分节）。两个函数各自都对，组合起来必崩 —— 属典型的「契约只写在注释里、没有守卫」缺陷。

### 2.2 次生根因：组装段在 `runCatching` 之外 → 失败静默

`doOKAction` 只把 `onSubmit(...)` 包进了 `runCatching`（`:326`），而请求组装（`collectRequest` / `collectStackAttachment` / `selectedBatchItems` / `composeSubmission`，`:319-322`）**没有任何兜底**。任何组装期异常都直接抛到 EDT：平台记 SEVERE、用户零反馈、对话框僵在原地。这与 T-FeedbackErr 定论的「失败必须可见（状态行给原因 + 可原地重试）」直接冲突——本轮事故正是这条纪律的漏网处。若当时组装段有兜底，用户会看到红字原因而不是「没反应」，问题当场就能被报出来（而不是转成一个说不清的 70 秒冻结截图）。

### 2.3 测试为何没抓住（假绿形态：分支未交叉）

`ErrorReportDialogLayoutTest.kt` 三条相关用例各测一半，交集为空：

| 用例（HEAD 行号） | 覆盖 | 缺口 |
|---|---|---|
| `单条批次条目不出分节_两条起才显示`（318-329） | 断言单条时 `batchList.componentCount == 0` —— 把「空容器」**钉成了契约** | 只测渲染，不测收集 |
| `收集语义_头部堆栈随主开关_其余按复选框`（346-361） | 收集逻辑（3 条，容器有 3 个组件） | 容器恒非空 |
| `空条目收集返回空集`（363-371） | 空条目 + 空容器 | `emptyList()` 被 `firstOrNull()` 提前挡掉，**根本没走到容器访问** |

即「**单条条目 + 空容器**」这一组合零覆盖，而这恰是生产唯一会走到的形态。

### 2.4 顺带发现：Java 端口**没有**这个缺陷（端口行为分叉）

k2j 树 `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-kotlin-to-java` 的 `src/main/java/…/feedback/ErrorReportDialog.java:810-817`：

```java
if (batchList.getComponentCount() > 0 && batchList.getComponent(0) instanceof JCheckBox) {
    ((JCheckBox) batchList.getComponent(0)).setSelected(headAttachStack);
}
…
boolean checked = index < batchList.getComponentCount()
        && batchList.getComponent(index) instanceof JCheckBox
        && ((JCheckBox) batchList.getComponent(index)).isSelected();
```

守卫在译入笔 `d85473c`（T-SyncK2JMaster176）就存在，Kotlin 侧从未有过（`git log -S "batchList.getComponent(0)"` 只有引入笔 `fa1cde9` 一笔）——译入期的一次自主加固，反过来把 master 侧的真缺陷标了出来。端口侧测试树里有同名 `ErrorReportDialogLayoutTest.kt`（Kotlin 测试保留），但那三条用例的覆盖缺口**同样存在**（无「单条 + 空容器」组合），所以端口不是「靠测试保住的」，是「靠译入时的防御写法保住的」。

## 三、方案

### S1（D111）批次收集的容器访问全部带界（Kotlin 主修）

保持 T-FeedbackBatch2 语义不动（单条不出分节），只把「索引越界」这个不变量显式化。改 `ErrorReportDialog.kt:665/667`：

```kotlin
// T-ErrReportSubmitNoOp：单条（=主预填自己）不出分节 ⇒ batchList 为空，索引访问必须带界
// （真机 AIOOBE `No such child: 0` 根因；as? 安全转换判类型不判下标，救不了裸索引）
(batchList.components.getOrNull(0) as? JCheckBox)?.isSelected = headAttachStack
return all.filterIndexed { index, _ ->
    (batchList.components.getOrNull(index) as? JCheckBox)?.isSelected ?: false
}
```

语义核对（逐条对齐既有契约）：

- 单条：`head.attachStack` 仍按主表单开关回写；镜像复选框不存在 → 跳过（本就是「不出分节」的语义）；收集结果为空集 → `composeSubmission` 因 `selected.size < 2` 原样返回 → 落到 `BatchSubmitter.submit(submission, emptyList())`，而空列表正是文档化的「1 条 = 原样单帖」路径（`BatchSubmitter` 头注释表第一行）⇒ 用户勾选的「附带异常堆栈」由主表单 `collectStackAttachment()` 照常上传。
- ≥2 条：组件数与条目数相等，行为与改前逐字节一致。

**考虑过但否掉的替代方案**：单条时也给 `batchList` 加一个复选框。它会把「单条不出分节」的既有契约与三条用例一起推翻，并在用户看不到分节（`batchSection.isVisible = false`）的情况下留一个不可见的死控件；收益仅是掩盖越界。不做。

### S2（D112）组装段纳入「失败可见」

把 `doOKAction` 的组装段整体纳入兜底，异常一律落到状态行 + 日志：

```kotlin
val submission = runCatching {
    composeSubmission(
        FeedbackApi.Submission(collectRequest(), collectStackAttachment()),
        selectedBatchItems(),
    )
}.getOrElse { error ->
    LOG.warn("报告提交前置组装失败", error)          // 平台公开 API Logger；别引 262 Internal 类
    finishSubmitting(FeedbackApi.SubmissionOutcome(FeedbackApi.SubmitResult.Failure(
        MpSqlVisualizerBundle.message(
            "error.report.dialog.status.assembleFailed",
            error.javaClass.simpleName,
        ),
    )))
    return
}
```

- `finishSubmitting(Failure)` 复用既有状态机：按钮文案回到「提交」、`isOKActionEnabled = true`、状态行红字 + 复制按钮出现 —— 用户当场看见原因、可原地改内容重试、可点「转浏览器填写」兜底（正是 T-FeedbackErr 的设计）。
- 新增文案键 `error.report.dialog.status.assembleFailed=报告组装失败（内部错误）：{0}`（en: `Failed to assemble the report (internal error): {0}`），**必须 zh/en 双份**——仓内没有通用键集守卫（`BundlePlaceholderArityTest` 只覆盖 `rollback.*`），漏一份不会自动变红，实施时按清单核对。
- 不改 `MpSqlVisualizerBundle` 的 `messageForIde` 语义（新键只用于插件自身对话框，走 `message`）。

### S3 测试与反向验证

`src/test/kotlin/…/feedback/ErrorReportDialogLayoutTest.kt` 新增两条（第二条堵 2.3 的「分支未交叉」形态）：

1. `单条条目_空批次容器收集不抛异常且返回空集`（回归钉）：`selectedBatchItemsFor(listOf(item(0)), headAttachStack = true, batchList = JPanel())` → 断言返回空集、不抛异常，且 `items[0].attachStack == true`（头部回写仍生效）。
   **反向验证**：临时把守卫改回裸索引 → 必须红（贴 AIOOBE 读数），两次读数一并交出。
2. `批次容器与条目数任意组合收集均不抛`（矩阵）：条目数 0..3 × 容器组件数 0..3 全交叉（容器用手工塞 JCheckBox 的 JPanel 造），断言不抛异常且选择集只含「下标有勾选框且被勾」的条目；顺带覆盖「条目多于组件」这一旧代码同样越界的形态。

三条既有用例保持不动（`componentCount == 0` 那条继续钉「单条不出分节」的渲染契约，与本修不冲突）。

### S4 端口同步与 parity 注记

- 生产码**无需移植**（端口 `ErrorReportDialog.java` 已等价带界，见 2.4）；本笔只需把 S3 的两条用例搬到端口测试树，并在下次 K2J 同步的登记里注明「master 侧 `fa1cde9` 修复在 Java 端已等价存在、仅补测试」，防止将来按文件对账时误判为「端口漏译」而重复改写。
- 反向意义：本笔证实「译入期自主加固」会与 master 产生静默分叉。若采纳 S3 矩阵用例，两侧就会用同一份测试把这条分叉封死。

## 四、改动清单

| # | 文件 | 改动 |
|---|---|---|
| 1 | `src/main/kotlin/cn/song/mybatisplus/visualizer/feedback/ErrorReportDialog.kt` | S1：`selectedBatchItemsFor` 的 665/667 两处裸索引改 `components.getOrNull(...)`；S2：`doOKAction` 组装段纳入 `runCatching` + `finishSubmitting(Failure)`，加类级 `LOG` |
| 2 | `src/main/resources/messages/MpSqlVisualizerBundle.properties`、`MpSqlVisualizerBundle_zh.properties` | S2 新键 `error.report.dialog.status.assembleFailed`（两文件各一行） |
| 3 | `src/test/kotlin/…/feedback/ErrorReportDialogLayoutTest.kt` | S3 两条用例（回归钉 + 矩阵） |
| 4 | （k2j 树）`src/test/kotlin/…/feedback/ErrorReportDialogLayoutTest.kt` | S4 同款两条用例（生产码不动） |
| 5 | `docs/变更记录.md`、`CHANGELOG.md`、`docs/MEMORY.md` | 见 §七（实施后同步） |

落点：**master**（用户可见缺陷，master 即在售代码线）；dev 侧按既有 `T-MergeMasterToDev` 流程合流，dev→master 回流仍须用户明令。

## 五、待验证前提

把本计划里「推断而非实测」的部分显式列出，实施者先证伪再动手：

1. **发行 jar 与仓库同源**：目前用「栈行号 321/337/665 三处逐一对齐 + `git diff 2026.10.9 HEAD -- feedback/` 为空」判定，**未**解 jar 或 `javap -l` 逐行比对。风险低，但若要写进变更记录当结论，实施时顺手解一次 jar 校对。→ **【已实测，2026-10-08】**：解已装 jar 后 `javap -p -l -c` 反汇编，产物 line 665/667 处即裸 `Container.getComponent(int)`，结论成立（读数见 §八 产物级核验）。
2. **「单条必崩」是静态推演**：由 `applyBatchItemsToFields` 早退 + `selectedBatchItemsFor` 裸索引直接判定，**未**在沙盒里重放（平台冻结不容易按需复现）。实施时用 S3 用例正反两向直接证实（比真机复现更硬）；真机复验改用「任意单条插件错误上报」路径替代。
3. **入口链路**：本计划只坐实了「对话框已弹出、提交即崩」这一半；「冻结通知 → `errorHandler` EP → 本对话框」这半沿用既有结论（`IdeErrorReportSubmitter` 头注释：「Freeze 事件已被归属插件（真机截图实证），同通道可达」），本轮截图与预填内容（类名 `Freeze` + 冻结消息）与之一致，未另跑链路验证。
4. **Java 端口不崩**：静态读码判定（守卫在 810-817），**未**跑端口测试或真机。S4 搬用例后即自动坐实。
5. **服务端未受影响**：按「崩溃点在 `onSubmit` 之前」推断未发出请求；如需坐实，可在需求池后台按标题 `Freeze: Freeze in EDT for 70 seconds` 查一次确认无重复帖（可选，非门禁）。
6. **编号可用性**：O-125/R71/D111~D112/V135~V136 系本计划落笔时（13:09）grep 结论，同刻有并行会话在写计划文档，实施前需复核一遍（仓库高频并行推进）。

## 六、验收标准

- **定向（headless）**：`ErrorReportDialogLayoutTest` 全绿；两条新用例在场，其中回归钉做过「摘掉守卫 → 必红 → 装回 → 必绿」两向读数并贴出。
- **S2 语义**：构造一次组装期异常（测试注入或临时 throw），状态行出现红字 `报告组装失败（内部错误）：…`、按钮回到「提交」可再点、窗口不关；idea.log 有 `LOG.warn` 一条（含栈）。
- **真机（沙盒 runIde）**：单条插件错误 → 报告问题 → 提交 → 按钮转「提交中…」→ 成功后弹「已提交」通知（带 FR 编号）或失败时状态行给红字原因（两种情况都算通过，只要不再是「没反应」）；随后在沙盒 `idea.log` 检索 `No such child: 0` 应为 0 命中。
- **门禁分层**：定向测试 → `testFast` 冒烟；本笔触达生产码 + i18n 资源，收口按定例跑一次权威全量（单路分片，先 `timeout 10 docker info`）；端口侧 `compileJava` + 端口测试绿。
- **不劣化**：≥2 条批量提交、手动形态、附件上传与 `BatchSubmitter` 分流策略的既有用例全部维持绿（本修不应触及这些路径）。

## 七、文档同步（实施后）

1. `CHANGELOG.md` `[Unreleased]` 增一条用户可见修复：「修复单条错误上报点『提交』无反应（批次收集容器越界）」。
2. `docs/变更记录.md` 追加一笔（缺陷引入笔 `fa1cde9`、受影响发行版区间、修复笔）。
3. `docs/MEMORY.md` 索引 + 项目记忆（本缺陷的「假绿形态=分支未交叉」值得进 `pitfall-fake-green-test-assertions` 形态库；「译入期自主加固 = 静默分叉」值得进 K2J 同步档）。
4. 发行面：缺陷已随 `2026.9.18-beta.2` 起全部发行版外发，是否随即发补丁版（`v2026.10.10`？）**由用户拍板**；若发，按发行工作流 8 处同步 + `releaseGate`。
5. 需求池/官网：如做补丁版，发行说明里点明「此前版本请升级到 vX 后再上报」；已发出的 FR 无需回滚（本轮未产生帖子）。

## 八、实施记录

**落点**：分支 `t-errreport-submitnoop`，独立工作树 `mybatis-plus-sql-visualizer-errdlg`（自 master `6a53aff` 切出，2026-10-08）；依据＝用户「从主分支新建分支和工作树 实施」。

**提交**：
1. `7980ad3` `docs(plan): …` —— 本计划落库（计划笔，233 行）。
2. `ddf8717` `fix: T-ErrReportSubmitNoOp …` —— S1 + S2 + S3 + 文档同步（7 文件 +96/−6）。

**与计划的一致性**：S1/S2/S3 均按计划落地，无方案变更。S1 未触碰 T-FeedbackBatch2 的「单条不出分节」契约，只把容器访问改带界；语义核对＝单条收集为空集 → `BatchSubmitter.submit(submission, emptyList())` 正是其头注释表第一行的「1 条 = 原样单帖」路径，主表单「附带异常堆栈」照常上传（用户现场勾了堆栈，行为不变）。S2 复用 `finishSubmitting(Failure)` 既有状态机（按钮回「提交」可再点、红字原因、复制按钮出现、窗不关），未新增状态分支。S3 两条用例按计划新增，既有三条用例零改动。

**读数（两向）**：

| 轮次 | 命令 | 读数 |
|---|---|---|
| 定向（修复态） | `./gradlew test --tests "cn.song.mybatisplus.visualizer.feedback.*"` | **124 / 0 / 0 / 0**（8 类 4m24s；`ErrorReportDialogLayoutTest` 26 → 28 例，两条新用例在 XML 中实名可查） |
| 反向（临时还原裸索引，标 `RED PROBE`） | `./gradlew test --tests "…ErrorReportDialogLayoutTest"` | **28 例 2 失败**（BUILD FAILED 17s）：两条均为 `java.lang.ArrayIndexOutOfBoundsException: No such child: 0`，与生产日志异常**逐字同形**；红点恰落新增两条用例，其余 26 例保绿＝判别力定向而非整片炸 |
| 恢复（守卫装回） | 同上 | **28 / 0 / 0 / 0**（BUILD SUCCESSFUL 18s）；`git diff` 复核约定：`RED PROBE` 残留 0 处，生产 diff 只有两行守卫 + 组装段兜底 + `LOG` + 注释 |
| 冒烟 | `./gradlew testFast` | **3406 / 0 / 0 / 6**（266 类，BUILD SUCCESSFUL 52s）；`> Task :testFast` 无 UP-TO-DATE 标记、结果 XML mtime 与运行同刻 ⇒ 真跑非缓存回读（T-ClickPreviewCopyRestore 登记过的「testFast 首跑 UP-TO-DATE 假绿」形态已核） |

> 口径提醒：`./gradlew test … | tail -n` 的退出码取自 `tail` 而非 gradle，反向轮 `exit code 0` 是管道假绿（PIPESTATUS 定例在册）——判定一律读 `BUILD FAILED/SUCCESSFUL` 与结果 XML 计数，本轮反向轮的"红"即由此确认。

**产物级核验（把 §五「待验证前提 1」由推断升级为实测）**：解用户日用 IDE 已装的 `mybatis-plus-sql-visualizer-2026.10.9.jar`，`javap -p -l -c` 反汇编 `ErrorReportDialog$Companion.selectedBatchItemsFor`：

```
line 665 → offset 97  →  99: invokevirtual #531  // Method java/awt/Container.getComponent:(I)
line 667 → offset 238 → 241: invokevirtual #531  // Method java/awt/Container.getComponent:(I)
```

即**发行产物里这两处就是裸 `getComponent`**，与用户机日志的 `ErrorReportDialog.kt:665` 同源，可排除构建漂移或历史版本解释（`git diff 2026.10.9 HEAD -- …/feedback/` 为空是源码侧旁证，本行是产物侧直证）。

**未做 / 待办（如实）**：
- **真机 sandbox 走查未做**：单条插件错误 → 报告 → 提交的端到端按钮状态机推进、以及沙盒 `idea.log` 零 `No such child: 0` 检索，本机沙盒 GUI 自动化不可用（CUA 对 243/262 IDE 窗口点击按键不落地，与 T-ClickPreviewCopyRestore 二轮同因），留用户真机或改 pyautogui 路线（`mpsv_walk.py`）。headless 侧已用「生产同形异常 + 状态行纯函数」覆盖判据，但**按钮真按下去的链路**只能由真机证。
- **K2J 端口同步登记**：生产码无需移植（端口 `ErrorReportDialog.java:810-817` 自 `d85473c` 起已带 `getComponentCount()` 守卫），下次 T-SyncK2J 同步轮按「master 已等价、仅需搬本笔两条用例」处理，勿重复改写。
- **合 master 与补丁发行**：本分支未合 master（用户明令的分界线）；缺陷已随 `2026.9.18-beta.2` 起全部发行版外发，是否发补丁版由用户拍板（见 §七.4）。

