package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * SQL 预览面板（M3-02/03 共享组件，F-07/F-08）。
 *
 * 弹窗 [SqlPreviewDialog] 与工具窗口共用本面板，展示：
 *  - 顶部：元信息（Wrapper 类型 / 表名 / 参数对照表）
 *  - 中部：只读 SQL 展示区（等宽字体）
 *  - 复制能力：[copySql] 将当前 SQL 写入系统剪贴板（F-08）
 *
 * 说明：SQL 语法高亮需要语言注入或 com.intellij.database 插件，列为后续增强（F-07 部分）。
 */
class SqlPreviewPanel : JBPanel<SqlPreviewPanel>(BorderLayout()) {

    private val metaLabel = JBLabel()
    private val sqlArea = JBTextArea().apply {
        isEditable = false
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        border = JBUI.Borders.empty(8)
    }

    /** 当前展示的预览结果（供复制/刷新）。 */
    private var current: PreviewResult? = null

    init {
        // 顶部元信息
        metaLabel.border = JBUI.Borders.empty(6, 8)
        add(metaLabel, BorderLayout.NORTH)

        // 中部 SQL 展示区
        add(JBScrollPane(sqlArea).apply { preferredSize = Dimension(560, 320) }, BorderLayout.CENTER)
    }

    /**
     * 更新展示内容。
     *
     * @param result 预览结果
     */
    fun showResult(result: PreviewResult) {
        current = result
        sqlArea.text = result.sql

        val meta = buildList {
            result.wrapperType?.let { add("类型: ${it.name}") }
            result.tableName?.let { add("表: $it") }
        }.joinToString("   ")
        val paramLine = result.paramTable?.let { "   $it" } ?: ""
        val warningLine = result.warning?.let { "   ⚠ $it" } ?: ""
        metaLabel.text = meta + paramLine + warningLine
        // 风险警示置红（UPDATE/DELETE 全表操作）
        metaLabel.foreground = if (result.warning != null) JBColor.RED else JBUI.CurrentTheme.Label.foreground()
    }

    /**
     * 一键复制当前 SQL 到剪贴板（F-08）。
     *
     * @return 是否复制成功（无内容时返回 false）
     */
    fun copySql(): Boolean {
        val text = current?.sql ?: sqlArea.text
        if (text.isBlank()) return false
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
        return true
    }

    /**
     * 清空展示区（工具窗口"清空"按钮）。
     */
    fun clear() {
        current = null
        sqlArea.text = ""
        metaLabel.text = ""
    }
}
