package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
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

    /** DatabaseRunners 类名（反射入口）。 */
    private const val RUNNERS_CLASS = "com.intellij.database.console.DatabaseRunners"

    /** 执行链反射目标类名。 */
    private const val CONSOLE_PROVIDER_CLASS = "com.intellij.database.console.JdbcConsoleProvider"
    private const val CONSOLE_CLASS = "com.intellij.database.console.JdbcConsole"
    private const val SESSION_CLASS = "com.intellij.database.console.session.DatabaseSession"

    /** last-used 数据源记忆 key（prepareConsole 静默复用，PropertiesComponent per-project）。 */
    private const val LAST_RUNNER_KEY = "mpsql.visualizer.last.runner"

    /**
     * M36.5.21b：构建版本戳——与源码同文件维护，每次执行打日志。
     * 判定法：沙盒日志若与本串不符 = 沙盒跑旧 jar（未重启 IDE），先重启再排查。
     */
    private const val BUILD_STAMP = "M36.5.21d-transaction"

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

    /** M36.5.6：结果区换入回调（准备阶段挂 ContentManager 监听，任一路径出结果即换入；复用时由新面板接管）。 */
    private var pendingOnConsole: ((javax.swing.JComponent) -> Unit)? = null

    /** M36.5.9：最近一次换入宿主的结果区组件实例（执行后重取刷新的去重依据）。 */
    private var lastLiveResultSwapped: java.awt.Component? = null

    /**
     * M36.5.10：执行提交代际计数——executeQueries 提交经 DumbService.runWhenSmart 挂起期间，
     * 若面板清空（clearPrepared）/新预览建立新控制台上下文（prepareInConsole/reuseSharedConsole），
     * 代际不匹配的挂起提交直接放弃（否则会在新文档上执行旧预览的 SQL——文档共享）。
     */
    @Volatile
    private var executionGeneration = 0

    // M36.5.7：共享缓存的分件存储——复用/换入时按**实时结果区组件**重建完整 SplitPane。
    // （组合组件引用在面板分离/重挂后会过期：M36.5.5 复用面板空显示、M36.5.6 第二次预览
    // 结果栏消失、contentAdded 换入未写回共享缓存导致复用嵌了旧编辑器块，均是此类过期引用。）
    private var sharedTopBlock: javax.swing.JComponent? = null

    /** 控制台是否已就绪（prepareConsole 成功后 true）。 */
    @JvmStatic
    fun isPrepared(): Boolean = preparedConsole != null

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
    }

    /**
     * M36.5：准备控制台（不执行）——选数据源 → 建 session → attachConsole → 拼合组件回调。
     * 面板打开时调用，原生控制台即刻可见；执行由 [executePrepared] 触发。
     *
     * 数据源选择策略：唯一数据源/last-used 命中 → 静默；多数据源 → 原生弹窗（记住选择）；
     * 无数据源 → 打开 Database 工具窗口 + onError。
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
    ): Boolean {
        val console = preparedConsole ?: return false
        val editor = preparedEditor ?: return false
        val scriptModel = preparedScriptModel ?: return false
        val execOption = preparedExecOption ?: return false
        executeOnConsole(project, console, editor, scriptModel, execOption, preparedUi, onResultsReady, onError)
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
    ) {
        chooseAndPrepare(project, sql, executeAfterPrepare = true,
            onConsole = onConsole, onEditor = onEditor, onError = onError, callback = callback)
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
    ) {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        if (!ExecuteSqlSupport.isAvailable()) {
            LOG.warn("[M36] database plugin NOT available, abort")
            onError?.invoke(MpSqlVisualizerBundle.message("execute.noDatabase"))
            callback?.invoke(false)
            return
        }

        // M36.5.5：共享控制台命中（last-used 数据源一致且控制台仍有效）→ 直接复用——
        // 不同步 session/连接/临时文件/方言映射，规避"第二个会话连接永不发起"挂死（见字段注释）。
        // 显式执行路径同样复用（结果落在已嵌入面板）；用户切换数据源走弹窗 → 新建并替换共享缓存。
        if (reuseSharedConsole(project, sql, executeAfterPrepare, onConsole, onEditor, onError, callback)) return

        // 临时文件 + VF + PsiFile：Runner 上下文 + 控制台挂载点（VF 须保持引用）
        val temp = createTempPsiFile(project, sql)
        if (temp == null) {
            LOG.warn("[M36] createTempPsiFile failed, abort")
            onError?.invoke("Failed to create temporary SQL file")
            callback?.invoke(false)
            return
        }
        val (vf, psiFile) = temp

        // consumer：runner.run 建好 session 后回调 → 续接准备链（attachConsole → 拼合 → 可选执行）
        var chosenRunnerName: String? = null
        val sessionConsumer = com.intellij.util.Consumer<Any> { session ->
            LOG.info("[M36] session created: ${session?.javaClass?.name}, attaching console (execute=$executeAfterPrepare, ds=$chosenRunnerName)")
            prepareInConsole(project, session, vf, executeAfterPrepare, chosenRunnerName, onConsole, onEditor, onError)
        }

        val runners = getAttachDataSourceRunners(psiFile, sql, sessionConsumer)
        LOG.info("[M36] runners=${runners.size}")

        // M36.5：last-used 数据源静默命中（仅 prepareConsole 路径；显式执行保持弹窗可选）
        if (!executeAfterPrepare) {
            val last = lastRunnerName(project)
            if (last != null) {
                runners.firstOrNull { getRunnerName(it) == last }?.let { matched ->
                    LOG.info("[M36] silent reuse last runner: $last")
                    chosenRunnerName = last
                    runRunner(matched)
                    callback?.invoke(true)
                    return
                }
            }
        }

        when {
            runners.isEmpty() -> {
                LOG.warn("[M36] runners empty -> open Database tool window")
                openDatabaseToolWindow(project)
                // M36.3：无数据源时通知 UI 从「执行中」恢复
                onError?.invoke(MpSqlVisualizerBundle.message("execute.datasource.empty"))
                callback?.invoke(false)
            }
            runners.size == 1 && shouldBeChosenIfAlone(runners.first()) -> {
                LOG.info("[M36] single runner, run directly")
                chosenRunnerName = getRunnerName(runners.first())
                runRunner(runners.first())
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
                    if (runner != null) runRunner(runner)
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
        if (!valid) {
            LOG.info("[M36] shared console invalid (ds=$dsName), drop and rebuild")
            dropSharedConsole()
            return false
        }
        runCatching {
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                editor.document.setText(sql)
            }
        }
        // M36.5.7：完整 UI 按**实时结果区组件**重建（组合引用在面板分离/重挂后会过期——
        // M36.5.5 复用面板空显示根因）；顶部块（原生头部+编辑器）自旧面板"偷"入新面板。
        val liveResult = runCatching {
            sharedUi?.javaClass?.getMethod("getComponent")?.invoke(sharedUi) as? javax.swing.JComponent
        }.getOrNull()
        val full = liveResult?.let { buildConsoleSplitPane(top, it) }
            ?: sharedFullComponent
            ?: return false
        lastLiveResultSwapped = liveResult
        sharedFullComponent = full
        preparedConsole = console
        preparedEditor = editor
        preparedScriptModel = scriptModel
        preparedExecOption = execOption
        preparedUi = sharedUi
        preparedTopBlock = top
        preparedResultComponent = liveResult
        preparedFullComponent = full
        onEditor?.invoke(editor)
        onConsole?.invoke(full)
        // M36.5.6：接管结果换入回调——后续任意路径执行产生新结果内容时换入本面板；
        // 已嵌完整组件场景由面板同实例防抖幂等吸收。
        pendingOnConsole = onConsole
        logDataContextCheck(top, console, editor, execOption, LOG)
        clearConsolePrompt(console, LOG)
        LOG.info("[M36] shared console reused (ds=$dsName, execute=$executeAfterPrepare, liveResult=${liveResult != null})")
        if (executeAfterPrepare) {
            executeOnConsole(project, console, editor, scriptModel, execOption, sharedUi, onResultsReady = null, onError = onError)
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
     * M36.5.8：清空控制台提示符（SQL 编辑器左侧 "rbac>"/">" 装饰）——用户反馈无用干扰。
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
    private fun refreshResultsComponent(ui: Any, LOG: com.intellij.openapi.diagnostic.Logger, forceSwapIn: Boolean = false) {
        val callback = pendingOnConsole ?: return
        val top = sharedTopBlock ?: preparedTopBlock ?: return
        val shared = sharedConsole
        var ticks = 0
        var swapped = false
        val timer = javax.swing.Timer(600, null)
        timer.addActionListener(object : java.awt.event.ActionListener {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                ticks++
                // M36.5.13：每拍清提示符（压制执行后重设）
                if (shared != null) clearConsolePrompt(shared, LOG)
                val live = runCatching {
                    ui.javaClass.getMethod("getComponent").invoke(ui) as? javax.swing.JComponent
                }.getOrNull()
                val shouldSwap = live != null && (forceSwapIn && !swapped || live !== lastLiveResultSwapped)
                if (shouldSwap && live != null) {
                    val changed = live !== lastLiveResultSwapped
                    swapped = true
                    lastLiveResultSwapped = live
                    val full = buildConsoleSplitPane(top, live)
                    sharedFullComponent = full
                    preparedFullComponent = full
                    val cb = pendingOnConsole
                    if (cb != null) {
                        // Timer 回调已在 EDT，直接换入（模态 pump 中 Timer 照常 fire）
                        cb.invoke(full)
                    }
                    LOG.info("[M36] results refreshed (tick=$ticks, forced=$forceSwapIn, instanceChanged=$changed)")
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
        LOG: com.intellij.openapi.diagnostic.Logger,
    ) {
        if (ui == null || resultComponent == null) return
        runCatching {
            val cm = ui.javaClass.getMethod("getContentManager").invoke(ui)
                as com.intellij.ui.content.ContentManager
            var fullCache: javax.swing.JComponent? = null
            cm.addContentManagerListener(object : com.intellij.ui.content.ContentManagerListener {
                override fun contentAdded(event: com.intellij.ui.content.ContentManagerEvent) {
                    val name = event.content?.displayName ?: return
                    LOG.info("[M36] contentAdded: $name")
                    if (name.equals("Output", ignoreCase = true) || name.equals("输出", ignoreCase = true)) return
                    val callback = pendingOnConsole ?: run {
                        LOG.info("[M36] contentAdded swap-in SKIPPED (pendingOnConsole=null)")
                        return
                    }
                    // 换入时重新取结果区组件——RunnerLayoutUi 可能在面板分离/重挂后重建组件，
                    // prepare 时捕获的引用可能已非当前展示实例（M36.5.5 复用面板空显示的疑因）。
                    // 注意：**不消费** pendingOnConsole——contentAdded 在查询提交时触发，数据到达
                    // 后 RunnerLayoutUi 可能再次重建组件，refreshResultsComponent 的限时重取
                    // 依赖该回调（去重靠 lastLiveResultSwapped）。
                    val liveResult = runCatching {
                        ui.javaClass.getMethod("getComponent").invoke(ui) as? javax.swing.JComponent
                    }.getOrNull() ?: resultComponent
                    lastLiveResultSwapped = liveResult
                    val full = fullCache
                        ?: buildConsoleSplitPane(topBlock, liveResult).also {
                            fullCache = it
                            preparedFullComponent = it
                        }
                    // M36.5.7：写回共享缓存——否则下次复用嵌的是换入前的旧编辑器块
                    //（M36.5.6 第二次预览结果栏消失根因）
                    sharedTopBlock = topBlock
                    sharedFullComponent = full
                    // M36.5.19：换入打点——contentAdded 回调体内异常会静默吞掉换入（EDT），打点定位
                    LOG.info("[M36] contentAdded swap-in dispatched (name=$name, cached=${fullCache != null})")
                    // M36.5.13：ModalityState.any()——模态弹窗（File Cache Conflict/密码框）期间
                    // 照常换入（默认 modality 的 invokeLater 会被冻结，沙盒 48s 挂起实证）
                    ApplicationManager.getApplication().invokeLater({
                        runCatching { callback.invoke(full) }.onFailure { ex ->
                            LOG.warn("[M36] contentAdded swap-in FAILED: ${ex.javaClass.simpleName}: ${ex.message}", ex)
                        }
                    }, com.intellij.openapi.application.ModalityState.any())
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
        // M36.5.13：ModalityState.any()——模态弹窗期间探针照常执行（默认 modality 下 invokeLater
        // 被冻结：沙盒 12:09:55→12:10:50 延迟 55s 实证，探针日志失去诊断价值）
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
                    val projectOk = ctx.getData(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.name) != null
                    val editorOk = ctx.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR.name) != null
                    val clientKeyName = runCatching {
                        (Class.forName("com.intellij.database.console.client.SessionClientHolder")
                            .getField("CLIENT_KEY").get(null)
                                as com.intellij.openapi.actionSystem.DataKey<*>).name
                    }.getOrNull()
                    val consoleOk = clientKeyName != null && ctx.getData(clientKeyName) != null
                    val psiOk = ctx.getData(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE.name) != null
                    val sqlAtPlace = runCatching {
                        Class.forName("com.intellij.database.console.JdbcConsoleProvider")
                            .getMethod("isSqlAtPlace", PsiFile::class.java, com.intellij.openapi.editor.Editor::class.java)
                            .invoke(null, ctx.getData(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE.name), editor)
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
                    LOG.info(
                        "[M36] dataContext check: PROJECT=$projectOk, EDITOR=$editorOk, CLIENT_KEY=$consoleOk, PSI_FILE=$psiOk, " +
                            "isSqlAtPlace=$sqlAtPlace, statementsRange=$statementsRange",
                    )
                }
            }.onFailure { e ->
                LOG.warn("[M36] dataContext check FAILED: ${e.message}")
            }
        }, com.intellij.openapi.application.ModalityState.any())
    }

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
            val console = attach.invoke(null, project, session, vf)
            LOG.info("[M36] attachConsole -> $console")
            if (console == null) return

            val consoleClass = Class.forName(CONSOLE_CLASS)

            // 2. 执行用编辑器：按 console 文档反查（先于内嵌——编辑器随 console 创建即初始化）
            val document = consoleClass.getMethod("getDocument").invoke(console)
                as? com.intellij.openapi.editor.Document
            var editor: com.intellij.openapi.editor.Editor? = null
            repeat(20) {
                if (editor != null) return@repeat
                document?.let {
                    editor = com.intellij.openapi.editor.EditorFactory.getInstance()
                        .getEditors(it).firstOrNull()
                }
                if (editor == null) Thread.sleep(20)
            }
            LOG.info("[M36] editor=$editor")
            if (editor == null) return
            // M36.2：编辑器就绪回调（调用方注入插件补全上下文，与原生补全叠加）
            onEditor?.invoke(editor)

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

            // M36.5：缓存已备控制台（executePrepared 消费）
            // M36.5.10：代际递增——新控制台上下文取代旧缓存（旧挂起的 runWhenSmart 提交作废，
            // 防止在新控制台上执行旧预览 SQL）
            executionGeneration++
            preparedConsole = console
            preparedEditor = editor
            preparedScriptModel = scriptModel
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
                if (preferredSize == null || preferredSize.height < 50) {
                    preferredSize = java.awt.Dimension(400, 200)
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
                    add(editorComponentRaw, java.awt.BorderLayout.CENTER)
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
            val component: javax.swing.JComponent = if (executeAfterPrepare) (fullComponent ?: topBlock) else topBlock
            preparedTopBlock = topBlock
            preparedResultComponent = resultComponent
            preparedFullComponent = if (executeAfterPrepare) fullComponent else null
            LOG.info("[M36] embed component=$component (executeAfterPrepare=$executeAfterPrepare)")

            if (!executeAfterPrepare) {
                attachResultsHook(ui, topBlock, resultComponent, LOG)
            }
            logDataContextCheck(editorComponent, console, editor, execOption, LOG)
            clearConsolePrompt(console, LOG)

            // M36.5.5：填充共享控制台缓存 + 统一记忆数据源（下次预览 last-used 命中即复用，
            // 不再新建 session/连接——挂死根因见 sharedConsole 字段注释）
            sharedConsole = console
            sharedEditor = editor
            sharedScriptModel = scriptModel
            sharedExecOption = execOption
            sharedUi = ui
            sharedTopBlock = topBlock
            sharedFullComponent = component
            sharedDataSourceName = dataSourceName
            if (dataSourceName != null) rememberRunnerName(project, dataSourceName)

            // 4.7 内嵌（同步，已在 EDT）
            onConsole?.invoke(component)

            // 5. 可选立即执行（selectAndExecute 全链语义；结果区已随完整 UI 内嵌，onResultsReady=null）
            if (executeAfterPrepare) {
                executeOnConsole(project, console, editor, scriptModel, execOption, ui, onResultsReady = null, onError = onError)
            }
        }.onFailure { e ->
            LOG.warn("[M36] prepareInConsole FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            // M36.3：通知调用方错误，确保 UI 从「执行中」状态恢复
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            onError?.invoke(cause.message ?: cause.toString())
        }
    }

    /** M36.5.2：主题化完整控制台 SplitPane（顶部块 40% / 结果区 60%）——M36.4 形态，两处构建共用。 */
    private fun buildConsoleSplitPane(
        topBlock: javax.swing.JComponent,
        resultComponent: javax.swing.JComponent,
    ): javax.swing.JComponent {
        return object : javax.swing.JSplitPane(
            javax.swing.JSplitPane.VERTICAL_SPLIT, topBlock, resultComponent,
        ) {
            override fun paint(g: java.awt.Graphics) {
                super.paint(g)
                g.color = com.intellij.util.ui.UIUtil.getPanelBackground()
                g.fillRect(0, getDividerLocation(), width, getDividerSize())
            }
        }.apply {
            resizeWeight = 0.4
            isContinuousLayout = true
            isOneTouchExpandable = false
            dividerSize = 3
            border = com.intellij.util.ui.JBUI.Borders.empty()
            background = com.intellij.util.ui.UIUtil.getPanelBackground()
        }
    }

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
    ) {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        runCatching {
            val consoleClass = Class.forName(CONSOLE_CLASS)

            // 后台执行 executeQueries（M36.3）：避免 EDT 阻塞导致密码弹窗被遮挡。
            // M36.5：executeQueries 内部 Document.createRangeMarker 要求 read action，
            // 线程池线程无 read action 上下文会抛 RuntimeExceptionWithAttachments
            val execute = consoleClass.getMethod(
                "executeQueries",
                com.intellij.openapi.editor.Editor::class.java,
                Class.forName("com.intellij.database.script.ScriptModel"),
                execOption.javaClass,
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
                        val consoleText = editor.document.text
                        @Suppress("DEPRECATION") // T-M52：WriteAction.compute(ThrowableComputable) 243 编译目标仅此重载可用
                        com.intellij.openapi.application.WriteAction.compute<Unit, Throwable> {
                            val fileDoc = com.intellij.openapi.fileEditor.FileDocumentManager
                                .getInstance().getDocument(consoleVf)
                            when {
                                fileDoc != null && fileDoc.text != consoleText -> {
                                    fileDoc.setText(consoleText)
                                    // setText 的 write command 结束时自动 commit；显式 commitDocument
                                    // 双保险（同步后同 tick 内 findFile 的 PSI 立即对齐）
                                    com.intellij.psi.PsiDocumentManager.getInstance(project)
                                        .commitDocument(fileDoc)
                                }
                                fileDoc != null -> com.intellij.psi.PsiDocumentManager.getInstance(project)
                                    .commitDocument(fileDoc)
                                fileDoc == null -> consoleVf.setBinaryContent(consoleText.toByteArray())
                            }
                        }
                        LOG.info("[M36] console doc synced+committed to vf before execution (${consoleVf.name})")
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
                // M36.5.15：连接等待监控（单次 8s）——invoke 会在连接建立时同步等待凭据输入
                //（密码弹窗被预览窗口遮挡，用户不输入则 invoke 长期阻塞、执行完成后的重试永不
                // 启动，面板永远"执行中"，沙盒 13:25:08→13:25:25 实证 17s）。提交前挂 8s 监控：
                // 到点仍无结果 Tab → 面板内红字提示（密码/驱动弹窗可能在预览窗口后方）。
                // 不直接提前启动 startResultTabRetry——重复执行场景它第一拍就会命中**旧**结果
                // Tab 并换入旧数据（新查询完成后无人再刷新）。
                if (ui != null && onError != null) {
                    val uiForWatch = ui
                    val hintOnError = onError
                    val consoleForWatch = console
                    ApplicationManager.getApplication().invokeLater({
                        val watch = javax.swing.Timer(8000, null)
                        watch.addActionListener {
                            watch.stop()
                            val hasResult = runCatching {
                                uiForWatch.javaClass.getMethod("getContents").invoke(uiForWatch)
                                    as? Array<com.intellij.ui.content.Content>
                            }.getOrNull()?.any {
                                !it.displayName.equals("Output", ignoreCase = true) &&
                                    !it.displayName.equals("输出", ignoreCase = true)
                            } ?: false
                            // M36.5.16：hasResult 不足以判定正常——连接失败（Access denied）时旧
                            // 结果 Tab 仍在（用户实测 SQL 已改而表格停留旧数据），查询挂起无任何
                            // 提示。补连接健康检查：getCurrentNamespace() 为空 = 连接未建立
                            //（工具栏数据源显示 <schema> 佐证），即使有旧 Tab 也提示。
                            val namespaceOk = runCatching {
                                val ns = consoleForWatch.javaClass
                                    .getMethod("getCurrentNamespace").invoke(consoleForWatch)
                                ns != null && ns.toString().isNotBlank()
                            }.getOrDefault(true)
                            if (!hasResult || !namespaceOk) {
                                LOG.info("[M36] no result tab within 8s (hasResult=$hasResult, namespaceOk=$namespaceOk) - panel hint")
                                hintOnError.invoke(MpSqlVisualizerBundle.message("execute.credential.hint"))
                            }
                        }
                        watch.isRepeats = false
                        watch.start()
                    }, com.intellij.openapi.application.ModalityState.any())
                }
                ApplicationManager.getApplication().executeOnPooledThread {
                    var executionStarted = false
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
                            if (freshModel != null && statementsInfo(freshModel).startsWith("count=0")) {
                                LOG.warn("[M36] vf PSI model has 0 statements, fallback to console scriptModel")
                                freshModel = null
                            }
                            if (freshModel == null) {
                                freshModel = runCatching {
                                    consoleClass.getMethod("getScriptModel").invoke(console)
                                }.getOrNull() ?: scriptModel
                            }
                            if (freshModel !== scriptModel) {
                                preparedScriptModel = freshModel
                                if (sharedConsole === console) sharedScriptModel = freshModel
                            }
                            LOG.info("[M36] executing model: class=${freshModel.javaClass.simpleName}, " +
                                "stmts=${statementsInfo(freshModel)}, vfPsiText tail=...${
                                    vfPsi?.text?.replace("\n", " ")?.takeLast(30)
                                }")
                            // M36.5.21c：执行链拆两段——vf PSI/model 探针在本 ReadAction 内；
                            // **原生 EDT 链挪到 ReadAction 外**（pooled 持读锁等 EDT、EDT 任务若需
                            // write 会死锁）。详见下方原生链处注释。
                            val vfPsiFinal = vfPsi
                            val editorFinal = editor
                            val execOptionFinal = execOption
                            var nativeRun = false
                            if (vfPsi != null) {
                                val providerClass = Class.forName(CONSOLE_PROVIDER_CLASS)
                                val findScriptModel = providerClass.getMethod(
                                    "findScriptModel",
                                    PsiFile::class.java,
                                    com.intellij.psi.PsiElement::class.java,
                                    com.intellij.openapi.editor.Editor::class.java,
                                    execOption.javaClass,
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
                                    // （面板关闭取消挂起事务）。不阻塞等待：pooled 持读锁等 EDT 会死锁；
                                    // 提交后结果由 retry/contentAdded 链兜底换入。
                                    ApplicationManager.getApplication().invokeLater({
                                        val disposable = (console as? com.intellij.openapi.Disposable)
                                            ?: com.intellij.openapi.Disposable { }
                                        try {
                                            com.intellij.openapi.application.TransactionGuard.getInstance()
                                                .submitTransactionLater(disposable) {
                                                    try {
                                                        val info = findScriptModel.invoke(null, vfPsi, vfPsi, editor, execOption)
                                                        if (info == null) {
                                                            LOG.warn("[M36] native findScriptModel returned null (nothing to run?)")
                                                            return@submitTransactionLater
                                                        }
                                                        LOG.info("[M36] native findScriptModel OK, doRunQueryInConsole")
                                                        doRunMethod.invoke(null, console, info)
                                                    } catch (t: Throwable) {
                                                        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                                                        LOG.warn(
                                                            "[M36] native run chain FAILED: ${cause.javaClass.name}: ${cause.message}\n    " +
                                                                cause.stackTrace.take(8).joinToString("\n    at ") { it.toString() },
                                                            cause,
                                                        )
                                                    }
                                                }
                                        } catch (t: Throwable) {
                                            LOG.warn("[M36] submitTransactionLater FAILED: ${t.javaClass.simpleName}: ${t.message}", t)
                                        }
                                    }, com.intellij.openapi.application.ModalityState.any())
                                    nativeRun = true
                                }
                            }
                            if (!nativeRun) {
                                execute.invoke(console, editor, freshModel, execOption)
                            }
                        }
                        executionStarted = true
                        LOG.info("[M36] executeQueries invoked OK")
                    } catch (e: Exception) {
                        LOG.warn("[M36] executeQueries FAILED: ${e.message}", e)
                        // 执行触发失败：通知调用方（UI 回调须在 EDT）
                        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                        val msg = cause.message ?: cause.toString()
                        // M36.5.13：ModalityState.any()——模态弹窗（如 File Cache Conflict/密码框）
                        // 打开期间默认 modality 的 invokeLater 冻结（沙盒 12:10:02→12:10:50 实证
                        // 48s 挂起），换入/重试/提示全不跑 → "执行中"卡住。any() 使任务在模态
                        // pump 中照常执行（纯 Swing 更新，安全）。
                        ApplicationManager.getApplication().invokeLater({
                            onError?.invoke(msg)
                        }, com.intellij.openapi.application.ModalityState.any())
                        return@executeOnPooledThread
                    }
                    // 执行触发成功后把右侧从「仅顶部块」切换为「顶部块+结果区」完整 UI
                    //（用户诉求：Console output 占位只在执行后出现）。换入组件按需构建并缓存——
                    // 仅准备路径预建的 SplitPane 已因顶部块内嵌被"偷"走子组件，不可复用；
                    // 已备实例（全链路径内嵌过）直接复用 + 面板同实例防抖，重复执行不重建。
                    // invoke 触发失败（含密码弹窗等待超时）不换入——结果区保留到真实执行后出现。
                    // **JSplitPane 构造（buildConsoleSplitPane）与换入均须 EDT**——M36.5.10 曾把
                    // buildConsoleSplitPane 留在 pooled thread，JSplitPane.setLeftComponent 触发
                    // 编辑器 hierarchy 事件 → SoftWrapApplianceManager → ScrollingModelImpl
                    // .getVisibleArea → ThreadingAssertions 抛异常（用户堆栈实锤），异常阻断
                    // onResultsReady/startResultTabRetry → 结果区不刷新、提示不显示。整个
                    // 「构建+换入」必须包在同一 invokeLater 中。
                    if (executionStarted && onResultsReady != null) {
                        val top = preparedTopBlock
                        val result = preparedResultComponent
                        if (top != null && result != null) {
                            val consoleForPrompt = console
                            ApplicationManager.getApplication().invokeLater({
                                val full = preparedFullComponent ?: buildConsoleSplitPane(top, result)
                                    .also { preparedFullComponent = it }
                                onResultsReady.invoke(full)
                                // M36.5.13：执行后清提示符（DatabaseLogView 在执行/数据渲染时重设）
                                clearConsolePrompt(consoleForPrompt, LOG)
                            }, com.intellij.openapi.application.ModalityState.any())
                        }
                    }
                    // 结果 Tab 默认激活：invoke 返回（真实提交完成）后启动（结果 Tab 异步创建）。
                    // 连接等待期由上方的 8s 监控覆盖（避免重复执行时命中旧 Tab 换入旧数据）。
                    if (executionStarted && ui != null) {
                        ApplicationManager.getApplication().invokeLater({
                            startResultTabRetry(project, ui, onError)
                        }, com.intellij.openapi.application.ModalityState.any())
                    }
                }
            }
        }.onFailure { e ->
            LOG.warn("[M36] executeOnConsole FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            onError?.invoke(cause.message ?: cause.toString())
        }
    }

    /**
     * 结果 Tab 激活重试（EDT Timer 400ms 非阻塞；结果 Tab 异步创建）。
     * M36.5.3 dumb-aware：dumb mode（索引）期间查询提交可能被推迟（沙盒实证：方言 push-all
     * 与连接建立延至索引完成）——索引中把重试窗口从 10 次（4s）放宽到 40 次（16s）；
     * 超时后按状态给准确提示：索引中 → 索引提示（execute.indexing.hint），
     * 否则 → 检查被遮挡的密码/驱动弹窗（execute.credential.hint）。
     */
    private fun startResultTabRetry(
        project: Project,
        ui: Any,
        onError: ((String) -> Unit)? = null,
    ) {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        val selectRetry = object : java.awt.event.ActionListener {
            var attempts = 0
            val timer = javax.swing.Timer(400, null)
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                attempts++
                val contents = runCatching {
                    ui.javaClass.getMethod("getContents").invoke(ui) as? Array<com.intellij.ui.content.Content>
                }.getOrNull() ?: emptyArray()
                val result = contents.firstOrNull {
                    !it.displayName.equals("Output", ignoreCase = true) &&
                        !it.displayName.equals("输出", ignoreCase = true)
                }
                LOG.info("[M36] retry#$attempts contents=${contents.map { it.displayName }}, select=${result?.displayName}")
                if (result != null) {
                    timer.stop()
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
                    refreshResultsComponent(ui, LOG, forceSwapIn = true)
                } else {
                    // M36.5.3：索引中放宽窗口（查询提交可能被 dumb mode 推迟）；连接等待期由
                    // 提交前的 8s 监控覆盖（M36.5.15），此处窗口保持原语义。
                    val dumb = runCatching { com.intellij.openapi.project.DumbService.isDumb(project) }.getOrDefault(false)
                    val limit = if (dumb) 40 else 10
                    if (attempts >= limit) {
                        timer.stop()
                        LOG.info("[M36] result tab not appeared after $attempts attempts (dumb=$dumb)")
                        // M36.5.14：提示改走 onError（**面板内显示**，resultPanel.showError）——
                        // 此前 Messages.showWarningDialog 弹窗也会被模态预览窗口遮挡（用户实测
                        // "关闭窗口才出现提示"）；面板内红字必然可见，且不叠加新模态。
                        // ModalityState.any() 保证模态弹窗（如密码框）期间提示照常落地。
                        val hint = MpSqlVisualizerBundle.message(
                            if (dumb) "execute.indexing.hint" else "execute.credential.hint",
                        )
                        ApplicationManager.getApplication().invokeLater({
                            onError?.invoke(hint)
                        }, com.intellij.openapi.application.ModalityState.any())
                    }
                }
            }
            init { timer.addActionListener(this) }
        }
        selectRetry.timer.start()
    }

    /** M36.5：读取 last-used 数据源名（prepareConsole 静默复用；异常兜底 null）。 */
    private fun lastRunnerName(project: Project): String? = runCatching {
        com.intellij.ide.util.PropertiesComponent.getInstance(project).getValue(LAST_RUNNER_KEY)
    }.getOrNull()

    /** M36.5：记录 last-used 数据源名。 */
    private fun rememberRunnerName(project: Project, name: String) {
        runCatching {
            com.intellij.ide.util.PropertiesComponent.getInstance(project).setValue(LAST_RUNNER_KEY, name)
        }
    }

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

    /** 反射调用 Runner.run：建 session → consumer 回调续接执行。失败（常见：驱动未下载）→ 弹错误引导。 */
    private fun runRunner(runner: Any) {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        runCatching {
            val method = runner.javaClass.getMethod("run")
            method.isAccessible = true
            method.invoke(runner)
            LOG.info("[M36] runRunner invoked OK")
        }.onFailure { e ->
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            LOG.warn("[M36] runRunner FAILED: ${cause.javaClass.simpleName}: ${cause.message}", cause)
            // 驱动缺失/连接失败等：提示用户到数据源设置解决（原生 Download Driver 弹窗由 Database 插件触发）
            val project = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull() ?: return
            com.intellij.openapi.ui.Messages.showErrorDialog(
                project,
                MpSqlVisualizerBundle.message("execute.datasource.failed", cause.message ?: cause.toString()),
                MpSqlVisualizerBundle.message("execute.title"),
            )
        }
    }

    /**
     * 弹窗展示 Runner 列表（图标 + 数据源名称，原生 BaseListPopupStep 单击即选）。
     */
    private fun showRunnerPopup(project: Project, runners: List<Any>, callback: (Any?) -> Unit) {
        val items = runners.map { RunnerItem(it, getRunnerName(it), getRunnerIcon(it)) }
        val step = object : com.intellij.openapi.ui.popup.util.BaseListPopupStep<RunnerItem>(
            MpSqlVisualizerBundle.message("execute.datasource.select"),
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
     */
    @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
    private fun createTempPsiFile(project: Project, sql: String): Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>? {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        return runCatching {
            // M36.5.12：临时文件改存项目内 `.idea/mybatis-plus-sql-visualizer/exec/`
            //（参考 sql-history 保存位置），不再用系统 Temp——Temp 文件不在数据源关联范围，
            // 导致 IDE 报 "Unable to resolve table"（M36.5.11 问题一，日志实证挂载点在
            // file:///C:/Users/USER/AppData/Local/Temp/）。项目内文件可被 VFS 追踪、可被
            // Database 插件解析。文件名稳定 mpsql-exec.sql（每次覆盖，不膨胀）。
            val ioFile = projectExecFile(project, sql) ?: return null
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
                ?: return null
            mapDialectBeforePsi(project, vf, LOG)
            val psi = ReadAction.compute<PsiFile, Throwable> {
                PsiManager.getInstance(project).findFile(vf)
            } ?: return null
            vf to psi
        }.getOrNull()
    }

    /**
     * M36.5.12：项目内执行文件路径——`<projectBase>/.idea/mybatis-plus-sql-visualizer/exec/mpsql-exec.sql`。
     * 目录不存在则创建；文件写入 SQL（覆盖）。与 sql-history 同根（.idea/mybatis-plus-sql-visualizer/），
     * 但**独立子目录** exec（sql-history 读取器只扫 sql-history 子目录，互不污染）。
     *
     * M36.5.13：**VFS 安全写入**——文件已在 VFS（上次预览创建过，console 编辑器持有其 document）
     * 时，直接 FileUtil.writeToFile 覆盖磁盘会触发 "File Cache Conflict" 模态弹窗
     * （MemoryDiskConflictResolver，沙盒日志 12:08:06 实证），弹窗期间默认 modality 的
     * invokeLater 全部冻结 → 执行链换入/重试挂起 → "执行中"卡住、结果区停在旧数据。
     * 改为：vf 已存在 → WriteAction 里 setBinaryContent（VFS/document/磁盘三方同步）；
     * 不存在 → 写盘后由调用方 refresh。
     */
    private fun projectExecFile(project: Project, sql: String): java.io.File? {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
        val base = project.basePath ?: return null
        val dir = java.io.File(base, ".idea${java.io.File.separator}mybatis-plus-sql-visualizer${java.io.File.separator}exec")
        if (!dir.exists()) dir.mkdirs()
        val f = java.io.File(dir, "mpsql-exec.sql")
        val existing = LocalFileSystem.getInstance().findFileByIoFile(f)
        if (existing != null) {
            val updated = runCatching {
                @Suppress("DEPRECATION") // T-M52：WriteAction.compute(ThrowableComputable) 243 编译目标仅此重载可用
                com.intellij.openapi.application.WriteAction.compute<Unit, Throwable> {
                    existing.setBinaryContent(sql.toByteArray())
                }
                true
            }.getOrDefault(false)
            if (updated) {
                LOG.info("[M36] exec file updated via VFS (no cache conflict): ${f.name}")
                return f
            }
            // WriteAction 失败（非 EDT 等）→ 兜底直接写盘（可能 conflict，但不阻塞链路）
        }
        FileUtil.writeToFile(f, sql)
        return f
    }

    /**
     * M36.5.3：临时文件方言预映射（MySQL）——须在 [PsiManager.findFile] 之前调用，
     * PSI 创建时即按映射解析为 MySQL（否则走 SqlDialectPusher，dumb mode 下被推迟）。
     * 方言实例经 SqlDialectMappings.getAvailableValues() 按 Language.id 匹配（不硬编码实现类），
     * 任何环节失败静默跳过（保持原行为，仅记录日志）。
     */
    private fun mapDialectBeforePsi(project: Project, vf: com.intellij.openapi.vfs.VirtualFile, LOG: com.intellij.openapi.diagnostic.Logger) {
        runCatching {
            val mappingsClass = Class.forName("com.intellij.sql.dialects.SqlDialectMappings")
            val mappings = mappingsClass.getMethod("getInstance", Project::class.java).invoke(null, project)
            @Suppress("UNCHECKED_CAST")
            val values = mappingsClass.getMethod("getAvailableValues").invoke(mappings) as? List<Any?>
            val mysql = values?.firstOrNull {
                (it as? com.intellij.lang.Language)?.id.equals("MySQL", ignoreCase = true)
            }
            if (mysql != null) {
                mappingsClass.getMethod(
                    "setMapping",
                    com.intellij.openapi.vfs.VirtualFile::class.java,
                    Any::class.java,
                ).invoke(mappings, vf, mysql)
                LOG.info("[M36] dialect pre-mapped to MySQL for ${vf.name}")
            } else {
                LOG.warn("[M36] MySQL language dialect not found in SqlDialectMappings (fallback to pusher)")
            }
        }.onFailure { e ->
            LOG.warn("[M36] dialect pre-mapping skipped: ${e.message}")
        }
    }

    /** 打开 Database 工具窗口（无配置数据源时引导用户）。 */
    private fun openDatabaseToolWindow(project: Project) {
        runCatching {
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("Database")
                ?.show()
        }
    }
}
