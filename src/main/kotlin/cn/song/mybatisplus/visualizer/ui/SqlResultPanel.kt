package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout

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
 * 非查询语句（INSERT/UPDATE/DELETE 等）的结果直接在此面板展示。
 */
class SqlResultPanel : JBPanel<SqlResultPanel>(BorderLayout()) {

    private val statusLabel = JBLabel()
    private val infoLabel = JBLabel().apply {
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(2, 8)
    }

    init {
        border = JBUI.Borders.empty(4, 8)
        add(infoLabel, BorderLayout.CENTER)
    }

    /**
     * 显示执行中状态。
     */
    fun showLoading(dataSourceName: String = "") {
        infoLabel.text = if (dataSourceName.isNotEmpty()) {
            "<html>${MpSqlVisualizerBundle.message("execute.result.loading")} | " +
                "${MpSqlVisualizerBundle.message("execute.datasource.label", dataSourceName)}</html>"
        } else {
            MpSqlVisualizerBundle.message("execute.result.loading")
        }
        infoLabel.foreground = JBColor.GRAY
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
        val dsInfo = if (dataSourceName.isNotEmpty()) {
            " | ${MpSqlVisualizerBundle.message("execute.datasource.label", dataSourceName)}"
        } else ""
        infoLabel.text = "<html>${MpSqlVisualizerBundle.message("execute.result.affected", affectedRows, elapsedMs)}$dsInfo</html>"
        infoLabel.foreground = JBColor.namedColor("Label.successForeground", JBColor(0x00, 0x00))
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
        val dsInfo = if (dataSourceName.isNotEmpty()) {
            " | ${MpSqlVisualizerBundle.message("execute.datasource.label", dataSourceName)}"
        } else ""
        infoLabel.text = "<html>${MpSqlVisualizerBundle.message("execute.result.rows", rowCount, elapsedMs)}$dsInfo</html>"
        infoLabel.foreground = JBColor.namedColor("Label.successForeground", JBColor(0x00, 0x00))
        revalidate()
        repaint()
    }

    /**
     * 显示错误信息。
     *
     * @param error 错误信息
     */
    fun showError(error: String) {
        infoLabel.text = "<html>${MpSqlVisualizerBundle.message("execute.result.error", error)}</html>"
        infoLabel.foreground = JBColor.namedColor("ErrorForeground", JBColor.RED)
        revalidate()
        repaint()
    }

    /**
     * 显示空状态（无执行）。
     */
    fun showEmpty() {
        infoLabel.text = ""
        revalidate()
        repaint()
    }

    /**
     * 清空结果展示。
     */
    fun clear() {
        showEmpty()
    }
}
