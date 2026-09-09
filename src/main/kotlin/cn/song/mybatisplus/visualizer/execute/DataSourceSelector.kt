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
    private const val BUILD_STAMP = "M36.7-hql-select-expand"

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

    /** T-PreviewFlash：mpsql-exec.sql 的 vf+psi 配对缓存（路径稳定，vf 有效即复用，吸收每次预览的 VFS refresh + findFile 慢操作）。 */
    private var cachedTempPsiFile: Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>? = null

    /**
     * T-ExecCopyFix F2a：最近一次 createTempPsiFile 失败的阶段原因（stage 标识或异常摘要），
     * 成功时清空——供 chooseAndPrepare 的 onError 文案携带，用户截图反馈的笼统
     * "Failed to create temporary SQL file" 自此可归因。
     */
    @Volatile
    private var lastTempFileFailure: String? = null

    /** T-PreviewFlash：已 setMapping 的 vf（同文件幂等短路，避免每次预览重设方言触发 push all dumb）。 */
    private var dialectMappedVf: com.intellij.openapi.vfs.VirtualFile? = null

    /** M36.5.6：结果区换入回调（准备阶段挂 ContentManager 监听，任一路径出结果即换入；复用时由新面板接管）。 */
    private var pendingOnConsole: ((javax.swing.JComponent) -> Unit)? = null

    /** M36.5.9：最近一次换入宿主的结果区组件实例（执行后重取刷新的去重依据）。 */
    private var lastLiveResultSwapped: java.awt.Component? = null

    /** M36.5.52：最近一次已上报的 SQL 错误文本——早检去重用（避免 console history 里上次
     * 执行的残留错误每次早检重复上报；同一错误用户已见过则不再打扰）。 */
    private var lastReportedError: String? = null

    /** M36.5.57：本次执行开始时 console history 的长度基线——早检只扫**本次执行之后**新增的
     * 文本，忽略 history 里的旧错误残留（否则正常 SQL 会把上个失败的错误"带过来"，用户实测）。 */
    private var consoleErrorBaseline: Int = 0

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
                    runRunner(matched, silent = true)
                    callback?.invoke(true)
                    return
                }
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
                runRunner(runners.first(), silent = !executeAfterPrepare)
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
                    val fileDoc = com.intellij.openapi.fileEditor.FileDocumentManager
                        .getInstance().getDocument(consoleVf)
                    when {
                        fileDoc != null && fileDoc.text != sql -> {
                            fileDoc.setText(sql)
                            com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(fileDoc)
                        }
                        fileDoc != null -> com.intellij.psi.PsiDocumentManager.getInstance(project)
                            .commitDocument(fileDoc)
                        fileDoc == null -> consoleVf.setBinaryContent(sql.toByteArray())
                    }
                }
                // M36.5.24g：dumb 退出后重建（同 prepareInConsole）
                val consoleForModel = console
                val vfForModel = consoleVf
                com.intellij.openapi.project.DumbService.getInstance(project).runWhenSmart {
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
            executeOnConsole(project, console, editor, refreshedModel ?: scriptModel, execOption, sharedUi, onResultsReady = null, onError = onError)
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
     * M36.5.48：从 console 历史文档提取真实错误文本——仅当**超时无结果**（真失败/真超时）时
     * 调用。DBN 查询执行失败（SQL 语法错/表不存在等）会把错误消息写入 console history。
     *
     * 提取规则（防误判）：只认**明确的 SQL 错误签名**（[ERROR_SIGNATURES]），且错误行不得是
     * 纯 SQL 语句（含 SELECT/FROM/WHERE/JOIN 等 SQL 词），避免把正常 SQL 历史误判为错误
     * （日志实证 `extracted console error: WHERE t.id = 1`——A 并无真失败，正常 SQL 片段被误报）。
     *
     * M36.5.57：只扫 **consoleErrorBaseline 之后**的文本——历史残留错误（上个失败 SQL 写入的
     * Unknown column 等）不在本次范围，正常 SQL 执行后不会"带过来"旧错误。
     */
    private fun extractConsoleError(console: Any?, LOG: com.intellij.openapi.diagnostic.Logger): String? {
        if (console == null) return null
        return runCatching {
            val consoleView = console.javaClass.getMethod("getConsoleView").invoke(console) ?: return null
            val historyViewer = consoleView.javaClass.getMethod("getHistoryViewer").invoke(consoleView) ?: return null
            val doc = historyViewer.javaClass.getMethod("getDocument").invoke(historyViewer)
                ?: return null
            var text = doc.javaClass.getMethod("getText").invoke(doc) as? String
                ?: return null
            // M36.5.57：只扫本次执行之后新增的文本（基线截断）——忽略 history 残留的旧错误。
            // baseline 可能因 DBN 写入历史而增长；若文本长度 <= 基线说明无新增内容，return null。
            val baseline = consoleErrorBaseline
            if (baseline > 0 && text.length > baseline) {
                text = text.substring(baseline)
            } else if (baseline > 0) {
                return null
            }
            if (text.isBlank()) return null
            val lines = text.lines().filter { it.isNotBlank() }
            // 从末尾往前找"明确错误签名"且"非纯 SQL"的行
            val errorLine = lines.lastOrNull { line ->
                ERROR_SIGNATURES.any { line.contains(it, ignoreCase = true) } && !isSqlLine(line)
            } ?: return null
            // 取错误行及其后非空行构成可读上下文（错误消息通常多行，后随详情）
            val idx = lines.indexOf(errorLine)
            val excerpt = lines.subList(idx, (idx + 4).coerceAtMost(lines.size)).joinToString("\n")
            LOG.info("[M36] extracted console error: ${excerpt.take(160)}")
            excerpt
        }.getOrNull()
    }

    /** M36.5.48：明确 SQL 错误签名（防误报——只认这些才算真错误，而非正常 SQL 历史）。 */
    private val ERROR_SIGNATURES = listOf(
        "sql exception", "syntax error", "sqlstate", "unknown column",
        "unknown table", "doesn't exist", "does not exist", "access denied",
        "deadlock", "error code", "broken pipe", "connection refused",
        "[\u65e5\u8bed\u9519\u8bef]", "sql\uff08", "exception in thread",
    )

    /** M36.5.48：判断一行是否纯 SQL 语句（避免把「WHERE t.id=1」等当错误）。 */
    private fun isSqlLine(line: String): Boolean {
        val lower = line.trim().lowercase()
        // 含 SQL 关键字且无错误签名增强词 → 大概率是 SQL 文本而非错误
        return (lower.startsWith("select") || lower.startsWith("from") ||
            lower.startsWith("where") || lower.startsWith("join") ||
            lower.startsWith("insert") || lower.startsWith("update") ||
            lower.startsWith("delete") || lower.startsWith("create") ||
            lower.startsWith("alter") || lower.startsWith("drop")) &&
            !ERROR_SIGNATURES.any { line.contains(it, ignoreCase = true) }
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
     */
    private fun writeConsoleOutput(console: Any?, text: String, isError: Boolean = false) {
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
    private fun refreshResultsComponent(ui: Any, LOG: com.intellij.openapi.diagnostic.Logger, forceSwapIn: Boolean = false, generation: Int = swapGeneration, onError: ((String) -> Unit)? = null) {
        val callback = pendingOnConsole ?: return
        val top = sharedTopBlock ?: preparedTopBlock ?: return
        val shared = sharedConsole
        var ticks = 0
        var swapped = false
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
                        ApplicationManager.getApplication().invokeLater({
                            writeConsoleOutput(shared ?: preparedConsole, "执行失败: $err\n", isError = true)
                            onError?.invoke(err)
                        }, com.intellij.openapi.application.ModalityState.any())
                        return
                    }
                // M36.5.13：每拍清提示符（压制执行后重设）
                if (shared != null) clearConsolePrompt(shared, LOG)
                val live = runCatching {
                    ui.javaClass.getMethod("getComponent").invoke(ui) as? javax.swing.JComponent
                }.getOrNull()
                val shouldSwap = live != null && (forceSwapIn && !swapped || live !== lastLiveResultSwapped)
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
                    LOG.info("[M36] results refreshed (tick=$ticks, forced=$forceSwapIn, instanceChanged=$changed, gen=$generation)")
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
                    LOG.info(
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
                        val fileDoc = com.intellij.openapi.fileEditor.FileDocumentManager
                            .getInstance().getDocument(consoleVf)
                        when {
                            fileDoc != null && fileDoc.text != consoleText -> {
                                fileDoc.setText(consoleText)
                                com.intellij.psi.PsiDocumentManager.getInstance(project)
                                    .commitDocument(fileDoc)
                            }
                            fileDoc != null -> com.intellij.psi.PsiDocumentManager.getInstance(project)
                                .commitDocument(fileDoc)
                            fileDoc == null -> consoleVf.setBinaryContent(consoleText.toByteArray())
                        }
                    }
                    // M36.5.24g：fresh 构造 + set 移入 DumbService.runWhenSmart——22:30 实证：
                    // attachConsole 后 SqlDialectPusher 的 dumb task（58.443 运行 → 58.714 exit）
                    // 触发 PSI 重建；在 dumb mode 中构造的 fresh（58.302，stmts=1）被 push 后
                    // PSI 重建击穿 → 语句 range=null（SIt(null)）→ statementsRange 零长度。
                    // runWhenSmart 保证 dialect push + PSI 完整后再构造 → 语句 range 非 null。
                    val consoleForModel = console
                    val vfForModel = consoleVf
                    com.intellij.openapi.project.DumbService.getInstance(project).runWhenSmart {
                        // 延迟到 dialpct push 完全结束后（dumb 退出 + 一次 EDT 泵）再重建
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
            if (dataSourceName != null) rememberRunnerName(project, dataSourceName)

            // 4.7 内嵌（同步，已在 EDT）
            onConsole?.invoke(component)

            // M36.5.55：预览（尚未执行）时把结果区（bottom）折叠——divider 压到接近底部，
            // bottom（DBN Output/Tx 结果区占位）收起不显示，满足"未执行不显示结果面板"。
            // 执行时 realignSplitDivider 展开 60%。用 invokeLater 等组件布局后再设 divider。
            if (!executeAfterPrepare) {
                (component as? javax.swing.JSplitPane)?.let { sp ->
                    ApplicationManager.getApplication().invokeLater({
                        sp.setDividerLocation(1.0)
                        sp.revalidate()
                        sp.repaint()
                    }, com.intellij.openapi.application.ModalityState.any())
                }
            }

            // 5. 可选立即执行（selectAndExecute 全链语义；结果区已随完整 UI 内嵌，onResultsReady=null）
            if (executeAfterPrepare) {
                executeOnConsole(project, console, editor, effectiveScriptModel, execOption, ui, onResultsReady = null, onError = onError)
            }
        }.onFailure { e ->
            LOG.warn("[M36] prepareInConsole FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            // M36.3：通知调用方错误，确保 UI 从「执行中」状态恢复
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            onError?.invoke(cause.message ?: cause.toString())
        }
    }

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
            log.info("[M36] divider realign(force=$force): topH=$topH total=$total → 0.6")
            sp.setDividerLocation(0.6)
            sp.revalidate()
            sp.repaint()
            dumpSplitBounds(sp, log)
        } else {
            log.info("[M36] divider OK: topH=$topH total=$total (no change)")
        }
        // M36.5.75：布局级修复 Output 控制台 44px 塌缩（替代 M36.5.72/73 的 fillOutputConsole
        // 一次性 setSize——被平台 EditorMergedHorizontalScrollBarLayout 每轮 layout 压回，必然失效）
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
                log.info("[M36][OUTFILL] no output editor (EditorImpl\$PanelWithFloatingToolbar) in result area; skip")
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
                        log.info("[M36][OUTFILL] EMHSBL panel not found above output editor; ancestors=$chainDesc")
                    }
                    continue
                }
                // 3) 逐层安装填充布局（跳过 JScrollPane——ScrollPaneLayout 由滚动机制管理，不可替换）
                var child: java.awt.Component = t
                for (c in chain) {
                    if (c is javax.swing.JScrollPane) {
                        log.info("[M36][OUTFILL] chain node is JScrollPane (${c.javaClass.name}), keep its layout")
                    } else if (c.layout !is FillTargetLayout) {
                        val oldLayout = c.layout?.javaClass?.name ?: "null"
                        val before = child.bounds
                        c.layout = FillTargetLayout(child)
                        c.revalidate()
                        log.info("[M36][OUTFILL] installed on ${c.javaClass.name} (was $oldLayout) target=${child.javaClass.simpleName} before=$before")
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
                    .info("[M36][OUTFILL] layout: parent=${parent.javaClass.simpleName} h=${parent.height} target(${target.javaClass.simpleName}) → $avail")
            }
        }
    }

    /** M36.5.60：运行时组件边界取证——像素扫描无法区分"编辑器未拉伸"与"编辑器拉伸但背景同色"
     *（editor bg 43,45,48 vs panel bg 43,45,48 无差异）。直接 dump SplitPane/top/bottom 及其直接
     * 子组件的真实 bounds + size，一锤定音：topblock 是否被编辑器真的 fill，结果区是否占满 bottom。 */
    private fun dumpSplitBounds(sp: javax.swing.JSplitPane, log: com.intellij.openapi.diagnostic.Logger) {
        runCatching {
            fun b(c: java.awt.Component?): String = if (c == null) "null" else
                "${c.javaClass.simpleName}@${System.identityHashCode(c)} bounds=${c.bounds} pref=${c.preferredSize} min=${c.minimumSize} max=${c.maximumSize}"
            log.info("[M36][BOUNDS] sp=${b(sp)} divider=${sp.dividerLocation}")
            log.info("[M36][BOUNDS]   top=${b(sp.topComponent)}")
            (sp.topComponent as? java.awt.Container)?.components?.forEachIndexed { i, c ->
                log.info("[M36][BOUNDS]   top.child[$i]=${b(c)}")
                if (c is java.awt.Container) c.components.forEachIndexed { j, d ->
                    log.info("[M36][BOUNDS]   top.child[$i].child[$j]=${b(d)}")
                    // M36.5.61：编辑器视图（PanelWithFloatingToolbar）内部是否含未拉伸的 scrollpane/
                    // viewport——mask 真相在此层（编辑器 view 有 bounds 但只 paint 内容高度）
                    if (d is java.awt.Container) d.components.forEachIndexed { k, e ->
                        log.info("[M36][BOUNDS]   top.child[$i].child[$j].child[$k]=${b(e)}")
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
        // M36.5.52：新执行重置错误去重基线——本次执行产生的错误应上报，不与上次残留混淆。
        lastReportedError = null
        // M36.5.57：记录本次执行开始时 console history 基线长度——早检只扫本次之后新增文本，
        // 忽略 history 残留（正常 SQL 不再"带过来"上个失败的错误）。
        consoleErrorBaseline = consoleHistoryLength(console)
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
                                                        val info = findScriptModel.invoke(null, vfPsi, vfPsi, editor, execOption)
                                                        if (info == null) {
                                                            LOG.warn("[M36] native findScriptModel returned null (nothing to run?)")
                                                            // M36.5.48：findScriptModel 空 = 无可执行语句——显示**真实原因**，
                                                            // 而非笼统凭据超时提示（此前误用 execute.credential.hint，误导用户查密码/驱动）。
                                                            ApplicationManager.getApplication().invokeLater({
                                                                onError?.invoke(MpSqlVisualizerBundle.message("execute.noStatement"))
                                                            }, com.intellij.openapi.application.ModalityState.any())
                                                            return@submitTransactionLater
                                                        }
                                                        LOG.info("[M36] native findScriptModel OK, doRunQueryInConsole")
                                                        doRunMethod.invoke(null, console, info)
                                                        // 真实提交完成——此刻才启动结果监听
                                                        LOG.info("[M36] native query SUBMITTED (starting result watch)")
                                                        afterSubmitted(project, ui, console, onResultsReady, onError, LOG)
                                                    } catch (t: Throwable) {
                                                        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                                                        LOG.warn(
                                                            "[M36] native run chain FAILED: ${cause.javaClass.name}: ${cause.message}\n    " +
                                                                cause.stackTrace.take(8).joinToString("\n    at ") { it.toString() },
                                                            cause,
                                                        )
                                                        // M36.5.45：native 执行链失败必须通知 UI（此前仅 LOG.warn，用户看不到任何错误）
                                                        ApplicationManager.getApplication().invokeLater({
                                                            onError?.invoke(cause.message ?: cause.toString())
                                                        }, com.intellij.openapi.application.ModalityState.any())
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
                        ApplicationManager.getApplication().invokeLater({
                            onError?.invoke(msg)
                        }, com.intellij.openapi.application.ModalityState.any())
                        return@executeOnPooledThread
                    }
                    // 兜底路径（executeQueries 同步提交）：监听在此启动；原生路径由事务内
                    // afterSubmitted 启动，此处不再重复（防双重监听互相顶掉结果）。
                    if (submitted) {
                        afterSubmitted(project, ui, console, onResultsReady, onError, LOG)
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
    ) {
        // M36.5.44：真实提交时刻递增换入代际——作废上一个执行遗留的 refresh Timer（防滞后一轮）。
        swapGeneration++
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
                    val newTab = resultContents(uiForWatch).any { it.displayName !in preExistingNames }
                    val namespaceOk = runCatching {
                        val ns = consoleForWatch.javaClass
                            .getMethod("getCurrentNamespace").invoke(consoleForWatch)
                        ns != null && ns.toString().isNotBlank()
                    }.getOrDefault(true)
                    if (!newTab && !namespaceOk) {
                        LOG.info("[M36] no NEW result tab AND no namespace within 8s (newTab=$newTab, namespaceOk=$namespaceOk) - credential/driver hint")
                        hintOnError.invoke(MpSqlVisualizerBundle.message("execute.credential.hint"))
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
                startResultTabRetry(project, ui, onError, preExistingNames)
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
     */
    internal fun pickResultTab(
        contents: Array<com.intellij.ui.content.Content>,
        preExistingNames: Set<String>,
        attempts: Int,
    ): com.intellij.ui.content.Content? {
        val newOne = contents.firstOrNull { !isOutputContent(it) && it.displayName !in preExistingNames }
        if (newOne != null) return newOne
        val staleOne = contents.firstOrNull { !isOutputContent(it) }
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
    private fun startResultTabRetry(
        project: Project,
        ui: Any,
        onError: ((String) -> Unit)? = null,
        preExistingNames: Set<String> = emptySet(),
        generation: Int = swapGeneration,
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
                    LOG.info("[M36] result tab retry cancelled (generation $generation != ${swapGeneration})")
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
                        lastReportedError = err
                        LOG.info("[M36] early console error detected (attempts=$attempts): ${err.take(120)}")
                        ApplicationManager.getApplication().invokeLater({
                            onError?.invoke(err)
                        }, com.intellij.openapi.application.ModalityState.any())
                        return
                    }
                val contents = runCatching {
                    ui.javaClass.getMethod("getContents").invoke(ui) as? Array<com.intellij.ui.content.Content>
                }.getOrNull() ?: emptyArray()
                val all = contents.map { it.displayName }
                val ids = contents.map { System.identityHashCode(it) }
                val result = pickResultTab(contents, preExistingNames, attempts)
                if (result != null && result.displayName in preExistingNames) {
                    LOG.info("[M36] result tab reused (name=${result.displayName}) - accepting existing tab")
                }
                LOG.info("[M36] retry#$attempts contents=$all, ids=$ids, select=${result?.displayName}, preExisting=$preExistingNames")
                if (result != null) {
                    timer.stop()
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
                    refreshResultsComponent(ui, LOG, forceSwapIn = true, generation = generation, onError = onError)
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
                        LOG.info("[M36] result tab not appeared after $attempts attempts (dumb=$dumb)")
                        // M36.5.14：提示改走 onError（**面板内显示**，resultPanel.showError）——
                        // 此前 Messages.showWarningDialog 弹窗也会被模态预览窗口遮挡（用户实测
                        // "关闭窗口才出现提示"）；面板内红字必然可见，且不叠加新模态。
                        // ModalityState.any() 保证模态弹窗（如密码框）期间提示照常落地。
                        // M36.5.48：优先取 console 真实错误文本（DBN 查询失败写入 history）；
                        // 读不到才 fallback 笼统 hint（超时/索引）。
                        val realError = extractConsoleError(sharedConsole ?: preparedConsole, LOG)
                        val hint = realError
                            ?: MpSqlVisualizerBundle.message(
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
    private fun runRunner(runner: Any, silent: Boolean = false) {
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
            if (silent) {
                val proj = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
                if (proj != null) {
                    runCatching {
                        com.intellij.ide.util.PropertiesComponent.getInstance(proj).unsetValue(LAST_RUNNER_KEY)
                    }
                }
                return
            }
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
     * 读权限，refresh 后续链不受影响。**后台化经评估否决**：setMapping 须在 EDT（线程语义未
     * 验证，M36.5 约束）而 refresh 适合 BGT，双向交接（BGT invokeAndWait 等 EDT 执行映射、
     * EDT 阻塞等 BGT 产物）形成互锁死锁面——invokeAndWait 须等 EDT 回事件泵，冷路径必超时。
     * SEVERE 慢操作噪音（findFile → WorkspaceFileIndex 断言）为生产 LOG.error 不抛、且 F2c 后
     * 冷路径每会话仅首次真实执行，不为其冒险。
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
            // projectExecFile 尾段 FileUtil.writeToFile 可抛 IOException——原实现靠外层
            // runCatching 吞掉，拆除后这里必须自接，否则异常直冲 EDT
            return fail("writeFile", "${e.javaClass.simpleName}: ${e.message}")
        } ?: return fail("writeFile", "projectExecFile null (basePath=${project.basePath})")
        // T-PreviewFlash：EDT 同步 VFS refresh + PsiManager.findFile 已被跨点击缓存吸收——
        // mpsql-exec.sql 路径稳定，vf 仍有效时直接复用配对缓存，把
        // WorkspaceFileIndex.ensureIsUpToDate 的 SEVERE 断言移出每次预览的热路径。
        cachedTempPsiFile?.let { (vf, psi) ->
            if (vf.isValid) {
                LOG.info("[M36] exec psi cache hit (no VFS refresh): ${vf.name}")
                return vf to psi
            }
            cachedTempPsiFile = null
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
            return fail("refreshVfs", "${e.javaClass.simpleName}: ${e.message}")
        } ?: return fail("refreshVfs", "VFS not visible after refresh (${ioFile.path}, exists=${ioFile.exists()})")
        mapDialectBeforePsi(project, vf, LOG)
        val psi = try {
            ReadAction.compute<PsiFile?, Throwable> {
                PsiManager.getInstance(project).findFile(vf)
            }
        } catch (e: Throwable) {
            return fail("findPsi", "${e.javaClass.simpleName}: ${e.message}")
        } ?: return fail("findPsi", "PsiManager.findFile null (${vf.name})")
        cachedTempPsiFile = vf to psi
        return vf to psi
    }

    /**
     * M36.5.12：项目内执行文件路径——`<projectBase>/.idea/sql-visualizer/exec/mpsql-exec.sql`
     * （F-44-4：目录随插件更名，旧名 mybatis-plus-sql-visualizer 不再使用）。
     * 目录不存在则创建；文件写入 SQL（覆盖）。与 sql-history 同根（.idea/sql-visualizer/），
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
        val existing = LocalFileSystem.getInstance().findFileByIoFile(f)
        if (existing != null) {
            val updated = runCatching {
                @Suppress("DEPRECATION") // T-M52：WriteAction.compute(ThrowableComputable) 243 编译目标仅此重载可用
                com.intellij.openapi.application.WriteAction.compute<Unit, Throwable> {
                    // M36.5.43：优先 **document 驱动**三方同步（VFS/磁盘/document 一致），杜绝
                    // MemoryDiskConflictResolver 弹 "File Cache Conflict"（16:05:39 沙盒实证
                    // documentStamp:1742 vs oldFileStamp:779）。executeOnConsole 里"console doc
                    // synced+committed to vf"同款模式已被验证无冲突；若仅 setBinaryContent（只改
                    // VFS+磁盘、不动已打开的 document 内存），console 编辑器 hold 的 document 与
                    // 磁盘 stamp 不一致 → 弹窗冻结后续换入链。
                    val fileDoc = com.intellij.openapi.fileEditor.FileDocumentManager
                        .getInstance().getDocument(existing)
                    when {
                        fileDoc != null && fileDoc.text != sql -> {
                            fileDoc.setText(sql)
                            com.intellij.psi.PsiDocumentManager.getInstance(project)
                                .commitDocument(fileDoc)
                        }
                        fileDoc != null -> com.intellij.psi.PsiDocumentManager.getInstance(project)
                            .commitDocument(fileDoc)
                        fileDoc == null -> existing.setBinaryContent(sql.toByteArray())
                    }
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
        // T-PreviewFlash：插件侧幂等短路——mpsql-exec.sql 路径稳定，同 vf 已映射过 MySQL 直接跳过，
        // 消除每次预览的点 setMapping（同值 setMapping 仍触发 SqlDialectPusher push all dumb 任务，见沙盒日志）。
        if (dialectMappedVf?.isValid == true && dialectMappedVf === vf) {
            LOG.debug("[M36] dialect already mapped for ${vf.name}, skip setMapping")
            return
        }
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
                dialectMappedVf = vf
                LOG.info("[M36] dialect pre-mapped to MySQL for ${vf.name}")
            } else {
                LOG.warn("[M36] MySQL language dialect not found in SqlDialectMappings (fallback to pusher)")
            }
        }.onFailure { e ->
            LOG.warn("[M36] dialect pre-mapping skipped: ${e.message}")
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

    /** T-ExecCopyFix F2a：最近一次 createTempPsiFile 失败的阶段原因（成功时 null）。 */
    internal fun lastTempFileFailureForTest(): String? = lastTempFileFailure

    /** T-ExecCopyFix F2c 测试缝：等价链路置缓存槽（psi 经 PsiManager.findFile 同生产路径配对）。 */
    internal fun markCachedTempPsiFileForTest(project: Project, vf: com.intellij.openapi.vfs.VirtualFile) {
        val psi = ReadAction.compute<PsiFile?, Throwable> {
            PsiManager.getInstance(project).findFile(vf)
        }
        checkNotNull(psi) { "markCachedTempPsiFileForTest: findFile null for ${vf.name}" }
        cachedTempPsiFile = vf to psi
    }

    /** T-ExecCopyFix F2c 测试缝：清缓存槽（测试隔离收尾）。 */
    internal fun clearCachedTempPsiFileForTest() {
        cachedTempPsiFile = null
    }
}
