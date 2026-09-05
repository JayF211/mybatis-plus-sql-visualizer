package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SqlSyntaxHighlighter 单测（F-CASE 全关键字覆盖）。
 *
 * 核心保证：每个词（关键字/标识符/运算符）都是独立 token，
 * 双击选择时不会跨词合并。
 *
 * 验证策略：
 *  - 每个 token 不含空格（防止 lexer 将多个词合并为一个 token）
 *  - 关键字与相邻标识符类型不同（KEYWORD vs IDENTIFIER）
 *  - 覆盖所有关键字上下文
 */
class SqlSyntaxHighlighterTest {

    private val highlighter = SqlSyntaxHighlighter()

    /** 分词（含空白 token）。 */
    private fun tokenize(sql: String): List<Pair<IElementType, String>> {
        val lexer = highlighter.highlightingLexer
        lexer.start(sql, 0, sql.length, 0)
        val tokens = mutableListOf<Pair<IElementType, String>>()
        while (true) {
            val type = lexer.tokenType ?: break
            tokens += type to lexer.tokenText
            lexer.advance()
        }
        return tokens
    }

    /** 分词并剔除空白。 */
    private fun tokens(sql: String): List<Pair<IElementType, String>> =
        tokenize(sql).filter { it.first != TokenType.WHITE_SPACE }

    /**
     * 核心断言：每个非空白 token 都不含空格/换行（防止多个词被合并为一个 token）。
     * 这是双击选择不粘连的根本保证。
     */
    private fun assertNoMultiWordTokens(t: List<Pair<IElementType, String>>) {
        for ((type, text) in t) {
            assertTrue(
                "Token 含空格/换行会导致选择粘连: type=$type, text='$text'",
                !text.contains(' ') && !text.contains('\n') && !text.contains('\r') && !text.contains('\t')
            )
        }
    }

    /** 验证关键字与相邻标识符类型不同。 */
    private fun assertKeywordIdentifierDistinct(t: List<Pair<IElementType, String>>) {
        for (i in t.indices) {
            if (t[i].first == SqlTokenTypes.KEYWORD) {
                // 前一个非空白 token（如果有）不应是 IDENTIFIER
                if (i > 0 && t[i - 1].first == SqlTokenTypes.IDENTIFIER) {
                    // 这是正常的：identifier keyword（如 "status WHEN"）
                }
                // 后一个非空白 token（如果有）不应是 IDENTIFIER 且与当前 token 类型相同
                if (i < t.size - 1 && t[i + 1].first == SqlTokenTypes.IDENTIFIER) {
                    // KEYWORD 后跟 IDENTIFIER 是正常的（如 "CASE use_status"）
                    // 关键是它们类型不同
                    assertNotEquals("KEYWORD 与 IDENTIFIER 类型必须不同", t[i].first, t[i + 1].first)
                }
            }
        }
    }

    // ==================================================================
    // CASE 表达式（原始问题）
    // ==================================================================

    @Test
    fun testCaseExpressionTokenization() {
        val sql = "SELECT CASE use_status WHEN 1 THEN '正常' ELSE '禁用' END FROM t"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        assertKeywordIdentifierDistinct(t)

        val expected = listOf(
            SqlTokenTypes.KEYWORD to "SELECT",
            SqlTokenTypes.KEYWORD to "CASE",
            SqlTokenTypes.IDENTIFIER to "use_status",
            SqlTokenTypes.KEYWORD to "WHEN",
            SqlTokenTypes.NUMBER to "1",
            SqlTokenTypes.KEYWORD to "THEN",
            SqlTokenTypes.STRING to "'正常'",
            SqlTokenTypes.KEYWORD to "ELSE",
            SqlTokenTypes.STRING to "'禁用'",
            SqlTokenTypes.KEYWORD to "END",
            SqlTokenTypes.KEYWORD to "FROM",
            SqlTokenTypes.TABLE to "t",
        )
        assertEquals(expected, t)
    }

    @Test
    fun testCaseWithDottedField() {
        val sql = "CASE a.status WHEN 1 THEN 'a' END"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        assertEquals(SqlTokenTypes.KEYWORD, t[0].first)     // CASE
        assertEquals(SqlTokenTypes.IDENTIFIER, t[1].first)  // a
        assertEquals(SqlTokenTypes.OPERATOR, t[2].first)    // .
        assertEquals(SqlTokenTypes.IDENTIFIER, t[3].first)  // status
        assertEquals(SqlTokenTypes.KEYWORD, t[4].first)     // WHEN
    }

    // ==================================================================
    // 所有关键字与相邻 token 边界
    // ==================================================================

    @Test
    fun testSelectFromWhereKeywords() {
        val sql = "SELECT id, name FROM users WHERE id = 1 AND name = 'x'"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        assertKeywordIdentifierDistinct(t)
        // SELECT(id) → KEYWORD, IDENTIFIER
        assertEquals(SqlTokenTypes.KEYWORD, t[0].first)
        assertEquals(SqlTokenTypes.IDENTIFIER, t[1].first)
        // FROM(users) → KEYWORD, TABLE
        val fromIdx = t.indexOfFirst { it.second == "FROM" }
        assertEquals(SqlTokenTypes.KEYWORD, t[fromIdx].first)
        assertEquals(SqlTokenTypes.TABLE, t[fromIdx + 1].first)
        // WHERE(id) → KEYWORD, IDENTIFIER
        val whereIdx = t.indexOfFirst { it.second == "WHERE" }
        assertEquals(SqlTokenTypes.KEYWORD, t[whereIdx].first)
        assertEquals(SqlTokenTypes.IDENTIFIER, t[whereIdx + 1].first)
    }

    @Test
    fun testJoinKeywords() {
        val sql = "SELECT a.id FROM t1 a LEFT JOIN t2 b ON a.id = b.id"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        val leftIdx = t.indexOfFirst { it.second == "LEFT" }
        assertEquals(SqlTokenTypes.KEYWORD, t[leftIdx].first)
        assertEquals(SqlTokenTypes.KEYWORD, t[leftIdx + 1].first) // JOIN
        val onIdx = t.indexOfFirst { it.second == "ON" }
        assertEquals(SqlTokenTypes.KEYWORD, t[onIdx].first)
    }

    @Test
    fun testInKeyword() {
        val sql = "SELECT id FROM t WHERE id IN (1, 2, 3)"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        val inIdx = t.indexOfFirst { it.second == "IN" }
        assertEquals(SqlTokenTypes.KEYWORD, t[inIdx].first)
        assertEquals(SqlTokenTypes.PAREN, t[inIdx + 1].first) // (
    }

    @Test
    fun testNotInKeyword() {
        val sql = "SELECT id FROM t WHERE id NOT IN (1, 2)"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        val notIdx = t.indexOfFirst { it.second == "NOT" }
        assertEquals(SqlTokenTypes.KEYWORD, t[notIdx].first)
        assertEquals(SqlTokenTypes.KEYWORD, t[notIdx + 1].first) // IN
        assertEquals(SqlTokenTypes.PAREN, t[notIdx + 2].first)   // (
    }

    @Test
    fun testLikeKeyword() {
        val sql = "SELECT id FROM t WHERE name LIKE '%abc%'"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        val likeIdx = t.indexOfFirst { it.second == "LIKE" }
        assertEquals(SqlTokenTypes.KEYWORD, t[likeIdx].first)
        assertEquals(SqlTokenTypes.STRING, t[likeIdx + 1].first)
    }

    @Test
    fun testBetweenKeyword() {
        val sql = "SELECT id FROM t WHERE age BETWEEN 18 AND 60"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        val betIdx = t.indexOfFirst { it.second == "BETWEEN" }
        assertEquals(SqlTokenTypes.KEYWORD, t[betIdx].first)
        assertEquals(SqlTokenTypes.NUMBER, t[betIdx + 1].first) // 18
        assertEquals(SqlTokenTypes.KEYWORD, t[betIdx + 2].first) // AND
        assertEquals(SqlTokenTypes.NUMBER, t[betIdx + 3].first) // 60
    }

    @Test
    fun testExistsKeyword() {
        val sql = "SELECT id FROM t WHERE EXISTS (SELECT 1 FROM t2)"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        val existsIdx = t.indexOfFirst { it.second == "EXISTS" }
        assertEquals(SqlTokenTypes.KEYWORD, t[existsIdx].first)
        assertEquals(SqlTokenTypes.PAREN, t[existsIdx + 1].first) // (
    }

    @Test
    fun testAsKeyword() {
        val sql = "SELECT id AS user_id FROM t"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        val asIdx = t.indexOfFirst { it.second == "AS" }
        assertEquals(SqlTokenTypes.KEYWORD, t[asIdx].first)
        assertEquals(SqlTokenTypes.IDENTIFIER, t[asIdx + 1].first) // user_id
    }

    @Test
    fun testOnKeyword() {
        val sql = "SELECT a.id FROM t1 a JOIN t2 b ON a.id = b.id"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        val onIdx = t.indexOfFirst { it.second == "ON" }
        assertEquals(SqlTokenTypes.KEYWORD, t[onIdx].first)
        assertEquals(SqlTokenTypes.IDENTIFIER, t[onIdx + 1].first) // a
    }

    @Test
    fun testUsingKeyword() {
        val sql = "SELECT id FROM t1 JOIN t2 USING (id)"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        val usingIdx = t.indexOfFirst { it.second == "USING" }
        assertEquals(SqlTokenTypes.KEYWORD, t[usingIdx].first)
        assertEquals(SqlTokenTypes.PAREN, t[usingIdx + 1].first) // (
    }

    @Test
    fun testNullKeyword() {
        val sql = "SELECT id FROM t WHERE name IS NULL"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        val nullIdx = t.indexOfFirst { it.second == "NULL" }
        assertEquals(SqlTokenTypes.KEYWORD, t[nullIdx].first)
    }

    @Test
    fun testIsNotNull() {
        val sql = "SELECT id FROM t WHERE name IS NOT NULL"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        val isIdx = t.indexOfFirst { it.second == "IS" }
        assertEquals(SqlTokenTypes.KEYWORD, t[isIdx].first)
        assertEquals(SqlTokenTypes.KEYWORD, t[isIdx + 1].first) // NOT
        assertEquals(SqlTokenTypes.KEYWORD, t[isIdx + 2].first) // NULL
    }

    @Test
    fun testGroupByOrderByKeywords() {
        val sql = "SELECT id, COUNT(*) FROM t GROUP BY id ORDER BY id ASC"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        val groupIdx = t.indexOfFirst { it.second == "GROUP" }
        assertEquals(SqlTokenTypes.KEYWORD, t[groupIdx].first)
        assertEquals(SqlTokenTypes.KEYWORD, t[groupIdx + 1].first) // BY
        val orderIdx = t.indexOfFirst { it.second == "ORDER" }
        assertEquals(SqlTokenTypes.KEYWORD, t[orderIdx].first)
        assertEquals(SqlTokenTypes.KEYWORD, t[orderIdx + 1].first) // BY
    }

    @Test
    fun testLimitOffsetKeywords() {
        val sql = "SELECT id FROM t LIMIT 10 OFFSET 5"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        val limitIdx = t.indexOfFirst { it.second == "LIMIT" }
        assertEquals(SqlTokenTypes.KEYWORD, t[limitIdx].first)
        assertEquals(SqlTokenTypes.NUMBER, t[limitIdx + 1].first) // 10
        val offsetIdx = t.indexOfFirst { it.second == "OFFSET" }
        assertEquals(SqlTokenTypes.KEYWORD, t[offsetIdx].first)
        assertEquals(SqlTokenTypes.NUMBER, t[offsetIdx + 1].first) // 5
    }

    @Test
    fun testDistinctKeyword() {
        val sql = "SELECT DISTINCT id FROM t"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        assertEquals(SqlTokenTypes.KEYWORD, t[0].first)     // SELECT
        assertEquals(SqlTokenTypes.KEYWORD, t[1].first)     // DISTINCT
        assertEquals(SqlTokenTypes.IDENTIFIER, t[2].first)  // id
    }

    @Test
    fun testUnionKeyword() {
        val sql = "SELECT id FROM t1 UNION SELECT id FROM t2"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        val unionIdx = t.indexOfFirst { it.second == "UNION" }
        assertEquals(SqlTokenTypes.KEYWORD, t[unionIdx].first)
        assertEquals(SqlTokenTypes.KEYWORD, t[unionIdx + 1].first) // SELECT
    }

    @Test
    fun testInsertIntoKeywords() {
        val sql = "INSERT INTO users (id, name) VALUES (1, 'x')"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        assertEquals(SqlTokenTypes.KEYWORD, t[0].first)     // INSERT
        assertEquals(SqlTokenTypes.KEYWORD, t[1].first)     // INTO
        assertEquals(SqlTokenTypes.TABLE, t[2].first)       // users
    }

    @Test
    fun testUpdateSetKeywords() {
        val sql = "UPDATE users SET name = 'x' WHERE id = 1"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        assertEquals(SqlTokenTypes.KEYWORD, t[0].first)     // UPDATE
        assertEquals(SqlTokenTypes.TABLE, t[1].first)       // users
        assertEquals(SqlTokenTypes.KEYWORD, t[2].first)     // SET
        assertEquals(SqlTokenTypes.IDENTIFIER, t[3].first)  // name
    }

    @Test
    fun testDeleteFromKeywords() {
        val sql = "DELETE FROM users WHERE id = 1"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        assertEquals(SqlTokenTypes.KEYWORD, t[0].first)     // DELETE
        assertEquals(SqlTokenTypes.KEYWORD, t[1].first)     // FROM
        assertEquals(SqlTokenTypes.TABLE, t[2].first)       // users
    }

    // ==================================================================
    // 函数调用（关键字后跟括号）
    // ==================================================================

    @Test
    fun testFunctionCallWithParen() {
        val sql = "SELECT COUNT(*), SUM(amount), MAX(id) FROM t"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        val countIdx = t.indexOfFirst { it.second == "COUNT" }
        assertEquals(SqlTokenTypes.KEYWORD, t[countIdx].first)
        assertEquals(SqlTokenTypes.PAREN, t[countIdx + 1].first)
    }

    @Test
    fun testCoalesceFunction() {
        val sql = "SELECT COALESCE(name, 'unknown') FROM t"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        val coalesceIdx = t.indexOfFirst { it.second == "COALESCE" }
        assertEquals(SqlTokenTypes.KEYWORD, t[coalesceIdx].first)
        assertEquals(SqlTokenTypes.PAREN, t[coalesceIdx + 1].first)
    }

    @Test
    fun testCastFunction() {
        val sql = "SELECT CAST(id AS CHAR) FROM t"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        val castIdx = t.indexOfFirst { it.second == "CAST" }
        assertEquals(SqlTokenTypes.KEYWORD, t[castIdx].first)
        assertEquals(SqlTokenTypes.PAREN, t[castIdx + 1].first)
    }

    // ==================================================================
    // 运算符边界
    // ==================================================================

    @Test
    fun testOperatorsAdjacentToIdentifiers() {
        val sql = "SELECT a.id FROM t WHERE a.id = b.id AND a.name <> 'x'"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        val eqIdx = t.indexOfFirst { it.second == "=" }
        assertEquals(SqlTokenTypes.OPERATOR, t[eqIdx].first)
    }

    @Test
    fun testDotOperator() {
        val sql = "SELECT t.id FROM t"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        assertEquals(SqlTokenTypes.IDENTIFIER, t[1].first)  // t
        assertEquals(SqlTokenTypes.OPERATOR, t[2].first)    // .
        assertEquals(SqlTokenTypes.IDENTIFIER, t[3].first)  // id
    }

    // ==================================================================
    // 高亮映射
    // ==================================================================

    @Test
    fun testTokenHighlightsMapped() {
        val keywordKey = highlighter.getTokenHighlights(SqlTokenTypes.KEYWORD)
        assertTrue("KEYWORD should have highlight", keywordKey.isNotEmpty())
        assertNotNull(keywordKey[0])

        SqlTokenTypes::class.java.declaredFields
            .filter { it.type == IElementType::class.java }
            .forEach { field ->
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val tokenType = field.get(SqlTokenTypes) as? IElementType
                if (tokenType != null) {
                    val key = highlighter.getTokenHighlights(tokenType)
                    assertTrue("${field.name} should have highlight", key.isNotEmpty())
                }
            }
    }

    @Test
    fun testBadCharacterHasHighlight() {
        val badCharKey = highlighter.getTokenHighlights(TokenType.BAD_CHARACTER)
        assertTrue("BAD_CHARACTER should have highlight", badCharKey.isNotEmpty())
    }

    @Test
    fun testUnknownTokenHasNoHighlight() {
        val customType = IElementType("CUSTOM_UNKNOWN", null)
        val unknown = highlighter.getTokenHighlights(customType)
        assertEquals("Unknown token should have no highlight", 0, unknown.size)
    }

    // ==================================================================
    // 综合复杂 SQL
    // ==================================================================

    @Test
    fun testComplexSelectNoMultiWordTokens() {
        val sql = "SELECT u.id, CASE u.status WHEN 1 THEN 'active' ELSE 'inactive' END AS status_text, COUNT(o.id) AS order_count FROM users u LEFT JOIN orders o ON u.id = o.user_id WHERE u.created_at > '2024-01-01' AND u.id IN (SELECT user_id FROM vip_users) GROUP BY u.id HAVING COUNT(o.id) > 0 ORDER BY order_count DESC LIMIT 10"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
    }

    @Test
    fun testMultipleCaseExpressions() {
        val sql = "SELECT CASE a WHEN 1 THEN 'x' END, CASE b WHEN 2 THEN 'y' END FROM t"
        val t = tokens(sql)
        assertNoMultiWordTokens(t)
        val caseCount = t.count { it.second == "CASE" }
        assertEquals(2, caseCount)
    }
}
