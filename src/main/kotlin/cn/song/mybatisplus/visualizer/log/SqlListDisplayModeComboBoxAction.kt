package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import javax.swing.JComponent

/**
 * SQL 列表显示模式下拉（T-ListMode；T-MergeFollowup-04 原生化）。
 *
 * 弃用裸 [javax.swing.JComboBox]（Swing 默认样式与 IDE 原生观感不符，用户反馈），
 * 改平台 [ComboBoxAction]（Git 分支切换器同款下拉按钮）：按钮文本随当前模式切换，
 * 点击弹三选菜单、当前项经 [getPreselectCondition] 高亮。两面板（SQL 日志/SQL 历史）
 * 共享同一设置键，选择后广播 SETTINGS_CHANGE_TOPIC 由既有监听统一刷新。
 */
internal class SqlListDisplayModeComboBoxAction(private val project: Project) : ComboBoxAction() {

    /** 选项 action（文案 = 模式名，携带模式值）。 */
    private inner class ModeItem(val mode: SqlListDisplayMode) : AnAction(modeLabel(mode)) {
        override fun actionPerformed(e: AnActionEvent) {
            val settings = PluginSettings.getInstance()
            if (settings.state.logListSqlDisplayMode != mode.name) {
                settings.state.logListSqlDisplayMode = mode.name
                project.messageBus.syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged()
            }
        }
    }

    override fun createPopupActionGroup(button: JComponent?): DefaultActionGroup =
        DefaultActionGroup(SqlListDisplayMode.values().map { ModeItem(it) })

    /** 当前选中项高亮（平台 popup 渲染时读取）。 */
    override fun getPreselectCondition(): Condition<AnAction> = Condition { action ->
        (action as? ModeItem)?.mode == currentMode()
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.text = modeLabel(currentMode())
    }

    /** 程序化回同步（模式被另一面板或设置页改变）：刷新按钮文本——presentation 变化由 ComboBoxButton 监听刷新。 */
    fun syncFromSettings() {
        templatePresentation.text = modeLabel(currentMode())
    }

    private fun currentMode(): SqlListDisplayMode = PluginSettings.getInstance().sqlListDisplayMode()

    private fun modeLabel(mode: SqlListDisplayMode): String = when (mode) {
        SqlListDisplayMode.SINGLE_LINE -> MpSqlVisualizerBundle.message("log.list.displayMode.single")
        SqlListDisplayMode.KEYWORD_WRAP -> MpSqlVisualizerBundle.message("log.list.displayMode.keywordWrap")
        SqlListDisplayMode.FORMATTED -> MpSqlVisualizerBundle.message("log.list.displayMode.formatted")
    }
}
