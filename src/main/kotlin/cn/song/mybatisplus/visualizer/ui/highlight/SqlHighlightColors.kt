package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

/** SQL 的 canonical 配色键。显式用户配置优先，旧下划线键次之，最后回落平台语言配色。 */
object SqlHighlightColors {

    val KEYWORD = key("KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    val TABLE = key("TABLE", DefaultLanguageHighlighterColors.STATIC_FIELD)
    val IDENTIFIER = key("IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER)
    val STRING = key("STRING", DefaultLanguageHighlighterColors.STRING)
    val NUMBER = key("NUMBER", DefaultLanguageHighlighterColors.NUMBER)
    val COMMENT = key("COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
    val OPERATOR = key("OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    val PAREN = key("PAREN", DefaultLanguageHighlighterColors.PARENTHESES)

    private fun key(name: String, platform: TextAttributesKey): TextAttributesKey {
        val legacy = TextAttributesKey.createTextAttributesKey("MP_SQL_$name", platform)
        return TextAttributesKey.createTextAttributesKey("MP_SQL.$name", legacy)
    }
}
