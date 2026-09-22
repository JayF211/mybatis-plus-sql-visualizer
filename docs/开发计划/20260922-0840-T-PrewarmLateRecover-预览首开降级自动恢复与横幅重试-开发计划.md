# T-PrewarmLateRecover 预览首开降级自动恢复与横幅重试 — 开发计划

- 日期：2026-09-22 08:40
- 任务编号：T-PrewarmLateRecover
- 来源：用户 2026-09-22 报障（预览顶部常驻降级横幅）→ 排查归入 [待分析问题清单](../验收文档/20260920-待分析问题清单.md) **P1 的残留路径**（P1 本体 2026-09-20 已修并复验 15/15 主路径，本次是「重负载 / 首次索引中的首开」这一条未覆盖场景被真实命中）
- 性质：可用性（不改渲染与执行语义；给降级链补一个「恢复」出口，而不是抬预算或加速预热）
- 实施分支：自 master 拉任务分支 `t-prewarm-late-recover`
- 验证基线：master `d3ef121`（2026-09-22 08:06）

---

## 一、背景与依据（现状核实）

### 1.1 现象与实测读数

沙盒 IU-2024.3.7.1（当轮 `runIde`，PID 7636）首开即降级，日志逐行如下：

| 时刻 | 事件 | 出处 |
|------|------|------|
| 08:22:35 | IDE 启动 | `AppStarter` |
| 08:23:01 | `Started scanning for indexing of mybatis-plus-sql-visualizer-demo`（116,870 个文件、11 个扫描线程） | `UnindexedFilesScanner` |
| 08:23:35,569 | `[T-PreviewFast] request→dialog: 335ms` | `SqlPreviewService` |
| 08:23:35,774 | `[M36] showSql branch=createEditor: len=109`（预热等待起算） | `SqlPreviewPanel` |
| 08:23:38,224 | `[M36] in-memory PSI preview document created (len=109)`（**超时降级 + 挂横幅**） | `SqlPreviewPanel` |
| 08:23:38,742 | `[M36] prewarm done in 3016ms`（**预热其实成功，晚到 518ms，无人消费**） | `SqlPreviewPanel` |
| 08:25:01 / 08:25:23 / 08:25:28 | 同会话复开：预热 216 / 226 / 152ms，**全部走主路径、无横幅** | 同上 |

2026-09-21 16:06 有同形态一次（预热 1347ms → 降级）。两个可下的结论：

1. **预热耗时 3016ms = 500ms 预算的 6 倍**，此前冷启首开样本只有 233/239/360ms，本次采到了分布尾部；正好命中 [预热耗时采样与预算建议](../验收文档/20260920-预热耗时采样与预算建议.md) §七「首次索引中的首开」这条**明示未覆盖**的场景。
2. 从超时（+500ms）到实际落内存 PSI 隔了约 2.4s——超时回调要经 `invokeLater` 回 EDT 排队，而预热窗口内平台 1.5s 无任何日志输出（EDT 被启动期负载占满）。**用户感知的延迟由 EDT 排队主导，不是预算本身**（与采样报告 §六 的推导一致，幅度更大）。

### 1.2 代码链路（逐处行号，master `d3ef121`）

| # | 位置 | 事实 |
|---|------|------|
| 1 | `SqlPreviewPanel.kt:542` `prewarmPreviewTempFile()` | 池线程建项目内临时文件 + VFS refresh + 方言映射 + PSI；调用点两处：`init`（`:537`）与 `clear()`（`:2548`） |
| 2 | `:666` `publishPrewarmProduct()` | **无条件** `prewarmedTempFile = product` + `await.complete(product)`；超时场景下 future 已由 `completeOnTimeout(null, 500)` 以 null 完成，这次 `complete` 无效——**产物只落在字段上，没有任何消费方** |
| 3 | `PrewarmAwait.kt:58-67` `scheduleOnce()` | `scheduled` CAS 保证一次性；`completeOnTimeout(null, timeoutMs)` 超时以 null 完成 → 回调走 `onFallback` |
| 4 | `SqlPreviewPanel.kt:840` `resolvePreviewEditorSource()` | 热态 `await.joinOrNull()`；冷态注册 `scheduleOnce` 后返回（本次不装配）；超时 → `onFallback` → `assemblePreviewEditor(sql, null)` |
| 5 | `:2838` `fallbackPreviewDocument()` | 第二级 = 内存 PSI（无 VFS 文件 → Database 插件解析不到 schema → 表/列上下文补全受限）+ 挂 `degradedPsi` 横幅（`:2841`）；第三级 = 裸 document + `degradedPlain`（`:2851`） |
| 6 | `:2559` `showSql()` → `:2643` `createEditor()` | **`createEditor` 只在 `editor == null` 时进入**；降级装配之后所有渲染都走 editor 分支的 `setText`，**降级编辑器永远不会被替换** |
| 7 | `:2745` | `degradationBanner.clear(Kind.DEGRADED)` 的**唯一站点**，在 `assemblePreviewEditor` 主路径分支内——所以降级后横幅在整个对话框生命周期内常驻，点「刷新」也不会掉 |
| 8 | `:2992-2999` `releaseEditor()` | 删本面板创建过的全部临时文件并置 `prewarmedTempFile = null`——**晚到的产物连文件一起被回收** |

### 1.3 为什么「重开就好」

重开预览 = 新面板 = 新一轮预热（热态 152~226ms，落在预算内）→ 主路径装配 → 横幅清除。横幅自述的「关闭并重新打开预览即可重试」属实，但它把一次本可自动完成的恢复外包给了用户。

---

## 二、目标与非目标

**目标**

- G1：超时降级后，**晚到的预热产物在用户未动手时自动换装**（文档从内存 PSI 换成项目内文件 + PSI），横幅随之清除。消除「必须重开」。
- G2：预热**硬失败**（无产物可等）时，横幅给出一次可点的「重试」，点了就重新臂预热、就绪后换装并清横幅。G1 覆盖不到这一类。
- G3：横幅文案与动作对齐——文案保留「重开即可恢复」作为兜底，但用户不必真的重开。

**非目标**

- **不抬 `PREWARM_WAIT_MS`**：采样报告 §5.2 已定「要覆盖重负载首开需 ≥1.5s」，代价是等待期普遍变长，本计划不追这个目标。
- 不优化预热内部耗时（VFS refresh / PSI 解析 / 方言映射）；不把预热提前到工程打开时启动（另有 IDE 启动开销 A/B 报告，启动期开销需另立评估）。
- 不动 P9 记录的 `releaseEditor()` 路径（同面板 attach→detach 后再装配必降级，在册不修）。
- 不改降级链层级本身（内存 PSI 仍是第二级，只是多了「升级」出口）。

---

## 三、方案

### F1（主线）迟到产物自动换装

1. `PrewarmAwait` 增加「消费侧是否已放弃」的观测口（`isDone() && joinOrNull() == null` 即可表达，不必新增状态字段；若要语义更直白，加一个 `timedOutOrFailed()` 只读方法）。
2. `publishPrewarmProduct()` 在 `await.complete(product)` 之后追加判定：若该次 `complete` 未生效（future 已以 null/异常完成）→ 回调面板 `onLatePrewarmProduct(product)`。线程：本方法跑在预热池线程，**必须经既有 dispatcher 回 EDT**（`SqlPreviewPanel.kt:560` 的 `ModalityState.defaultModalityState()`，写安全，勿用 `any()`——N1 的教训）并带 `previewDisposed` 守卫，与 `scheduleOnce` 回调同款。
3. 面板侧 `onLatePrewarmProduct(product)` 的换装判据（**全部满足**）：
   - 面板与项目未销毁（`previewDisposed == false` 且 `project?.isDisposed != true`）；
   - `editor != null`（降级装配已完成，说明确有一次超时）；
   - 当前文档 `===` 降级装配时创建的那一份（新增字段 `degradedPreviewDocument: Document?`，在 `fallbackPreviewDocument` 的两条分支里赋值，主路径装配成功时置 null）；
   - `editor.document.text == lastAssembledSql`（**用户未改动**；`lastAssembledSql` 在 `assemblePreviewEditor` 里记录）。**不要用 `Document.isModified()`**（见 §七 前提 4）。
   - 任一条不满足 → 静默保持现状（产物仍在 `prewarmedTempFile`，留给后续 `createEditor`），不报错、不写日志噪音。
4. 换装动作：先释放旧编辑器（照抄 `releaseEditor()` 的 `autoPopupDisposable` 释放做法，**但不删临时文件**）→ 调 `assemblePreviewEditor(sql, product)`（内部消费 `prewarmedTempFile`，成功分支会 `clear(DEGRADED)`）。实现上抽一个 `disposeCurrentEditorOnly()` 私有方法，`releaseEditor()` 与换装共用，删文件的部分留在 `releaseEditor()`。

### F2（覆盖硬失败）横幅「重试」动作

1. `DegradationBanner` 增加 `var onRetry: (() -> Unit)?` 与一个动作标签，**照抄同文件既有 `dismissButton` 的形态**（`JBLabel` + `HAND_CURSOR` + `MouseAdapter`），仅 `Kind.DEGRADED` 可见；文案走新 i18n 键（中英同步 + 过 i18n parity 守卫）。EAST 位已被 `dismissButton` 占用，实施时用一个 EAST 容器横排两个标签（或把动作并入 CENTER 文案尾部），按视觉定，不新造控件类型。
2. `SqlPreviewPanel` 在两条降级分支挂 `degradationBanner.onRetry = { retryPrewarm() }`；主路径清除横幅时一并清 `onRetry`。
3. `retryPrewarm()`：新建 `PrewarmAwait` 替换字段（**轮次令牌**：预热线程内以 `prewarmAwait !== await` 判定「本轮已被替换」，据此在下一个检查点放弃，避免 P9 记录的双开竞争）→ 调 `prewarmPreviewTempFile()` → 就绪回 EDT 换装、失败保留动作可再点。
4. 重试时若文档已被用户编辑：**把当前文档文本作为 `sql` 传给换装**（`assemblePreviewEditor` 会把该文本写进新文档），内容不丢、且新文档有项目文件背书。该口径是产品决策，见 §七 前提 5。

### F3 记录

把本次读数补进既有文档（`docs/验收文档/20260920-待分析问题清单.md` P1 条目 + 采样报告 §九 与变更记录），**不新建计划文件以外的文档**。见 §八。

---

## 四、验收标准

### 4.1 自动化（新增 `SqlPreviewPanelPrewarmLateRecoverTest`）

| # | 用例 | 判据 |
|---|------|------|
| 1 | 超时降级 → 迟到产物到达 | 编辑器文档落在项目内临时文件上；横幅已清；`editor` 字段换了实例（旧编辑器已释放，无 Editor 泄漏） |
| 2 | 用户已编辑（文本已变）→ 迟到产物到达 | 不换装、用户文本不丢、横幅保留。**反向验证**：临时去掉该守卫必须变红 |
| 3 | 预热硬失败 → 点横幅动作 | 动作可见 → 触发后重新预热 → 就绪换装 + 清横幅；失败则动作仍在 |
| 4 | 面板销毁后迟到产物到达 | 不装配、不抛、无 SEVERE |
| 5 | 正对照 | `===` 成立时仍走 `assemblePreviewEditor` 的项目文件分支（防降级路径测试「空绿」） |

### 4.2 真机走查（沙盒或真机，冷启首开 + 首次索引场景）

- 首开挂横幅后，**横幅应在数秒内自动消失**，无需关闭对话框；
- `.idea/sql-visualizer/preview/` 下出现本轮唯一名文件，关闭面板后回到空（回收语义不变）；
- 编辑区 SQL 与降级时逐字相同（换装不丢内容、不改文本）；
- 补全候选出现数据源列（表/列上下文确实恢复）——这是 G1 的真实收益，必须真机看，自动化只能断言「文档有本地文件」（见 §七 前提 2）。

---

## 五、风险

| 风险 | 处置 |
|------|------|
| 换装重建编辑器 → 光标/滚动/撤销栈丢失 | 自动换装只在「用户未编辑」窗口内发生，且落在打开后数秒内；点重试属用户主动行为 |
| 换装写文档的写安全 | 必须 EDT + 写安全模态：复用既有 dispatcher（已是 `defaultModalityState()`），不得引入 `any()` |
| 与 `clear()` / `releaseEditor()` 竞态 | 轮次令牌（`prewarmAwait !== await`）+ `previewDisposed` 守卫；**不在 `releaseEditor` 里重臂预热**（P9 记录的双开坑） |
| 自动换装判据漏掉「用户已输入」→ 丢内容 | 本任务最高风险项；判据里两条（文档身份 + 文本比对）都要有，并配用例 2 的反向验证 |
| 新增 UI 交互偏离既有形态 | 照抄同文件 `dismissButton` 实现；面板内嵌提示不属于弹窗，弹窗克制红线不受影响 |
| 新 i18n 键破坏守卫 | 中英同步，过 i18n parity 与 `DegradedBannerWordingGuardTest` |

---

## 六、实施顺序

F3（记录，已完成）→ F1（自动换装）→ 用例 1/2/4/5 → F2（横幅动作 + 重试链）→ 用例 3 → 全量测试 + 构建门禁（一次）→ 真机走查 → 回填 §八。

---

## 七、待验证前提（实施前先证伪）

1. **「超时后 `publishPrewarmProduct` 仍会把产物写进 `prewarmedTempFile`」**——读码成立（`:666-681`），但没有日志或断言坐实过；实施前先加一行临时日志或写单测确认。若这条不成立，F1 的输入根本不存在，整条路线要重定。
2. **换装后补全是否真的升级**（Database 插件按项目内文件解析 schema）——本环境数据源模型 `roots=0`（B 路径跑不起来），只能真机走查判定。
3. **`assemblePreviewEditor` 在已有编辑器时再入是否安全**——它会 `editorHolder.removeAll()` 并新建编辑器，旧编辑器必须显式 `EditorFactory.releaseEditor`，否则被平台报 "Memory leak detected"。实施前核实 `editor` 字段与释放的配对。
4. **`Document.isModified()` 不可用**（262 已从接口移除，改用 `FileDocumentManager.isDocumentUnsaved`）——本计划一律用文本比对，不引该 API；若实施时想用，先在 243/262 双平台核实。
5. **重试时文档已被用户编辑的处理口径**（本计划取「把当前文档文本作为 sql 传入换装」）——产品决策，实施前与用户确认一次；若改取「只清横幅不换装」，用例 3 的判据要改。
6. **`DegradationBanner` 加动作后既有测试仍绿**——`DegradationBannerTest`、三个 i18n 守卫、`DegradedBannerWordingGuardTest` 全部要在改动后跑一遍。

---

## 八、实施记录（实施后回填）

| 日期 | 事项 |
|------|------|
| 2026-09-22 | 立项：排查 2026-09-22 08:23 沙盒首开降级（预热 3016ms / 预算 500ms），归入 P1 残留；读数已补进 [待分析问题清单](../验收文档/20260920-待分析问题清单.md) P1 与 [采样报告](../验收文档/20260920-预热耗时采样与预算建议.md) §九（F3）。代码未改。 |
