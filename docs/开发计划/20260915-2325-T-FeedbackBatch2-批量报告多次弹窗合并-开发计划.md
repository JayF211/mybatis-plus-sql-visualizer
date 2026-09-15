---
search: false
---

# 批量报告多次弹窗合并（T-FeedbackBatch2）开发计划

- 立项时间：2026-09-15 23:25
- 需求原话：「目前插件的批量报告问题没有生效，出现多个报错信息后，点击包含全部，还是出现了多次弹窗，包含问题下方的堆栈选择只有一个待选择的，可能是因为，这是多次错误触发，而不是一次错误触发有了多个堆栈」（2026-09-15）
- 仓库：实施在插件仓库 mybatis-plus-sql-visualizer（Kotlin），计划文档落 docs/开发计划/
- 影响范围：`IdeErrorReportSubmitter`（错误报告提交器）、`ErrorReportDialog`（批次分节显示）、i18n bundle、单元测试

---

## 一、问题分析

### 1.1 症状与用户假设

用户在 IDE Internal Errors 对话框看到多条报错后，点击「报告全部」（平台 "Report All" 动作，中文语言包下用户记作「包含全部」），结果：

1. 依然弹出**多个**报告确认框（一个错误一个）；
2. 每个确认框里「其他 N 条异常」分节只有**一条**待勾选项。

用户假设：这是多次错误触发（平台逐条调用），而不是一次触发带多个堆栈。

### 1.2 现场：日常 IDEA 2026.2 日志核查

翻遍 `%APPDATA%\..\Local\JetBrains\IntelliJIdea2026.2\log\`（idea.log + idea.1~7.log，2026-09-15 15:25~16:57 共 8 个文件）：

- 本插件包名 `cn.song.mybatisplus.visualizer` 在 ERROR/SEVERE 级别**零记录**，仅有的插件相关行是 INFO 级流程日志与 Marketplace 更新检查列表；
- 全部 SEVERE 条目只有两类：Kotlin 插件归属的 `HighlightInfoB`（Custom severity WARNING(396)，今天重复 10+ 次被平台抑制）与 `KillableProcessHandler` Ctrl+C 回退，均与本插件无关。

结论：**当天日志里没有本插件错误现场**（日志只保留到 15:25 起，且报错可能发生在更早会话）。症状机制不依赖日志定案——平台侧字节码已经给出确定性证据。

### 1.3 根因：262 平台字节码实证（javap，IntelliJIdea 2026.2.2 #IU-262.10315.125）

平台 `com.intellij.diagnostic.IdeErrorsDialog`（intellij.platform.ide.impl.jar）反编译结论：

1. **"Report All" 是逐簇循环**：`reportAll(List<ErrorMessageCluster>, ...)` 对每个错误簇调用 `IdeErrorsDialog.access$reportMessage`（`IdeErrorsDialog$reportAll$reported$1` 字节码引用实证）。错误池按指纹把消息聚成 `ErrorMessageCluster`，N 个不同错误 = N 个簇。
2. **每次 submit 只传一条事件**：`reportMessage(ErrorMessageCluster, Component)` 里 `anewarray 1` + 单次 `aastore`——事件数组固定长度 1，内容取 `cluster.getFirst()`，随后调 `ErrorReportSubmitter.submit(events, ...)`（同一次调用里 `cluster.getFirst()` 只取簇内第一条，同错误多次重复也只报首条）。
3. **提交是同步循环**：`reportAll` 在 `withContext(EDT)` 内同步逐簇调 `reportMessage`，因此本插件的 `submit()` 会在 EDT 上背靠背被调 N 次。

对照插件侧（T-FeedbackBatch 设计前提）：「平台错误列表可多选，`events` 是数组」——数组确实存在，**但 262 平台永远不会给它塞第二条**。于是：

- `IdeErrorReportSubmitter.submit` 每次调用弹一个模态框 → N 簇 = N 个弹窗（前一个模态泵事件时下一个 `invokeLater` 已排上，表现为连着弹）；
- 每次调用的 `events` 长度 1 → `batchItemsOf` 产出 1 条批次条目 → 对话框显示「其他 1 条异常」+ 一个勾选框（就是主预填那条自己）。

**用户假设完全成立**：多次错误触发，每次一个堆栈。

### 1.4 附带发现的显示语义瑕疵

批次条目契约是「全含头部」（`batchItemsOf` 映射全部 events，含 events[0]），`BatchSubmitter` 的合并/补发逻辑（`drop(1)` 留头当首发、合并附件吃全部条目）也是按全含契约写的——这条链自洽。但对话框分节标题写的是「其他 {0} 条异常」，而条目数含头部主预填自己：单错误场景显示「其他 1 条异常」+ 一个与主表单重复的勾选行，语义自相矛盾（「其他」却列了自己）。

---

## 二、修复方案

核心思路：**在提交器里把同一波（去抖窗口内的）submit 调用合并成一个弹窗**；平台仍逐簇调，但用户只见一个框、一次批量提交，正好落进 T-FeedbackBatch 已有的「合并成一条帖子 / 首发带合并附件 + 错峰补发」链路。

### 2.1 T-01 提交器累积合并（核心）

`IdeErrorReportSubmitter`：

- 新增 `PendingCall(freshEvents, consumer)` 待处理队列 + 去抖 flush：
  - `submit()` 不再直接 `openReportFlow`，改为：空事件返回 false；按事件指纹逐条去重（`dedupeGate`，被去重的事件直接剔出本波；整条调用全被去重时立即对它的 consumer 回调 DUPLICATE，不弹框）；剩余新鲜事件入队并**重置去抖定时**（500ms，经典 debounce——平台循环逐簇调用间隔远小于此，全部落到同一波）；
  - 防呆上限：自首个待处理条目起 3s 强制 flush（防平台逐簇间隔波动导致弹窗无限推迟）；
  - flush：队列在锁内整体 drain 并复位调度标记 → 合并全部事件 → 取首事件做主预填（prefill/stackText/指纹），`batchItemsOf(全部事件)` 做批次条目（契约不变），**复合 consumer**（把本波每个调用的 consumer 都收进来，提交结束时统一回调同一 `SubmittedReportInfo`，平台侧 N 个簇全部正确标记已提交）→ 走既有 `openReportFlow`；
  - 提交发起时记录**全部**合并事件的指纹（原实现只记首条，其余条目 24h 去重从未生效）；
  - 线程安全：队列操作 `@Synchronized`；调度器用 `AppExecutorUtil.getAppScheduledExecutorService()`，flush 内经 `invokeLater` 回 EDT（`openDialogAndSubmit` 已有）。
- `openReportFlow` seam 签名变更：`fingerprint: String` → `fingerprints: List<String>`（首元素=主预填指纹，全集=提交时待记录）。生产实现与测试注入同步更新。

单次调用路径行为对齐现状：入队 1 条 → flush → 一次 `openReportFlow`，与改造前逐参数一致（仅多 500ms 去抖延迟）。

### 2.2 T-02 对话框批次分节显示语义修正

`ErrorReportDialog`（提交链契约零改动，只动显示与收集）：

- 分节可见性：`items.size >= 2` 才显示批次分节。单错误场景不再出现「其他 1 条异常」+ 重复勾选行（T-FeedbackBatch 前的原始单条形态）；
- 标题文案改如实计数：`error.report.dialog.batchHeader` 改为「本批共 {0} 条异常（默认一并提交）」（条目含头部主预填，旧文案「其他」撒谎），英文同步 "This batch contains {0} errors (submitted together by default)"；
- 头部条目勾选态与主表单「附带异常堆栈」收敛为同一事实源：头部行复选框**禁用**（仅镜像显示 `attachStackCheck.isSelected`，tooltip 注明由上方开关控制），收集提交条目时以 `attachStackCheck.isSelected` 回写 `batchItems[0].attachStack`。消除「头部堆栈两处开关可能不一致」的暗坑（勾选主开关但头部行没勾 → 合并附件丢主堆栈）；
- 收集逻辑抽纯函数 `selectedBatchItemsFor(all, headAttachStack, batchList)`（companion，headless 可测）。

`BatchSubmitter` / `ErrorReportComposer` / `PendingReportScheduler` 零改动（全含契约按原设计运转）。

### 2.3 不做（记录取舍）

- 不在平台对话框层面拦「报告全部」（无公开 API 可接管，反射绑死平台内部类不可维护）；
- 不动手动形态报告（无批次概念）与 `PendingReportScheduler` 错峰链路；
- 不做跨波持久累积（弹窗开着时平台被模态阻塞，不会有后续 submit 进来）。

---

## 三、改动清单

| 文件 | 改动 |
|------|------|
| `src/main/kotlin/.../feedback/IdeErrorReportSubmitter.kt` | T-01 累积合并：PendingCall 队列、去抖调度、复合 consumer、指纹全集记录、seam 签名 |
| `src/main/kotlin/.../feedback/ErrorReportDialog.kt` | T-02 分节可见性 ≥2、头部行镜像禁用、收集纯函数、文档注释更新 |
| `src/main/resources/messages/MpSqlVisualizerBundle.properties` | `error.report.dialog.batchHeader` 英文文案 |
| `src/main/resources/messages/MpSqlVisualizerBundle_zh.properties` | 同上中文 |
| `src/test/kotlin/.../feedback/IdeErrorReportSubmitterTest.kt` | seam 注入适配 + 合并/去重/复合回调新例 |
| `src/test/kotlin/.../feedback/ErrorReportDialogLayoutTest.kt` | 分节可见性、头部镜像、收集纯函数新例 |

测试遵循项目约定：JUnit 断言风、反引号中文用例名、seam 注入无 EDT 依赖；bundle parity / plugin.xml 结构守卫自动覆盖文案改动。

## 四、测试计划

1. **提交器合并**：3 次连续 `submit()` → 手动触发 flush → `openReportFlow` 恰好 1 次调用、批次条目 = 3、3 个 consumer 全部收到同一结果信息；
2. **单次调用等价**：1 次 submit → flush → 流程参数与改造前逐项一致（仅多去抖）；
3. **去重**：本波内指纹命中的事件被剔除（其余照常合并）；整条调用全命中 → DUPLICATE 直调不弹框；
4. **防呆上限**：首条入队超 3s 强制 flush（时钟 seam）；
5. **对话框**：1 条批次条目 → 分节不显示；2 条 → 显示且标题计数含头部；头部行禁用镜像、收集时以主开关为准；
6. 全量 `testShards` 绿（跑前 `python parallel_tasks.py check`）。

## 五、实施步骤

1. 本计划文档落 `docs/开发计划/`，master 先行提交；
2. `git worktree add ../mybatis-plus-sql-visualizer-feedbackbatch2 -b t-feedbackbatch2`，实施 T-01 → T-02 → 测试；
3. 全量测试绿 → 合回 master（--no-ff）→ 删分支与工作树；
4. CHANGELOG 记入进行中版本块。

## 六、验收清单

- [ ] 「报告全部」N 个错误只弹**一个**确认框，批次分节列出全部 N 条（含主预填那条）；
- [ ] 单错误「报告」弹窗不再出现「其他 1 条异常」假分节；
- [ ] 去重命中不重复弹框（24h 窗口语义不变）；
- [ ] 提交后平台 Internal Errors 列表所有选中簇均标记为已提交；
- [ ] 合并批次 >5 条时照旧走「首发带合并附件 + 错峰补发」；
- [ ] 全量测试绿。
