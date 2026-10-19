package cn.song.mybatisplus.visualizer.share

import org.junit.Assert.*
import org.junit.Test

class ShareContentProjectionTest {
    private fun hasKeyword(text: String, word: String): Boolean =
        Regex("(?<![A-Za-z0-9_])$word(?![A-Za-z0-9_])").containsMatchIn(text)

    @Test
    fun `默认省略全部字面量注释参数名和标识符`() {
        val projected = ShareContentProjection.sql(
            "SELECT private_customer, \"private_column\" FROM private_table WHERE password = 'SENTINEL_PASS' " +
                "AND amount = 90817263 AND secret_name = :private_param /* SENTINEL_COMMENT */ -- SENTINEL_TAIL",
        )
        assertFalse(projected.omitted)
        val text = projected.sql!!
        listOf("private_customer", "private_column", "private_table", "password", "SENTINEL_PASS", "90817263",
            "secret_name", "private_param", "SENTINEL_COMMENT", "SENTINEL_TAIL").forEach {
            assertFalse("不得保留 $it：$text", text.contains(it))
        }
        assertTrue(text.contains("SELECT"))
        assertTrue(text.contains("identifier_"))
        assertEquals(3, projected.maskedValues)
    }

    @Test
    fun `引号内部转义不会泄漏后半段`() {
        val result = ShareContentProjection.sql("SELECT 'a''SENTINEL_SECRET' FROM t WHERE n = -12.3e+9")
        assertFalse(result.omitted)
        assertFalse(result.sql!!.contains("SENTINEL_SECRET"))
        assertFalse(result.sql.contains("12.3"))
        assertEquals(2, result.maskedValues)
    }

    @Test
    fun `无法确认方言引号语义时省略整段而非回落原文`() {
        val unsupported = listOf(
            "SELECT q'[SENTINEL_SECRET]' FROM dual",
            "SELECT \$tag\$SENTINEL_SECRET\$tag\$",
            "SELECT E'SENTINEL_SECRET'",
            "SELECT N'SENTINEL_SECRET'",
            "SELECT 'a\\' SENTINEL_SECRET'",
            "SELECT 'SENTINEL_SECRET",
            "SELECT /* SENTINEL_SECRET",
            "SELECT /* outer /* inner */ SENTINEL_SECRET */ 1",
            "SELECT # SENTINEL_SECRET",
            "SELECT @SENTINEL_SECRET",
        )
        unsupported.forEach {
            val result = ShareContentProjection.sql(it)
            assertTrue("不支持的形态必须省略：$it", result.omitted)
            assertNull(result.sql)
        }
    }

    @Test
    fun `标识符包含须显式选择且不放行引号中秘密`() {
        val result = ShareContentProjection.sql("SELECT customer_name, \"SENTINEL_QUOTED\" FROM customers WHERE n='VALUE_SECRET'", true)
        assertTrue(result.sql!!.contains("customer_name"))
        assertTrue(result.sql.contains("customers"))
        assertFalse(result.sql.contains("SENTINEL_QUOTED"))
        assertFalse(result.sql.contains("VALUE_SECRET"))
        assertNull(ShareContentProjection.identifier("customer_name"))
        assertEquals("customer_name", ShareContentProjection.identifier("customer_name", true))
        assertNull(ShareContentProjection.identifier("jdbc:mysql://private:secret", true))
        assertNull(ShareContentProjection.identifier("C:\\private\\key.txt", true))
    }

    @Test
    fun `函数名同名表列与布尔值不能穿过结构白名单`() {
        val projected = ShareContentProjection.sql("SELECT count, sum, first, next FROM json WHERE private_flag = TRUE AND private_other = FALSE")
        assertFalse(projected.omitted)
        val text = projected.sql!!
        listOf("count", "sum", "first", "next", "json", "private_flag", "private_other", "TRUE", "FALSE").forEach {
            assertFalse("同名标识符或布尔值泄漏：$text", text.contains(it, ignoreCase = true))
        }
        assertEquals(2, projected.maskedValues)
    }

    @Test
    fun `结构关键词保留而同名裸列继续匿名`() {
        val projected = ShareContentProjection.sql(
            "select count, sum, json from private_table where private_flag = true and id in (select 1 from other_table)",
        )
        assertFalse(projected.omitted)
        val text = projected.sql!!
        listOf("SELECT", "FROM", "WHERE", "AND", "IN").forEach {
            assertTrue("结构词 $it 应保留（否则接收方读不出查询结构）：$text", hasKeyword(text, it))
        }
        listOf("count", "sum", "json", "private_table", "private_flag", "other_table").forEach {
            assertFalse("同名标识符不得借白名单泄漏 $it：$text", text.contains(it, ignoreCase = true))
        }
        assertEquals(2, projected.maskedValues)
    }

    @Test
    fun `含标识符模式保留未加引号名字但引号内容仍不复制`() {
        val projected = ShareContentProjection.sql(
            "SELECT customer_name, \"SENTINEL_QUOTED\" FROM customers WHERE note = 'VALUE_SECRET'",
            true,
        )
        val text = projected.sql!!
        assertTrue("含标识符模式应保留未加引号表列名：$text", text.contains("customer_name"))
        assertTrue("含标识符模式应保留未加引号表列名：$text", text.contains("customers"))
        assertTrue("结构词应保留：$text", hasKeyword(text, "FROM"))
        assertTrue("结构词应保留：$text", hasKeyword(text, "WHERE"))
        assertFalse("引号内容在含标识符模式下仍不复制：$text", text.contains("SENTINEL_QUOTED"))
        assertFalse("字面量仍须打码：$text", text.contains("VALUE_SECRET"))
    }

    @Test
    fun `预算与空输入边界不回退原文`() {
        assertTrue(ShareContentProjection.sql("x".repeat(ShareContentProjection.MAX_INPUT_CHARS + 1)).omitted)
        assertTrue(ShareContentProjection.sql("SELECT " + "identifier,".repeat(3000)).omitted)
        assertEquals("", ShareContentProjection.sql("").sql)
        val typeCast = ShareContentProjection.sql("SELECT 7::integer FROM t")
        assertFalse(typeCast.omitted)
        assertTrue(typeCast.sql!!.contains("?::"))
    }
}
