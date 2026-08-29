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
        logDataContextCheck(full, LOG)
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
                    val callback = pendingOnConsole ?: return
                    pendingOnConsole = null
                    // 换入时重新取结果区组件——RunnerLayoutUi 可能在面板分离/重挂后重建组件，
                    // prepare 时捕获的引用可能已非当前展示实例（M36.5.5 复用面板空显示的疑因）
                    val liveResult = runCatching {
                        ui.javaClass.getMethod("getComponent").invoke(ui) as? javax.swing.JComponent
                    }.getOrNull() ?: resultComponent
                    val full = fullCache
                        ?: buildConsoleSplitPane(topBlock, liveResult).also {
                            fullCache = it
                            preparedFullComponent = it
                        }
                    // M36.5.7：写回共享缓存——否则下次复用嵌的是换入前的旧编辑器块
                    //（M36.5.6 第二次预览结果栏消失根因）
                    sharedTopBlock = topBlock
                    sharedFullComponent = full
                    ApplicationManager.getApplication().invokeLater { callback.invoke(full) }
                }
            })
            LOG.info("[M36] results hook attached (contentAdded -> embed results)")
        }.onFailure { e ->
            LOG.warn("[M36] results hook attach FAILED: ${e.message}")
        }
    }

    /**
     * M36.5.6：嵌入后自检数据上下文——原生 ▶ 执行按钮启用链（QueryActionBase：EDITOR +
     * JdbcConsole.findConsole 的 CLIENT_KEY 分支）经 DataManager 在嵌入块上解析验证，
     * 日志直接给出两键可达性（此前 ▶ 禁用只能靠截图灰态间接判断）。
     */
    private fun logDataContextCheck(editorBlock: javax.swing.JComponent, LOG: com.intellij.openapi.diagnostic.Logger) {
        ApplicationManager.getApplication().invokeLater {
            runCatching {
                val ctx = com.intellij.ide.DataManager.getInstance()
                    .getDataContext(editorBlock)
                val editorOk = ctx.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR.name) != null
                val clientKeyName = runCatching {
                    (Class.forName("com.intellij.database.console.client.SessionClientHolder")
                        .getField("CLIENT_KEY").get(null)
                            as com.intellij.openapi.actionSystem.DataKey<*>).name
                }.getOrNull()
                val consoleOk = clientKeyName != null && ctx.getData(clientKeyName) != null
                LOG.info("[M36] dataContext check: EDITOR=$editorOk, CLIENT_KEY(console)=$consoleOk (key=$clientKeyName)")
            }.onFailure { e ->
                LOG.warn("[M36] dataContext check FAILED: ${e.message}")
            }
        }
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
            val execOption = execOptionClass.getDeclaredConstructor().newInstance()
            if (scriptModel == null) return

            // M36.5：缓存已备控制台（executePrepared 消费）
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
            val psiForData = runCatching {
                @Suppress("DEPRECATION")
                ReadAction.compute<PsiFile, Throwable> {
                    PsiManager.getInstance(project).findFile(vf)
                }
            }.getOrNull()
            val editorComponent: javax.swing.JComponent =
                object : com.intellij.ui.components.JBPanel<com.intellij.ui.components.JBPanel<*>>(
                    java.awt.BorderLayout(),
                ), com.intellij.openapi.actionSystem.DataProvider {
                    override fun getData(dataId: String): Any? = when {
                        com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR.`is`(dataId) -> editorForData
                        dataId == clientKeyName -> consoleForData
                        com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE.`is`(dataId) -> vfForData
                        com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE.`is`(dataId) -> psiForData
                        else -> null
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
            val component: javax.swing.JComponent = if (executeAfterPrepare) (fullComponent ?: topBlock) else topBlock
            preparedTopBlock = topBlock
            preparedResultComponent = resultComponent
            preparedFullComponent = if (executeAfterPrepare) fullComponent else null
            LOG.info("[M36] embed component=$component (executeAfterPrepare=$executeAfterPrepare)")

            if (!executeAfterPrepare) {
                pendingOnConsole = onConsole
                attachResultsHook(ui, topBlock, resultComponent, LOG)
                logDataContextCheck(editorComponent, LOG)
            }

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

            // 后台线程执行，带超时检测
            var executionStarted = false
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            val future = executor.submit {
                try {
                    ReadAction.compute<Unit, Throwable> {
                        execute.invoke(console, editor, scriptModel, execOption)
                    }
                    executionStarted = true
                    LOG.info("[M36] executeQueries invoked OK")
                } catch (e: Exception) {
                    LOG.warn("[M36] executeQueries FAILED: ${e.message}", e)
                    throw e
                }
            }
            executor.shutdown()

            // 等待执行完成，带超时（5秒）
            // 超时意味着可能弹出了密码/驱动弹窗（被面板遮挡），不取消 future，
            // 让它在后台继续等待用户处理弹窗
            try {
                future.get(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                LOG.warn("[M36] executeQueries TIMEOUT (5s) - may need credentials or driver download")
            } catch (e: Exception) {
                LOG.warn("[M36] executeQueries FAILED: ${e.message}", e)
            }

            // M36.5.2：执行触发成功后把右侧从「仅顶部块」切换为「顶部块+结果区」完整 UI
            //（用户诉求：Console output 占位只在执行后出现）。换入组件按需构建并缓存——
            // 仅准备路径预建的 SplitPane 已因顶部块内嵌被"偷"走子组件，不可复用；
            // 已备实例（全链路径内嵌过）直接复用 + 面板同实例防抖，重复执行不重建。
            // invoke 触发失败（含密码弹窗等待超时）不换入——结果区保留到真实执行后出现。
            if (executionStarted && onResultsReady != null) {
                val top = preparedTopBlock
                val result = preparedResultComponent
                if (top != null && result != null) {
                    val full = preparedFullComponent ?: buildConsoleSplitPane(top, result)
                        .also { preparedFullComponent = it }
                    ApplicationManager.getApplication().invokeLater {
                        onResultsReady.invoke(full)
                    }
                }
            }

            // 结果 Tab 默认激活：EDT Timer 非阻塞重试（结果 Tab 异步创建；10×400ms）
            if (ui != null) {
                startResultTabRetry(project, ui)
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
    private fun startResultTabRetry(project: Project, ui: Any) {
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
                } else {
                    // M36.5.3：索引中放宽窗口（查询提交可能被 dumb mode 推迟）
                    val dumb = runCatching { com.intellij.openapi.project.DumbService.isDumb(project) }.getOrDefault(false)
                    val limit = if (dumb) 40 else 10
                    if (attempts >= limit) {
                        timer.stop()
                        LOG.info("[M36] result tab not appeared after $attempts attempts (dumb=$dumb)")
                        ApplicationManager.getApplication().invokeLater {
                            if (dumb) showIndexingHint(project) else showCredentialHint(project)
                        }
                    }
                }
            }
            init { timer.addActionListener(this) }
        }
        selectRetry.timer.start()
    }

    /**
     * M36.3：显示连接提示（密码过期/驱动未下载时，原生弹窗可能被面板遮挡）。
     */
    private fun showCredentialHint(project: Project) {
        runCatching {
            com.intellij.openapi.ui.Messages.showWarningDialog(
                project,
                MpSqlVisualizerBundle.message("execute.credential.hint"),
                MpSqlVisualizerBundle.message("execute.title"),
            )
        }
    }

    /**
     * M36.5.3：显示索引中提示（dumb mode 下查询提交被推迟——沙盒实证，非执行失败；
     * 与密码/驱动问题区分，避免误报打断）。
     */
    private fun showIndexingHint(project: Project) {
        runCatching {
            com.intellij.openapi.ui.Messages.showWarningDialog(
                project,
                MpSqlVisualizerBundle.message("execute.indexing.hint"),
                MpSqlVisualizerBundle.message("execute.title"),
            )
        }
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
            val ioFile = FileUtil.createTempFile("mpsql-exec-", ".sql", true)
            FileUtil.writeToFile(ioFile, sql)
            ioFile.deleteOnExit()
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
