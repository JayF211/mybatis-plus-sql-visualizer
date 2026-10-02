# O-95 T-PreviewEdtGuardFollowup 预览面板 EDT 慢操作与事务守卫修缮批 — 开发计划

- **日期**：2026-10-02
- **编号**：O-95（编号台账实测：O-94=plugin.xml 文案〔并行会话占用〕，O-95 空号；用户令 20261002「按照你的推荐，定案」＝登记项三项立案）
- **动机**（批次 9 沙盒 UI 走查 + R33 真机三验登记项，盘点 60 版）：①**SlowOperations on EDT 断言**——清空参数框触发预览重渲染链时集中爆发 54 次（Plugin to blame: SQL Visualizer，Last Action: EditorBackSpace），属 psi 渲染链在 EDT 走慢操作 API；②**TransactionGuard write-unsafe**——`DataSourceSelector.kt:3732` 在 NON_MODAL 模态下 `commitDocument` 未包 WriteAction（R33 修复轮日志实证 2 组）；③**预览面板无数据源绑定常驻指示**——R33 补验实证绑定循控制台上下文成立但面板不显示绑定了哪个源（用户需点执行计划看回退通知才知道）。
- **成本**：M（S1 定性为主+治理、S2 小件、S3 中件）
- **状态**：已立项待实施（用户令 20261002 定案立案）

---

## 一、方案（三子任务）

| 子任务 | 内容 | 写集 |
|---|---|---|
| **S1 SlowOperations 定性+治理** | ①定性：本地复现清参数框→重渲染链，抓 SlowOperations 断言栈归因（哪些慢操作 API、在哪个调用层）；②治理：**线程归位两段式**（BGT 读探测+EDT 收口）或预计算/缓存化——`allowSlowOperations` 逃生舱 243+262 双重证伪**禁再试**；③无法根治的路径逐点缓存化降噪，目标=重渲染链断言归零或降至个位数并有归因账 | `ui/SqlPreviewPanel.kt` 重渲染链+相关缓存 |
| **S2 TransactionGuard 修复** | `DataSourceSelector.kt:3732` `commitDocument` 包 `WriteAction.compute`（或改走 FileDocumentManager 保存通道——读码定），NON_MODAL 模态下不再 write-unsafe；反向验证=还原→TransactionGuard 断言红 | `execute/DataSourceSelector.kt`（单点） |
| **S3 数据源绑定指示** | 预览面板参数区/标题区增**绑定数据源常驻指示**（轻量：绑定态文本行或角标，循 R32 角标视觉口径；ReadAction+DBN 链+变更订阅，无绑定/未绑定时显示「未绑定（在控制台执行一次查询即可绑定）」灰字）——与 R33 回退通知文案同源 | `ui/SqlPreviewPanel.kt` + bundle 双语 |

## 二、验收

1. S1：本地复现序列（填参→清空）断言计数 before/after 两轮读数；归因账落计划 §实施记录；
2. S2：反向验证（摘 WriteAction→TransactionGuard 红→恢复绿）；
3. S3：真机走查判定（绑定态指示出现/未绑定灰字）——随下一轮沙盒走查批；
4. 收口全量门禁（testShards 5 片+agent+verifyGate 三目标）。

## 三、决策点

| 编号 | 决策点 | 推荐 |
|---|---|---|
| D1 | S1 治理深度 | **A=定性+可治理点治理+余量归因账**（断言清零不设硬指标，避免为清零改语义）；B=不达标不收口 |
| D2 | S3 指示形态 | **A=灰字文本行**（轻量，与回退通知同源文案）；B=角标图形（与 R32 同款，工作量大） |

## 四、待验证前提（实施先证伪）

1. S1 断言集中爆发=EditorBackSpace 清空触发全量重渲染——先复现抓栈证实归因链（可能被证伪为其它入口）；
2. S2 `commitDocument` 是唯一 write-unsafe 点（日志只见此一处，但需 grep 同族）；
3. S3 DBN 变更订阅在预览弹窗 disposable 树内注册可安全释放（O-88 dispose 链先例可循）。

## 五、实施记录

（t-o95fix 工作树实施，2026-10-02；基线 dev `a0e2b32`；提交 `7742787`〔S2〕/`64db513`〔S1〕/S3 随后）

### S2 TransactionGuard write-unsafe 修复（`7742787`）

- **前提②复核（grep 同族 commitDocument 全调用面）**：R33 日志帧 `DataSourceSelector.kt:3732` 对应 `writeVfTextViaDocument` 内联 lambda 区（`ExecDocAutosaveGate.suppressDuring` 内联起点，bf23915 与当前树该文件逐字节一致；`commitDocument` 在 3743，PSI commit 链是平台断言点之一——T-DynUpdateWriteSafe 记录在案）。全部四个调用点核对：
  - `:471`（reuseSharedConsole）/`:1699`（prepareInConsole attach）/`:2776`（executeOnConsole runWhenSmart）——三处**均已包 `WriteAction.compute`**（与计划「未包 WriteAction」的预期不符，前提②被证伪）；R33 日志无实证指向这三处，不动；
  - `:1216`（contentAdded 重写 console 文档）——已是 `ModalityState.nonModal()` 投递（T-WSC-02 定例）；
  - **真正缺口=`dispatchDocWriteOnEdt` 的 EDT 直调分支**（原 `if (app.isDispatchThread) return docWriteOnce(...)`）：是 `writeVfTextViaDocument` 调用面里**唯一未过 `DocWriteContext` 闸的入口**（fire-and-forget/bounded 两形态已闸）。仓内平台定例（T-WriteSafeCtx 243 源码逐帧）：**WriteAction 不 confer 写安全资格**——write-unsafe EDT 活动（any() 投递/「写不安全进入」的模态泵）里包了 WriteAction 照样报 Write-unsafe context，R33 三验 2 组（EXPLAIN 验证链逐次执行 → gStrip `\G` 回写 `dispatchDocWriteOnEdt` 即此链）。
- **修法**：EDT 直调分支同样过 `DocWriteContext.runWriteSafe` 闸——判可写就地执行（常态行为逐字不变）；判不可写转 nonModal 延后一拍并返回 false（写体 `docWriteOnce` 幂等；现有调用方——gStrip 回写与 `syncExecVfContentIfNeeded` 四调用点——均不消费返回值，延后语义与有界等待路径「判不安全立即交还」同纪律）。`dispatchDocWriteOnEdt` private→internal 供测试直测。
- **反向红绿**：摘闸→`ExecDocSyncTest.testEdtDirectDispatchDefersWriteWhenContextUnsafe` 红（同步直写返回 true、deferredCount 不动；14 例 1 失败）；恢复→14/0 全绿。新增配对用例 `testEdtDirectDispatchWritesInlineWhenContextAllowed`（可写就地写零投递）。
- **证据边界**：真实 TransactionGuard 断言需生产 EDT 活动语义（生产 IdeaLogger 只记 SEVERE 不抛、单测 TestLogger 收集不抛），单测以 `writeAllowedProbe` 注入观测**闸门分支行为**（与 T-FixWriteSafe 两例同口径）；真机门禁=沙盒日志 grep `Write-unsafe context`，随下一轮走查批。

### S1 SlowOperations 定性+治理（`64db513`，D1=A）

- **定性（归因账）**：
  - **实栈证据**（2026-09-24 主树沙盒 `idea.log:1263`，同插件同族）：慢操作断言源=`SyntaxHighlighterFactory.getSyntaxHighlighter` → SQL 插件 `SqlLanguageSubstitutor.getSqlLanguageDialect` → `SqlDialectMappings.getMapping` → `PerFileMappingsBase.ensureStateLoaded` → `findFileByUrl`/`findFileByPath` → `VirtualDirectoryImpl.findChild` → `FSRecordsImpl.update`（**VFS 目录下沉查找本身是慢操作门控 API**）。该族在插件的触发面=编辑器创建期（`LogToSqlPanel:541` 直调〔已实证炸过〕、`SqlPreviewPanel.createEditor`→`EditorFactory.createEditor` 内部），**均不在 renderSql→showSql 逐帧链上**。
  - **前提①证据边界（如实标注）**：批次 9 走查的 54 次原始日志已不在盘（走查工作树沙盒被复用，主树沙盒仅 9/24 旧日志 2 条）。「断言源在重渲染链」无法逐帧复核——按链枚举治理，不设清零硬指标（D1=A 定案）。
  - **重渲染链枚举**（参数框 documentListener→`onParamChanged`→`scheduleRender`〔150ms 防抖〕→`renderSql`〔write-safe 闸〕→模型渲染→`backfillParamsBySource`〔纯文本〕→`showSql`→WriteCommandAction 写文档+saveDocument→`SqlAutoFolding.applyTo`→`syncToggleSelectLabels`）：链上插件直调的**可治理点=`SqlAutoFolding.applyTo` 每轮全量重建折叠区域**（2×N region 增删+两次批操作；参数防抖后渲染文本未变成常态=纯冗余 EDT 工作）；`applyDisplayMode` 已有单槽缓存（T-PreviewFast P6）；其余为必要写或纯 Swing。
- **治理**：`SqlAutoFolding.applyTo` **同文本幂等短路**——开启态+上一轮应用文本相同（`KEY_LAST_APPLIED_TEXT` UserData）+本方区域全部仍有效（isValid 检查保住「防御平台重算抹除」语义）→ 直接返回。行为差：同文本渲染不再把用户手动展开的区域重新收起（旧实现与防御初衷无关的副作用，新行为更贴近用户意图）。
- **防回潮钉×2**：①行为钉=`SqlAutoFoldingTest` 新增三例（同文本二次 applyTo 区域实例不重建/区域被外部抹除后照常重建/关闭态不受短路影响）；②守卫钉=`PreviewRenderSlowOpsGuardTest`（重渲染链宿主文件 `SqlPreviewPanel.kt`+`SqlAutoFolding.kt` 禁直调慢操作 API 族 `getSyntaxHighlighter(`/`findFileByUrl(`/`findFileByPath(`，Sep 24 实栈族；判定内核 `slowOpDirectCallHits`+自证语料「三令牌逐一必中+干净源零误报」双向，GUARD_CLASSES 已登记，元守卫 8/0 接受）。
- **反向红绿**：摘短路（`if (false &&`）→`SqlAutoFoldingTest` 8 例 1 红（区域实例换代 expected same … was not）；守卫探针（面板注入 `getSyntaxHighlighter(` 字面量）→守卫红命中 `getSyntaxHighlighter(@152`；恢复后 `SqlAutoFoldingTest` 8/0+守卫 2/0+元守卫 8/0+`SqlPreviewPanelDocLifecycleTest` 3/0 全绿。
- **余量归因账**（未治理，如实登记）：①批次 9 的 54 次断言原始栈缺失，其确切落点不可复核——若走查复现，按本实栈族优先排查编辑器创建/方言映射链；②`saveDocument`/文档写引发的平台重高亮/重解析属平台内部（blame 归属随插件帧，根治需平台侧），不在插件可治理面；③`LogToSqlPanel:541` 直调 `getSyntaxHighlighter` 在启动路径（工具窗首开，每会话一次），非重渲染链，留观。

### S3 数据源绑定指示行（D2=A 灰字文本行）

- **形态**：参数区预设行之下常驻灰字文本行（`UIUtil.getContextHelpForeground` 与面板既有灰字形态一致，禁自创图形）；无参数早退分支同样保留指示行（常驻语义——无参时用户更需要看到「有没有绑定」）。
- **文案同源**：绑定=`panel.bindstate.bound`「已绑定：{0}」（数据源名=新增 `DataSourceSelector.boundConsoleDataSourceName()`，与 R33 结构化视图头部 `boundConsoleDataSource()` 完全同源选择链：共享/已备控制台+isValid 先探+DBN 反射取名，Community fail-closed → null）；未绑定=`panel.bindstate.unbound`「未绑定数据源（在控制台执行一次查询即可绑定）」（与 R33 回退通知 `panel.explain.structured.reason.noDataSource` 同源文案族）；双语 bundle 齐备（`panel.bindstate.*` 对齐 `panel.scheme.*` 命名风格）。
- **刷新通道**：`renderSql` 尾部单点收口（循 R30 条件探镜「零监听器」口径）——控制台 attach/reuse/clearPrepared 链的既有 post-attach 渲染/清空路径都走 renderSql，不另立监听器；纯文本读，write-safe 闸重投递无副作用。
- **防串扰**：`paramRowLabelForTest` 同形状排除扩展到指示行（含 JBLabel 的 JBPanel 行契约不变）。
- **定向**：`SqlPreviewPanelBindStateTest` 三例（无绑定灰字文案/有绑定态显示源名+随渲染刷新回流/指示行位于预设行之下〔组件序=BoxLayout 视觉序〕）3/0+`SqlPreviewPanelTest` 73/0+`SqlPreviewPanelParamSchemesTest` 13/0 布局回归+`BundlePlaceholderArityTest` 4/0 占位符契约（0 失败）。
- **真机走查**（验收 3）：绑定态指示出现/未绑定灰字留下一轮沙盒走查批（计划 §二既定）。
