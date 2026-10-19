package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.execute.ExecutionSubmission
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import java.util.concurrent.atomic.AtomicBoolean
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver
import cn.song.mybatisplus.visualizer.util.EditorCopyShortcuts
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 回滚 SQL 视图（T-RollbackSql P1-F3 / P2-F5，dbx 精华融合）。
 *
 * MODELESS 独立弹窗（仿 SqlPreviewDialog 惯例）——刻意不写进预览面板 native 文档、
 * 不切显示模式：`showSql` 三分支管线（T-RefreshDbSteal 治理后）零侵入。
 *
 * 形态按评级分层（dbx「评级不足阻断执行」的对应物）：
 *  - Complete：绿色横幅 + 只读回滚 SQL 编辑器 + 底部「执行回滚 / 复制 / 关闭」；
 *  - Partial：橙色横幅（风险警示）+ COUNT 自查模板展示，其余同上；
 *  - Unable（可解析的 UPDATE/DELETE）：红色横幅 + 人工恢复指引 + **快照工作流**——
 *    快照查询模板一键执行（结果落面板控制台网格，用户亲手取得 before-image）+
 *    粘贴结果生成完整回滚（dbx「有 before-image 才回滚」的半自动落地，P2-F5 降级方案；
 *    262 连接协议不可程序化快照的定夺见 [RollbackSnapshotStore] KDoc）——粘贴生成的回填 INSERT
 *    在快照条目持有 identity 信息（O-52：[RollbackSnapshotStore.SnapshotEntry.dialect] /
 *    `identityColumns`）时按方言包装，否则不包装；
 *  - Unable（完全不可解析/MERGE/DDL）：不进本视图，面板入口直接消息框（计划 §2.2）。
 *
 * 「执行回滚」不走 [cn.song.mybatisplus.visualizer.execute.SqlExecutor.execute] 全链弹数据源
 * 选择——持预览面板引用走 [SqlPreviewPanel.executeAdhocSql]，T-RefreshDbSteal 所有权卫队天然生效；
 * 执行提交后快照条目置「已执行」（F6 留痕）+ 横幅更新。
 */
class RollbackSqlDialog(
    private val project: Project?,
    /** 预览面板引用——「执行回滚/执行快照查询」复用 executeAdhocSql 管线（计划 §2.4）。 */
    private val panel: SqlPreviewPanel?,
    /** 生成结果（面板入口已按「Unable 且无快照工作流」过滤）。 */
    internal val result: RollbackSqlGenerator.RollbackResult,
    /** 原语句关键字（INSERT/UPDATE/DELETE）——快照回填模式分派。 */
    private val statementKind: String,
    /** 经执行按钮执行过的 UPDATE/DELETE 登记条目（F6 留痕；非执行路径入口为 null）。 */
    internal val snapshotEntry: RollbackSnapshotStore.SnapshotEntry? = null,
) : DialogWrapper(project, false, DialogWrapper.IdeModalityType.MODELESS) {

    private val bannerLabel = JBLabel()
    private val warningLabel = JBLabel()
    private val countCheckLabel = JBLabel()
    private val snapshotQueryLabel = JBLabel()
    private val pasteArea = JBTextArea(4, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val pkField = JBTextField(20)
    private var editor: Editor? = null

    // 底部动作以字段持有——DialogWrapper 无公开 getActions()（javap 实证），粘贴生成后
    // 启用态只能经自身引用刷新；字段初始化先于 init()（createActions 在 init() 内调用）
    private val executeAction = object : DialogWrapperAction(MpSqlVisualizerBundle.message("rollback.action.execute")) {
        override fun doAction(actionEvent: java.awt.event.ActionEvent?) {
            executeRollback()
        }
    }
    private val copyAction = object : DialogWrapperAction(MpSqlVisualizerBundle.message("rollback.action.copy")) {
        override fun doAction(actionEvent: java.awt.event.ActionEvent?) {
            copyRollback()
        }
    }

    /** 粘贴生成后的最新回滚结果（执行回滚/复制按钮取此值）。 */
    internal var currentResult: RollbackSqlGenerator.RollbackResult = result
        private set

    /** 仅快照派生产物受输入编辑影响；原始 INSERT 等非快照回滚保持原有执行路径。 */
    private var snapshotGenerationStarted = false

    init {
        title = MpSqlVisualizerBundle.message("rollback.dialog.title")
        setCancelButtonText(MpSqlVisualizerBundle.message("rollback.action.close"))
        init()
        assembleBanner()
        assembleSnapshotSection()
        val inputListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = snapshotInputChanged()
            override fun removeUpdate(e: DocumentEvent) = snapshotInputChanged()
            override fun changedUpdate(e: DocumentEvent) = snapshotInputChanged()
        }
        pasteArea.document.addDocumentListener(inputListener)
        pkField.document.addDocumentListener(inputListener)
    }

    override fun createCenterPanel(): JComponent {
        val body = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(buildTopBlock(), BorderLayout.NORTH)
            add(buildEditorBlock(), BorderLayout.CENTER)
            if (hasSnapshotWorkflow()) add(buildSnapshotBlock(), BorderLayout.SOUTH)
        }
        body.preferredSize = Dimension(DIALOG_WIDTH, DIALOG_HEIGHT)
        return body
    }

    // ------------------------------------------------------------------
    // 组装
    // ------------------------------------------------------------------

    private fun buildTopBlock(): JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(6, 10, 2, 10)
        add(bannerLabel, BorderLayout.NORTH)
        add(warningLabel, BorderLayout.CENTER)
        if (currentResult.countCheckSql != null) {
            countCheckLabel.text = countCheckHtml()
            countCheckLabel.toolTipText = currentResult.countCheckSql
            add(countCheckLabel, BorderLayout.SOUTH)
        }
    }

    private fun buildEditorBlock(): JComponent {
        val sqlText = currentResult.statements.joinToString("\n")
        val doc = EditorFactory.getInstance().createDocument(sqlText)
        val created = if (project != null) {
            EditorFactory.getInstance().createEditor(doc, project, sqlFileType(), true)
        } else {
            EditorFactory.getInstance().createViewer(doc)
        }
        // T-PreviewWhitespaceToggle O-44a：空白符可视化（默认关=不设置该属性、继承 IDE 全局）。
        // 本编辑器此前零外观设置（只读回滚 SQL），故是唯一不落在 EditorEx.apply 块内的接线点。
        EditorWhitespaceSettings.applyTo(created)
        // T-SqlCopyShortcuts O-122：无选中 Ctrl+C 复制全文、有选中只复制片段
        //（层位与理由见 EditorCopyShortcuts 的 KDoc；本处此前无任何键盘复制绑定）
        EditorCopyShortcuts.install(created, UiTextClipboard::copy)
        editor = created
        // 编辑器随弹窗 disposable 树级联释放（Disposer 树铁律）
        Disposer.register(disposable) { releaseEditor() }
        return JBScrollPane(created.component).apply {
            preferredSize = Dimension(DIALOG_WIDTH, EDITOR_HEIGHT)
        }
    }

    private fun hasSnapshotWorkflow(): Boolean = result.snapshotSelectSql != null && panel != null

    private fun buildSnapshotBlock(): JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4, 10, 6, 10)
        add(
            JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                isOpaque = false
                add(snapshotQueryLabel)
                add(JButton(MpSqlVisualizerBundle.message("rollback.snapshot.run")).apply {
                    addActionListener { runSnapshotQuery() }
                })
                add(JButton(MpSqlVisualizerBundle.message("rollback.snapshot.copy")).apply {
                    addActionListener {
                        val sql = currentResult.snapshotSelectSql ?: return@addActionListener
                        copyToClipboard(sql)
                    }
                })
            },
            BorderLayout.NORTH,
        )
        add(
            JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply {
                isOpaque = false
                add(JBScrollPane(pasteArea), BorderLayout.CENTER)
                add(
                    JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                        isOpaque = false
                        add(JBLabel(MpSqlVisualizerBundle.message("rollback.snapshot.pk")))
                        add(pkField)
                        add(JButton(MpSqlVisualizerBundle.message("rollback.snapshot.generate")).apply {
                            addActionListener { generateFromPaste() }
                        })
                    },
                    BorderLayout.SOUTH,
                )
            },
            BorderLayout.CENTER,
        )
    }

    private fun assembleBanner() {
        val (textKey, color) = when {
            snapshotEntry?.status == RollbackSnapshotStore.Status.ROLLBACK_EXECUTED ->
                "rollback.banner.executed" to SUCCESS_COLOR
            currentResult.rating == RollbackSqlGenerator.Rating.COMPLETE ->
                "rollback.banner.complete" to SUCCESS_COLOR
            currentResult.rating == RollbackSqlGenerator.Rating.PARTIAL ->
                "rollback.banner.partial" to WARNING_COLOR
            else -> "rollback.banner.unable" to ERROR_COLOR
        }
        bannerLabel.text = MpSqlVisualizerBundle.message(textKey)
        bannerLabel.foreground = color
        warningLabel.text = warningsHtml(currentResult)
        warningLabel.foreground = JBColor.foreground()
    }

    private fun assembleSnapshotSection() {
        val sql = currentResult.snapshotSelectSql ?: return
        val shown = if (sql.length > SNAPSHOT_DISPLAY_LIMIT) sql.take(SNAPSHOT_DISPLAY_LIMIT) + "…" else sql
        snapshotQueryLabel.text = "<html><b>${MpSqlVisualizerBundle.message("rollback.snapshot.section")}</b> <code>$shown</code></html>"
        snapshotQueryLabel.toolTipText = sql
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    override fun createActions(): Array<Action> {
        val hasStatements = currentResult.statements.isNotEmpty()
        executeAction.isEnabled = hasStatements
        copyAction.isEnabled = hasStatements
        return arrayOf(executeAction, copyAction, cancelAction)
    }

    /** 「执行回滚」：经预览面板 executeAdhocSql 复用执行管线（DML 二次确认内置）。 */
    internal fun executeRollback() {
        val sql = currentResult.statements.joinToString("\n")
        if (sql.isBlank()) return
        panel?.executeAdhocSql(sql, rollbackOfEntry = snapshotEntry, onSubmission = rollbackSubmissionReceipt())
    }

    /** 每次请求仅结算一次；真实提交后保留业务留痕，关闭弹窗仅抑制迟到的 UI 更新。 */
    internal fun rollbackSubmissionReceipt(): (ExecutionSubmission) -> Unit {
        val completed = AtomicBoolean(false)
        val submittedResult = currentResult
        val store = project?.getService(RollbackSnapshotStore::class.java)
        return receipt@{ outcome ->
            if (!completed.compareAndSet(false, true) || outcome != ExecutionSubmission.SUBMITTED) return@receipt
            // SUBMITTED 来自底层 afterSubmitted，不代表数据库执行成功或事务已提交。
            snapshotEntry?.let { store?.markRollbackExecuted(it) }
            val updateBanner = {
                if (!isDisposed && currentResult === submittedResult) {
                    bannerLabel.text = MpSqlVisualizerBundle.message("rollback.banner.executed")
                    bannerLabel.foreground = SUCCESS_COLOR
                }
            }
            val app = ApplicationManager.getApplication()
            if (app.isDispatchThread) updateBanner()
            else app.invokeLater({ updateBanner() }, ModalityState.any())
        }
    }

    /** 「复制」：回滚 SQL 入剪贴板。 */
    internal fun copyRollback(): Boolean {
        val sql = currentResult.statements.joinToString("\n")
        if (sql.isBlank()) return false
        copyToClipboard(sql)
        return true
    }

    /** 「执行快照查询」：查询类语句经 executeAdhocSql 免确认直跑，结果落面板控制台网格。 */
    internal fun runSnapshotQuery() {
        val sql = currentResult.snapshotSelectSql ?: return
        panel?.executeAdhocSql(sql)
    }

    private fun snapshotInputChanged() {
        if (snapshotGenerationStarted) invalidateSnapshotResult()
    }

    /** 清空执行/复制共用的模型和编辑器，不能仅禁用按钮或退回可能可执行的原始结果。 */
    private fun invalidateSnapshotResult() {
        currentResult = result.copy(
            rating = RollbackSqlGenerator.Rating.UNABLE,
            statements = emptyList(),
            countCheckSql = null,
        )
        refreshAfterPaste()
    }

    /** 「生成回滚」：粘贴 TSV → 快照回填（按原语句类型分派）→ 刷新编辑器与横幅。 */
    internal fun generateFromPaste() {
        if (!hasSnapshotWorkflow()) return
        snapshotGenerationStarted = true
        // 每次尝试先撤销上次产物；解析失败、生成失败乃至异常都不得保留旧 SQL。
        invalidateSnapshotResult()
        val table = result.targetTable ?: return
        val parsed = RollbackSqlGenerator.parseSnapshotTsv(pasteArea.text ?: "")
        if (parsed == null) {
            warningLabel.text = "<html>${escapeHtml(MpSqlVisualizerBundle.message("rollback.snapshot.parseFailed"))}</html>"
            return
        }
        val (columns, rows) = parsed
        val pk = pkField.text?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
        val mode = when (statementKind.uppercase()) {
            "UPDATE" -> RollbackSqlGenerator.SnapshotMode.REVERT_UPDATE
            "DELETE" -> RollbackSqlGenerator.SnapshotMode.BACKFILL_INSERT
            else -> RollbackSqlGenerator.SnapshotMode.REVERT_INSERT
        }
        val generated = RollbackSqlGenerator.generateFromSnapshotRows(
            table = table,
            columns = columns,
            rows = rows,
            mode = mode,
            primaryKeyColumns = pk,
            changedColumns = result.setColumns,
            newValuesByColumn = result.setValues,
            // O-52（D1=A）：identity 知识随快照条目留存，此处取回传给生成器——条目未持有
            // （旧条目/未传参的登记点）时保持 null/空 → 不包装，与改前逐字节相同
            dialect = snapshotEntry?.dialect,
            // O-59 T-IIR-02：identity 列**在消费点解析**（而非登记点）。登记点位于执行前调用链上，
            // 在那里同步走 DAS 模型遍历有 EDT 慢操作断言风险（O-59 计划 §六 风险表首条即点名）；
            // 本对话框由用户点击动作打开，且 [DatabaseSchemaResolver] 本身是缓存优先 + 有界扫描的
            // UI 面，在此解析既避开执行链、又复用既有缓存。条目已带非空清单时优先用条目。
            identityColumns = snapshotEntry?.identityColumns?.takeIf { it.isNotEmpty() }
                ?: autoGeneratedColumnsOf(table),
        )
        if (generated.rating == RollbackSqlGenerator.Rating.UNABLE) {
            warningLabel.text = warningsHtml(generated)
            return
        }
        // 生成结果不携带原语句的快照模板/SET 元信息；保留它们以支持再次生成与快照查询。
        currentResult = generated.copy(
            whereText = result.whereText,
            setColumns = result.setColumns,
            setValues = result.setValues,
            snapshotSelectSql = result.snapshotSelectSql,
        )
        refreshAfterPaste()
    }

    /**
     * O-59 T-IIR-02：按表名解析该表的**自增列**（`DasColumn.Attribute.AUTO_GENERATED`）。
     *
     * 取不到（无数据源 / 模型未内省 / 表不在模型里 / 反射链异常）一律返回**空清单 = 不包装**——
     * 与 O-52 D1=A 的「未知不臆造」契约一致（宁可少包装，也不误包装）。
     * 解析走 [DatabaseSchemaResolver.columnMeta]（缓存优先；扫描预算与负缓存口径见该类 KDoc）。
     */
    private fun autoGeneratedColumnsOf(table: String?): List<String> {
        val name = table?.takeIf { it.isNotBlank() } ?: return emptyList()
        val proj = project ?: return emptyList()
        return runCatching {
            DatabaseSchemaResolver.columnMeta(proj, name)
                .filterValues { it.autoGenerated == true }
                .keys.toList()
        }.getOrElse { emptyList() }
    }

    /** 粘贴生成成功后的视图刷新（编辑器内容 + 横幅 + 底部动作启用态）。 */
    private fun refreshAfterPaste() {
        val text = currentResult.statements.joinToString("\n")
        editor?.document?.let { doc ->
            if (doc.text != text) {
                // 文档写入须写操作语境（测试实证：裸 setText 在平台断言下硬抛）
                val proj = project
                if (proj != null) {
                    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(proj) { doc.setText(text) }
                } else {
                    doc.setText(text)
                }
            }
        }
        assembleBanner()
        val hasStatements = currentResult.statements.isNotEmpty()
        executeAction.isEnabled = hasStatements
        copyAction.isEnabled = hasStatements
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun countCheckHtml(): String =
        "<html><span style='color:gray'>${escapeHtml(MpSqlVisualizerBundle.message("rollback.countcheck.hint"))}</span> " +
            "<code>${escapeHtml(currentResult.countCheckSql ?: "")}</code></html>"

    private fun warningsHtml(r: RollbackSqlGenerator.RollbackResult): String {
        if (r.warnings.isEmpty()) return ""
        val items = r.warnings.joinToString("") { warning ->
            val text = if (warning.messageArgs.isEmpty()) {
                MpSqlVisualizerBundle.message(warning.messageKey)
            } else {
                MpSqlVisualizerBundle.message(warning.messageKey, *warning.messageArgs.toTypedArray())
            }
            "<li>${escapeHtml(text)}</li>"
        }
        return "<html><ul style='margin-left:12px'>$items</ul></html>"
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    private fun releaseEditor() {
        editor?.let {
            EditorFactory.getInstance().releaseEditor(it)
            editor = null
        }
    }

    private fun sqlFileType(): com.intellij.openapi.fileTypes.FileType =
        com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getStdFileType("SQL")

    override fun getDimensionServiceKey(): String = DIMENSION_KEY

    /** T-RollbackSql：程序化关闭（同 SqlPreviewDialog.doDispose 惯例；测试 tearDown 用）。 */
    fun doDispose() {
        dispose()
    }

    /** 释放自建编辑器（DialogWrapper 不感知 EditorFactory 产物，须自行兜底）。 */
    override fun dispose() {
        releaseEditor()
        super.dispose()
    }

    // ------------------------------------------------------------------
    // 测试访问（同模块测试使用，仿 SqlPreviewPanel 访问器惯例）
    // ------------------------------------------------------------------

    internal fun bannerTextForTest(): String = bannerLabel.text
    internal fun warningsTextForTest(): String = warningLabel.text
    internal fun sqlTextForTest(): String = editor?.document?.text ?: ""

    /** 测试访问器：编辑器实例（T-SqlCopyShortcuts：键盘复制接线断言用）。 */
    internal fun editorForTest(): com.intellij.openapi.editor.Editor? = editor
    internal fun pasteAreaForTest(): JBTextArea = pasteArea
    internal fun pkFieldForTest(): JBTextField = pkField
    internal fun executeActionForTest(): Action = executeAction
    internal fun copyActionForTest(): Action = copyAction
    internal fun snapshotQueryLabelForTest(): JBLabel = snapshotQueryLabel

    companion object {
        /** 尺寸记忆 key。 */
        const val DIMENSION_KEY = "cn.song.mybatisplus.visualizer.RollbackSqlDialog"

        private const val DIALOG_WIDTH = 720
        private const val DIALOG_HEIGHT = 460
        private const val EDITOR_HEIGHT = 180

        /** 快照 SQL 行内展示截断长度（全文在 tooltip）。 */
        private const val SNAPSHOT_DISPLAY_LIMIT = 80

        private val SUCCESS_COLOR = JBColor.namedColor("RollbackSql.complete", java.awt.Color(0x2E7D32))
        private val WARNING_COLOR = JBColor.namedColor("RollbackSql.partial", java.awt.Color(0xB26A00))
        private val ERROR_COLOR = JBColor.namedColor("ErrorForeground", JBColor.RED)
    }
}
