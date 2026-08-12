package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent

/**
 * SQL 预览弹窗（M3-02，F-07 弹窗展示方式）。
 *
 * 由 [cn.song.mybatisplus.visualizer.actions.ConvertToSqlAction] 触发，
 * 复用 [SqlPreviewPanel] 展示 SQL 与参数对照，底部提供"复制 / 关闭"。
 */
class SqlPreviewDialog(
    private val project: Project?,
    private val result: PreviewResult,
) : DialogWrapper(project, true) {

    private val panel = SqlPreviewPanel(project)

    init {
        title = MpSqlVisualizerBundle.message("dialog.preview.title")
        panel.showResult(result)
        init()
        // 弹窗默认尺寸
        setSize(720, 480)
    }

    override fun createCenterPanel(): JComponent = panel

    override fun createActions(): Array<Action> = arrayOf(
        // 一键复制（F-08，提示由面板统一处理）
        object : DialogWrapperAction(MpSqlVisualizerBundle.message("dialog.copy.button")) {
            override fun doAction(actionEvent: java.awt.event.ActionEvent?) {
                panel.copySql()
            }
        },
        // 关闭
        cancelAction,
    )
}
