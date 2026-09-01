package cn.song.mybatisplus.visualizer.ui.highlight;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;

/**
 * P-05.1：轻量 JSON 语法高亮器（不依赖 JSON 插件，Community 可用）。
 *
 * 高亮：键（key）/ 字符串值 / 数值 / 布尔 / null / 标点。
 * 基于 {@link Lexer} 逐字符扫描，映射到平台标准命名色（亮/暗主题自适应）。
 */
public class JsonSyntaxHighlighter extends SyntaxHighlighterBase {

    @Override
    public com.intellij.lexer.Lexer getHighlightingLexer() {
        return new JsonLexer();
    }

    @Override
    public TextAttributesKey[] getTokenHighlights(IElementType tokenType) {
        if (tokenType == JsonTokenTypes.STRING) {
            return pack(STRING);
        }
        if (tokenType == JsonTokenTypes.NUMBER) {
            return pack(NUMBER);
        }
        if (tokenType == JsonTokenTypes.BOOLEAN) {
            return pack(KEYWORD);
        }
        if (tokenType == JsonTokenTypes.NULL) {
            return pack(KEYWORD);
        }
        if (tokenType == JsonTokenTypes.LBRACE || tokenType == JsonTokenTypes.RBRACE
                || tokenType == JsonTokenTypes.LBRACKET || tokenType == JsonTokenTypes.RBRACKET
                || tokenType == JsonTokenTypes.COMMA || tokenType == JsonTokenTypes.COLON) {
            return pack(DEFAULT);
        }
        if (tokenType == JsonTokenTypes.BAD_CHARACTER) {
            return pack(BAD_CHARACTER);
        }
        return new TextAttributesKey[0];
    }

    private static final TextAttributesKey STRING = TextAttributesKey.createTextAttributesKey("JSON_STRING", DefaultLanguageHighlighterColors.STRING);
    private static final TextAttributesKey NUMBER = TextAttributesKey.createTextAttributesKey("JSON_NUMBER", DefaultLanguageHighlighterColors.NUMBER);
    private static final TextAttributesKey KEYWORD = TextAttributesKey.createTextAttributesKey("JSON_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
    private static final TextAttributesKey DEFAULT = TextAttributesKey.createTextAttributesKey("JSON_DEFAULT", DefaultLanguageHighlighterColors.BRACES);
    private static final TextAttributesKey BAD_CHARACTER = TextAttributesKey.createTextAttributesKey("JSON_BAD", HighlighterColors.BAD_CHARACTER);

    public static TextAttributesKey[] pack(TextAttributesKey key) {
        return SyntaxHighlighterBase.pack(key);
    }
}
