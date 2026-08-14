package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiMethodCallExpression
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel

/**
 * 多 SQL 选择弹窗（T-M23.7/8）：选择代码块后右键、选择区内含**多个** SQL 候选时，
 * 弹出列表让用户选择要预览哪一个。
 *
 * T-M23.8（用户反馈：摘要不够明细、不能定位代码位置）：列表每项**两行**展示——
 * 第一行摘要（`行号 + Wrapper 类型/方法名 + 表名`），第二行**代码行内容**（缩进 + 截断 80 字符 +
 * HTML 转义），对齐 IDEA 原生搜索的代码预览体验；选中确定 → [onSelect] 回调
 * （调用方负责编辑器定位跳转 + 预览 SQL）。
 */
class SqlSelectionDialog(
    private val project: Project,
    candidates: List<PsiMethodCallExpression>,
    private val onSelect: (PsiMethodCallExpression) -> Unit,
) : DialogWrapper(project, true) {

    /** 候选 SQL 调用（显示文本列表索引序与之一一对应）。 */
    private val candidates: List<PsiMethodCallExpression> = candidates

    private val list = JList(
        candidates.map { displayText(WrapperLocator.sqlCandidateSummary(it), WrapperLocator.sqlCandidateLineText(it)) }.toTypedArray(),
    ).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    init {
        title = MpSqlVisualizerBundle.message("dialog.selectSql.title")
        list.selectedIndex = 0
        init()
    }

    override fun createCenterPanel(): JComponent = JScrollPane(list).apply {
        preferredSize = Dimension(DIALOG_WIDTH, DIALOG_HEIGHT)
        minimumSize = Dimension(DIALOG_MIN_WIDTH, DIALOG_MIN_HEIGHT)
    }

    override fun doOKAction() {
        val selected = list.selectedIndex
        if (selected in candidates.indices) {
            onSelect(candidates[selected])
        }
        super.doOKAction()
    }

    companion object {
        /** 弹窗默认尺寸。 */
        const val DIALOG_WIDTH = 560
        const val DIALOG_HEIGHT = 220
        const val DIALOG_MIN_WIDTH = 420
        const val DIALOG_MIN_HEIGHT = 120

        /** 代码行预览最大长度（字符；超出截断加省略号，防撑爆弹窗宽度）。 */
        const val MAX_LINE_LENGTH = 80

        /**
         * T-M23.8：列表项显示文本（纯函数，可单测）——两行 HTML：
         * 第一行摘要（行号 + 类型/方法名 + 表名），第二行代码行内容（缩进两空格；
         * **先截断再转义**——`&`/`<`/`>` 转义防破坏 HTML 布局）。
         */
        internal fun displayText(summary: String, lineText: String): String {
            val core = if (lineText.length > MAX_LINE_LENGTH) lineText.take(MAX_LINE_LENGTH) + "…" else lineText
            val escaped = core
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            return "<html>$summary<br>&nbsp;&nbsp;&nbsp;$escaped</html>"
        }
    }
}
