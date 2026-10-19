# T-LogListFreeze-04：日志列表 HTML 逐条解析 EDT 冻结 16 秒根治 开发计划

> **创建日期**：2026-09-08（同日修订：并入 dev 分支对账结论，Wave 2 改为移植 dev T-Perf-03/24）
> **分支**：master（当前 HEAD `0219f91`）；dev 对账基线 `9388cdb`（2026-09-08）
> **优先级**：P0（日志面板打开即冻结 16 秒，IDE 判 Freeze 弹窗，主路径不可用级卡顿）
> **预估工时**：4~6h（含全量回归与历史面板同型修复）
> **来源**：用户真机 IDE 2026.2.1 Freeze 报告（`com.intellij.diagnostic.Freeze`，采样 11400ms，GC 0%）

---

## 一、问题与根因

### 1.1 现象

打开 SQL 日志面板（或日志刷新）后 IDE 冻结 16 秒，Freeze 报告业务栈集中在：

```
java.desktop/javax.swing.plaf.basic.BasicHTML.createHTMLView / updateRenderer
  ← BasicLabelUI.propertyChange ← JLabel.setText
  ← LogEntryListCellRenderer.getListCellRendererComponent(SqlLogPanel.kt:1245)   ← 业务帧
  ← WideSelectionListUI.getItemPreferredSize → updateLayoutState（逐条取首选尺寸）
  ← JBScrollPane$Layout.layoutContainer → RepaintManager.validateInvalidComponents（EDT）
```

采样 11.4s 全部落在此链路；GC 0%、CPU 1%——不是内存压力，是 EDT 单线程在做海量 Swing HTML 解析排版。报告栈行号 1245 对应 master 渲染器位置（dev 同文件渲染器在 1401 行），证实用户真机运行的是 master 发行线（v2026.9.4），dev 上未发行的改造与本冻结无关。

### 1.2 根因：三层叠加

1. **渲染通道根因**：列表条目用 `DefaultListCellRenderer`（JLabel）+ HTML 文本渲染（master SqlLogPanel.kt:1245）。JLabel 每次 `setText` 且文本以 `<html>` 开头时，`BasicHTML.updateRenderer` 会**新建一个 Swing HTML 视图并完整排版**（HTML 解析 + 每个 `<span>` 的 CSS 属性解析 + 字体解析 + FlowView 文档布局）。`SqlListHtmlRenderer` 对 SQL **逐 token 包 `<span>`**（SqlListHtmlRenderer.kt:46，关键字/表名/字符串等 8 类着色），长 SQL 一条几十个 span，单条排版达数 ms~数十 ms；换行 `<br>` 进一步加重段落布局。
2. **布局路径放大**：`list.fixedCellHeight = -1`（可变行高，master SqlLogPanel.kt:713）→ JList 每次布局状态更新都对**每一条**条目调 renderer 取 preferred size，成本 O(N) 次 HTML 排版；滚动/resize 同样触发。
3. **刷新管线放大**（master 现状）：`SqlLogEntryBuffer.addEntry` 每条日志到达即 `notifyChanged`（SqlLogEntryBuffer.kt:59/69）→ `refreshList` **全量重建**：`listModel.clear()` + 分批 `addAll`，并全清 HTML 缓存（master SqlLogPanel.kt:759）。批量查询风暴时每秒多次全量重建；T-LogListFreeze-03 的 invokeLater 分批追加在模型变更使布局缓存全失效的语义下，把总成本放大为 O(批数 × N) 次 HTML 排版——默认 200 条上限即 7 批 × 平均百余条 ≈ 800 次/刷新，叠加后即 16 秒。

### 1.3 为什么 T-LogListFreeze-02 的 HTML 缓存无效

上轮缓存（`LogSqlEntry.cachedListHtml`）只省了 `SqlListHtmlRenderer.render` 的**字符串拼接**，而栈里可证昂贵点在 `JLabel.setText` 之后的 `BasicHTML.createHTMLView`——字符串命中缓存与否，Swing HTML 解析排版一次不少。缓存机制治标未治本。

### 1.4 dev 分支对账（2026-09-08，dev HEAD `9388cdb`）

结论：**dev 未修复本冻结的两个主因，已修复管线层放大因**。逐项对账：

| 冻结成因 | master 现状 | dev 现状 | 结论 |
|----------|------------|----------|------|
| HTML 渲染通道 | 渲染器 :1245 HTML 拼接 | 渲染器 :1401，T-TwoLine 两行 HTML（:1423 `computeRowHtml` 元信息行+`<br>`+SQL 行） | **未修复**，dev 反而单条 HTML 更重 |
| 可变行高 O(N) 布局 | :713 `fixedCellHeight = -1` | :835 同值 | **未修复** |
| 全量刷新管线 | clear + 分批 addAll + 全清缓存 | **已修**：T-Perf-03 增量差分刷新（`applyIncremental`/`VisibleDiff`/`diffVisible`/代际守卫）+ T-Perf-19 按 `dedupeKey` 选择性缓存失效 + T-Perf-24 搜索 200ms 防抖 + T-Perf-15 dispose 注销监听 | dev 已有成熟实现 |

dev 侧剩余风险：全量重建路径（打开面板/类型过滤/排序切换/搜索应用）仍是 `clear` + 分批 `addAll` → 每批触发 O(N) HTML 排版，同型冻结在 dev 上依然可复现；流式追加场景被 T-Perf-03 消掉的只是重建放大，不是渲染通道本身。

**对计划的约束**：Wave 2（刷新管线）不再自创方案，改为**移植 dev 的 T-Perf-03/19/24/15 实现**（同源代码，后续 master→dev 合并无冲突）；Wave 1（渲染通道）替换 HTML 后须**保留 T-TwoLine 两行 UX**（元信息行在上、SQL 高亮行在下），否则 dev 的 aae6768 会在合并时形成语义冲突。

### 1.5 全库排雷：同型隐患一处

| 位置 | 形态 | 是否纳入本轮 |
|------|------|:---:|
| SqlLogPanel.kt:1245（日志列表条目，master 行号） | HTML JLabel + 可变行高 + 高频刷新 | 是（主修复点） |
| HistorySqlLogPanel.kt:447（历史条目列表，master 行号） | 同型 HTML JLabel 渲染；dev 侧该文件已 +151 行且 T-TwoLine 同步改过 HistoryCellRenderer | 是（Wave 3，按 dev 现状对齐） |
| HistorySqlLogPanel.kt:388（历史文件列表） | HTML `<b>` JLabel，条目少、低频 | 顺带同型替换 |
| LogToSqlPanel / SqlToJavaPanel 结果区 | Editor 通道，非 JLabel-HTML | 否 |

---

## 二、技术方案

### 2.1 方案选型

| 方案 | 做法 | 结论 |
|------|------|------|
| A（推荐） | 弃 Swing HTML 通道：渲染器改平台标准 `SimpleColoredComponent` 系，SQL 着色由 `SqlLexer` 分词直接产着色文本片段，保留 T-TwoLine 两行结构；列表改固定行高（两行恒定高）；管线层移植 dev T-Perf-03/19/24/15 | 根治三因；渲染通道无 HTML 解析、布局走 O(1) 快路径；与 dev 合并面最小 |
| B | 保 HTML，仅固定行高 | 布局重排消失，但可见行滚动仍逐条 BasicHTML 解析，滚动掉帧；且放弃多行/两行结构，收益不及 A |
| C | 保 HTML + 两行，仅移植 dev 管线层 | 即 dev 现状：只消流式追加放大，打开面板/过滤/排序的 O(N) 排版冻结仍在 |

选 A。补充说明：固定行高与 T-TwoLine 两行结构兼容——元信息行恒存在（时间戳常开；四段全空时 dev 侧已用占位符 `[—]`/`[unknown]` 归元信息行，aae6768 测试锁定），两行高度为常量，`fixedCellHeight` 取实测常数即可。多行 SQL 换行展示不再保留（历史行为：HTML `<br>` 折行），全文查看由既有双击预览弹窗承接（SqlLogPanel.kt:718），列表定位概览；tooltip 保留 `restoredSql` 全文。

### 2.2 方案 A 细节

1. **渲染器替换 + T-TwoLine 结构保留**（SqlLogPanel）：`LogEntryListCellRenderer` 从 `DefaultListCellRenderer`+HTML 改为双 `SimpleColoredComponent` 叠放的 JPanel（元信息行在上、SQL 行在下，对齐 dev `computeRowHtml` 的两行语义）。片段映射：元信息行 = 时间戳灰片段 + 耗时片段（慢查询红/正常灰/选中白语义保留）+ `[来源:行]` 灰片段 + `⨉N` 徽章片段（trim 后单空格连接，空段跳过）；SQL 行 = `SqlLexer` 分词着色片段。选中态由平台渲染器标准绘制（`SimpleListCellRenderer`/`ColoredListCellRenderer` 实施时按 javap 结果定）——无 HTML 缓存后 dev T-Perf-23 的「选中色不固化」语义天然成立。多行 SQL 的 `\n` 折叠为单行（替换 `⏎`），tooltip 保持全文。
2. **着色管线改造**：`SqlListHtmlRenderer` 旁新增 fragment 产出路径（`SqlLexer` 分词 → `List<Pair<text, SimpleTextAttributes>>`，色值仍走 `SqlHighlightColors` + `EditorColorsScheme`，与预览编辑器同一配色体系）。防 HTML 注入的转义逻辑随 HTML 通道退役。
3. **固定行高**：`list.fixedCellHeight` 按两行内容实测设定（2 × 行高 + 既有 5,8,5,8 EmptyBorder + 1px matte 下边框，JBUI.scale），`buildListPane` 一次设定。固定行高使 `updateLayoutState` 走 O(1) 快路径，滚动/resize/追加均不再逐条渲染。
4. **管线层移植 dev（Wave 2 主体）**——以 dev `9388cdb` 实现为蓝本原样移植，不自创：
   - `applyIncremental`/`applyFullRebuild`（代际守卫）/`diffVisible`/`VisibleDiff`/`lastRendered`/`refreshGeneration` 整套差分刷新（dev SqlLogPanel.kt:876-968）；
   - `SqlLogEntryBuffer.drainEvictedCount`（evictedSinceDrain 原子计数，dev :153）；
   - `LogSqlEntry.dedupeKey`（lazy sqlFingerprint，dev LogSqlModels.kt:76）——徽章查找键从 `restoredSql` 原文（master :1242）切到指纹，与 dev 对齐；
   - T-Perf-24 搜索输入 200ms 防抖（Timer 实现，dev :542/:1271/:1341）；
   - T-Perf-15 dispose 注销监听（master 侧已有等价注销则对齐语义即可）。
   - **不移植**：T-Perf-19 的 HTML 选择性失效代码块——fragment 渲染无缓存，徽章计数变化下次 paint 即时呈现，失效机制随 HTML 缓存一并退役；对应 dev 合并时该块按「master 侧已删缓存」解决。
   - dev 测试 `SqlLogStreamingDiffTest` 一并移植。
5. **HTML 缓存机制退役**：`LogSqlEntry.cachedListHtml` 字段、refreshList 全清循环（master :759）、渲染器取缓存分支（master :1236）删除。
6. **历史面板同型**（Wave 3）：HistorySqlLogPanel 两个渲染器同 2.2.1~2.2.3 改造（着色管线复用，HistoryCellRenderer 按 dev T-TwoLine 后的两行语义平移）。

### 2.3 API 权威核实清单（实施前 javap，243 编译基准 distro）

铁律：方法名以本地 jar 反编译为唯一权威。实施第一步核实以下类存在于 `lib/app.jar`/`lib/util.jar` 且签名同形：

- `com.intellij.ui.SimpleListCellRenderer.create(BiConsumer)` / `create(String, String, BiConsumer)`
- `com.intellij.ui.SimpleColoredComponent.append(String, SimpleTextAttributes)` / `clear()`
- `com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES` / `BOLD_ATTRIBUTES` / `ERROR_ATTRIBUTES`（或 `SimpleTextAttributes(Color)` 构造）
- `com.intellij.util.ui.JBUI.scale(int)`（固定行高常数换算）

---

## 三、实施步骤

| # | 任务 | 说明 |
|---|------|------|
| 1 | javap API 核实 | §2.3 清单逐项核对，签名不符则回调方案（等价平台类替代） |
| 2 | 着色 fragment 管线 | SqlLexer → 片段列表（新对象 SqlListFragmentRenderer 或并入现类），单测先行 |
| 3 | SqlLogPanel 渲染器替换 | 双 SimpleColoredComponent 两行渲染器（保留 T-TwoLine UX）+ 固定行高 + `\n` 折叠 + tooltip 保持 |
| 4 | 管线层移植 dev | T-Perf-03 差分刷新全套 + drainEvictedCount + dedupeKey + T-Perf-24 搜索防抖；SqlLogStreamingDiffTest 随迁；T-Perf-19 失效块不移植（无缓存语义） |
| 5 | 删 HTML 缓存机制 | cachedListHtml 字段/全清循环/取缓存分支退役 |
| 6 | 历史面板同型 | HistorySqlLogPanel 两处渲染器替换（按 dev T-TwoLine 后语义） |
| 7 | 测试迁移 + 新增 | §四测试计划 |
| 8 | 全量回归 | gradlew 前 `python parallel_tasks.py check`；`./gradlew test --no-build-cache --no-configuration-cache` 基线绿 |
| 9 | runIde 真机验证 | 大批量日志（压测项目跑批量查询）开面板：无 Freeze、滚动流畅、两行结构/着色/徽章/耗时语义不回退；统一走 runide-demo-verify-workflow |
| 10 | 文档三处同步 | 变更记录.md 追加（日期倒序）+ 本计划 §实施记录 + docs/MEMORY.md 变更记录；CHANGELOG 下版本条目 |
| 11 | 分开提交 | fix 与 docs 两个 commit，四段式提交信息 |

---

## 四、测试计划

| 层级 | 做法 | 判定 |
|------|------|------|
| 迁移：LogListFreezeTest | 缓存语义用例（缓存命中/清空/独立）随 HTML 缓存退役删除；批次写入用例按移植后的差分管线改写 | 重写后全绿 |
| 移植：SqlLogStreamingDiffTest | dev 同名测试随 Wave 2 原样移植（VisibleDiff 五分支/代际守卫/驱逐计数） | 全绿 |
| 迁移：SqlLogPanelRendererUiTest | HTML 断言改片段断言：组件类型 is SimpleColoredComponent（通道级根治断言）、片段数与着色属性、转义用例改原文保真用例、慢查询红/选中白/徽章语义平移、**两行结构断言平移自 dev aae6768 测试**（元信息在上/来源不混入 SQL 行/占位符归元信息行） | 全绿 |
| 迁移：SqlListHtmlRendererTest | HTML 输出用例迁到 fragment 路径（分词正确性保留）；HTML 路径若无残留调用则删除 | 全绿 |
| 新增：布局快路径 | `list.fixedCellHeight > 0` 断言；N 条追加后模型大小断言（增量路径不 clear 已有条目） | 全绿 |
| 新增：搜索防抖 | T-Perf-24 同款（200ms 窗口合并击键刷新） | 全绿 |
| 回归 | SqlLogPanelFilter/DedupeToggle/SortAndSlowQuery/JumpToSource、HistoryCellRendererTest、SqlLogPanelLayoutTest 全量 | 基线绿 |
| 真机 | runIde 压测：日志面板 200+ 条打开/刷新/滚动/双击预览 | 无 Freeze 报告，idea.log 无新 SEVERE |

不设硬性耗时断言（防 CI flaky）；性能以真机 Freeze 报告消失为准。

---

## 五、风险与回滚

- **合并策略（master→dev 下轮同步）**：Wave 1/3 渲染器冲突（dev 侧 `computeRowHtml` 两行 HTML vs master 侧片段渲染器）按「本修复有意取代 HTML 通道、T-TwoLine UX 已在片段渲染器中等价保留」一次性解决；Wave 2 移植与 dev 同源，git 自然合流（T-Perf-19 失效块取 master 侧删除态）。kotlin-to-java 锚点同步自 master 顺带携带，无需单独处理。
- **产品行为变更**：列表条目不再按 SQL 内容自动折行（HTML `<br>` 折行移除），恒定两行（元信息+SQL 单行截断）+ tooltip 全文；全文由双击预览承接。若用户要求保留折行，回退备选为 JTextArea 无着色通道，改动收敛在渲染器一处。
- **着色视觉差异**：SimpleColoredComponent 无 CSS，着色颗粒度与现 HTML 一致（同为 SqlLexer token 级），主题跟随不回退。
- **增量追加边界**：排序 DURATION_DESC / 搜索过滤等不可增量场景由 VisibleDiff.FullRebuild 分支兜底回退全量重建（dev 已验证语义）。
- **回滚**：各波独立 commit，可按波 revert；HTML 缓存字段删除涉及 LogSqlModels，revert 需整波回。

---

## 六、实施记录

（2026-09-08 实施，HEAD 基线 8f0ba6b）

- **Wave 1**：新增 `SqlListFragmentRenderer.kt`（SqlLexer→SqlTextFragment 片段管线，scheme 注入可单测，关键字 fontType 加粗随片段保留）；`SqlLogPanel.kt` 渲染器改双 SimpleColoredComponent 两行面板（元信息行在上/SQL 行在下，T-TwoLine 保留），`buildListPane` 样例实测固定行高（fallback 字体行高×2+13），`cachedListHtml` 字段/全清循环/取缓存分支退役（LogSqlModels 同步删字段）。
- **Wave 2**：自 dev 9388cdb 移植——VisibleDiff 五分支 + diffVisible/applyIncremental/applyFullRebuild（代际守卫）+ refreshGeneration/lastRendered；buffer 增 `evictedSinceDrain`/`drainEvictedCount`，addEntry 合并临界区（T-Perf-06）+ onEvicted 移出锁（T-Perf-12）；LogSqlModels 增 `dedupeKey`（FNV-1a 指纹）+ `sqlFingerprint`；manager notifyUi 置脏合并 + 新增 `removeCapturingListener`；面板监听器命名引用 + dispose 精确注销（T-Perf-15）+ 搜索 200ms 防抖（T-Perf-24）。T-Perf-19 的 HTML 选择性失效块按计划不移植（片段无缓存）。
- **Wave 3**：HistorySqlLogPanel 两渲染器片段化（文件列表 tooltip 保留），两列表固定行高。
- **与并行会话协同**：T-ClickPreview（悬停 tooltip 移除 + 单击预览）与本项目同文件交织——其渲染器 tooltip 删除语义在新片段渲染器中以注释保留（不设 toolTipText）；该会话已自行提交 c9d7fe2/8f0ba6b，本任务工作区 diff 随之收敛为纯 T-LogListFreeze04 内容。
- **测试结果**：全量 `./gradlew test` **3102/0/0/6 绿**（基线 3094 + 净增 8：SqlListFragmentRendererTest 6 + SqlLogStreamingDiffTest 10 移植 − LogListFreezeTest 缓存 4 − SqlListHtmlRendererTest 5 + RendererUiTest 净增 1）。过程中修正三处断言：徽章 map 与快照键改 `sqlFingerprint` 键（T-Perf-19 语义）；「正常 50ms 灰色」旧断言实为蹭到时间戳灰，直读片段属性后修正为分档绿（0x4CAF50）。
- **API 核实**：243 基准 app-client.jar javap——SimpleColoredComponent.append/clear、SimpleTextAttributes(int,Color)/REGULAR/GRAYED/BOLD、SimpleListCellRenderer、ColoredIterator.getFragment/getTextAttributes 全存在；WideSelectionListUI.updateLayoutState 字节码证实 fixedCellHeight≠-1 时 goto 短路逐条测量。
- **遗留人工跟进**：runIde 真机大批量日志验证（打开/刷新/滚动/双击预览无 Freeze、两行着色视觉核对）——统一走 runide-demo-verify-workflow；T-LogDedupeMpMark（去重空白差异 + MP 计数误标）与本修复正交（isDuplicate 仍原文精确相等），由其会话继续。
