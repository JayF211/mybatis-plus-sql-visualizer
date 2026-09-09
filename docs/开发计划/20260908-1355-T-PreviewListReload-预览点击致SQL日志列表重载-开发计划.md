# T-PreviewListReload：预览点击致 SQL 日志列表重载 根因分析与开发计划

> **状态**：已由 `20260909-1328-T-PreviewFlash-预览点击侧边栏闪烁-开发计划.md` 接续收编（2026-09-09）——触发源推断已由该计划沙盒日志实证，重放守卫方案原样并入，实施随新计划推进。当前文件仅按要求补状态标注。

> **创建日期**：2026-09-08
> **分支**：master（当前 HEAD `4a15230`）
> **优先级**：P1（任何预览操作都触发，日志列表是插件最高频查看面；列表成倍增长伴随徽章膨胀与多行状态机污染）
> **预估工时**：3~4h（守卫改造与单测 2h + runIde 真机复现与复验 1h + 文档 0.5h）
> **来源**：用户反馈原话——「目前点击任何地方的预览 sql，插件侧边栏的 sql 列表都会重新加载」

---

## 一、问题与症状

### 1.1 现场

用户在 IDE（2026.2.1，插件安装版）中点击任意位置的 SQL 预览——gutter 图标、右键转换、日志列表双击弹窗，入口不限——右侧工具窗口的「SQL 日志」列表都会整体重新加载：历史条目重新灌入、列表内容成倍增长、⨉N 去重徽章数字无端变大。预览本身功能正常，但每次点击都伴随一次列表重载。

### 1.2 症状拆解

| 可观测表现 | 机制对应 |
|---|---|
| 列表历史条目重新出现/成倍增长 | 60s 去重窗口之外的控制台历史行被重新解析入库，追加在尾部 |
| ⨉N 徽章数字膨胀 | 窗口内的重复行走去重丢弃分支，`dedupeCounts` 每次 merge 累加 |
| 列表滚动/选区丢失、可见闪烁 | 排序激活时重放条目与原条目交错，差分无法增量，走 `applyFullRebuild` 全量重建 |
| JdbcTemplate 多行日志偶发重复条目 | 重放的 Preparing/Parameters 重新进入 T-JdbcMultiline 的 pendingSource 两态机 |

---

## 二、根因分析

### 2.1 插件侧实证：列表只有一条数据通道

「SQL 日志」列表的刷新函数 `SqlLogPanel.refreshList()`（SqlLogPanel.kt:865）只有两类触发源：面板自身的 UI 交互（过滤/排序/搜索/设置事件）和 `manager.addUiListener(uiRefreshListener)` 注册的缓冲变更回调（SqlLogPanel.kt:282/428）。前者全部由用户在面板内的显式操作触发，与预览无关——已逐一排除。

缓冲变更回调的唯一上游是 `SqlLogEntryBuffer.addEntry`（SqlLogEntryBuffer.kt:59，去重丢弃分支 :80 也通知），而它的**全仓唯一调用点**是控制台 filter 回调（SqlLogConsoleBridge.kt:317-319）：

```kotlin
LogFilterProxyHandler { line ->
    val context = consoleContexts[console] ?: return@LogFilterProxyHandler
    processConsoleLine(line, context, timePrefixCache, syncBufferConfig).forEach { buffer.addEntry(it) }
}
```

结论：点击预览后列表「重新加载」，必然意味着**控制台 filter 对已处理过的历史行被重新调用了一整轮**。预览链路本身不写缓冲（SqlPreviewService 只在 recordHistory 里读 snapshot 补耗时，SqlPreviewService.kt:1292-1296），面板也不会被重建（工具窗口 content 只在工厂创建一次）。

### 2.2 平台实证（243 反编译）：控制台 filter 会被全文档重跑

对编译基线 ideaIU-2024.3.7.1 的 `ConsoleViewImpl` 反编译（工具链见 §3.6），确认三个平台事实：

1. **全量重跑入口存在且是 public 稳定 API**：`ConsoleViewImpl.rehighlightHyperlinksAndFoldings()` 内部调用 `highlightHyperlinksAndFoldings(0, …)`——起始行硬编码 0，即对控制台整篇文档从第一行起重跑全部 message filter（含插件经 `addMessageFilter` 挂载的自定义 filter）。
2. **触发点不止一处**（反编译可见的调用方）：
   - `exitDumbMode`：进入 dumb mode 时记录文档 modificationStamp，退出时若 stamp 变了（dumb 窗口期内控制台有新输出）→ rehighlight；
   - `EditorColorsManager.TOPIC` 订阅：编辑器配色方案变更 → `updatePredefinedFiltersLater` → rehighlight；
   - 控制台组件重新入层级树（ancestorAdded）且预定义 filter 尚未计算时；
   - `DynamicPluginListener`：插件动态加载/卸载（沙盒 `-Didea.auto.reload.plugins=true` 常开）。
3. **常规打印路径也会回退全量**：`flushDeferredText` 里 `int startLine = lastProcessedOutput.isValid() ? … : 0`——RangeMarker 失效时同样从第 0 行重跑。

也就是说：**「filter 对同一行可能被调用多次」是平台设计契约**，rehighlight 机制的存在本身就是证明。JetBrains 生态里所有 ConsoleView Filter 实现都必须幂等——重跑应当产出与首次相同的结果而不是副作用。

### 2.3 触发链推断：预览点击如何命中 rehighlight

M36.5 起，每次预览展示都无条件执行 `prepareDefaultConsole`（SqlPreviewPanel.kt:699 → :467，统一入口 SqlPreviewService.showResult → SqlPreviewDialog）：反射创建 Database session、挂载 JdbcConsole，期间——

- 每次覆盖写项目内文件 `.idea/sql-visualizer/exec/mpsql-exec.sql` + VFS refresh + SlowOperations 逃生舱内 PSI findFile（DataSourceSelector.kt:2587 createTempPsiFile）；
- `SqlDialectMappings.setMapping` → pushAll/reparseFiles 方言重解析（M36.5.3，DataSourceSelector.kt 内 mapDialectBeforePsi）；
- RunnerLayoutUi 内容挂载/换入（attachResultsHook 的 contentAdded 监听，DataSourceSelector.kt:755-820）。

这些操作的共同副作用是**制造索引窗口与文档/VFS 事件**。应用运行中 SQL 日志持续打印，控制台文档在 dumb 窗口期内几乎必然变更——精确命中 §2.2 第 1 条 `exitDumbMode` 分支：退出 dumb → rehighlight → 整篇控制台重跑 filter。配色方案变更与插件自动重载分支则解释了「不开预览偶尔也会重载一次」的背景噪音。

> 这一段是触发源的**推断**，与 §2.1/§2.2 的实证不同层级。实证方案并入实施步骤（§四 步骤 1）：守卫自带重放计数日志，沙盒复现「点击预览 → idea.log 出现重放丢弃记录」即闭环。需要强调：**修复的正确性不依赖这一推断成立**——平台契约要求 filter 幂等，无论 rehighlight 由什么事件触发，插件都必须接得住。

### 2.4 症状闭环

重放一轮（rehighlight 从第 0 行顺序回调 applyFilter）→ `processConsoleLine` 把历史行当作全新输入重新解析 → 产出**新的 LogSqlEntry 实例** → `buffer.addEntry`：

- 去重开（默认）：60s 窗口内的行被丢弃但 `dedupeCounts` 累加（徽章膨胀）；窗口外的历史行**全部作为新条目追加到尾部**——列表成倍增长，用户感知为「重新加载」；
- 排序（DURATION_DESC）激活时重放条目与原条目交错，`diffVisible` 判定 FullRebuild → `listModel.clear()` + 分批重建——滚动与选区丢失，闪烁可见；
- JdbcTemplate 多行日志的 Preparing/Parameters 重放重新驱动 pendingSource 两态机，产生重复条目；
- 每行 addEntry 都 notifyChanged → `notifyUi` 合并为一次 refreshList，但快照已是翻倍后的列表。

三层叠加（触发源高频 + 平台全量重跑 + 插件不幂等）与用户描述完全吻合。

---

## 三、修复方案

### 3.1 思路：在 filter 层建立重放幂等，对齐平台契约

在 `LogFilterProxyHandler` 回调进 `processConsoleLine` 之前加一道**重放守卫**。判定依据来自 §2.2 反编译出的两个平台事实的组合：

- `applyFilter(line, entireLength)` 的 `entireLength` 实为**该行在整篇文档中的行尾偏移**（`AsyncFilterRunner$HighlighterJob.analyzeLine`：`endOffset = lineStart + lineText.length()` 后传入）——正常流式打印时单调递增，是天然的文档位置游标；
- rehighlight 重放从第 0 行开始，首个回调的 endOffset 必然**远小于**已处理水位线，且重放本身不修改文档（modificationStamp 不变）。

守卫三态判定（纯逻辑，独立可测）：

| 输入特征 | 判定 | 动作 |
|---|---|---|
| `endOffset > watermark` | 流式新增 | 处理，watermark 前移 |
| `endOffset ≤ watermark` 且文档 stamp 未变 | 平台重放 | 丢弃（计数 + debug 日志） |
| `endOffset ≤ watermark` 且 stamp 变了、行文本 ≠ 当前行 0 文本 | 控制台重置（clear/截断） | watermark 重置为 endOffset，处理 |
| `endOffset ≤ watermark` 且 stamp 变了、行文本 = 当前行 0 文本 | 重放叠加新增的混合态 | 判定重放，丢弃 |

混合态的区分读一次控制台首行文本（仅可疑分支才反射读文档，流式热路径零开销）。极端场景——控制台 clear 后新打印的第一行恰好与旧首行相同——会误丢一行，debug 日志可辨，属可接受代价（对照收益：根治整表重载）。

### 3.2 改动点

1. **`LogFilterProxyHandler` 透传 entireLength**（LogFilterProxyHandler.kt:21-24）：`applyFilter` 分支当前只取 `args[0]`，改为把 `args[1] as? Int` 一并回调给 onLine（签名扩为 `(line, endOffset)`）。
2. **新增 `ConsoleReplayGuard`**（log 包独立文件，internal 纯逻辑类）：持有 watermark、上次文档 stamp、守卫可用标志；`decide(endOffset, docStamp, lineText, line0Text)` 返回密封结果 Process/Replay/Reset。不依赖任何平台类，可直接 JUnit。
3. **`BridgeLineContext` 挂守卫状态**（SqlLogConsoleBridge.kt:530-538）：追加 guard 字段；`attachFilter`（:260）成功路径反射缓存 `console.getEditor()`（ConsoleViewImpl.getEditor 为 public 反编译确认；runCatching 包裹，取不到则 guard 降级停用——退化为现状语义，绝不阻塞捕获）。
4. **回调链路接入守卫**（SqlLogConsoleBridge.kt:317-319）：`processConsoleLine` 前先过 guard；重放丢弃时累计计数，LOG.debug 输出（真机日志验证锚点）。
5. **文档注释**：在 attachFilter 与 guard 的 KDoc 写明平台契约依据（rehighlight 机制 + 反编译出处），防止后人误删守卫。

### 3.3 备选方案与否决理由

- **加大去重窗口/全局指纹去重**：改变「60s 窗口语义」（T-LogDedupeMpMark 刚定的语义），且治标——重放还会污染多行状态机、白耗解析器吞吐。
- **面板层 diff 修复**（识别重放实例并剔除）：数据在入库前就该拦，面板层无法区分「真执行了第二次」与「平台重放」，误判风险高。
- **预览链路减负**（prepareDefaultConsole 惰性化）：M36.5 是用户明确要求的产品语义（预览阶段显示 DBN 工具栏），且触发源只是加速剂，平台重跑契约不因它消失。不动。

### 3.4 API 权威核实（反编译，243 基线）

```
com/intellij/execution/impl/ConsoleViewImpl（ideaIU-2024.3.7.1-win/lib/app-client.jar，fernflower 反编译）
  public void rehighlightHyperlinksAndFoldings()
    → highlightHyperlinksAndFoldings(0, expirableToken)   // 起始行 0 = 全文档
  private void highlightHyperlinksAndFoldings(int startLine, Expirable)
    → getHyperlinks().highlightHyperlinksLater(compositeFilter, startLine, endLine, token)
  flushDeferredText(): int startLine = lastProcessedOutput.isValid() ? … : 0
  exitDumbMode$lambda$0: if (myLastStamp != document.getModificationStamp()) rehighlightHyperlinksAndFoldings()

com/intellij/execution/impl/AsyncFilterRunner$HighlighterJob.analyzeLine（同 jar）
  int endOffset = lineStart + lineText.length();
  filter.applyFilter(lineText, endOffset);   // entireLength = 行在整篇文档中的行尾偏移

com.intellij.execution.ui.ConsoleView$Filter.applyFilter(String line, int entireLength)  // 平台 filter 契约
ConsoleView.getEditor()  // public，attachFilter 时反射缓存用于守卫降级判定
```

反编译命令模板与运行时要求（JBR 25）沿用 [[reference-fernflower-decompile]]。

---

## 四、实施步骤

1. 新增 `ConsoleReplayGuard` 纯逻辑类 + 单测（先测后改，见 §5.1 用例清单）。
2. `LogFilterProxyHandler` 扩回调签名；`BridgeLineContext` 加 guard 字段；`attachFilter` 缓存 editor（降级路径 runCatching）。
3. 回调链接入守卫 + 重放计数 debug 日志。
4. 全量回归（§5.2）。
5. runIde 沙盒复现与复验（§5.3）：先在**未修复基线**或守卫日志开启状态下点预览复现，idea.log 确认「重放丢弃 N 行」计数与症状同步消失，同时闭环 §2.3 的触发源推断。
6. 文档同步（§七）。

---

## 五、测试与验收

### 5.1 单测（ConsoleReplayGuard 纯逻辑 + 桥接层）

- 流式递增：endOffset 逐行上涨 → 全部 Process，watermark 正确前移。
- 纯重放：同批行 endOffset 回落、stamp 不变 → 全部 Replay，watermark 不动。
- 重放叠加新增：stamp 变、endOffset 回落、行文本 = 行 0 → Replay；行文本 ≠ 行 0 → Reset 并处理。
- 控制台重置（clear 后新日志）：stamp 变、endOffset 从小值起步、文本非行 0 → Reset，watermark 重置。
- guard 降级：editor 不可得（null）→ 恒 Process（现状语义），不抛异常。
- 桥接层：LogFilterProxyHandler 对同一条 applyFilter 重放 N 次 → buffer 仅 1 条目；entireLength 正确透传。

### 5.2 全量回归

基线 3112/0/0/6（master `2326fb4`），新增用例净增；重点盯 SqlLogStreamingDiffTest、JdbcParser 回放、BridgeMarker 系列不回归。

### 5.3 runIde 真机验证

- 复现：demo 项目启动应用 → 日志列表灌入历史 → 点击 gutter 预览/日志双击预览 → 未修复版本观察列表成倍增长；修复版本观察列表纹丝不动、idea.log 出现 `replay guard` 丢弃计数。
- 触发源实证：记录预览点击与丢弃计数的时间对应关系（预期点击后 0~2s 内出现，对应 dumb 窗口退出）。
- 262 对照：用户真机 2026.2.1 验证一次（243 反编译为编译基线权威，262 行为以真机日志为准；rehighlight 为 public 稳定面，跨版本机制一致）。

---

## 六、风险与边界

- **守卫降级路径**：editor 反射失败（非 ConsoleViewImpl 的罕见控制台实现）→ guard 停用，行为等同现状，不引入新风险。
- **行 0 误判**：clear 后新首行与旧首行相同会误丢一行，debug 日志可辨；概率与代价都远小于现状的整表重放。
- **cycle buffer/文档截断**：平台截短文档时 stamp 必变 → 走 Reset 路径，语义正确。
- **性能**：流式热路径只多一次整数比较；可疑分支才反射读文档，且重放 storm 恰是被消灭的对象。
- **多控制台**：守卫状态挂在 `BridgeLineContext`（每 console 独立），互不干扰，与 F-44-6 既有隔离设计一致。

---

## 七、交付时同步项

- `docs/变更记录.md` 总册顶插（2026-09-08）。
- `docs/MEMORY.md` 变更记录行。
- `CHANGELOG.md` Unreleased Fixed 条目（用户视角一句：修复点击 SQL 预览后日志列表历史条目重复灌入/成倍增长的问题）。
- 本文件补 §实施记录（测试计数、沙盒日志截图、真机复验结论）。

---

## 实施记录

（实施后回填）
