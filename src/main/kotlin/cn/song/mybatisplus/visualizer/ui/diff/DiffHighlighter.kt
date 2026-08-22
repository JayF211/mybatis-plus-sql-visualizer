package cn.song.mybatisplus.visualizer.ui.diff

import cn.song.mybatisplus.visualizer.ui.diff.SqlDiffer.DiffEntry
import cn.song.mybatisplus.visualizer.ui.diff.SqlDiffer.DiffType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color

/**
 * SQL 差异高亮渲染器（O-4 Phase B）。
 *
 * 将 [SqlDiffer.computeDiff] 的结果渲染到 IntelliJ 编辑器的行级高亮：
 *  - 新增行：绿色背景
 *  - 变更行：黄色背景
 *  - 删除行：单视图模式下不渲染（已不在当前编辑器中）
 *
 * 高亮由本类跟踪引用，[clear] 仅移除本插件添加的高亮（不影响语法高亮）。
 */
object DiffHighlighter {

    /** 高亮层优先级（在语法高亮之上，选区之下）。 */
    private const val HIGHLIGHTER_LAYER = HighlighterLayer.SELECTION - 100

    /** 本插件当前添加的高亮引用（MarkupModel 无公共 API 枚举高亮，须自行跟踪）。 */
    private val activeHighlighters: MutableList<RangeHighlighter> = mutableListOf()

    /**
     * 在编辑器中渲染差异高亮（先清除旧高亮，再渲染新的）。
     *
     * @param editor  目标编辑器
     * @param diff    diff 结果
     * @param newSql  新 SQL（当前编辑器内容）
     */
    fun highlight(editor: Editor, diff: List<DiffEntry>, newSql: String) {
        val markupModel = editor.markupModel
        clear(markupModel)
        val newLines = newSql.split("\n")
        for (entry in diff) {
            when (entry.type) {
                DiffType.INSERT -> addLineHighlight(markupModel, newLines, entry.newLineNumber, INSERT_ATTRS)
                DiffType.CHANGE -> addLineHighlight(markupModel, newLines, entry.newLineNumber, CHANGE_ATTRS)
                DiffType.DELETE -> { /* 单视图模式不渲染删除行 */ }
                DiffType.EQUAL -> { /* 无高亮 */ }
            }
        }
    }

    /** 清除本插件添加的所有高亮。 */
    fun clear(markupModel: MarkupModel) {
        val toRemove = activeHighlighters.toList()
        activeHighlighters.clear()
        for (h in toRemove) {
            runCatching { markupModel.removeHighlighter(h) }
        }
    }

    /** 在指定行号（1-based）渲染背景色高亮。 */
    private fun addLineHighlight(
        markupModel: MarkupModel,
        lines: List<String>,
        lineNumber: Int?,
        attrs: TextAttributes,
    ) {
        if (lineNumber == null || lineNumber < 1 || lineNumber > lines.size) return
        var startOffset = 0
        for (i in 0 until lineNumber - 1) {
            startOffset += lines[i].length + 1 // +1 for \n
        }
        val endOffset = startOffset + lines[lineNumber - 1].length
        if (startOffset >= endOffset) return // 空行不渲染
        val highlighter = markupModel.addRangeHighlighter(
            startOffset,
            endOffset,
            HIGHLIGHTER_LAYER,
            attrs,
            HighlighterTargetArea.EXACT_RANGE,
        )
        activeHighlighters += highlighter
    }

    // ---- 颜色属性（半透明背景，亮/暗主题均适配） ----

    private val INSERT_ATTRS = TextAttributes().apply {
        backgroundColor = Color(0x4CAF50, true).let { Color(it.red, it.green, it.blue, 40) }
        effectType = EffectType.BOXED
        effectColor = Color(0x4CAF50)
    }

    private val CHANGE_ATTRS = TextAttributes().apply {
        backgroundColor = Color(0xFFC107, true).let { Color(it.red, it.green, it.blue, 40) }
        effectType = EffectType.BOXED
        effectColor = Color(0xFFC107)
    }
}
