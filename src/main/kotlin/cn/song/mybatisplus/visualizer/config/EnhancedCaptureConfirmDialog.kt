package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * M56 T-M56-08：增强捕获注入确认对话框（首次启用弹一次，确认记忆持久化）。
 *
 * 话术四件套（§4.5）：目的 / 采集范围 / 数据留本机不回传 + 回落路径 /
 * JDK21+ 目标控制台会出现动态挂载告警属预期（JEP 451，仅告警未禁止；未来硬禁止走
 * 回落路径并提示 `-XX:+EnableDynamicAgentLoading`，插件不自动改用户配置）。
 */
class EnhancedCaptureConfirmDialog(parent: Project?) : DialogWrapper(parent, true) {

    companion object {
        /** 模态确认；true = 用户同意注入。 */
        fun confirm(parent: Project?): Boolean =
            EnhancedCaptureConfirmDialog(parent).showAndGet()
    }

    init {
        title = MpSqlVisualizerBundle.message("settings.enhancedCapture.confirm.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = JBUI.size(520, 240)
        val label = JBLabel()
        label.setText(
            "<html><body style='width:480px'>"
                + MpSqlVisualizerBundle.message("settings.enhancedCapture.confirm.body")
                + "</body></html>",
        )
        panel.add(label, BorderLayout.CENTER)
        return panel
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)

    override fun getOKAction(): Action = super.getOKAction().apply {
        putValue(Action.NAME, MpSqlVisualizerBundle.message("settings.enhancedCapture.confirm.ok"))
    }
}
