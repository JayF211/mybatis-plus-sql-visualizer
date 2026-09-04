package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * 基于自研 [SqlLexer] 的 SQL 语法高亮器（F-CASE）。
 *
 * 替代平台原生 SQL 高亮器（Database 插件提供），用于预览编辑器：
 *  - 自研 SqlLexer 将 CASE/WHEN/THEN/ELSE/END 正确标记为 KEYWORD，
 *    字段名标记为 IDENTIFIER（不同类型 → SelectWordHandler 不会跨 token 合并选择）
 *  - 不依赖 Database 插件（Community 版也能正确高亮 + 词选择）
 *
 * 颜色方案对齐平台 SQL 关键字/标识符/字符串/数字/注释惯例（亮/暗主题自适应）。
 */
class SqlSyntaxHighlighter : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): SqlLexer = SqlLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = when (tokenType) {
        SqlTokenTypes.KEYWORD -> pack(KEYWORD)
        SqlTokenTypes.IDENTIFIER -> pack(IDENTIFIER)
        SqlTokenTypes.TABLE -> pack(TABLE)
        SqlTokenTypes.STRING -> pack(STRING)
        SqlTokenTypes.NUMBER -> pack(NUMBER)
        SqlTokenTypes.COMMENT -> pack(COMMENT)
        SqlTokenTypes.OPERATOR -> pack(OPERATOR)
        SqlTokenTypes.PAREN -> pack(PAREN)
        TokenType.BAD_CHARACTER -> pack(BAD_CHARACTER)
        else -> emptyArray()
    }

    private companion object {
        val KEYWORD = TextAttributesKey.createTextAttributesKey("MP_SQL_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        val IDENTIFIER = TextAttributesKey.createTextAttributesKey("MP_SQL_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER)
        val TABLE = TextAttributesKey.createTextAttributesKey("MP_SQL_TABLE", DefaultLanguageHighlighterColors.STATIC_FIELD)
        val STRING = TextAttributesKey.createTextAttributesKey("MP_SQL_STRING", DefaultLanguageHighlighterColors.STRING)
        val NUMBER = TextAttributesKey.createTextAttributesKey("MP_SQL_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
        val COMMENT = TextAttributesKey.createTextAttributesKey("MP_SQL_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
        val OPERATOR = TextAttributesKey.createTextAttributesKey("MP_SQL_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
        val PAREN = TextAttributesKey.createTextAttributesKey("MP_SQL_PAREN", DefaultLanguageHighlighterColors.PARENTHESES)
        val BAD_CHARACTER = TextAttributesKey.createTextAttributesKey("MP_SQL_BAD", HighlighterColors.BAD_CHARACTER)
    }
}
