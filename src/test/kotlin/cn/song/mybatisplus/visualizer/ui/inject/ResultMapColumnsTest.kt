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
    fun testF89_超长文本熔断_零提取() {
        // F89（T-PerfSweep25）：提取前文本长度熔断（对齐 XmlSynHiGate.MAX_TEXT_LENGTH 100K 口径）。
        // 修复前超限文本照常全文 findAll——尾部的合法 select 仍被提出（两条入口均非空 → 红）；
        // 修复后超限一律空候选（补全候选提取的输入卡顿防线）。
        val huge = "<!-- " + "x".repeat(XmlSynHiGate.MAX_TEXT_LENGTH) + " -->\n" +
            "<select id=\"a\" resultMap=\"rm\">SELECT fuse_col FROM t</select>"
        assertTrue("夹具须真超限", huge.length > XmlSynHiGate.MAX_TEXT_LENGTH)
        assertTrue(
            "超限全文提取熔断=空候选（修复前提取出 fuse_col → 红）",
            ResultMapColumns.extractColumns(huge).isEmpty(),
        )
        assertTrue(
            "超限 resultMap 定向提取熔断=空候选（修复前提取出 fuse_col → 红）",
            ResultMapColumns.extractColumnsForResultMap(huge, "rm").isEmpty(),
        )
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
