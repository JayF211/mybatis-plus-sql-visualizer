package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.psi.tree.IElementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-XmlSynHi D1 词法单测（纯逻辑）：关键字 / 参数整体 token（决策 3-A）/ 字符串 / 注释 /
 * 转义与未闭合边缘 / 守卫纯逻辑面（属性白名单与长度熔断；inMapperFile 需 PSI 归 fixture 测试）。
 */
class SqlMapperHighlightingLexerTest {

    private fun tokens(sql: String): List<Pair<IElementType, String>> {
        val lexer = SqlMapperHighlightingLexer()
        lexer.start(sql)
        val out = mutableListOf<Pair<IElementType, String>>()
        while (lexer.tokenType != null) {
            out += lexer.tokenType!! to lexer.bufferSequence.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
            lexer.advance()
        }
        return out
    }

    private fun texts(sql: String, type: IElementType): List<String> =
        tokens(sql).filter { it.first == type }.map { it.second }

    @Test
    fun test关键字_大小写不敏感着色() {
        val ks = tokens("select * from t where a = 1")
            .filter { it.first == SqlMapperTT.KEYWORD }.map { it.second.uppercase() }
        assertEquals(listOf("SELECT", "FROM", "WHERE"), ks)
    }

    @Test
    fun test参数占位_整体token_决策3A() {
        val ps = texts("SELECT * FROM t WHERE id = #{id} AND n = ${'$'}{n}", SqlMapperTT.PARAM)
        assertEquals(listOf("#{id}", "\${n}"), ps)
    }

    @Test
    fun test字符串_单引号_内部分号与关键字不分裂() {
        assertEquals(listOf("'a SELECT b'"), texts("SELECT 'a SELECT b' FROM t", SqlMapperTT.STRING))
    }

    @Test
    fun test字符串_未闭合_扫到尾() {
        assertEquals(listOf("'abc"), texts("SELECT 'abc", SqlMapperTT.STRING))
    }

    @Test
    fun test注释_行注释与块注释() {
        assertEquals(listOf("-- c"), texts("-- c\nSELECT 1", SqlMapperTT.COMMENT))
        assertEquals(1, texts("/* block */", SqlMapperTT.COMMENT).size)
    }

    @Test
    fun test转义_反斜杠井号_不产参数token() {
        assertTrue("转义形态不应有 PARAM token", texts("\\#{escaped}", SqlMapperTT.PARAM).isEmpty())
    }

    @Test
    fun test守卫_属性白名单() {
        assertTrue(XmlSynHiGate.allowAttributeValue("test", "page != null"))
        assertTrue(XmlSynHiGate.allowAttributeValue("collection", "list"))
        assertFalse(XmlSynHiGate.allowAttributeValue("unknown", "x"))
    }

    @Test
    fun test守卫_长度熔断() {
        assertFalse(XmlSynHiGate.allowAttributeValue("test", "x".repeat(5000)))
    }
}
