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
 * 多 SQL 选择弹窗（T-M23.7）：选择代码块后右键、选择区内含**多个** Wrapper 链时，
 * 弹出列表让用户选择要预览哪一个（`行号 + Wrapper 类型 + 表名` 摘要）。
 *
 * 确定 → [onSelect] 回调选中链首调用（调用方在 EDT 上预览）；取消 → 不动作。
 */
class SqlSelectionDialog(
    private val project: Project,
    candidates: List<PsiMethodCallExpression>,
    private val onSelect: (PsiMethodCallExpression) -> Unit,
) : DialogWrapper(project, true) {

    /** 候选链首调用（摘要列表索引序与之一一对应）。 */
    private val candidates: List<PsiMethodCallExpression> = candidates

    private val list = JList(candidates.map { WrapperLocator.chainHeadSummary(it) }.toTypedArray()).apply {
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
        const val DIALOG_WIDTH = 420
        const val DIALOG_HEIGHT = 200
        const val DIALOG_MIN_WIDTH = 320
        const val DIALOG_MIN_HEIGHT = 120
    }
}
