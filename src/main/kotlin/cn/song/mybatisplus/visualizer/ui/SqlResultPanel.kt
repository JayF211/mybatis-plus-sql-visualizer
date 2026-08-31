package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JButton

/**
 * SQL 执行结果面板（M36）：在 SQL 预览面板下方展示执行状态与结果摘要。
 *
 * 查询结果由 IDEA 原生控制台内联展示（编辑器下方），本面板仅展示：
 *  - 执行状态（成功/失败/执行中）
 *  - 数据源名称
 *  - 影响行数 / 返回行数
 *  - 执行耗时
 *  - 错误信息（执行失败时）
 *
 * M36.5：自动可见性——有状态/错误内容时显示，空状态时隐藏（不占高度，
 * 消除控制台下方空白占位）；宿主容器用 BorderLayout.SOUTH 承载。
 *
 * M36.5.48：错误信息**可复制**——执行失败展示真实原因（SQLException 等），
 * 提供复制按钮（用户实测：状态栏错误文本难以选中复制）。
 */
class SqlResultPanel : JBPanel<SqlResultPanel>(BorderLayout()) {

    private val statusLabel = JBLabel()
    private val infoLabel = JBLabel().apply {
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(1, 4)
    }

    /** M36.5.48：错误复制按钮（仅 showError 时可见），一键复制完整错误文本到剪贴板。
     * M36.5.53：加可见文字（原实现 JButton() 无 text，按钮空白几乎不可见——用户"不方便复制"）。 */
    private val errorCopyButton = JButton(MpSqlVisualizerBundle.message("panel.copyError")).apply {
        isVisible = false
        isOpaque = false
        isContentAreaFilled = false
        border = JBUI.Borders.empty(1, 6)
        font = font.deriveFont(11f)
        foreground = JBColor.namedColor("Link.activeForeground", JBColor.GRAY)
        margin = JBUI.insets(0, 6, 0, 6)
        addActionListener {
            currentError?.let { copyToClipboard(it) }
        }
    }

    /** M36.5.48：当前错误文本（供复制按钮使用）。 */
    private var currentError: String? = null

    /** M36.5.49：面板状态——防止 error 被后续 showEmpty/showLoading 误清（"一闪而过"）。 */
    private enum class State { EMPTY, LOADING, ERROR, SUCCESS }
    private var state = State.EMPTY

    init {
        // 无边框 + 透明背景，与预览面板融为一体
        border = JBUI.Borders.empty()
        isOpaque = false
        add(infoLabel, BorderLayout.CENTER)
        // M36.5.48：错误复制按钮放右侧（CENTER 是 infoLabel）
        val east = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty()
            add(errorCopyButton)
        }
        add(east, BorderLayout.EAST)
        // M36.5：初始隐藏（无内容不占高度）
        isVisible = false
    }

    /** 有内容时显示面板（M36.5 自动可见性统一入口）。 */
    private fun showContent() {
        isVisible = true
    }

    /**
     * 显示临时文件已打开状态（提示用户 Ctrl+Enter 执行）。
     */
    fun showTempFileOpened(dataSourceName: String = "") {
        state = State.EMPTY
        currentError = null
        errorCopyButton.isVisible = false
        val dsInfo = if (dataSourceName.isNotEmpty()) {
            " | ${MpSqlVisualizerBundle.message("execute.datasource.label", dataSourceName)}"
        } else ""
        infoLabel.text = "<html>${MpSqlVisualizerBundle.message("execute.fileOpened")}$dsInfo</html>"
        infoLabel.foreground = JBColor.GRAY
        showContent()
        revalidate()
        repaint()
    }

    /**
     * 显示执行中状态。
     */
    fun showLoading(dataSourceName: String = "") {
        state = State.LOADING
        currentError = null
        errorCopyButton.isVisible = false
        infoLabel.text = if (dataSourceName.isNotEmpty()) {
            "<html>${MpSqlVisualizerBundle.message("execute.result.loading")} | " +
                "${MpSqlVisualizerBundle.message("execute.datasource.label", dataSourceName)}</html>"
        } else {
            MpSqlVisualizerBundle.message("execute.result.loading")
        }
        infoLabel.foreground = JBColor.GRAY
        showContent()
        revalidate()
        repaint()
    }

    /**
     * 显示非查询语句执行结果（影响行数 + 耗时）。
     *
     * @param affectedRows 影响行数
     * @param elapsedMs    执行耗时（毫秒）
     * @param dataSourceName 数据源名称
     */
    fun showUpdateResult(affectedRows: Int, elapsedMs: Long, dataSourceName: String = "") {
        state = State.SUCCESS
        currentError = null
        errorCopyButton.isVisible = false
        val dsInfo = if (dataSourceName.isNotEmpty()) {
            " | ${MpSqlVisualizerBundle.message("execute.datasource.label", dataSourceName)}"
        } else ""
        infoLabel.text = "<html>${MpSqlVisualizerBundle.message("execute.result.affected", affectedRows, elapsedMs)}$dsInfo</html>"
        infoLabel.foreground = JBColor.namedColor("Label.successForeground", JBColor(0x00, 0x00))
        showContent()
        revalidate()
        repaint()
    }

    /**
     * 显示查询结果摘要（行数 + 耗时）。
     * 实际查询数据由原生控制台内联展示。
     *
     * @param rowCount     返回行数
     * @param elapsedMs    执行耗时（毫秒）
     * @param dataSourceName 数据源名称
     */
    fun showQueryResult(rowCount: Int, elapsedMs: Long, dataSourceName: String = "") {
        state = State.SUCCESS
        currentError = null
        errorCopyButton.isVisible = false
        val dsInfo = if (dataSourceName.isNotEmpty()) {
            " | ${MpSqlVisualizerBundle.message("execute.datasource.label", dataSourceName)}"
        } else ""
        infoLabel.text = "<html>${MpSqlVisualizerBundle.message("execute.result.rows", rowCount, elapsedMs)}$dsInfo</html>"
        infoLabel.foreground = JBColor.namedColor("Label.successForeground", JBColor(0x00, 0x00))
        showContent()
        revalidate()
        repaint()
    }

    /**
     * 显示错误信息（M36.5.48：支持复制）。
     *
     * @param error 错误信息——应为真实失败原因（SQLException message 等），
     *              而非笼统超时提示，便于用户定位/复制。
     */
    fun showError(error: String) {
        state = State.ERROR
        currentError = error
        // M36.5.53：转义 HTML（错误含 < > & [charset] 等会破坏布局），并有界显示——
        // 长错误不横向挤压（infoLabel 单行 + tooltip 完整），复制按钮在 EAST 不被挤掉。
        val esc = error.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        infoLabel.text = "<html>${MpSqlVisualizerBundle.message("execute.result.error", esc)}</html>"
        infoLabel.foreground = JBColor.namedColor("ErrorForeground", JBColor.RED)
        infoLabel.toolTipText = error   // M36.5.53：hover 显示完整错误（超长不截断可读）
        // M36.5.48：错误时显示复制按钮（用户可一键复制完整错误文本）
        errorCopyButton.isVisible = true
        errorCopyButton.revalidate()
        errorCopyButton.repaint()
        showContent()
        revalidate()
        repaint()
    }

    /**
     * 显示空状态（无执行）——M36.5：隐藏面板不占高度。
     */
    fun showEmpty() {
        state = State.EMPTY
        currentError = null
        errorCopyButton.isVisible = false
        infoLabel.text = ""
        isVisible = false
        revalidate()
        repaint()
    }

    /**
     * 清空结果展示。
     */
    fun clear() {
        showEmpty()
    }

    /**
     * M36.5.49：执行完成后的"空闲收尾"——清 loading/成功态，但**保留错误**（ERROR 态不清）。
     * 用于 `embedConsole` 同实例回调等"执行完成标志"，避免后续流程把刚显示的 showError
     * 误清（用户实测"错误信息一闪而过"——showError 后紧接着 results refreshed 触发
     * embedConsole 同实例 showEmpty，错误被覆盖）。
     */
    fun showIdle() {
        if (state == State.ERROR) {
            // 保留错误（用户可查看/复制）；仅隐藏复制按钮的 hover 态不变
            revalidate()
            repaint()
            return
        }
        showEmpty()
    }

    /** M36.5.48：复制文本到系统剪贴板。 */
    private fun copyToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
    }

    /** M36.5.48：当前错误文本（测试断言用）。 */
    internal fun currentErrorForTest(): String? = currentError

    /** M36.5.49：当前状态（测试断言用）。 */
    internal fun stateForTest(): String = state.name
}
