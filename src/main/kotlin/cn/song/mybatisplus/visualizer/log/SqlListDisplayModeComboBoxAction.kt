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

    /** [createToolbarComponent] 创建的按钮 presentation 引用（syncFromSettings 更新它触发按钮文本刷新）。 */
    private var buttonPresentation: com.intellij.openapi.actionSystem.Presentation? = null

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
        e.presentation.description = MpSqlVisualizerBundle.message("log.list.displayMode")
    }

    /**
     * 创建工具栏按钮组件（T-MergeFollowup-04 复查修正）：**先填充文本再创建**——243 ComboBoxButton
     * 构造时取一次 `presentation.text`（手动场景无 ActionToolbar 驱动 update()，首次显示前文本恒空，
     * 只剩箭头；后续变更经 presentation 属性监听刷新）。syncFromSettings 更新同实例保持监听生效。
     */
    fun createToolbarComponent(place: String): JComponent {
        val presentation = com.intellij.openapi.actionSystem.Presentation()
        presentation.text = modeLabel(currentMode())
        presentation.description = MpSqlVisualizerBundle.message("log.list.displayMode")
        buttonPresentation = presentation
        return createCustomComponent(presentation, place)
    }

    /** 程序化回同步（模式被另一面板或设置页改变）：更新按钮 presentation——ComboBoxButton 属性监听刷新文本。 */
    fun syncFromSettings() {
        val presentation = buttonPresentation ?: return
        presentation.text = modeLabel(currentMode())
    }

    /** 当前模式文案（测试断言用——与按钮显示同源）。 */
    fun currentLabelForTest(): String = modeLabel(currentMode())

    private fun currentMode(): SqlListDisplayMode = PluginSettings.getInstance().sqlListDisplayMode()

    private fun modeLabel(mode: SqlListDisplayMode): String = when (mode) {
        SqlListDisplayMode.SINGLE_LINE -> MpSqlVisualizerBundle.message("log.list.displayMode.single")
        SqlListDisplayMode.KEYWORD_WRAP -> MpSqlVisualizerBundle.message("log.list.displayMode.keywordWrap")
        SqlListDisplayMode.FORMATTED -> MpSqlVisualizerBundle.message("log.list.displayMode.formatted")
    }
}
