// T-SyncK2JMaster482 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/SqlMapperSyntaxHighlighter.kt
package cn.song.mybatisplus.visualizer.ui.inject;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.Nullable;

/**
 * T-XmlSynHi D1/D4：注入语言高亮器（lexer-only）。D4 决策 3-A：颜色用
 * 平台既有 TextAttributesKey 借色（KEYWORD/STRING/NUMBER/INSTANCE_FIELD 借族），
 * 不挂 colorSettingsPage（决策 3-A 落定，颜色页随反馈另立项）。
 */
public class SqlMapperSyntaxHighlighter extends SyntaxHighlighterBase {

    @Override
    public Lexer getHighlightingLexer() {
        return new SqlMapperHighlightingLexer();
    }

    @Override
    public TextAttributesKey[] getTokenHighlights(@Nullable IElementType tokenType) {
        if (tokenType == SqlMapperTT.KEYWORD) {
            return KEYWORD_KEYS;
        } else if (tokenType == SqlMapperTT.STRING) {
            return STRING_KEYS;
        } else if (tokenType == SqlMapperTT.COMMENT) {
            return COMMENT_KEYS;
        } else if (tokenType == SqlMapperTT.PARAM) {
            return PARAM_KEYS;
        } else if (tokenType == SqlMapperTT.NUMBER) {
            return NUMBER_KEYS;
        } else {
            return EMPTY_KEYS;
        }
    }

    // ---- 借平台语义键（主题跟随，免自管颜色；「无效字符串」形态由 PARAM 底色承担）----

    public static final TextAttributesKey KEYWORD = TextAttributesKey.createTextAttributesKey(
            "MPSQL_KEYWORD_DEFAULT", com.intellij.openapi.editor.DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey STRING = TextAttributesKey.createTextAttributesKey(
            "MPSQL_STRING_DEFAULT", com.intellij.openapi.editor.DefaultLanguageHighlighterColors.STRING);
    public static final TextAttributesKey COMMENT = TextAttributesKey.createTextAttributesKey(
            "MPSQL_COMMENT_DEFAULT", com.intellij.openapi.editor.DefaultLanguageHighlighterColors.LINE_COMMENT);
    public static final TextAttributesKey PARAM = TextAttributesKey.createTextAttributesKey(
            "MPSQL_PARAM_DEFAULT", com.intellij.openapi.editor.DefaultLanguageHighlighterColors.TEMPLATE_LANGUAGE_COLOR);
    public static final TextAttributesKey NUMBER = TextAttributesKey.createTextAttributesKey(
            "MPSQL_NUMBER_DEFAULT", com.intellij.openapi.editor.DefaultLanguageHighlighterColors.NUMBER);

    private static final TextAttributesKey[] KEYWORD_KEYS = {KEYWORD};
    private static final TextAttributesKey[] STRING_KEYS = {STRING};
    private static final TextAttributesKey[] COMMENT_KEYS = {COMMENT};
    private static final TextAttributesKey[] PARAM_KEYS = {PARAM};
    private static final TextAttributesKey[] NUMBER_KEYS = {NUMBER};
    private static final TextAttributesKey[] EMPTY_KEYS = new TextAttributesKey[0];
}
