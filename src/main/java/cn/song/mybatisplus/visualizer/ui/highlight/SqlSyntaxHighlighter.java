package cn.song.mybatisplus.visualizer.ui.highlight;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 基于自研 {@link SqlLexer} 的 SQL 语法高亮器（F-CASE）。
 *
 * <p>替代平台原生 SQL 高亮器（Database 插件提供），用于预览编辑器：
 * <ul>
 *  <li>自研 SqlLexer 将 CASE/WHEN/THEN/ELSE/END 正确标记为 KEYWORD，
 *      字段名标记为 IDENTIFIER（不同类型 → SelectWordHandler 不会跨 token 合并选择）</li>
 *  <li>不依赖 Database 插件（Community 版也能正确高亮 + 词选择）</li>
 * </ul>
 * 颜色方案对齐平台 SQL 关键字/标识符/字符串/数字/注释惯例（亮/暗主题自适应）。
 */
public class SqlSyntaxHighlighter extends SyntaxHighlighterBase {

    private static final TextAttributesKey KEYWORD =
            TextAttributesKey.createTextAttributesKey("MP_SQL_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
    private static final TextAttributesKey IDENTIFIER =
            TextAttributesKey.createTextAttributesKey("MP_SQL_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER);
    private static final TextAttributesKey TABLE =
            TextAttributesKey.createTextAttributesKey("MP_SQL_TABLE", DefaultLanguageHighlighterColors.STATIC_FIELD);
    private static final TextAttributesKey STRING =
            TextAttributesKey.createTextAttributesKey("MP_SQL_STRING", DefaultLanguageHighlighterColors.STRING);
    private static final TextAttributesKey NUMBER =
            TextAttributesKey.createTextAttributesKey("MP_SQL_NUMBER", DefaultLanguageHighlighterColors.NUMBER);
    private static final TextAttributesKey COMMENT =
            TextAttributesKey.createTextAttributesKey("MP_SQL_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
    private static final TextAttributesKey OPERATOR =
            TextAttributesKey.createTextAttributesKey("MP_SQL_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN);
    private static final TextAttributesKey PAREN =
            TextAttributesKey.createTextAttributesKey("MP_SQL_PAREN", DefaultLanguageHighlighterColors.PARENTHESES);
    private static final TextAttributesKey BAD_CHARACTER =
            TextAttributesKey.createTextAttributesKey("MP_SQL_BAD", HighlighterColors.BAD_CHARACTER);

    @NotNull
    @Override
    public com.intellij.lexer.Lexer getHighlightingLexer() {
        return new SqlLexer();
    }

    @NotNull
    @Override
    public TextAttributesKey[] getTokenHighlights(@Nullable IElementType tokenType) {
        if (tokenType == SqlTokenTypes.KEYWORD) {
            return pack(KEYWORD);
        }
        if (tokenType == SqlTokenTypes.IDENTIFIER) {
            return pack(IDENTIFIER);
        }
        if (tokenType == SqlTokenTypes.TABLE) {
            return pack(TABLE);
        }
        if (tokenType == SqlTokenTypes.STRING) {
            return pack(STRING);
        }
        if (tokenType == SqlTokenTypes.NUMBER) {
            return pack(NUMBER);
        }
        if (tokenType == SqlTokenTypes.COMMENT) {
            return pack(COMMENT);
        }
        if (tokenType == SqlTokenTypes.OPERATOR) {
            return pack(OPERATOR);
        }
        if (tokenType == SqlTokenTypes.PAREN) {
            return pack(PAREN);
        }
        if (tokenType == TokenType.BAD_CHARACTER) {
            return pack(BAD_CHARACTER);
        }
        return TextAttributesKey.EMPTY_ARRAY;
    }
}
