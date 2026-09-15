package cn.song.mybatisplus.visualizer.util

import cn.song.mybatisplus.visualizer.log.LogToSqlPanel
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量等值谓词 IN 合并测试（T-BatchInMerge / O-14，T-BI-01/03 表驱动）：
 * 正例（10/500/501/超字节预算）、回退全集（复合键/IS NULL/子查询/列对列/函数包裹/UPDATE/
 * 混表/单条）、null 路径、注入样例显示（含引号字面量原样拼接）、文案构建（全同构/异构
 * 披露/截断后缀）。普通 JUnit——纯函数零 UI 依赖。
 */
class BatchPredicateMergeTest {

    private fun deletes(count: Int, table: String = "t", column: String = "id", start: Int = 1): List<String> =
        (start until start + count).map { "DELETE FROM $table WHERE $column = $it" }

    private fun preview(statements: List<String>) = BatchPredicateMerge.mergePreview(statements)!!

    // ======================== 合并正例 ========================

    @Test
    fun `十条同构等值删除全并`() {
        val p = preview(deletes(10))
        assertEquals(10, p.coveredCount)
        assertEquals(10, p.totalStatements)
        assertEquals(0, p.hiddenValues)
        assertEquals("t", p.table)
        assertEquals("id", p.column)
        assertEquals("DELETE FROM t WHERE id IN (1, 2, 3, 4, 5, 6, 7, 8, 9, 10)", p.mergedSql())
    }

    @Test
    fun `五百条行数上限边界不截断`() {
        val p = preview(deletes(500))
        assertEquals(500, p.values.size)
        assertEquals(0, p.hiddenValues)
    }

    @Test
    fun `五百零一条行数上限截断计数`() {
        val p = preview(deletes(501))
        assertEquals("行数上限 500", BatchPredicateMerge.MAX_ROWS, p.values.size)
        assertEquals("超限 1 条计数披露", 1, p.hiddenValues)
        assertEquals(501, p.coveredCount)
    }

    @Test
    fun `超字节预算截断计数`() {
        // 500 条长字符串字面量（每条 ~200B）→ 远超 8KB 预算
        val statements = (1..500).map { "DELETE FROM t WHERE token = 'token-value-pad-${it.toString().padStart(4, '0')}-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'" }
        val p = preview(statements)
        assertTrue("字节预算生效：可见值远小于总数（visible=${p.values.size}）", p.values.size < 500)
        assertTrue("截断计数 > 0", p.hiddenValues > 0)
        assertTrue("合并串在预算内", p.mergedSql().length <= BatchPredicateMerge.MAX_BYTES)
    }

    @Test
    fun `字符串与数值字面量混合原样拼接`() {
        val statements = listOf(
            "DELETE FROM t WHERE k = 'abc'",
            "DELETE FROM t WHERE k = 42",
            "DELETE FROM t WHERE k = -7",
        )
        val p = preview(statements)
        assertEquals("DELETE FROM t WHERE k IN ('abc', 42, -7)", p.mergedSql())
    }

    @Test
    fun `注入样例_含引号字面量原样显示`() {
        val statements = listOf(
            "DELETE FROM t WHERE name = 'O''Brien'",
            "DELETE FROM t WHERE name = 'x; DROP TABLE u'",
        )
        val p = preview(statements)
        // 字面量原样文本拼接（不再转义——展示形态，非执行文本）
        assertEquals("DELETE FROM t WHERE name IN ('O''Brien', 'x; DROP TABLE u')", p.mergedSql())
    }

    @Test
    fun `表名列名大小写不敏感归组`() {
        val statements = listOf(
            "DELETE FROM T WHERE ID = 1",
            "DELETE FROM t WHERE id = 2",
        )
        assertEquals("DELETE FROM T WHERE ID IN (1, 2)", preview(statements).mergedSql())
    }

    // ======================== 回退路径（任一命中 → 出局，绝不部分合并） ========================

    @Test
    fun `回退_单条无合并价值`() {
        assertNull(BatchPredicateMerge.mergePreview(deletes(1)))
        assertNull(BatchPredicateMerge.mergePreview(emptyList()))
    }

    @Test
    fun `回退_复合键谓词出局`() {
        val statements = deletes(3) + listOf("DELETE FROM t WHERE id = 4 AND status = 1")
        val p = preview(statements)
        assertEquals("复合谓词单条出局，其余同构 3 条成集", 3, p.coveredCount)
        assertEquals(4, p.totalStatements)
    }

    @Test
    fun `回退_IS_NULL出局`() {
        val statements = deletes(3) + listOf("DELETE FROM t WHERE id IS NULL")
        assertEquals(3, preview(statements).coveredCount)
    }

    @Test
    fun `回退_子查询出局`() {
        val statements = deletes(3) + listOf("DELETE FROM t WHERE id = (SELECT MAX(id) FROM u)")
        assertEquals(3, preview(statements).coveredCount)
    }

    @Test
    fun `回退_列对列出局`() {
        val statements = deletes(3) + listOf("DELETE FROM t WHERE id = other_col")
        assertEquals(3, preview(statements).coveredCount)
    }

    @Test
    fun `回退_函数包裹出局`() {
        val statements = deletes(3) + listOf("DELETE FROM t WHERE id = COALESCE(x, 1)")
        assertEquals(3, preview(statements).coveredCount)
    }

    @Test
    fun `回退_UPDATE不合并`() {
        val statements = (1..5).map { "UPDATE t SET a = $it WHERE id = $it" }
        assertNull("UPDATE 不合并（SET 不同构）", BatchPredicateMerge.mergePreview(statements))
    }

    @Test
    fun `回退_混表不归一组`() {
        val statements = deletes(3) + deletes(3, table = "u")
        // 两组各 3 条同构——取最大组（并列取其一），异构组按 N/M 披露不静默丢
        val p = preview(statements)
        assertEquals(3, p.coveredCount)
        assertEquals(6, p.totalStatements)
    }

    @Test
    fun `回退_解析失败单条出局`() {
        val statements = deletes(3) + listOf("DELETE FROM t WHERE")
        assertEquals(3, preview(statements).coveredCount)
    }

    @Test
    fun `回退_全部异构返回null`() {
        assertNull(BatchPredicateMerge.mergePreview(listOf("SELECT 1", "SELECT 2")))
        assertNull(BatchPredicateMerge.mergePreview(listOf("DELETE FROM t WHERE id = 1", "SELECT 2")))
    }

    // ======================== 文案构建（浮层参考行） ========================

    @Test
    fun `文案_全同构无截断`() {
        val hint = LogToSqlPanel.buildMergeAdText(deletes(3))!!
        assertEquals(message("logToSql.execute.mergeHint", 3, "DELETE FROM t WHERE id IN (1, 2, 3)"), hint)
    }

    @Test
    fun `文案_异构在场按N分之M披露`() {
        val statements = deletes(5) + listOf("INSERT INTO t (a) VALUES (1)")
        val hint = LogToSqlPanel.buildMergeAdText(statements)!!
        assertTrue(hint, hint == message("logToSql.execute.mergeHintSubset", 6, 5, "DELETE FROM t WHERE id IN (1, 2, 3, 4, 5)"))
    }

    @Test
    fun `文案_截断后缀与展示截断`() {
        // 501 条 → 行数上限截 1 条 + 展示预算裁掉的值——计数合并进「…等 N 条」且后缀恒可见
        val hint = LogToSqlPanel.buildMergeAdText(deletes(501))!!
        assertTrue("截断计数后缀在文案中（…等 N 条）", hint.contains("…等 "))
        assertTrue("双保险文案在场（执行仍逐条）", hint.contains("执行仍逐条"))
        assertTrue("展示串不超预算量级", hint.length < 320)
    }

    @Test
    fun `文案_无合并集返回null`() {
        assertNull(LogToSqlPanel.buildMergeAdText(listOf("SELECT 1", "SELECT 2")))
    }
}
