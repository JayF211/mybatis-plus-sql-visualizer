package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.Timer

/**
 * 单击 SQL 预览（T-ClickPreview）：主日志列表 / 历史 tab 二级列表 / F-7 历史窗列表共用。
 *
 * 背景：列表条目多时悬停 tooltip（renderer toolTipText = 完整 SQL）逐行弹出/销毁、
 * 连动可变行高重排，滑过 N 行成本 × N 叠加成冻结。改为「单击出预览、双击开完整弹窗」，
 * 悬停零成本。
 *
 * 交互（[onClick] 由面板 MouseAdapter 转发 clickCount）：
 * - 单击：延迟 [CLICK_PREVIEW_DELAY_MS] 出预览弹窗（内容 = [previewSql] 选中条目 SQL，
 *   锚定行左上角）；延迟内第二击先到 → 定时器被停，不闪预览；
 * - 双击：停定时器 + 关旧预览（随后面板自行 openPreview 走完整弹窗）。
 *
 * 弹窗形态：只读等宽 JTextArea（长 SQL 可滚动、文字可选中复制），Esc / 点外 / 其他窗口
 * 打开即关；不抢焦点，列表键盘操作不受影响。
 *
 * headless 测试约束：[show] 依赖真实屏幕（RelativePoint → getLocationOnScreen），
 * 测试只走 [build]（构建不显示）与 [onClick]/[isScheduled]/[currentPopup] 交互断言。
 */
internal class SqlClickPreview(
    private val list: javax.swing.JList<*>,
    /** 当前选中条目的预览 SQL；null = 无选中不弹。 */
    private val previewSql: () -> String?,
) {

    private var popup: JBPopup? = null

    private val timer = Timer(CLICK_PREVIEW_DELAY_MS, null).apply {
        isRepeats = false
        addActionListener { show() }
    }

    /** 列表单击处理：单击延迟出预览；双击停表 + 关旧预览。 */
    fun onClick(clickCount: Int) {
        if (clickCount >= 2) {
            timer.stop()
            hide()
        } else {
            timer.restart()
        }
    }

    /** 关闭当前预览弹窗（无则空操作）。 */
    fun hide() {
        popup?.let { if (it.isVisible) it.cancel() }
        popup = null
    }

    /** 面板 dispose：停定时器 + 关弹窗。 */
    fun dispose() {
        timer.stop()
        hide()
    }

    /** 定时器是否挂起中（测试断言：单击已排程 / 双击已取消）。 */
    internal val isScheduled: Boolean get() = timer.isRunning

    /** 当前预览弹窗（测试断言：双击后不残留）。 */
    internal val currentPopup: JBPopup? get() = popup

    /** Timer 到点：对当前选中条目弹出预览（新弹前先关旧的）。 */
    private fun show() {
        val sql = previewSql() ?: return
        popup?.let { if (it.isVisible) it.cancel() }
        val next = build(sql)
        popup = next
        val index = list.selectedIndex
        val rowPoint = if (index >= 0) list.indexToLocation(index) else null
        if (rowPoint != null) {
            next.show(RelativePoint(list, rowPoint))
        } else {
            next.showUnderneathOf(list)
        }
    }

    companion object {
        /** 单击 → 预览延迟（ms）：正常双击间隔（150~250ms）内第二击先到 → 不闪预览。 */
        internal const val CLICK_PREVIEW_DELAY_MS = 300

        /**
         * 构建预览弹窗（不显示）：只读等宽 JTextArea + JScrollPane，内容自适应尺寸，
         * 可拖角调整大小。internal 供测试直接构建断言内容（headless 不可 show）。
         */
        internal fun build(sql: String): JBPopup {
            val area = JTextArea(sql).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                font = Font(Font.MONOSPACED, Font.PLAIN, 13)
                border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            }
            val content = JScrollPane(area).apply {
                preferredSize = Dimension(PREFERRED_WIDTH, PREFERRED_HEIGHT)
            }
            return JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, null)
                .setRequestFocus(false)
                .setResizable(true)
                .setMovable(false)
                .setCancelOnClickOutside(true)
                .setCancelOnOtherWindowOpen(true)
                .setLocateByContent(true)
                .createPopup()
        }

        private const val PREFERRED_WIDTH = 520
        private const val PREFERRED_HEIGHT = 300
    }
}
