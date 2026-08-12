package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.psi.tree.IElementType

/**
 * SQL 预览语法高亮器（M8-01）：把 [SqlLexer] 的 token 分发到 [SqlHighlightColors] 配色。
 *
 * 经 [com.intellij.openapi.editor.highlighter.LexerEditorHighlighter] 挂到
 * 只读编辑器视图上，随 IDE 亮/暗主题切换配色。
 */
class SqlSyntaxHighlighter : SyntaxHighlighter {

    override fun getHighlightingLexer(): Lexer = SqlLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = when (tokenType) {
        SqlTokenTypes.KEYWORD -> arrayOf(SqlHighlightColors.KEYWORD)
        SqlTokenTypes.TABLE -> arrayOf(SqlHighlightColors.TABLE)
        SqlTokenTypes.IDENTIFIER -> arrayOf(SqlHighlightColors.IDENTIFIER)
        SqlTokenTypes.STRING -> arrayOf(SqlHighlightColors.STRING)
        SqlTokenTypes.NUMBER -> arrayOf(SqlHighlightColors.NUMBER)
        SqlTokenTypes.COMMENT -> arrayOf(SqlHighlightColors.COMMENT)
        SqlTokenTypes.OPERATOR -> arrayOf(SqlHighlightColors.OPERATOR)
        SqlTokenTypes.PAREN -> arrayOf(SqlHighlightColors.PAREN)
        else -> emptyArray()
    }
}
