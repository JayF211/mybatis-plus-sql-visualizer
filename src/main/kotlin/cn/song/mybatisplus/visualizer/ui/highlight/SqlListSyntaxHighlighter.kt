package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.psi.tree.IElementType

/**
 * 编辑器版 SQL 高亮器（T-ClickPreview-04）：[SqlLexer] 分词 + [SqlHighlightColors] 配色。
 *
 * 单击预览编辑器与日志列表条目共用同一条「token 类型 → 配色键」链路——映射唯一权威
 * [ATTR_BY_TYPE]，[cn.song.mybatisplus.visualizer.log.SqlListFragmentRenderer]（列表片段
 * 渲染）与本类（编辑器高亮）共用，保证编辑器内高亮与列表条目着色一致。此前预览编辑器
 * 高亮交给平台 SQL FileType 的数据库方言高亮，与列表的 One Dark 风格配色并存两套，
 * 同一条 SQL 两处颜色不同（四轮用户反馈）；换成自建高亮器后不再依赖 Database 插件，
 * 缺席环境（如测试沙盒）同样有高亮。
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
