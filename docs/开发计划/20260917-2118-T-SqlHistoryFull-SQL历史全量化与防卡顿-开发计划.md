# T-SqlHistoryFull SQL 历史全量化与打开防卡顿 — 开发计划

- 日期：2026-09-17 21:18
- 任务编号：T-SqlHistoryFull
- 来源：用户功能调整——SQL 历史目前只记录「从列表淘汰的 SQL」，希望改为记录全量 SQL；反复强调性能敏感，SQL 过多时打开历史不能卡顿
- 定性：**功能语义调整 + 读侧性能加固**（写侧复用现有异步批量链路，读侧补齐历史面板缺失的分批/流式防线）

---

## 一、需求

现状行为：SQL 主列表（SqlLogEntryBuffer，默认上限 200）满了之后，被淘汰的最旧条目才进入磁盘 SQL 历史（`onEvicted` → `SqlLogAutoSaver.submit`）。手动删除单条、清空列表、以及从未达到上限的会话，SQL 都不会进历史。

期望行为：SQL 历史包含**全量 SQL**——所有进入列表的 SQL 都记录到历史，不再只在被淘汰时记录。

边界语义（延续列表既有定义，不新发明）：
- 「全量」= 所有**成功入队列表**的 SQL（paused 暂停态、filterKeywords 命中过滤、去重窗口内重复丢弃的条目不回调成功入队，自然不进历史）；
- 去重窗口外第一次出现的相同 SQL 会各自进历史（去重只在列表层折叠，历史仍全量留存——这正是「历史全量」与「列表去重」的差异所在）；
- 手动删除/清空列表不再导致条目丢失（此前这两条路径直接丢弃，全量化后天然修复）。

## 二、现状链路（读码实测）

写侧（进历史的唯一出口）：

1. `SqlLogEntryBuffer.addEntry`（`SqlLogEntryBuffer.kt:107`）：成功入队走 `onEntryAccepted`（`SqlLogEntryBuffer.kt:132`，当前无条目参数，仅作「有新 SQL」即时通知）；超过上限淘汰走 `onEvicted`（`SqlLogEntryBuffer.kt:131`，带 `LogSqlEntry` 参数）。
2. `SqlLogManager.applySettings`（`SqlLogManager.kt:443-450`）：`logAutoSaveEnabled` 开启时把 `buffer.onEvicted = { entry -> autoSaver.submit(entry) }`，关闭时置 null（**关闭自动保存则历史完全不记录**——此为既有开关，语义保留）。
3. `SqlLogAutoSaver.submit`（`SqlLogAutoSaver.kt:58`）：投递无锁队列 → 专用后台线程批量写盘（T-Perf-12，风暴期 N 条 = 1 个写任务、整批一次 flush）。写盘已是异步批量，风暴期安全，无需改。

读侧（历史打开性能关键）：

4. `HistorySqlLogPanel.showFileContent`（`HistorySqlLogPanel.kt:290`）：读盘已后台化（Task.Backgroundable），但结果回来后 `applySqlSearch()`（`HistorySqlLogPanel.kt:438`）在 EDT **全量同步**填 `DefaultListModel`——`sqlListModel.clear()` + `forEach { addElement }`，几百上千条 = 上千次模型变更事件 + 布局失效。
5. `SqlLogFileReader.readFromFile`（`SqlLogFileReader.kt:61`）：`file.readText()` **一次性整文件读入 String**，再 `parseContent` 全量建 `List<LogSqlEntry>`。自动保存单文件滚动阈值 100MB（`SqlLogAutoSaver.kt:31`），即单文件读入内存峰值可接近文件整体。
6. 一级文件列表计数 `countInFile`（`SqlLogFileReader.kt:79`）已有 lastModified+length 缓存（`countCache`），低损耗，保留。

对照（实时面板已有、历史面板缺失的范式）：

- `SqlLogPanel.applyFullRebuild`（`SqlLogPanel.kt:1203`）：`list.ignoreRepaint=true` + 首屏 30 条 + 剩余每 30 条 `invokeLater` 分块 EDT 追加（`REFRESH_FIRST_BATCH_SIZE=30` / `REFRESH_CHUNK_SIZE=30`，`SqlLogPanel.kt:109,112`）+ generation 守卫。历史面板没有这套分批/ignoreRepaint/增量机制——这是「历史全量化后条目巨多」打开卡顿的真正落点。

高亮专项调研结论（独立子调研，供本计划定调）：

- 两面板均非编辑器，是 `JBList` + 自定义 cell renderer，SQL 用 `SimpleColoredComponent` 片段渲染（`SqlListFragmentRenderer` 自研轻量 tokenizer，`SqlListFragmentRenderer.kt:49`）。
- **瓶颈不在高亮渲染**：JBList 渲染是 Swing 虚拟化的，只对可视区行调用 renderer；高亮成本 O(可视行数)，与 DefaultListModel 总量无关。
- **改用 IDEA 原生 SQL 高亮不会优化性能**：平台没有「可被列表 cell 复用的高亮 renderer」现成 API；换原生只能替换词法器来源，渲染管线、填充逻辑、虚拟化行为完全不变，且引入 Database 插件依赖（与 `SqlListSyntaxHighlighter.kt` 现有「缺席环境也有高亮」的刻意设计冲突）。
- 结论：**本项目不采纳「改原生高亮」路线**，性能优化聚焦读侧加载（第 4、5 点）。

## 三、方案设计

核心分两段：写侧「入队即写」实现全量；读侧补齐分批 + 流式防卡顿。

### 写侧（P0）：入队成功即写历史，替换「仅淘汰写」

1. `SqlLogEntryBuffer` 新增带条目参数的成功入队回调（`onEntryAccepted` 现无参数，写盘需要条目）：

```kotlin
/** 条目入队成功回调（非暂停/过滤/去重丢弃）——历史「全量」写入出口，带条目标。</param> */
var onEntryStored: ((LogSqlEntry) -> Unit)? = null
```

在 `addEntry:131-132` 成功入队路径（`synchronized` 外，与 `onEvicted` 同位置）调用 `onEntryStored?.invoke(addNumbered 后的实例)`。保留 `onEntryAccepted`（自动展开的「有新 SQL」即时语义）不动，避免影响 T-PerfBatch 分层通知与既有测试。

2. `SqlLogManager.applySettings`（`SqlLogManager.kt:445-450`）切换写入来源：开启自动保存时

```kotlin
buffer.onEntryStored = { entry -> autoSaver.submit(entry) }
buffer.onEvicted = null
```

关闭时两者都置 null。`autoSaver` 的异步批量写链路原样复用（`submit` → 队列 → 写线程），**写侧性能结构不变**，只是每条成功入队都会触发（与实时列表内容节流解耦——历史写盘是独立后台线程，不阻塞）。

3. 关于「被淘汰条目重复写」：入队成功已写过，淘汰后再经 `onEvicted` 写会重复。故 `onEvicted` 置 null；`onEvicted` 通道与 `onEntryStored` 在 `SqlLogManager` 侧互斥装配（一次只挂一个），避免同一会话同时从两个钩子写盘。

### 读侧（P0/P1）：历史面板打开防卡顿

4. `HistorySqlLogPanel.applySqlSearch`（`HistorySqlLogPanel.kt:438`）改为复用实时面板的分批 EDT 追加范式：
   - `sqlList.ignoreRepaint = true` 批量抑制布局（T-LogListFreeze-01 同款）；
   - 首屏 30 条立即写入 + 剩余每 30 条 `invokeLater` 分块追加（复用 `REFRESH_FIRST_BATCH_SIZE` / `REFRESH_CHUNK_SIZE` 同值常量，或历史面板自持同名常量）；
   - gen 代际守卫（复用 `bgGeneration`）——新的加载/搜索/刷新启动时终止旧分块链，防止幽灵条目；
   - 纯搜索过滤也走同一条分批路径（搜索本身每击键 O(N) 已有 `SEARCH_DEBOUNCE_MS=200` 防抖，`HistorySqlLogPanel.kt:93`，保留）。

5. `SqlLogFileReader.readFromFile`（`SqlLogFileReader.kt:59`）`readText()` 改流式逐行：用 `file.bufferedReader().useLines` 代替一次性整读，块解析逻辑（`parseContent:109`）保持头行识别不变，减少大文件读入内存峰值；`parseContent` 本身逐行 + `flushBlock` 已是流式友好的，只需把输入源从整 String 换成逐行消费（新增 `readFromFileStream`/改造 `parseContent` 接受 `Sequence<String>`，保留原 String 重载供现有测试）。

6. 一级文件列表 `countInFile` 缓存保留（`countCache`，`SqlLogFileReader.kt:70`）——大文件计数的低耗防线，不动。

### P2：历史读侧上限防线（防单文件无限增长恶化打开）

全量化后历史文件条目量级上台阶。设置项与面板已有「自动保存」开关与目录/文件名模板（`PluginSettings.logAutoSaveEnabled` / `logAutoSavePath` / `logAutoSaveFileNamePattern`，`PluginSettings.kt:231,237,243`），**不新增条数上限设置**（用户要「全量」，条数上限与意图冲突）——以现有 100MB 文件滚动（`SqlLogAutoSaver:31,154`）+ 本次读侧流式/分批为界面卡顿的防线。若后续实测单日文件仍过大，再评估日内滚动。

## 四、性能专项（用户关切，逐条落实）

| 指标 | 现状 | 改后 | 落点 |
|------|------|------|------|
| 历史打开 EDT 冻结 | `applySqlSearch` 全量填 model（O(N) 模型事件+布局） | 首屏 30 即时 + 分块追加 + ignoreRepaint | `HistorySqlLogPanel.kt:438-454` |
| 历史文件读取内存 | `readText()` 整文件读串 | 流式逐行（峰值≈单条） | `SqlLogFileReader.kt:59-61` |
| 高亮渲染 | 自研 tokenizer | 保持（虚拟化只渲染可视行，非瓶颈） | 不改 |
| 写盘风暴 | 异步批量队列+单写线程 | 原样复用 | `SqlLogAutoSaver.kt` |
| 列表去重/暂停/过滤 | 不回调成功入队 | 语义延续（不入历史） | `SqlLogEntryBuffer.kt:108-134` |

风险点：搜索每击键 O(N) 过滤 + 分批写入多次触发 —— 有 `SEARCH_DEBOUNCE_MS`（`HistorySqlLogPanel.kt:620`）200ms 防抖兜底，与实时面板同一基准（`SqlLogPanel` 同款 debounce），可接受。

## 五、改动落点清单

- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogEntryBuffer.kt`：新增 `onEntryStored` 回调 + 成功入队路径调用（:131-132）。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogManager.kt`：`applySettings` 写入来源切换（:443-450）；`dispose` 补 `onEntryStored=null`（:522 附近）。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/HistorySqlLogPanel.kt`：`applySqlSearch` 分批填充（:438）；复用 `bgGeneration` dev 代际守卫。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogFileReader.kt`：`readFromFile` 流式（:59）。

## 六、测试计划

- `SqlLogEntryBufferTest`：新增 `onEntryStored` 成功入队触发、`paused/filtered/duplicate` 不触发、去重窗口外同 SQL 各触发一次 / 窗口内重复不触发。
- `SqlLogManagerTest`（如有）：`logAutoSaveEnabled` 开→ `onEntryStored` 装配、`onEvicted=null`；关→两者 null。
- `SqlLogAutoSaverTest`：全量写入的文件往返——入队 N 条后文件含 N 个块（此前只有淘汰条数）。
- `SqlLogHistoryRoundTripTest`：写 `onEntryStored` 全量 → 读回条目数与内容一致（去重/过滤/暂停边界）。
- `HistorySqlLogPanelNavigationTest` / 相关 UI 测试：大条目集下 `applySqlSearch` 分批后 `sqlListEntries` 最终完整、gen 代际终止旧链不产生幽灵/重复条目；`showFileContent` 后台读取语义不变。
- `SqlLogFileReaderTest`：流式读与 `readText` 读结果一致（混三种头部块、pd 行、旧版头部）。

## 七、边界 / 已知取舍

- 「全量」沿用「成功入队」定义：去重、暂停、过滤丢弃的 SQL 不进历史（不是字节级全量）。如需连这些也留档，是独立需求，另行评估（会放大写盘与文件量级，届时需重新确认不卡顿防线）。
- 关闭自动保存（`logAutoSaveEnabled=false`）仍是不记录历史的总开关，语义未变。
- 历史面板卡片一次性读大文件耗时（后台线程）保留，不做分页预览——流式已降内存，分批已降 EDT 冻结，两者覆盖「打开卡顿」。
- 不采纳「改原生 SQL 高亮」路线（见第二节高亮专项结论），避免引入 Database 依赖且无性能收益。