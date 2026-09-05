---
name: project-m36-direct-execute
description: M36 SQL预览直接执行（2026-08-27~29）——M36.4/M36.5 原生控制台内嵌成功（共享复用+UiDataProvider+runWhenSmart）；M36.5.14~21d 终局解决"执行内容滞后一轮"（执行链切原生两步 findScriptModel→doRunQueryInConsole + TransactionGuard write-safe 事务）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_c90388b1-ffe0-4a46-98e7-a620ff182919
---

M36「SQL 预览直接执行与结果展示」。计划文档：`docs/开发计划/20260827-1500-M36-SQL预览直接执行与结果展示-开发计划.md`（M36.2i）、`docs/开发计划/20260828-1900-M36.5-SQL预览面板原生融合优化-开发计划.md`（M36.5 系列，含逐轮实施记录）。

**历史路径**：M36.2 八轮内嵌迭代失败（RunnerLayoutUi 0 尺寸）→ M36.2i 转 JDBC 直连 + JBTable → **M36.4/M36.5 系列（2026-08-28~29）回归原生控制台内嵌并最终成功**——「架构性不可解」结论被推翻，成功条件见 [[feedback-m36-native-console-misalignment]]。

**M36.5.10 当前状态（2026-08-29，已实施）**：
- **执行提交包 `DumbService.runWhenSmart`**：索引期查询提交被 Database 插件调度层推迟（dumb 窗口内重试 16s 无 Tab、索引结束后才真正执行，用户感知"一直加载中"）——改为提交前等 smart，结果 Tab 重试在真实提交后才启动
- **generation 代际计数**（`executionGeneration` @Volatile）：`clearPrepared`/`prepareInConsole`/`reuseSharedConsole` 均递增；挂起的 runWhenSmart 提交代际不匹配即放弃（`execution submit cancelled`）——共享控制台文档是单文档，面板清空/新预览后旧 SQL 不得在新文档上执行
- 执行链改异步：`executeOnPooledThread` + `invokeLater`（javax.swing.Timer 须 EDT 创建）；补上执行失败 `onError` 回调（原实现只记日志不通知 UI，面板卡"执行中"）
- 探针 `logDataContextCheck` 包 ReadAction——lazy PSI 在 EDT 无 read action 直查被 DataValidators 判无效返回 null（探针假阴性；动作线程 ActionUpdater BGT 含 read action 不受影响）
- 验证：ExecuteSqlErrorHandlingTest 15/15 通过；未提交 git

**M36.5.11 当前状态（2026-08-29，沙盒五问题修复后）**：
- **M36.5.10 引入回归**：把换入逻辑挪进 `executeOnPooledThread` 时，`buildConsoleSplitPane`（JSplitPane 构造）留在后台线程——JSplitPane 构造触发编辑器 hierarchy 事件 → `ScrollingModelImpl.getVisibleArea` → EDT 检查抛异常（用户堆栈实锤），异常发生在 `onResultsReady` 之前，**静默中断整个 lambda**（onResultsReady/startResultTabRetry/clearConsolePrompt/refreshResultsComponent 全不跑）
- **修复**：执行提交仍在 pooled thread（read action），但「buildConsoleSplitPane + onResultsReady 换入 + startResultTabRetry」**整体包进同一个 `invokeLater`**——只包 invoke、构造留后台是隐性坑
- 五问题定级：表解析 `Unable to resolve table` = 临时 .sql 在 Temp 目录不在数据源关联范围（IDE 固有行为，插件不可修）；其余四问题（执行不成功/提示迟到/提示符残留/插件错误）均为同一 EDT 异常链
- 探针 `PSI_FILE=false` 不修：不影响 ▶ 启用（UiDataProvider 走动作系统专线，DataManager 探针不触发）；用户点「执行 SQL」按钮成功即证据
- 验证：ExecuteSqlErrorHandlingTest 15/15 通过；未提交 git

**M36.5.12 当前状态（2026-08-29，用户复测仍未解决 → 旧 jar 误判澄清 + 临时文件迁移）**：
- **用户复测"没解决"**实际是沙盒在跑旧 jar：报错堆栈行号指向源码注释行 + 堆栈出现旧 lambda 形态（`$0$0$0` 直调 buildConsoleSplitPane），而新代码该调用已内联进 `$0$0$0$2`（invokeLater Runnable）——javap 双方对比即铁证（详见 [[project-sandbox-jar-file-lock]] 变体四）。修复代码是好的，只需 `buildPlugin` 重打包 + cp 同步沙盒 + javap 验证
- **临时文件迁移（用户指示）**：`createTempPsiFile` 改用 `projectExecFile`——存 `<projectBase>/.idea/mybatis-plus-sql-visualizer/exec/mpsql-exec.sql`（参考 sql-history 位置；文件名稳定每次覆盖；独立子目录防 HistorySqlLogPanel 误扫）
- **"Unable to resolve table" 真根因澄清**：与文件位置无关——`getAttachDataSourceRunners(psiFile, ...)` 用 psiFile 找数据源（Temp 文件同样 `runners=2`）；表解析取决于 session 连接状态，**`Access denied`（密码错误/未连接）→ schema 空 → 表无法解析**。文件放项目内只是满足"文件在项目内"预期；凭证正确连接成功后表可解析（用户截图 `1 row` 已证明）
- 验证：ExecuteSqlErrorHandlingTest 15/15 + ExecuteSqlSupportTest 3/3 = 18 通过；未提交 git

**M36.5.13 当前状态（2026-08-29，File Cache Conflict 连锁 + 模态挂起修复，最新）**：
- 用户复测四问题**同根一条链**：`projectExecFile` 用 `FileUtil.writeToFile` 直写已在 VFS 的 mpsql-exec.sql（console 编辑器持其 document）→ `MemoryDiskConflictResolver - reload from disk?`（日志 12:08:06）**模态弹窗** → **默认 modality 的 invokeLater 全冻结**（铁证：executeSql 12:10:02 → retry#1 12:10:50，48s 期间无 retry#2——Timer 启动任务在被冻结的队列里；dataContext check 12:09:55→12:10:50 延迟 55s 旁证）→ 换入/重试/提示全不跑 → "执行中"卡住 + 结果区停留第一次结果
- 修复四件套：① VFS 安全写入——vf 已存在 → `WriteAction.compute { vf.setBinaryContent(sql) }`（WriteAction 失败兜底直写），不存在才写盘+refresh；② 执行链全部 invokeLater 加 `ModalityState.any()`（executeOnConsole 三处 + attachResultsHook + logDataContextCheck + 超时提示）；③ refreshResultsComponent 加 `forceSwapIn`——startResultTabRetry 找到结果 Tab 即强制换入一次（组件实例恒定时"实例变化才换入"永不触发，新结果不显示的根因）；④ 提示符 refresh Timer 每拍清 + 换入后清
- 验证：18/18 测试通过；buildPlugin 后 jar（javap 验证含 setBinaryContent/ModalityState 标记）已 cp 同步沙盒；未提交 git
- 详细教训见 [[feedback-m36-native-console-misalignment]] 条件 10-13

**M36.5.14~21d 当前状态（2026-08-29，"执行内容滞后一轮"终局修复，最新）**：
- **现象精确模式**：编辑器 t.id=2 执行返回 t.id=1 的数据；改回 t.id=1 返回 t.id=2——**执行的永远是上一次的内容**
- **八轮迭代路线回顾（全部被沙盒/堆栈否决，勿重复踩）**：M36.5.14 getScriptModel() 重取（isActual() 误报新鲜，返回同一实例）→ M36.5.18 createROFile 文本直构（**RO file 缺 DBN 会话上下文，statements() 反射即抛**——14:55 会话 `stmts reflect failed`）→ M36.5.20 vf PSI model + commitDocument（三要素日志铁证：model class=SqlScriptModel/stmts=1/vfPsiText=新 SQL 全对，**结果仍旧 → executeQueries 内部对传入 model 的处理存在状态依赖**）→ M36.5.21 原生两步链（pooled ReadAction 内调 doRunQueryInConsole 抛线程违规——它是 EDT 动作体）→ M36.5.21c invokeLater(any)（**write-unsafe：doRunQueryInConsole→chooseStatements→saveAllDocuments 需 write-safe context**，用户堆栈实锤 "not any"）→ **M36.5.21d 终局：TransactionGuard**
- **M36.5.21d 终局方案**：执行链切**原生两步**（复刻 Ctrl+Enter）：`JdbcConsoleProvider.findScriptModel(psiFile, psiElement, editor, execOption)` → Info → `doRunQueryInConsole(console, info)`（两个 public static），整体包 **`TransactionGuard.getInstance().submitTransactionLater(console as Disposable) { ... }`**（write-safe 事务，与原生用户事件同机制；挂 console 为 Disposable 面板关闭取消挂起事务）；外层 `invokeLater(any)` 仅转 EDT；executeQueries(freshModel vf PSI model + commitDocument) 降级兜底（事务提交本身失败才走）
- **三要素日志**（执行前必打，一击判定）：`executing model: class=SqlScriptModel, stmts=count=N, vfPsiText tail=...`——model 类型/语句数/PSI 内容一次看全；vf 同步+commit 日志 `synced+committed to vf`
- **配套修复**：execOption 改 `DatabaseSettings.getDefaultExecOption()`（new 空实例 execSelection=0 非法值 → statementsRange 零长度 → ▶ 恒灰）；vf 同步后显式 `PsiDocumentManager.commitDocument(fileDoc)`（同 tick 内 findFile 的 PSI 才对齐——"滞后一轮"之一环）；projectExecFile 用 WriteAction setBinaryContent（VFS 安全写入，防 File Cache Conflict）
- **8s 连接等待监控实证**：`no result tab within 8s (hasResult=true, namespaceOk=false)` —— 密码等待（Access denied）期间面板内红字提示落地；旧结果 Tab 存在时用 getCurrentNamespace() 判连接健康
- **BUILD_STAMP 版本戳**：每次 executeOnConsole 第一行打 `[M36] executeOnConsole begin (build=...)`——15:44 会话实证用户未重启沙盒导致"修复未生效"假象，先对版本再排查
- 验证：ExecuteSqlErrorHandlingTest 15/15 + ExecuteSqlSupportTest 3/3；各版本标记已 grep 验证进 jar 并同步沙盒；未提交 git
- 执行前**三要素日志**一击判定（model class/stmts count/vfPsiText tail）；语句元素取值用 `StatementIt.query()`（元素非 PsiElement，cast 恒 null）
- 若 `executing statements: count=0` → vf PSI model 语句校验自动回退 console.getScriptModel()（日志 `vf PSI model has 0 statements, fallback`）

**M36.2 八轮内嵌迭代教训（历史，勿重复踩）**：
1. ❌ `getUi().getComponent()` 只嵌结果区（缺编辑器+工具栏）
2. ❌ `consoleView.getComponent()` 多 Console output 块（LanguageConsoleView 拼合体）
3. ❌ `editor.component` + `ui.getComponent()` 自拼 SplitPane（M36.4 起证明可行——当时缺可见性兜底）
4. ❌ `logView.getContent().getComponent()` 构建前拿 `[0,0,0x0,invalid]`
5. ❌ 阻塞轮询等结果 Tab（密码挂起 3s 不出现）
6. ❌ 模态弹窗遮挡密码框
7. ❌ 只嵌 `logView.getContent().getComponent()` 仍 0 尺寸
8. ❌ 弹窗改非模态后组件仍 0 尺寸

**关键反编译结论**：
1. `consoleView.getComponent()` = LanguageConsoleView 拼合体——必多一块
2. `logView.getContent().getComponent()` 在 ContentManager 外曾 `[0,0,0x0,invalid]`
3. 结果 Tab 由 `executeQueries` 异步创建——EDT Timer 非阻塞重试
4. `ensureContentInitialized` 在 BaseLogView(logView) 上
5. `DatabaseRunners$MyRunner` 包私有——反射须 `isAccessible = true`
6. `runInDataSource`/`MyRunner.run()` 只建 session 不执行——须 Consumer 回调续接 attachConsole + executeQueries
7. `DatabaseSettings$ExecOption` 非 enum——`getDeclaredConstructor().newInstance()`；executeQueries 反射签名用 `execOption.javaClass`（缓存实例运行时类）更稳健
8. 模态 `DialogWrapper(project,true)` 遮挡原生密码框——改 `false`
9. 沙盒 jar 文件锁/增量构建滞后——见 [[project-sandbox-jar-file-lock]]

**JDBC 方案要点（M36.2i，已被原生内嵌主线取代，留档）**：
- `JdbcExecutor` 反射 `LocalDataSource.getUrl/getUsername/getDriverClass` + PasswordSafe 取密码 + 驱动加载兜底
- 返回 `SqlExecuteResult(columns/rows/error)` → JBTable 渲染

**Why:** M36.2i 的 JDBC 直连是被"内嵌不可解"假象逼退的；M36.5 系列补齐时机/上下文/复用/等 smart 后原生内嵌成为主线（体验更原生：会话下拉/结果表格/原生工具栏）。
**How to apply:** M36 后续：原生控制台内嵌为主路径；执行提交一律 runWhenSmart + 代际校验；探针读 PSI 包 ReadAction。关联 [[feedback-m36-native-console-misalignment]] [[project-sandbox-jar-file-lock]]。

**M36.6 收尾阶段（2026-08-30 起）**：M36.5 未解决问题清单转入新计划文档 `docs/开发计划/20260830-0038-M36.6-SQL预览原生控制台收尾-开发计划.md`，后续轮次记录写该文档 §五（M36.5 文档已 1200+ 行不再追加）。M36.6.1/M36.6.2 实施与 ▶ 灰探针重建见 [[project-m36-native-finish]]。
