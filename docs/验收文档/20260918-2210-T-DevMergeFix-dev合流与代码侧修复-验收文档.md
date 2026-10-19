# T-DevMergeFix dev 全量合流与代码侧修复 — 验收文档

- 日期：2026-09-18 22:10
- 任务编号：T-DevMergeFix（T-MergeDev2Master 后半程 + 代码侧问题修复；sam 调度六路并行）
- 来源：用户指令「除了 K2J O-24 不合并，其他都合并到主分支，并修复所有问题」+「$sam 使用 git 工作树新建分支和目录实施，可多开子智能体」
- 交付形态：`master` 快进至 `6cce631`（= dev 合流后提交）后并入 4 条修复分支

---

## 一、改动总览

### 1. 合流（master ← dev，除 K2J）

- 合流方向定为**先 master→dev 再 master 快进**：dev 只落后 master 15 个提交（T-ReachAudit 七项 + T-TestDbDriver 等），先合再快进可让 163 提交的冲突面**只解一次**，且 master 快进时**零冲突**。
- **真实冲突仅 2 文件**（实测：以基点 `087494e` 取两侧都改过的文件共 11 个，其中 9 个 hunk 坐标不相交、自动并集）：
  - `docs/变更记录.md`：同点插入 → 并集，master 两行置顶 + dev 八行，10 行一条不丢；
  - `docs/开发计划/20260916-1730-待完成开发计划全景盘点.md`：三处人工合成（版本头 → 第 10 版；T-M56 二期取 dev「已交付」行；testDatabase 行取 master 最终态 + 保留 dev 的「本轮新发现」小节并把「7 项」更正为实测「6 项（9 例 6 失败）」；「均已交付」长行取并集并补回 dev 移走的 T-DbSchemaCompletion/T-DgmFullCols 登记）。
- **关键人工补齐（自动合并抓不到）**：dev 侧 premain 建会话点（`AgentManager.kt:599`）未传 `clockMs` —— 心跳窗口判定跨「会话记 lastSeen」与「管理器算过期」两侧，缺此参数则 premain 通道在注入假时钟时恒判健康，**T-RA-01 修的「半死会话永久封口桥」会在 premain 通道原样复活**。已补齐。
- K2J 未合并（`feature/kotlin-to-java` 保持独立）；合并未触碰发行元数据（版本号 / `update/*.json` / `plugin.xml` 的 since-build 与 description 均未变）。

### 2. 代码侧修复（4 条并行工作树，写集互不相交）

| 工作树 / 分支 | 提交 | 内容 |
|---|---|---|
| `mpsv-fix-db` / `t-fix-testdb` | `627fd13` | `testDatabase` 六失败清偿：稳定工程目录（新 `DbIntegrationProjectSupport`）+ 控制台/编辑器夹具 + 数据源发现链收敛等待 + `buildSrc` `forkEvery = 1`；**`src/main` 零改动** |
| `mpsv-fix-diagload` / `t-fix-diag-loading` | `b5a2414` | F8 JOIN 图加载态接线：转圈抬到标签页宿主层（`diagramLoadingHost`）+ `DiagramPanel.bindHostLoadingPanel` 转交，真机默认原生图路径下可见 |
| `mpsv-fix-settings` / `t-fix-settings-scope` | `2c3eb6a` | 设置导入改 base-merge（未导出字段不再被静默重置）+ 项目级表设置按实体所属项目取值 + `SettingsRealityGuardTest` 三条断言收紧 |
| `mpsv-fix-writesafe` / `t-fix-writesafe` | `d1afde1` | `dispatchDocWriteOnEdt` 两处投递写体过 write-safe 闸门（fire-and-forget 整拍改 nonModal；有界等待保留默认模态投递、写体过 `runWriteSafe`，不安全态当拍 countDown 不白等 2s） |

## 二、自动化验证清单

| 通道 | 结果 |
|---|---|
| `testShards`（dev 合并树，合流后 / 修复前） | **BUILD SUCCESSFUL**（5m10s） |
| `testDatabase`（master 合并树，修复后） | **BUILD SUCCESSFUL**（4m53s）——修复前为 9 例 6 失败 |
| `testShards`（master 合并树，修复后） | **5117 / 0 失败 / 0 错误 / 6 跳过**（448 套件；首跑 11 例假失败已三步证伪） |
| `:sql-visualizer-agent:test` | 隔离复跑绿（首跑 1 例计时断言失准，负载所致） |
| 各路定向 + `testFast` | 4 条修复各自定向全绿；`testFast` 2728~2730 例 0 失败 |

**先红后绿（4 处）**：F8 加载态接线（未接线时探针序列为空 → 断言红）、WriteSafe 有界派发（stash 主代码改动 → `testBoundedDispatchDefersWriteWhenContextUnsafe` 红）、设置导入 base-merge（`PluginSettingsPersistenceTest.testImportJsonKeepsFieldsAbsentFromFile` 红，`参数历史应保留 expected:<[...]> but was:<[]>`）、守卫收紧（收紧后全绿，无红）。

## 三、独立复核（sam 写验分离，四路只读验证者）

四路验证者均判**有条件通过**（无阻断反例），抓到以下条件——**这些是本次交付的已知边界，需在后续批次处置**：

### A. testDatabase 修复（`627fd13`）

1. **`HeavyNative` 的绿不等于 B 路径已验证**：该用例实际在 `component == null` 处自跳（XML `system-out` 实证打印 `[DB-IT] SKIP: B 路径链路不可用…`），节点/连线/SQLV_JOIN/fitContent/JoinFkRegistry 五组断言**全部未执行**，却被 JUnit 计为绿。交付说明「9 例 0 失败 0 skip」是**计数器口径**，须改述为「9 例 JUnit 绿，其中 HeavyNative B 路径未验证」；记录文档 §四/§五 的冲突口径需更新。
2. **临时工程目录永久残留**：`<tmp>/mpsql-db-itest-<ksuid>` 无清理路径（平台只对 `unitTest_` 前缀递归删父目录，字节码实证），实测已积 28 个、`forkEvery=1` 后每轮 +5。属卫生问题（体积小），需补清理钩子或如实标注。
3. **`forkEvery = 1` 掩盖了一类真机可观测现象**：DBN 引擎终止清扫 dispose 掉刚 attach 的控制台（真机同款 T-PreviewExecDs-03 F3）在分 JVM 后不再被任何用例复现，`DataSourceSelector.kt:1305-1311` 的处置分支零覆盖。作用域正确（仅 `testDatabase`）、代价（+2~3 分钟）已如实记录。
4. **建议（非阻断）**：`DataSourceSelector.kt:1267` / `:1284-1296` 编辑器解析失败**静默 return 不回调 onError** → 真机同类情形会「120s 无任何提示」，补 3 行 `onError` 可消除可诊断性缺口（记录 §五① 原本就给了这个方向，本次改为测试侧夹具消掉触发条件，故该静默挂死契约从此无用例可观测）。

### B. F8 加载态（`b5a2414`）

1. **测试没钉住宿主面（最实的缺口）**：删掉 `SqlPreviewPanel.kt:469` 的 `bindHostLoadingPanel` 注入、或把 `:1605` 的 `addTab` 目标改回 `diagramTabHost`，三个用例**仍全绿**，而「原生路径转圈不可见」会原样复发 → 需补一条「标签页内容 === 宿主 loading 面板」的断言。
2. **异常兜底缺失**：`SqlPreviewPanel.kt:1496` 的 `buildDiagramModel` 在 `try` 之外，`showJoinDiagram` 无 `try/finally` → 一旦抛异常，pooled 线程终止、`invokeLater` 永不执行，**转圈永留宿主层**（直到下一次 `showResult`/`clear`）。建议收尾改 `try { … } finally { hideLoading() }`。
3. 交付说明的测试计数（76→79）与仓库实测（73→76）不符，+3 增量无误。
4. `swapContent` 的 `contentLayer.remove(it)` 确为 no-op（`JBLoadingPanel` 未重写 `remove`，字节码实证；换页靠 repaint 叠加），属 T-UiNative 既有形态，本次未加剧。

### C. 设置侧（`2c3eb6a`）

1. **守卫收紧未到位**：4 个符号仍可构造假绿——`effectiveReplaceParams` / `effectiveInjectLogicDelete` / `effectiveLogicDeleteValue` / `isEnabled`（同名 helper 的内部调用点被计入消费）。提交信息「堵住同名遮蔽缺口」的表述对它们**不成立**，需改为「要求限定接收者形态（如 `ps.effectiveXxx(`）」或降级该声明。
2. **行为差需登记**：导入不再把 `enhancedCaptureEnabled` 翻回默认 true、也不再清零 `enhancedCaptureRejectedAtMs`（旧行为会在后续 apply 造出一次**假上升沿**从而误清 24h 冷却）。属意图内改进，但应写入说明避免被当作回归。
3. **API 陷阱**：`importFromJson(json)` 无 base 重载保留旧重置语义，且已被 `SettingsIOTest` 固化为契约（生产无调用方）→ 建议改为必填或补警示注释。
4. **写集外调用方实为 11 处**（非 4 文件，漏 `CriteriaQueryConverter.kt:133/147/459`）：多项目同开且项目级表配置不同时，join/子查询/collection 表名与「SQL→Java」反查会用到「首个打开项目」的配置。均为**无 PSI 兜底分支**（主路径已修）。其中 `WrapperLocator.kt:729`、`EntityClassFinder.kt:108` 手头就有 project，宜列入下一批低成本清理。
5. 文档数字订正：`PluginSettingsPersistenceTest.kt:371` 注释「71 个 var 字段」应为 **77**；commit body 的 49/56 不自洽。

### D. WriteSafeCtx（`d1afde1`）

1. **声明措辞**：有界路径不安全态「立即 countDown（ok=false）」中 `ok=false` **非确定**（良性竞态：真机模态阻塞下确定 false，非阻塞场景可观测 true；两种取值都安全，`projectExecFile` 两个分支都只记日志）。「不等满 2s」由 `finally` 同拍 countDown 结构保证，**成立**。
2. **`expireWith = project` 采纳**（优于 `null`：项目关闭后不再碰已 dispose 的 Project/PSI/VFS，且 exec 文件只是临时镜像，无用户数据丢失之虞）。
3. **测试覆盖真实但收益钉不牢**：`deferredCount+1` 是有效判据（改前必红）；`elapsed<1800ms` 无法捕捉「countDown 被挪进延迟拍」的回归。
4. **守卫收紧清单须更正**：`SqlPreviewPanel` 实际在 `:2486`（非 `:2450`）；现有提取器用 `indexOf("invokeLater(")` **看不见尾随 lambda** `invokeLater {`，只收紧谓词会新增 0 处红；`:3387` 并未出扫描面（需「闸门感知豁免」而非功能改动）。
5. **4 处真实 write-unsafe 风险须列入后续任务**：`ui/SqlInputEditor.kt:142`、`ui/SqlPreviewPanel.kt:2486`（补全 `commitDocument`）、`log/SqlToJavaPanel.kt:820/881`（结果区回填）。

## 四、门禁复跑（假失败证伪）

master 合并树首跑 `testShards` 报 **11 例失败（testShard1）**、`:sql-visualizer-agent:test` 报 **1 例失败**，全部落在本会话已登记的假失败形态上：

- `AlreadyDisposedException: Container is already disposed`（`SectionConfigurableAutoCaptureLinkageTest` ×2、`AgentListenSyncTest` ×1、`EpBridgeSinglePathTest` 部分）；
- 平台扩展注册表断言转储 `{class org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry=[…MultiHostInjector…]}`（`EpBridgeSinglePathTest` 部分、`HistorySqlLogPanelNavigationTest` ×1）；
- agent 侧 `StackCaptureConvergenceTest.缓存命中路径不再全栈建池_深栈计时防回潮`：**计时断言**（`hit=417000ns, miss=1040600ns`）在负载下失准——当时同机并发跑着三通道门禁与四个验证代理。

处置：按项目定例先隔离复跑四类 + agent 证伪，再于静默机重跑 `testShards`。

**证伪结果（全部成立）**：四类定向隔离复跑 **BUILD SUCCESSFUL（24s，退出 0）**；`:sql-visualizer-agent:test` 隔离复跑 **BUILD SUCCESSFUL（21s，退出 0）**；静默机重跑 `testShards` → **5117 / 0 失败 / 0 错误 / 6 跳过（448 套件）**。即 12 例失败全为「分片重平衡顺序依赖 + 负载下计时断言失准」的已知假失败形态，与本次改动无关。

**最终门禁口径**：testShards **5117/0/0/6**（对比合流前 master 4453，+664 例 = dev 侧六批功能）+ testDatabase **9/0/0/0**（修复前 9 例 6 失败）+ agent 子项目绿。

## 五、判定卡

| 项 | 判定 |
|---|---|
| 合流完整性（除 K2J） | ✅ master 已含 dev 全部 163 提交 |
| 关键人工补齐（premain `clockMs`） | ✅ 已补，编译验证 |
| testDatabase 六失败 | ✅ 已修（9/0/0/0），但 HeavyNative B 路径未验证（见 §三 A1） |
| F8 加载态 | ✅ 已接线（真机默认路径可见），但宿主面断言与异常兜底待补（§三 B1/B2） |
| 设置导入静默重置 | ✅ 已修（base-merge），行为差已登记（§三 C2） |
| 多项目取值歧义 | ⚠️ 主路径已修，11 处无 PSI 兜底分支留档（§三 C4） |
| WriteSafeCtx 两处 | ✅ 已过闸，4 处同类风险留档（§三 D5） |
| 发行元数据 | ✅ 未受影响（版本/更新清单/plugin.xml 未变） |
| 真机走查 | ⏳ 留人工（见 §六） |

## 六、真机走查（留人工）

1. **半死会话**：跑应用 → `kill -STOP` 应用进程 → 30 秒内设置页状态行应转心跳类回落且控制台捕获恢复（premain 通道同样适用）。
   ⚠️ **2026-09-21 文案订正（T-AgentHeartbeat T-05）**：本条原写「应转『应用侧心跳中断（进程可能已卡死）』」，该措辞已被替换——它是对成因的因果断言，而代码无从得知进程是否卡死。现文案为「已 {0} 秒未收到应用侧心跳，已回落控制台捕获」，秒数取在线窗口值。
2. **JOIN 图加载态**：默认配置（原生图开启）下预览含 JOIN 的 SQL，标签页选中时应看到平台转圈盖在整页上。
3. **JOIN 图错误页**：注入一次渲染失败（真实输入几乎不抛）看错误页观感。
4. **确认框**：勾选「增强捕获」→「同意并启用」后复选框应保持勾选、启动应用后日志出现「用户同意注入」。
5. **设置导入**：导入一份只含部分键的 JSON 后，参数历史 / 增强捕获授权记忆 / 反馈身份 / 保存路径应**保持不变**。
6. **多项目**：两项目同开且项目级表前缀不同，在 B 项目预览应使用 B 的配置。
7. **模态场景**（WriteSafeCtx）：模态框存续 >2s 时 exec 文件同步应记 "raw write SKIPPED" 且**不卡 2 秒**（日志 grep `Write-unsafe context` / deferred 打点）。
8. **testDatabase**：`./gradlew testDatabase` 应 9 例绿（HeavyNative 仍为软跳，属已知边界）。

## 七、后续批次（本次验证发现，未修）

| 项 | 级别 | 说明 |
|---|---|---|
| 4 处 write-unsafe 站点 | P2 | `SqlInputEditor.kt:142` / `SqlPreviewPanel.kt:2486` / `SqlToJavaPanel.kt:820/881`；需同时把守卫提取器扩到尾随 lambda 形态 |
| F8 宿主面断言 + `try/finally` | P2 | 防「删注入仍全绿」与异常逃逸卡转圈 |
| 守卫断言三改限定接收者形态 | P3 | 堵住 4 个符号的假绿反例 |
| 11 处无 PSI 兜底调用方补 project | P3 | 其中 `WrapperLocator:729` / `EntityClassFinder:108` 手头有 project，优先 |
| testDatabase 临时目录清理 + HeavyNative B 路径覆盖 | P3 | 前者补清理钩子；后者补日志与用例（`NativeDatabaseDiagramSupport.kt:72` 静默 null 需补 log） |
| `DataSourceSelector` 编辑器解析失败补 `onError` | P3 | 消除「120s 无提示」的可诊断性缺口 |
| `importFromJson` 无 base 重载改必填 | P4 | 消除保留型陷阱 |
