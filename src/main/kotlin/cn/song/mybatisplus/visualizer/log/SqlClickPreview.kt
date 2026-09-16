package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.ui.highlight.SqlHighlightColors
import cn.song.mybatisplus.visualizer.ui.highlight.SqlListSyntaxHighlighter
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.awt.RelativePoint
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

/** 预览内容持有者：面板组件 + 编辑器实例（测试断言与释放用）。 */
internal class SqlClickPreviewContent(val component: JComponent, val editor: Editor)

/**
 * 单击预览浮层文本（T-ListPreviewCopy）：分号收尾 + 按设置「预览时格式化还原 SQL」
 * （logFormatSql，默认开=默认格式化显示）决定是否 [SqlFormatter.format] 多行展开。
 * 与双击弹窗 `toPreviewResult` 的 `formatIfNeeded(withTerminator(...))` 同序——
 * 先补分号再格式化（生产已验证的分号处理路径）。纯函数，开关由调用方读设置传入。
 */
internal fun clickPreviewSql(sql: String, formatSql: Boolean): String =
    if (formatSql) SqlFormatter.format(withTerminator(sql)) else withTerminator(sql)

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
 * - 多选进行中（Ctrl/Shift/拖框 ≥2 行）单击：不排程、已弹出的关闭（T-ListPreviewCopy 用户反馈：
 *   同时选择多个 SQL 时不要再出现单击预览）；[show] 到点时兜底复查，封 300ms 延迟窗内补选竞态。
 *
 * 弹窗形态：头部复制/关闭图标按钮 + **SQL 高亮可编辑编辑器**（三轮用户要求：高亮展示 +
 * 可临时改字段值再复制）。高亮走 [SqlListSyntaxHighlighter]（SqlLexer 分词 +
 * SqlHighlightColors 配色键，与日志列表条目**共用同一 token→配色映射**——四轮用户反馈：
 * 预览高亮须与列表同色；无 PSI、不依赖 Database 插件）。
 * 弹出即聚焦内容（setRequestFocus(true)，四轮修复：false 时编辑器拿不到键盘焦点，
 * "可编辑"名存实亡；复制/关闭按钮 isFocusable=false 不抢焦点），点进编辑器直接打字，
 * Esc / 点外 / 其他窗口打开即关，可拖角调整大小。
 *
 * headless 测试约束：show 依赖真实屏幕（RelativePoint → getLocationOnScreen），
 * 测试走 [buildContent]（构建不显示、编辑器用后释放）、[onClick]/[anchorPointFor]/
 * [isScheduled]/[currentPopup] 交互断言。
 */
internal class SqlClickPreview(
    private val project: Project,
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

    /** 列表单击处理：命中行才排程预览；空白区立即收表关预览；双击停表关旧预览；多选进行中不排程。 */
    fun onClick(clickCount: Int, clickPoint: Point) {
        if (clickCount >= 2) {
            timer.stop()
            hide()
            return
        }
        // T-ListPreviewCopy：多选（Ctrl/Shift/拖框 ≥2 行）时单击不再排程预览——多选操作被
        // 逐行弹出的单行预览干扰；与空白区同语义：停表并关旧
        if (list.selectedIndices.size > 1) {
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

    /** 面板 dispose：停定时器 + 关弹窗（编辑器经 onClosed 一并释放）。 */
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

    /** Timer 到点：对点击时捕获的行弹出预览（新弹前先关旧的）；已多选则放弃（延迟窗内补选竞态兜底）。 */
    private fun show() {
        val sql = pendingSql ?: return
        if (list.selectedIndices.size > 1) {
            hide()
            return
        }
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

    /** 构建预览弹窗（实例路径）：复制走系统剪贴板（取编辑后当前文本），关闭走 [hide]；编辑器随弹窗关闭释放。 */
    private fun buildPopup(sql: String): JBPopup {
        var editor: Editor? = null
        val content = buildContent(
            project,
            sql,
            copyText = { UiTextClipboard.copy(it) },
            close = { hide() },
        ).also { editor = it.editor }
        return JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content.component, null)
            // 四轮修复：setRequestFocus(false) 导致弹窗内编辑器拿不到键盘焦点，
            // 键盘输入全落到列表上——看似"只读"。改 true：弹出即聚焦内容，
            // 焦点自然落进编辑器，可直接打字编辑（复制/关闭按钮 isFocusable=false 不抢焦点）
            .setRequestFocus(true)
            .setFocusable(true)
            .setResizable(true)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    popup = null // 点外/Esc 等外部关闭后清引用，杜绝复用已关闭弹窗
                    editor?.let { EditorFactory.getInstance().releaseEditor(it) }
                }
            })
            .createPopup()
    }

    companion object {
        /** 单击 → 预览延迟（ms）：正常双击间隔（150~250ms）内第二击先到 → 不闪预览。 */
        internal const val CLICK_PREVIEW_DELAY_MS = 300

        /** 弹窗锚点与被点行下缘的间距（px）。 */
        private const val ANCHOR_GAP_PX = 2

        private const val PREFERRED_WIDTH = 560
        private const val PREFERRED_HEIGHT = 320

        /**
         * 构建预览内容：头部「复制 | 关闭」图标按钮 + SQL 高亮可编辑编辑器。
         * 高亮 = [SqlListSyntaxHighlighter]（SqlLexer 分词 + [SqlHighlightColors] 配色键，
         * 与日志列表条目共用同一映射——四轮用户反馈：预览高亮须与列表同色，直接复用；
         * 不走平台 SQL FileType 方言高亮，也不依赖 Database 插件，缺席环境同样有高亮），
         * 软换行长 SQL 免横向滚动，隐藏行号/折叠/缩进导轨/页底留白的紧凑外观。
         * 按钮回调由调用方注入（测试直录断言）；**调用方负责释放 editor**（弹窗路径经 onClosed）。
         */
        internal fun buildContent(
            project: Project,
            sql: String,
            copyText: (String) -> Unit,
            close: () -> Unit,
        ): SqlClickPreviewContent {
            val document = EditorFactory.getInstance().createDocument(sql)
            val editor = EditorFactory.getInstance().createEditor(document, project)
            (editor as? EditorEx)?.apply {
                // createEditor 静态类型为 Editor，setHighlighter 在 EditorEx（243 javap 核验）；
                // EditorImpl 实现之，非 EditorEx 实现时跳过（降级无高亮纯文本）
                setHighlighter(
                    LexerEditorHighlighter(
                        SqlListSyntaxHighlighter(),
                        EditorColorsManager.getInstance().globalScheme,
                    ),
                )
                settings.apply {
                    isLineNumbersShown = false // 紧凑弹窗不留 gutter
                    isFoldingOutlineShown = false
                    isIndentGuidesShown = false
                    isAdditionalPageAtBottom = false
                    setCaretRowShown(false)
                    setUseSoftWraps(true) // 长 SQL 软换行免横向滚动
                }
            }
            editor.component.preferredSize = Dimension(PREFERRED_WIDTH, PREFERRED_HEIGHT)

            // T-AutoFold F4-b：弹窗编辑器程序性折叠（关闭态 applyTo 内部即清除短路，零外观变更；
            // 开启态由 applyTo 补设 isFoldingOutlineShown=true——上面紧凑外观的 false 被覆盖）
            cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(editor, sql)

            val copyButton = JButton(AllIcons.Actions.Copy).apply {
                toolTipText = MpSqlVisualizerBundle.message("log.preview.copy")
                isFocusable = false
                addActionListener {
                    copyText(editor.document.text) // 复制当前内容（含用户编辑后文本）
                    cn.song.mybatisplus.visualizer.ui.flashCopied(this) // 复制成功反馈：复制图标换对号，短暂后恢复
                }
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
            val panel = JPanel(BorderLayout()).apply {
                add(header, BorderLayout.NORTH)
                add(editor.component, BorderLayout.CENTER)
            }
            return SqlClickPreviewContent(panel, editor)
        }
    }
}
