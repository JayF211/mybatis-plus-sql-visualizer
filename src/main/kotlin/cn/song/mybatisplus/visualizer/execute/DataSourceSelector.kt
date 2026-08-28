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
     * @return false = 控制台未就绪（调用方回退全链 selectAndExecute）
     */
    @JvmStatic
    fun executePrepared(project: Project, onError: ((String) -> Unit)? = null): Boolean {
        val console = preparedConsole ?: return false
        val editor = preparedEditor ?: return false
        val scriptModel = preparedScriptModel ?: return false
        val execOption = preparedExecOption ?: return false
        executeOnConsole(project, console, editor, scriptModel, execOption, preparedUi, onError)
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
        val sessionConsumer = com.intellij.util.Consumer<Any> { session ->
            LOG.info("[M36] session created: ${session?.javaClass?.name}, attaching console (execute=$executeAfterPrepare)")
            prepareInConsole(project, session, vf, executeAfterPrepare, onConsole, onEditor, onError)
        }

        val runners = getAttachDataSourceRunners(psiFile, sql, sessionConsumer)
        LOG.info("[M36] runners=${runners.size}")

        // M36.5：last-used 数据源静默命中（仅 prepareConsole 路径；显式执行保持弹窗可选）
        if (!executeAfterPrepare) {
            val last = lastRunnerName(project)
            if (last != null) {
                runners.firstOrNull { getRunnerName(it) == last }?.let { matched ->
                    LOG.info("[M36] silent reuse last runner: $last")
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
                runRunner(runners.first())
                callback?.invoke(true)
            }
            else -> {
                LOG.info("[M36] show popup with ${runners.size} runners")
                showRunnerPopup(project, runners) { runner ->
                    LOG.info("[M36] popup selected: ${runner?.let { getRunnerName(it) }}")
                    // M36.5：记住选择（prepareConsole 路径下次静默复用）
                    if (!executeAfterPrepare && runner != null) {
                        rememberRunnerName(project, getRunnerName(runner))
                    }
                    callback?.invoke(runner != null)
                    if (runner != null) runRunner(runner)
                }
            }
        }
    }

    /**
     * 准备链续接（consumer 回调内调用）：session → attachConsole → 编辑器/scriptModel →
     * 缓存已备控制台 → 拼合组件回调 →（可选）立即执行。
     *
     * 反编译实锤：logView.getContent().getComponent() = RunnerLayoutUi（仅结果 tabs），不含编辑器。
     * 编辑器组件 = editor.component（EditorImpl）。拼合：垂直 SplitPane（编辑器 40% / 结果区 60%）。
     */
    private fun prepareInConsole(
        project: Project,
        session: Any,
        vf: com.intellij.openapi.vfs.VirtualFile,
        executeAfterPrepare: Boolean,
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

            // 4. 拼合编辑器 + 结果区为垂直 SplitPane（M36.4 方案，SplitPane 布局撑起编辑器）
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
            val editorComponent: javax.swing.JComponent? = editor?.component
            LOG.info("[M36] resultComponent=$resultComponent, editorComponent=$editorComponent")

            // 4.4 拼合：编辑器（上）/ 结果区（下）——任一缺失单独内嵌另一块
            val component: javax.swing.JComponent = when {
                editorComponent != null && resultComponent != null ->
                    object : javax.swing.JSplitPane(
                        javax.swing.JSplitPane.VERTICAL_SPLIT, editorComponent, resultComponent,
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
                editorComponent != null -> editorComponent
                resultComponent != null -> resultComponent
                else -> return
            }
            LOG.info("[M36] combined component=$component")

            // 4.5 内嵌（同步，已在 EDT）
            onConsole?.invoke(component)

            // 5. 可选立即执行（selectAndExecute 全链语义）
            if (executeAfterPrepare) {
                executeOnConsole(project, console, editor, scriptModel, execOption, ui, onError)
            }
        }.onFailure { e ->
            LOG.warn("[M36] prepareInConsole FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            // M36.3：通知调用方错误，确保 UI 从「执行中」状态恢复
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            onError?.invoke(cause.message ?: cause.toString())
        }
    }

    /**
     * M36.5：在指定控制台上执行 SQL（后台线程 + ReadAction + 5s 超时 + 结果 Tab 激活重试）。
     * 由 [executePrepared]（已备控制台）与准备全链（executeAfterPrepare=true）共用。
     */
    private fun executeOnConsole(
        project: Project,
        console: Any,
        editor: com.intellij.openapi.editor.Editor,
        scriptModel: Any,
        execOption: Any,
        ui: Any?,
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
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            val future = executor.submit {
                try {
                    ReadAction.compute<Unit, Throwable> {
                        execute.invoke(console, editor, scriptModel, execOption)
                    }
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

    /** 结果 Tab 激活重试（EDT Timer 10×400ms；超时后提示检查被遮挡的密码/驱动弹窗）。 */
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
                } else if (attempts >= 10) {
                    timer.stop()
                    LOG.info("[M36] result tab not appeared after $attempts attempts (may need credentials or query failed)")
                    // M36.3：超时后提示用户检查是否有被遮挡的密码/驱动弹窗
                    ApplicationManager.getApplication().invokeLater {
                        showCredentialHint(project)
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
     */
    private fun createTempPsiFile(project: Project, sql: String): Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>? {
        return runCatching {
            val ioFile = FileUtil.createTempFile("mpsql-exec-", ".sql", true)
            FileUtil.writeToFile(ioFile, sql)
            ioFile.deleteOnExit()
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
                ?: return null
            val psi = PsiManager.getInstance(project).findFile(vf) ?: return null
            vf to psi
        }.getOrNull()
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
