package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.ui.highlight.SqlHighlightColors
import cn.song.mybatisplus.visualizer.ui.highlight.SqlLexer
import cn.song.mybatisplus.visualizer.ui.highlight.SqlTokenTypes
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.psi.tree.IElementType

/**
 * SQL 列表显示模式（T-ListMode）：SQL 日志/历史列表条目中 SQL 行的展示形态。
 *
 * @property SINGLE_LINE 单行原文（T-TwoLine 前的形态）
 * @property KEYWORD_WRAP 关键字换行——子句顶格换行、同行内容压一行（默认，长 SQL 快速总览）
 * @property FORMATTED 格式化——多行缩进全展开（阅读细节）
 */
enum class SqlListDisplayMode {
    SINGLE_LINE,
    KEYWORD_WRAP,
    FORMATTED,
}

/** 按显示模式变换 SQL 文本（渲染前调用；变换器复用 [SqlFormatter] 既有实现，字符串原文保留）。 */
internal fun prepareSqlForList(sql: String, mode: SqlListDisplayMode): String = when (mode) {
    SqlListDisplayMode.SINGLE_LINE -> sql
    SqlListDisplayMode.KEYWORD_WRAP -> SqlFormatter.formatClauseLine(sql)
    SqlListDisplayMode.FORMATTED -> SqlFormatter.format(sql)
}

/**
 * 列表条目 SQL 着色（T-M31.13/14）：SqlLexer 分词 → 每 token 按类型包 `<span style=color>` 着色。
 *
 * T-M31.14：颜色改从 [SqlHighlightColors] 的 TextAttributesKey 经 [EditorColorsScheme] 取前景色——
 * **与预览编辑器/预览弹窗同一配色体系（插件自己的 SQL 高亮，One Dark 亮暗双主题随 IDE 切换）**，
 * 不再固定暗色值。scheme 可注入（纯逻辑可单测：传固定色 map）。
 */
object SqlListHtmlRenderer {

    /** 按主题 scheme 取色（跟随 IDE 亮/暗主题，与编辑器高亮一致）。 */
    fun render(sql: String, scheme: EditorColorsScheme = EditorColorsManager.getInstance().globalScheme): String {
        val colorByType = mapOf(
            SqlTokenTypes.KEYWORD to scheme.getAttributes(SqlHighlightColors.KEYWORD).foregroundColor,
            SqlTokenTypes.TABLE to scheme.getAttributes(SqlHighlightColors.TABLE).foregroundColor,
            SqlTokenTypes.IDENTIFIER to scheme.getAttributes(SqlHighlightColors.IDENTIFIER).foregroundColor,
            SqlTokenTypes.STRING to scheme.getAttributes(SqlHighlightColors.STRING).foregroundColor,
            SqlTokenTypes.NUMBER to scheme.getAttributes(SqlHighlightColors.NUMBER).foregroundColor,
            SqlTokenTypes.COMMENT to scheme.getAttributes(SqlHighlightColors.COMMENT).foregroundColor,
            SqlTokenTypes.OPERATOR to scheme.getAttributes(SqlHighlightColors.OPERATOR).foregroundColor,
            SqlTokenTypes.PAREN to scheme.getAttributes(SqlHighlightColors.PAREN).foregroundColor,
        )
        return render(sql) { type -> colorByType[type]?.let { toHex(it) } }
    }

    /** 渲染为 HTML 片段（无 `<html>` 包裹，调用方按需包裹；文本已 HTML 转义防注入）。 */
    fun render(sql: String, colorOf: (IElementType) -> String?): String {
        if (sql.isEmpty()) return ""
        val lexer = SqlLexer()
        lexer.start(sql, 0, sql.length, 0)
        val sb = StringBuilder(sql.length + 64)
        while (true) {
            val type = lexer.tokenType ?: break
            val text = lexer.tokenText
            if (text.isNotEmpty()) {
                val color = colorOf(type)
                if (color != null) {
                    sb.append("<span style='color:").append(color).append("'>")
                        .append(escapeHtml(text)).append("</span>")
                } else {
                    sb.append(escapeHtml(text))
                }
            }
            lexer.advance()
        }
        return sb.toString()
    }

    private fun toHex(color: java.awt.Color): String {
        val r = Integer.toHexString(color.red).padStart(2, '0')
        val g = Integer.toHexString(color.green).padStart(2, '0')
        val b = Integer.toHexString(color.blue).padStart(2, '0')
        return "#$r$g$b"
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("\n", "<br>")  // 换行转 <br>（multiline SQL 在列表 cell 中正确渲染）
        // T-ListMode：空格转 &nbsp;——BasicHTML 折叠连续普通空格，格式化（AND 缩进/子查询缩进）
        // 与关键字换行（AND 前双空格）的缩进层次会塌掉；列表 cell 无 word-wrap，&nbsp; 无副作用
        .replace(" ", "&nbsp;")
}
