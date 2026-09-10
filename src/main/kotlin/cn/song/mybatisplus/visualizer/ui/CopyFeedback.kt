package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import javax.swing.JButton
import javax.swing.Timer

/**
 * T-ExecCopyFix：复制成功反馈（文本按钮适配版）。
 *
 * 语义对齐 SqlClickPreview.flashCopied（图标按钮版，T-ClickPreview-05 交付）：复制动作
 * 成功后按钮给出可感知反馈——图标换 [AllIcons.Actions.Checked] 对号，文本按钮额外把
 * 文案短暂换成 `panel.copied`（「已复制」），一次性 [Timer] 到期恢复原文案与原图标。
 *
 * 用一次性 Timer 不引入常驻定时器（同先例）；按钮复用即过期，无 Disposer 义务。
 * 多次快速点击时后续调用重置前次的视觉态（原值取自按钮当前值，仍能正确恢复）。
 */
internal fun flashCopied(button: JButton, durationMs: Int = 1500) {
    val prevText = button.text
    val prevIcon = button.icon
    val prevToolTip = button.toolTipText
    button.icon = AllIcons.Actions.Checked
    button.text = MpSqlVisualizerBundle.message("panel.copied")
    button.toolTipText = MpSqlVisualizerBundle.message("panel.copied")
    Timer(durationMs, null).apply {
        isRepeats = false
        addActionListener {
            button.icon = prevIcon
            button.text = prevText
            button.toolTipText = prevToolTip
        }
        start()
    }
}
