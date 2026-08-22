package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.diff.SqlDiffer
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JSplitPane
import javax.swing.text.DefaultHighlighter

/**
 * SQL 并排 Diff 弹窗（O-4 Phase C）。
 *
 * 并排展示两个 SQL 文本，差异行高亮（新增绿色 / 删除红色），
 * 方便 Code Review 时对比两个 Wrapper 链的 SQL 差异。
 *
 * 实现：双 [JBTextArea] 只读文本区 + 行级背景色高亮（轻量实现，无需两个完整编辑器）。
 */
class SqlDiffDialog(
    private val project: Project,
    private val leftSql: String,
    private val rightSql: String,
    private val leftTitle: String,
    private val rightTitle: String,
) : DialogWrapper(project, true) {

    private val leftArea = ReadOnlyDiffArea()
    private val rightArea = ReadOnlyDiffArea()

    init {
        title = "SQL Diff"
        init()
    }

    override fun createCenterPanel(): JComponent {
        // 计算 diff
        val diff = SqlDiffer.computeDiff(leftSql, rightSql)
        val leftLines = leftSql.split("\n")
        val rightLines = rightSql.split("\n")

        // 渲染文本
        leftArea.text = leftSql
        rightArea.text = rightSql

        // 高亮差异行
        leftArea.highlightLines(computeLeftHighlightLines(diff, leftLines.size), Color(0xEF5350, true).let { Color(it.red, it.green, it.blue, 50) })
        rightArea.highlightLines(computeRightHighlightLines(diff, rightLines.size), Color(0x66BB6A, true).let { Color(it.red, it.green, it.blue, 50) })

        // 并排布局
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
            createPaddedPane(leftTitle, leftArea),
            createPaddedPane(rightTitle, rightArea),
        ).apply {
            resizeWeight = 0.5
            isContinuousLayout = true
            dividerSize = 4
            border = JBUI.Borders.empty(4, 8)
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            preferredSize = Dimension(1100, 600)
            add(split, BorderLayout.CENTER)
        }
    }

    /** 计算左侧需要高亮（红色 = 删除）的行号集合。 */
    private fun computeLeftHighlightLines(diff: List<SqlDiffer.DiffEntry>, lineCount: Int): Set<Int> {
        val lines = mutableSetOf<Int>()
        for (entry in diff) {
            when (entry.type) {
                SqlDiffer.DiffType.DELETE -> entry.oldLineNumber?.let { lines.add(it) }
                else -> {}
            }
        }
        return lines
    }

    /** 计算右侧需要高亮（绿色 = 新增）的行号集合。 */
    private fun computeRightHighlightLines(diff: List<SqlDiffer.DiffEntry>, lineCount: Int): Set<Int> {
        val lines = mutableSetOf<Int>()
        for (entry in diff) {
            when (entry.type) {
                SqlDiffer.DiffType.INSERT -> entry.newLineNumber?.let { lines.add(it) }
                else -> {}
            }
        }
        return lines
    }

    private fun createPaddedPane(title: String, area: ReadOnlyDiffArea): JBPanel<JBPanel<*>> {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(JBLabel(title).apply {
                border = JBUI.Borders.empty(4, 8)
                font = font.deriveFont(Font.BOLD)
            }, BorderLayout.NORTH)
            add(JBScrollPane(area).apply {
                border = JBUI.Borders.empty(0, 8, 8, 8)
            }, BorderLayout.CENTER)
        }
    }

    companion object {
        /** 弹窗默认尺寸。 */
        const val DEFAULT_WIDTH = 1100
        const val DEFAULT_HEIGHT = 600
    }
}

/**
 * 只读差异展示文本区（O-4 Phase C）：等宽字体 + 行级背景色高亮。
 *
 * 轻量实现：用 [JBTextArea] + 行背景色标记，避免两个完整编辑器的开销。
 */
internal class ReadOnlyDiffArea : JBTextArea() {
    private val highlightLines = mutableSetOf<Int>()

    init {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap = false
        border = JBUI.Borders.empty(4, 8)
        tabSize = 4
    }

    /** 设置需要高亮的行号集合（1-based）及颜色。 */
    fun highlightLines(lines: Set<Int>, color: Color) {
        highlightLines.clear()
        highlightLines.addAll(lines)
        // 通过 highlighter 添加段高亮（行背景色）
        val hl = highlighter
        hl.removeAllHighlights()
        val linesContent = text.split("\n")
        var offset = 0
        for ((index, line) in linesContent.withIndex()) {
            if ((index + 1) in lines) {
                val endOffset = offset + line.length
                if (offset < endOffset) {
                    runCatching { hl.addHighlight(offset, endOffset, DefaultHighlighter.DefaultHighlightPainter(color)) }
                }
            }
            offset += line.length + 1 // +1 for \n
        }
        revalidate()
        repaint()
    }
}
