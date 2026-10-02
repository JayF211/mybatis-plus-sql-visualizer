package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.FatalErrors
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import javax.swing.Icon

/**
 * 原生数据源选择器（M36，反射隔离）：选数据源 → Database 控制台直接执行。
 *
 * 流程（选中后自动执行，等价原生 Ctrl+Enter 全链路）：
 *  1. 创建临时 .sql 文件（含 SQL）+ PsiFile —— 同时作为 [VF] 控制台挂载文件
 *  2. 反射 `DatabaseRunners.getAttachDataSourceRunners(psiFile, sql, consumer)` 获取
 *     原生 Runner 列表（与原生 Ctrl+Enter 数据源列表同源；consumer 在 runner.run 后收到
 *     [com.intellij.database.console.session.DatabaseSession]）
 *  3. 选中 runner → `run()` 内部建 session 并回调 consumer
 *  4. consumer 内续接执行链：`JdbcConsoleProvider.attachConsole(project, session, vf)`
 *     将控制台挂到临时文件 → 打开编辑器 → `console.executeQueries(editor, scriptModel, option)`
 *     直接执行（结果在控制台下方原生展示，无需手动 Ctrl+Enter）
 *
 * **关键教训**（多轮迭代定位）：
 *  - `MyRunner` 是包私有类 → 反射须 `isAccessible = true`
 *  - `runInDataSource`/`MyRunner.run()` 只**建 session 不执行**——SQL 执行必须由
 *    consumer 回调续接 `attachConsole + executeQueries`（此前传空 Consumer 导致"选中无反应"）
 *
 * 反射隔离约定：本类仅当 Database 插件已加载时被调用，Community 下永不加载。
 */
object DataSourceSelector {

    /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
    private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"

    /** DatabaseRunners 类名（反射入口）。 */
    private const val RUNNERS_CLASS = "com.intellij.database.console.DatabaseRunners"

    /** 执行链反射目标类名。 */
    private const val CONSOLE_PROVIDER_CLASS = "com.intellij.database.console.JdbcConsoleProvider"
    private const val CONSOLE_CLASS = "com.intellij.database.console.JdbcConsole"
    private const val SESSION_CLASS = "com.intellij.database.console.session.DatabaseSession"

    /** last-used 数据源记忆 key（prepareConsole 静默复用，PropertiesComponent per-project）。 */
    private const val LAST_RUNNER_KEY = "mpsql.visualizer.last.runner"

    /**
     * T-SI-02：last-used 记忆的**身份键**（数据源 UUID，LocalDataSource.getUniqueId）。
     * 旧键（显示名）继续读写，兼容一个版本后废弃（见 CHANGELOG [Unreleased]）；命中判定以
     * UUID 为主、显示名为回退——显示名同名多条时旧实现静默取首条，属「猜」，已弃。
     */
    private const val LAST_RUNNER_ID_KEY = "mpsql.visualizer.last.runner.id"

    /**
     * M36.5.21b：构建版本戳——与源码同文件维护，每次执行打日志。
     * 判定法：沙盒日志若与本串不符 = 沙盒跑旧 jar（未重启 IDE），先重启再排查。
     */
    private const val BUILD_STAMP = "M36.8-exec-silent-noop"

    /** M36.5.76：OUTFILL 未锚定诊断日志节流（EDT 串行访问，无需同步）。 */
    private var lastOutFillDiagAt = 0L

    // M36.5：已准备控制台缓存（prepareConsole 填充，executePrepared 消费；组件本体由 IDE 管理）
    private var preparedConsole: Any? = null
    private var preparedEditor: com.intellij.openapi.editor.Editor? = null
    private var preparedScriptModel: Any? = null
    private var preparedExecOption: Any? = null
    private var preparedUi: Any? = null

    // M36.5.4：已备控制台的 UI 块——顶部块（原生头部工具栏+编辑器 DataProvider 包装）、结果区、
    // 完整 SplitPane。内嵌统一为完整 SplitPane（三条执行路径结果都可见）；executeOnConsole 的
    // onResultsReady 换入兜底仅在完整 UI 缺失时按需重建（SplitPane 构造会 reparent 顶部块，
    // 已被内嵌"偷走"的顶部块不可复用——预建实例缺子组件的教训见 M36.5.2）。
    private var preparedTopBlock: javax.swing.JComponent? = null
    private var preparedResultComponent: javax.swing.JComponent? = null
    private var preparedFullComponent: javax.swing.JComponent? = null

    // M36.5.5：共享控制台缓存（跨预览复用，原生 IDE 语义：一个数据源一个常驻控制台）。
    // 沙盒三次实证（00:22 run2 / 00:27 run5 / 01:40 runB）：每预览新建 session 的模式下，
    // **关闭上一个预览弹窗后，下一个会话的连接永不发起**（无任何日志/报错，结果区恒
    // "0 rows of 0+"；与 dumb mode 无关——同窗口内首个会话在索引中照样秒连）。复用同一
    // 控制台后不再有第二连接，首次连接保持温热；复用时仅同步 SQL 进控制台文档 + 重挂 UI。
    private var sharedConsole: Any? = null
    private var sharedEditor: com.intellij.openapi.editor.Editor? = null
    private var sharedScriptModel: Any? = null
    private var sharedExecOption: Any? = null
    private var sharedUi: Any? = null
    private var sharedFullComponent: javax.swing.JComponent? = null
    private var sharedDataSourceName: String? = null

    /** T-PreviewFlash：mpsql-exec.sql 的 vf+psi 配对缓存（路径稳定，vf 有效即复用，吸收每次预览的 VFS refresh + findFile 慢操作）。
     *  T-ExecSlowOps F3：[prewarmExecTempFile] 后台线程成为第二写方后须 volatile 跨线程可见。 */
    @Volatile
    private var cachedTempPsiFile: Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>? = null
    /** T-PreviewExecDs-02 F1：缓存归属项目名（跨项目拒绝诊断用，与 cachedTempPsiFile 同生命周期）。 */
    @Volatile
    private var cachedTempPsiProjectName: String? = null

    /**
     * T-ExecCopyFix F2a：最近一次 createTempPsiFile 失败的阶段原因（stage 标识或异常摘要），
     * 成功时清空——供 chooseAndPrepare 的 onError 文案携带，用户截图反馈的笼统
     * "Failed to create temporary SQL file" 自此可归因。
     */
    @Volatile
    private var lastTempFileFailure: String? = null

    /** T-PreviewFlash：已 setMapping 的 vf（同文件幂等短路，避免每次预览重设方言触发 push all dumb）。
     * T-ExecSlowOps F3：BGT 预热链也会读写，volatile 保证跨线程可见。 */
    @Volatile
    private var dialectMappedVf: com.intellij.openapi.vfs.VirtualFile? = null

    /** T-ExecSlowOps F2：exec 预热进行中标记（并发预览只跑一条真实链，其余直接返回）。 */
    private val execPrewarmInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** M36.5.6：结果区换入回调（准备阶段挂 ContentManager 监听，任一路径出结果即换入；复用时由新面板接管）。 */
    private var pendingOnConsole: ((javax.swing.JComponent) -> Unit)? = null

    /** M36.5.9：最近一次换入宿主的结果区组件实例（执行后重取刷新的去重依据）。 */
    private var lastLiveResultSwapped: java.awt.Component? = null

    /** M36.5.52：最近一次已上报的 SQL 错误文本——早检去重用（避免 console history 里上次
     * 执行的残留错误每次早检重复上报；同一错误用户已见过则不再打扰）。 */
    private var lastReportedError: String? = null

    /** M36.5.57：本次执行开始时 console history 的长度基线——早检只扫**本次执行之后**新增的
     * 文本，忽略 history 里的旧错误残留（否则正常 SQL 会把上个失败的错误"带过来"，用户实测）。 */
    // T-PerfSweep2-01：internal 供 ConsoleErrorExtractTest 注入基线（生产路径仅 :2237 赋值）。
    internal var consoleErrorBaseline: Int = 0

    /** T-CEC-01：最近一次已记入日志的「未命中判据的控制台尾部」——取证日志去重用（每次执行至多
     * 记一条，避免 400ms 拍刷屏）。 */
    @Volatile
    private var lastUnrecognizedTail: String? = null

    /** T-ExecSilentNoop：本次执行的提示已发出标记——8s 连接监控与 40 拍超时窗口共用，
     * 保证同一次执行只报一次提示（此前二者各按同一 key 报一次，用户见两次红字）。 */
    @Volatile
    private var executionHintReported = false

    /**
     * M36.5.10：执行提交代际计数——executeQueries 提交经 DumbService.runWhenSmart 挂起期间，
     * 若面板清空（clearPrepared）/新预览建立新控制台上下文（prepareInConsole/reuseSharedConsole），
     * 代际不匹配的挂起提交直接放弃（否则会在新文档上执行旧预览的 SQL——文档共享）。
     */
    @Volatile
    private var executionGeneration = 0

    /**
     * M36.5.44：结果换入代际——每次**真实提交**递增（executeOnConsole 的 afterSubmitted 前），
     * 作废上一个执行遗留的 refreshResultsComponent Timer。跨执行竞态根因：SQL1 执行后起
     * refresh Timer A，SQL2 执行后起 Timer B；若 SQL1 的结果组件重建晚于 SQL2 换入，Timer A
     * 拿到旧结果组件会**覆盖** SQL2 的新结果（"滞后一轮"用户实证）。代际不匹配的 Timer 回调
     * 直接丢弃，杜绝互踩。
     */
    @Volatile
    private var swapGeneration = 0

    // M36.5.7：共享缓存的分件存储——复用/换入时按**实时结果区组件**重建完整 SplitPane。
    // （组合组件引用在面板分离/重挂后会过期：M36.5.5 复用面板空显示、M36.5.6 第二次预览
    // 结果栏消失、contentAdded 换入未写回共享缓存导致复用嵌了旧编辑器块，均是此类过期引用。）
    private var sharedTopBlock: javax.swing.JComponent? = null

    /** 控制台是否已就绪（prepareConsole 成功后 true）。 */
    @JvmStatic
    fun isPrepared(): Boolean = preparedConsole != null

    /**
     * R33 EXPLAIN 结构化通道：当前预览会话已绑控制台的 DBN 数据源（共享/已备控制台二者取一，
     * 循 T-ConsoleDbContext 既有选择形态——预览会话用哪条数据源，EXPLAIN 就在同一条上跑）。
     *
     * 控制台有效性先探（`isValid()` 反射，复用链同款判据——失效控制台的数据源对象不可信）；
     * 取引用包 ReadAction（EDT 读 PSI 硬抛平台陷阱；[ExecConsoleContext.boundDataSource]
     * 是 DBN 链内 API，Community 下经 McpDbExecutor.isDatabaseAvailable 前置闸不会到达）。
     * 无控制台/已失效/反射失败一律 null → 调用方 fail-closed 回退直送通道。
     */
    @JvmStatic
    fun boundConsoleDataSource(): Any? {
        val console = sharedConsole ?: preparedConsole ?: return null
        val live = runCatching {
            console.javaClass.getMethod("isValid").invoke(console) == true
        }.getOrDefault(false)
        if (!live) return null
        return ReadAction.compute<Any?, RuntimeException> {
            ExecConsoleContext.boundDataSource(console)
        }
    }

    /**
     * O-95 T-PreviewEdtGuard S3：当前预览会话绑定数据源**名**（预览面板绑定指示行数据源）。
     * 选择链与 [boundConsoleDataSource] 完全同源（共享/已备控制台二者取一 + isValid 先探 +
     * DBN 链内取引用）；取名走反射（本类对 DBN 类型一律反射惯例，Community/无 Database 插件
     * fail-closed → null）。`LocalDataSource.getName()` 出处：243 平台 jar 反编译
     * （ExecConsoleContext KDoc 同款口径：`JdbcConsole.getDataSource(): LocalDataSource`），
     * 与 `ds?.name`（ExecConsoleContext 诊断行在用）同一方法面。
     * null = 未绑定/失效/反射失败 → 面板显示未绑定灰字（panel.bindstate.unbound）。
     */
    @JvmStatic
    fun boundConsoleDataSourceName(): String? {
        val ds = boundConsoleDataSource() ?: return null
        return runCatching {
            ds.javaClass.getMethod("getName").invoke(ds) as? String
        }.getOrNull()
    }

    /**
     * O-96 测试缝：headless 无 Database 插件时注入「在籍数据源名单 + UUID→名解析」替代
     * 真链（[DataSourceSettings.getAllDataSources] 反射在 Community 沙盒恒空）；生产为 null
     * 走真链。测试用例负责置空复位（object 单例静态状态）。
     */
    internal var rememberedLookupForTest: ((Project) -> Pair<List<String>, (String) -> String?>)? = null

    /**
     * O-96 T-PreviewBindAssociation S1：last-used 记忆回退取名（绑定指示行第二优先级专用，
     * **只读**——歧义不清记忆、不弹窗、不触碰任何状态，展示路径禁副作用）。
     *
     * 判定与执行链 T-SI-02 静默复用**同一纯函数** [resolveLastUsedRunnerIndex]（UUID 精确命中
     * → 名字唯一命中 → 同名多条歧义即弃），runner 名单换为在籍数据源名
     * （[DataSourceSettings.getAllDataSources]）——指示行所说「下次执行会用哪条」与 prepareConsole
     * 静默复用的实际解析完全同源。记忆缺失 → null（快速早退）；UUID 失效（数据源已删）按
     * T-SI-02 口径退回名字判定；歧义/不存在 → null = 未绑定，不静默猜。
     *
     * 优先级契约（S3）：活控制台 [boundConsoleDataSourceName] 恒高于本记忆回退 > 未绑定。
     */
    @JvmStatic
    fun rememberedDataSourceName(project: Project): String? {
        val memory = lastUsedRunner(project)
        if (memory.dataSourceId.isNullOrBlank() && memory.displayName.isNullOrBlank()) return null
        val (registeredNames, nameById) = rememberedLookupForTest?.invoke(project) ?: run {
            DataSourceSettings.getAllDataSources(project).map { DataSourceSettings.getName(it) } to
                { id: String ->
                    runCatching {
                        DataSourceSettings.getDataSourceById(project, id)?.let { DataSourceSettings.getName(it) }
                    }.getOrNull()
                }
        }
        return resolveRememberedDataSourceName(memory, registeredNames, nameById)
    }

    /** O-96 S1：记忆回退取名判定（纯函数壳——复用 T-SI-02 判定，命中下标换算在籍名）。 */
    internal fun resolveRememberedDataSourceName(
        memory: LastUsedMemory,
        registeredNames: List<String>,
        dataSourceNameById: (String) -> String?,
    ): String? =
        resolveLastUsedRunnerIndex(memory, registeredNames, dataSourceNameById)
            .index?.let { registeredNames.getOrNull(it) }

    /** O-96 测试辅助：headless 直写 last-used 记忆双键（生产写入口 [rememberRunner] 为 private）。 */
    internal fun plantLastUsedMemoryForTest(project: Project, id: String?, name: String?) {
        val pc = com.intellij.ide.util.PropertiesComponent.getInstance(project)
        if (id.isNullOrBlank()) pc.unsetValue(LAST_RUNNER_ID_KEY) else pc.setValue(LAST_RUNNER_ID_KEY, id)
        if (name.isNullOrBlank()) pc.unsetValue(LAST_RUNNER_KEY) else pc.setValue(LAST_RUNNER_KEY, name)
    }

    /** 释放已准备控制台引用（面板清空/关闭时调用；JdbcConsole 本体由 IDE 管理）。 */
    @JvmStatic
    fun clearPrepared() {
        // M36.5.10：代际递增——作废所有挂起的 runWhenSmart 执行提交（面板清空）
        executionGeneration++
        preparedConsole = null
        preparedEditor = null
        preparedScriptModel = null
        preparedExecOption = null
        preparedUi = null
        preparedTopBlock = null
        preparedResultComponent = null
        preparedFullComponent = null
        // T-ExecCopyFix F2c：不清 cachedTempPsiFile——vf 失效守卫已兜底（下次命中前校验
        // isValid），内容覆盖写走 document 驱动不破坏 vf↔psi 配对；原实现每次预览切换清
        // 缓存，执行必落冷路径，击穿 T-PreviewFlash「跨点击吸收」的设计意图。
    }

    /**
     * T-Perf-16：面板关闭/项目关闭时释放单例滞留引用——object 单例的 prepared、shared、
     * pendingOnConsole 槽位强引用已关闭面板（pendingOnConsole 闭包捕获 SqlPreviewPanel 整棵
     * Swing 树）与原生控制台/编辑器/文档（持旧 Project 引用）。此前只有面板「清空」按钮走
     * clearPrepared，弹窗关闭/项目关闭路径无清理挂点 → 堆中滞留整棵面板树。
     */
    @JvmStatic
    fun releaseForPanel() {
        clearPrepared()
        dropSharedConsole()
        pendingOnConsole = null
    }

    /**
     * M36.5：准备控制台（不执行）——选数据源 → 建 session → attachConsole → 拼合组件回调。
     * 面板打开时调用，原生控制台即刻可见；执行由 [executePrepared] 触发。
     *
     * 数据源选择策略：唯一数据源/last-used 命中 → 静默；多数据源 → 原生弹窗（记住选择）；
     * 无数据源 → 预览路径静默 onError（不抢侧边栏），显式执行路径打开 Database 工具窗口 + onError。
     */
    @JvmStatic
    fun prepareConsole(
        project: Project,
        sql: String,
        onConsole: ((javax.swing.JComponent) -> Unit)? = null,
        onEditor: ((com.intellij.openapi.editor.Editor) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        callback: ((Boolean) -> Unit)? = null,
    ) {
        chooseAndPrepare(project, sql, executeAfterPrepare = false,
            onConsole = onConsole, onEditor = onEditor, onError = onError, callback = callback)
    }

    /**
     * M36.5：在已准备的控制台上执行（[prepareConsole] 成功后调用）。
     * @param onResultsReady 执行触发成功回调：参数为完整 UI（顶部块+结果区 SplitPane），
     *                       调用方据此把右侧从纯编辑器切换为带结果区的完整控制台
     * @return false = 控制台未就绪（调用方回退全链 selectAndExecute）
     */
    @JvmStatic
    fun executePrepared(
        project: Project,
        onResultsReady: ((javax.swing.JComponent) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        onErrorRich: ((ExecutionErrorInfo) -> Unit)? = null,
        wholeScriptExec: Boolean = false,
    ): Boolean {
        val console = preparedConsole ?: return false
        val editor = preparedEditor ?: return false
        val scriptModel = preparedScriptModel ?: return false
        val execOption = preparedExecOption ?: return false
        executeOnConsole(project, console, editor, scriptModel, execOption, preparedUi, onResultsReady, onError, onErrorRich, wholeScriptExec = wholeScriptExec)
        return true
    }

    /**
     * 选择数据源并执行 SQL（准备 + 执行合一，等价原生 Ctrl+Enter 全链路）。
     * 控制台已就绪时（[isPrepared]）仅触发执行，不重建。
     *
     * @param project   当前项目
     * @param sql       SQL 文本
     * @param onConsole 控制台就绪回调：参数为拼合的原生 UI 组件（编辑器+结果区）。
     *                  调用方可将其内嵌到任意容器；null = 不需要内嵌
     * @param onEditor  控制台编辑器就绪回调（注入插件补全上下文等）；null = 不需要
     * @param onError   错误回调（执行链任何环节失败时调用，参数为错误信息；null = 不通知）
     * @param callback  完成回调（true=已触发执行；false=不可用/无数据源/取消）
     */
    fun selectAndExecute(
        project: Project,
        sql: String,
        onConsole: ((javax.swing.JComponent) -> Unit)? = null,
        onEditor: ((com.intellij.openapi.editor.Editor) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        callback: ((Boolean) -> Unit)? = null,
        onErrorRich: ((ExecutionErrorInfo) -> Unit)? = null,
        wholeScriptExec: Boolean = false,
    ) {
        chooseAndPrepare(project, sql, executeAfterPrepare = true,
            onConsole = onConsole, onEditor = onEditor, onError = onError, callback = callback, onErrorRich = onErrorRich,
            wholeScriptExec = wholeScriptExec)
    }

    /**
     * M36.5：公共链——数据源选择 → session → attachConsole → 拼合组件（→ 可选立即执行）。
     *
     * @param executeAfterPrepare true = 准备后立即执行（原 selectAndExecute 语义）；
     *                            false = 仅准备（prepareConsole，控制台默认展示）
     */
    private fun chooseAndPrepare(
        project: Project,
        sql: String,
        executeAfterPrepare: Boolean,
        onConsole: ((javax.swing.JComponent) -> Unit)?,
        onEditor: ((com.intellij.openapi.editor.Editor) -> Unit)?,
        onError: ((String) -> Unit)?,
        callback: ((Boolean) -> Unit)?,
        onErrorRich: ((ExecutionErrorInfo) -> Unit)? = null,
        wholeScriptExec: Boolean = false,
    ) {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        if (!ExecuteSqlSupport.isAvailable()) {
            LOG.warn("[M36] database plugin NOT available, abort")
            onError?.invoke(MpSqlVisualizerBundle.message("execute.noDatabase"))
            callback?.invoke(false)
            return
        }

        // T-MysqlGStrip T-MG-02：提交链总口——SQL 文本进入 exec 文件/控制台文档之前剥离 MySQL CLI
        // 的 `\G` 后缀（DBX #35）。此点数据源尚未选定（runner 弹窗在下方），方言不可得 → 按计划
        // §三 定案默认剥离（`\G` 非任何方言合法语法，剥离无害）。剥后文本写入 exec 文件，
        // 控制台编辑器所见即所执行；历史链在预览侧记录原文，不受此处影响（两态分离）。
        val submitSql = stripGSuffixForSubmit(sql, null).submitText

        // M36.5.5：共享控制台命中（last-used 数据源一致且控制台仍有效）→ 直接复用——
        // 不同步 session/连接/临时文件/方言映射，规避"第二个会话连接永不发起"挂死（见字段注释）。
        // 显式执行路径同样复用（结果落在已嵌入面板）；用户切换数据源走弹窗 → 新建并替换共享缓存。
        if (reuseSharedConsole(project, submitSql, executeAfterPrepare, onConsole, onEditor, onError, callback, onErrorRich, wholeScriptExec)) return

        // 临时文件 + VF + PsiFile：Runner 上下文 + 控制台挂载点（VF 须保持引用）
        val temp = createTempPsiFile(project, submitSql)
        if (temp == null) {
            // T-ExecCopyFix F2a：携带阶段原因（stage 标识/异常摘要），本地化文案——
            // 用户真机曾只见笼统英文 "Failed to create temporary SQL file"，无从归因
            LOG.warn("[M36] createTempPsiFile failed (reason=${lastTempFileFailure}), abort")
            onError?.invoke(MpSqlVisualizerBundle.message("execute.tempFile.failed", lastTempFileFailure ?: "unknown"))
            callback?.invoke(false)
            return
        }
        val (vf, psiFile) = temp

        // consumer：runner.run 建好 session 后回调 → 续接准备链（attachConsole → 拼合 → 可选执行）
        var chosenRunnerName: String? = null
        val sessionConsumer = com.intellij.util.Consumer<Any> { session ->
            LOG.info("[M36] session created: ${session?.javaClass?.name}, attaching console (execute=$executeAfterPrepare, ds=$chosenRunnerName)")
            prepareInConsole(project, session, vf, executeAfterPrepare, chosenRunnerName, onConsole, onEditor, onError, onErrorRich, wholeScriptExec)
        }

        val runners = getAttachDataSourceRunners(psiFile, submitSql, sessionConsumer)
        LOG.info("[M36] runners=${runners.size}")

        // M36.5：last-used 数据源静默命中（仅 prepareConsole 路径；显式执行保持弹窗可选）
        // T-SI-02：命中判定升级为「UUID 精确命中 → 名字唯一命中」，同名多条判歧义弃记忆走弹窗
        //（旧实现 firstOrNull 静默取首条，选错数据源时用户无从察觉）。
        if (!executeAfterPrepare) {
            val memory = lastUsedRunner(project)
            val resolution = resolveLastUsedRunnerIndex(memory, runners.map { getRunnerName(it) }) { id ->
                runCatching {
                    DataSourceSettings.getDataSourceById(project, id)?.let { DataSourceSettings.getName(it) }
                }.getOrNull()
            }
            val matched = resolution.index?.let { runners.getOrNull(it) }
            if (matched != null) {
                val matchedName = getRunnerName(matched)
                LOG.info("[M36] silent reuse last runner: $matchedName (memory id=${memory.dataSourceId})")
                chosenRunnerName = matchedName
                runRunner(project, matched, silent = true, onError)
                callback?.invoke(true)
                return
            }
            if (resolution.ambiguous) {
                LOG.info("[M36] last-used memory ambiguous (id=${memory.dataSourceId}, name=${memory.displayName}) - drop memory, show popup")
                forgetLastRunner(project)
            }
        }

        when {
            runners.isEmpty() -> {
                LOG.warn("[M36] runners empty (execute=$executeAfterPrepare)")
                // T-DsCover：仅显式执行引导打开 Database 工具窗；预览默认准备静默——
                // 补全 M36.5 预览路径（onError=null）设计意图，避免每次双击预览抢占侧边栏。
                if (executeAfterPrepare) openDatabaseToolWindow(project)
                // M36.3：无数据源时通知 UI 从「执行中」恢复
                onError?.invoke(MpSqlVisualizerBundle.message("execute.datasource.empty"))
                callback?.invoke(false)
            }
            runners.size == 1 && shouldBeChosenIfAlone(runners.first()) -> {
                LOG.info("[M36] single runner, run directly")
                chosenRunnerName = getRunnerName(runners.first())
                runRunner(project, runners.first(), silent = !executeAfterPrepare, onError)
                callback?.invoke(true)
            }
            else -> {
                LOG.info("[M36] show popup with ${runners.size} runners")
                showRunnerPopup(project, runners) { runner ->
                    LOG.info("[M36] popup selected: ${runner?.let { getRunnerName(it) }}")
                    // M36.5：记住选择（prepareConsole 路径下次静默复用；控制台创建时也会统一记忆）
                    if (!executeAfterPrepare && runner != null) {
                        rememberRunnerName(project, getRunnerName(runner))
                    }
                    if (runner != null) chosenRunnerName = getRunnerName(runner)
                    callback?.invoke(runner != null)
                    // 弹窗中用户刚做了主动选择，失败仍需模态引导（silent=false）
                    if (runner != null) runRunner(project, runner, onError = onError)
                }
            }
        }
    }

    /**
     * M36.5.5：共享控制台复用检查（last-used 数据源 == 共享缓存数据源且控制台/编辑器仍有效）。
     * 命中 → SQL 同步进控制台文档、重挂 UI、回调 true，返回 true；未命中返回 false 走全链。
     */
    private fun reuseSharedConsole(
        project: Project,
        sql: String,
        executeAfterPrepare: Boolean,
        onConsole: ((javax.swing.JComponent) -> Unit)?,
        onEditor: ((com.intellij.openapi.editor.Editor) -> Unit)?,
        onError: ((String) -> Unit)?,
        callback: ((Boolean) -> Unit)?,
        onErrorRich: ((ExecutionErrorInfo) -> Unit)? = null,
        wholeScriptExec: Boolean = false,
    ): Boolean {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        val console = sharedConsole ?: return false
        val editor = sharedEditor ?: return false
        val top = sharedTopBlock ?: return false
        val dsName = sharedDataSourceName ?: return false
        val scriptModel = sharedScriptModel ?: return false
        val execOption = sharedExecOption ?: return false
        if (lastRunnerName(project) != dsName) return false
        // M36.5.10：代际递增——复用会覆盖控制台文档（setText），作废挂起的 runWhenSmart 提交，
        // 防止旧预览 SQL 在新文档上执行（文档共享串扰）
        executionGeneration++
        val valid = runCatching {
            console.javaClass.getMethod("isValid").invoke(console) == true && !editor.isDisposed
        }.getOrDefault(false)
        // T-SI-01：复用身份 fail-closed——复用判定从「显示名相等 + isValid」升级为「显示名相等 +
        // isValid + 三分量探测的 dsInFacade 分量」（复用 attach 路径既有探测，不重造）。复用是
        // 低频路径，探测是内存级反查（DbPsiFacade.findDataSource），成本可接受。
        // 身份被拒时弃共享会话并清 last-used 记忆——旧实现只丢会话不清记忆，下一轮仍会按显示名
        // 静默命中（同名数据源场景直接钉错库），与 attach 路径同规：身份不可信即弃，不猜。
        val identity = probeConsoleValidity(console, project, LOG)
        if (!valid || !reuseIdentityAccepted(identity)) {
            LOG.warn("[M36] shared console identity rejected (ds=$dsName, isValid=$valid, $identity), drop session")
            dropSharedConsole()
            if (identity?.dsInFacade == false) forgetLastRunner(project)
            return false
        }
        runCatching {
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                editor.document.setText(sql)
            }
        }
        // M36.5.24：复用路径同款刷新——setText 只更新 console document，console.getScriptModel()
        // 的缓存 model（isActual 仅比较 splitScriptMode）仍基于旧内容/空 vf → statementsRange=(0,0)
        // → ▶ 灰 + 无绿色边框。同步 vf document 并 commit；model 重建移入 runWhenSmart（dialect
        // push dumb task 完成后 PSI 才完整，M36.5.24g 22:30 实证）。
        var refreshedModel: Any? = scriptModel
        runCatching {
            val consoleVf = console.javaClass.getMethod("getVirtualFile").invoke(console)
                as? com.intellij.openapi.vfs.VirtualFile
            if (consoleVf != null) {
                @Suppress("DEPRECATION") // T-M52：WriteAction.compute(ThrowableComputable) 243 编译目标仅此重载可用
                com.intellij.openapi.application.WriteAction.compute<Unit, Throwable> {
                    // T-ExecDocSync2 F1：同步收口为 writeVfTextViaDocument（T-EC-01 语义超集：
                    // setText-if-differ + commit + saveDocument；fileDoc==null 走 setBinaryContent）。
                    // 原实现只 commit 不落盘——console document 即 exec 文件 document（attachConsole
                    // 挂载点同文件），滞留「已修改未保存」+ 窗口期任意外部改盘 + VFS refresh =
                    // File Cache Conflict 弹窗（第四轮根因 G2，开发计划 §1.2）
                    writeVfTextViaDocument(project, consoleVf, sql)
                }
                // T-ConsoleDbContext B：复用链同样对齐库上下文（控制台可能来自别的库记忆）
                LOG.info("[M36] shared console db-context: ${ExecConsoleContext.alignToDataSourceDatabase(console)}")
                // M36.5.24g：dumb 退出后重建（同 prepareInConsole）
                val consoleForModel = console
                val vfForModel = consoleVf
                com.intellij.openapi.project.DumbService.getInstance(project).runWhenSmart {
                    // write-safe: 无 document 写（反射替换 DBN script model）
                    ApplicationManager.getApplication().invokeLater({
                        runCatching {
                            refreshConsoleModel(project, consoleForModel, vfForModel, LOG)
                        }.onFailure { e ->
                            LOG.warn("[M36] shared scriptModel refresh (smart) FAILED: ${e.message}")
                        }
                    }, com.intellij.openapi.application.ModalityState.any())
                }
            }
        }.onFailure { e ->
            LOG.warn("[M36] shared scriptModel vf-sync FAILED (keep cached): ${e.message}")
        }
        // M36.5.50：预览（execute=false 复用 console）时清空上个 SQL 的结果区残留——用户
        // "还没执行就显示 t.id=1 旧结果"（预览 A=t.id=2 时结果区仍显示 B=t.id=1）。预览应显示
        // 空态，执行后才出现结果。M36.5.46 的 realignSplitDivider 已保证清空后 bottom 为空
        // 组件也校准 divider（不塌缩，M36.5.45b 的塌缩问题已修）。
        // M36.5.62：连 console 历史（Output tab 回显的上个 SQL）一起清空——预览时应显示干净空态，
        // 不残留上个执行/预览 SQL 的回显文本（用户实测"预览 sqlA 时下方显示之前执行过的 sqlB"）。
        if (!executeAfterPrepare) {
            clearResultContents(sharedUi, LOG)
            clearConsoleHistory(console, LOG)
        }
        // M36.5.7：完整 UI 按**实时结果区组件**刷新——M36.5.41-A4 起不再重建 SplitPane：
        // 持久整控台原位换底（身份稳定，sameAsPrev=true），仅整控台容器缺失时回退重建。
        // （组合引用在面板分离/重挂后会过期——M36.5.5 复用面板空显示根因；顶部块（原生头部+编辑器）
        // 自旧面板"偷"入新面板。）
        val liveResult = runCatching {
            sharedUi?.javaClass?.getMethod("getComponent")?.invoke(sharedUi) as? javax.swing.JComponent
        }.getOrNull()
        val full = when {
            liveResult == null -> sharedFullComponent
            // M36.5.43：swap 助手强制换入（不再返回 null）——结果区从 DBN 宿主摘入本 SplitPane
            else -> swapResultIntoPersistentConsole(sharedFullComponent, liveResult, top, LOG)
        } ?: return false
        if (liveResult != null) lastLiveResultSwapped = liveResult
        sharedFullComponent = full
        preparedConsole = console
        preparedEditor = editor
        preparedScriptModel = refreshedModel ?: scriptModel
        preparedExecOption = execOption
        sharedScriptModel = refreshedModel ?: scriptModel
        preparedUi = sharedUi
        preparedTopBlock = top
        preparedResultComponent = liveResult
        preparedFullComponent = full
        onEditor?.invoke(editor)
        onConsole?.invoke(full)
        // M36.5.55：预览（execute=false）复用 console 时也把结果区（bottom）折叠——divider 压到
        // 接近底部，bottom（DBN Output/Tx 结果区占位）收起不显示，满足"未执行不显示结果面板"。
        if (!executeAfterPrepare) {
            (full as? javax.swing.JSplitPane)?.let { sp ->
                // write-safe: UI-only（纯 Swing/只读，无文档写）
                ApplicationManager.getApplication().invokeLater({
                    sp.setDividerLocation(1.0)
                    sp.revalidate()
                    sp.repaint()
                }, com.intellij.openapi.application.ModalityState.any())
            }
        }
        // M36.5.6：接管结果换入回调——后续任意路径执行产生新结果内容时换入本面板；
        // 已嵌完整组件场景由面板同实例防抖幂等吸收。
        pendingOnConsole = onConsole
        logDataContextCheck(top, console, editor, execOption, LOG)
        clearConsolePrompt(console, LOG)
        LOG.info("[M36] shared console reused (ds=$dsName, execute=$executeAfterPrepare, liveResult=${liveResult != null})")
        if (executeAfterPrepare) {
            executeOnConsole(project, console, editor, refreshedModel ?: scriptModel, execOption, sharedUi, onResultsReady = null, onError = onError, onErrorRich = onErrorRich, wholeScriptExec = wholeScriptExec)
        }
        callback?.invoke(true)
        return true
    }

    /** M36.5.5：释放共享控制台缓存（控制台失效时懒清理；JdbcConsole 本体由 IDE 管理）。 */
    private fun dropSharedConsole() {
        sharedConsole = null
        sharedEditor = null
        sharedScriptModel = null
        sharedExecOption = null
        sharedUi = null
        sharedTopBlock = null
        sharedFullComponent = null
        sharedDataSourceName = null
    }

    /**
     * M36.5.50：清空 RunnerLayoutUi 的**结果 Tab**（保留 Output 输出与 Tx 控制台），
     * 用于预览切换时消除上个 SQL 的结果残留。反射调用 DBN 的 removeContent。
     * 与 M36.5.45b 的差异：彼时清空后无 realignSplitDivider 兜底导致布局塌缩，已回退；
     * 现 M36.5.46 换入后校准 divider，清空后 bottom 为空组件也不再塌缩。
     */
    private fun clearResultContents(ui: Any?, LOG: com.intellij.openapi.diagnostic.Logger) {
        if (ui == null) return
        runCatching {
            val contents = ui.javaClass.getMethod("getContents").invoke(ui) as? Array<com.intellij.ui.content.Content>
                ?: return
            val remove = ui.javaClass.getMethod("removeContent", com.intellij.ui.content.Content::class.java, Boolean::class.javaPrimitiveType)
            contents.filter { !isOutputContent(it) }.forEach { content ->
                runCatching { remove.invoke(ui, content, true) }.onFailure { ex ->
                    LOG.warn("[M36] clear result content FAILED: ${ex.message}")
                }
            }
            LOG.info("[M36] result contents cleared (preview switch, removed=${contents.count { !isOutputContent(it) }})")
        }.onFailure { e ->
            LOG.warn("[M36] clear result contents FAILED: ${e.message}")
        }
    }

    /**
     * M36.5.57：取 console history 文档长度（反射 LanguageConsoleView historyViewer），
     * 用作早检基线——本次执行后新增文本的起始偏移。
     */
    private fun consoleHistoryLength(console: Any?): Int {
        if (console == null) return 0
        return runCatching {
            val consoleView = console.javaClass.getMethod("getConsoleView").invoke(console) ?: return 0
            val historyViewer = consoleView.javaClass.getMethod("getHistoryViewer").invoke(consoleView) ?: return 0
            val doc = historyViewer.javaClass.getMethod("getDocument").invoke(historyViewer) ?: return 0
            (doc.javaClass.getMethod("getTextLength").invoke(doc) as? Int) ?: 0
        }.getOrDefault(0)
    }

    /**
     * T-ExecSilentNoop F1a 探针：提交前的一次性可执行性预检 + 现场埋点。
     *
     * DBN 侧存在两条**全静默出口**（javap 实证，database-plugin.jar）：`chooseStatements`
     * 空选集且 `execOutside==1` 时既不消费 model 也不提示、直接 return；`beforeExecuteQueries`
     * 遇 `model.statements().isEmpty()` 或 `console.isValid()==false` 返回 false → 整段跳过。
     * 两者都无输出、无结果 Tab、无异常。此探针把这两个出口对应的现场量提前照出来，
     * 归因"查询没提交"具体撞的是哪一条，并据此决定是否走 `executeQueries(freshModel)` 兜底。
     * 纯反射 + runCatching，任何一步失败降级为 null，绝不阻断执行。
     *
     * @return null 表示"DBN 侧可执行（或探不到），按 native 链走"；
     *         非 null 表示"预检判定 DBN 侧会静默拒绝"，返回真实原因描述。
     */
    private fun execSilentProbe(
        console: Any,
        freshModel: Any?,
        demoted: Boolean,
        destDsName: String?,
        LOG: com.intellij.openapi.diagnostic.Logger,
    ): String? {
        LOG.info("[M36][EXEC] probe start (console isValid=${runCatching { console.javaClass.getMethod("isValid").invoke(console) }.getOrNull()}, " +
            "freshModel stmts=${runCatching { freshModel?.javaClass?.getMethod("statements")?.invoke(freshModel) as? Iterable<*> }.getOrNull()?.toList()?.size}, " +
            "ds=$destDsName)")
        if (demoted) {
            val reason = "freshModel demoted (vf PSI resolved 0 statements, fell back to console model)"
            LOG.info("[M36][EXEC] probe demoted -> $reason")
            return reason
        }
        return null
    }

    /**
     * M36.5.48：从 console 历史文档提取真实错误文本——仅当**超时无结果**（真失败/真超时）时
     * 调用。DBN 查询执行失败（SQL 语法错/表不存在等）会把错误消息写入 console history。
     *
     * 提取规则（防误判）：只认**明确的 SQL 错误判据**（[isErrorSignatureLine]），且错误行不得是
     * 纯 SQL 语句（含 SELECT/FROM/WHERE/JOIN 等 SQL 词），避免把正常 SQL 历史误判为错误
     * （日志实证 `extracted console error: WHERE t.id = 1`——A 并无真失败，正常 SQL 片段被误报）。
     *
     * M36.5.57：只扫 **consoleErrorBaseline 之后**的文本——历史残留错误（上个失败 SQL 写入的
     * Unknown column 等）不在本次范围，正常 SQL 执行后不会"带过来"旧错误。
     *
     * T-CEC-02（用户真机 P15）：判据由「短语词表」升级为「结构性判据 + 短语族」——原词表对
     * `[42000][1064] You have an error in your SQL syntax`（MySQL 语法错）一条都不含，面板因此
     * 全程无红字、无复制按钮，用户只能在 Output 页自己找。同批漏检的还有 Oracle `ORA-00933`、
     * MySQL 1062/1451 等；历史真机验证过的错误恰好全是 `Unknown column` 系，故缺口长期未现。
     */
    internal fun extractConsoleError(console: Any?, LOG: com.intellij.openapi.diagnostic.Logger): String? {
        if (console == null) return null
        return runCatching {
            val text = newConsoleText(console) ?: return null
            if (text.isBlank()) return null
            val lines = text.lines().filter { it.isNotBlank() }
            // 从末尾往前找"命中错误判据"且"非纯 SQL / 非本插件自写回显"的行
            val errorLine = lines.lastOrNull { line ->
                isErrorSignatureLine(line) && !isSqlLine(line) && !isOwnEchoLine(line)
            } ?: run {
                // T-CEC-01：未命中即取证——记下本次新增文本的尾部（有界 + 每次执行只记一次），
                // 下次扩判据时以真机报文为准，不照用户截图猜形态。
                logUnrecognizedConsoleTail(lines, LOG)
                return null
            }
            // 取错误行及其后非空行构成可读上下文——错误消息常跨多行（MySQL 语法错把 "near …" 的
            // SQL 片段跟在其后）。T-CEC-02：4 行放宽到 8 行，并剥掉逐行的 `[时间戳] ` 与提示符。
            val prompt = consolePrompt(console)
            val idx = lines.indexOf(errorLine)
            val excerpt = lines.subList(idx, (idx + EXCERPT_MAX_LINES).coerceAtMost(lines.size))
                .joinToString("\n") { stripConsolePrefix(it, prompt) }
            // T-ConsoleDbContext A：附「当前库 / 数据源 / 数据源默认库」上下文——真实事故中 SQL 本身
            // 没问题，是执行落在了错的库（mpj_demo 的 user 表没有 username → 1054），而裸 MySQL 报文
            // 看不出这一层；带上上下文后一眼可归因
            val ctxLine = ExecConsoleContext.describeContext(console)
            val enriched = if (ctxLine != null) "$excerpt\n$ctxLine" else excerpt
            LOG.info("[M36] extracted console error: ${enriched.take(200)}")
            enriched
        }.getOrNull()
    }

    /**
     * T-CEC-03：兜底呈现——判据认不出的失败，把控制台**新增文本去掉本次 SQL 回显后的残余**交回
     * 面板，而不是给「SQL 已提交执行，但等待结果超时……检查密码输入框/测试连接」这类笼统提示
     * （真机 P15 实证：把一条 MySQL 语法错归因成密码/驱动问题，归因方向是错的）。
     *
     * 只做「减去回显」不做任何语义判断：残余是什么就报什么，面板侧附一句如实说明
     * （`execute.error.unknownShape`）——不声称它是 SQL 错误，也不猜原因。
     *
     * 剔除规则（宁少勿错）：空行；DBN 回显行（剥掉时间戳/提示符后是本次执行 SQL 的子串）；
     * DBN 状态行（Connected / completed in … 等）。全部被剔完返 null（调用方落回既有提示）。
     */
    internal fun extractConsoleResidue(
        console: Any?,
        sqlText: String?,
        LOG: com.intellij.openapi.diagnostic.Logger,
    ): String? {
        if (console == null) return null
        return runCatching {
            val text = newConsoleText(console) ?: return null
            if (text.isBlank()) return null
            val sqlNorm = normalizeForEcho(sqlText.orEmpty())
            val prompt = consolePrompt(console)
            val kept = text.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .filterNot { isConsoleStatusLine(it) }
                .filterNot { isSqlEchoLine(it, sqlNorm, prompt) }
            if (kept.isEmpty()) return null
            val joined = kept.takeLast(RESIDUE_MAX_LINES).joinToString("\n")
            val out = if (joined.length > RESIDUE_MAX_CHARS) joined.take(RESIDUE_MAX_CHARS) else joined
            LOG.info("[T-CEC] console residue (no known signature): ${out.replace('\n', '⏎').take(200)}")
            out
        }.getOrNull()
    }

    /**
     * T-CEC：读取 console history 文档中**基线之后**的新增文本（读不到 / 无新增返 null）。
     *
     * T-PerfSweep2-01 的片段物化契约在此保持（ConsoleErrorExtractTest 逐条断言）：baseline<=0
     * 走整串一次、>0 只切一刀、无增长零物化——本函数是两个定时器逐拍调用的共用读取面，
     * 抽出来只为让兜底呈现复用同一份切片语义。
     */
    private fun newConsoleText(console: Any?): String? {
        if (console == null) return null
        val consoleView = console.javaClass.getMethod("getConsoleView").invoke(console) ?: return null
        val historyViewer = consoleView.javaClass.getMethod("getHistoryViewer").invoke(consoleView) ?: return null
        val doc = historyViewer.javaClass.getMethod("getDocument").invoke(historyViewer) ?: return null
        // T-PerfSweep2-01：getCharsSequence + subSequence 片段读取——本函数被 600ms×8 拍与
        // 400ms×40/100 拍两个定时器**逐拍**调用，此前 doc.getText() 每拍整串拷贝全史
        // （history 随长会话涨到 MB 级后是 EDT 上唯一可感知的 O(全史) 重复分配）。
        // 只物化 baseline 之后的切片；getCharsSequence 与 getText 同属 Document 接口，
        // 反射可达性一致，且免去 BadLocationException 面。
        val seq = doc.javaClass.getMethod("getCharsSequence").invoke(doc) as? CharSequence ?: return null
        // M36.5.57：只扫本次执行之后新增的文本（基线截断）——忽略 history 残留的旧错误。
        // baseline 可能因 DBN 写入历史而增长；若文本长度 <= 基线说明无新增内容，返回 null。
        val baseline = consoleErrorBaseline
        return when {
            baseline <= 0 -> seq.toString()
            seq.length > baseline -> seq.subSequence(baseline, seq.length).toString()
            else -> null
        }
    }

    /**
     * T-CEC-01：未命中判据的控制台新增文本尾部（有界 + 同一段只记一次）——取证用，供下次扩判据
     * 时核对 DBN 真实写入 history 的报文形态（是否带 `[时间戳]` 前缀、是否跨行、SQLSTATE 前缀
     * 是否在场）。行数与字符数双上限，避免长会话刷屏。
     */
    private fun logUnrecognizedConsoleTail(lines: List<String>, LOG: com.intellij.openapi.diagnostic.Logger) {
        val tail = lines.takeLast(UNRECOGNIZED_TAIL_LINES).joinToString("\n").take(UNRECOGNIZED_TAIL_CHARS)
        if (tail.isBlank() || tail == lastUnrecognizedTail) return
        lastUnrecognizedTail = tail
        LOG.info("[T-CEC] console tail (no error signature matched): ${tail.replace('\n', '⏎')}")
    }

    /** T-CEC-02：错误上下文摘录的最大行数（错误行起算）。 */
    private const val EXCERPT_MAX_LINES = 8

    /** T-CEC-01：取证日志的行数 / 字符数上限。 */
    private const val UNRECOGNIZED_TAIL_LINES = 6
    private const val UNRECOGNIZED_TAIL_CHARS = 600

    /** T-CEC-03：兜底呈现的行数 / 字符数上限。 */
    private const val RESIDUE_MAX_LINES = 6
    private const val RESIDUE_MAX_CHARS = 600

    /**
     * T-CEC-02：DBN 服务端错误前缀形态 `[SQLSTATE][errorCode]`——DBN 把驱动 SQLException 的
     * SQLState 与 vendor code 拼在报文最前（真机日志实证：`[42S22][1054] Unknown column …`、
     * `[42000][1064] You have an error in your SQL syntax …`）。跨方言同形，比短语词表硬：
     * 原词表认 `syntax error`，却认不出 `You have an error in your SQL syntax`。
     * 5 位定长是关键——`[2026-09-22 08:48:12]` 这类时间戳前缀（19 字符）不会被误命中。
     */
    private val SQLSTATE_PREFIX = Regex("""\[[0-9A-Za-z]{5}]\[\d+]""")

    /** T-CEC-02：Oracle 错误码形态（`ORA-00933` 等）——Oracle 报文常不带 SQLSTATE 前缀。 */
    private val ORACLE_CODE = Regex("""\bORA-\d{5}\b""", RegexOption.IGNORE_CASE)

    /** T-CEC-02：错误签名短语族（结构性判据覆盖不到的方言 / 驱动惯用报文）。 */
    private val ERROR_SIGNATURES = listOf(
        // M36.5.48 原有 14 条（历史命中系，行为保持不动）
        "sql exception", "syntax error", "sqlstate", "unknown column",
        "unknown table", "doesn't exist", "does not exist", "access denied",
        "deadlock", "error code", "broken pipe", "connection refused",
        "[\u65e5\u8bed\u9519\u8bef]", "sql\uff08", "exception in thread",
        // T-CEC-02 新增：MySQL 语法错（用户 2026-09-22 真机 Output 报文逐字）
        "you have an error in your sql syntax",
        // T-CEC-02 新增：厂商固定报文（MySQL 1062 / 1451、Oracle ORA-00933）
        "duplicate entry", "foreign key constraint",
        "cannot add or update a child row", "cannot delete or update a parent row",
        "sql command not properly ended",
    )

    /**
     * R33 真机走查修复：EXPLAIN 附随的 SHOW WARNINGS note 行（`[HY000][1003] Note: …`，
     * 内含重写查询）——informational，不是执行失败。MySQL 服务端 1003 号属 note 消息段
     * （ER_YES，不落失败码段）；结构化通道回退后直送通道执行 EXPLAIN 成功时，DBN 会把这条
     * note 写进 history，早检逐拍扫到即被 [SQLSTATE_PREFIX] 命中误报红条（真机实证：
     * 结果已正常出，底部红条却是这条 note）。整行排除（不要求行内带 "Note" 字样——DBN
     * 是否透出级别词无源码级实锤，宁可宽放：1003 号在 MySQL 不是失败码）。
     */
    private val NOTE_1003_PREFIX = Regex("""\[HY000]\[1003]""", RegexOption.IGNORE_CASE)

    /** T-CEC-02：一行是否命中错误判据（结构性判据 + 短语族；EXPLAIN note 1003 行除外）。 */
    private fun isErrorSignatureLine(line: String): Boolean =
        !NOTE_1003_PREFIX.containsMatchIn(line) &&
            (SQLSTATE_PREFIX.containsMatchIn(line) ||
                ORACLE_CODE.containsMatchIn(line) ||
                ERROR_SIGNATURES.any { line.contains(it, ignoreCase = true) })

    /**
     * T-CEC-02：本插件自己写进 Output 的回显行前缀（[writeConsoleOutput] 的「执行失败: 」）——
     * 提取时排除，避免「已上报的错误被自己写回控制台后又当成新错误」的自反馈。
     */
    private const val EXEC_FAILURE_ECHO_PREFIX = "执行失败:"

    private fun isOwnEchoLine(line: String): Boolean =
        stripConsolePrefix(line, null).startsWith(EXEC_FAILURE_ECHO_PREFIX)

    /** T-CEC-02：DBN 输出行的 `[时间戳] ` 前缀（用户真机报文逐行带此前缀）。 */
    private val TIMESTAMP_PREFIX = Regex("""^\[\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:[.,]\d+)?]\s*""")

    private fun stripTimestamp(line: String): String {
        val s = line.trim()
        val m = TIMESTAMP_PREFIX.find(s) ?: return s
        return s.substring(m.range.last + 1).trim()
    }

    /** T-CEC-02：剥掉时间戳与（DBN 实读的）提示符前缀——呈现与复制都更干净。 */
    private fun stripConsolePrefix(line: String, prompt: String?): String {
        var s = stripTimestamp(line)
        if (!prompt.isNullOrBlank() && s.startsWith(prompt)) s = s.substring(prompt.length).trim()
        return s
    }

    /**
     * T-CEC-02：读 DBN 控制台提示符（`mpj_demo>` / `ds>>`，即 `LanguageConsoleView.prompt`——
     * 243 源码 jar 实证为 Kotlin `var prompt: String?`，插件既有 [clearConsolePrompt] 已用 setPrompt）。
     * 取不到返 null：调用方退化为只剥时间戳，不猜提示符形态。
     */
    private fun consolePrompt(console: Any?): String? = runCatching {
        if (console == null) return null
        val consoleView = console.javaClass.getMethod("getConsoleView").invoke(console) ?: return null
        (consoleView.javaClass.getMethod("getPrompt").invoke(consoleView) as? String)
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** T-CEC-03：回显比对用的归一化（空白折叠 + 小写）。 */
    private val WHITESPACE_RUN = Regex("""\s+""")

    private fun normalizeForEcho(text: String): String =
        text.replace(WHITESPACE_RUN, " ").trim().lowercase()

    /** T-CEC-03：兜底剥离提示符的候选形态（仅当剩余部分是本次 SQL 子串时才据此判为回显）。 */
    private val PROMPT_CANDIDATE = Regex("""^\S{1,64}>+\s+""")

    /**
     * T-CEC-03：该行是否本次执行 SQL 的回显——剥掉时间戳后（必要时再剥提示符）是本次 SQL 文本的
     * 子串即认（多行 SQL 的每一行都是 SQL 的子串）。判据宽松方向是「多剔」：宁可残余变空退回
     * 既有提示，也不把回显当错误报给用户。
     */
    private fun isSqlEchoLine(line: String, sqlNorm: String, prompt: String?): Boolean {
        if (sqlNorm.isEmpty()) return false
        val body = stripTimestamp(line)
        val candidates = mutableListOf(body)
        if (!prompt.isNullOrBlank() && body.startsWith(prompt)) {
            candidates += body.substring(prompt.length).trim()
        }
        PROMPT_CANDIDATE.find(body)?.let { m -> candidates += body.substring(m.value.length).trim() }
        return candidates.any { c ->
            val n = normalizeForEcho(c)
            n.length >= 4 && sqlNorm.contains(n)
        }
    }

    /**
     * T-CEC-03：DBN 状态 / 结果摘要行——不是错误内容，兜底呈现时剔除。
     *
     * 判据刻意偏「多剔」：语句**成功**但 DBN 不建结果 Tab 的场景同样会走满 40 拍窗口（DML 与
     * `use` 这类工具语句的影响行数/完成回显就写在 Output 里），把 `1 row affected` 当残余报出来
     * 等于把成功说成失败；剔多了只是落回既有提示，属可接受方向（宁少勿错）。
     */
    private val CONSOLE_STATUS_LINES = listOf(
        "connected", "completed in", "executing", "fetching", "fetched",
        "cancelled", "canceled", "query ok", "affected", "took ", "elapsed",
    )

    /** T-CEC-03：结果摘要词（`1 row affected` / `N rows` / `updated` 等）——同上，偏多剔。 */
    private val CONSOLE_STATUS_WORDS =
        Regex("""\b(rows?|records?|updated|deleted|inserted)\b""", RegexOption.IGNORE_CASE)

    private fun isConsoleStatusLine(line: String): Boolean {
        val n = stripTimestamp(line).lowercase()
        return CONSOLE_STATUS_LINES.any { n.contains(it) } || CONSOLE_STATUS_WORDS.containsMatchIn(n)
    }

    /** M36.5.48：判断一行是否纯 SQL 语句（避免把「WHERE t.id=1」等当错误）。 */
    private fun isSqlLine(line: String): Boolean {
        val lower = line.trim().lowercase()
        // 含 SQL 关键字且无错误判据命中 → 大概率是 SQL 文本而非错误
        return (lower.startsWith("select") || lower.startsWith("from") ||
            lower.startsWith("where") || lower.startsWith("join") ||
            lower.startsWith("insert") || lower.startsWith("update") ||
            lower.startsWith("delete") || lower.startsWith("create") ||
            lower.startsWith("alter") || lower.startsWith("drop")) &&
            !isErrorSignatureLine(line)
    }

    /**
     * M36.5.8：清空控制台提示符（SQL 编辑器左侧 "rbac>"/">" 装饰）——用户反馈无用干扰。
     * 反编译实锤：DatabaseLogView 经 LanguageConsoleView.setPrompt(String) 维护（默认 ">"、
     * 带搜索路径时 "<数据源>>"）；置空后若数据源/搜索路径变化可能被插件重设，属可接受回退。
     */
    /**
     * M36.5.62：清空 console 历史文档（LanguageConsoleView historyViewer 的 document）——
     * 预览（execute=false）时清除上一个 SQL 的历史残留。DBN 每次执行/预览都会把 SQL 回显到
     * historyViewer（Output tab），`clearResultContents` 只移除 result Tab（ui.removeContent），
     * **保留 Output content**——Output tab 仍显示上个 SQL 的回显文本（用户实测"预览 sqlA 时下方有
     * 之前预览执行过的 sqlB"）。预览应显示干净空态，须连历史文档一起清空。
     * 注意：执行路径（execute=true）**不清**——执行后 DBN 需重新回显本次 SQL，历史清空会让
     * 执行结果上下文断链，且 consoleErrorBaseline 依赖历史长度基线（清空会改变基线语义）。
     */
    private fun clearConsoleHistory(console: Any?, LOG: com.intellij.openapi.diagnostic.Logger) {
        if (console == null) return
        runCatching {
            val consoleView = console.javaClass.getMethod("getConsoleView").invoke(console) ?: return
            val historyViewer = consoleView.javaClass.getMethod("getHistoryViewer").invoke(consoleView) ?: return
            val doc = historyViewer.javaClass.getMethod("getDocument").invoke(historyViewer) ?: return
            doc.javaClass.getMethod("setText", String::class.java).invoke(doc, "")
            LOG.info("[M36] console history cleared (preview switch)")
        }.onFailure { e ->
            LOG.warn("[M36] clear console history FAILED: ${e.message}")
        }
    }

    /** M36.5.8：清空控制台提示符（SQL 编辑器左侧 "rbac>"/">" 装饰）——用户反馈无用干扰。
     * 反编译实锤：DatabaseLogView 经 LanguageConsoleView.setPrompt(String) 维护（默认 ">"、
     * 带搜索路径时 "<数据源>>"）；置空后若数据源/搜索路径变化可能被插件重设，属可接受回退。
     */
    private fun clearConsolePrompt(console: Any, LOG: com.intellij.openapi.diagnostic.Logger) {
        runCatching {
            val consoleView = console.javaClass.getMethod("getConsoleView").invoke(console) ?: return
            consoleView.javaClass.getMethod("setPrompt", String::class.java).invoke(consoleView, "")
            LOG.info("[M36] console prompt cleared")
        }.onFailure { e ->
            LOG.warn("[M36] clear console prompt FAILED: ${e.message}")
        }
    }

    /**
     * M36.5.70：用 DBN ConsoleView 原生 print API 把文本写入 Output 控制台可视区。问题 SQL（报错
     * 无结果行）时 DBN 结果区内 Output 内容视图（JBScrollPane）布局为 0x0（`[TEXT]` 探针实证），
     * historyViewer document 有错误文本但 Output 面板不显示——用户"output 里没有信息"。走
     * ConsoleView.print(String, ConsoleViewContentType) 会经 DBN 原生输出链路写入并刷新显示，
     * 不依赖/不破坏结果区内部布局（结果网格照常）。反射调用避免对 ConsoleViewContentType 的
     * 编译期依赖（其所在模块可能随 IDE 版本变动）。
     *
     * T-FatalPassthrough T-FP-02b：`internal` 供 `FatalPassthroughTest` 直测本函数的**外层 runCatching
     * 边界**（同 [execFileContentMatches] / [isExecDocOpenInEditor] 先例）——私有则行为用例无处注入。
     */
    internal fun writeConsoleOutput(console: Any?, text: String, isError: Boolean = false) {
        if (console == null || text.isBlank()) return
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        runCatching {
            val consoleView = console.javaClass.getMethod("getConsoleView").invoke(console)
            if (consoleView == null) { LOG.info("[M36] writeConsoleOutput: consoleView null"); return }
            // ① 直接向 historyViewer 的 document 追加文本（Output 控制台显示的就是此 document）
            //    ——比走 ConsoleView.print 更可靠（不依赖 ConsoleViewContentType 枚举类是否存在）。
            val historyViewer = consoleView.javaClass.getMethod("getHistoryViewer").invoke(consoleView)
            if (historyViewer != null) {
                val doc = historyViewer.javaClass.getMethod("getDocument").invoke(historyViewer)
                if (doc is com.intellij.openapi.editor.Document) {
                    val append = if (doc.text.isNotEmpty() && !doc.text.endsWith("\n")) "\n" else ""
                    try {
                        val proj = (console as? com.intellij.openapi.project.Project) ?: runCatching {
                            console.javaClass.getMethod("getProject").invoke(console) as? com.intellij.openapi.project.Project
                        }.getOrNull()
                        @Suppress("DEPRECATION")
                        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(proj) {
                            doc.insertString(doc.textLength, "$append$text")
                        }
                        // 滚到末尾（新输出可见）
                        // write-safe: 无 document 写（反射替换 DBN script model）
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                            runCatching {
                                // 滚动到底：LanguageConsoleView 的 caret/scrollPosition
                                val editor = com.intellij.openapi.editor.EditorFactory.getInstance()
                                    .getEditors(doc).firstOrNull()
                                if (editor != null) {
                                    editor.caretModel.moveToOffset(doc.textLength)
                                    editor.scrollingModel.scrollToCaret(
                                        com.intellij.openapi.editor.ScrollType.RELATIVE,
                                    )
                                }
                            }
                        }, com.intellij.openapi.application.ModalityState.any())
                        LOG.info("[M36] writeConsoleOutput: appended to history doc len=${doc.textLength}")
                        return
                    } catch (e: Throwable) {
                        // T-FatalPassthrough T-FP-02：JVM 级致命错误透传（见 util/FatalErrors.kt）——
                        // 本文件是执行链长驻路径，捕获面覆盖 Error：普通异常（含可选能力探测的
                        // ClassNotFoundException）仍走既有降级，VirtualMachineError/LinkageError 不得
                        // 被吞成一条日志后让执行链带病继续。
                        if (FatalErrors.isFatal(e)) throw e
                        LOG.info("[M36] writeConsoleOutput doc-append FAILED: ${e.message}")
                    }
                }
            }
            // ② 兜底 1：ConsoleView.print(String, ConsoleViewContentType) 反射
            val contentClass = runCatching { Class.forName("com.intellij.execution.ui.ConsoleViewContentType") }.getOrNull()
            if (contentClass != null) {
                val ct = try {
                    val fieldName = if (isError) "ERROR_OUTPUT" else "NORMAL_OUTPUT"
                    contentClass.getField(fieldName).get(null)
                } catch (e: Throwable) {
                    if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传
                    contentClass.fields.firstOrNull()?.get(null)
                }
                val print = consoleView.javaClass.methods.firstOrNull { m ->
                    m.name == "print" && m.parameterCount >= 2 && m.parameterTypes[0] == String::class.java
                }
                if (ct != null && print != null) {
                    print.invoke(consoleView, text, ct)
                    LOG.info("[M36] writeConsoleOutput: ConsoleView.print ok")
                    return
                } else {
                    LOG.info("[M36] writeConsoleOutput: ct=${ct != null} print=${print != null}")
                }
            } else {
                LOG.info("[M36] writeConsoleOutput: ConsoleViewContentType class not found")
            }
        }.onFailure { e ->
            // T-FatalPassthrough T-FP-02b（独立复核阻断 1 修正）：本函数的 runCatching 包住上面两处
            // fatal 透传（doc-append 捕获块 / ConsoleViewContentType 字段回退捕获块）——在此把 fatal
            // 吞成一行 INFO 等于透传无效（改前实测：fatal 被转成 "[M36] writeConsoleOutput FAILED: …"
            // 后执行链带病继续，正是上面捕获块注释声称的反面）。反射链先解包 InvocationTargetException
            // 再判（同本文件原生执行链 :2604-2607 形态；只判外层会漏判目标方法抛出的 fatal）。
            // 非 fatal 路径行为逐字节不变：仍只记原 INFO 行，函数返回 Unit、调用方（EDT 排出的
            // writeConsoleOutput + onError 回调）行为不变；fatal 则透传到平台 EDT 处理器记录现场。
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            if (FatalErrors.isFatal(cause)) throw cause
            LOG.info("[M36] writeConsoleOutput FAILED: ${e.message}")
        }
    }

    /**
     * M36.5.8/M36.5.9/M36.5.13：执行后结果区刷新——**限时多次重取**。新执行会重建 RunnerLayoutUi 的
     * 内容组件，重建时机与数据到达相关（单次重取可能仍取到旧实例——M36.5.8 实测刷新命中后
     * 显示依旧停留上一轮）；EDT Timer 600ms×8 重取 `ui.getComponent()`。
     *
     * M36.5.13 变更：
     *  - [forceSwapIn] = true（startResultTabRetry 找到结果 Tab 后传入）：**立即强制换入一次**——
     *    旧 ui 的组件实例可能恒定（M36.5.10 盘点实证"刷新定时器 8 拍实例恒定"），"实例变化才换入"
     *    的去重导致新结果永远不显示（用户实测第二次执行后仍显示第一次结果）；换入后更新
     *    lastLiveResultSwapped 基线，后续实例变化仍可继续换入。
     *  - Timer **每拍清提示符**——DatabaseLogView 在执行/数据渲染时重设 prompt（用户实测
     *    prepare/reuse/入口清理后仍重现），4.8s 窗口内持续压制。
     */
    private fun refreshResultsComponent(ui: Any, LOG: com.intellij.openapi.diagnostic.Logger, forceSwapIn: Boolean = false, generation: Int = swapGeneration, onError: ((String) -> Unit)? = null, onErrorRich: ((ExecutionErrorInfo) -> Unit)? = null, sqlText: String? = null) {
        val callback = pendingOnConsole ?: return
        val top = sharedTopBlock ?: preparedTopBlock ?: return
        val shared = sharedConsole
        var ticks = 0
        var swapped = false
        // T-ExplainFirstNative：结果 Tab 名称集合逐拍快照——集合变化（空名占位改名/新 Tab 出现）
        // 说明结果内容更新，即使组件实例未变也要补一次换入重绘（首点网格晚于换入构建的兜底）
        var lastResultNames: Set<String>? = null
        val timer = javax.swing.Timer(600, null)
        timer.addActionListener(object : java.awt.event.ActionListener {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                ticks++
                // M36.5.44：代际不匹配 → 新执行已覆盖本 watch，丢弃（防"滞后一轮"互踩）
                if (generation != swapGeneration) {
                    timer.stop()
                    LOG.info("[M36] results refresh cancelled (generation $generation != ${swapGeneration})")
                    return
                }
                // M36.5.57：每拍早检错误（refresh 8 拍窗口覆盖 DBN 错误写入较晚的时机）——
                // 首次执行结果 Tab 复用后 startResultTabRetry 找到 result 即停，但 DBN 把错误
                // 写入 history 可能晚于其时点 → 首次执行漏检（用户"执行两次才出现错误"）。
                // 本 Timer 在此阶段仍运转，每拍检查本次执行新增的错误并上报。
                extractConsoleError(shared ?: preparedConsole, LOG)
                    ?.takeIf { it != lastReportedError }?.let { err ->
                        timer.stop()
                        lastReportedError = err
                        LOG.info("[M36] refresh console error detected (tick=$ticks): ${err.take(120)}")
                        // M36.5.70：把错误写入 Output 控制台可视区（DBN 报错时 Output 内容视图 0x0 不显示）
                        // T-WSC-02：writeConsoleOutput 内部是文档写（runWriteCommandAction + insertString），
                        // 且上游是 Swing Timer 拍（Timer 活动同样非用户活动）——必须 write-safe 模态投递，
                        // any() 恒 write-unsafe（见 DocWriteContext KDoc）。
                        ApplicationManager.getApplication().invokeLater({
                            writeConsoleOutput(
                                shared ?: preparedConsole,
                                "$EXEC_FAILURE_ECHO_PREFIX $err\n",
                                isError = true,
                            )
                            // T-ELU-01/03：语句级错误同 [startResultTabRetry] 的早检路径——走富通道收口
                            deliverError(onErrorRich, onError, null, err, sqlText?.length)
                        }, com.intellij.openapi.application.ModalityState.nonModal())
                        return
                    }
                // M36.5.13：每拍清提示符（压制执行后重设）
                if (shared != null) clearConsolePrompt(shared, LOG)
                val names = resultContents(ui).map { it.displayName }.toSet()
                val namesChanged = lastResultNames != null && names != lastResultNames
                lastResultNames = names
                val live = runCatching {
                    ui.javaClass.getMethod("getComponent").invoke(ui) as? javax.swing.JComponent
                }.getOrNull()
                val shouldSwap = live != null && (forceSwapIn && !swapped || live !== lastLiveResultSwapped || namesChanged)
                if (shouldSwap && live != null) {
                    // M36.5.41-A4：持久整控台原位换底（不重建 SplitPane，身份稳定）；
                    // M36.5.43：swap 助手不再返回 null（强制换入），持久容器缺失时回退
                    // buildConsoleSplitPane（feature flag）。
                    val full = swapResultIntoPersistentConsole(sharedFullComponent, live, top, LOG)
                    val changed = live !== lastLiveResultSwapped
                    swapped = true
                    lastLiveResultSwapped = live
                    sharedFullComponent = full
                    preparedFullComponent = full
                    val cb = pendingOnConsole
                    if (cb != null) {
                        // Timer 回调已在 EDT，直接换入（模态 pump 中 Timer 照常 fire）
                        cb.invoke(full)
                    }
                    LOG.info("[M36] results refreshed (tick=$ticks, forced=$forceSwapIn, instanceChanged=$changed, namesChanged=$namesChanged, gen=$generation)")
                }
                if (ticks >= 8) {
                    timer.stop()
                    LOG.info("[M36] results refresh watch finished (ticks=$ticks)")
                }
            }
        })
        timer.start()
    }

    /**
     * M36.5.6：结果区换入监听——RunnerLayoutUi 的 ContentManager 上任意结果内容新增
     * （contentAdded，排除 Output 自身）即把宿主面板从「编辑器块」换入「完整 SplitPane」。
     * 覆盖原生 ▶ / 控制台 Ctrl+Enter 等插件外执行路径（此前仅插件 executePrepared 有
     * onResultsReady 换入）。topBlock/resultComponent 闭包自捕获（复用路径会清 prepared*
     * 字段，监听器不可依赖它们）；换入组件懒构建并缓存（顶部块已被内嵌"偷走"，预建实例缺
     * 子组件——M36.5.2 教训）。同实例重复换入由面板 embedConsole 防抖兜底。
     * 待机回调 [pendingOnConsole] 由创建面板设置、复用面板接管（面板可能已关闭重建）。
     */
    private fun attachResultsHook(
        ui: Any?,
        topBlock: javax.swing.JComponent,
        resultComponent: javax.swing.JComponent?,
        console: Any,
        sql: String,
        LOG: com.intellij.openapi.diagnostic.Logger,
    ) {
        if (ui == null || resultComponent == null) return
        runCatching {
            val cm = ui.javaClass.getMethod("getContentManager").invoke(ui)
                as com.intellij.ui.content.ContentManager
            cm.addContentManagerListener(object : com.intellij.ui.content.ContentManagerListener {
                override fun contentAdded(event: com.intellij.ui.content.ContentManagerEvent) {
                    val name = event.content?.displayName ?: return
                    LOG.info("[M36] contentAdded: $name")
                    if (name.equals("Output", ignoreCase = true) || name.equals("输出", ignoreCase = true)) return
                    // T-ExplainFirstNative：空名占位（DBN 先 add 后改名）换入必然过早——网格未构建，
                    // 交由 startResultTabRetry 在改名后 select+换入
                    if (name.isBlank()) return
                    val callback = pendingOnConsole ?: run {
                        LOG.info("[M36] contentAdded swap-in SKIPPED (pendingOnConsole=null)")
                        return
                    }
                    // 换入时重新取结果区组件——RunnerLayoutUi 可能在面板分离/重挂后重建组件，
                    // prepare 时捕获的引用可能已非当前展示实例（M36.5.5 复用面板空显示的疑因）。
                    val liveResult = runCatching {
                        ui.javaClass.getMethod("getComponent").invoke(ui) as? javax.swing.JComponent
                    }.getOrNull() ?: resultComponent
                    lastLiveResultSwapped = liveResult
                    // M36.5.41-A4：持久整控台原位换底（不重建 SplitPane，身份稳定）；持久容器
                    // 缺失时 swap 助手回退 buildConsoleSplitPane（feature flag，可回退旧路径）。
                    val full = swapResultIntoPersistentConsole(
                        sharedFullComponent ?: preparedFullComponent,
                        liveResult, topBlock, LOG,
                    )
                    preparedFullComponent = full
                    sharedFullComponent = full
                    sharedTopBlock = topBlock
                    LOG.info("[M36] contentAdded swap-in dispatched (name=$name, rebuilt=true)")
                    ApplicationManager.getApplication().invokeLater({
                        runCatching { callback.invoke(full) }.onFailure { ex ->
                            LOG.warn("[M36] contentAdded swap-in FAILED: ${ex.javaClass.simpleName}: ${ex.message}", ex)
                        }
                        // M36.5.25c：换入后重写 SQL 到 console document——topBlock reparent 到新
                        // SplitPane 后 editor 内容可能被清空（图1 SQL 区域空白根因），用**当前** SQL
                        // 文本写回 console document 保证编辑器显示完整 SQL。
                        // M36.5.56 修复：原实现用 attachResultsHook 捕获的**准备时 sql 快照**
                        // 重写——用户执行新 SQL 后 contentAdded 触发时用它覆盖，导致 SQL 回退
                        // 成上一个（日志实证：执行 417 后 `console doc rewritten (len=183)`，SQL
                        // 变回 183）。改用**当前编辑器文档文本**（用户当前 SQL），不再用过期快照。
                        runCatching {
                            val currentSql = (sharedEditor ?: preparedEditor)
                                ?.document?.text?.takeIf { it.isNotBlank() } ?: sql
                            val consoleDoc = console.javaClass.getMethod("getDocument")
                                .invoke(console) as? com.intellij.openapi.editor.Document
                            if (consoleDoc != null && consoleDoc.text != currentSql) {
                                @Suppress("DEPRECATION")
                                com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(
                                    console.javaClass.getMethod("getProject").invoke(console) as? com.intellij.openapi.project.Project
                                        ?: return@runCatching
                                ) {
                                    consoleDoc.setText(currentSql)
                                    // T-ExecDocSync2 F3：同临界区落盘——console document 即 exec 文件
                                    // document（attachConsole 挂载点），只 setText 不落盘滞留
                                    // 「已修改未保存」窗口（第四轮根因 G4，开发计划 §1.2）。
                                    // 落盘失败不回滚改写本身（显示优先），仅记录
                                    runCatching {
                                        val p = console.javaClass.getMethod("getProject").invoke(console)
                                            as? com.intellij.openapi.project.Project
                                        if (p != null) {
                                            com.intellij.psi.PsiDocumentManager.getInstance(p)
                                                .commitDocument(consoleDoc)
                                            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                                                .saveDocument(consoleDoc)
                                        }
                                    }.onFailure { e ->
                                        LOG.warn("[M36] contentAdded post-rewrite save FAILED: ${e.message}")
                                    }
                                }
                                // 光标移到文档开头（用户体验：能立即看到第一条 SQL）
                                runCatching {
                                    val ed = com.intellij.openapi.editor.EditorFactory.getInstance()
                                        .getEditors(consoleDoc).firstOrNull()
                                    ed?.let { ed.selectionModel.removeSelection(); ed.caretModel.moveToOffset(0) }
                                }
                                LOG.info("[M36] contentAdded: console doc rewritten (len=${consoleDoc.textLength})")
                            }
                        }.onFailure { e ->
                            LOG.warn("[M36] contentAdded rewrite FAILED: ${e.message}")
                        }
                        // T-WSC-02：本回调写 console 文档（setText + commitDocument + saveDocument）且用的是
                        // 快照文本、不幂等——必须「投递时即 write-safe」，不能用重投递自愈，故 any() 改
                        // nonModal（any() 恒 write-unsafe，见 DocWriteContext KDoc）。
                    }, com.intellij.openapi.application.ModalityState.nonModal())
                }
            })
            LOG.info("[M36] results hook attached (contentAdded -> embed results)")
        }.onFailure { e ->
            LOG.warn("[M36] results hook attach FAILED: ${e.message}")
        }
    }

    /**
     * M36.5.6：嵌入后自检数据上下文——原生 ▶ 执行按钮启用链（QueryActionBase：EDITOR +
     * JdbcConsole.findConsole 的 CLIENT_KEY 分支）经 DataManager 在嵌入块上解析验证。
     * M36.5.8：补齐 ▶ 启用链的剩余环节探测——isSqlAtPlace(psiFile, editor)（isApplicable
     * 依赖）与 getSelectedStatementsRange(client, editor, execOption)（最终 setEnabled 依据，
     * = scriptModel.getTextRange().length > 0）——每步结果落日志，▶ 禁用时一眼定位断点。
     */
    private fun logDataContextCheck(
        editorBlock: javax.swing.JComponent,
        console: Any,
        editor: com.intellij.openapi.editor.Editor,
        execOption: Any,
        LOG: com.intellij.openapi.diagnostic.Logger,
    ) {
        // T-Perf-13：诊断探针降 debug——非 debug 模式零开销（此前每次执行一次 DataManager 解析
        // + 数十条反射 + info 刷屏；需要诊断时开 debug 恢复全部输出）
        if (!LOG.isDebugEnabled) return
        // M36.5.13：ModalityState.any()——模态弹窗期间探针照常执行（默认 modality 下 invokeLater
        // 被冻结：沙盒 12:09:55→12:10:50 延迟 55s 实证，探针日志失去诊断价值）
        // write-safe: UI-only（纯 Swing/只读，无文档写）
        ApplicationManager.getApplication().invokeLater({
            runCatching {
                // M36.5.10：整个探针体包 ReadAction——lazy PSI 经 DataManager.getDataContext
                // 在 EDT 无 read action 直查会被 DataValidators 判无效返回 null（探针假阴性，
                // M36.5.9 日志 `PSI_FILE=false` 实为探针自身问题）；动作线程（ActionUpdater BGT
                // 含 read action）不受影响，仅探针需要显式包。
                @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
                ReadAction.compute<Unit, Throwable> {
                    val ctx = com.intellij.ide.DataManager.getInstance()
                        .getDataContext(editorBlock)
                    // T-M52 修复（verifier 2026-08-29）：DataContext.getData(String) 在 262 既标
                    // deprecated-for-removal 又标 Internal，改用 DataKey.getData(ctx) 类型化取值
                    val projectOk = com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.getData(ctx) != null
                    val editorOk = com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR.getData(ctx) != null
                    val clientKey = runCatching {
                        Class.forName("com.intellij.database.console.client.SessionClientHolder")
                            .getField("CLIENT_KEY").get(null)
                                as com.intellij.openapi.actionSystem.DataKey<*>
                    }.getOrNull()
                    val consoleOk = clientKey != null && clientKey.getData(ctx) != null
                    val psiFile = com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE.getData(ctx)
                    val psiOk = psiFile != null
                    val sqlAtPlace = runCatching {
                        Class.forName("com.intellij.database.console.JdbcConsoleProvider")
                            .getMethod("isSqlAtPlace", PsiFile::class.java, com.intellij.openapi.editor.Editor::class.java)
                            .invoke(null, psiFile, editor)
                    }.getOrNull()
                    val statementsRange = runCatching {
                        val clientClass = Class.forName("com.intellij.database.console.client.DatabaseScriptingSessionClient")
                        Class.forName("com.intellij.database.script.ScriptModelUtil")
                            .getMethod(
                                "getSelectedStatementsRange",
                                clientClass,
                                com.intellij.openapi.editor.Editor::class.java,
                                execOption.javaClass,
                            )
                            .invoke(null, console, editor, execOption)
                    }.getOrNull()
                    // M36.5.23g：statementsRange 仍 (0,0) 时的诊断——打印 model 详情定位 stale/方言/光标
                    val modelDiag = runCatching {
                        val model = Class.forName(CONSOLE_CLASS).getMethod("getScriptModel").invoke(console)
                        val isActual = model?.javaClass?.getMethod("isActual")?.invoke(model)
                        val stmts = model?.javaClass?.getMethod("statements")?.invoke(model) as? Iterable<*>
                        val tr = model?.javaClass?.getMethod("getTextRange")?.invoke(model)
                        // M36.5.24e：language + 语句 range 诊断——subModel(光标行).statements()
                        // .filter(isStatement) 失败的判定依据
                        val lang = runCatching {
                            model?.javaClass?.getMethod("getLanguage")?.invoke(model) as? com.intellij.lang.Language
                        }.getOrNull()
                        // M36.5.24m：先 toList 再取 detail——statements() 是单次 JBIterable，
                        // 先 take 会消耗迭代器导致 toList 返回 0（探针顺序 bug，23:10 实证）
                        val stmtsList = stmts?.toList() ?: emptyList<Any>()
                        val stmtDetail = stmtsList.take(3).map {
                            val cls = it?.javaClass?.simpleName
                            // SIt 无 getTextRange()，有 range()（ModelItBase，javap 实证）
                            val r = runCatching { it?.javaClass?.getMethod("getTextRange")?.invoke(it) }.getOrNull()
                                ?: runCatching { it?.javaClass?.getMethod("range")?.invoke(it) }.getOrNull()
                            "$cls($r)"
                        }.joinToString(",")
                        val modelIdentity = System.identityHashCode(model)
                        // M36.5.24k/m：subModel/filter 诊断——statementsRange 零长度的直接环节。
                        // getSelectedStatementsRange 的 adjustSelectionRange（isEmpty 分支）：
                        //   subModel(PositionRange(光标行)).statements().filter(isStatement).last()
                        // 返回 null → 回退 selection。PositionRange extends TextRange（javap 实证），
                        // subModel 反射参数须用 TextRange.class（PositionRange.class 精确匹配失败）。
                        val subDiag = runCatching {
                            val posRangeClass = Class.forName("com.intellij.database.script.ScriptModel\$PositionRange")
                            val posRange = posRangeClass.getConstructor(com.intellij.openapi.util.TextRange::class.java)
                                .newInstance(com.intellij.openapi.util.TextRange.create(0, editor.document.textLength))
                            val sub = model?.javaClass
                                ?.getMethod("subModel", com.intellij.openapi.util.TextRange::class.java)
                                ?.invoke(model, posRange)
                            val subStmts = runCatching {
                                (sub?.javaClass?.getMethod("statements")?.invoke(sub) as? Iterable<*>)
                            }.getOrNull()?.toList()
                            val filterCond = runCatching {
                                // 取 sub 的 statements 第一条做 isStatement 判定（经 EvaluationHelper）
                                val first = subStmts?.firstOrNull()
                                val api = first?.javaClass?.getMethod("api")?.invoke(first)
                                // M36.6.2：探针假阴性修正——EvaluationHelper.isStatement 是**实例方法**
                                //（javap：interface EvaluationHelper，EP: LanguageExtension 分发实现），
                                // 旧探针 getMethod("isStatement", apiClass).invoke(null, api) 以 null
                                // 接收者调实例方法必抛 NPE 被 runCatching 吞掉 → "isStatement=null" 自
                                // M36.5.24 起一直是探针自身的假阴性（误导 24d/26 两轮方言修复方向）。
                                // 正确链路：EP.forLanguage(model.language) 取实现 → 实例调用 isStatement。
                                val ep = Class.forName("com.intellij.sql.dialects.EvaluationHelper")
                                    .getField("EP").get(null) as com.intellij.lang.LanguageExtension<*>
                                val helperInstance = runCatching {
                                    lang?.let { ep.forLanguage(it) }
                                }.getOrNull()
                                val isStmt = runCatching {
                                    helperInstance?.javaClass
                                        ?.getMethod("isStatement", api?.javaClass)
                                        ?.invoke(helperInstance, api) as? com.intellij.openapi.util.Condition<*>
                                }.getOrNull()
                                val v = runCatching {
                                    @Suppress("UNCHECKED_CAST")
                                    (isStmt as? com.intellij.openapi.util.Condition<Any>)?.value(first)
                                }.getOrNull()
                                // M36.6.2：adjustSelectionRange 空 selection 分支复刻（javap 字节码）——
                                // caret=0 时探测范围是 TextRange(0,1)（前两个字符），subScript 对 2 字符
                                // 范围的语句语义（相交/包含）直接决定 statementsRange 是否非零
                                val probe01 = runCatching {
                                    val posRangeClass = Class.forName("com.intellij.database.script.ScriptModel\$PositionRange")
                                    val pr = posRangeClass.getConstructor(com.intellij.openapi.util.TextRange::class.java)
                                        .newInstance(com.intellij.openapi.util.TextRange.create(0, 1))
                                    val sub = model?.javaClass
                                        ?.getMethod("subModel", com.intellij.openapi.util.TextRange::class.java)
                                        ?.invoke(model, pr)
                                    val stmts = runCatching {
                                        (sub?.javaClass?.getMethod("statements")?.invoke(sub) as? Iterable<*>)
                                    }.getOrNull()?.toList()
                                    val firstRange = stmts?.firstOrNull()?.let {
                                        runCatching { it.javaClass.getMethod("range").invoke(it) }.getOrNull()
                                    }
                                    "${stmts?.size}:${firstRange ?: "none"}"
                                }.getOrElse { "probe01 FAILED:${it.message}" }
                                "firstStmt=${first?.javaClass?.simpleName}(${
                                    runCatching { first?.javaClass?.getMethod("range")?.invoke(first) }.getOrNull()
                                }) api=${api?.javaClass?.simpleName} helper=${helperInstance?.javaClass?.name} isStatement=$v probe01=$probe01"
                            }.getOrElse { "filter FAILED:${it.message}" }
                            "stmts=$stmtsList.size subModelStmts=${subStmts?.size} $filterCond"
                        }.getOrElse { "subDiag FAILED:${it.message}" }
                        // M36.5.24c：document 关系诊断——console.getDocument() 与 editor.document 是否
                        // 同一对象；console.getFile() 的 PSI 状态；consoleVf document 内容。
                        val docRel = runCatching {
                            val consoleDoc = Class.forName(CONSOLE_CLASS).getMethod("getDocument").invoke(console)
                            val consoleVf = Class.forName(CONSOLE_CLASS).getMethod("getVirtualFile").invoke(console)
                            val fileDoc = if (consoleVf != null) {
                                com.intellij.openapi.fileEditor.FileDocumentManager
                                    .getInstance().getDocument(consoleVf as com.intellij.openapi.vfs.VirtualFile)
                            } else null
                            val file = runCatching {
                                Class.forName(CONSOLE_CLASS).getMethod("getFile").invoke(console)
                                    as? com.intellij.psi.PsiFile
                            }.getOrNull()
                            "consoleDoc(${System.identityHashCode(consoleDoc)}:${
                                (consoleDoc as? com.intellij.openapi.editor.Document)?.textLength
                            }) editorDoc(${System.identityHashCode(editor.document)}:${editor.document.textLength}) " +
                                "same=${consoleDoc === editor.document} " +
                                "vfDoc(${System.identityHashCode(fileDoc)}:${
                                    (fileDoc as? com.intellij.openapi.editor.Document)?.textLength
                                }) vfDocSameEditorDoc=${fileDoc === editor.document} " +
                                "consoleFile=${file?.javaClass?.simpleName}:${file?.textLength}:" +
                                "${file?.virtualFile?.name}"
                        }.getOrElse { "docRel FAILED:${it.message}" }
                        // M36.6.2：启用链模型身份判定——ScriptModelUtil.getSelectedStatementsRange 的
                        // 模型来自 DatabaseScriptingSessionClient.getScriptModel()（javap offset 30
                        // invokeinterface 实证）；SessionClientHolder<S>=<JdbcConsole,...> 说明 "client"
                        // 即 JdbcConsole 本体（则读到的就是我们替换的 myScriptModel）。isInstance 一次
                        // 判死，排除"替换未到达启用链"的可能
                        val clientCheck = runCatching {
                            val clientClass = Class.forName("com.intellij.database.console.client.DatabaseScriptingSessionClient")
                            val isClient = clientClass.isInstance(console)
                            val clientModel = if (isClient) {
                                clientClass.getMethod("getScriptModel").invoke(console)
                            } else null
                            val consoleModel = Class.forName(CONSOLE_CLASS).getMethod("getScriptModel").invoke(console)
                            "consoleIsClient=$isClient " +
                                "clientModelId=${clientModel?.let { System.identityHashCode(it) }} " +
                                "consoleModelId=${System.identityHashCode(consoleModel)} " +
                                "sameModel=${clientModel === consoleModel}"
                        }.getOrElse { "clientCheck FAILED:${it.message}" }
                        "modelIsActual=$isActual stmts=$stmtsList.size modelRange=$tr lang=${lang?.id} stmtsDetail=[$stmtDetail] modelId=$modelIdentity docLen=${editor.document.textLength} caret=${editor.caretModel.offset} execSelection=${runCatching { execOption.javaClass.getField("execSelection").getInt(execOption) }.getOrNull()} $docRel $clientCheck | $subDiag"
                    }.getOrNull()
                    LOG.debug(
                        "[M36] dataContext check: PROJECT=$projectOk, EDITOR=$editorOk, CLIENT_KEY=$consoleOk, PSI_FILE=$psiOk, " +
                            "isSqlAtPlace=$sqlAtPlace, statementsRange=$statementsRange, $modelDiag",
                    )
                }
            }.onFailure { e ->
                LOG.warn("[M36] dataContext check FAILED: ${e.message}")
            }
        }, com.intellij.openapi.application.ModalityState.any())
    }

    /**
     * T-TS-09：控制台编辑器不可得时的用户可读提示（[prepareInConsole] 两条静默分支共用）。
     *
     * 复用既有键 `execute.failed`（"执行 SQL 失败：{0}"）＋技术性明细参数：明细是诊断串而非文案，
     * 与 `execute.tempFile.failed` 传 [lastTempFileFailure] 同款（新增专用键要动双语 bundle，
     * 不在本任务写集内）。
     *
     * @param path 触发的分支（sync = 非 EDT 同步轮询 / async = EDT 异步等待）——排障时据此定位，
     *             两条分支的成因不同（同步：调用方在非 EDT；异步：EDT 首查未命中后后台轮询）
     */
    private fun consoleEditorUnavailableMessage(path: String): String =
        MpSqlVisualizerBundle.message(
            "execute.failed",
            "控制台编辑器不可得（$path：400ms 内 getEditors(consoleDocument) 恒空，无一方为该文档建过编辑器）",
        )

    /**
     * 准备链续接（consumer 回调内调用）：session → attachConsole → 编辑器/scriptModel →
     * 缓存已备控制台 → 拼合组件回调 →（可选）立即执行。
     *
     * 反编译实锤：logView.getContent().getComponent() = RunnerLayoutUi（仅结果 tabs），不含编辑器。
     * 编辑器组件 = editor.component（EditorImpl）。
     * M36.5.2 拼合：顶部块（原生工具栏+编辑器）；结果区执行后才随完整 SplitPane 展示。
     *
     * @param dataSourceName 所选数据源名（M36.5.5 共享缓存记忆与复用判定用；可 null）
     */
    private fun prepareInConsole(
        project: Project,
        session: Any,
        vf: com.intellij.openapi.vfs.VirtualFile,
        executeAfterPrepare: Boolean,
        dataSourceName: String?,
        onConsole: ((javax.swing.JComponent) -> Unit)?,
        onEditor: ((com.intellij.openapi.editor.Editor) -> Unit)?,
        onError: ((String) -> Unit)?,
        onErrorRich: ((ExecutionErrorInfo) -> Unit)? = null,
        wholeScriptExec: Boolean = false,
    ) {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        runCatching {
            val providerClass = Class.forName(CONSOLE_PROVIDER_CLASS)
            val sessionClass = Class.forName(SESSION_CLASS)

            // 1. attachConsole：将 JdbcConsole 挂到临时文件（同一 session）
            val attach = providerClass.getMethod(
                "attachConsole",
                Project::class.java,
                sessionClass,
                com.intellij.openapi.vfs.VirtualFile::class.java,
            )
            attach.isAccessible = true
            // T-ReverseParenWira：attachConsole 包 WriteIntentReadAction（既非 plain ReadAction，
            // 亦非裸 EDT）——三轮踩坑收口：
            //   · T-PreviewDocLock2 F1 包 plain ReadAction 压读权限断言 → DBN attachConsole 底层
            //     new EditorImpl 的 setHighlighter 走 WriteIntentReadAction，平台锁层明文禁
            //     「plain ReadAction 内再开 WIRA」硬抛（IllegalStateException ← setHighlighter:1832
            //     ← ConsoleExecutionEditor.<init>），被 runCatching 吞成 InvocationTargetException:null
            //     → 控制台从未挂载。
            //   · T-PreviewSlow F2 整段拆包还原裸 EDT → 修了 setHighlighter 嵌套，但 262 同一条构造链
            //     上 ConsoleExecutionEditor.<init>:54 → FileDocumentManager.getDocument 要求调用方已
            //     持读权限，裸 EDT 直调硬抛 RuntimeExceptionWithAttachments「Read access is allowed
            //     from inside read-action only」（真机堆栈实证），又被外层 runCatching 吞掉静默降级。
            //   · 本轮包 WIRA：隐含读权限满足 getDocument 断言；内部 setHighlighter 的 WIRA 属
            //     WIRA-in-WIRA 重入——平台只禁 plain ReadAction→WIRA，重入合法。同文件
            //     T-ExecCopyFix F2b createTempPsiFile 冷路径同款先例（Computable 单类型实参，
            //     243~262 交集——T-Wia262）。V-2 留 262 真机 idea.log 验证无新嵌套硬抛。
            val console = com.intellij.openapi.application.WriteIntentReadAction
                .compute<Any?> { attach.invoke(null, project, session, vf) }
            LOG.info("[M36] attachConsole -> $console")
            if (console == null) return

            val consoleClass = Class.forName(CONSOLE_CLASS)

            // 2. 执行用编辑器：按 console 文档反查（先于内嵌——编辑器随 console 创建即初始化）。
            // T-ReverseParenWira：单独为 getDocument 包 plain ReadAction（T-PreviewSlow F2 注释
            // 预先授权方案）——此句只取 attachConsole 已构建好的 console 文档，不 new EditorImpl、
            // 不触发 WIRA，plain ReadAction 即满足 262 读权限断言，无嵌套风险。
            @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
            val document = ReadAction.compute<com.intellij.openapi.editor.Document?, Throwable> {
                consoleClass.getMethod("getDocument").invoke(console)
                    as? com.intellij.openapi.editor.Document
            }
            // T-Perf-13：EDT 上不再 repeat(20){Thread.sleep(20)} 忙等编辑器实例（最多 400ms 冻结）——
            // 首查即中（绝大多数：编辑器随 attachConsole 同步构造）或后台线程调用：同步短轮询（原语义）；
            // EDT 首查未命中：后台轮询线程 20ms 一查，命中后回 EDT 继续余下准备链（prepareAfterEditorResolved）。
            val editorFactory = com.intellij.openapi.editor.EditorFactory.getInstance()
            fun findEditor(): com.intellij.openapi.editor.Editor? =
                document?.let { editorFactory.getEditors(it).firstOrNull() }
            val firstEditor = findEditor()
            if (firstEditor != null || !ApplicationManager.getApplication().isDispatchThread) {
                var editor = firstEditor
                if (editor == null) {
                    repeat(20) {
                        if (editor != null) return@repeat
                        editor = findEditor()
                        if (editor == null) Thread.sleep(20)
                    }
                }
                LOG.info("[M36] editor=$editor")
                if (editor == null) {
                    // T-TS-09：原为静默 `?: return`——非 EDT 调用方（本分支只在非 EDT 到达）在控制台
                    // 文档无人建编辑器时永久拿不到任何回调，UI 停在「执行中」且 errors=[]（驱动管线打通
                    // 前观测到的「120s 不就绪 + 错误为空」正是此处与下方异步分支两处静默共同造成）。
                    // 只补通知，不改准备链语义（onEditor/onConsole 仍只在成功路径触发）。
                    LOG.warn("[M36] console editor unavailable after 400ms wait (sync path), abort prepare")
                    // write-safe: UI-only（纯 Swing/只读，无文档写）——onError 由面板直接刷状态栏，
                    // 本分支在非 EDT 线程上，须归位 EDT（同下方异步分支语义）
                    ApplicationManager.getApplication().invokeLater({
                        onError?.invoke(consoleEditorUnavailableMessage("sync"))
                    }, com.intellij.openapi.application.ModalityState.any())
                    return
                }
                val ed = editor
                // M36.2：编辑器就绪回调（调用方注入插件补全上下文，与原生补全叠加）
                onEditor?.invoke(ed)
                prepareAfterEditorResolved(project, vf, executeAfterPrepare, dataSourceName, onConsole, onError, console, document, ed, onErrorRich, wholeScriptExec)
            } else {
                ApplicationManager.getApplication().executeOnPooledThread {
                    var editor: com.intellij.openapi.editor.Editor? = null
                    repeat(20) {
                        if (editor != null) return@repeat
                        editor = findEditor()
                        if (editor == null) Thread.sleep(20)
                    }
                    val resolved = editor
                    // T-WSC-02：本块经 prepareAfterEditorResolved 写 console 文档（`:1354`
                    // WriteAction.compute { writeVfTextViaDocument }）——文档写回调一律 write-safe
                    // 模态，any() 恒 write-unsafe（见 DocWriteContext KDoc）。代价：模态弹窗存续期
                    // 准备链延后到弹窗关闭，正是平台对「改模型」要求的语义。
                    ApplicationManager.getApplication().invokeLater({
                        LOG.info("[M36] editor=$resolved (async wait)")
                        if (resolved != null) {
                            onEditor?.invoke(resolved)
                            runCatching {
                                prepareAfterEditorResolved(project, vf, executeAfterPrepare, dataSourceName, onConsole, onError, console, document, resolved, onErrorRich, wholeScriptExec)
                            }.onFailure { e ->
                                LOG.warn("[M36] prepareInConsole(editor async wait) FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
                                val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                                onError?.invoke(cause.message ?: cause.toString())
                            }
                        } else {
                            // T-TS-09：原为「无 else」——EDT 调用方（测试/面板准备链的常态）轮询 400ms
                            // 仍取不到编辑器时，本函数就此静默结束：调用方既无 onError 也无 callback，
                            // 面板停在「执行中」且 errors=[]，与上面的同步分支同款不可诊断（20260918
                            // 基线 idea.log `[M36] editor=null (async wait)` 实证）。只补通知，不改语义。
                            LOG.warn("[M36] console editor unavailable after 400ms wait (async path), abort prepare")
                            onError?.invoke(consoleEditorUnavailableMessage("async"))
                        }
                    }, com.intellij.openapi.application.ModalityState.nonModal())
                }
            }
        }.onFailure { e ->
            LOG.warn("[M36] prepareInConsole FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            // M36.3：通知调用方错误，确保 UI 从「执行中」状态恢复
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            // T-PreviewExecDs-03 F3：console 被清扫 dispose 的处置（prepare 链后段撞上时同款
            // 特判，见 prepareAfterEditorResolved onFailure）
            if (cause is com.intellij.util.IncorrectOperationException &&
                cause.message?.contains("already been disposed") == true
            ) {
                LOG.warn("[M36] console disposed during prepareInConsole (swept), clear caches for retry: ${cause.message?.take(120)}")
                clearPrepared()
                dropSharedConsole()
                onError?.invoke(MpSqlVisualizerBundle.message("execute.console.disposed"))
            } else {
                onError?.invoke(cause.message ?: cause.toString())
            }
        }
    }

    /**
     * M36.5：控制台准备链后段（T-Perf-13 从 [prepareInConsole] 抽出——编辑器实例就绪后执行：
     * scriptModel 装配 / 执行上下文缓存 / 原生控制台 UI 拼合内嵌 / 可选立即执行）。
     * EDT 编辑器异步等待路径会经此继续（异常处理与同步路径一致：warn + onError 面板内显示）。
     */
    private fun prepareAfterEditorResolved(
        project: Project,
        vf: com.intellij.openapi.vfs.VirtualFile,
        executeAfterPrepare: Boolean,
        dataSourceName: String?,
        onConsole: ((javax.swing.JComponent) -> Unit)?,
        onError: ((String) -> Unit)?,
        console: Any,
        document: com.intellij.openapi.editor.Document?,
        editor: com.intellij.openapi.editor.Editor,
        onErrorRich: ((ExecutionErrorInfo) -> Unit)? = null,
        wholeScriptExec: Boolean = false,
    ) {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        runCatching {
            val consoleClass = Class.forName(CONSOLE_CLASS)

            // T-PreviewExecDs-03 F2：getScriptModel 前 isValid 预检 + 三分量诊断——attach 后
            // console 可能已被 getActiveConsoles 清扫（isValid=false，依赖 facade 数据源加载状态，
            // 见开发计划 §2.2 反编译实锤）。disposed 直接快速失败（等待无意义，F3 自愈清缓存）；
            // 未 disposed 但分量失效时，非 EDT 上有界重查等 facade 异步窗口，EDT 不阻塞直走
            // （getScriptModel 若撞上清扫由 F3 特判接管）。
            val initialValidity = probeConsoleValidity(console, project, LOG)
            if (initialValidity != null && initialValidity.disposed) {
                LOG.warn("[M36] console DISPOSED right after attach: $initialValidity")
                throw IllegalStateException(MpSqlVisualizerBundle.message("execute.console.disposed"))
            }
            if (initialValidity != null && !initialValidity.valid &&
                !ApplicationManager.getApplication().isDispatchThread
            ) {
                runCatching {
                    repeat(3) { attempt ->
                        Thread.sleep(300)
                        val v = probeConsoleValidity(console, project, LOG)
                        if (v != null && v.valid) {
                            LOG.info("[M36] console valid after retry (attempt=${attempt + 1})")
                            return@repeat
                        }
                    }
                }
                LOG.warn("[M36] console still invalid after bounded retry: ${probeConsoleValidity(console, project, LOG)}")
            }

            // 3. scriptModel + execOption
            val scriptModel = consoleClass.getMethod("getScriptModel").invoke(console)
            val execOptionClass = Class.forName("com.intellij.database.settings.DatabaseSettings\$ExecOption")
            // M36.5.16：execOption 用 **DatabaseSettings.getDefaultExecOption()**（原生默认配置）
            // 而非 new 空实例——空实例的 execSelection=0（非法值），ScriptModelUtil
            // .getSelectedStatementsRange 按"执行选中部分"解析空选择 → model.getTextRange()
            // = (N,N) 零长度 → 原生 ▶ isEnabled=false 恒灰（沙盒探针 statementsRange=(0,0)/
            // (57,57) 实证，其余四键已全绿）。原生同源配置顺带修正 newTab/submitImmediately 执行语义。
            val execOption: Any = runCatching {
                Class.forName("com.intellij.database.settings.DatabaseSettings")
                    .getMethod("getDefaultExecOption").invoke(null)
            }.getOrNull() ?: execOptionClass.getDeclaredConstructor().newInstance()
            if (scriptModel == null) return

            // M36.5.24：▶ 灰/无绿色边框根因修复——statementsRange=(0,0)（探针实证）。
            // 根因链（javap 实证）：DBN 原生 ▶ isEnabled 依赖
            //   ScriptModelUtil.getSelectedStatementsRange = getSelectionForConsole(editor, opt)
            //     → client.getScriptModel()（= console.getScriptModel()）
            //     → adjustModelForSelection → subModel(...).getTextRange()
            //   console.getScriptModel() 缓存 model，isActual() **仅比较 splitScriptMode 配置**
            //   （与文档/PSI 无关）→ attachConsole 早期（vf PSI 未 commit / document 内容未
            //   同步）创建的 model 永不重建 → SqlScriptModel.getTextRange() = myScript.getRange()
            //   = null（Script 基于 ScriptModelUtilCore.getScriptDocument(consoleVf)，空内容）
            //   → statementsRange=(0,0) → ▶ 灰、StatementRangeAnnotator 无语句 → 无绿色边框。
            // 修复两步：① console doc 内容同步进 console vf document 并 commit（PSI 对齐，
            //   与 executeOnConsole 执行前同款同步）；② 反射强制重建 scriptModel
            //   （JdbcConsole.updateScriptModel 内部 createScriptModel(console.getFile())），
            //   isActual() 不再拦截。
            var effectiveScriptModel = scriptModel
            runCatching {
                val consoleVf = consoleClass.getMethod("getVirtualFile").invoke(console)
                    as? com.intellij.openapi.vfs.VirtualFile
                if (consoleVf != null) {
                    val consoleText = (document as com.intellij.openapi.editor.Document).text
                    @Suppress("DEPRECATION") // T-M52：WriteAction.compute(ThrowableComputable) 243 编译目标仅此重载可用
                    com.intellij.openapi.application.WriteAction.compute<Unit, Throwable> {
                        // T-ExecDocSync2 F2：同 F1 收口——commit 后补 saveDocument，console document
                        // 不再滞留「已修改未保存」（第四轮根因 G3，开发计划 §1.2）
                        writeVfTextViaDocument(project, consoleVf, consoleText)
                    }
                    // T-ConsoleDbContext B：控制台就绪即把库上下文对齐到「数据源 URL 里的库」——
                    // DBN 的文件→数据源映射与库记忆会把会话钉在别的库上（真机事故：exec 文件被映射
                    // 到 mpj_demo@localhost，而 SQL 属于 jpa_demo → Unknown column 'u.username'）。
                    // 用户定案：预览默认以数据源 URL 的库为准；用户之后在控制台手调库，记忆照常保留
                    //（对齐只在「与数据源默认库不一致」时切一次，已一致即 no-op，不会覆盖用户后来的选择）。
                    LOG.info("[M36] console db-context: ${ExecConsoleContext.alignToDataSourceDatabase(console)}")
                    // M36.5.24g：fresh 构造 + set 移入 DumbService.runWhenSmart——22:30 实证：
                    // attachConsole 后 SqlDialectPusher 的 dumb task（58.443 运行 → 58.714 exit）
                    // 触发 PSI 重建；在 dumb mode 中构造的 fresh（58.302，stmts=1）被 push 后
                    // PSI 重建击穿 → 语句 range=null（SIt(null)）→ statementsRange 零长度。
                    // runWhenSmart 保证 dialect push + PSI 完整后再构造 → 语句 range 非 null。
                    val consoleForModel = console
                    val vfForModel = consoleVf
                    com.intellij.openapi.project.DumbService.getInstance(project).runWhenSmart {
                        // 延迟到 dialpct push 完全结束后（dumb 退出 + 一次 EDT 泵）再重建
                        // write-safe: UI-only（纯 Swing/只读，无文档写）
                        ApplicationManager.getApplication().invokeLater({
                            runCatching {
                                refreshConsoleModel(project, consoleForModel, vfForModel, LOG)
                            }.onFailure { e ->
                                LOG.warn("[M36] scriptModel refresh (smart) FAILED: ${e.message}")
                            }
                        }, com.intellij.openapi.application.ModalityState.any())
                    }
                }
            }.onFailure { e ->
                LOG.warn("[M36] scriptModel vf-sync FAILED (fallback to attach-time model): ${e.message}")
            }

            // M36.5：缓存已备控制台（executePrepared 消费）
            // M36.5.10：代际递增——新控制台上下文取代旧缓存（旧挂起的 runWhenSmart 提交作废，
            // 防止在新控制台上执行旧预览 SQL）
            executionGeneration++
            preparedConsole = console
            preparedEditor = editor
            preparedScriptModel = effectiveScriptModel
            preparedExecOption = execOption

            // 4. 拼合原生控制台 UI：顶部块（工具栏+编辑器）/ 结果区——默认展示只嵌顶部块（M36.5.2）
            val logView = runCatching {
                consoleClass.getMethod("getView").invoke(console)
            }.getOrNull()

            // 4.1 触发内容构建（独立 try-catch）
            if (logView != null) {
                runCatching {
                    logView.javaClass.getMethod("ensureContentInitialized").invoke(logView)
                }.onFailure { e ->
                    LOG.warn("[M36] ensureContentInitialized FAILED: ${e.message}")
                }
            }

            // 4.2 取 ui 与结果区组件
            val ui: Any? = logView?.let {
                runCatching { it.javaClass.getMethod("getUi").invoke(it) }.getOrNull()
            } ?: runCatching { consoleClass.getMethod("getUi").invoke(console) }.getOrNull()
            preparedUi = ui
            val resultComponent: javax.swing.JComponent? =
                ui?.javaClass?.getMethod("getComponent")?.invoke(ui) as? javax.swing.JComponent

            // 4.3 SQL 编辑器组件（EditorImpl.component）
            // M36.5.2：未执行路径下 console 编辑器从未布局，preferredSize=0 且 hidden →
            // 宿主容器/SplitPane 按首选尺寸分配时编辑器塌缩到 0 高（截图实证 "Console output"
            // 占位满屏）。强制可见 + 首选高度兜底，任何宿主布局下编辑器块均可占位可见。
            val editorComponentRaw: javax.swing.JComponent? = editor?.component?.apply {
                isVisible = true
                    // M36.5.25e：editor 兜底 200——GridLayout(1,1) 已强制 fill，固定高度不再
                    // 需要（700 在窗口小时会溢出）；preferredSize/minimumSize 仅作首次塌缩
                    //（preferred=0）的兜底，正常自适应由布局管理器保证。
                    if (preferredSize == null || preferredSize.height < 50) {
                        preferredSize = java.awt.Dimension(400, 200)
                    }
                    if (minimumSize == null || minimumSize.height < 200) {
                        minimumSize = java.awt.Dimension(400, 200)
                    }
            }
            LOG.info("[M36] resultComponent=$resultComponent, editorComponent=$editorComponentRaw")
            if (editorComponentRaw == null) return

            // 4.3.1 M36.5.4：编辑器块补 DataProvider——原生头部工具栏动作（▶执行等）经
            // JdbcConsole.findConsole(event) 解析控制台，仅两条路：PlatformCoreDataKeys.FILE_EDITOR
            // （嵌入面板无 FileEditor）或 SessionClientHolder.CLIENT_KEY（组件 DataProvider 链）——
            // 嵌入面板两者皆缺 → ▶执行按钮恒禁用（沙盒实测，QueryActionBase.update 需
            // EDITOR + console）。包一层 DataProvider 直供 CLIENT_KEY=console + EDITOR=editor，
            // 工具栏动作数据上下文与原生工具窗口等效。
            val clientKeyName: String? = runCatching {
                (Class.forName("com.intellij.database.console.client.SessionClientHolder")
                    .getField("CLIENT_KEY").get(null)
                        as com.intellij.openapi.actionSystem.DataKey<*>).name
            }.getOrNull()
            val consoleForData = console
            val editorForData = editor
            // M36.5.7：补 PSI_FILE/VIRTUAL_FILE——▶ 启用链 isApplicable →
            // JdbcConsoleProvider.isSqlAtPlace(psiFile, editor) 需要 PSI_FILE
            // （javap 实证）；嵌入面板无 FileEditor/平台 Provider 链，须直供。
            // PSI 预解析包 ReadAction（DataProvider.getData 可能在 BGT 动作更新线程调用）。
            val vfForData = vf
            // M36.5.14：psiForData 不再是固定快照——文件内容每次预览都会更新（setText/
            // setBinaryContent），PSI 随之重建，prepare 时预解析的 PsiFile 会 **isValid=false**，
            // isSqlAtPlace(invalidPsi) 恒 false → 原生 ▶ 恒灰（12:29:49 探针 isSqlAtPlace=false
            // 实证）。改为 lazy 取值时**失效检测 + 实时重解析**（lazy 回调在平台 read action 中）。
            var psiForData: PsiFile? = runCatching {
                @Suppress("DEPRECATION")
                ReadAction.compute<PsiFile, Throwable> {
                    PsiManager.getInstance(project).findFile(vf)
                }
            }.getOrNull()
            // M36.5.9：改实现 **UiDataProvider**（legacy DataProvider 在 2024.3 被 DataValidators
            // 拒绝 PSI——PluginException "PSI element ... provided on EDT ... Use DataSink.lazy"，
            // 用户堆栈实证，validOrNull 返回 null → isApplicable 拿到 null → ▶ 恒灰）。
            // PSI_FILE 经 DataSink.lazy 异步提供（平台在 read action 中取值）；CLIENT_KEY 反射
            // 取 DataKey 后 set（值非 PSI 可同步）。
            val editorComponent: javax.swing.JComponent =
                object : com.intellij.ui.components.JBPanel<com.intellij.ui.components.JBPanel<*>>(
                    java.awt.BorderLayout(),
                ), com.intellij.openapi.actionSystem.UiDataProvider {
                    override fun uiDataSnapshot(sink: com.intellij.openapi.actionSystem.DataSink) {
                        // M36.5.15：补 PROJECT——原生动作（含 ▶ 执行）的 update 普遍依赖
                        // CommonDataKeys.PROJECT，嵌入面板的 DataProvider 链缺失时 AnActionEvent
                        // .project=null → 动作禁用（▶ 灰的最后一环：PSI_FILE/isSqlAtPlace 已
                        // 12:42 版本修复为 true，唯 PROJECT 未直供）
                        sink.set(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                        sink.set(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR, editorForData)
                        sink.set(com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE, vfForData)
                        // M36.5.14：PSI 失效检测 + 实时重解析（快照失效是 ▶ 恒灰根因）
                        sink.lazy(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE) {
                            if (psiForData?.isValid == true) psiForData else runCatching {
                                @Suppress("DEPRECATION")
                                ReadAction.compute<PsiFile, Throwable> {
                                    PsiManager.getInstance(project).findFile(vfForData).also { psiForData = it }
                                }
                            }.getOrNull()
                        }
                        if (clientKeyName != null) {
                            @Suppress("UNCHECKED_CAST")
                            sink.set(
                                Class.forName("com.intellij.database.console.client.SessionClientHolder")
                                    .getField("CLIENT_KEY").get(null)
                                        as com.intellij.openapi.actionSystem.DataKey<Any>,
                                consoleForData,
                            )
                        }
                    }
                }.apply {
                    isOpaque = false
                    // M36.5.59：编辑器拉伸填充修正——BorderLayout.CENTER 尊重子组件 preferred（仅
                    // ~4 行高），DBN 控制台编辑器在长文档/空余区不扩展，topBlock 下方留下大面积
                    // 面板底色"空洞"（用户截图实证：编辑器仅 ~80px，顶部区 ~240px）。改 GridLayout
                    // (1,1)，与 editorHolder（SqlPreviewPanel:171）同机制：强制唯一子组件（原生控制台
                    // 编辑器）fill 满容器，忽略其 preferred/maximum，任何宿主布局下编辑器块均占满
                    // 分配的 topBlock，不留空区。
                    layout = java.awt.GridLayout(1, 1)
                    // 解除编辑器组件可能的 maximumSize 上限，确保 GridLayout fill 不被 preferred 推算压回
                    add(editorComponentRaw)
                    editorComponentRaw.maximumSize = java.awt.Dimension(
                        Int.MAX_VALUE, Int.MAX_VALUE,
                    )
                    // M36.5.65：GridLayout 在某些 DBN 编辑器（自身带 header+editor 内部布局）上不生效
                    // ——沙盒 BOUNDS 实证 `top=...x403` 但 `top.child[0]=...x234`（编辑器组件未填满，
                    // 只到 234px，下方留面板底色空洞）。GridLayout 依赖容器 doLayout，而 DBN 编辑器
                    // 组件内部 preferred=220 且不随父高扩展。兜底：随父 resize 强制把编辑器组件
                    // setSize 到父容器可用高度（header 之外的全部），任何布局管理器下编辑器都填满。
                    addComponentListener(object : java.awt.event.ComponentAdapter() {
                        override fun componentResized(e: java.awt.event.ComponentEvent) {
                            val avail = size
                            if (avail.width > 0 && avail.height > 0) {
                                editorComponentRaw.setSize(
                                    avail.width, avail.height,
                                )
                                editorComponentRaw.revalidate()
                            }
                        }
                    })
                }

            // 4.4 原生控制台头部（M36.5.3）：JdbcConsole.buildHeaderComponent(Editor, JdbcConsole)
            // public static（javap 反编译实锤）——ActionManager 取 "Console.Jdbc.Left"（▶执行/Debug/
            // 历史/参数/设置）与 "Console.Jdbc.Right"（Tx/会话/数据源选择器）动作组 → EditorHeaderComponent
            // （左 CENTER/右 EAST）→ editor.setHeaderComponent + setPermanentHeaderComponent。
            // 与 IDE Database 控制台同一构建路径，嵌入编辑器顶部即刻拥有完整原生工具栏；
            // 失败回退 M36.5.2 的 addLeftToolbarConsoleActions 精简工具栏（仅 console 通用动作）。
            var topBlock: javax.swing.JComponent = editorComponent
            runCatching {
                val buildHeader = consoleClass.getMethod(
                    "buildHeaderComponent",
                    com.intellij.openapi.editor.Editor::class.java,
                    consoleClass,
                )
                buildHeader.invoke(null, editor, console)
                LOG.info("[M36] native console header built (Console.Jdbc.Left/Right toolbars)")
            }.onFailure { headerError ->
                LOG.warn("[M36] buildHeaderComponent FAILED (${headerError.message}), fallback to console-actions toolbar")
                runCatching {
                    val consoleView = consoleClass.getMethod("getConsoleView").invoke(console)
                        ?: return@runCatching
                    val group = com.intellij.openapi.actionSystem.DefaultActionGroup()
                    val util = Class.forName("com.intellij.database.console.JdbcConsoleUtil")
                    val addActions = util.getMethod(
                        "addLeftToolbarConsoleActions",
                        com.intellij.openapi.actionSystem.DefaultActionGroup::class.java,
                        Class.forName("com.intellij.execution.ui.ConsoleView"),
                    )
                    addActions.invoke(null, group, consoleView)
                    if (group.childrenCount > 0) {
                        val toolbar = com.intellij.openapi.actionSystem.ActionManager.getInstance()
                            .createActionToolbar("MpSqlNativeConsole", group, true)
                        toolbar.targetComponent = editorComponent
                        topBlock = javax.swing.JPanel(java.awt.BorderLayout()).apply {
                            isOpaque = false
                            border = com.intellij.util.ui.JBUI.Borders.empty()
                            add(toolbar.component, java.awt.BorderLayout.NORTH)
                            add(editorComponent, java.awt.BorderLayout.CENTER)
                        }
                        LOG.info("[M36] console-actions toolbar injected: ${group.childrenCount} actions")
                    }
                }.onFailure { e ->
                    LOG.warn("[M36] toolbar injection skipped (fallback to bare editor): ${e.message}")
                }
            }

            // 4.5 完整控制台 = 顶部块（上）/ 结果区（下）垂直 SplitPane（M36.4 主题化形态）
            val fullComponent: javax.swing.JComponent? = resultComponent?.let { result ->
                buildConsoleSplitPane(topBlock, result)
            }

            // 4.6 内嵌（M36.5.6：准备阶段**只嵌编辑器块**——"Console output" 空占位不再预嵌；
            // 结果区由 ContentManager 监听换入：任何执行路径（原生 ▶ / 控制台 Ctrl+Enter /
            // 插件按钮）产生结果内容（contentAdded）即换入完整 SplitPane——三路径全覆盖、
            // 以真实结果出现为准。准备+执行全链仍直接嵌完整 SplitPane（执行后立即有结果可看）。
            // 塌缩防护不变：4.3 首选高度兜底 + embedConsole 分隔保险。
            // M36.5.8：pendingOnConsole 覆盖全路径——执行后结果区组件会被 RunnerLayoutUi 重建，
            // 旧引用显示停留在上一轮（用户实测：新 SQL 执行后一直加载中、重开面板才见新结果）；
            // startResultTabRetry 找到结果 Tab 后重取组件换入（refreshResultsComponent）。
            pendingOnConsole = onConsole
            // M36.5.53：预览也要嵌**完整 SplitPane**（而非仅 topBlock）——同一 topBlock 不能同时被
            // editorHolder（预览挂载）和 SplitPane（执行换入）持有，否则执行后 SplitPane 的 top
            // 失效（沙盒实证 topH=0，setDividerLocation 撑不起）。预览时 bottom（结果区占位）通过
            // 清空 + divider 压底折叠，满足"未执行不显示结果面板"。
            val component: javax.swing.JComponent = fullComponent ?: topBlock
            preparedTopBlock = topBlock
            preparedResultComponent = resultComponent
            // M36.5.41-A4：持久整控台**始终**缓存——executePrepared 的 onResultsReady 与
            // 结果刷新都走原位换底，不再按次重建 SplitPane（身份稳定，sameAsPrev=true）。
            preparedFullComponent = fullComponent
            LOG.info("[M36] embed component=$component (executeAfterPrepare=$executeAfterPrepare)")

            if (!executeAfterPrepare) {
                attachResultsHook(ui, topBlock, resultComponent, console, editor.document.text, LOG)
            }
            logDataContextCheck(editorComponent, console, editor, execOption, LOG)
            clearConsolePrompt(console, LOG)

            // M36.5.5：填充共享控制台缓存 + 统一记忆数据源（下次预览 last-used 命中即复用，
            // 不再新建 session/连接——挂死根因见 sharedConsole 字段注释）
            sharedConsole = console
            sharedEditor = editor
            sharedScriptModel = effectiveScriptModel
            sharedExecOption = execOption
            sharedUi = ui
            sharedTopBlock = topBlock
            // M36.5.53：sharedFullComponent 始终缓存**完整 SplitPane**（preparedFullComponent 也是
            // fullComponent），不随预览时内嵌的 component（可能为 topBlock 编辑器）变化——否则
            // 执行时 swapResultIntoPersistentConsole 拿到的 sharedFullComponent 是 topBlock（非
            // SplitPane）→ 回退 buildConsoleSplitPane 重建 → A4 身份漂移。预留给执行复用。
            sharedFullComponent = fullComponent
            sharedDataSourceName = dataSourceName
            // T-SI-02：控制台已建，数据源 UUID 从此可取（LocalDataSource.getUniqueId）——
            // 记忆升级为「UUID + 显示名」双字段，下次预览按身份命中而非猜名字
            if (dataSourceName != null) rememberRunner(project, dataSourceName, consoleDataSourceId(console))

            // 4.7 内嵌（同步，已在 EDT）
            onConsole?.invoke(component)

            // M36.5.55：预览（尚未执行）时把结果区（bottom）折叠——divider 压到接近底部，
            // bottom（DBN Output/Tx 结果区占位）收起不显示，满足"未执行不显示结果面板"。
            // 执行时 realignSplitDivider 展开 60%。用 invokeLater 等组件布局后再设 divider。
            if (!executeAfterPrepare) {
                (component as? javax.swing.JSplitPane)?.let { sp ->
                    // write-safe: UI-only（纯 Swing/只读，无文档写）
                    ApplicationManager.getApplication().invokeLater({
                        sp.setDividerLocation(1.0)
                        sp.revalidate()
                        sp.repaint()
                    }, com.intellij.openapi.application.ModalityState.any())
                }
            }

            // 5. 可选立即执行（selectAndExecute 全链语义；结果区已随完整 UI 内嵌，onResultsReady=null）
            if (executeAfterPrepare) {
                // 2026-09-23 真机走查补：此处原**漏传** onErrorRich（默认 null）⇒ 这条路径上的错误
                // 投递收口只能回退旧通道，定位按钮永不出现。本函数签名里一直有该参数，只是没往下传
                // ——「实现有、接线漏」的又一例（同族见 ErrorPositionWiringGuardTest 的转发断言）。
                executeOnConsole(
                    project, console, editor, effectiveScriptModel, execOption, ui,
                    onResultsReady = null, onError = onError, onErrorRich = onErrorRich,
                    wholeScriptExec = wholeScriptExec,
                )
            }
        }.onFailure { e ->
            LOG.warn("[M36] prepareInConsole FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            // M36.3：通知调用方错误，确保 UI 从「执行中」状态恢复
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            // T-PreviewExecDs-03 F3：console 已被 getActiveConsoles 清扫（Disposer.register 撞
            // 已 dispose 父节点抛 IncorrectOperationException）→ 清单例缓存（否则下轮执行复用
            // dead 缓存继续失败）+ 可读文案，不再透平台异常长串。
            if (cause is com.intellij.util.IncorrectOperationException &&
                cause.message?.contains("already been disposed") == true
            ) {
                LOG.warn("[M36] console disposed during prepare (swept by getActiveConsoles), clear caches for retry: ${cause.message?.take(120)}")
                clearPrepared()
                dropSharedConsole()
                onError?.invoke(MpSqlVisualizerBundle.message("execute.console.disposed"))
            } else {
                onError?.invoke(cause.message ?: cause.toString())
            }
        }
    }

    /**
     * T-PreviewExecDs-03 F2：console 有效性的三分量快照（反编译实锤 `isValid` =
     * `!isDisposed && isViewValid && facade.findDataSource(dataSource.uniqueId) != null`）。
     * 任何一环为 false 即可能被 `getActiveConsoles` 清扫 dispose——日志/自愈判定共用。
     */
    internal data class ConsoleValidity(
        val disposed: Boolean,
        val viewValid: Boolean?,
        val dsInFacade: Boolean?,
    ) {
        val valid: Boolean get() = !disposed && viewValid != false && dsInFacade != false
        override fun toString(): String = "disposed=$disposed, viewValid=$viewValid, dsInFacade=$dsInFacade, valid=$valid"
    }

    /**
     * T-SI-01：共享会话复用身份判定（纯函数，默认 test 通道可覆盖）——在 `console.isValid()`
     * 与编辑器未释放之外，把 [ConsoleValidity] 的 `dsInFacade` 分量作为**独立判据**接进复用
     * 判定（复用 attach 路径既有探测，不重造）。`identity == null` 表示探测不可用（降级），
     * 按现状放行——探测失败不得把正常复用打成重建（宁可用旧会话，也不每次预览重建连接）。
     */
    internal fun reuseIdentityAccepted(identity: ConsoleValidity?): Boolean = identity == null || identity.valid

    /** T-PreviewExecDs-03 F2：反射探测 console 三分量有效性（任一环节失败降级 null，绝不阻断）。 */
    private fun probeConsoleValidity(console: Any, project: Project, LOG: com.intellij.openapi.diagnostic.Logger): ConsoleValidity? =
        runCatching {
            val consoleClass = Class.forName(CONSOLE_CLASS)
            val disposed = runCatching {
                // 243 平台仅 Disposable 版本（标 deprecated），无 Object 重载——cast + suppress
                @Suppress("DEPRECATION")
                (console as? com.intellij.openapi.Disposable)
                    ?.let { com.intellij.openapi.util.Disposer.isDisposed(it) } ?: false
            }.getOrDefault(false)
            val viewValid = runCatching {
                consoleClass.getMethod("isViewValid").invoke(console) as? Boolean
            }.getOrNull()
            val dsInFacade = runCatching {
                val ds = consoleClass.getMethod("getDataSource").invoke(console) ?: return@runCatching null
                val id = ds.javaClass.getMethod("getUniqueId").invoke(ds) as? String ?: return@runCatching null
                val facade = Class.forName("com.intellij.database.psi.DbPsiFacade")
                    .getMethod("getInstance", Project::class.java).invoke(null, project)
                facade.javaClass.getMethod("findDataSource", String::class.java)
                    .invoke(facade, id) != null
            }.getOrNull()
            ConsoleValidity(disposed, viewValid, dsInFacade)
        }.onFailure { e ->
            LOG.warn("[M36] probeConsoleValidity FAILED: ${e.message}")
        }.getOrNull()

    /**
     * M36.5.24g：dumb 退出后重建 console 的 scriptModel——在 **DBN SqlDialectPusher 的 dialect push
     * dumb task 完成之后**（PSI 已按 MySQL 方言完整重建）构造 fresh model，避免 dumb mode 中
     * PSI 解析不完整导致语句 range=null（22:30 实证：attachConsole 后 dialect push 触发 PSI 重建，
     * dumb 中构造的 fresh 语句 range 全失效）。构造 + splitMode 对齐 + set 缓存，并更新共享缓存。
     */
    private fun refreshConsoleModel(
        project: Project,
        console: Any,
        consoleVf: com.intellij.openapi.vfs.VirtualFile,
        LOG: com.intellij.openapi.diagnostic.Logger,
    ) {
        runCatching {
            val consoleClass = Class.forName(CONSOLE_CLASS)
            // M36.5.26：model 的 language 必须用 **SqlDialect（方言 Language 实例）**——
            // `LanguageUtil.getLanguageForPsi` 返回 SQL 插件的 MySQLLanguage（id="MySQL"），
            // 而 DBN 的 `EvaluationHelper.isStatement` 经 `LanguageExtension.forLanguage` 注册在
            // **SqlDialectMappings 的 SqlDialect 实例**上（00:26 探针实证 isStatement=null）——
            // 两者不匹配导致 filter(isStatement) 抛弃全部语句 → statementsRange=(0,0) → ▶ 灰。
            val lang = runCatching {
                Class.forName("com.intellij.sql.dialects.SqlDialectMappings")
                    .getMethod("getMapping", Project::class.java, com.intellij.openapi.vfs.VirtualFile::class.java)
                    .invoke(null, project, consoleVf) as? com.intellij.lang.Language
            }.getOrNull() ?: com.intellij.lang.LanguageUtil.getLanguageForPsi(project, consoleVf)
            val fresh = Class.forName("com.intellij.sql.script.SqlScriptModel")
                .getConstructor(
                    Project::class.java,
                    com.intellij.openapi.vfs.VirtualFile::class.java,
                    com.intellij.lang.Language::class.java,
                )
                .newInstance(project, consoleVf, lang)
            val freshStmts = runCatching {
                (fresh.javaClass.getMethod("statements").invoke(fresh) as? Iterable<*>)
            }.getOrNull()?.toList()
            val freshLang = runCatching {
                fresh.javaClass.getMethod("getLanguage").invoke(fresh)
            }.getOrNull()
            if (freshStmts == null || freshStmts.isEmpty()) {
                LOG.info(
                    "[M36] refreshConsoleModel: model empty (lang=$freshLang, stmts=${freshStmts?.size}), keep console model",
                )
                return
            }
            // splitMode 对齐：isActual() = getSplitScriptMode(myScript) == settings.splitScriptMode；
            // 调 settings 使 fresh.isActual()=true → set 持久化（否则 getScriptModel() 走 updateAndGet
            // 重建为 console.getFile() 的 FileScript → 又失效）。副作用：DBN 全局 settings 的
            // splitScriptMode 改变（target 是基于 Script 的合理值，符合 DBN 派发设计）。
            val myScriptField = fresh.javaClass.getDeclaredField("myScript").apply { isAccessible = true }
            val myScript = myScriptField.get(fresh)
            val targetMode = runCatching {
                Class.forName("com.intellij.database.script.ScriptModelUtilCore")
                    .getMethod("getSplitScriptMode", Class.forName("com.intellij.sql.script.Script"))
                    .invoke(null, myScript)
            }.getOrNull()
            val settings = Class.forName("com.intellij.database.settings.DatabaseSettings")
                .getMethod("getSettings").invoke(null)
            val splitField = settings.javaClass.getField("splitScriptMode")
            val oldSplit = splitField.get(settings)
            if (targetMode != null && targetMode != oldSplit) {
                splitField.set(settings, targetMode)
            }
            val freshIsActual = runCatching {
                fresh.javaClass.getMethod("isActual").invoke(fresh)
            }.getOrNull()
            // 反射替换 myScriptModel 缓存（getScriptModel 首查缓存）
            val field = consoleClass.getDeclaredField("myScriptModel")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(console) as java.util.concurrent.atomic.AtomicReference<Any>).set(fresh)
            preparedScriptModel = fresh
            if (sharedConsole === console) sharedScriptModel = fresh
            // set 后立即验证：getScriptModel() 是否真的返回 fresh（22:38 实证 set 后 40ms
            // post-probe 仍 stmts=0——identityHashCode 对比确认 set 持久化与否）
            val afterSet = runCatching {
                consoleClass.getMethod("getScriptModel").invoke(console)
            }.getOrNull()
            val afterStmts = runCatching {
                (afterSet?.javaClass?.getMethod("statements")?.invoke(afterSet) as? Iterable<*>)
            }.getOrNull()?.toList()?.size
            LOG.info(
                "[M36] refreshConsoleModel: replaced with explicit-language model: " +
                    "newLang=$freshLang, stmts=${freshStmts.size}, freshIsActual=$freshIsActual, " +
                    "splitMode=$oldSplit->$targetMode, setAfterGetIdentity=${
                        System.identityHashCode(afterSet)
                    }, setAfterStmts=$afterStmts",
            )
            // M36.5.24i：set 后 fresh 可能被 document modificationStamp 变化（editor 初始化/
            // highlight 触发）击穿 sealed 缓存 → statements() 变 0（22:44 实证 set 后 20ms
            // stmts=1→0、identity 不变）。启动 Timer 周期重查：若 console.getScriptModel()
            // 的 statements 为空则重新构造 fresh 并 set（document 稳定后 sealed 不再重建），
            // 连续稳定即停止，最多重试 6 次（约 6s，覆盖 editor 初始化窗口）。
            var stable = 0
            var retries = 0
            val timer = javax.swing.Timer(1000, null)
            timer.addActionListener {
                runCatching {
                    val cur = consoleClass.getMethod("getScriptModel").invoke(console)
                    val curStmts = runCatching {
                        (cur?.javaClass?.getMethod("statements")?.invoke(cur) as? Iterable<*>)
                    }.getOrNull()?.toList()
                    if (curStmts != null && curStmts.isNotEmpty()) {
                        stable++
                        if (stable >= 2) {
                            timer.stop()
                            LOG.info("[M36] refreshConsoleModel: model stable (stmts=${curStmts.size})")
                            // stable 后最终探针——确认 statementsRange 恢复非零（▶ isEnabled 依据）
                            runCatching {
                                val editorNow = preparedEditor ?: return@runCatching
                                val execNow = preparedExecOption ?: return@runCatching
                                val topNow = preparedTopBlock ?: return@runCatching
                                logDataContextCheck(topNow, console, editorNow, execNow, LOG)
                            }.onFailure { e ->
                                LOG.warn("[M36] refreshConsoleModel stable-probe FAILED: ${e.message}")
                            }
                        }
                    } else {
                        stable = 0
                        retries++
                        if (retries > 6) {
                            timer.stop()
                            LOG.warn("[M36] refreshConsoleModel: give up after $retries retries (stmts=${curStmts?.size})")
                            return@addActionListener
                        }
                        // 重新构造 + set（document modificationStamp 已稳定）
                        val freshN = Class.forName("com.intellij.sql.script.SqlScriptModel")
                            .getConstructor(
                                Project::class.java,
                                com.intellij.openapi.vfs.VirtualFile::class.java,
                                com.intellij.lang.Language::class.java,
                            )
                            .newInstance(project, consoleVf, lang)
                        val nStmts = runCatching {
                            (freshN.javaClass.getMethod("statements").invoke(freshN) as? Iterable<*>)
                        }.getOrNull()?.toList()?.size
                        if (nStmts != null && nStmts > 0) {
                            val f2 = consoleClass.getDeclaredField("myScriptModel")
                            f2.isAccessible = true
                            @Suppress("UNCHECKED_CAST")
                            (f2.get(console) as java.util.concurrent.atomic.AtomicReference<Any>).set(freshN)
                            preparedScriptModel = freshN
                            if (sharedConsole === console) sharedScriptModel = freshN
                            LOG.info("[M36] refreshConsoleModel: retry#$retries re-set fresh (stmts=$nStmts)")
                        } else {
                            LOG.info("[M36] refreshConsoleModel: retry#$retries fresh empty (stmts=$nStmts), keep current")
                        }
                    }
                }.onFailure { e ->
                    LOG.warn("[M36] refreshConsoleModel timer FAILED: ${e.message}")
                    timer.stop()
                }
            }
            timer.isRepeats = true
            timer.start()
            // 重建后探针：确认 statementsRange 恢复非零（▶ isEnabled 依据）
            runCatching {
                val editorNow = preparedEditor ?: return@runCatching
                val execNow = preparedExecOption ?: return@runCatching
                val topNow = preparedTopBlock ?: return@runCatching
                logDataContextCheck(topNow, console, editorNow, execNow, LOG)
            }.onFailure { e ->
                LOG.warn("[M36] refreshConsoleModel post-probe FAILED: ${e.message}")
            }
        }.onFailure { e ->
            LOG.warn("[M36] refreshConsoleModel FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    /** M36.5.2：主题化完整控制台 SplitPane（顶部块 40% / 结果区 60%）——M36.4 形态，两处构建共用。 */
    private fun buildConsoleSplitPane(
        topBlock: javax.swing.JComponent,
        resultComponent: javax.swing.JComponent,
    ): javax.swing.JComponent {
        // M36.5.55：给 top（编辑器块）设最小高度兜底——JSplitPane 初始 divider 按 preferred 推算，
        // 编辑器块（BorderLayout 包装 editorComponentRaw）preferred 可能为 0（未布局），导致 top
        // collapse 到 0 高（沙盒实证 topH=0，setDividerLocation(0.6) 也撑不起来）。minimumSize
        // 让 JSplitPane 至少给编辑器块留 260px，不被压塌。
        topBlock.minimumSize = java.awt.Dimension(topBlock.minimumSize?.width ?: 400, 260)
        // M36.5.64：给底（结果区）设最小高度兜底——DBN 结果区（RunnerLayoutUi grid）preferred=84px
        // （只够 Output 一行），re-parent 后 JSplitPane 每次重排都会把 bottom 拉回 preferred
        // （沙盒实证 `topH=569 total=656`=86%，bottom=84px）。minimumSize 让 JSplitPane 无法把
        // 结果区压到 84px，至少留 160px，配合 realignSplitDivider 双向校正 60%，结果网格不再塌缩。
        resultComponent.minimumSize = java.awt.Dimension(
            resultComponent.minimumSize?.width ?: 400, 160,
        )
        return object : javax.swing.JSplitPane(
            javax.swing.JSplitPane.VERTICAL_SPLIT, topBlock, resultComponent,
        ) {
            override fun paint(g: java.awt.Graphics) {
                super.paint(g)
                g.color = com.intellij.util.ui.UIUtil.getPanelBackground()
                g.fillRect(0, getDividerLocation(), width, getDividerSize())
            }
        }.apply {
            // M36.5.25f：编辑器 60% / 结果区 40%——原 0.4 导致 SQL 区域只占 40%（窗口放大时
            // 额外空间 60% 给结果区，SQL 被压扁）；0.6 让编辑器主导高度，随窗口自适应。
            resizeWeight = 0.6
            isContinuousLayout = true
            isOneTouchExpandable = false
            dividerSize = 3
            border = com.intellij.util.ui.JBUI.Borders.empty()
            background = com.intellij.util.ui.UIUtil.getPanelBackground()
            // M36.6.2：分隔位置保险（每个实例自带，覆盖 prepare/reuse/contentAdded 重建全部路径）——
            // 构造时子组件 0x0 invalid（沙盒日志 toString 实证），首次真实布局的 divider 由 preferred
            // 尺寸推算，落点随构造时序漂移（用户三张截图：编辑器塌缩 ~40px / 编辑器截断无结果区 /
            // 结果区仅剩窄条，每次打开形态不同）。首触获得真实高度后无条件强制 60%，一次性监听，
            // 不干预用户后续拖拽。
            addComponentListener(object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent) {
                    if (height < 200) return
                    removeComponentListener(this)
                    setDividerLocation(0.6)
                    revalidate()
                    repaint()
                }
            })
        }
    }

    /**
     * M36.5.41-A4：持久整控台——结果刷新时**原位换底**（bottomComponent 指向新结果实例），
     * 不再重建 SplitPane。重建会让面板侧 embedConsole 身份漂移（sameAsPrev=false，方案 A 判据
     * 失效），且旧 SplitPane 反复摘挂正是「右侧空白/显示链断裂」的来源（M36.5 根因级问题）。
     * 顶部块（原生头部工具栏+编辑器）自构建起不再摘动；仅当整控台容器缺失（full 非分栏）时
     * 回退 [buildConsoleSplitPane]（feature flag 语义，可回退旧路径）。
     *
     * M36.5.43 修正守卫：原实现"liveResult.parent != null 且 != sp → 跳过本拍"过度保守——
     * 沙盒日志实证 `parent=ServiceViewComponentWrapper`（DBN 的服务视图宿主，结果的**长期老家**，
     * 非 DBN 临时 reparent 中转），守卫把它误判为"临时持有"而**每拍跳过**，结果区永远进不了
     * 面板（16:05 日志 `result component held by DBN ... skip this tick` 连续数十拍）。
     * 正确语义：只要 liveResult 是当前结果实例且不在本 SplitPane（parent !== sp），就**强制换入**
     *（从 ServiceView 摘出挂回面板）——DBN 的 ServiceView 是它"默认展示"宿主，方案 A 下结果
     * 属于面板，我们保持不变式 SplitPane.bottom == liveResult。换入后 parent 必为 sp；被 DBN
     * 抢走则下一拍再换回（幂等）。
     */
    private fun swapResultIntoPersistentConsole(
        full: javax.swing.JComponent?,
        liveResult: javax.swing.JComponent,
        topBlock: javax.swing.JComponent,
        LOG: com.intellij.openapi.diagnostic.Logger,
    ): javax.swing.JComponent {
        val sp = full as? javax.swing.JSplitPane
            ?: return buildConsoleSplitPane(topBlock, liveResult)
        val current = sp.bottomComponent
        if (current === liveResult && liveResult.parent === sp) {
            // M36.5.45：同实例也要强制重绘——DBN 复用同一 Content 组件（DataGrid），实例不变
            // 但内容已更新（如重复查询同一表），不重绘则用户仍看到上一个 SQL 结果（"执行 A
            // 仍显示 B"沙盒实证 17:04:41 `instanceChanged=false`）。repaint 触发 DBN 重渲染。
            // M36.5.46：同步校准 divider——同实例换入时 DBN 可能已 reparent/重挂，divider 落点
            // 漂移到顶部（编辑器被压缩，用户截图"执行 A 后布局塌缩"实证）。布局初无尺寸不强改，
            // 下一拍（尺寸就绪）再强制 60%。force=true：执行换入覆盖预览折叠的 divider=1.0。
            // M36.5.64：同实例路径也设 bottom 最小高度——否则 JSplitPane 重排时把结果区压回
            // preferred(84px)，结果网格塌缩（沙盒 topH=569 total=656 实证）。
            liveResult.minimumSize = java.awt.Dimension(
                liveResult.minimumSize?.width ?: 400, 160,
            )
            realignSplitDivider(sp, force = true)
            sp.revalidate()
            sp.repaint()
            return sp
        }
        // 强制换入：从 DBN 宿主（ServiceView 等）摘出，挂回本 SplitPane 底部。
        if (current != null) sp.remove(current)
        sp.bottomComponent = liveResult
        // M36.5.64：换入路径也设 bottom 最小高度——防 JSplitPane 重排时把结果区压回 preferred。
        liveResult.minimumSize = java.awt.Dimension(
            liveResult.minimumSize?.width ?: 400, 160,
        )
        // M36.5.63：取证 liveResult 运行时类型 + 其内部是否 JSplitPane（RunnerLayoutUi 结果页内部
        // sub-split 塌缩是 "Restore Default Layout 修复" 的根源——内外层两个 SplitPane 嵌套时，
        // 外层 setDividerLocation 只调外层，内层（结果页 Output/结果/消息）塌缩仍显示 Output 一行）。
        runCatching {
            LOG.info("[M36] liveResult class=${liveResult.javaClass.name}, hasSplit=${liveResult is javax.swing.JSplitPane}")
            if (liveResult is javax.swing.JSplitPane) {
                LOG.info("[M36] liveResult divider=${liveResult.dividerLocation} orientation=${liveResult.orientation} top=${liveResult.topComponent?.javaClass?.simpleName} bottom=${liveResult.bottomComponent?.javaClass?.simpleName}")
            }
        }
        realignSplitDivider(sp, force = true)
        sp.revalidate()
        sp.repaint()
        LOG.info("[M36] persistent console bottom swapped (result id=${System.identityHashCode(liveResult)}, prevParent=${liveResult.parent?.javaClass?.simpleName ?: "null"})")
        return sp
    }

    /** M36.5.52/53：校准整控台 SplitPane 分隔——[force]=true（执行换入）时**无条件**拉回 60%
     *（覆盖预览折叠的 1.0）；[force]=false（componentListener 兜底）时仅当顶部<35% 才校正。
     * 调用方：执行结果换入（force=true）、预览折叠后布局漂移兜底（force=false）。 */
    private fun realignSplitDivider(sp: javax.swing.JSplitPane, force: Boolean = false) {
        // ① invokeLater 下一拍（组件已 add 到树）
        // write-safe: UI-only（纯 Swing/只读，无文档写）
        ApplicationManager.getApplication().invokeLater({
            realignDividerOnce(sp, force)
        }, com.intellij.openapi.application.ModalityState.any())
        // ② componentListener：每次布局后检查（force=false，只在顶部不足时校正）
        sp.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                realignDividerOnce(sp, force = false)
            }
        })
    }

    /** 单次 divider 校正：[force]=true 无条件 0.6；[force]=false 双向校正——
     *  顶部过小（topH < total*0.35，编辑器被压）或**顶部过大**（topH > total*0.68，结果区塌缩到
     *  preferred，只显示 Output 一行）都拉回 0.6。M36.5.64：原实现只校正"顶部过小"，漏掉
     *  "结果区塌缩"方向——DBN 结果区（RunnerLayoutUi grid）preferred=84px，re-parent 后内部
     *  内容变化会把 bottom 拉回 preferred（沙盒上限 `topH=569 total=656`=86%，bottom=84px），
     *  force=false 分支不触发 → 结果区一直塌缩（用户截图"Output 没撑满下方空白"）。 */
    private fun realignDividerOnce(sp: javax.swing.JSplitPane, force: Boolean) {
        if (sp.height < 100) return   // 布局初无尺寸，等下次
        val topH = sp.topComponent?.height ?: 0
        val total = sp.height
        val log = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        val tooAuto = force || topH < total * 0.35 || topH > total * 0.68
        if (tooAuto) {
            log.debug("[M36] divider realign(force=$force): topH=$topH total=$total → 0.6")
            sp.setDividerLocation(0.6)
            sp.revalidate()
            sp.repaint()
            dumpSplitBounds(sp, log)
        } else {
            log.debug("[M36] divider OK: topH=$topH total=$total (no change)")
        }
        // M36.5.75：布局级修复 Output 控制台 44px 塌缩（替代 M36.5.72/73 的 fillOutputConsole
        // 一次性 setSize——被平台 EditorMergedHorizontalScrollBarLayout 每轮 layout 压回，必然失效）
        // write-safe: UI-only（纯 Swing/只读，无文档写）
        ApplicationManager.getApplication().invokeLater({
            installOutputHistoryFillLayout(sp, log)
        }, com.intellij.openapi.application.ModalityState.any())
    }

    /**
     * M36.5.75：Output 控制台「有内容但只显示 2 行（44px）」的根因修复——**布局替换**，非一次性 setSize。
     *
     * 根因（javap 反编译沙盒 IU-2024.3.7.1 `lib/app-client.jar` 铁证）：
     * `LanguageConsoleImpl.myPanel`（ConsoleEditorsPanel）的布局是平台私有类
     * `com.intellij.execution.console.EditorMergedHorizontalScrollBarLayout`——面板竖直二分：
     * 上=historyViewer（Output 历史编辑器，`EditorImpl$PanelWithFloatingToolbar`）、下=输入编辑器
     * （consoleExecutionEditor，其组件已被我们收养进 topBlock）。`layoutContainer` 的高度分配
     * **输入编辑器优先**：容器高度不足时输入编辑器拿 preferred，history 被压到
     * `getMinHistoryLineCount() × lineHeight`（字节码硬编码 `iconst_2` = 2 行 × 22px = **44px**；
     * 沙盒 [BOUNDS] 实证 consoleImpl=864x235 满高而 PanelWithFloatingToolbar=44、pref=1122）。
     * 平台唯一豁免分支「输入编辑器不可见 → history 占满整个容器」走不到——输入编辑器组件
     * 在我们 topBlock 中可见（isVisible 与挂载位置无关）。这同时解释了 M36.5.72/73 的一次性
     * setSize 为何必然失效：该布局管理器每轮 layout 都会重算并压回。
     *
     * 修复：在结果区（bottom）内定位 Output 历史编辑器（类名精确匹配
     * `EditorImpl$PanelWithFloatingToolbar`——结果网格的 JBScrollPane/DataGrid/TableResultView
     * 类名不同，绝不误伤，M36.5.69 教训），向上收集到 EMHSBL 布局面板（含）为止的容器链，
     * 逐层安装 [FillTargetLayout]（链上目标子组件占满、其余子组件 0 高、可见 JScrollBar 保底条）。
     * 布局管理器随每次 validate 重跑，无"被下一布局周期压回"的失效模式；逐层自证日志（教训 11）。
     */
    internal fun installOutputHistoryFillLayout(root: java.awt.Component, log: com.intellij.openapi.diagnostic.Logger) {
        runCatching {
            // M36.5.76：搜索范围收紧到**结果区（bottom）**——M36.5.75 实证缺陷：从整个 SplitPane
            // 深搜时第一个命中的 PWT 是 topBlock 顶部编辑器（同为 EditorImpl$PanelWithFloatingToolbar），
            // 从它向上找不到 EMHSBL 面板而整体 skip（沙盒 68 次 `not found; skip`，0 次安装）。
            val sp = root as? javax.swing.JSplitPane
            val searchRoot: java.awt.Component = sp?.bottomComponent ?: root
            if (searchRoot !is java.awt.Container) return
            // 1) 定位结果区内全部 Output 历史编辑器（类名精确匹配，结果网格类名不同绝不误伤）
            val targets = ArrayList<java.awt.Component>()
            fun findAll(c: java.awt.Component, depth: Int) {
                if (targets.size >= 4 || depth > 24) return
                if (c.javaClass.name.contains("EditorImpl\$PanelWithFloatingToolbar")) { targets.add(c); return }
                if (c is java.awt.Container) for (d in c.components) findAll(d, depth + 1)
            }
            findAll(searchRoot, 0)
            if (targets.isEmpty()) {
                log.debug("[M36][OUTFILL] no output editor (EditorImpl\$PanelWithFloatingToolbar) in result area; skip")
                return
            }
            var installedAny = false
            for (t in targets) {
                // 幂等：直接父层已装过则跳过（重复 realign/预览+执行双路径）
                if (t.parent?.layout is FillTargetLayout) continue
                // 2) 从 target 直接父层向上收集容器链，锚定到 EMHSBL 布局面板（含，contains 匹配兼容子类）
                val chain = ArrayList<java.awt.Container>()
                var anchored = false
                var cur: java.awt.Container? = t.parent
                while (cur != null) {
                    chain.add(cur)
                    if (cur.layout?.javaClass?.name?.contains("EditorMergedHorizontalScrollBarLayout") == true) {
                        anchored = true
                        break
                    }
                    if (cur === searchRoot) break
                    cur = cur.parent
                }
                if (!anchored) {
                    // M36.5.76：诊断——祖先链一次性打点（节流 2s），定位 Output 编辑器真实上层结构
                    val now = System.currentTimeMillis()
                    if (now - lastOutFillDiagAt > 2000) {
                        lastOutFillDiagAt = now
                        val chainDesc = StringBuilder()
                        var p: java.awt.Container? = t.parent
                        var lvl = 0
                        while (p != null && lvl < 10) {
                            chainDesc.append("\n  ↑$lvl ${p.javaClass.name} layout=${p.layout?.javaClass?.name} bounds=${p.bounds}")
                            if (p === searchRoot) break
                            p = p.parent; lvl++
                        }
                        log.debug("[M36][OUTFILL] EMHSBL panel not found above output editor; ancestors=$chainDesc")
                    }
                    continue
                }
                // 3) 逐层安装填充布局（跳过 JScrollPane——ScrollPaneLayout 由滚动机制管理，不可替换）
                var child: java.awt.Component = t
                for (c in chain) {
                    if (c is javax.swing.JScrollPane) {
                        log.debug("[M36][OUTFILL] chain node is JScrollPane (${c.javaClass.name}), keep its layout")
                    } else if (c.layout !is FillTargetLayout) {
                        val oldLayout = c.layout?.javaClass?.name ?: "null"
                        val before = child.bounds
                        c.layout = FillTargetLayout(child)
                        c.revalidate()
                        log.debug("[M36][OUTFILL] installed on ${c.javaClass.name} (was $oldLayout) target=${child.javaClass.simpleName} before=$before")
                        installedAny = true
                    }
                    child = c
                }
            }
            if (installedAny) {
                searchRoot.revalidate()
                searchRoot.repaint()
            }
        }.onFailure { e ->
            log.warn("[M36][OUTFILL] install failed: ${e.message}")
        }
    }

    /**
     * M36.5.75：目标子组件填充布局——[target] 占满容器可用高度（可见 [javax.swing.JScrollBar]
     * 占底部条位），其余子组件高度 0（Output 内的输入编辑器是重复编辑表面，用户在 topBlock
     * 编辑 SQL）。每次 validate 自动重跑（幂等），不存在一次性 setSize 被压回的问题；
     * 自证日志节流（每实例前 3 次 target 高度修正打点，教训 11）。
     */
    private class FillTargetLayout(private val target: java.awt.Component) : java.awt.LayoutManager {
        private var logged = 0
        override fun addLayoutComponent(name: String?, comp: java.awt.Component?) {}
        override fun removeLayoutComponent(comp: java.awt.Component?) {}
        override fun preferredLayoutSize(parent: java.awt.Container?): java.awt.Dimension {
            val p = parent ?: return java.awt.Dimension(0, 0)
            // 已有尺寸时按当前尺寸申报，避免祖先按内容高（history pref=上千 px）扩张/滚动
            if (p.height > 0) return java.awt.Dimension(p.width, p.height)
            val sbH = p.components.firstOrNull { it is javax.swing.JScrollBar && it.isVisible }
                ?.preferredSize?.height ?: 0
            return java.awt.Dimension(p.width, target.preferredSize.height + sbH)
        }
        override fun minimumLayoutSize(parent: java.awt.Container?): java.awt.Dimension =
            java.awt.Dimension(0, 0)
        override fun layoutContainer(parent: java.awt.Container) {
            val sb = parent.components.firstOrNull { it is javax.swing.JScrollBar && it.isVisible }
            val reserved = if (sb != null) sb.preferredSize.height.coerceAtLeast(0) else 0
            val avail = (parent.height - reserved).coerceAtLeast(0)
            for (c in parent.components) {
                when {
                    c === target -> c.setBounds(0, 0, parent.width, avail)
                    c === sb -> c.setBounds(0, avail, parent.width, reserved)
                    else -> c.setBounds(0, avail, parent.width, 0)
                }
            }
            if (logged < 3 && avail > 0 && target.height != avail) {
                logged++
                com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
                    .debug("[M36][OUTFILL] layout: parent=${parent.javaClass.simpleName} h=${parent.height} target(${target.javaClass.simpleName}) → $avail")
            }
        }
    }

    /** M36.5.60：运行时组件边界取证——像素扫描无法区分"编辑器未拉伸"与"编辑器拉伸但背景同色"
     *（editor bg 43,45,48 vs panel bg 43,45,48 无差异）。直接 dump SplitPane/top/bottom 及其直接
     * 子组件的真实 bounds + size，一锤定音：topblock 是否被编辑器真的 fill，结果区是否占满 bottom。 */
    private fun dumpSplitBounds(sp: javax.swing.JSplitPane, log: com.intellij.openapi.diagnostic.Logger) {
        // T-Perf-13：取证 dump 降 debug 门控（非 debug 模式零开销）
        if (!log.isDebugEnabled) return
        runCatching {
            fun b(c: java.awt.Component?): String = if (c == null) "null" else
                "${c.javaClass.simpleName}@${System.identityHashCode(c)} bounds=${c.bounds} pref=${c.preferredSize} min=${c.minimumSize} max=${c.maximumSize}"
            log.debug("[M36][BOUNDS] sp=${b(sp)} divider=${sp.dividerLocation}")
            log.debug("[M36][BOUNDS]   top=${b(sp.topComponent)}")
            (sp.topComponent as? java.awt.Container)?.components?.forEachIndexed { i, c ->
                log.debug("[M36][BOUNDS]   top.child[$i]=${b(c)}")
                if (c is java.awt.Container) c.components.forEachIndexed { j, d ->
                    log.debug("[M36][BOUNDS]   top.child[$i].child[$j]=${b(d)}")
                    // M36.5.61：编辑器视图（PanelWithFloatingToolbar）内部是否含未拉伸的 scrollpane/
                    // viewport——mask 真相在此层（编辑器 view 有 bounds 但只 paint 内容高度）
                    if (d is java.awt.Container) d.components.forEachIndexed { k, e ->
                        log.debug("[M36][BOUNDS]   top.child[$i].child[$j].child[$k]=${b(e)}")
                        if (e is javax.swing.JScrollPane) {
                            log.info("[M36][BOUNDS]     scrollpane viewport=${e.viewport?.let { v -> "${b(v)} vsbV=${e.verticalScrollBar?.isVisible} hsbV=${e.horizontalScrollBar?.isVisible}" } }")
                        }
                    }
                }
            }
            log.info("[M36][BOUNDS]   bottom=${b(sp.bottomComponent)}")
            // M36.5.63：递归看结果区内部——RunnerLayoutUi 结果页有内部 layout（Output/结果 sub-split），
            // 换入时若内部 divider 塌缩（结果页只显示 Output 一行），外层 bounds 正确但内部是坏的。
            fun dumpDeep(c: java.awt.Component?, depth: Int, path: String) {
                if (c == null || depth > 12) return
                val spExtra = if (c is javax.swing.JSplitPane) " div=${c.dividerLocation} split(${c.orientation})" else ""
                log.info("[M36][BOUNDS]   ${path}=${b(c)}$spExtra")
                if (c is java.awt.Container) {
                    var idx = 0
                    for (d in c.components) {
                        dumpDeep(d, depth + 1, "$path.child[$idx]")
                        idx++
                    }
                }
            }
            // 结果区整体（MyNonOpaquePanel/MyComponent）+ 内部两级，重点找 JSplitPane/塌缩 divider
            (sp.bottomComponent as? java.awt.Container)?.components?.forEachIndexed { i, c ->
                log.info("[M36][BOUNDS]   bottom.child[$i]=${b(c)}")
                if (c is java.awt.Container) c.components.forEachIndexed { j, d ->
                    dumpDeep(d, 2, "bottom.child[$i].child[$j]")
                }
            }
            // M36.5.68：定位 Output 控制台（ConsoleView/EditorImpl 文本视图）真实高度——问题 SQL 后
            // 结果 grid 内容 ProviderWrapper 填满 234px，但承载 Output 历史文本的滚动视图可能只按
            // 内容高度渲染（2 行），下方留白。深扫所有 JScrollPane/JTextComponent，看谁没撑满。
            fun scanTextViews(c: java.awt.Component?, path: String) {
                if (c == null) return
                val cls = c.javaClass.name
                if (c is javax.swing.JScrollPane || cls.contains("ConsoleView") || cls.contains("EditorImpl") || c is javax.swing.text.JTextComponent) {
                    log.info("[M36][BOUNDS][TEXT] $path ${cls.substringAfterLast('.')} bounds=${c.bounds} pref=${c.preferredSize}")
                    if (c is javax.swing.JScrollPane) {
                        log.info("[M36][BOUNDS][TEXT]   viewport=${c.viewport?.let { it.bounds.toString() }} vsbV=${c.verticalScrollBar?.isVisible}")
                        c.viewport?.let { scanTextViews(it.view, "$path.view") }
                    }
                }
                if (c is java.awt.Container) {
                    var idx = 0
                    for (d in c.components) { scanTextViews(d, "$path.child[$idx]"); idx++ }
                }
            }
            (sp.bottomComponent as? java.awt.Container)?.let { scanTextViews(it, "bottom") }
        }.onFailure { e ->
            log.info("[M36][BOUNDS] dump failed: ${e.message}")
        }
    }

    /**
     * T-ErrorLocate T-ELU-01/03：错误投递**统一收口**——富通道优先，富通道缺席才回退旧
     * `onError(String)`；两者**互斥**，不再先后各投一次。
     *
     * **为什么必须互斥**（T-ELU-01 实测的真相，与计划 §一.1 的「按钮可见但点了没反应」不同）：
     * 消费方 `SqlPreviewPanel` 的 `onError` 恒为 `resultPanel.showError(errorMsg)`，而
     * `showError` 会清掉定位动作与定位按钮（`ui/SqlResultPanel.kt` 的
     * `locateHandler = null` + `errorLocateButton.isVisible = false`）。原实现先投富通道
     * （`showErrorRich` 置按钮可见）、紧接着无条件投旧通道——**同一 EDT 任务内**后者立刻把
     * 前者抹掉，端态恒为「按钮不可见」，T-ELU-01 的「先解析后显隐」因此形同 no-op。
     * 两者呈现的文本同源（富通道 `showErrorRich` 内部就是 `showError(info.message)`），
     * 故富通道成功时不再投旧通道，展示行为等价而定位态得以保留。
     *
     * 富通道自身抛异常（`runCatching`）时**回退旧通道**——不让「结构化」这一增强把错误呈现吞掉。
     *
     * **2026-09-23 真机走查补记（本函数曾丢失过，勿再删）**：本函数在分支上实现于 `4186231`，
     * 但 `914724c`（「O-48/O-49 采信 master 侧已合入实现」）把 O-49 整体改回 master 侧形态，
     * 该收口随之消失，端态退回「按钮永不出现」——真机走查实证：一条锚点可解析的失败语句
     * （`DELETE FROM no_such_table_xyz`）面板只出现红字 +「复制错误信息」，**没有定位按钮**。
     *
     * @param cause        解包后的异常（取 SQLState/errorCode；控制台历史文本错误传 null）
     * @param message      错误文本（位置 marker 从它提取）
     * @param sqlMaxLength 提交/被执行文本长度（T-ELU-03 越界钳制上界）；null = 不钳制
     */
    internal fun deliverError(
        onErrorRich: ((ExecutionErrorInfo) -> Unit)?,
        onError: ((String) -> Unit)?,
        cause: Throwable?,
        message: String,
        sqlMaxLength: Int? = null,
    ) {
        val rich = onErrorRich
        if (rich != null) {
            runCatching { rich(ErrorPositionExtractor.buildInfo(cause, message, sqlMaxLength)) }
                .onFailure {
                    com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
                        .warn("[T-ErrorLocate] onErrorRich invoke failed → fallback to onError", it)
                    onError?.invoke(message)
                }
        } else {
            onError?.invoke(message)
        }
    }

    /**
     * O-64/T-RollbackIdentityParse：identity 包装产物的「整批直跑」ExecOption 副本。
     *
     * 平台语义（javap 243 `DatabaseSettings$ExecOption` + 常量组实证）：多语句执行范围由
     * `execInside`（光标在语句内）/ `execOutside`（光标在语句间）两个 int 决定，默认
     * `EXECUTE_INSIDE_SHOW_CHOOSER` 弹 Statements 选择层且默认只选第一条——P19 的 identity
     * 产物首语句恰是无副作用的 `SET … ON`，按默认执行等于 INSERT 没跑。本副本把两键都覆写为
     * `*_WHOLE_SCRIPT`：不弹层、整批全部执行，即「默认全选」的唯一平台杠杆。
     *
     * 只造副本：`copyFrom` 基础配置后覆写两键，preparedExecOption/sharedExecOption 缓存与
     * 用户全局设置（`getDefaultExecOption()` 活实例）一概不碰。反射不可用 → 原样返回基础
     * 实例（执行维持现态，不劣化）。测试直接覆盖本函数（DatabaseTools 在默认 test classpath）。
     */
    internal fun wholeScriptExecOption(base: Any): Any = runCatching {
        val settingsClass = Class.forName("com.intellij.database.settings.DatabaseSettings")
        val fresh = base.javaClass.getDeclaredConstructor().newInstance()
        base.javaClass.getMethod("copyFrom", base.javaClass).invoke(fresh, base)
        base.javaClass.getField("execInside").setInt(fresh, settingsClass.getField("EXECUTE_INSIDE_WHOLE_SCRIPT").getInt(null))
        runCatching {
            base.javaClass.getField("execOutside").setInt(fresh, settingsClass.getField("EXECUTE_OUTSIDE_WHOLE_SCRIPT").getInt(null))
        }
        fresh
    }.getOrElse { base }

    /**
     * M36.5：在指定控制台上执行 SQL（后台线程 + ReadAction + 5s 超时 + 结果 Tab 激活重试）。
     * 由 [executePrepared]（已备控制台）与准备全链（executeAfterPrepare=true）共用。
     *
     * @param onResultsReady 执行触发成功后回调完整 UI（顶部块+结果区 SplitPane）——
     *                       仅 executePrepared 路径传入（准备时只嵌了顶部块，执行后换入结果区）
     */
    private fun executeOnConsole(
        project: Project,
        console: Any,
        editor: com.intellij.openapi.editor.Editor,
        scriptModel: Any,
        execOption: Any,
        ui: Any?,
        onResultsReady: ((javax.swing.JComponent) -> Unit)?,
        onError: ((String) -> Unit)?,
        onErrorRich: ((ExecutionErrorInfo) -> Unit)? = null,
        dsName: String? = sharedDataSourceName,
        wholeScriptExec: Boolean = false,
    ) {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        // O-64/T-RollbackIdentityParse：identity 包装产物整批直跑——覆写本次执行的 ExecOption
        // （只造**副本**传下去，prepared/shared 缓存与用户全局设置一概不动），弹层不再出现、
        // 整批（SET…ON + INSERT + SET…OFF）全部执行，即「Statements 默认全选」的平台实现杠杆。
        // 反射不可用（Database 插件类缺席）时原样返回基础实例 = 维持现态，不劣化。
        val effectiveExecOption = if (wholeScriptExec) wholeScriptExecOption(execOption) else execOption
        // T-MysqlGStrip T-MG-02：提交链总口（已备控制台路径）——提交文本源是控制台编辑器文档，
        // 执行前剥离 `\G` 后缀并把剥后文本**回写编辑器文档**（所见=所执行，避免显示/执行分叉）。
        // 回写复用既有 T-ExecDocSync 写纪律（dispatchDocWriteOnEdt → writeVfTextViaDocument：
        // setText→commit→saveDocument 同临界区，BGT 调用派 EDT），不新开写路径。
        // 方言按控制台数据源 JDBC URL 判定：非 MySQL 系方言不剥；URL 不可得 → 默认剥离（计划 §三定案）。
        val consoleVfForG = runCatching {
            console.javaClass.getMethod("getVirtualFile").invoke(console) as? com.intellij.openapi.vfs.VirtualFile
        }.getOrNull()
        val gStrip = stripGSuffixForSubmit(editor.document.text, ExecConsoleContext.dataSourceUrl(console))
        if (gStrip.stripped) {
            LOG.info(
                "[T-MysqlGStrip] stripped \\G suffix before submit " +
                    "(len ${gStrip.original.length}->${gStrip.submitText.length}, vf=${consoleVfForG?.name})",
            )
            if (consoleVfForG != null) dispatchDocWriteOnEdt(project, consoleVfForG, gStrip.submitText)
        }
        // T-DdlCacheInvalidate（O-12）：DDL/事务提交语句在提交前失效插件自持 schema 缓存
        // （DBX object_cache 语义：失效须在结果返回前、失效失败只记日志不吞执行结果——
        // Oracle 系 DDL 隐式提交，DDL 报错不代表没提交）。全执行链唯一提交收口点；
        // SQL 文本取控制台编辑器文档（三调用路径共用同一文档，提交时刻即执行内容）。
        val executedSql = if (gStrip.stripped) gStrip.submitText else editor.document.text
        runCatching { MetadataInvalidator.invalidateAfterExecution(executedSql) }
            .onFailure { LOG.warn("[T-DdlCacheInvalidate] invalidate hook failed (execution continues)", it) }
        // M36.5.52：新执行重置错误去重基线——本次执行产生的错误应上报，不与上次残留混淆。
        lastReportedError = null
        // T-ExecSilentNoop：新执行亦重置提示去重标记——同一次执行只报一次提示。
        executionHintReported = false
        // M36.5.57：记录本次执行开始时 console history 基线长度——早检只扫本次之后新增文本，
        // 忽略 history 残留（正常 SQL 不再"带过来"上个失败的错误）。
        consoleErrorBaseline = consoleHistoryLength(console)
        // T-CEC-01：取证日志去重基线同批重置——每次执行至多记一条未识别尾部。
        lastUnrecognizedTail = null
        runCatching {
            val consoleClass = Class.forName(CONSOLE_CLASS)

            // 后台执行 executeQueries（M36.3）：避免 EDT 阻塞导致密码弹窗被遮挡。
            // M36.5：executeQueries 内部 Document.createRangeMarker 要求 read action，
            // 线程池线程无 read action 上下文会抛 RuntimeExceptionWithAttachments
            val execute = consoleClass.getMethod(
                "executeQueries",
                com.intellij.openapi.editor.Editor::class.java,
                Class.forName("com.intellij.database.script.ScriptModel"),
                effectiveExecOption.javaClass,
            )

            // M36.5.10：执行提交包 DumbService.runWhenSmart——索引期查询提交被 Database 插件
            // 调度层推迟（沙盒实证 09:47 会话：dumb=true 下重试 16s 无 Tab、索引结束后才真正
            // 执行，用户感知"一直加载中"）。改为**提交前等 smart**：查询只在索引完成后提交，
            // 结果 Tab 重试在真实提交后才启动；面板清空/新预览（clearPrepared/prepareInConsole/
            // reuseSharedConsole 递增 executionGeneration）→ 代际不匹配的挂起提交作废。
            val generation = executionGeneration
            // M36.5.21b：**构建版本戳**——jar 替换对运行中的沙盒 JVM 无效（class 已加载不重载），
            // 曾连续多轮"修复未生效"实为用户未重启沙盒 IDE。版本戳让日志第一行即暴露运行版本，
            // 与源码不符时先重启再排查，杜绝无效往返。
            LOG.info("[M36] executeOnConsole begin (build=$BUILD_STAMP)")
            com.intellij.openapi.project.DumbService.getInstance(project).runWhenSmart {
                if (generation != executionGeneration) {
                    LOG.warn("[M36] execution submit cancelled (generation $generation != ${executionGeneration})")
                    return@runWhenSmart
                }
                // M36.5.20：vf 同步 + **强制 commitDocument**——同步只保证 document 内容一致，
                // vf 的 PSI 需 commit 后才与 document 对齐（M36.5.18 的"滞后一轮"正是 PSI 未
                // commit 时 createScriptModel 解析了旧内容）。commit 后 findFile 的 PSI 即为
                // 用户所见 SQL。
                runCatching {
                    val consoleVf = consoleClass.getMethod("getVirtualFile").invoke(console)
                        as? com.intellij.openapi.vfs.VirtualFile
                    if (consoleVf != null) {
                        // T-MysqlGStrip：剥离后的文本才是本次执行内容（回写失败时编辑器文档可能仍是
                        // 原文，此处以剥后文本为准收敛 vf，保证提交内容恒定无 `\G`）
                        val consoleText = if (gStrip.stripped) gStrip.submitText else editor.document.text
                        @Suppress("DEPRECATION") // T-M52：WriteAction.compute(ThrowableComputable) 243 编译目标仅此重载可用
                        com.intellij.openapi.application.WriteAction.compute<Unit, Throwable> {
                            // T-ExecDocSync T-EC-01：document 驱动写 + 同临界区落盘（saveDocument）——
                            // 执行前 console 手改未保存内容与磁盘收口，不留「已修改未保存」窗口
                            //（M36.5.20 的 commitDocument/PSI 对齐语义保留在 writeVfTextViaDocument 内）
                            writeVfTextViaDocument(project, consoleVf, consoleText)
                        }
                        LOG.info("[M36] console doc synced+committed+saved to vf before execution (${consoleVf.name})")
                    }
                }.onFailure { e ->
                    LOG.warn("[M36] sync console doc to vf FAILED: ${e.message}")
                }
                // 执行提交在 pooled thread 进行（executeQueries 内 Document.createRangeMarker 要求
                // read action；池线程无 read action 上下文会抛 RuntimeExceptionWithAttachments），
                // 但**所有 Swing 操作（JSplitPane 构造/换入/Timer 启动）必须在 EDT**——M36.5.10 曾
                // 把 buildConsoleSplitPane 放进 pooled thread，JSplitPane 构造触发编辑器 hierarchy
                // 事件 → ScrollingModelImpl.getVisibleArea → ThreadingAssertions 抛异常（用户堆栈
                // 实锤），异常阻断 onResultsReady/startResultTabRetry → 结果区不刷新/提示不显示。
                // M36.5.22：8s 连接等待监控**从提交时刻起算**（见 afterSubmitted）——此前挂在
                // 提交请求处，而原生事务派发实测可延迟 6.7s（16:53 会话实证），会导致连接
                // 正常时也误报"密码/驱动"提示。
                ApplicationManager.getApplication().executeOnPooledThread {
                    var submitted = false
                    try {
                        ReadAction.compute<Unit, Throwable> {
                            // M36.5.20：fresh model 回退 **vf PSI 版**（createScriptModel(findFile(vf))）。
                            // M36.5.18 的 createROFile 文本直构**不可靠**：14:55 会话 `stmts reflect
                            // failed`——RO file model 的 statements() 反射即抛异常，executeQueries
                            // 内部同样会失败 → 零语句执行（空结果 Tab）。vf 同步+commitDocument（EDT
                            // 步骤）已保证 findFile 的 PSI = 用户所见 SQL；此处按 vf PSI 建 model
                            //（DBN updateScriptModel 的同一重建路径）并**校验语句数**——count<=0 或
                            // 反射异常时回退 console.getScriptModel()（DBN 内部 model 保底可执行）。
                            val modelVf = runCatching {
                                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                                    .getFile(editor.document)
                            }.getOrNull()
                                ?: runCatching {
                                    consoleClass.getMethod("getVirtualFile").invoke(console)
                                        as? com.intellij.openapi.vfs.VirtualFile
                                }.getOrNull()
                            val vfPsi = modelVf?.let {
                                runCatching { PsiManager.getInstance(project).findFile(it) }.getOrNull()
                            }
                            fun statementsInfo(model: Any): String = runCatching {
                                val stmts = model.javaClass.getMethod("statements").invoke(model) as? Iterable<Any>
                                "count=${stmts?.toList()?.size}"
                            }.getOrElse { ex -> "FAILED:${ex.javaClass.simpleName}: ${ex.message}" }

                            var freshModel: Any? = vfPsi?.let { pf ->
                                runCatching {
                                    val facade = Class.forName("com.intellij.sql.psi.SqlPsiFacade")
                                        .getMethod("getInstance", Project::class.java)
                                        .invoke(null, project)
                                    facade.javaClass.getMethod("createScriptModel", PsiFile::class.java)
                                        .invoke(facade, pf)
                                }.getOrNull()
                            }
                            // 语句校验：vf PSI model 语句为空/反射异常 → 回退 console 内部 model
                            var freshModelDemoted = false
                            if (freshModel != null && statementsInfo(freshModel).startsWith("count=0")) {
                                LOG.warn("[M36] vf PSI model has 0 statements, fallback to console scriptModel")
                                freshModel = null
                                freshModelDemoted = true
                            }
                            if (freshModel == null) {
                                freshModel = runCatching {
                                    consoleClass.getMethod("getScriptModel").invoke(console)
                                }.getOrNull() ?: scriptModel
                                freshModelDemoted = true
                            }
                            if (freshModel !== scriptModel) {
                                preparedScriptModel = freshModel
                                if (sharedConsole === console) sharedScriptModel = freshModel
                            }
                            LOG.info("[M36] executing model: class=${freshModel.javaClass.simpleName}, " +
                                "stmts=${statementsInfo(freshModel)}, vfPsiText tail=...${
                                    vfPsi?.text?.replace("\n", " ")?.takeLast(30)
                                }")
                            // T-ExecSilentNoop F1a：提交前预检——探测 DBN 会静默拒绝的现场。
                            // demoted 时（vf PSI 解析 0 语句回落 console model）说明准备窗口不稳
                            //（方言 push 未完成 / PSI 未对齐），优先走 executeQueries(freshModel) 兜底
                            // 而非原生链，把"静默不执行"降级为"明确执行/明确报错"。
                            // 保守起见只在 demoted 时强制降级；非 demoted 保持 native 链原行为，
                            // 交由提交后的回执判定（afterSubmitted/startResultTabRetry）兜底。
                            val demoteToFallback = freshModelDemoted
                            execSilentProbe(console, freshModel, freshModelDemoted, dsName, LOG)
                            // M36.5.21c：执行链拆两段——vf PSI/model 探针在本 ReadAction 内；
                            // **原生 EDT 链挪到 ReadAction 外**（pooled 持读锁等 EDT、EDT 任务若需
                            // write 会死锁）。详见下方原生链处注释。
                            val vfPsiFinal = vfPsi
                            val editorFinal = editor
                            val execOptionFinal = effectiveExecOption
                            var nativeRun = false
                            if (vfPsi != null && !demoteToFallback) {
                                val providerClass = Class.forName(CONSOLE_PROVIDER_CLASS)
                                val findScriptModel = providerClass.getMethod(
                                    "findScriptModel",
                                    PsiFile::class.java,
                                    com.intellij.psi.PsiElement::class.java,
                                    com.intellij.openapi.editor.Editor::class.java,
                                    effectiveExecOption.javaClass,
                                )
                                val doRunMethod = runCatching {
                                    providerClass.getMethod(
                                        "doRunQueryInConsole",
                                        consoleClass,
                                        Class.forName("com.intellij.database.console.JdbcConsoleProvider\$Info"),
                                    )
                                }.getOrNull()
                                if (doRunMethod == null) {
                                    LOG.warn("[M36] native doRunQueryInConsole method not found, fallback")
                                } else {
                                    // M36.5.21d：原生链在 **EDT + write-safe 事务**执行——
                                    // doRunQueryInConsole → chooseStatements → saveAllDocuments 需要
                                    // write-safe context（用户堆栈实锤：invokeLater(any) 的 NON_MODAL
                                    // 是 write-unsafe，TransactionGuard 抛 "not any"）。
                                    // TransactionGuard.submitTransactionLater 与原生 Ctrl+Enter 用户
                                    // 事件同机制，事务内 model 修改合法。事务挂 console 为 Disposable
                                    // （面板关闭取消挂起事务）。不阻塞等待：pooled 持读锁等 EDT 会死锁。
                                    //
                                    // M36.5.22：**提交时刻不可控**——javap 实证 TransactionGuardImpl
                                    // .submitTransactionLater 内部是 invokeLaterOnWriteThread(runnable,
                                    // ctx != null ? ctx.myModality : ModalityState.nonModal())，而
                                    // getContextTransaction() 在写不安全/无全局进度时返回 null → 退化为
                                    // NON_MODAL 派发。本轮沙盒实证该派发延迟 6.7s（首轮 43ms，次轮
                                    // 6729ms）。因此**所有"提交后"动作（8s 监控 / 结果 Tab 监听 /
                                    // 换入）必须在 doRunQueryInConsole 真正返回后**才启动，否则监听
                                    // 窗口会在真实提交前跑完并把上一轮结果顶进面板（滞后一轮真因）。
                                    // write-safe: UI-only（纯 Swing/只读，无文档写）
                                    ApplicationManager.getApplication().invokeLater({
                                        if (generation != executionGeneration) {
                                            LOG.warn("[M36] execution submit cancelled at EDT (generation $generation != ${executionGeneration})")
                                            return@invokeLater
                                        }
                                        val disposable = (console as? com.intellij.openapi.Disposable)
                                            ?: com.intellij.openapi.Disposable { }
                                        try {
                                            com.intellij.openapi.application.TransactionGuard.getInstance()
                                                .submitTransactionLater(disposable) {
                                                    try {
                                                        val info = findScriptModel.invoke(null, vfPsi, vfPsi, editor, execOptionFinal)
                                                        if (info == null) {
                                                            LOG.warn("[M36] native findScriptModel returned null (nothing to run?)")
                                                            // M36.5.48：findScriptModel 空 = 无可执行语句——显示**真实原因**，
                                                            // 而非笼统凭据超时提示（此前误用 execute.credential.hint，误导用户查密码/驱动）。
                                                            // write-safe: UI-only（纯 Swing/只读，无文档写）
                                                            ApplicationManager.getApplication().invokeLater({
                                                                onError?.invoke(MpSqlVisualizerBundle.message("execute.noStatement"))
                                                            }, com.intellij.openapi.application.ModalityState.any())
                                                            return@submitTransactionLater
                                                        }
LOG.info("[M36] native findScriptModel OK, doRunQueryInConsole")
                                                    doRunMethod.invoke(null, console, info)
                                                    // 真实提交完成——此刻才启动结果监听
                                                    LOG.info("[M36] native query SUBMITTED (starting result watch)")
                                                    afterSubmitted(project, ui, console, onResultsReady, onError, LOG, dsName, demotedOverride = false, sqlText = executedSql, onErrorRich = onErrorRich)
                                                    } catch (t: Throwable) {
                                                        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                                                        // T-FP-02：反射包装层（InvocationTargetException）是普通异常，
                                                        // 直接判外层会漏判——须解包后再判 fatal 并透传（见 util/FatalErrors.kt）
                                                        if (FatalErrors.isFatal(cause)) throw cause
                                                        LOG.warn(
                                                            "[M36] native run chain FAILED: ${cause.javaClass.name}: ${cause.message}\n    " +
                                                                cause.stackTrace.take(8).joinToString("\n    at ") { it.toString() },
                                                            cause,
                                                        )
                                                        // M36.5.45：native 执行链失败必须通知 UI（此前仅 LOG.warn，用户看不到任何错误）
                                                        // T-ErrorLocate T-EL-02：富通道并行投递（SQLState/errorCode/驱动位置结构化），
                                                        // 旧 onError(String) 消费方零改动
                                                        // T-ErrorLocateUsability T-ELU-03（O-49）：接上被执行文本长度——
                                                        // 越界钳制此前只在单测生效（调用方全都不传 sqlMaxLength）
                                                        // write-safe: UI-only（纯 Swing/只读，无文档写）
                                                        ApplicationManager.getApplication().invokeLater({
                                                            deliverError(
                                                                onErrorRich, onError, cause,
                                                                cause.message ?: cause.toString(),
                                                                executedSql.length,
                                                            )
                                                        }, com.intellij.openapi.application.ModalityState.any())
                                                    }
                                                }
                                        } catch (t: Throwable) {
                                            if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传
                                            LOG.warn("[M36] submitTransactionLater FAILED: ${t.javaClass.simpleName}: ${t.message}", t)
                                        }
                                    }, com.intellij.openapi.application.ModalityState.any())
                                    nativeRun = true
                                }
                            }
                            if (!nativeRun) {
                                LOG.info("[M36][EXEC] fallback path (native not used: ${if (demoteToFallback) "demoted" else "no vf psi or doRun missing"}), executeQueries(freshModel)")
                                execute.invoke(console, editor, freshModel, execOptionFinal)
                                submitted = true
                            }
                        }
                        // M36.5.22：此处**不代表执行已发生**——原生路径的提交是异步事务（延迟不可控），
                        // 只有 executeQueries 兜底路径在此刻已真实提交。日志按路径区分，杜绝历轮
                        // "executeQueries invoked OK 但结果陈旧"的误判。
                        if (submitted) {
                            LOG.info("[M36] executeQueries invoked OK (fallback path, submitted synchronously)")
                        } else {
                            LOG.info("[M36] native transaction posted (NOT submitted yet - watch starts at submit)")
                        }
                    } catch (e: Exception) {
                        LOG.warn("[M36] executeQueries FAILED: ${e.message}", e)
                        // 执行触发失败：通知调用方（UI 回调须在 EDT）
                        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                        val msg = cause.message ?: cause.toString()
                        // M36.5.13：ModalityState.any()——模态弹窗（如 File Cache Conflict/密码框）
                        // 打开期间默认 modality 的 invokeLater 冻结（沙盒 12:10:02→12:10:50 实证
                        // 48s 挂起），换入/重试/提示全不跑 → "执行中"卡住。any() 使任务在模态
                        // pump 中照常执行（纯 Swing 更新，安全）。
                        // write-safe: UI-only（纯 Swing/只读，无文档写）
                        ApplicationManager.getApplication().invokeLater({
                            // T-ELU-01/03：经 [deliverError] 收口——富通道优先、**不再**同时投旧通道
                            // （旧通道会抹掉定位按钮），并接上被执行文本长度作越界钳制上界
                            deliverError(onErrorRich, onError, cause, msg, executedSql.length)
                        }, com.intellij.openapi.application.ModalityState.any())
                        return@executeOnPooledThread
                    }
                    // 兜底路径（executeQueries 同步提交）：监听在此启动；原生路径由事务内
                    // afterSubmitted 启动，此处不再重复（防双重监听互相顶掉结果）。
                    if (submitted) {
                        afterSubmitted(project, ui, console, onResultsReady, onError, LOG, dsName, demotedOverride = false, sqlText = executedSql, onErrorRich = onErrorRich)
                    }
                }
            }
        }.onFailure { e ->
            LOG.warn("[M36] executeOnConsole FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            // T-ELU-01/03：经 [deliverError] 收口（富通道优先 + 提交文本长度钳制上界）。
            // 本对回调在 executeOnPooledThread 的后台线程上（非 EDT）——[deliverError] 只做纯计算
            // 与回调转发，不含 Swing 操作，故不改变既有线程契约。
            deliverError(onErrorRich, onError, cause, cause.message ?: cause.toString(), executedSql.length)
        }
    }

    /**
     * M36.5.22：**真实提交后**的统一收尾（EDT, ModalityState.any()）。
     *
     * 背景（本轮沙盒 16:53 会话实证 + javap 反编译）：
     * 原生链经 `TransactionGuard.submitTransactionLater` 投递，派发时刻由平台决定且**不可控**
     *（首轮 43ms、次轮 6729ms）。原实现把「8s 连接监控 / onResultsReady 换入 / startResultTabRetry
     * / refreshResultsComponent」全部挂在"提交请求发出"处，于是：
     *   - retry 第 1 拍（400ms）就命中**上一轮**的 Result Tab → M36.5.13 的 forceSwapIn 把它
     *     **强制顶进面板** → 用户看到旧数据（"滞后一轮"）；
     *   - 4.8s 观察窗在真实提交前就关闭 → 之后无人再换入新结果。
     *
     * 修复：所有"提交后"动作改为在真实提交后启动，并以 `preExisting` 快照限定
     * **只认本次提交之后新建的结果 Tab**。
     */
    private fun afterSubmitted(
        project: Project,
        ui: Any?,
        console: Any,
        onResultsReady: ((javax.swing.JComponent) -> Unit)?,
        onError: ((String) -> Unit)?,
        LOG: com.intellij.openapi.diagnostic.Logger,
        dsName: String? = null,
        demotedOverride: Boolean = false,
        sqlText: String? = null,
        onErrorRich: ((ExecutionErrorInfo) -> Unit)? = null,
    ) {
        // M36.5.44：真实提交时刻递增换入代际——作废上一个执行遗留的 refresh Timer（防滞后一轮）。
        swapGeneration++
        // write-safe: UI-only（纯 Swing/只读，无文档写）
        ApplicationManager.getApplication().invokeLater({
            // ① 快照：本次提交时已存在的结果 Tab **名称**（判定"新结果"的基线）。
            // M36.5.22b：实例身份判定失效——DBN 复用同一个 Content 实例并改名/更新
            //（17:25 会话实证：pre-existing=[Result 1]，retry#1 已见 Result 2，但
            // `it !in preExisting` 仍为 false → select=null）。改以 displayName 为键。
            val pre = resultContents(ui)
            val preExistingNames = pre.map { it.displayName }.toSet()
            LOG.info("[M36] result watch started (pre-existing-names=$preExistingNames, ids=${pre.map { System.identityHashCode(it) }})")
            // ② 8s 连接等待监控（从提交时刻起算）。M36.5.44 放宽：原判定 `!newTab || !namespaceOk`
            // 双条件任一为假即报超时——但连接建立慢（首个 SQL，query 在 8s 后才完成）时
            // newTab=false 且 namespace 可能暂空，**误报"执行超时"**（用户截图红字实证）。
            // 改为：只要 8s 后**出现了新结果 Tab**（resultContents 有非 Output 且名不在快照）即视为
            // 成功；仅当"既无新 Tab 又无有效 namespace"（连接确实没建起来）才提示凭据/驱动。
            if (ui != null && onError != null) {
                val uiForWatch = ui
                val hintOnError = onError
                val consoleForWatch = console
                val watch = javax.swing.Timer(8000, null)
                watch.addActionListener {
                    watch.stop()
                    val newTab = resultContents(uiForWatch).any { it.displayName.isNotBlank() && it.displayName !in preExistingNames }
                    val namespaceOk = runCatching {
                        val ns = consoleForWatch.javaClass
                            .getMethod("getCurrentNamespace").invoke(consoleForWatch)
                        ns != null && ns.toString().isNotBlank()
                    }.getOrDefault(true)
                    if (!newTab && !namespaceOk) {
                        // T-ExecSilentNoop：提交后 8s 回执——namespace 都出来了说明连接**已建立**、
                        // 探针显示未提交（freshModel 回落）则归因"未提交"而非凭据；否则才落到
                        // 凭据/驱动。与 40 拍窗口共用 executionHintReported 去重，同次执行只报一次。
                        if (!executionHintReported) {
                            executionHintReported = true
                            val hint = if (demotedOverride) {
                                MpSqlVisualizerBundle.message("execute.notSubmitted.hint", dsName ?: "")
                            } else {
                                MpSqlVisualizerBundle.message("execute.credential.hint")
                            }
                            LOG.info("[M36] no NEW result tab AND no namespace within 8s (newTab=$newTab, namespaceOk=$namespaceOk, demotedOverride=$demotedOverride) - hint=${
                                if (demotedOverride) "notSubmitted" else "credential"
                            }")
                            hintOnError.invoke(hint)
                        }
                    } else {
                        LOG.info("[M36] result/layout OK within 8s (newTab=$newTab, namespaceOk=$namespaceOk) - no hint")
                    }
                }
                watch.isRepeats = false
                watch.start()
            }
            // ③ executePrepared 路径：右侧已嵌持久整控台（M36.5.25i 统一 embed）——原位换底
            //    后回调同一实例（面板侧 embedConsole 同实例防抖幂等吸收，身份稳定）。
            //    JSplitPane 操作必须一体在 EDT（M36.5.11 教训）。
            //    M36.5.44：换入**实时结果组件**（ui.getComponent()）而非 preparedResultComponent
            //    （prepare 时捕获的占位，从未更新——用它会在第二次执行时把"首个 SQL 已换入的
            //    真实结果"覆盖回占位，正是"滞后一轮"的直接来源）。拿不到实时组件则跳过，
            //    交由 refreshResultsComponent 兜底。
            if (onResultsReady != null) {
                val top = preparedTopBlock
                val liveResult = runCatching {
                    ui?.javaClass?.getMethod("getComponent")?.invoke(ui) as? javax.swing.JComponent
                }.getOrNull() ?: preparedResultComponent
                if (top != null && liveResult != null) {
                    // M36.5.43：swap 助手强制换入且不返回 null——结果区从 DBN 宿主摘入持久 SplitPane。
                    val full = swapResultIntoPersistentConsole(preparedFullComponent, liveResult, top, LOG)
                    onResultsReady.invoke(full)
                    clearConsolePrompt(console, LOG)
                }
            }
            // ④ 结果 Tab 监听（只认新 Tab 名）
            if (ui != null) {
                startResultTabRetry(project, ui, onError, preExistingNames, dsName = dsName, demotedOverride = demotedOverride, sqlText = sqlText, onErrorRich = onErrorRich)
            }
        }, com.intellij.openapi.application.ModalityState.any())
    }
    /** 读取 RunnerLayoutUi 当前全部结果内容（排除 Output/输出）。 */
    internal fun resultContents(ui: Any?): List<com.intellij.ui.content.Content> {
        if (ui == null) return emptyList()
        val all = runCatching {
            ui.javaClass.getMethod("getContents").invoke(ui) as? Array<com.intellij.ui.content.Content>
        }.getOrNull() ?: return emptyList()
        return all.filter { !isOutputContent(it) }
    }

    private fun isOutputContent(c: com.intellij.ui.content.Content): Boolean =
        c.displayName.equals("Output", ignoreCase = true) ||
            c.displayName.equals("输出", ignoreCase = true)

    /**
     * M36.5.22b：结果 Tab 选择判定（纯函数，[startResultTabRetry] 每 tick 调用；抽出为
     * 可测函数——DBN「复用 Content 实例改名」行为可在单测中用动态代理精确复现）。
     *
     * 判定规则（按优先级）：
     *  1. displayName **不在** [preExistingNames]（提交时刻快照）中的第一个结果 Tab → 新结果，
     *     **立即选中**。注意必须按名称而非实例身份——DBN 复用同一 Content 实例并改名
     *     （17:25 沙盒实证：pre-existing=[Result 1]，retry#1 已见 Result 2 但实例身份
     *     过滤判定为旧 Tab → select=null → 滞后一轮）。
     *  2. 只有旧名称的 Tab 时：attempts >= [REUSE_ACCEPT_TICKS]（M36.5.44 降为 4 拍 1.6s）
     *     才接受复用（防"任意见到旧 Tab 就换入"把上一轮结果顶进面板——M36.5.22 真因；
     *     [swapGeneration] 已保证 watch 生命周期仅本次执行，同名 Tab 即本次结果，可快速接受）。
     *  3. 无结果 Tab（仅 Output/空）→ null（继续等待）。
     *
     * T-ExplainFirstNative：**空名占位不算结果**——DBN 首次执行先 add 空名 Content 再异步
     * 改名/建网格（262 真机 21:06:45 实证 `contentAdded: `），空名 ∉ 快照集合恒真会把占位当
     * 新结果第 1 拍选中并强制换入（网格尚未构建），事后无人补换入 → 结果滞留原生控制台。
     */
    internal fun pickResultTab(
        contents: Array<com.intellij.ui.content.Content>,
        preExistingNames: Set<String>,
        attempts: Int,
    ): com.intellij.ui.content.Content? {
        val newOne = contents.firstOrNull {
            !isOutputContent(it) && it.displayName.isNotBlank() && it.displayName !in preExistingNames
        }
        if (newOne != null) return newOne
        val staleOne = contents.firstOrNull { !isOutputContent(it) && it.displayName.isNotBlank() }
        return if (staleOne != null && attempts >= REUSE_ACCEPT_TICKS) staleOne else null
    }

    /**
     * M36.5.44：旧 Tab 复用接受的 tick 数（400ms×N）。
     * 原 15 拍（6s）对"重复执行同一 SQL、DBN 复用同名 Content"场景太慢——用户看到的上一次结果
     * 会滞留 6 秒（沙盒 16:53 实证：执行2/执行3 `retry#1..#14` 全 select=null，retry#15 才换入，
     * 期间状态栏恒"执行中..."）。降为 4 拍（1.6s）：既不卡 6 秒，又保留了"DBN 复用同名 Tab 时
     * 内容更新需要时间"的合理缓冲（2 拍可能过早换入旧内容——提交后结果渲染未必即刻完成）。
     * 同名 Tab 由 [swapGeneration] 保证属本次执行，最终都会换入；阈值只决定"多快接受复用"。
     */
    internal const val REUSE_ACCEPT_TICKS = 4

    /**
     * 结果 Tab 激活重试（EDT Timer 400ms 非阻塞；结果 Tab 异步创建）。
     * M36.5.3 dumb-aware：dumb mode（索引）期间查询提交可能被推迟（沙盒实证：方言 push-all
     * 与连接建立延至索引完成）——索引中把重试窗口放宽到 40 次（16s）；
     * 超时后按状态给准确提示：索引中 → 索引提示（execute.indexing.hint），
     * 否则 → 检查被遮挡的密码/驱动弹窗（execute.credential.hint）。
     *
     * M36.5.22b：`preExistingNames` = 提交时刻已存在的结果 Tab **名称**快照；**只认本次提交后
     * 新出现的名称**（此前按 Content 实例身份判定，DBN 复用同一个 Content 实例并改名/更新
     * 导致新 Tab 被误判为旧 Tab）。
     * M36.5.44：接受旧 Tab 复用的阈值降为 [REUSE_ACCEPT_TICKS]（2 拍 0.8s）；且回调校验
     * `generation != swapGeneration` 即停（新执行覆盖旧 watch，防空转造成"执行中..."卡顿）。
     */
    /**
     * T-DdlPostInvalidate（O-17）：执行收尾兜底失效——[MetadataInvalidator.invalidateAfterExecution]
     * 内部先 mayChangeMetadata 预判（命中才动作）且动作幂等，入口+收尾双调全程失效次数有界（1+1）。
     * **成功与失败/超时收尾都调**（DBX：失败的 DDL 可能已隐式提交——Oracle），把「过程内 DDL/
     * 预判漏判/中途结构变化」三类窗口关死；动作异常只记日志不吞收尾结果。
     */
    private fun runPostExecutionInvalidate(sqlText: String?, LOG: com.intellij.openapi.diagnostic.Logger) {
        if (sqlText == null) return
        runCatching { MetadataInvalidator.invalidateAfterExecution(sqlText) }
            .onFailure { LOG.warn("[T-DdlPostInvalidate] post-execution invalidation failed", it) }
    }

    private fun startResultTabRetry(
        project: Project,
        ui: Any,
        onError: ((String) -> Unit)? = null,
        preExistingNames: Set<String> = emptySet(),
        generation: Int = swapGeneration,
        dsName: String? = null,
        demotedOverride: Boolean = false,
        sqlText: String? = null,
        onErrorRich: ((ExecutionErrorInfo) -> Unit)? = null,
    ) {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        val selectRetry = object : java.awt.event.ActionListener {
            var attempts = 0
            val timer = javax.swing.Timer(400, null)
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                attempts++
                // M36.5.44：代际不匹配 → 新执行已覆盖本 watch，立即停止（否则空转到阈值，
                // 期间用户看到上一次结果 + "执行中..."——滞后一轮的另一来源）。
                if (generation != swapGeneration) {
                    timer.stop()
                    LOG.debug("[M36] result tab retry cancelled (generation $generation != ${swapGeneration})")
                    return
                }
                // M36.5.56：每拍开头统一早检错误（**不再只放 else 分支**）——DBN 查询失败写
                // console history 后应立即上报，无论结果 Tab 是否出现。原实现把早检放 else
                //（result==null）分支，第一次执行结果 Tab 出现（复用）时走 if 分支不检查，
                // 错误要等第二次执行（result=null 走 else）才报——用户"执行两次才出现错误"。
                // extractConsoleError 用 ERROR_SIGNATURES+isSqlLine 精确匹配，不误判正常 SQL。
                extractConsoleError(sharedConsole ?: preparedConsole, LOG)
                    ?.takeIf { it != lastReportedError }?.let { err ->
                        timer.stop()
                        runPostExecutionInvalidate(sqlText, LOG)
                        lastReportedError = err
                        LOG.info("[M36] early console error detected (attempts=$attempts): ${err.take(120)}")
                        // write-safe: UI-only（纯 Swing/只读，无文档写）
                        // T-ELU-01/03：DBN 写进 console history 的**语句级**错误也走富通道——
                        // 这是「正常 SQL 执行失败」的主路径（提交链不抛异常，错误只落在 history），
                        // 原实现只投旧 onError ⇒ 定位按钮永不出现。cause 传 null（文本里已有
                        // 位置 marker，SQLState/errorCode 由驱动器在 history 文本中给出）。
                        ApplicationManager.getApplication().invokeLater({
                            deliverError(onErrorRich, onError, null, err, sqlText?.length)
                        }, com.intellij.openapi.application.ModalityState.any())
                        return
                    }
                val contents = runCatching {
                    ui.javaClass.getMethod("getContents").invoke(ui) as? Array<com.intellij.ui.content.Content>
                }.getOrNull() ?: emptyArray()
                val result = pickResultTab(contents, preExistingNames, attempts)
                if (result != null && result.displayName in preExistingNames) {
                    LOG.info("[M36] result tab reused (name=${result.displayName}) - accepting existing tab")
                }
                // T-PerfSweep2-01：列表构建仅喂 LOG.debug——debug 关闭时不再逐拍白建两份列表
                //（400ms 定时器最长 100 拍，此前每拍 2 次 map 空转）。
                if (LOG.isDebugEnabled) {
                    val all = contents.map { it.displayName }
                    val ids = contents.map { System.identityHashCode(it) }
                    LOG.debug("[M36] retry#$attempts contents=$all, ids=$ids, select=${result?.displayName}, preExisting=$preExistingNames")
                }
                if (result != null) {
                    timer.stop()
                    runPostExecutionInvalidate(sqlText, LOG)
                    // M36.5.48 勘误：此处**不**调用 extractConsoleError——结果 Tab 复用成功时
                    // console history 含正常 SQL 文本（如 "WHERE t.id = 1"），会被误判为错误并
                    // onError（日志实证 `extracted console error: WHERE t.id = 1`，误报 + 一闪而过）。
                    // 真实失败原因只在"超时无结果"路径提取（那里才是真失败/超时）。
                    runCatching {
                        val select = ui.javaClass.getMethod(
                            "selectAndFocus",
                            com.intellij.ui.content.Content::class.java,
                            Boolean::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                        )
                        select.invoke(ui, result, false, true)
                    }.onFailure { ex ->
                        LOG.warn("[M36] select result tab FAILED: ${ex.message}")
                    }
                    // M36.5.8：执行后结果区组件被 RunnerLayoutUi 重建，旧引用显示停留在上一轮
                    //（用户实测：新 SQL 执行后一直加载中、重开面板才见新结果）——重取组件换入。
                    // M36.5.13：forceSwapIn=true——结果 Tab 出现即有新结果，强制换入一次
                    //（组件实例可能恒定，"实例变化才换入"导致新结果不显示）
                    refreshResultsComponent(ui, LOG, forceSwapIn = true, generation = generation, onError = onError, onErrorRich = onErrorRich, sqlText = sqlText)
                } else {
                    // M36.5.52：每拍错误早检——DBN 查询失败（Unknown column/syntax error 等）
                    // 会立即写入 console history，无需等 40 拍（16s）超时；发现真实错误立即上报
                    // M36.5.3：索引中放宽窗口（查询提交可能被 dumb mode 推迟）；连接等待期由
                    // 提交前的 8s 监控覆盖（M36.5.15），此处窗口保持原语义。
                    val dumb = runCatching { com.intellij.openapi.project.DumbService.isDumb(project) }.getOrDefault(false)
                    // M36.5.22：窗口统一放宽到 40 拍（16s）——监听已在真实提交后启动，且需覆盖
                    // 15 拍（6s）的 "DBN 复用旧 Tab" 回退判定；连接等待另有 8s 监控负责提示。
                    val limit = if (dumb) 100 else 40
                    if (attempts >= limit) {
                        timer.stop()
                        runPostExecutionInvalidate(sqlText, LOG)
                        LOG.info("[M36] result tab not appeared after $attempts attempts (dumb=$dumb)")
                        // M36.5.14：提示改走 onError（**面板内显示**，resultPanel.showError）——
                        // 此前 Messages.showWarningDialog 弹窗也会被模态预览窗口遮挡（用户实测
                        // "关闭窗口才出现提示"）；面板内红字必然可见，且不叠加新模态。
                        // ModalityState.any() 保证模态弹窗（如密码框）期间提示照常落地。
                        // M36.5.48：优先取 console 真实错误文本（DBN 查询失败写入 history）；
                        // 读不到才 fallback 笼统 hint（超时/索引）。
                        val realError = extractConsoleError(sharedConsole ?: preparedConsole, LOG)
                        // T-CEC-03：判据认不出的失败也要有下文——控制台确有新增输出（去掉本次 SQL
                        // 回显后的残余）时如实呈现，而不是把语法错归因成密码/驱动（真机 P15 实证）。
                        val residue = if (realError == null) {
                            extractConsoleResidue(sharedConsole ?: preparedConsole, sqlText, LOG)
                        } else {
                            null
                        }
                        // T-ExecSilentNoop：40 拍超时窗口与 8s 监控共用 executionHintReported 去重。
                        // 真实错误优先（DBN 已把失败写进 history 才叫真失败/真超时）；无错误痕迹时
                        // 不再一律归因凭据——先看本次是否探到"未提交"（freshModel 回落，DBN 侧静默
                        // 拒绝），按情况给 notSubmitted / timeout / indexing 三种真实提示。
                        if (realError == null && !executionHintReported) {
                            executionHintReported = true
                            val message = if (residue != null) {
                                // 如实标注：这是控制台新增输出，**不是**已识别的错误形态（不猜原因）
                                "${MpSqlVisualizerBundle.message("execute.error.unknownShape")}\n$residue"
                            } else {
                                when {
                                    dumb -> MpSqlVisualizerBundle.message("execute.indexing.hint")
                                    demotedOverride -> MpSqlVisualizerBundle.message("execute.notSubmitted.hint", dsName ?: "")
                                    else -> MpSqlVisualizerBundle.message("execute.timeout.hint")
                                }
                            }
                            LOG.info(
                                "[M36] result tab not appeared, no console error (dumb=$dumb, " +
                                    "demoted=$demotedOverride, residue=${residue != null}) - hint via 40-tick window",
                            )
                            // write-safe: UI-only（纯 Swing/只读，无文档写）
                            ApplicationManager.getApplication().invokeLater({
                                onError?.invoke(message)
                            }, com.intellij.openapi.application.ModalityState.any())
                        } else if (realError != null) {
                            // write-safe: UI-only（纯 Swing/只读，无文档写）
                            // T-ELU-01/03：语句级错误同早检路径，走富通道收口
                            ApplicationManager.getApplication().invokeLater({
                                deliverError(onErrorRich, onError, null, realError, sqlText?.length)
                            }, com.intellij.openapi.application.ModalityState.any())
                        }
                    }
                }
            }
            init { timer.addActionListener(this) }
        }
        selectRetry.timer.start()
    }

    /** M36.5：读取 last-used 数据源名（prepareConsole 静默复用；异常兜底 null）。 */
    private fun lastRunnerName(project: Project): String? = lastUsedRunner(project).displayName

    /** M36.5：记录 last-used 数据源名。 */
    private fun rememberRunnerName(project: Project, name: String) {
        rememberRunner(project, name, null)
    }

    /**
     * T-SI-02：last-used 记忆（UUID 身份 + 显示名，双字段）。
     * [dataSourceId] 来自 LocalDataSource.getUniqueId（[DataSourceSettings] 既有反射封装）。
     */
    internal data class LastUsedMemory(val dataSourceId: String?, val displayName: String?)

    /**
     * T-SI-02：记忆命中解析结果。[index] 命中 runner 下标；[ambiguous] 表示「记忆在、但同名
     * 多条无法唯一对应」——调用方据此清记忆走弹窗（不猜首条）。
     */
    internal data class LastUsedResolution(val index: Int?, val ambiguous: Boolean)

    /**
     * T-SI-02：last-used 记忆命中判定（纯函数，默认 test 通道可覆盖）。
     *
     * 命中顺序（UUID 优先，名字回退，皆不唯一即弃）：
     *  1. UUID 精确命中——[dataSourceNameById] 取该 UUID 当前的数据源名（数据源被改名也认，
     *     这是 UUID 相对显示名的增益），再按该名在 runner 列表里唯一匹配；
     *  2. 名字命中且唯一——旧键（显示名）兼容路径，升级用户无感；
     *  3. 其余（未命中 / 同名多条）→ 弃记忆，调用方走弹窗重新选择。
     *
     * [dataSourceNameById] 返回 null 表示该 UUID 的数据源已不存在——UUID 分量失效，退回名字判定。
     */
    internal fun resolveLastUsedRunnerIndex(
        memory: LastUsedMemory,
        runnerNames: List<String>,
        dataSourceNameById: (String) -> String?,
    ): LastUsedResolution {
        val id = memory.dataSourceId?.takeIf { it.isNotBlank() }
        if (id != null) {
            val dsName = runCatching { dataSourceNameById(id) }.getOrNull()
            if (!dsName.isNullOrBlank()) {
                val hits = runnerNames.indices.filter { runnerNames[it] == dsName }
                if (hits.size == 1) return LastUsedResolution(hits.first(), false)
                if (hits.size > 1) return LastUsedResolution(null, true)
            }
        }
        val name = memory.displayName?.takeIf { it.isNotBlank() }
        if (name != null) {
            val hits = runnerNames.indices.filter { runnerNames[it] == name }
            if (hits.size == 1) return LastUsedResolution(hits.first(), false)
            if (hits.size > 1) return LastUsedResolution(null, true)
        }
        return LastUsedResolution(null, false)
    }

    /** T-SI-02：读 last-used 记忆双字段（异常兜底全空）。 */
    private fun lastUsedRunner(project: Project): LastUsedMemory = runCatching {
        val pc = com.intellij.ide.util.PropertiesComponent.getInstance(project)
        LastUsedMemory(pc.getValue(LAST_RUNNER_ID_KEY), pc.getValue(LAST_RUNNER_KEY))
    }.getOrDefault(LastUsedMemory(null, null))

    /**
     * T-SI-02：写 last-used 记忆。[dataSourceId] 取不到（弹窗路径尚无控制台）时**清掉身份键**
     * ——留着上一轮的 UUID 会指认另一个数据源（身份说谎比没有身份更糟）。
     */
    private fun rememberRunner(project: Project, name: String, dataSourceId: String?) {
        runCatching {
            val pc = com.intellij.ide.util.PropertiesComponent.getInstance(project)
            pc.setValue(LAST_RUNNER_KEY, name)
            if (dataSourceId.isNullOrBlank()) pc.unsetValue(LAST_RUNNER_ID_KEY)
            else pc.setValue(LAST_RUNNER_ID_KEY, dataSourceId)
        }
    }

    /** T-SI-02：清 last-used 记忆（两个键一起清，避免半截身份残留）。 */
    private fun forgetLastRunner(project: Project) {
        runCatching {
            val pc = com.intellij.ide.util.PropertiesComponent.getInstance(project)
            pc.unsetValue(LAST_RUNNER_KEY)
            pc.unsetValue(LAST_RUNNER_ID_KEY)
        }
    }

    /** T-SI-02：从已建控制台取数据源 UUID（复用 [DataSourceSettings] 既有反射封装；取不到 null）。 */
    private fun consoleDataSourceId(console: Any?): String? = runCatching {
        val ds = console?.javaClass?.getMethod("getDataSource")?.invoke(console) ?: return null
        DataSourceSettings.getUniqueId(ds)
    }.getOrNull()

    /**
     * 反射调用 DatabaseRunners.getAttachDataSourceRunners 获取原生 Runner 列表。
     * [sessionConsumer] 在选中 runner 执行后收到 DatabaseSession。
     */
    private fun getAttachDataSourceRunners(psiFile: PsiFile, sql: String, sessionConsumer: com.intellij.util.Consumer<Any>): List<Any> {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        return runCatching {
            val clazz = Class.forName(RUNNERS_CLASS)
            val method = clazz.getMethod(
                "getAttachDataSourceRunners",
                PsiFile::class.java,
                String::class.java,
                com.intellij.util.Consumer::class.java,
            )
            val result = method.invoke(null, psiFile, sql, sessionConsumer)
            (result as? List<*>)?.filterNotNull() ?: emptyList()
        }.onFailure { e ->
            LOG.warn("[M36] getAttachDataSourceRunners reflection FAILED: $e", e)
        }.getOrDefault(emptyList())
    }

    /** 反射获取 Runner 显示名称（数据源名，如 `jpa_demo@localhost`）。 */
    private fun getRunnerName(runner: Any): String = runCatching {
        val method = runner.javaClass.getMethod("getDisplayName")
        method.isAccessible = true
        method.invoke(runner) as? String
    }.getOrNull() ?: "Unknown"

    /** 反射获取 Runner 图标（数据源 DB 类型图标）。 */
    private fun getRunnerIcon(runner: Any): Icon? = runCatching {
        val method = runner.javaClass.getMethod("getIcon")
        method.isAccessible = true
        method.invoke(runner) as? Icon
    }.getOrNull()

    /** 反射调用 Runner.shouldBeChosenIfAlone（单数据源时原生是否免选直接执行）。 */
    private fun shouldBeChosenIfAlone(runner: Any): Boolean = runCatching {
        val method = runner.javaClass.getMethod("shouldBeChosenIfAlone")
        method.isAccessible = true
        method.invoke(runner) as? Boolean
    }.getOrNull() ?: false

    /** 反射调用 Runner.run：建 session → consumer 回调续接执行。失败（常见：驱动未下载）→ 面板行内回调 + 按 silent 决定是否模态引导。 */
    // T-PreviewExecDs-02 F4：显式传 project——失败路径曾用 openProjects.firstOrNull() 清 last-used
    // /弹错误框，多项目同开时清的是「第一个打开项目」的记忆（错项目 bug，见开发计划 §2.2）。
    private fun runRunner(project: Project, runner: Any, silent: Boolean = false, onError: ((String) -> Unit)? = null) {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        runCatching {
            val method = runner.javaClass.getMethod("run")
            method.isAccessible = true
            method.invoke(runner)
            LOG.info("[M36] runRunner invoked OK")
        }.onFailure { e ->
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            LOG.warn("[M36] runRunner FAILED: ${cause.javaClass.simpleName}: ${cause.message}", cause)
            // T-DsCover：预览默认准备路径静默失败（被动展示不应弹模态打断）；
            // 并清 last-used 记忆，避免驱动损坏的数据源每次预览都白失败一次。
            // T-Perf-01：失败同时回调 onError（面板行内红字 + 「执行中」态恢复），静默与非静默路径都回调。
            val failMsg = MpSqlVisualizerBundle.message("execute.datasource.failed", cause.message ?: cause.toString())
            onError?.invoke(failMsg)
            if (silent) {
                // T-SI-02：两个键一起清（只清旧键会留下指认另一个数据源的 UUID 身份）
                forgetLastRunner(project)
                return
            }
            // 驱动缺失/连接失败等：提示用户到数据源设置解决（原生 Download Driver 弹窗由 Database 插件触发）
            // T-UiNative T-UN-02：改 ERROR 轻通知（原模态错误框——失败仍醒目，但不打断）；
            // 测试模式跳过 + runCatching 兜底（对齐 CopyExecutableSqlAction.showToast 约定）
            if (!com.intellij.openapi.application.ApplicationManager.getApplication().isUnitTestMode) {
                runCatching {
                    com.intellij.notification.Notification(
                        NOTIFICATION_GROUP_ID,
                        MpSqlVisualizerBundle.message("execute.title"),
                        failMsg,
                        com.intellij.notification.NotificationType.ERROR,
                    ).notify(project)
                }
            }
        }
    }

    /**
     * 弹窗展示 Runner 列表（图标 + 数据源名称，原生 BaseListPopupStep 单击即选）。
     */
    private fun showRunnerPopup(project: Project, runners: List<Any>, callback: (Any?) -> Unit) {
        val items = runners.map { RunnerItem(it, getRunnerName(it), getRunnerIcon(it)) }
        // T-PreviewExecDs-02 F5：多项目同开时标题带当前项目名——runner 枚举严格按当前项目
        // facade（F1 修复后），标题注明归属避免「这是哪来的数据源」困惑；单项目不加噪音。
        val multiProject = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.size > 1
        val title = if (multiProject) {
            MpSqlVisualizerBundle.message("execute.datasource.select.in", project.name)
        } else {
            MpSqlVisualizerBundle.message("execute.datasource.select")
        }
        val step = object : com.intellij.openapi.ui.popup.util.BaseListPopupStep<RunnerItem>(
            title,
            items,
        ) {
            override fun getTextFor(value: RunnerItem): String = value.name
            override fun getIconFor(value: RunnerItem): Icon? = value.icon
            override fun onChosen(selectedValue: RunnerItem?, finalChoice: Boolean): com.intellij.openapi.ui.popup.PopupStep<*>? {
                com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
                    .info("[M36] popup onChosen: ${selectedValue?.name}")
                callback(selectedValue?.runner)
                return com.intellij.openapi.ui.popup.PopupStep.FINAL_CHOICE
            }
        }
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createListPopup(step)
            .showInFocusCenter()
    }

    /** 弹窗列表项（Runner 包装）。 */
    private data class RunnerItem(val runner: Any, val name: String, val icon: Icon?)

    /**
     * 创建临时 .sql 文件（含 SQL），返回 (VirtualFile, PsiFile)。
     * VF 作为控制台挂载点（attachConsole），PsiFile 作为 Runner 上下文。
     *
     * M36.5.2：PSI 查找包 ReadAction——EDT 直调 findFile 触发 LanguageSubstitutors 慢操作
     * 硬抛 SEVERE（沙盒日志实证；M36.5 默认展示使 prepareConsole 在 showResult 的 EDT 上调用，
     * 不再仅限用户点执行的路径）。
     *
     * M36.5.3：方言预映射（执行"静默失败"根因修复）——沙盒日志实锤：临时文件 PSI 创建时的
     * 方言替换（GenericSQL→MySQL）依赖 SqlDialectPusher push-all，**dumb mode（索引）下被推迟
     * 到索引完成**；期间 executeQueries 按 GenericSQL PSI 解析语句静默不执行（无结果 Tab、
     * 连接尝试都未发起），重试超时后误报"密码过期/驱动未下载"（两次失败 run 均在索引窗口内，
     * 两次成功 run 方言在执行前已解析）。修复：findFile 前显式 setMapping（IDE 自身
     * ChangeDialectAction.setDialectImpl0 同路径：SqlDialectMappings.setMapping(file, dialect)），
     * 替换确定性命中映射、不再依赖 pusher/ProjectFileIndex（顺带消除映射查找途中的
     * ProjectFileIndex.isInContent 慢操作告警）。
     *
     * T-ExecCopyFix：失败可见化——原实现整段 runCatching{}.getOrNull() 吞异常零日志，
     * 用户真机 "Failed to create temporary SQL file" 无法归因。现分级记录每个 return null
     * 站点（stage 标识 + ioFile 路径/exists 状态），异常级 warn 带堆栈，并向调用方回传
     * 阶段原因（[lastTempFileFailure]）供错误文案携带。
     *
     * T-ExecCopyFix F2b：冷路径 refresh 段换 WriteIntentReadAction.compute（Computable 单类型
     * 实参，243~262 交集——T-Wia262）——修 262 真机最可能根因：refreshAndFindFileByIoFile 同步
     * VFS refresh 要求 write-safe 语境，262 平台 RefreshSessionImpl.launch 对 EDT-ReadAction
     * 硬抛 "Access is allowed from write thread only"（T-PreviewWiLock 同 API 先例）。WIRA 隐含
     * 读权限，refresh 后续链不受影响。
     *
     * T-ExecSlowOps F2：**本方法自此退化为兜底路径**——预览链成功后
     * [prewarmExecTempFile] 已在后台线程完成 写盘→refresh→方言映射→findFile→填缓存槽，
     * 弹窗打开后这里 [consumeCachedTempPsiFile] 必命中，EDT 上不再执行 refresh/findFile
     * （262 真机 "Slow operations are prohibited on EDT" SEVERE 本体，WorkspaceFileIndex
     * .ensureIsUpToDate）。冷路径仅存于无预览链触达的显式执行首跑等场景。原"后台化经评估
     * 否决"（BGT 无法安全做 EDT-only 的 setMapping）已被 [DialectPreMapping.ensureMapped]
     * 两段式（BGT 读探测 + invokeLater 派 EDT + 有界 latch 等待，绝不 invokeAndWait）解决。
     */
    @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
    private fun createTempPsiFile(project: Project, sql: String): Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>? {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        // T-ExecCopyFix F2a：失败阶段记录（onError 文案携带，见 chooseAndPrepare）
        fun fail(stage: String, detail: String = ""): Nothing? {
            lastTempFileFailure = "$stage${if (detail.isNotEmpty()) ": $detail" else ""}"
            LOG.warn("[M36] createTempPsiFile FAILED at $stage${if (detail.isNotEmpty()) " ($detail)" else ""}")
            return null
        }
        lastTempFileFailure = null
        // M36.5.12：临时文件改存项目内 `.idea/sql-visualizer/exec/`（F-44-4：目录随插件更名）
        //（参考 sql-history 保存位置），不再用系统 Temp——Temp 文件不在数据源关联范围，
        // 导致 IDE 报 "Unable to resolve table"（M36.5.11 问题一，日志实证挂载点在
        // file:///C:/Users/USER/AppData/Local/Temp/）。项目内文件可被 VFS 追踪、可被
        // Database 插件解析。文件名稳定 mpsql-exec.sql（每次覆盖，不膨胀）。
        val ioFile = try {
            projectExecFile(project, sql)
        } catch (e: Throwable) {
            if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传
            // projectExecFile 尾段 FileUtil.writeToFile 可抛 IOException——原实现靠外层
            // runCatching 吞掉，拆除后这里必须自接，否则异常直冲 EDT
            return fail("writeFile", "${e.javaClass.simpleName}: ${e.message}")
        } ?: return fail("writeFile", "projectExecFile null (basePath=${project.basePath})")
        // T-PreviewFlash：EDT 同步 VFS refresh + PsiManager.findFile 已被跨点击缓存吸收——
        // mpsql-exec.sql 路径稳定，vf 仍有效时直接复用配对缓存，把
        // WorkspaceFileIndex.ensureIsUpToDate 的 SEVERE 断言移出每次预览的热路径。
        consumeCachedTempPsiFile(project)?.let { return it }
        // T-ExecDocSync2 F4：refresh 前强制收敛——写点之后、refresh 之前若被用户键入/其他写方
        // 插入（doc modified-unsaved + 磁盘≠文档），refresh 恰会命中 MemoryDiskConflictResolver
        // 弹窗条件；findFileByIoFile（与 projectExecFile 内裸调同形态）命中即过一遍分叉探测 +
        // EDT 派发 doc 写收敛，正常恒 no-op。详见 prewarmExecTempFileInner 同款注释。
        try {
            LocalFileSystem.getInstance().findFileByIoFile(ioFile)?.let { vf0 ->
                syncExecVfContentIfNeeded(project, vf0, sql)
            }
        } catch (e: Throwable) {
            if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传
            return fail("preRefreshConverge", "${e.javaClass.simpleName}: ${e.message}")
        }
        // T-ExecCopyFix F2b：refresh 段包 WriteIntentReadAction（详见方法 KDoc）；
        // mapDialect 与 findFile 维持原线程原语义（setMapping 须 EDT；findFile 沿用
        // ReadAction.compute 既有包装，与 T-M36.6-03 前行为一致）。
        val vf = try {
            com.intellij.openapi.application.WriteIntentReadAction
                .compute<com.intellij.openapi.vfs.VirtualFile?> {
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
                }
        } catch (e: Throwable) {
            if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传
            return fail("refreshVfs", "${e.javaClass.simpleName}: ${e.message}")
        } ?: return fail("refreshVfs", "VFS not visible after refresh (${ioFile.path}, exists=${ioFile.exists()})")
        // T-EC-04：VFS 缺条目裸写路径的防御收口——正常恒 no-op，异常残留在此收敛（含落盘）
        try {
            syncExecVfContentIfNeeded(project, vf, sql)
        } catch (e: Throwable) {
            if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传
            return fail("syncVfContent", "${e.javaClass.simpleName}: ${e.message}")
        }
        mapDialectBeforePsi(project, vf, LOG)
        val psi = try {
            ReadAction.compute<PsiFile?, Throwable> {
                PsiManager.getInstance(project).findFile(vf)
            }
        } catch (e: Throwable) {
            if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传
            return fail("findPsi", "${e.javaClass.simpleName}: ${e.message}")
        } ?: return fail("findPsi", "PsiManager.findFile null (${vf.name})")
        // T-PerfSweep2-04：写点统一走 markCachedTempPsiFile（挂一次性项目关闭钩子）
        markCachedTempPsiFile(project, vf, psi)
        return vf to psi
    }

    /**
     * T-PreviewExecDs-02 F1：消费 exec 临时文件 vf+psi 缓存——可用（归属当前项目且 vf 有效）
     * 返回配对，否则清缓存返回 null（走冷路径重建）。抽 internal 供跨项目隔离测试直测。
     * 跨项目泄漏实锤见开发计划 §2.2：旧实现只查 vf.isValid，在 B 项目执行时返回 A 项目的
     * vf+psi → runner 按 A 项目 facade 枚举数据源 → 弹窗显示另一项目数据源 → console 因
     * facade 找不到该数据源被 getActiveConsoles 清扫 → "parent has already been disposed"。
     */
    internal fun consumeCachedTempPsiFile(project: Project): Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>? {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        return cachedTempPsiFile?.let { (vf, psi) ->
            if (isCacheUsable(project, vf)) {
                LOG.info("[M36] exec psi cache hit (no VFS refresh): ${vf.name}")
                vf to psi
            } else {
                // 跨项目/失效命中——弃缓存，禁止把别项目的 vf+psi 当当前项目用
                if (cachedTempPsiProjectName != null && cachedTempPsiProjectName != project.name) {
                    LOG.warn("[M36] exec psi cache REJECTED across project (cached=${cachedTempPsiProjectName}, current=${project.name}), rebuild")
                }
                cachedTempPsiFile = null
                cachedTempPsiProjectName = null
                null
            }
        }
    }

    /**
     * T-PreviewExecDs-02 F1：exec 临时文件 vf+psi 缓存是否对当前项目可用——既要 vf 有效，
     * 也要 vf 归属当前项目（basePath 前缀）。跨项目泄漏根因修复的判定纯函数（表驱动单测）。
     */
    internal fun isCacheUsable(project: Project, vf: com.intellij.openapi.vfs.VirtualFile): Boolean {
        if (!vf.isValid) return false
        val base = project.basePath ?: return false
        val nBase = com.intellij.openapi.util.io.FileUtil.toSystemIndependentName(base).trimEnd('/')
        val nPath = com.intellij.openapi.util.io.FileUtil.toSystemIndependentName(vf.path)
        return nPath.startsWith("$nBase/")
    }

    /**
     * T-EC-03：执行文件单写方锁——BGT 预热（prewarmExecTempFileInner）与 EDT 冷路径
     * （createTempPsiFile）两条链并发写同一 mpsql-exec.sql 的串行化。临界区毫秒级；
     * 两个调用点均在普通线程语境（非 WriteAction 内），持本锁再取平台写锁无环。
     */
    private val execFileWriteLock = Any()

    /**
     * T-EC-02 的裸写守卫（canFallBackToRawWrite）已由 T-ExecDocSync3 整体退役——真机日志实证其
     * 判据不可靠（EDT 忙时 getCachedDocument 恒 null，而排队中的写任务随后会建出 document），
     * 现策略见 [projectExecFile] 内注释：VFS 已有该文件即绝不裸写。
     */

    /**
     * T-MysqlGStrip T-MG-02：提交链总口的 `\G` 剥离决策（[chooseAndPrepare] 新建控制台链与
     * [executeOnConsole] 已备控制台链共用同一入口）。
     *
     * @param dataSourceUrl 控制台数据源 JDBC URL（方言判定输入）；null = 方言不可得 →
     *                      [MysqlClientGSuffix.stripForDialect] 按 MySQL 处理（默认剥离）
     * @return 两态结果——[MysqlClientGSuffix.Result.submitText] 进 exec 文件/控制台文档，
     *         [MysqlClientGSuffix.Result.original] 留给历史链（历史记原文、提交文本剥离）
     *
     * internal 供 headless 用例直测（沙盒默认 test 任务无 Database 插件，
     * chooseAndPrepare/executeOnConsole 在默认 test 任务不可达——ExecTempFileFixTest 同款口径）。
     */
    internal fun stripGSuffixForSubmit(sqlText: String, dataSourceUrl: String?): MysqlClientGSuffix.Result =
        MysqlClientGSuffix.stripForDialect(sqlText, MysqlClientGSuffix.dialectFromUrl(dataSourceUrl))

    /**
     * T-EC-01：document 驱动写 + **同临界区落盘**——`setText → commitDocument → saveDocument`，
     * 磁盘=VFS=内存三方在同一写临界区内收口，不留「已修改未保存」窗口（该窗口内任何绕过
     * document 的磁盘改写都触发 File Cache Conflict 弹窗，弹窗期间 invokeLater 链冻结 →
     * 执行链卡死）；落盘不再依赖 IDE 自动保存追赶。
     *
     * **仅 EDT 调用**（BGT 统一经 [dispatchDocWriteOnEdt] 派发）——T-ExecDocSync 关键发现
     * （javap + 沙盒堆栈双实证）：262 平台文件 document（非 FREE_THREADED）的 setText 包
     * CommandProcessor.executeCommand，BGT 直调硬抛 EDT-only 断言（RuntimeExceptionWithAttachments）。
     * 旧实现 BGT 预热走 document 分支**每次必炸**，runCatching 吞掉后静默掉进裸写兜底——
     * 这正是真机 File Cache Conflict 链的本体（非低概率竞态）。
     *
     * document 未加载（fileDoc == null，即无冲突面）走 setBinaryContent（VFS+磁盘同步）；
     * text 相同但 document 仍 modified（用户改后还原等）时同样 commit+save 收敛。
     * internal 供测试。
     */
    internal fun writeVfTextViaDocument(project: Project, vf: com.intellij.openapi.vfs.VirtualFile, text: String) {
        // T-PreviewFast P4：程序性写入整段开闸——文档变更事件同步派发给自动落盘监听，
        // 闸门开着即跳过（内容由本函数自己落盘，无需 1.5s 后再写一遍）
        ExecDocAutosaveGate.suppressDuring {
            val fileDoc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
            if (fileDoc == null) {
                vf.setBinaryContent(text.toByteArray())
            } else {
                if (fileDoc.text != text) {
                    fileDoc.setText(text)
                }
                // setText 的 write command 结束时自动 commit；显式 commitDocument 双保险
                // （同步后同 tick 内 findFile 的 PSI 立即对齐，M36.5.20 语义保留）
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(fileDoc)
                // T-EC-01 核心：同临界区落盘——磁盘即时与内存一致，document 不滞留 modified 态
                // T-PreviewFast P5：落盘前先判「已修改未保存」——内容本就一致时（连续预览同一
                // SQL 的常见路径）跳过磁盘写，消除每次预览的无效落盘与随之而来的 VFS/索引噪音
                val fdm = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                if (fdm.isDocumentUnsaved(fileDoc)) {
                    fdm.saveDocument(fileDoc)
                }
            }
        }
    }

    /** BGT 派 EDT document 写的有界等待预算（毫秒）——DialectPreMapping 同款取值；EDT 病态冻结时超时放行走守卫降级。 */
    private const val EDT_EXEC_DOC_SYNC_WAIT_MS = 2000L

    /**
     * T-EC-01/T-EC-03 编排：document 写统一派发 EDT——EDT 调用方同步直执行；BGT 两段式
     * invokeLater + 有界 latch 等待（DialectPreMapping.ensureMapped 同款：BGT 等 EDT，
     * EDT 永不反向阻塞，超时自愈不互锁）。失败注入缝 [execWriteActionFailureInjection]
     * 在 [docWriteOnce] 内统一生效（EDT/BGT 两形态等价）。
     *
     * T-PreviewFast P2：`waitBudgetMs <= 0` 时走 **fire-and-forget**（派发后立即返回 true）——
     * 预热是投机性写入，T-ExecDocSync3 取消裸写后超时已无副作用，没必要让 BGT 白等 2 秒。
     *
     * T-FixWriteSafe（T-WriteSafeCtx 留档补口）：两处投递原用 1 参 `invokeLater`，即隐式
     * `ModalityState.defaultModalityState()`。平台源码实证（`openapi/application/modality.kt`：
     * `defaultModalityImpl()` 依次取 `ProgressManager.currentProgressModality` → 线程/协程上下文
     * modality → NON_MODAL）：BGT 常态取到 NON_MODAL（`TransactionGuardImpl:37` 构造期即登记为
     * write-safe，与显式 `nonModal()` 等价），但**在模态进度/带 modality 上下文里会取到该模态**；
     * 若该模态本身是「写不安全进入」的（`enteredModality:188-190` 存的是进入那一刻
     * `myWritingAllowed` 的快照），排队的 runnable 就在该模态事件泵内以 write-unsafe 身份执行 →
     * 改文档即 `Write-unsafe context!`。故两处投递的**写体**统一过 [DocWriteContext] 闸门：
     *  - fire-and-forget：整拍改以 nonModal 投递（`invokeLaterWriteSafe`）——本路径无有界等待，
     *    模态存续期延后一拍正是「模态期间禁改文档」的正确语义，不会撞任何预算；
     *  - 有界等待：**保留默认模态投递**（保证能在该模态事件泵内执行，常态与改前逐字一致），
     *    只把写体过闸（`runWriteSafe`）：常态判为可写 → 就地写 + 同拍 countDown；仅当判为不安全
     *    才转 nonModal 投递并**立即** countDown（ok=false）——不让调用方白等 2 秒预算。延后那一拍
     *    重跑 [docWriteOnce] 是幂等的（内容相同不 setText、已落盘不 saveDocument），且该超时/未完成
     *    路径已由 T-ExecDocSync3 定为无害（不裸写、内容旧一拍由后续同步链收敛）；
     *  - EDT 直调（T-PreviewEdtGuard S2 补口）：原样直跑 [docWriteOnce]，write-unsafe 活动里
     *    （WriteAction 不 confer 写安全资格，T-WriteSafeCtx）即 R33 三验 2 组 Write-unsafe 的本体
     *    ——现同样过闸（runWriteSafe）：可写就地执行（ok=写结果）；不可写转 nonModal 延后一拍
     *    并返回 false（调用方均不消费该返回值，见分支内注释）。
     */
    /** internal 供测试（T-PreviewEdtGuard S2：EDT 直调分支闸门行为直测）。 */
    internal fun dispatchDocWriteOnEdt(
        project: Project,
        vf: com.intellij.openapi.vfs.VirtualFile,
        sql: String,
        waitBudgetMs: Long = EDT_EXEC_DOC_SYNC_WAIT_MS,
    ): Boolean {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            // T-PreviewEdtGuard S2：EDT 直调同样过写安全闸——R33 三验真机日志实证 2 组
            // Write-unsafe context（帧=writeVfTextViaDocument）：write-unsafe EDT 活动（any() 投递 /
            // 「写不安全进入」的模态泵）里 WriteAction.compute 并不 confer 写安全资格（T-WriteSafeCtx
            // 平台源码定例：「写入合法性与是否在 WriteCommandAction 内无关」），commitDocument 的
            // PSI 事件链（PsiDocumentManagerBase 为平台断言点之一）即报 Write-unsafe context!。
            // 本分支是 [writeVfTextViaDocument] 调用面里唯一未过 DocWriteContext 闸的入口
            // （本函数另两形态已闸；471/1699/2776 三处直调各自包 WriteAction，R33 日志无实证指向）。
            // 判为可写 → 就地写（常态行为逐字不变）；判为不安全 → 转 nonModal 延后一拍并返回
            // false（写体 docWriteOnce 幂等：内容相同不 setText、已落盘不 save；现有调用方——
            // gStrip 回写不消费返回值、syncExecVfContentIfNeeded 各调用方亦不消费——延后语义安全，
            // 与下方有界等待路径「判不安全立即交还」同一纪律）。
            var ok = false
            DocWriteContext.runWriteSafe(project, "exec doc sync (edt direct)") {
                ok = docWriteOnce(project, vf, sql)
            }
            return ok
        }
        if (waitBudgetMs <= 0L) {
            // project 即 owner disposable：项目关闭后丢弃该拍（写目标就是项目内临时文件，重跑无意义）
            DocWriteContext.invokeLaterWriteSafe(project, "exec prewarm doc write (fire-and-forget)") {
                runCatching { docWriteOnce(project, vf, sql) }
            }
            LOG.info("[M36] exec doc write dispatched fire-and-forget (T-PreviewFast P2)")
            return true
        }
        val done = java.util.concurrent.CountDownLatch(1)
        var ok = false
        app.invokeLater {
            try {
                DocWriteContext.runWriteSafe(project, "exec doc sync (bounded)") {
                    ok = docWriteOnce(project, vf, sql)
                }
            } finally {
                done.countDown()
            }
        }
        val signaled = runCatching {
            done.await(waitBudgetMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!signaled) {
            LOG.warn("[M36] exec doc write EDT dispatch timeout after ${waitBudgetMs}ms (EDT busy?)")
        }
        return signaled && ok
    }

    /** document 写单次尝试：WriteAction 内 [writeVfTextViaDocument]；失败原因必达日志（旧 getOrDefault 吞 Throwable 的教训）。 */
    private fun docWriteOnce(project: Project, vf: com.intellij.openapi.vfs.VirtualFile, sql: String): Boolean = runCatching {
        if (execWriteActionFailureInjection) {
            error("injected exec WriteAction failure (T-ExecDocSync test seam)")
        }
        @Suppress("DEPRECATION") // T-M52：WriteAction.compute(ThrowableComputable) 243 编译目标仅此重载可用
        com.intellij.openapi.application.WriteAction.compute<Unit, Throwable> {
            writeVfTextViaDocument(project, vf, sql)
        }
        true
    }.onFailure { e ->
        com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
            .warn("[M36] exec doc write FAILED: ${e.javaClass.simpleName}: ${e.message}")
    }.getOrDefault(false)

    /**
     * T-EC-04：VFS 缺条目裸写路径的防御性收口——raw write 后由调用方 refresh 得到 vf，
     * 此处探测 document（getCachedDocument，不主动创建）或 VFS 内容与 sql 的分叉，
     * 命中则经 [dispatchDocWriteOnEdt] 补一次 document 驱动同步（T-EC-01 同款落盘收口，
     * BGT 调用安全——写侧统一派 EDT）。正常流程恒为 no-op（磁盘刚写入 == sql）；防御
     * VFS 陈旧条目/裸写与 refresh 之间的外部改写残留。探测包 ReadAction（BGT 无隐式读）。
     *
     * @return true = 执行了修复同步（正常流程 false）
     */
    internal fun syncExecVfContentIfNeeded(project: Project, vf: com.intellij.openapi.vfs.VirtualFile, sql: String): Boolean {
        val diverged = ReadAction.compute<Boolean, Throwable> {
            val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getCachedDocument(vf)
            (doc != null && doc.text != sql) ||
                vf.inputStream.readBytes().toString(Charsets.UTF_8) != sql
        }
        if (!diverged) return false
        return dispatchDocWriteOnEdt(project, vf, sql)
    }

    // ------------------------------------------------------------------
    // T-ExecDocSync2 F5/F6：执行文档防抖自动落盘 + 冲突探针（第四轮）
    // ------------------------------------------------------------------

    /**
     * exec 文件 VFS 路径匹配（F5 自动落盘 / F6 探针共用过滤器；VFS 路径恒 '/' 分隔）。
     * internal 供表驱动测试。
     */
    internal fun isExecVfPath(path: String): Boolean =
        path.endsWith("/${cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME}/exec/mpsql-exec.sql")

    /** 防抖窗（毫秒）——连击合并，窗内 modified-unsaved 暴露面上界。 */
    private const val EXEC_DOC_AUTOSAVE_DELAY_MS = 1500L

    /** F5 防抖调度器（daemon，不阻 IDE 退出；任务本身极轻——路径过滤后仅换句柄）。 */
    private val execDocAutosaveExecutor =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "mpv-exec-doc-autosave").apply { isDaemon = true }
        }

    /** F5 待执行的防抖任务（新变更到达即顶掉旧任务）。 */
    private val execDocAutosavePending =
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<*>?>(null)

    /**
     * T-PS4-11 快判缓存：上次命中 exec 路径的 document（**弱引用**——防强引用滞留已关闭文档，
     * GC 后 get()==null 自然退回原链探测）。companion 监听是应用级的，IDE 全局每次文档变更
     * 都会进 [scheduleExecDocAutosave]；exec 文档连续变更（用户在控制台键入）是唯一需要跟进
     * 落盘的形态，其余文档每次都白付 getFile + endsWith。identity 比对（===）而非 equals，
     * document 换实例（文件关闭重开）即视为未命中。
     */
    private val lastExecDocRef =
        java.util.concurrent.atomic.AtomicReference<java.lang.ref.WeakReference<com.intellij.openapi.editor.Document>?>(null)

    /**
     * F6 探针已安装项目集（连接随项目 disposable 自动断开，此处只防重复安装）。
     *
     * T-PS5-08：改**弱键集**（`WeakHashMap` 键弱引用：项目唯一强引用消失即可回收）——此前强引用
     * 集把项目对象钉到显式 `remove`（走 [hookProjectClose] 的项目 Disposer，但该函数在
     * `hookProjectsClose` 已登记时会提前 return、不再登记第二次项目级 Disposer，留下不可回收窗口）。
     * 去重/判空语义不变（条目只用于「是否已安装」判定）。
     */
    private val conflictProbeProjects: MutableSet<Project> =
        java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<Project, Boolean>()),
        )

    /** T-PS5-08 测试缝：探针项目集（弱引用可达性断言用；生产链路无调用点）。 */
    internal fun conflictProbeProjectsForTest(): MutableSet<Project> = conflictProbeProjects

    /**
     * F5：应用级 document 变更监听（惰性安装一次）——exec 文档每次变更（用户在控制台手改/
     * 插件 setText）后 1.5s 防抖自动落盘。动机：console 编辑器文档即 exec 文件 document
     * （attachConsole 挂载点），用户手改滞留「已修改未保存」是第四轮冲突弹窗「内存中更改」
     * 一半的唯一插件拦不到的源头；帧失活自动保存最快也要切帧才触发，防抖落盘把暴露面压到
     * 防抖窗内。落盘方向恒 doc→disk（saveDocument），不回读不改写用户内容。
     */
    private fun installExecDocAutosaveListener() {
        // T-PS4-11：改两参重载 addDocumentListener(listener, disposable)，挂 [ExecDocAutosaveHost]
        // （应用级轻服务 = Disposable parent）——此前单参裸装、装后永不卸载，插件动态卸载
        // （DynamicInstallSupport → 平台 unloadPlugin）后监听残活并钉住旧 classloader，正是
        // 动态卸载屏障要杜绝的形态。挂靠定案依据见 [ExecDocAutosaveHost] KDoc。
        ExecDocAutosaveHost.getInstance().ensureListenerInstalled(
            object : com.intellij.openapi.editor.event.DocumentListener {
                override fun documentChanged(e: com.intellij.openapi.editor.event.DocumentEvent) {
                    scheduleExecDocAutosave(e.document)
                }
            },
        )
    }

    private fun scheduleExecDocAutosave(doc: com.intellij.openapi.editor.Document) {
        // T-PreviewFast P4：程序性写入不排自动落盘——闸门开着说明本次变更来自插件写入
        //（写入方自己已落盘），防抖落盘只为用户手改兜底。保持最先判（闸门语义先于快判，
        // 程序性写入高频路径不因快判命中而漏抑制）
        if (ExecDocAutosaveGate.isSuppressed()) return
        // T-PS4-11 快判：上次命中的 document 直接身份比对短路（免 getFile + endsWith）；
        // 未命中走原链探测回退，确认命中后更新缓存。弱引用被 GC 后 get()==null 视为未命中。
        if (lastExecDocRef.get()?.get() !== doc) {
            val vf = runCatching {
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(doc)
            }.getOrNull() ?: return
            if (vf == null || !isExecVfPath(vf.path)) return
            lastExecDocRef.set(java.lang.ref.WeakReference(doc))
        }
        val prev = execDocAutosavePending.getAndSet(
            execDocAutosaveExecutor.schedule(
                { flushExecDocAutosave(doc) },
                EXEC_DOC_AUTOSAVE_DELAY_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            ),
        )
        prev?.cancel(false)
    }

    private fun flushExecDocAutosave(doc: com.intellij.openapi.editor.Document) {
        ApplicationManager.getApplication().invokeLater {
            runCatching {
                if (!autosaveExecDocNow(doc)) return@invokeLater
                com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
                    .info("[M36] exec doc autosaved (debounced ${EXEC_DOC_AUTOSAVE_DELAY_MS}ms, T-ExecDocSync2 F5)")
            }.onFailure { e ->
                com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
                    .warn("[M36] exec doc autosave FAILED: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * F5 原语：EDT 上守卫落盘——doc 无主文件/已失效/未修改即 no-op（false）；确需落盘包
     * WriteAction saveDocument（T-EC-01 同款落盘语义）。internal 供测试。
     */
    internal fun autosaveExecDocNow(doc: com.intellij.openapi.editor.Document): Boolean {
        val fdm = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
        val vf = runCatching { fdm.getFile(doc) }.getOrNull()
        if (vf == null || !vf.isValid) return false
        // 262：Document.isModified 已从接口移除——「已修改未保存」唯一查询是 isDocumentUnsaved
        if (!fdm.isDocumentUnsaved(doc)) return false
        @Suppress("DEPRECATION") // T-M52：WriteAction.compute(ThrowableComputable) 243 编译目标仅此重载可用
        com.intellij.openapi.application.WriteAction.compute<Unit, Throwable> {
            fdm.saveDocument(doc)
        }
        return true
    }

    /**
     * F6：冲突探针——「文件缓冲区冲突」弹窗（MemoryDiskConflictResolver）响应的是平台对修改中
     * document 发起的 reload，reload 前平台经 FileDocumentManagerListener.beforeFileContentReload
     * 广播（与弹窗实现同 topic，监听先后不保证，故仅取证不拦截）。exec 文件命中即落 warn 指纹
     * （doc/vf 长度与 stamp、unsaved 态）：F1-F5 全上后若真机仍复发，idea.log 直出现场数据，
     * 替代人工复现排查。经项目 messageBus 连接（随项目 disposable 自动断开）。
     */
    private fun installExecConflictProbe(project: Project) {
        if (!conflictProbeProjects.add(project)) return
        project.messageBus.connect(project).subscribe(
            com.intellij.openapi.fileEditor.FileDocumentManagerListener.TOPIC,
            object : com.intellij.openapi.fileEditor.FileDocumentManagerListener {
                override fun beforeFileContentReload(
                    vf: com.intellij.openapi.vfs.VirtualFile,
                    document: com.intellij.openapi.editor.Document,
                ) {
                    if (!isExecVfPath(vf.path)) return
                    val fdm = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                    com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
                        .warn(
                            "[T-ExecDocSync2][conflict-probe] reload-with-unsaved-doc: ${vf.path}" +
                                ", docLen=${document.textLength}, docStamp=${document.modificationStamp}" +
                                ", vfLen=${vf.length}, vfStamp=${vf.modificationStamp}" +
                                ", unsaved=${runCatching { fdm.isDocumentUnsaved(document) }.getOrNull()}",
                        )
                }
            },
        )
    }

    /**
     * M36.5.12：项目内执行文件路径——`<projectBase>/.idea/sql-visualizer/exec/mpsql-exec.sql`
     * （F-44-4：目录随插件更名，旧名 mybatis-plus-sql-visualizer 不再使用）。
     * 目录不存在则创建；文件写入 SQL（覆盖）。与 sql-history 同根（.idea/sql-visualizer/），
     * 但**独立子目录** exec（sql-history 读取器只扫 sql-history 子目录，互不污染）。
     *
     * M36.5.13/M36.5.43：VFS 已知文件走 document 驱动写（历史：裸 FileUtil.writeToFile 覆盖
     * 磁盘触发 "File Cache Conflict" 模态弹窗，MemoryDiskConflictResolver，沙盒 12:08:06/
     * 16:05:39 两次实证）。T-ExecDocSync 三点收口（同一问题族第三轮）：
     *  ① **同临界区落盘**（T-EC-01，[writeVfTextViaDocument]）——补 saveDocument，磁盘即时
     *     与内存一致，不再留「已修改未保存」窗口（M36.5.43 只改内存、磁盘靠自动保存追赶，
     *     窗口期内任何外部改盘即弹冲突窗，easy-code-demo 真机截图实证）；
     *  ② **document 写统一派 EDT**（[dispatchDocWriteOnEdt]，DialectPreMapping 两段式同款）——
     *     262 平台文件 document 的 setText 包 CommandProcessor BGT 硬抛 EDT-only（javap+沙盒
     *     堆栈双实证）：旧实现 BGT 预热的 document 分支每次必炸、静默掉进裸写兜底 = 真机
     *     File Cache Conflict 链本体。BGT 预热自此不再降级；
     *  ③ **裸写兜底守卫**（T-EC-02，[canFallBackToRawWrite]）——document 写失败（含注入缝）
     *     时只有无已加载 document 才允许裸写盘；有 document 一律抛 writeConflictGuard 拒写
     *     （宁内容旧一拍，执行前 console doc 同步链会重写并落盘，执行内容不受影响），
     *     失败原因 Throwable 必达日志（原 getOrDefault(false) 吞掉）；
     *     → **T-ExecDocSync3 退役**（第四轮真机日志实证判据不可靠，见方法内注释）：改为
     *     「VFS 已有该文件即绝不裸写」，冷启动 EDT 忙场景彻底无冲突面；
     *  ④ **单写方锁**（T-EC-03，[execFileWriteLock]）——串行化 BGT 预热与 EDT 冷路径。
     * VFS 缺条目路径维持裸写 + 调用方 refresh + [syncExecVfContentIfNeeded] 防御收口（T-EC-04）。
     * T-PreviewFast P1/P2：差量快路径（内容已一致即返回）+ 预热非阻塞派发（`nonBlocking=true`）。
     * internal 供测试。
     *
     * @param nonBlocking true = 预热等投机性调用：BGT 派发不等 EDT（fire-and-forget，P2）
     */
    internal fun projectExecFile(project: Project, sql: String, nonBlocking: Boolean = false): java.io.File? {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        val base = project.basePath ?: run {
            // T-ExecCopyFix F2a：basePath null 是「无异常的 null」，原来连日志都没有
            LOG.warn("[M36] projectExecFile FAILED: project.basePath null (project=${project.name})")
            return null
        }
        val dir = java.io.File(base, ".idea${java.io.File.separator}${cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME}${java.io.File.separator}exec")
        if (!dir.exists() && !dir.mkdirs()) {
            // T-ExecCopyFix F2a：mkdirs 返回值原来被忽略，目录建不起来后写盘抛异常再被吞
            LOG.warn("[M36] projectExecFile FAILED: mkdirs false (${dir.path})")
            return null
        }
        val f = java.io.File(dir, "mpsql-exec.sql")
        synchronized(execFileWriteLock) {
            val existing = LocalFileSystem.getInstance().findFileByIoFile(f)
            if (existing != null) {
                // T-PreviewFast P1：**差量快路径**——派发前先判当前内容是否已等于目标 SQL。相同则
                // 直接返回，不派发、不落盘、不等 EDT（连续预览同一 SQL 是常见形态：真机日志 14:17
                // 五连预览每次都白付 2s 派发等待 + 一次无效落盘 + 一轮索引噪音）。
                // 判据双源：document 文本（有已加载文档时，UI 所见为准）或 vf 内容（无文档时）。
                if (execFileContentMatches(project, existing, sql)) {
                    LOG.info("[M36] exec file content already matches — write SKIPPED (T-PreviewFast P1): ${f.name}")
                    return f
                }
                // T-EC-01/T-EC-03：document 写统一派 EDT（EDT 直调同步；BGT 派发 + 有界等待）。
                // 262 实证 document setText BGT 硬抛（见 writeVfTextViaDocument KDoc）——旧实现
                // 的 BGT document 分支每次必炸、静默掉进裸写兜底，即真机冲突链本体。
                // T-PreviewFast P2：预热调用（unblocking=true）走 fire-and-forget，不再等满 2 秒。
                val updated = dispatchDocWriteOnEdt(
                    project, existing, sql,
                    waitBudgetMs = if (nonBlocking) 0L else EDT_EXEC_DOC_SYNC_WAIT_MS,
                )
                if (updated) {
                    LOG.info("[M36] exec file updated via document+save (disk synced in-critical-section): ${f.name}")
                    return f
                }
                // T-ExecDocSync3（第四轮真机复现的收口，20260917 真实 IDE 日志实证）：**VFS 已有
                // 该文件时一律不裸写盘**。原 T-EC-02 兜底守卫查 getCachedDocument==null 就放行裸写，
                // 实机证伪——EDT 忙（冷启动/索引，日志同刻可见 DumbService enter dumb mode）时
                // dispatch 有界等待 2s 超时返回 false，此刻「排队的 EDT 写任务」尚未执行、document
                // 也还没建，getCachedDocument 恒 null；随之而来的裸写先改盘（12,590），排队任务
                // 随后 ExecutionException 建出 document（resolver 日志 oldFileStamp:0 = 文档建于文件
                // 写入之前）并标脏，裸写引发的 VFS 变更落到这个刚建的文档上 →
                // MemoryDiskConflictResolver「重载 mpsql-exec.sql？」弹窗（12,609），23ms 后文档写入
                // 才自己落盘（12,613）。即**插件与自己的排队写赛跑**，而非外部改盘。
                // 宁内容旧一拍：排队任务/later 的 console doc 同步链（attachConsole 后的
                // prepareAfterEditorResolved、executeOnConsole 执行前 T-EC-01 同款同步）都会重写并
                // 落盘，执行内容不受影响。附带收益：冷启动 EDT 忙时不再有裸写 → 该场景彻底无冲突面。
                LOG.warn(
                    "[M36] exec doc-write not completed (EDT dispatch timeout/WriteAction failure); " +
                        "raw write SKIPPED to avoid File Cache Conflict (T-ExecDocSync3): ${f.name}",
                )
                return f
            }
            // VFS 无该条目（首次创建）：不可能存在 document（无 vf 可挂），裸写安全
            FileUtil.writeToFile(f, sql)
            return f
        }
    }

    /**
     * M36.5.3：临时文件方言预映射（MySQL）——须在 [PsiManager.findFile] 之前调用，
     * PSI 创建时即按映射解析为 MySQL（否则走 SqlDialectPusher，dumb mode 下被推迟）。
     * 方言实例经 SqlDialectMappings.getAvailableValues() 按 Language.id 匹配（不硬编码实现类），
     * 任何环节失败静默跳过（保持原行为，仅记录日志）。
     *
     * T-ExecSlowOps F1：反射段收编 [DialectPreMapping]（与预览路径 mapPreviewDialectBeforePsi 共用），
     * 本方法保留 exec 侧「进程内同 vf 幂等短路」语义 + 成功回写 [dialectMappedVf]；[ensureMapped]
     * 线程自适应——EDT 直调安全（本方法现调用点全在 EDT），BGT 调用自动走两段式派发。
     */
    private fun mapDialectBeforePsi(project: Project, vf: com.intellij.openapi.vfs.VirtualFile, LOG: com.intellij.openapi.diagnostic.Logger) {
        // T-PreviewFlash：插件侧幂等短路——mpsql-exec.sql 路径稳定，同 vf 已映射过 MySQL 直接跳过，
        // 消除每次预览的点 setMapping（同值 setMapping 仍触发 SqlDialectPusher push all dumb 任务，见沙盒日志）。
        val inProcessMapped = dialectMappedVf?.isValid == true && dialectMappedVf === vf
        if (DialectPreMapping.ensureMapped(project, vf, inProcessMapped)) {
            dialectMappedVf = vf
            LOG.info("[M36] dialect pre-mapped to MySQL for ${vf.name}")
        }
    }

    /** 打开 Database 工具窗口（无配置数据源时引导用户，仅显式执行路径调用；预览路径静默）。 */
    private fun openDatabaseToolWindow(project: Project) {
        runCatching {
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("Database")
                ?.show()
        }
    }

    // ------------------------------------------------------------------
    // 测试访问（同模块测试使用）
    // ------------------------------------------------------------------

    /** T-ExecCopyFix F2c：exec 临时文件 vf+psi 缓存是否仍持有（clearPrepared 不清缓存断言用）。 */
    internal fun cachedTempPsiFileForTest(): Boolean = cachedTempPsiFile != null

    /**
     * T-ExecSlowOps F2：后台预热 exec 临时文件（写盘 → VFS refresh → 方言映射 → findFile →
     * 填 [cachedTempPsiFile]）——**只允许后台线程调用**（EDT 直调拒绝并 warn：本链含
     * refresh/findFile 慢操作，EDT 执行正是 262 真机堆栈①的 SEVERE 本体）。
     *
     * 由 `SqlPreviewService.previewAsync` 在池线程内、`invokeLater` 派发弹窗之前调用：弹窗打开后
     * `prepareDefaultConsole → chooseAndPrepare → consumeCachedTempPsiFile` 必命中缓存，EDT 冷路径
     * （[createTempPsiFile] 的 refresh+findFile）退化为兜底（无预览链触达的显式执行路径首跑等）。
     *
     * SQL 一致性：预热写入的是 [cn.song.mybatisplus.visualizer.model.PreviewResult.sql] 原文，与
     * 面板渲染文本（格式化/参数回填后）可能不同——与 T-PreviewFlash 缓存命中路径同款语义（缓存命中
     * 时本就不重写文件，文档内容差异由下游 console doc 同步链吸收：reuseSharedConsole setText /
     * prepareAfterEditorResolved model 同步 / executeOnConsole 执行前同步）。
     *
     * 幂等：缓存已对当前项目可用即直接返回（同项目连续预览仅一次真实链）。
     * Community/无 Database 插件场景安全：本方法不触任何 Database 类，纯 VFS/PSI。
     */
    @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
    fun prewarmExecTempFile(project: Project, sql: String) {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        if (ApplicationManager.getApplication().isDispatchThread) {
            LOG.warn("[M36] prewarmExecTempFile called on EDT — refused (slow ops, T-ExecSlowOps F2)")
            return
        }
        if (project.isDisposed || sql.isBlank()) return
        val cachedVf = cachedTempPsiFile?.first
        if (cachedVf != null && isCacheUsable(project, cachedVf)) {
            // 缓存命中≠内容新鲜：连续预览 SQL 会变，而缓存命中路径不重写文件——只刷新文件
            // 内容（projectExecFile doc 驱动三方同步，vf/psi 身份不变），保证 attachConsole
            // 初始 document 与本条预览 SQL 一致；差量内容比较在 projectExecFile 内部短路。
            //
            // T-PreviewConsoleFmt F2 + T-PreviewFast P3：**该文件正被编辑器打开时跳过内容刷新**——
            // 有人正看着这份内容时预热不得改写（预热写的是「预览原文」裸单行，会盖掉面板渲染进
            // 控制台的格式化文本；真机 20260917 日志：showSql createEditor len=274/16 行 → prewarm
            // 原文 → attach 显示 1 行）。判据为「文档已加载且挂了编辑器」——原 `console.getVirtualFile
            // () === vf` 判据实机从未命中（SKIPPED 计数 0 / refreshed 10），已退役。
            if (isExecDocOpenInEditor(cachedVf)) {
                LOG.info("[M36] exec prewarm: cache hit, exec doc open in editor — content refresh SKIPPED (T-PreviewFast P3)")
                return
            }
            // T-PreviewFast P2：非阻塞派发——预热是投机性写入，没必要让 BGT 等满 2 秒
            runCatching { projectExecFile(project, sql, nonBlocking = true) }.onFailure { e ->
                // T-FatalPassthrough T-FP-02b（独立复核阻断 2）：写盘链的 fatal 不得被本 runCatching
                // 吞成一条 warn（否则 prewarm 链 4 处既有透传全部作废）。非 fatal 仍原样 warn 降级。
                if (FatalErrors.isFatal(e)) throw e
                LOG.warn("[M36] exec prewarm refresh-content failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            LOG.info("[M36] exec prewarm: cache hit, content refreshed only")
            return
        }
        // 并发预览（gutter 连点/多面板）只允许一条真实预热链——其余直接返回，弹窗冷路径兜底
        if (!execPrewarmInFlight.compareAndSet(false, true)) {
            LOG.info("[M36] exec prewarm skipped (another prewarm in flight)")
            return
        }
        try {
            prewarmExecTempFileInner(project, sql, LOG)
        } finally {
            execPrewarmInFlight.set(false)
        }
    }

    /**
     * T-PreviewFast P1：exec 文件当前内容是否已等于目标 SQL——差量快路径判据（双源）。
     *
     * 有已加载 document 时以 **document 文本**为准（UI/控制台所见即权威，也是落盘会写入的内容）；
     * 无 document 时读 **vf 内容**。两者比对失败（异常/不可读）一律返回 false（保守走正常写入路径）。
     * internal 供测试。
     */
    internal fun execFileContentMatches(
        project: Project,
        vf: com.intellij.openapi.vfs.VirtualFile,
        sql: String,
    ): Boolean = runCatching {
        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getCachedDocument(vf)
        if (doc != null) {
            doc.text == sql
        } else {
            vf.inputStream.readBytes().toString(Charsets.UTF_8) == sql
        }
    }.getOrDefault(false)

    /**
     * T-PreviewFast P3：exec 文件是否**正被编辑器打开**（= 有人正在看这份内容）——预热据此决定
     * 是否跳过内容刷新。
     *
     * 取代 T-PreviewConsoleFmt 的 `console.getVirtualFile() === vf` 判据：真机实测该等式从未成立
     * （`content refresh SKIPPED` 计数 0 而 `content refreshed only` 10 次），预热因此每次仍改写
     * 文件、与面板回填互相覆盖一轮。改判「该 vf 的文档已加载且挂了编辑器」——语义正是
     * 「别改用户眼前的内容」，且不依赖 DBN 内部方法。internal 供测试。
     */
    internal fun isExecDocOpenInEditor(vf: com.intellij.openapi.vfs.VirtualFile): Boolean = runCatching {
        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getCachedDocument(vf)
            ?: return false
        com.intellij.openapi.editor.EditorFactory.getInstance().getEditors(doc).isNotEmpty()
    }.getOrDefault(false)

    /** [prewarmExecTempFile] 的真实链（已持有 in-flight 标记；仅后台线程调用）。 */
    @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
    private fun prewarmExecTempFileInner(project: Project, sql: String, LOG: com.intellij.openapi.diagnostic.Logger) {
        lastTempFileFailure = null
        // T-ExecCopyFix F2b：refresh 段 WriteIntentReadAction（BGT 上 WIRA 合法；与 EDT 冷路径同款包装）
        val ioFile = runCatching { projectExecFile(project, sql) }.getOrElse { e ->
            // T-FatalPassthrough T-FP-02b（独立复核阻断 2）：fatal 上浮（预热跑在 BGT，透传由平台
            // 记录现场）；非 fatal 仍返回（writeFile 失败 → 跳过预热，语义逐字不变）。
            if (FatalErrors.isFatal(e)) throw e
            LOG.warn("[M36] exec prewarm writeFile failed: ${e.javaClass.simpleName}: ${e.message}")
            return
        } ?: return
        // T-ExecDocSync2 F4：refresh 前强制收敛——projectExecFile 的 doc 写在 EDT 落盘后、本
        // refresh 之前，用户在控制台（document 即 exec 文件 document）键入可插入 modified-unsaved
        // 态；此时 refresh 命中「doc 已修改 && 磁盘≠文档」即弹 File Cache Conflict（第四轮残余竞态，
        // 开发计划 §1.3）。findFileByIoFile（与 projectExecFile 内裸调同形态，prewarm DONE 日志
        // 实证 BGT 可调）命中即过一遍分叉探测 + EDT 派发收敛，正常恒 no-op。
        try {
            LocalFileSystem.getInstance().findFileByIoFile(ioFile)?.let { vf0 ->
                syncExecVfContentIfNeeded(project, vf0, sql)
            }
        } catch (e: Throwable) {
            if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传
            LOG.warn("[M36] exec prewarm pre-refresh converge FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
        val vf = try {
            com.intellij.openapi.application.WriteIntentReadAction
                .compute<com.intellij.openapi.vfs.VirtualFile?> {
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
                }
        } catch (e: Throwable) {
            if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传（prewarm 跑在 BGT，透传由平台记录现场）
            LOG.warn("[M36] exec prewarm refreshVfs failed: ${e.javaClass.simpleName}: ${e.message}")
            return
        } ?: run {
            LOG.warn("[M36] exec prewarm: VFS not visible after refresh (${ioFile.path}, exists=${ioFile.exists()})")
            return
        }
        // T-EC-04：同冷路径——裸写残留的防御收口（正常恒 no-op）
        try {
            syncExecVfContentIfNeeded(project, vf, sql)
        } catch (e: Throwable) {
            if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传
            LOG.warn("[M36] exec prewarm syncVfContent failed: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        // T-ExecSlowOps F1：BGT 两段式映射（读探测稳态跳过 / 首见派 EDT + 有界等待）
        val inProcessMapped = dialectMappedVf?.isValid == true && dialectMappedVf === vf
        if (DialectPreMapping.ensureMapped(project, vf, inProcessMapped)) {
            dialectMappedVf = vf
        }
        val psi = try {
            ReadAction.compute<PsiFile?, Throwable> {
                PsiManager.getInstance(project).findFile(vf)
            }
        } catch (e: Throwable) {
            if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传
            LOG.warn("[M36] exec prewarm findPsi failed: ${e.javaClass.simpleName}: ${e.message}")
            return
        } ?: run {
            LOG.warn("[M36] exec prewarm: findFile null (${vf.name})")
            return
        }
        if (project.isDisposed) return
        markCachedTempPsiFile(project, vf, psi)
        LOG.info("[M36] exec prewarm DONE (cache filled off EDT): ${vf.name}")
    }

    /** T-PerfSweep2-04：已挂项目关闭钩子的项目集——防对同一项目重复 Disposer.register。 */
    private val hookProjectsClose =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Project, Boolean>())

    /**
     * T-ExecSlowOps F2：填 exec 临时文件 vf+psi 缓存槽（项目绑定；BGT/EDT 均可调用——volatile 写）。
     * T-PerfSweep2-04：同时挂一次性项目关闭钩子——项目关闭即清槽，不再等下次消费顶掉
     * （此前项目关闭后槽里残留该项目的 vf+psi 强引用至下次写入；isCacheUsable 惰性校验
     * 保留为双保险）。[parentDisposable] 默认项目自身，测试注入自有 Disposable 手动触发。
     */
    internal fun markCachedTempPsiFile(
        project: Project,
        vf: com.intellij.openapi.vfs.VirtualFile,
        psi: PsiFile,
        parentDisposable: com.intellij.openapi.Disposable = project,
    ) {
        cachedTempPsiFile = vf to psi
        cachedTempPsiProjectName = project.name
        hookProjectClose(project, parentDisposable)
        // T-ExecDocSync2 F5/F6 挂载点：exec 缓存槽建立即安装防抖自动落盘监听（应用级惰性一次）
        // 与冲突探针（项目级一次）；非 exec 路径 vf 进不来本方法，路径过滤双保险。
        // 安装段 runCatching 降级——探针 connect 在无 messageBus 的代理桩项目上 NPE
        // （CachedTempPsiFileTest 全代理桩实证）；两件均为增强件，失败仅失去防抖落盘
        // 与冲突取证，不得阻断缓存主链
        if (isExecVfPath(vf.path)) {
            runCatching {
                installExecDocAutosaveListener()
                installExecConflictProbe(project)
            }.onFailure { e ->
                com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
                    .warn("[M36] exec doc autosave/probe install skipped: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun hookProjectClose(project: Project, parentDisposable: com.intellij.openapi.Disposable) {
        if (!hookProjectsClose.add(project)) return
        Disposer.register(parentDisposable) {
            hookProjectsClose.remove(project)
            conflictProbeProjects.remove(project)
            val cached = cachedTempPsiFile
            if (cached != null && cached.second.project == project) {
                cachedTempPsiFile = null
                cachedTempPsiProjectName = null
            }
        }
    }

    /** T-ExecCopyFix F2a：最近一次 createTempPsiFile 失败的阶段原因（成功时 null）。 */
    internal fun lastTempFileFailureForTest(): String? = lastTempFileFailure

    /** T-ExecCopyFix F2c 测试缝：等价链路置缓存槽（psi 经 PsiManager.findFile 同生产路径配对）。 */
    internal fun markCachedTempPsiFileForTest(project: Project, vf: com.intellij.openapi.vfs.VirtualFile) {
        val psi = ReadAction.compute<PsiFile?, Throwable> {
            PsiManager.getInstance(project).findFile(vf)
        }
        checkNotNull(psi) { "markCachedTempPsiFileForTest: findFile null for ${vf.name}" }
        markCachedTempPsiFile(project, vf, psi)
    }

    /** T-ExecCopyFix F2c 测试缝：清缓存槽（测试隔离收尾）。 */
    internal fun clearCachedTempPsiFileForTest() {
        cachedTempPsiFile = null
        cachedTempPsiProjectName = null
    }

    /** T-ExecDocSync 测试缝：置 true 时 [docWriteOnce] 注入失败（测 T-EC-02 守卫拒绝路径；EDT/BGT 两形态统一生效）。 */
    internal var execWriteActionFailureInjection = false

    /** T-PS4-11 测试缝：防抖任务是否在途（未完成）——监听生效/卸载的行为断言点。 */
    internal fun execDocAutosaveScheduledForTest(): Boolean =
        execDocAutosavePending.get()?.isDone == false

    /** T-PS4-11 测试缝：取消在途防抖任务并清快判缓存（测试隔离收尾，不触碰宿主挂载态）。 */
    internal fun resetExecDocAutosaveForTest() {
        execDocAutosavePending.getAndSet(null)?.cancel(false)
        lastExecDocRef.set(null)
    }
}

/**
 * T-PS4-11：F5 防抖自动落盘监听的挂靠宿主（应用级轻服务，Disposable parent）。
 *
 * 挂靠定案：监听以两参重载挂到本服务实例持有的子 Disposable 上，而不是「自建应用级
 * Disposable + 订阅 DynamicPluginListener 在卸载回调里手动 dispose」——本插件的动态卸载走
 * [cn.song.mybatisplus.visualizer.update.DynamicInstallSupport] → 平台 `DynamicPlugins
 * .unloadPlugin`，平台卸载会 dispose 插件应用级容器并级联释放其中的服务（轻服务实例注册于
 * 容器 Disposer 树），以其为根的监听随之自动移除，插件卸载时监听随类加载器一并释放；而
 * DynamicInstallSupport KDoc 明示卸载/装载回调内只宜触达平台 API 与纯函数（调用栈运行在
 * 将被卸载的旧 classloader 上），回调里反手 dispose 自身类有 classloader 时序风险。容器
 * 级联释放即平台对「插件级应用监听」的惯例挂法。
 *
 * 服务惰性实例化（首次 [getInstance] 才创建）与监听「惰性装一次」语义一致；正常应用退出时
 * 应用容器 dispose 同样级联移除监听。子 Disposable 缓解重挂：宿主被容器 dispose 后实例不可
 * 再作 parent（Disposer 拒绝已 dispose 节点），但仅卸载监听（测试卸载模拟/极端重装）时 dispose
 * 子节点即可重挂——容器级卸载（服务 dispose）则 [dispose] 复位后同实例不再可用，新容器装载
 * 新类自然拿到新宿主。
 */
@Service(Service.Level.APP)
internal class ExecDocAutosaveHost : com.intellij.openapi.Disposable {

    companion object {
        fun getInstance(): ExecDocAutosaveHost =
            ApplicationManager.getApplication().getService(ExecDocAutosaveHost::class.java)
    }

    /** 当前挂载的子 Disposable（null = 未挂载）。经 eventMulticaster 两参重载挂监听。 */
    private var attached: com.intellij.openapi.Disposable? = null

    /** F5 监听挂载（幂等；仅未挂载时装，重复调用 no-op）。 */
    fun ensureListenerInstalled(listener: com.intellij.openapi.editor.event.DocumentListener) {
        synchronized(this) {
            if (attached != null) return
            val parent = Disposer.newDisposable("ExecDocAutosaveHost.listener")
            com.intellij.openapi.editor.EditorFactory.getInstance().eventMulticaster
                .addDocumentListener(listener, parent)
            Disposer.register(this, parent)
            attached = parent
        }
    }

    /** T-PS4-11 测试缝：仅卸载监听（dispose 子节点），宿主可重挂——模拟「监听随 Disposable 卸载」。 */
    internal fun detachListenerForTest() {
        synchronized(this) {
            attached?.let { Disposer.dispose(it) }
            attached = null
        }
    }

    /** T-PS4-11 测试缝：监听是否在挂（attach 幂等判重 + 卸载断言用）。 */
    internal val isListenerAttached: Boolean
        get() = synchronized(this) { attached != null }

    override fun dispose() {
        // 子节点经 Disposer 树级联 dispose（监听随之移除）；复位挂载态。
        // 宿主已随插件容器 dispose 后实例不再作 parent（重挂会抛 already disposed）——
        // 生产语义即「插件已卸载，旧类不应再装监听」，仅测试卸载模拟走 detachListenerForTest。
        synchronized(this) { attached = null }
    }
}
