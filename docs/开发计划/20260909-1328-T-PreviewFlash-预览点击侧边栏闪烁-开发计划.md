# T-PreviewFlash：预览点击侧边栏闪烁一次 根因分析与开发计划

> **创建日期**：2026-09-09
> **分支**：master（当前 HEAD `22eeff7`）
> **优先级**：P1（预览是插件最高频操作，每次点击伴随一次可感知闪烁 + 一批 SEVERE 错误日志）
> **预估工时**：5~6h（守卫实现与单测 2h + 慢操作两热点与方言短路 1.5h + 全量回归 0.5h + runIde 复现复验 1h + 文档 0.5h）
> **来源**：用户反馈原话——「目前点击 sql 预览的图标，插件侧边栏会闪烁一次，像是刷新，正常不应该出现这种情况」
> **与既有计划关系**：接续并收编 `20260908-1355-T-PreviewListReload-预览点击致SQL日志列表重载-开发计划.md`（计划定稿未实施）。本计划以其重放守卫方案为主修复；2026-09-09 沙盒日志分析补齐了该计划缺失的触发源实证，并新增两类同链缺陷（EDT 慢操作 SEVERE 风暴、方言映射每击重设）并入同批交付。

---

## 一、问题与症状

### 1.1 现场

用户在 IDE 中点击 SQL 预览图标（gutter 图标等入口），插件侧边栏「SQL 日志」工具窗口闪一下，观感像被刷新了一次。预览功能本身正常，但闪烁不应出现。

### 1.2 与 20260908 计划症状的关系

20260908 计划针对的是同一触发链的重载表现：点击预览 → SQL 日志列表历史条目重新灌入/成倍增长/徽章膨胀。本症状「闪烁一次」是同一根因链的轻量表现形态——当重放条目全部落入 60s 去重窗口（或去重开启）时列表内容不变，但重放风暴仍触发一轮 `addEntry → notifyChanged → refreshList`，列表组件整体重渲染一拍即「闪烁一次」；若排序激活则升级为 FullRebuild 全量重建（20260908 计划 §2.4）。因此主修复不变：**filter 层重放幂等守卫**。

---

## 二、根因分析（2026-09-09 沙盒日志实证）

### 2.1 日志样本

`IU-2024.3.7.1/log/idea.log`（2026-09-09 12:54 会话，项目 easy-code-demo，用户连点 4 次 gutter 预览，demo 未配数据源）：

| 点击 | 预览入口（injectTenantLine） | exec file 覆盖写 | dialect 重映射 | enter dumb | exit dumb | dumb 任务原因 |
|---|---|---|---|---|---|---|
| 1 | 12:54:34.645 | 34.993 | 35.011 | 34.998 | 35.558 | SqlDialectPusher push all + Push on VFS changes |
| 2 | 12:54:40.909 | 40.994 | 40.998 | 40.932 | 41.336 | 同上 |
| 3 | 12:54:43.253 | 43.301 | 43.305 | 43.275 | 43.600 | 同上 |
| 4 | 12:54:45.470 | 45.536 | 45.542 | 45.493 | 45.920 | 同上 |

三个硬结论：

1. **每次点击制造一个独立 dumb 窗口**（时长约 300~600ms），成对出现 `Push all on [com.intellij.sql.psi.SqlDialectPusher]` 与 `Push on VFS changes` 两个 dumb 任务。20260908 计划 §2.3 对「预览点击 → exitDumbMode → `ConsoleViewImpl.rehighlightHyperlinksAndFoldings()` 全文档重跑 filter」的触发源**推断**，其前置条件（dumb 窗口 + 窗口期内控制台文档变更）自此有日志实锤——真机上日志持续打印，dumb 窗口期内控制台几乎必然有新输出，rehighlight 命中。
2. **每次点击方言映射被重设一次**（`[M36] dialect pre-mapped to MySQL for mpsql-exec.sql` ×4）：`mapDialectBeforePsi`（DataSourceSelector.kt:2690）无条件 `setMapping`，同值映射也触发 SqlDialectPusher push all dumb 任务。
3. **每次点击伴随一批 EDT 慢操作 SEVERE**：合计 18 条（36 行日志），全部 `Plugin to blame: SQL Visualizer`，两个热点各 9 条：
   - `SqlPreviewPanel.createEditor$lambda$1$0`（SqlPreviewPanel.kt:1264 `refreshAndFindFileByIoFile` ×1、:1267 `PsiManager.findFile` ×8）→ `WorkspaceFileIndexDataImpl.ensureIsUpToDate` 断言（:153）。1267 是**主路径**：即使消费了预热产物，`findFile` 仍每次执行；预热线程新建临时文件制造 VFS 事件把 WorkspaceFileIndex 弄脏，EDT 上的 `findFile` 撞上脏索引即断言 + 同步等索引更新（EDT 卡顿）。
   - `DataSourceSelector.createTempPsiFile$lambda$0$0$0`（DataSourceSelector.kt:2621-2622）：`findFile` 已包在 `SlowOperations.allowSlowOperations(GENERIC)` + `ReadAction` 内**仍触发**——T-M36.6-03 注释声称逃生舱已消除该 SEVERE，实测对 WorkspaceFileIndex 断言无效（疑 `ensureIsUpToDate` 断言的 section 非 GENERIC，须经反编译定论，见 §3.2）。

### 2.2 症状闭环

每次预览点击 = 覆盖写 mpsql-exec.sql + VFS refresh + setMapping 方言重解析 → dumb 窗口 + WorkspaceFileIndex/索引弄脏 → ① 真机控制台日志流在 dumb 窗口期内变更 → exit dumb 时平台对整个控制台文档重跑 message filter（20260908 计划 §2.2 反编译实锤的平台契约）→ 插件 filter 不幂等 → 缓冲重灌/通知刷新 → 列表重载或闪烁；② EDT 上 4~5 条 SEVERE 断言各伴随一次同步索引等待 → 卡顿放大刷新的可感知度。沙盒会话无 Run 进程、无控制台捕获，故只见预览链副作用风暴；真机侧边栏闪烁 = ①②叠加。

---

## 三、修复方案

### 3.1 主修复：filter 层重放守卫（收编 20260908 计划 §三）

在 `LogFilterProxyHandler` 回调进 `processConsoleLine` 之前加重放守卫，依据两个反编译平台事实：`applyFilter(line, entireLength)` 的 `entireLength` 实为该行在整篇文档中的行尾偏移（`AsyncFilterRunner$HighlighterJob.analyzeLine`），流式单调递增；rehighlight 重放从第 0 行起步、不修改文档（modificationStamp 不变）。守卫四态判定（纯逻辑，独立可测）：

| 输入特征 | 判定 | 动作 |
|---|---|---|
| `endOffset > watermark` | 流式新增 | 处理，watermark 前移 |
| `endOffset ≤ watermark` 且文档 stamp 未变 | 平台重放 | 丢弃（计数 + debug 日志） |
| `endOffset ≤ watermark` 且 stamp 变、行文本 ≠ 当前行 0 文本 | 控制台重置（clear/截断） | watermark 重置后处理 |
| `endOffset ≤ watermark` 且 stamp 变、行文本 = 当前行 0 文本 | 重放叠加新增混合态 | 判定重放，丢弃 |

改动点（行号以当前 master 为准）：

1. `LogFilterProxyHandler`（log/LogFilterProxyHandler.kt:21-24）`applyFilter` 分支把 `args[1] as? Int` 与行文本一并回调，onLine 签名扩为 `(line, endOffset)`。
2. 新增 `ConsoleReplayGuard`（log 包独立文件，internal 纯逻辑类）：watermark + docStamp + 行 0 文本四态判定，返回密封结果 Process/Replay/Reset；不依赖平台类可直接 JUnit。
3. `BridgeLineContext`（SqlLogConsoleBridge.kt:530-538）追加 guard 字段；`attachFilter`（:261）成功路径反射缓存 `console.getEditor()`（public 反编译确认；runCatching 包裹，取不到则守卫降级停用＝现状语义，绝不阻塞捕获）。
4. 回调链接入守卫（SqlLogConsoleBridge.kt:317-319）：`processConsoleLine` 前先过 guard；重放丢弃累计计数 LOG.debug 输出（真机验证锚点）。
5. attachFilter 与 guard 的 KDoc 写明平台契约依据（rehighlight 机制 + 反编译出处），防后人误删。

备选与否决（沿用 20260908 计划 §3.3）：加大去重窗口（改 T-LogDedupeMpMark 语义、治标）、面板层 diff 剔除（误判风险）、预览链惰性化（M36.5 产品语义，用户要求预览阶段显示 DBN 工具栏）——均不动。

### 3.2 同批修复一：EDT 慢操作 SEVERE 两热点消除

先反编译定策（243 基线 jar，工具链见 `~/.zcode` 记忆 reference-fernflower-decompile）：`WorkspaceFileIndexDataImpl.ensureIsUpToDate`（WorkspaceFileIndexDataImpl.kt:153）断言的 section 与 `SlowOperations.assertSlowOperationsAreAllowed`（SlowOperations.java:114 一带）的判定逻辑，确认 `allowSlowOperations(GENERIC)` 压不住的原因。随后按结论执行：

- **createEditor 主路径**（SqlPreviewPanel.kt:1267）：预热产物已含 psi（`prewarmedTempFile = vf to psi`），消费预热时直接复用其 psi，不再重复 `PsiManager.findFile`——主路径 SEVERE 即消。
- **createEditor 预热竞态兜底**（SqlPreviewPanel.kt:1264）：兜底改为对预热产物有界等待（如 300ms），超时才走原同步路径（罕见竞态下接受一次性 SEVERE，有界等待远短于断言触发的同步索引等待）。
- **createTempPsiFile**（DataSourceSelector.kt:2621-2622）：按反编译结论处理——若存在可用的 section 包装则补正确 section；否则采用**跨点击缓存 vf+psi**（mpsql-exec.sql 路径稳定，`vf.isValid()` 守卫 + 失效回退全路径），把 `refreshAndFindFileByIoFile` + `findFile` 移出每次点击的热路径。
- 候选优化（视余量）：预热临时文件从每次面板新建改为项目级稳定文件，减少 VFS 事件源。

### 3.3 同批修复二：方言映射幂等短路

`mapDialectBeforePsi`（DataSourceSelector.kt:2690）改为插件侧内存缓存（project 级 vf→已映射语言 id）：缓存命中（已记录 MySQL）直接跳过 `setMapping` 与映射读取。不读平台 `getConfiguredMapping`——`PerFileMappingsBase.getNotInHierarchy → isInContent → ensureIsUpToDate` 与 SEVERE 同源，读映射本身就是慢操作；缓存失效场景（vf 缓存未命中）走现全路径。效果：消除每次点击的 `Push all on SqlDialectPusher` dumb 任务，dumb 窗口只剩 VFS push（守卫已兜底，不追求消除）。

### 3.4 API 权威核实清单（铁律：本地 jar 反编译为准）

```
com/intellij/execution/impl/ConsoleViewImpl        # rehighlight/exitDumbMode/flushDeferredText——20260908 计划 §3.4 已核
com/intellij/execution/impl/AsyncFilterRunner$HighlighterJob.analyzeLine   # entireLength 语义——同上
ConsoleView.getEditor()                            # public，守卫降级判定——同上
com/intellij/workspaceModel/core/fileIndex/impl/WorkspaceFileIndexDataImpl.ensureIsUpToDate(:153)  # 本次新增：断言 section
com/intellij/util/SlowOperations(:114 一带)        # 本次新增：allowSlowOperations(Section) 判定逻辑
```

---

## 四、实施步骤

1. 反编译核实 §3.4 新增两项，定 §3.2 createTempPsiFile 对策。
2. 新增 `ConsoleReplayGuard` 纯逻辑类 + 单测（先测后改，用例清单见 §5.1）。
3. `LogFilterProxyHandler` 扩签名；`BridgeLineContext` 挂 guard；`attachFilter` 缓存 editor；回调链接入守卫与丢弃计数日志。
4. §3.2 两热点消除 + §3.3 方言短路。
5. 全量回归（§5.2）。
6. runIde 沙盒复现与复验（§5.3）。
7. 文档同步（§七）；旧计划文件头部加一行「已被本计划接续」标注，随本计划一并提交。

---

## 五、测试与验收

### 5.1 单测

守卫：流式递增全 Process；纯重放（endOffset 回落、stamp 不变）全 Replay；重放叠加新增混合态区分（行 0 文本比对）；控制台重置 Reset；editor 不可得降级恒 Process 不抛异常；同一条 applyFilter 重放 N 次 buffer 仅 1 条目、entireLength 正确透传。
新增：方言短路（同 vf 第二次调用不触发 setMapping 反射；vf 缓存失效回退全路径）；createTempPsiFile vf 缓存命中/失效两分支。

### 5.2 全量回归

基线 3153/0/6（master `22eeff7`），新增用例净增；重点盯 SqlLogStreamingDiffTest、JdbcParser 回放、BridgeMarker 系列、T-JdbcMultiline pendingSource 状态机不回归。

### 5.3 runIde 真机验证

- demo 项目启动 → Run 应用灌日志 → 点击 gutter 预览：未修复基线观察列表重载/闪烁；修复版本列表纹丝不动，idea.log 出现重放丢弃计数，且点击时刻与 dumb 窗口退出对应（闭环触发源）。
- 预览链日志验收：点击后零新增 `Slow operations are prohibited`；`dialect pre-mapped` 仅首次出现；后续点击 dumb 任务只剩 `Push on VFS changes`。
- 262 对照：用户真机 2026.2.1 验证一次（243 反编译为编译基线权威，262 行为以真机日志为准）。

---

## 六、风险与边界

- 守卫降级路径（editor 反射失败）＝现状语义；行 0 误判概率与代价远小于整表重放（20260908 计划 §六已论）。
- vf+psi 缓存：文件被外部删除/清理时 `vf.isValid()` 失败回退全路径重建，不会卡死预览链；缓存按 project 隔离。
- 方言短路：仅作用于插件自有 mpsql-exec.sql，不影响用户手动切换其它 SQL 文件方言；映射读取绕开平台查询，无慢操作风险。
- 有界等待兜底：预热超时路径保留原同步逻辑，行为不劣于现状。

---

## 七、交付时同步项

- `docs/变更记录.md` 总册顶插（2026-09-09）。
- `docs/MEMORY.md` 变更记录行。
- `CHANGELOG.md` Unreleased Fixed 条目（用户视角一句：修复点击 SQL 预览时插件侧边栏日志列表闪烁/重载的问题）。
- 本文件补 §实施记录（测试计数、沙盒日志验收摘录、真机复验结论）。

---

## 实施记录

（实施后回填）

## 实施记录（2026-09-09）

**反编译核实（§3.4 新增两项）**：`SlowOperations.assertSlowOperationsAreAllowed` 的 `isSlowOperationAllowed()` 里，`ourStack` 遍历只能在 EDT 上压栈（`startSection` 首行 `if (!EDT.isCurrentThreadEdt()) return EMPTY`，不压栈）；而 `isAlwaysAllowed()` 在沙盒（`idea.plugin.in.sandbox.mode=true`）恒 false。故 EDT 上 `allowSlowOperations(GENERIC)` 是空壳——`ourStack` 为空即返回「判定慢操作」，SEVERE 必刷。逃生舱只对**非 EDT 线程**生效。结论：创建文件/刷新 VFS/findFile 必须移出 EDT（预热线程 + 有界等待 + 跨点击缓存），不能靠逃生舱在 EDT 上压制。

**代码改动**：
- 新增 `ConsoleReplayGuard.kt`（纯逻辑四态守卫）+ `ConsoleReplayGuardTest.kt` 6 用例。
- `LogFilterProxyHandler.kt` onLine 扩为 `(line, endOffset)`；`LogFilterProxyHandlerTest` 断言同步。
- `SqlLogConsoleBridge.kt`：`BridgeLineContext` 挂 `replayGuard`/`replayGuardEditor`/`docStampReader`/`line0Reader`/`replayDropCount`；`attachReplayGuard` 反射解析编辑器→文档→stamp/行0 读取器（任一环 runCatching 失败即降级）；回调链在 `docStampReader != null` 时才走守卫，否则直连现状语义。
- `SqlPreviewPanel.kt`：`createEditor` 弃 EDT 同步 VFS/findFile，改 `awaitPrewarmedTempFile()` 有界等待预热产物（预热线程后台完成 refresh+findFile）。
- `DataSourceSelector.kt`：`createTempPsiFile` 加 `cachedTempPsiFile` 跨点击缓存（vf.isValid 命中即复用）；`mapDialectBeforePsi` 加 `dialectMappedVf` 幂等短路。

**测试结论**：`ConsoleReplayGuardTest`（6）、`LogFilterProxyHandlerTest`、`SqlLogConsoleBridgeRealEnvTest` 全绿；全量回归 3173 全绿（此前多轮 `prepareTestSandbox` 文件锁与 `SqlDifferTest`/`UpdateSectionTest` 偶发失败均为并行会话互相抢沙盒容器/残留测试 JVM 持锁所致，清理残留进程后复跑转绿，与本次改动无关）。注：与并行会话 T-ExecCopyFix 共享 `DataSourceSelector.kt`（其 createTempPsiFile 冷路径后台化已吸收本方案的缓存字段，`cachedTempPsiFile` 设计意图被 T-ExecCopyFix F2c 注释承接）。

**runIde 真机复验**：留人工——真机点击预览，预期 idea.log 出现重放丢弃计数、零新增 `Slow operations are prohibited on EDT`、`dialect pre-mapped` 仅首次。
