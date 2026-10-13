package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-XmlGap W2/W3 高亮器键表守卫（纯逻辑，无平台 fixture）。
 *
 * 三组判别位：
 *  1. W2：`#{}` 与 `${}` 两个 TextAttributesKey **不相等**且 fallback 语义槽**不同**
 *     （计划 §六 W2 行「`#{}` 与 `${}` 显示不同色」的可测代理——无主题实例时，
 *     键与槽都不同是「默认主题下渲染肉眼不同」的必要条件）；
 *  2. W3 #6：[SqlMapperTT.IDENTIFIER] / [SqlMapperTT.OPERATOR] / 标点族**必须**有映射，
 *     且落点就是平台语义槽（回归守卫：原实现这两类返回空 = 表名列名运算符与普通文本同色）；
 *  3. 未知/null token → 空映射（不误挂颜色）。
 *
 * 平台槽位断言用 [com.intellij.openapi.editor.colors.TextAttributesKey.getFallbackAttributeKey]：
 * 键是自有的（`MPSQL_*_DEFAULT`，将来 W6 颜色页要能独立配色），语义靠 fallback 链跟随主题。
 */
class SqlMapperSyntaxHighlighterKeyTest {

    private val highlighter = SqlMapperSyntaxHighlighter()

    private fun fallbackOf(tokenType: com.intellij.psi.tree.IElementType) =
        highlighter.getTokenHighlights(tokenType).single().getFallbackAttributeKey()

    // ------------------------------------------------------------------
    // W2：两个参数定界符键
    // ------------------------------------------------------------------

    @Test
    fun testW2_两个参数定界符键不相等() {
        assertNotEquals(
            "PARAM_HASH 与 PARAM_DOLLAR 必须是两个不同的键",
            SqlMapperSyntaxHighlighter.PARAM_HASH,
            SqlMapperSyntaxHighlighter.PARAM_DOLLAR,
        )
        assertNotEquals(
            "两键的 externalName 必须不同",
            SqlMapperSyntaxHighlighter.PARAM_HASH.externalName,
            SqlMapperSyntaxHighlighter.PARAM_DOLLAR.externalName,
        )
    }

    @Test
    fun testW2_两个fallback语义槽不同_肉眼可分() {
        assertEquals(
            "`#{`/`}` 定界符应落 KEYWORD（与竞品同构）",
            DefaultLanguageHighlighterColors.KEYWORD,
            SqlMapperSyntaxHighlighter.PARAM_HASH_FALLBACK,
        )
        assertEquals(
            "`\${`/`}` 定界符应落 INSTANCE_FIELD（与 KEYWORD 明显不同色相）",
            DefaultLanguageHighlighterColors.INSTANCE_FIELD,
            SqlMapperSyntaxHighlighter.PARAM_DOLLAR_FALLBACK,
        )
        assertNotEquals(
            "两个 fallback 语义槽必须不同，否则两键渲染同色",
            SqlMapperSyntaxHighlighter.PARAM_HASH_FALLBACK,
            SqlMapperSyntaxHighlighter.PARAM_DOLLAR_FALLBACK,
        )
        // 实际建键时的 fallback 就是这两个槽（不是只声明不用）
        assertSame(SqlMapperSyntaxHighlighter.PARAM_HASH_FALLBACK, fallbackOf(SqlMapperTT.PARAM_HASH))
        assertSame(SqlMapperSyntaxHighlighter.PARAM_DOLLAR_FALLBACK, fallbackOf(SqlMapperTT.PARAM_DOLLAR))
    }

    @Test
    fun testW2_两类定界符各自的键映射非空且不同() {
        val hash = highlighter.getTokenHighlights(SqlMapperTT.PARAM_HASH)
        val dollar = highlighter.getTokenHighlights(SqlMapperTT.PARAM_DOLLAR)
        assertEquals(listOf(SqlMapperSyntaxHighlighter.PARAM_HASH), hash.toList())
        assertEquals(listOf(SqlMapperSyntaxHighlighter.PARAM_DOLLAR), dollar.toList())
        assertNotEquals(hash.toList(), dollar.toList())
    }

    // ------------------------------------------------------------------
    // W3 #6：覆盖面（回归守卫）
    // ------------------------------------------------------------------

    @Test
    fun testW3_标识符与运算符必须有映射_回归守卫() {
        assertTrue(
            "IDENTIFIER 不再映射 = 表名/列名与普通文本同色（W3 #6 要修的差距）",
            highlighter.getTokenHighlights(SqlMapperTT.IDENTIFIER).isNotEmpty(),
        )
        assertSame(
            DefaultLanguageHighlighterColors.IDENTIFIER,
            fallbackOf(SqlMapperTT.IDENTIFIER),
        )
        assertSame(
            DefaultLanguageHighlighterColors.OPERATION_SIGN,
            fallbackOf(SqlMapperTT.OPERATOR),
        )
    }

    @Test
    fun testW3_标点族各接平台语义槽位() {
        assertSame(DefaultLanguageHighlighterColors.PARENTHESES, fallbackOf(SqlMapperTT.PAREN))
        assertSame(DefaultLanguageHighlighterColors.BRACKETS, fallbackOf(SqlMapperTT.BRACKET))
        assertSame(DefaultLanguageHighlighterColors.BRACES, fallbackOf(SqlMapperTT.BRACE))
        assertSame(DefaultLanguageHighlighterColors.COMMA, fallbackOf(SqlMapperTT.COMMA))
        assertSame(DefaultLanguageHighlighterColors.DOT, fallbackOf(SqlMapperTT.DOT))
    }

    @Test
    fun testW3_新增token均有键_布尔与静态引用() {
        assertTrue(highlighter.getTokenHighlights(SqlMapperTT.BOOLEAN).isNotEmpty())
        assertSame(DefaultLanguageHighlighterColors.STATIC_FIELD, fallbackOf(SqlMapperTT.STATIC_REF))
    }

    @Test
    fun test基线键位未变_KEYWORD_STRING_NUMBER_COMMENT() {
        assertSame(DefaultLanguageHighlighterColors.KEYWORD, fallbackOf(SqlMapperTT.KEYWORD))
        assertSame(DefaultLanguageHighlighterColors.STRING, fallbackOf(SqlMapperTT.STRING))
        assertSame(DefaultLanguageHighlighterColors.NUMBER, fallbackOf(SqlMapperTT.NUMBER))
        assertSame(DefaultLanguageHighlighterColors.LINE_COMMENT, fallbackOf(SqlMapperTT.COMMENT))
    }

    // ------------------------------------------------------------------
    // 空映射
    // ------------------------------------------------------------------

    @Test
    fun test未知与null_token_空映射() {
        assertTrue(highlighter.getTokenHighlights(null).isEmpty())
        assertTrue(highlighter.getTokenHighlights(SqlMapperTT.WHITE_SPACE).isEmpty())
    }

    @Test
    fun test高亮器词法与注入词法同一实现() {
        assertTrue(highlighter.getHighlightingLexer() is SqlMapperHighlightingLexer)
    }
}
