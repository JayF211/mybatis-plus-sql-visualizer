package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * SQL → HTML 渲染器（M8-05，悬停预览复用高亮）。
 *
 * 用 [SqlLexer] 分词，把关键字/表名/列名/字符串/数字/注释包上带前景色的
 * `<span>`，供 [cn.song.mybatisplus.visualizer.ui.SqlPreviewDocumentationProvider]
 * 生成带语法高亮的悬停弹层。颜色取自 [SqlHighlightColors]（随 IDE 主题）。
 */
object SqlHtmlRenderer {

    /** 渲染带色 HTML（无独立字体样式，由调用方包裹 pre 块）。 */
    fun render(sql: String): String {
        val sb = StringBuilder(sql.length + 64)
        val lexer = SqlLexer()
        lexer.start(sql, 0, sql.length, 0)
        while (true) {
            val type = lexer.tokenType ?: break
            val text = StringUtil.escapeXmlEntities(lexer.tokenText)
            if (type == TokenType.WHITE_SPACE) {
                sb.append(text)
            } else {
                val color = colorFor(type)
                if (color != null) sb.append("<span style='color:#$color;'>$text</span>") else sb.append(text)
            }
            lexer.advance()
        }
        return sb.toString()
    }

    /** 取 token 对应前景色（hex 6 位，无颜色时 null）。 */
    private fun colorFor(type: IElementType): String? {
        val key = SqlSyntaxHighlighter().getTokenHighlights(type).firstOrNull() ?: return null
        val fg = EditorColorsManager.getInstance().globalScheme.getAttributes(key)?.foregroundColor ?: return null
        return Integer.toHexString(fg.rgb and 0xFFFFFF).padStart(6, '0')
    }
}
