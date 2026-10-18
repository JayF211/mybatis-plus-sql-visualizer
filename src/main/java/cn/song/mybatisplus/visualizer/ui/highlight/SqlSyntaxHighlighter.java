package cn.song.mybatisplus.visualizer.ui.highlight;

import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 自研词法器与列表共用 token 配色映射，不依赖 Database 插件。 */
public class SqlSyntaxHighlighter extends SyntaxHighlighterBase {
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
        if (tokenType == TokenType.BAD_CHARACTER) return pack(BAD_CHARACTER);
        TextAttributesKey key = tokenType != null ? SqlListSyntaxHighlighter.ATTR_BY_TYPE.get(tokenType) : null;
        return key != null ? pack(key) : TextAttributesKey.EMPTY_ARRAY;
    }
}
