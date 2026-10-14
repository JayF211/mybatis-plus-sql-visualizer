package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/** SQL 编辑器与列表共用八种 canonical token 配色；坏字符仍使用平台默认高亮。 */
class SqlSyntaxHighlighter : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): SqlLexer = SqlLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = when (tokenType) {
        TokenType.BAD_CHARACTER -> pack(BAD_CHARACTER)
        else -> tokenType?.let { SqlListSyntaxHighlighter.ATTR_BY_TYPE[it] }?.let { pack(it) } ?: emptyArray()
    }

    private companion object {
        val BAD_CHARACTER = TextAttributesKey.createTextAttributesKey("MP_SQL_BAD", HighlighterColors.BAD_CHARACTER)
    }
}
