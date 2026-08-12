package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SqlLexer 词法器单测（M8-01）。
 *
 * 纯逻辑测试（不依赖 IDE fixture）：对 SQL 文本分词并校验 token 序列，
 * 覆盖关键字/标识符/表名启发式/字符串/数字/注释/运算符。
 */
class SqlLexerTest {

    /** 分词（含空白 token）。 */
    private fun tokenize(sql: String): List<Pair<IElementType, String>> {
        val lexer = SqlLexer()
        lexer.start(sql, 0, sql.length, 0)
        val tokens = mutableListOf<Pair<IElementType, String>>()
        while (true) {
            val type = lexer.tokenType ?: break
            tokens += type to lexer.tokenText
            lexer.advance()
        }
        return tokens
    }

    /** 分词并剔除空白（断言用）。 */
    private fun tokens(sql: String): List<Pair<IElementType, String>> =
        tokenize(sql).filter { it.first != TokenType.WHITE_SPACE }

    @Test
    fun testBasicCategories() {
        val sql = "SELECT id FROM tbl WHERE name = 'abc' AND num > 5"
        val t = tokens(sql)
        assertEquals(
            listOf(
                SqlTokenTypes.KEYWORD to "SELECT",
                SqlTokenTypes.IDENTIFIER to "id",
                SqlTokenTypes.KEYWORD to "FROM",
                SqlTokenTypes.TABLE to "tbl",
                SqlTokenTypes.KEYWORD to "WHERE",
                SqlTokenTypes.IDENTIFIER to "name",
                SqlTokenTypes.OPERATOR to "=",
                SqlTokenTypes.STRING to "'abc'",
                SqlTokenTypes.KEYWORD to "AND",
                SqlTokenTypes.IDENTIFIER to "num",
                SqlTokenTypes.OPERATOR to ">",
                SqlTokenTypes.NUMBER to "5",
            ),
            t,
        )
    }

    @Test
    fun testTableHeuristicWithAlias() {
        // FROM 后的表名与别名均标记为 TABLE；SELECT 中的别名列保持普通标识符
        val sql = "SELECT s.amount FROM sys_account s WHERE s.id = 1"
        val t = tokens(sql)
        assertEquals(SqlTokenTypes.KEYWORD, t[0].first)         // SELECT
        assertEquals(SqlTokenTypes.IDENTIFIER, t[1].first)      // s（SELECT 中）
        assertEquals(SqlTokenTypes.OPERATOR, t[2].first)        // .
        assertEquals(SqlTokenTypes.IDENTIFIER, t[3].first)      // amount
        assertEquals(SqlTokenTypes.KEYWORD, t[4].first)         // FROM
        assertEquals(SqlTokenTypes.TABLE, t[5].first)           // sys_account
        assertEquals(SqlTokenTypes.TABLE, t[6].first)           // s（别名）
    }

    @Test
    fun testJoinTableHeuristic() {
        val sql = "SELECT a.id FROM t1 a LEFT JOIN t2 b ON a.id = b.id"
        val t = tokens(sql)
        // FROM t1 a LEFT JOIN t2 b：t1、a、t2、b 均为表/别名
        val tableTokens = t.filter { it.first == SqlTokenTypes.TABLE }.map { it.second }
        assertEquals(listOf("t1", "a", "t2", "b"), tableTokens)
    }

    @Test
    fun testUpdateSet() {
        val sql = "UPDATE tbl SET name = 'x' WHERE id = 1"
        val t = tokens(sql)
        assertEquals(SqlTokenTypes.KEYWORD, t[0].first)   // UPDATE
        assertEquals(SqlTokenTypes.TABLE, t[1].first)     // tbl
        assertEquals(SqlTokenTypes.KEYWORD, t[2].first)   // SET
        assertEquals(SqlTokenTypes.IDENTIFIER, t[3].first) // name（SET 后为列）
    }

    @Test
    fun testStringEscapes() {
        // 单引号内 '' 转义，不应提前结束字符串
        val sql = "SELECT 'it''s a test'"
        val t = tokens(sql)
        assertEquals(SqlTokenTypes.STRING, t[1].first)
        assertEquals("'it''s a test'", t[1].second)
    }

    @Test
    fun testNumberDecimal() {
        val sql = "SELECT 1.5, -2, 0.5"
        val t = tokens(sql)
        assertEquals(SqlTokenTypes.NUMBER, t[1].first)
        assertEquals("1.5", t[1].second)
        // -2：负号是运算符，2 是数字
        assertEquals(SqlTokenTypes.OPERATOR, t[3].first)
        assertEquals(SqlTokenTypes.NUMBER, t[4].first)
        assertEquals("2", t[4].second)
    }

    @Test
    fun testComments() {
        val sql = "-- header comment\nSELECT 1 /* inline */ FROM t"
        val t = tokens(sql)
        assertEquals(SqlTokenTypes.COMMENT, t[0].first)
        assertTrue(t[0].second.startsWith("--"))
        assertTrue(t.any { it.first == SqlTokenTypes.COMMENT && it.second.contains("inline") })
    }

    @Test
    fun testWhitespaceKept() {
        val sql = "SELECT 1"
        val t = tokenize(sql)
        assertTrue(t.any { it.first == TokenType.WHITE_SPACE })
    }

    @Test
    fun testOperatorsAndParen() {
        val sql = "WHERE (a != b) AND c <= 2"
        val t = tokens(sql)
        assertEquals(SqlTokenTypes.PAREN, t[1].first)
        assertEquals(SqlTokenTypes.OPERATOR, t[3].first)
        assertEquals("!=", t[3].second)
        assertEquals(SqlTokenTypes.PAREN, t[5].first)
        assertEquals(SqlTokenTypes.OPERATOR, t[8].first)
        assertEquals("<=", t[8].second)
    }

    @Test
    fun testKeywordCaseInsensitive() {
        val sql = "select id from tbl where id = 1"
        val t = tokens(sql)
        assertEquals(SqlTokenTypes.KEYWORD, t[0].first)
        assertEquals("select", t[0].second)
        assertEquals(SqlTokenTypes.KEYWORD, t[2].first)
        assertEquals(SqlTokenTypes.TABLE, t[3].first)
    }
}
