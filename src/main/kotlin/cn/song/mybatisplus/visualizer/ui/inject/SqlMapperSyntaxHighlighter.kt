package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

/**
 * T-XmlSynHi D1/D4：注入语言高亮器（lexer-only）。D4 决策 3-A：颜色用
 * 平台既有 TextAttributesKey 借色（KEYWORD/STRING/NUMBER/INSTANCE_FIELD 借族），
 * 不挂 colorSettingsPage（决策 3-A 落定，颜色页随反馈另立项）。
 */
class SqlMapperSyntaxHighlighter : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): Lexer = SqlMapperHighlightingLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = when (tokenType) {
        SqlMapperTT.KEYWORD -> KEYWORD_KEYS
        SqlMapperTT.STRING -> STRING_KEYS
        SqlMapperTT.COMMENT -> COMMENT_KEYS
        SqlMapperTT.PARAM -> PARAM_KEYS
        SqlMapperTT.NUMBER -> NUMBER_KEYS
        else -> EMPTY_KEYS
    }

    companion object {
        /** 借平台语义键（主题跟随，免自管颜色；「无效字符串」形态由 PARAM 底色承担）。 */
        val KEYWORD: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("MPSQL_KEYWORD_DEFAULT", com.intellij.openapi.editor.DefaultLanguageHighlighterColors.KEYWORD)
        val STRING: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("MPSQL_STRING_DEFAULT", com.intellij.openapi.editor.DefaultLanguageHighlighterColors.STRING)
        val COMMENT: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("MPSQL_COMMENT_DEFAULT", com.intellij.openapi.editor.DefaultLanguageHighlighterColors.LINE_COMMENT)
        val PARAM: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("MPSQL_PARAM_DEFAULT", com.intellij.openapi.editor.DefaultLanguageHighlighterColors.TEMPLATE_LANGUAGE_COLOR)
        val NUMBER: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("MPSQL_NUMBER_DEFAULT", com.intellij.openapi.editor.DefaultLanguageHighlighterColors.NUMBER)

        private val KEYWORD_KEYS = arrayOf(KEYWORD)
        private val STRING_KEYS = arrayOf(STRING)
        private val COMMENT_KEYS = arrayOf(COMMENT)
        private val PARAM_KEYS = arrayOf(PARAM)
        private val NUMBER_KEYS = arrayOf(NUMBER)
        private val EMPTY_KEYS = emptyArray<TextAttributesKey>()
    }
}
