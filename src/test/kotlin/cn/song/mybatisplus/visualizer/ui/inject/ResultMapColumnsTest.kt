package cn.song.mybatisplus.visualizer.ui.inject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-XmlSynHi D5 后半：resultMap column 候选提取与排序纯逻辑测试。
 */
class ResultMapColumnsTest {

    private val mapper = """
        <mapper namespace="n">
          <select id="a">SELECT order_id, user_name, createdAt FROM t_order</select>
          <select id="b">SELECT count(*) AS cnt FROM t</select>
        </mapper>
    """.trimIndent()

    @Test
    fun test提取_逗号分段与别名与表前缀() {
        val cols = ResultMapColumns.extractColumns(mapper)
        assertTrue("order_id" in cols)
        assertTrue("user_name" in cols)
        assertTrue("createdAt" in cols)
        assertTrue("cnt" in cols) // AS 别名
    }

    @Test
    fun test建议_前缀精确优先() {
        val cols = listOf("order_id", "user_name", "createdAt")
        val out = ResultMapColumns.suggest(cols, "order")
        assertEquals("order_id", out.first())
    }

    @Test
    fun test建议_下划线驼峰近邻() {
        val cols = listOf("order_id", "other")
        val out = ResultMapColumns.suggest(cols, "orderId")
        assertEquals(listOf("order_id"), out)
    }

    @Test
    fun test建议_空前缀_全量保序() {
        val out = ResultMapColumns.suggest(listOf("b", "a"), "")
        assertEquals(listOf("b", "a"), out)
    }

    /** D5 后半接线验证：resultMap column 补全的真实调用链语料——提取+前缀+驼峰排序组合。 */
    @Test
    fun testD5后半_组合语料() {
        val cols = ResultMapColumns.extractColumns(mapper)
        val out = ResultMapColumns.suggest(cols, "order")
        assertEquals("order_id", out.first())
        val out2 = ResultMapColumns.suggest(cols, "user")
        assertEquals("user_name", out2.first())
    }
}
