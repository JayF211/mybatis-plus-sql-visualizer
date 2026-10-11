package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R33 PG/KingBase JSON 计划树模型构建单测（渲染判别测试）。
 *
 * 形态来源（ExplainIndexAdvisor T-ECH-03 同款真机实证）：PG `EXPLAIN (FORMAT JSON, VERBOSE)`
 * 返回单列 `QUERY PLAN`，值为数组 `[{ "Plan": {…} }]`，节点键 `"Node Type"` / `"Relation Name"` /
 * `"Plan Rows"`，子节点在 `"Plans"` 数组里。本构建器把它折叠成可展示树：
 *  - 计划节点标签 = `Node Type on Relation (rows=N)`（缺关系名/行数按段省略）；
 *  - 非 JSON 文本（截断单元格/意外形态）降级为文本叶节点——诚实降级，不猜不炸。
 */
class ExplainJsonPlanTreeTest {

    @Test
    fun `真机PG形态_嵌套计划树`() {
        val json = """
            [{"Plan": {"Node Type": "Limit", "Plans": [
                {"Node Type": "Seq Scan", "Relation Name": "bench_items", "Plan Rows": 10000}
            ]}}]
        """.trimIndent()
        val roots = ExplainJsonPlanTree.build(listOf(listOf(json)))!!
        assertEquals(1, roots.size)
        val limit = roots[0]
        assertEquals("Limit", limit.label)
        assertEquals(1, limit.children.size)
        assertEquals("Seq Scan on bench_items (rows=10000)", limit.children[0].label)
    }

    @Test
    fun `顶层多节点数组_多根`() {
        val json = """[{"Node Type": "Sort"}, {"Node Type": "Nested Loop"}]"""
        val roots = ExplainJsonPlanTree.build(listOf(listOf(json)))!!
        assertEquals(2, roots.size)
        assertEquals("Sort", roots[0].label)
        assertEquals("Nested Loop", roots[1].label)
    }

    @Test
    fun `标签省略段_无关系名与行数`() {
        val roots = ExplainJsonPlanTree.build(listOf(listOf("""[{"Node Type": "Hash"}]""")))!!
        assertEquals("Hash", roots[0].label)
    }

    @Test
    fun `标签带别名回退_无RelationName时用Alias`() {
        val roots = ExplainJsonPlanTree.build(
            listOf(listOf("""[{"Node Type": "Seq Scan", "Alias": "u", "Plan Rows": 5}]""")),
        )!!
        assertEquals("Seq Scan on u (rows=5)", roots[0].label)
    }

    @Test
    fun `行数取PlanRows_缺省回退ActualRows`() {
        val roots = ExplainJsonPlanTree.build(
            listOf(listOf("""[{"Node Type": "Seq Scan", "Relation Name": "t", "Actual Rows": 42}]""")),
        )!!
        assertEquals("Seq Scan on t (rows=42)", roots[0].label)
    }

    @Test
    fun `标量键值入树`() {
        val roots = ExplainJsonPlanTree.build(
            listOf(listOf("""[{"Node Type": "Limit", "Planning Time": 0.123}]""")),
        )!!
        assertEquals(1, roots[0].children.size)
        assertEquals("Planning Time: 0.123", roots[0].children[0].label)
    }

    @Test
    fun `截断JSON降级文本叶节点_不猜不炸`() {
        // 单元格 2000 字符预算截断（T-ECH-03 实测最常见成因）：解析失败 → 原文文本叶节点
        val truncated = """[{"Plan": {"Node Type": "Seq Scan", "Relation Na"""
        val roots = ExplainJsonPlanTree.build(listOf(listOf(truncated)))!!
        assertEquals(1, roots.size)
        assertEquals(truncated, roots[0].label)
        assertTrue(roots[0].children.isEmpty())
    }

    @Test
    fun `非JSON文本降级文本叶节点`() {
        val roots = ExplainJsonPlanTree.build(listOf(listOf("not a json at all")))!!
        assertEquals(1, roots.size)
        assertEquals("not a json at all", roots[0].label)
    }

    @Test
    fun `空行清单_空树`() {
        assertNull(ExplainJsonPlanTree.build(emptyList()))
    }

    @Test
    fun `空白单元格跳过`() {
        val roots = ExplainJsonPlanTree.build(listOf(listOf("  ", "")))
        assertNull(roots)
    }
}
