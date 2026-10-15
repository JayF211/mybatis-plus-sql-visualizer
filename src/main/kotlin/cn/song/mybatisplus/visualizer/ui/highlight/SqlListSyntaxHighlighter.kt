package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.psi.tree.IElementType

/**
 * 编辑器版 SQL 高亮器：[SqlLexer] 分词 + [SqlHighlightColors] canonical 配色键。
 *
 * [ATTR_BY_TYPE] 是编辑器及列表片段投影共用的 token → key 映射；key 在当前 scheme
 * 显式配置优先，随后回退旧 MP_SQL_* 用户配置和平台语言配色，不写死主题颜色。
 */
class SqlListSyntaxHighlighter : SyntaxHighlighter {

    override fun getHighlightingLexer(): Lexer = SqlLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> =
        ATTR_BY_TYPE[tokenType]?.let { arrayOf(it) } ?: emptyArray()

    companion object {
        /** token 类型 → 配色键：列表片段渲染与编辑器高亮共用的唯一映射（改色只动这里）。 */
        internal val ATTR_BY_TYPE: Map<IElementType, TextAttributesKey> = mapOf(
            SqlTokenTypes.KEYWORD to SqlHighlightColors.KEYWORD,
            SqlTokenTypes.TABLE to SqlHighlightColors.TABLE,
            SqlTokenTypes.IDENTIFIER to SqlHighlightColors.IDENTIFIER,
            SqlTokenTypes.STRING to SqlHighlightColors.STRING,
            SqlTokenTypes.NUMBER to SqlHighlightColors.NUMBER,
            SqlTokenTypes.COMMENT to SqlHighlightColors.COMMENT,
            SqlTokenTypes.OPERATOR to SqlHighlightColors.OPERATOR,
            SqlTokenTypes.PAREN to SqlHighlightColors.PAREN,
        )
    }
}
