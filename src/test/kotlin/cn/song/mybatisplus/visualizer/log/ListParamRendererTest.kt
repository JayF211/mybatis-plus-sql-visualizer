package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命名参数集合值渲染测试（T-ListParamCollect）：用户真机实测的方括号误入 SQL 问题。
 *
 * 纯逻辑表驱动（零平台依赖）。断言口径：产物绝不出现 `[` / `]`；非 IN 位置逐字节不变。
 */
class ListParamRendererTest {

    /** 渲染入口（渲染回调取恒等 → 断言剥括号后的元素文本本身）。 */
    private fun render(sql: String, name: String, value: String?): String =
        ListParamRenderer.renderParam(value, name in ListParamRenderer.inClauseParamNames(sql)) { it }

    // ---------- 集合元素拆分 ----------

    @Test
    fun `拆分_单元素与多元素与空集合`() {
        assertEquals(listOf("a"), ListParamRenderer.splitListElements("[a]"))
        assertEquals(listOf("a", "b"), ListParamRenderer.splitListElements("[a, b]"))
        assertEquals(listOf("1", "2", "3"), ListParamRenderer.splitListElements("[1,2 ,3]"))
        assertEquals(emptyList<String>(), ListParamRenderer.splitListElements("[]"))
        assertEquals(emptyList<String>(), ListParamRenderer.splitListElements("[ ]"))
    }

    @Test
    fun `拆分_非集合形态返回null`() {
        assertNull(ListParamRenderer.splitListElements("abc"))
        assertNull(ListParamRenderer.splitListElements("[abc"))
        assertNull(ListParamRenderer.splitListElements("abc]"))
        assertNull(ListParamRenderer.splitListElements("[a]b"))
        assertNull(ListParamRenderer.splitListElements("["))
    }

    // ---------- IN 子句识别 ----------

    @Test
    fun `IN识别_大小写与空格与无空格形态`() {
        assertEquals(setOf("p1"), ListParamRenderer.inClauseParamNames("SELECT * FROM t WHERE id IN (:p1)"))
        assertEquals(setOf("p1"), ListParamRenderer.inClauseParamNames("SELECT * FROM t WHERE id in (:p1)"))
        assertEquals(setOf("p1"), ListParamRenderer.inClauseParamNames("SELECT * FROM t WHERE id IN(:p1)"))
        assertEquals(setOf("p1"), ListParamRenderer.inClauseParamNames("SELECT * FROM t WHERE id\nIN\n(:p1)"))
        assertEquals(setOf("a", "b"), ListParamRenderer.inClauseParamNames("WHERE x IN (:a, :b)"))
    }

    @Test
    fun `IN识别_非IN位置与字符串内不误判`() {
        assertTrue(ListParamRenderer.inClauseParamNames("SELECT * FROM t WHERE a = :p1").isEmpty())
        assertTrue(ListParamRenderer.inClauseParamNames("SELECT * FROM t WHERE a = ':p1'").isEmpty())
        // 字符串字面量里的 "IN (" 不触发；标识符 int_/index 不误判
        assertTrue(ListParamRenderer.inClauseParamNames("SELECT 'IN (:p1)' AS c").isEmpty())
        assertTrue(ListParamRenderer.inClauseParamNames("SELECT index_col FROM t WHERE a = :p1").isEmpty())
        assertTrue(ListParamRenderer.inClauseParamNames("SELECT * FROM t WHERE a = :p1 AND b = :p2").isEmpty())
    }

    @Test
    fun `IN识别_注释与嵌套括号`() {
        assertTrue(ListParamRenderer.inClauseParamNames("SELECT 1 -- IN (:p1)").isEmpty())
        assertTrue(ListParamRenderer.inClauseParamNames("SELECT 1 /* IN (:p1) */ WHERE a = :p2").isEmpty())
        assertEquals(
            setOf("p1"),
            ListParamRenderer.inClauseParamNames("SELECT * FROM t WHERE id IN ((:p1))"),
        )
    }

    @Test
    fun `IN识别_非IN参数在IN之后仍不计入`() {
        val sql = "SELECT * FROM t WHERE ( (\"deleted\" = :param1) AND (\"id\" IN (:param3)) )"
        assertEquals(setOf("param3"), ListParamRenderer.inClauseParamNames(sql))
    }

    // ---------- 渲染 ----------

    @Test
    fun `渲染_用户真机样例_方括号不落进SQL`() {
        val sql = """SELECT COUNT(*) as count FROM tableA WHERE ( ("deleted" = :param1) AND (("deleted" = :param2) AND ("id" IN (:param3))) )"""
        assertEquals("0", render(sql, "param1", "0"))
        assertEquals("0", render(sql, "param2", "0"))
        assertEquals("6b31d693xxxxx7f4979b", render(sql, "param3", "[6b31d693xxxxx7f4979b]"))
    }

    @Test
    fun `渲染_多元素按逗号拆分_空集合按NULL`() {
        assertEquals("a, b", render("WHERE x IN (:p)", "p", "[a, b]"))
        assertEquals("NULL", render("WHERE x IN (:p)", "p", "[]"))
    }

    @Test
    fun `渲染_非IN位置保持原值_含形如方括号的值`() {
        assertEquals("[a]", render("WHERE x = :p", "p", "[a]"))
        assertEquals("abc", render("WHERE x = :p", "p", "abc"))
        // null 字面量交回调渲染（本层不臆造 NULL 文本；解析器的回调产出 "NULL"）
        assertEquals("", render("WHERE x = :p", "p", null))
    }

    @Test
    fun `渲染_回调逐元素生效`() {
        val quoted = ListParamRenderer.renderParam("[a, b]", true) { "'" + it.replace("'", "''") + "'" }
        assertEquals("'a', 'b'", quoted)
        // 元素含单引号 → 转义交给回调（本层不做转义猜测）
        assertEquals("'''", ListParamRenderer.renderParam("[']", true) { "'" + it + "'" })
    }
}
