package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.generator.CrudStatement
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.ui.SqlSelectionDialog
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * CRUD 生成结果 → 现有预览窗口的展示流（T-CrudSql-04，展示层零新弹窗）。
 *
 * 候选选择用 **JBPopup 浮层**（T-CrudSql 样式反馈二次定案：模态对话框带 OK/Cancel 与
 * 底部留白不符合 IDEA 原生风格；Recent Files/搜索式浮层才是原生态——无按钮、Enter/双击
 * 选择、ESC 关闭、打字过滤定位），选中后走 [SqlPreviewService.showResult] 打开既有
 * SqlPreviewDialog——渲染/格式化/执行/复制按钮全部复用，INSERT/UPDATE 的列勾选重建由
 * PreviewResult.batchModel 承载。候选 0/1 条时不弹选择（0 条由调用方兜底提示）。
 */
object CrudSqlPreviewFlow {

    /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
    private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"

    /** 候选项：表名（标签前缀）+ 语句。 */
    data class Candidate(val tableName: String, val statement: CrudStatement)

    /**
     * 展示候选语句。多表多语句合并进同一列表（标签带表名前缀）。
     *
     * @param tables 表名 → 该表生成的语句列表（空列表的表已由调用方过滤）
     */
    fun showCandidates(project: Project, tables: List<Pair<String, List<CrudStatement>>>) {
        val candidates = tables.flatMap { (table, statements) ->
            statements.map { Candidate(table, it) }
        }
        when (candidates.size) {
            0 -> return
            // 调用方（action invokeLater）已在 EDT 非模态上下文——直接开预览
            1 -> SqlPreviewService.showResult(project, candidates[0].statement.result, null)
            else -> JBPopupFactory.getInstance()
                .createPopupChooserBuilder(candidates)
                .setTitle(MpSqlVisualizerBundle.message("dialog.selectSql.title"))
                .setRenderer(CandidateCellRenderer())
                .setNamerForFiltering { summary(it) }
                .setItemChosenCallback { selected ->
                    // 预览打开延后到选择浮层关闭之后（T-CrudSql 真机修复：不在选择 UI 的
                    // 事件栈内同步开 modeless 预览弹窗，262 实测会被吞且零异常零日志）
                    ApplicationManager.getApplication().invokeLater {
                        SqlPreviewService.showResult(project, selected.statement.result, null)
                    }
                }
                .createPopup()
                .showInFocusCenter()
        }
    }

    /** 兜底提示（update 已拦截可见性，竞态/解析异常路径才会到达）。
     * T-UiNative T-UN-02：改轻通知（原模态弹窗——纯告知无需打断）；测试模式跳过
     * （对齐 CopyExecutableSqlAction.showToast 约定）。 */
    fun showNoneFound(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        runCatching {
            Notification(
                NOTIFICATION_GROUP_ID,
                MpSqlVisualizerBundle.message("crudSql.noneFound.title"),
                MpSqlVisualizerBundle.message("crudSql.noneFound.message"),
                NotificationType.INFORMATION,
            ).notify(project)
        }
    }

    /** 候选摘要：`表名 · 语句种类`（无主键降级追加标注）。纯函数可单测。 */
    internal fun summary(candidate: Candidate): String {
        val kind = MpSqlVisualizerBundle.message(candidate.statement.kind.labelKey)
        val base = "${candidate.tableName} · $kind"
        return if (candidate.statement.missingPrimaryKey) {
            "$base${MpSqlVisualizerBundle.message("crudSql.candidate.noPrimaryKey")}"
        } else {
            base
        }
    }

    /** 候选第二行：SQL 首行预览（渲染时截断 80 字符）。 */
    internal fun lineText(candidate: Candidate): String =
        candidate.statement.result.sql.lineSequence().firstOrNull().orEmpty()

    /**
     * 候选行渲染器：主题色双行（加粗摘要 + 次要色详情，选中态随平台列表配色），
     * 与 [SqlSelectionDialog] 行渲染同款范式；详情行禁 HTML 解析（代码行可能以 `<` 开头）。
     */
    internal class CandidateCellRenderer : JPanel(BorderLayout()), ListCellRenderer<Candidate> {
        private val summaryLabel = JLabel()
        private val detailLabel = JLabel()

        init {
            isOpaque = true
            border = JBUI.Borders.empty(6, 12)
            summaryLabel.font = summaryLabel.font.deriveFont(Font.BOLD)
            detailLabel.putClientProperty("html.disable", true)
            detailLabel.foreground = UIUtil.getContextHelpForeground()
            add(summaryLabel, BorderLayout.NORTH)
            add(detailLabel, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out Candidate>,
            value: Candidate,
            index: Int,
            selected: Boolean,
            focused: Boolean,
        ): Component {
            summaryLabel.text = summary(value)
            val line = lineText(value)
            detailLabel.text =
                if (line.length > SqlSelectionDialog.MAX_LINE_LENGTH) line.take(SqlSelectionDialog.MAX_LINE_LENGTH) + "…" else line
            background = if (selected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
            summaryLabel.foreground = UIUtil.getListForeground()
            detailLabel.foreground = if (selected) UIUtil.getListForeground() else UIUtil.getContextHelpForeground()
            return this
        }
    }
}
