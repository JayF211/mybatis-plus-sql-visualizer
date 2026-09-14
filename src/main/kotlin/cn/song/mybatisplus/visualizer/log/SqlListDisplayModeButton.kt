package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem
import javax.swing.SwingConstants

/**
 * SQL 列表显示模式下拉按钮（T-ListMode；T-MergeFollowup-04）。
 *
 * 与 [SqlLogPanel] 的「SQL类型」按钮**完全同构**（普通 [JButton] + ArrowDown 图标 + 弹 [JPopupMenu]
 * 单选菜单）——复查修正：曾改平台 ComboBoxAction，但其 ComboBoxButton 在手动创建场景下字体/边框
 * 与同工具栏的既有按钮不一致（用户反馈应与「SQL类型」靠齐），且构造时取一次文本需手工驱动，遂弃用。
 * 单选菜单当前项由 JRadioButtonMenuItem 勾选态表达。
 *
 * 两面板（SQL 日志/SQL 历史）共享同一设置键，选择后广播 SETTINGS_CHANGE_TOPIC 由既有监听统一刷新；
 * [syncFromSettings] 供跨面板/设置页变更时回写按钮文本。
 */
internal class SqlListDisplayModeButton(private val project: com.intellij.openapi.project.Project) : JButton() {

    init {
        isFocusable = false
        icon = AllIcons.General.ArrowDown
        horizontalTextPosition = SwingConstants.LEFT // 图标在文字右侧（下拉按钮惯例，与 SQL类型 同款）
        toolTipText = MpSqlVisualizerBundle.message("log.list.displayMode")
        text = modeLabel(currentMode())
        addActionListener { showPopup() }
    }

    /** 弹三选单选菜单（当前项勾选；选择写设置并广播）。 */
    private fun showPopup() {
        val popup = JPopupMenu()
        val group = ButtonGroup()
        SqlListDisplayMode.values().forEach { mode ->
            val item = JRadioButtonMenuItem(modeLabel(mode), currentMode() == mode)
            item.addActionListener {
                val settings = PluginSettings.getInstance()
                if (settings.state.logListSqlDisplayMode != mode.name) {
                    settings.state.logListSqlDisplayMode = mode.name
                    project.messageBus.syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged()
                }
            }
            group.add(item)
            popup.add(item)
        }
        popup.show(this, 0, height + 2)
    }

    /** 程序化回同步（模式被另一面板或设置页改变）：刷新按钮文本。 */
    fun syncFromSettings() {
        val label = modeLabel(currentMode())
        if (text != label) text = label
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
