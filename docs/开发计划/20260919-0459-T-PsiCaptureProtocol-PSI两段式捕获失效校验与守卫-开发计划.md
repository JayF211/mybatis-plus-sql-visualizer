# T-PsiCaptureProtocol PSI 两段式捕获失效校验与守卫 — 开发计划

- 日期：2026-09-19 04:59
- 任务编号：T-PsiCaptureProtocol（第八轮报告 §9.4 **O-35**，P2；原拟名「PSI 捕获一致性协议推广」——核实后**锚点全部更换**，见 §一）
- 来源：第八轮报告 §7.2 zMyBatis 精华 **Z-16**——双快照 + commit document + 前后比对 + PSI text 与文档内容交叉验证（`XmlMapperMethodCaptureAdapter.kt:96-170`）
- 性质：**并发正确性加固**——不是「补 ReadAction」（现状比预期规范），而是补「EDT 捕获元素 → 后台读取」这条链的**失效校验**与守卫
- 实施分支：自 master 拉任务分支 t-psi-capture-protocol
- 验证基线：master（testShards 5153/0/0/6 + testDatabase 10/0/0/0 + agent 绿）

---

## 一、背景与依据（现状核实，含两处口径修正）

| # | 原建议口径 | 核实事实 | 影响 |
|---|-----------|---------|------|
| 1 | 「对 `StackLocator`/`EntryJumpResolver` 等跨线程 PSI 读取点做两段式快照」 | **口径修正（关键）**：`StackLocator` **不是 PSI 读取点**——它是 agent 子项目的 Java 类（`sub-projects/sql-visualizer-agent/src/main/java/cn/song/mybatisplus/sqlvis/agent/util/StackLocator.java:69`），职责是**调用栈帧定位**，不读 PSI/Document。第八轮报告对它的引用是比喻性的，不能作代码锚点 | 锚点全部更换，见下 |
| 2 | 「大多数后台 PSI 读需要补 ReadAction」 | **现状比预期规范**：绝大多数后台 PSI/VFS 读已自带 `ReadAction`（`log/CallerMethodRangeResolver.kt:47`、`log/MapperXmlLocator.kt:43`、`log/ProjectTypeDetector.kt:53,96`、`execute/DataSourceSelector.kt:1016/1544/1569/2452/3259/3466/3884/3952`（`executeOnPooledThread` 内）、`actions/ConvertSqlToJavaAction.kt:87,114,181`、`actions/ConvertToSqlAction.kt:110/329/373/419/471/540/581`、`log/SqlToJavaPanel.kt:808`、`log/SqlSourceInterceptorAutoInjector.kt:86`、`onboarding/OnboardingAnchorTip.kt:85`） | 增量收窄为**失效校验 + 守卫**，不是补权限包裹 |

**真实落点（两类）**：

| 类 | 位置 | 现状 | 缺口 |
|---|------|------|------|
| 甲：EDT 捕获元素 → BGT 读 | `ui/gutter/SqlGutterLineMarkerProvider.kt:163-171`（EDT 传入 `elt: PsiElement`，BGT 内 `ReadAction.compute` 读 `elt.containingFile`/`elt.textRange` `:166-170`）；`ui/gutter/StringSqlMethodGutterLineMarkerProvider.kt:137-143`（EDT 捕获锚点方法，BGT 内读 `call.callExpression.containingFile.viewProvider.document` `:143`） | 有 `ReadAction`（读权限合规） | **无「元素是否仍有效」的校验/交叉验证**——捕获与读取之间发生写操作会使元素失效（这正是 Z-16 要解决的形态） |
| 乙：依赖调用方持读权限 | `log/EntryJumpResolver.kt:26-52`（**全文件无 ReadAction**）+ `log/EntrySourceMatcher.kt:85-106`（`SourcePathResolver.resolveVirtualFile`，KDoc `:83-84` 明写「调用方保证持有读权限」）；调用点 `log/SqlLogPanel.kt:1476,1503`、`log/HistorySqlLogPanel.kt:616` 均为 EDT 右键路径 | 读的是 `ModuleManager`/`LocalFileSystem`（平台模型读，非 PSI），EDT 下合法 | 口径为「靠约定」，无断言保障；一旦将来被后台调用即违约 |

**既有约定（可援引，但无工具）**：`ui/SqlPreviewService.kt:2085-2092`（`previewAsync` KDoc）已写明「锚点元素可先在 EDT 捕获，块内读取」——这是 O-35 可援引的既有协议文本；`execute/DialectPreMapping.kt:24,86` 与 `DataSourceSelector.kt:3201` 是「BGT 探测 → `invokeLater` 派 EDT + 有界 latch」的另一条两段式先例。

**无现成快照工具**：`copyText` 全是剪贴板复制（`log/SqlClickPreview.kt:165,209,242` 等）；`snapshot()` 仅指集合快照（`log/SqlLogEntryBuffer.kt:216`）。

**守卫现状**：仅一条相关规则 `convention/BannedApiSourceScanTest.kt:175-189`（`createFileFromText` 调用点文件必须含 `ReadAction` 保护）——**粗粒度「文件含字样」级**，不覆盖跨线程 PSI/Document 读取。扫描基架可扩展（`:51-67` 的 `mainSources()`/`assertNoHits`）。

**非问题（澄清，防误立项）**：`ui/diagram/nativefw/NativeJoinDiagramOpener.kt:49,75,90` 的 `runAsync()` 是平台 diagram 异步布局 API；`ui/diagram/DiagramPanel.kt:342,368,395` 的 `executeOnPooledThread` 是文件导出（`VirtualFileWrapper`），不碰 PSI；`log/agent/` 全目录无 PSI/Document 访问（纯数据适配）。

## 二、目标与非目标

**目标**：①给「EDT 捕获 → 后台读」这条链补**失效校验**（读前/读后交叉验证，读不到时如实降级而非静默错值）；②把协议写成可援引的文档；③补一条能抓「新增跨线程 PSI 读且无校验」的守卫规则。

**非目标**：不批量重写既有 ReadAction 包裹（现状合规）；不给乙类强加 ReadAction（其调用面全在 EDT，强行加包裹会改变既有语义）；不动 agent 子项目（`StackLocator` 不属本面）；不重造平台读权限机制。

## 三、方案

**T-PCP-01（两段式快照校验工具）**：新增轻量工具（建议 `ui/psi/PsiSnapshot.kt`）——在 EDT 侧捕获时记录 `(element 文本, textRange, 所属文件路径, 文档 modificationStamp)`，后台读取时**交叉验证**：文件仍存在、`modificationStamp` 未变、`textRange` 仍在界内；任一不符即返回「已失效」信号，由调用方降级（gutter provider 则不渲染标记、跳转解析则不跳并给诊断）。**形态对齐 Z-16**（双快照 + 前后比对），但**按平台能力落地**——用 `document.modificationStamp` 而非 commit document（我方是读侧，不需要写回）。

**T-PCP-02（甲类两处接入）**：`SqlGutterLineMarkerProvider`（`:163-171`）与 `StringSqlMethodGutterLineMarkerProvider`（`:137-143`）改用 T-PCP-01 的校验形态；失效时静默不渲染（gutter 标记缺失无害）并留一条 `LOG.debug` 级诊断（不打扰）。**注意**：gutter provider 在 EDT 高频调用，校验须廉价（`modificationStamp` 读取是 O(1)）。

**T-PCP-03（乙类协议文档化 + 断言）**：`EntryJumpResolver`/`SourcePathResolver` 一族**不改调用形态**（EDT 合法），但：①把 KDoc 约定（`EntrySourceMatcher.kt:83-84`）提炼进 T-PCP-05 的协议文档；②加一条**调用点守卫**——若将来在后台线程调用该族，须显式包 `ReadAction`（可用既有扫描基架实现，或加运行期断言）。

**T-PCP-04（守卫规则，防回潮）**：在 `convention/BannedApiSourceScanTest.kt` 既有基架上新增规则：**EDT 捕获 `PsiElement` 并在 lambda/后台块内读取**的形态必须走 T-PCP-01 的校验（或显式豁免注释）。**先红后绿**：构造一个绕过校验的形态证明会红。**串行约束**：T-TailSweep2 的 T-FS-06 也改本文件，**两者必须串行**（本任务宜在其后，或本规则另建守卫文件避免冲突）。

**T-PCP-05（协议文档）**：`docs/架构设计/` 下补「PSI 跨线程读取协议」一节——三条形态（EDT 直读 / BGT 包 ReadAction / EDT 捕获 + BGT 校验读）各写适用条件与反例，援引 `SqlPreviewService.kt:2085-2092` 既有 KDoc 作起点。CHANGELOG + 变更记录 + 第八轮报告 Z-16 落地备注（含「锚点由 `StackLocator` 更正为两个 gutter provider」的口径说明）。

## 四、验收标准

1. T-PCP-01 工具的表驱动用例：文件不存在 / stamp 变化 / textRange 越界三态各判「失效」；无变化判「有效」。
2. 甲类两处的失效路径有用例（构造 stamp 变化 → 断言不渲染/不跳转且无异常）。
3. 正常路径行为逐字节不变（gutter 标记照常渲染、跳转照常命中）。
4. 守卫先红后绿，豁免清单带理由。
5. 协议文档在册且三条形态各有适用条件。
6. 全量 testShards + testDatabase + agent 绿；跑前按并行铁律登记。

## 五、真机走查清单（留人工）

1. 真机快速编辑 mapper 文件（触发写）的同时观察 gutter 标记行为——无异常、无错值标记。
2. 真机日志面板右键跳转 → 正常命中；在文件被外部修改后跳转 → 行为如实（不跳到错位置）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| gutter 热路径开销 | 每次渲染多读 `modificationStamp` | 该读取为 O(1) 且不涉 PSI 遍历；用例断言渲染耗时无回退 |
| 校验过严导致标记消失 | stamp 在正常编辑中频繁变化 | 校验只在**跨线程捕获**场景启用；同线程直读不走校验（EDT 内捕获后立即读的场景不退化） |
| 守卫误报 | 形态识别可能漏/过报 | 先红后绿；豁免清单允许分批补齐；结论按 T-TailSweep 守卫的既有教训（「只可能漏报不会过报」的表述曾被证伪）如实写边界 |
| 与 T-TailSweep2 冲突 | T-FS-06 同改守卫文件 | 串行执行（见 T-PCP-04） |

## 七、实施顺序

```
T-PCP-01（快照校验工具）→ T-PCP-02（甲类接入）→ T-PCP-03（乙类协议与断言）→ T-PCP-04（守卫，先红后绿；与 T-FS-06 串行）→ T-PCP-05（协议文档）→ 真机走查 §五
```

## 八、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| T-PCP-01 | （实施后填） | 工具落点与校验字段最终形态须回填 |
| T-PCP-02 | （实施后填） | 两处 gutter 的失效降级形态须回填 |
| T-PCP-03 | （实施后填） | 调用点守卫实现方式须回填 |
| T-PCP-04 | （实施后填） | 守卫规则描述、误报边界、与 T-FS-06 的串行次序须回填 |
| T-PCP-05 | （实施后填） | |
