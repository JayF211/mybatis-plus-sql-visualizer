package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.awt.RelativePoint
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import java.awt.datatransfer.StringSelection
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
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
 * 交互（[onClick] 由面板 MouseAdapter 转发 clickCount 与坐标，仅左键）：
 * - 单击落在**真实行**上：捕获该行 SQL，延迟 [CLICK_PREVIEW_DELAY_MS] 后在**该行下缘**弹出
 *   预览（不遮被点行；屏幕边缘由平台自动回调贴边）；延迟内第二击先到 → 定时器被停，不闪预览；
 * - 单击落在空白区（无行/行下留白）：立即停表并关预览——不借旧选中复活预览（二轮用户反馈：
 *   点其他地方后未再单击 SQL，预览又出现）；
 * - 双击：停定时器 + 关旧预览（随后面板自行 openPreview 走完整弹窗）。
 *
 * 弹窗形态：头部复制/关闭图标按钮 + 可编辑等宽 JTextArea（用户可直接选择、编辑、按钮复制）；
 * 不自动抢焦点（setRequestFocus(false)，列表键盘操作不受影响；点进文本区即可编辑），
 * Esc / 点外 / 其他窗口打开即关，可拖角调整大小。
 *
 * headless 测试约束：show 依赖真实屏幕（RelativePoint → getLocationOnScreen），
 * 测试走 [build]（构建不显示）、[buildContent]（按钮回调直录）、[onClick]/[anchorPointFor]/
 * [isScheduled]/[currentPopup] 交互断言。
 */
internal class SqlClickPreview(
    private val list: javax.swing.JList<*>,
    /** 指定行号的预览 SQL；null = 该行不可预览。点击时捕获，定时器到点直接用，不依赖当时选中态。 */
    private val sqlAt: (Int) -> String?,
) {

    private var popup: JBPopup? = null

    /** 本次点击待预览的行号与 SQL（[onClick] 命中行时捕获）。 */
    private var pendingIndex: Int = -1
    private var pendingSql: String? = null

    private val timer = Timer(CLICK_PREVIEW_DELAY_MS, null).apply {
        isRepeats = false
        addActionListener { show() }
    }

    /** 列表单击处理：命中行才排程预览；空白区立即收表关预览；双击停表关旧预览。 */
    fun onClick(clickCount: Int, clickPoint: Point) {
        if (clickCount >= 2) {
            timer.stop()
            hide()
            return
        }
        val index = list.locationToIndex(clickPoint)
        val hitRow = index >= 0 && list.getCellBounds(index, index)?.contains(clickPoint) == true
        pendingIndex = index
        pendingSql = if (hitRow) sqlAt(index) else null
        if (pendingSql == null) {
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

    /** 定时器是否挂起中（测试断言：单击已排程 / 空白区已取消）。 */
    internal val isScheduled: Boolean get() = timer.isRunning

    /** 当前预览弹窗（测试断言：外部关闭后不残留）。 */
    internal val currentPopup: JBPopup? get() = popup

    /**
     * 指定行的预览锚点：该行**下缘**左侧留 2px 缝（弹窗在行下方展开，不遮被点行）。
     * 行不可得返回 null（调用方回退 showUnderneathOf）。屏幕下方放不下时由平台自动贴边回调。
     */
    internal fun anchorPointFor(index: Int): Point? {
        val bounds = list.getCellBounds(index, index) ?: return null
        return Point(bounds.x, bounds.y + bounds.height + ANCHOR_GAP_PX)
    }

    /** Timer 到点：对点击时捕获的行弹出预览（新弹前先关旧的）。 */
    private fun show() {
        val sql = pendingSql ?: return
        popup?.let { if (it.isVisible) it.cancel() }
        val next = buildPopup(sql)
        popup = next
        val anchor = if (pendingIndex >= 0) anchorPointFor(pendingIndex) else null
        if (anchor != null) {
            next.show(RelativePoint(list, anchor))
        } else {
            next.showUnderneathOf(list)
        }
    }

    /** 构建预览弹窗（实例路径）：复制走系统剪贴板，关闭走 [hide]。 */
    private fun buildPopup(sql: String): JBPopup {
        val content = buildContent(
            sql,
            copyText = { CopyPasteManager.getInstance().setContents(StringSelection(it)) },
            close = { hide() },
        )
        return JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setRequestFocus(false) // 弹出不抢焦点（列表键盘操作不受影响）；点进文本区即可编辑
            .setFocusable(true)
            .setResizable(true)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    popup = null // 点外/Esc 等外部关闭后清引用，杜绝复用已关闭弹窗
                }
            })
            .createPopup()
    }

    companion object {
        /** 单击 → 预览延迟（ms）：正常双击间隔（150~250ms）内第二击先到 → 不闪预览。 */
        internal const val CLICK_PREVIEW_DELAY_MS = 300

        /** 弹窗锚点与被点行下缘的间距（px）。 */
        private const val ANCHOR_GAP_PX = 2

        /**
         * 构建预览弹窗（不显示，测试便捷入口）：回调为空实现。
         */
        internal fun build(sql: String): JBPopup {
            val content = buildContent(sql, copyText = {}, close = {})
            return JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, null)
                .setRequestFocus(false)
                .setFocusable(true)
                .setResizable(true)
                .setMovable(false)
                .setCancelOnClickOutside(true)
                .setCancelOnOtherWindowOpen(true)
                .createPopup()
        }

        /**
         * 构建预览内容组件：头部「复制 | 关闭」图标按钮 + 可编辑等宽文本区（长 SQL 可滚动，
         * 用户可直接选择/编辑内容后经按钮或 Ctrl+C 复制）。按钮回调由调用方注入（测试直录断言）。
         */
        internal fun buildContent(sql: String, copyText: (String) -> Unit, close: () -> Unit): JComponent {
            val area = JTextArea(sql).apply {
                isEditable = true // 可编辑：方便选择与复制（T-ClickPreview 二轮用户要求）
                lineWrap = true
                wrapStyleWord = true
                font = Font(Font.MONOSPACED, Font.PLAIN, 13)
                border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            }
            val copyButton = JButton(AllIcons.Actions.Copy).apply {
                toolTipText = MpSqlVisualizerBundle.message("log.preview.copy")
                isFocusable = false
                addActionListener { copyText(area.text) } // 复制当前内容（含用户编辑后文本）
            }
            val closeButton = JButton(AllIcons.Actions.Close).apply {
                toolTipText = MpSqlVisualizerBundle.message("log.preview.close")
                isFocusable = false
                addActionListener { close() }
            }
            val header = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
                add(copyButton)
                add(closeButton)
            }
            val scroll = JScrollPane(area).apply {
                preferredSize = Dimension(PREFERRED_WIDTH, PREFERRED_HEIGHT)
            }
            return JPanel(BorderLayout()).apply {
                isOpaque = true
                add(header, BorderLayout.NORTH)
                add(scroll, BorderLayout.CENTER)
            }
        }

        private const val PREFERRED_WIDTH = 520
        private const val PREFERRED_HEIGHT = 300
    }
}
