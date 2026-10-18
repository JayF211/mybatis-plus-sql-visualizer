package cn.song.mybatisplus.visualizer.ui.highlight;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;

/** 编辑器与列表共享主题键；保留旧下划线键作为回退以兼容用户配色。 */
public final class SqlHighlightColors {
    public static final SqlHighlightColors INSTANCE = new SqlHighlightColors();

    public static final TextAttributesKey KEYWORD = key("KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey TABLE = key("TABLE", DefaultLanguageHighlighterColors.STATIC_FIELD);
    public static final TextAttributesKey IDENTIFIER = key("IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER);
    public static final TextAttributesKey STRING = key("STRING", DefaultLanguageHighlighterColors.STRING);
    public static final TextAttributesKey NUMBER = key("NUMBER", DefaultLanguageHighlighterColors.NUMBER);
    public static final TextAttributesKey COMMENT = key("COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
    public static final TextAttributesKey OPERATOR = key("OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN);
    public static final TextAttributesKey PAREN = key("PAREN", DefaultLanguageHighlighterColors.PARENTHESES);

    private static TextAttributesKey key(String name, TextAttributesKey platform) {
        TextAttributesKey legacy = TextAttributesKey.createTextAttributesKey("MP_SQL_" + name, platform);
        return TextAttributesKey.createTextAttributesKey("MP_SQL." + name, legacy);
    }

    private SqlHighlightColors() {
    }
}
