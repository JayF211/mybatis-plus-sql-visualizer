package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.ui.highlight.SqlLexer
import cn.song.mybatisplus.visualizer.ui.highlight.SqlTokenTypes
import com.intellij.psi.tree.IElementType

/**
 * 列表条目 SQL 着色（T-M31.13）：SqlLexer 分词 → 每 token 按类型包 `<span style=color>` 着色。
 *
 * 颜色对齐 [cn.song.mybatisplus.visualizer.ui.highlight.SqlHighlightColors] 暗色值（One Dark 风格）——
 * 列表渲染不依赖编辑器配色主题，固定取暗色系（工具窗口深色背景下可读）。
 * 纯逻辑可单测；HTML 用于 JBList cell renderer（JLabel 原生支持 HTML）。
 */
object SqlListHtmlRenderer {

    private val COLOR_BY_TYPE: Map<IElementType, String> = mapOf(
        SqlTokenTypes.KEYWORD to "#C678DD",
        SqlTokenTypes.TABLE to "#D55FDE",
        SqlTokenTypes.IDENTIFIER to "#61AFEF",
        SqlTokenTypes.STRING to "#98C379",
        SqlTokenTypes.NUMBER to "#D19A66",
        SqlTokenTypes.COMMENT to "#7F848E",
        SqlTokenTypes.OPERATOR to "#ABB2BF",
        SqlTokenTypes.PAREN to "#ABB2BF",
    )

    /** 渲染为 HTML 片段（无 `<html>` 包裹，调用方按需包裹；文本已 HTML 转义防注入）。 */
    fun render(sql: String): String {
        if (sql.isEmpty()) return ""
        val lexer = SqlLexer()
        lexer.start(sql, 0, sql.length, 0)
        val sb = StringBuilder(sql.length + 64)
        while (true) {
            val type = lexer.tokenType ?: break
            val text = lexer.tokenText
            if (text.isNotEmpty()) {
                val color = COLOR_BY_TYPE[type]
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

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
