package cn.song.mybatisplus.visualizer.reverse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SqlTokenizer 纯逻辑单测（T-TestGuard-07 零覆盖补测：reverse/SqlTokenizer 此前无任何测试）。
 *
 * 覆盖 SQL→Java 反向转换词法层的核心词形与错误面：
 * 标识符（含反引号/点分）、字符串字面量（`''` 双写转义）、数字、符号、注释跳过、
 * 未闭合字面量与非法字符抛 [SqlReverseException]、offset 正确性。
 */
class SqlTokenizerTest {

    private val tokenizer = SqlTokenizer()

    private fun tokenize(sql: String) = tokenizer.tokenize(sql)

    private fun texts(sql: String) = tokenize(sql).map { it.text() }

    @Test
    fun `标识符与关键字`() {
        val tokens = tokenize("SELECT id FROM t")
        assertEquals(
            listOf("SELECT", "id", "FROM", "t"),
            tokens.map { it.text() },
        )
        assertTrue(tokens.all { it.type() == SqlTokenizer.TokenType.WORD })
    }

    @Test
    fun `点分标识符合并`() {
        val tokens = tokenize("u.user_name")
        assertEquals(1, tokens.size)
        assertEquals("u.user_name", tokens[0].text())
    }

    @Test
    fun `反引号标识符原样保留`() {
        val tokens = tokenize("SELECT `order` FROM `t`")
        assertEquals(listOf("SELECT", "`order`", "FROM", "`t`"), tokens.map { it.text() })
    }

    @Test
    fun `字符串字面量_双写引号转义`() {
        val tokens = tokenize("'it''s ok'")
        assertEquals(1, tokens.size)
        assertEquals(SqlTokenizer.TokenType.STRING, tokens[0].type())
        assertEquals("'it''s ok'", tokens[0].text())
    }

    @Test
    fun `数字字面量_整数与小数`() {
        assertEquals(listOf("123"), texts("123"))
        assertEquals(listOf("1.5"), texts("1.5"))
        // 前导点数字（.5 形态）
        assertEquals(listOf(".5"), texts(".5"))
    }

    @Test
    fun `符号逐个产出_含问号占位符`() {
        val tokens = tokenize("a=?")
        assertEquals(listOf("a", "=", "?"), tokens.map { it.text() })
        assertEquals(SqlTokenizer.TokenType.SYMBOL, tokens[2].type())
    }

    @Test
    fun `行注释与块注释跳过不产出`() {
        assertEquals(listOf("a", "b"), texts("a -- comment\nb"))
        assertEquals(listOf("a", "b"), texts("a /* block */ b"))
    }

    @Test
    fun `offset指向原文位置`() {
        val tokens = tokenize("SELECT 'x', y")
        // SELECT@0, 'x'@7, ','@10, y@12
        assertEquals(7, tokens[1].offset())
        assertEquals(10, tokens[2].offset())
        assertEquals(12, tokens[3].offset())
    }

    @Test
    fun `未闭合字符串抛词法异常`() {
        try {
            tokenize("'abc")
            org.junit.Assert.fail("未闭合字符串应抛 SqlReverseException")
        } catch (e: SqlReverseException) {
            assertTrue("异常信息应指明未闭合", (e.message ?: "").contains("未闭合"))
        }
    }

    @Test
    fun `未闭合反引号抛词法异常`() {
        try {
            tokenize("`abc")
            org.junit.Assert.fail("未闭合反引号应抛 SqlReverseException")
        } catch (e: SqlReverseException) {
            assertTrue("异常信息应指明未闭合", (e.message ?: "").contains("未闭合"))
        }
    }

    @Test
    fun `非法字符抛词法异常`() {
        try {
            tokenize("a # b")
            org.junit.Assert.fail("非法字符应抛 SqlReverseException")
        } catch (e: SqlReverseException) {
            assertTrue("异常信息应含非法字符", (e.message ?: "").contains("#"))
        }
    }

    @Test
    fun `空输入产出空列表`() {
        assertEquals(0, tokenize("").size)
        assertEquals(0, tokenize("   \n\t").size)
    }
}
