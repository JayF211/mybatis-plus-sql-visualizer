package cn.song.mybatisplus.visualizer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-ExplainIndex（N-23）T-EI-02：EXPLAIN 计划索引缺失建议识别表驱动。
 * MySQL 表格式（ALL 全表扫 / key=NULL / filesort / temporary）、PG 文本树（Seq Scan）、
 * 阈值边界、无命中零输出、不支持方言静默降级、空清单渲染零差异。
 */
class ExplainIndexAdvisorTest {

    private val mysqlHeaders = listOf("id", "select_type", "table", "type", "key", "rows", "Extra")

    // ---------- MySQL 表格式 ----------

    @Test
    fun testMySQL全表扫超阈提示() {
        val rows = listOf(listOf("1", "SIMPLE", "orders", "ALL", "NULL", "500000", ""))
        val hints = ExplainIndexAdvisor.advise("MySQL", mysqlHeaders, rows)
        assertEquals(1, hints.size)
        assertEquals("FULL_SCAN", hints[0].rule)
        assertEquals("orders", hints[0].target)
        assertTrue(hints[0].advice.contains("500000"))
    }

    @Test
    fun testMySQL小表全扫不提示() {
        // rows 低于阈值：小表全扫常是更优计划，不噪音
        val rows = listOf(listOf("1", "SIMPLE", "t", "ALL", "NULL", "500", ""))
        assertTrue(ExplainIndexAdvisor.advise("MySQL", mysqlHeaders, rows).isEmpty())
    }

    @Test
    fun test阈值边界() {
        val at = listOf(listOf("1", "SIMPLE", "t", "ALL", "NULL", ExplainIndexAdvisor.ROWS_THRESHOLD.toString(), ""))
        assertEquals(1, ExplainIndexAdvisor.advise("MySQL", mysqlHeaders, at).size)
        val below = listOf(listOf("1", "SIMPLE", "t", "ALL", "NULL", "9999", ""))
        assertTrue(ExplainIndexAdvisor.advise("MySQL", mysqlHeaders, below).isEmpty())
    }

    @Test
    fun testMySQL非ALL但keyNULL弱信号() {
        val rows = listOf(listOf("1", "SIMPLE", "t", "index", "NULL", "50", ""))
        val hints = ExplainIndexAdvisor.advise("MySQL", mysqlHeaders, rows)
        assertEquals(1, hints.size)
        assertEquals("NO_INDEX_USED", hints[0].rule)
    }

    @Test
    fun testMySQLfilesort与temporary() {
        val rows = listOf(
            listOf("1", "SIMPLE", "t", "ref", "idx_a", "100", "Using where; Using filesort; Using temporary"),
        )
        val rules = ExplainIndexAdvisor.advise("MySQL", mysqlHeaders, rows).map { it.rule }
        assertTrue("FILESORT" in rules)
        assertTrue("TEMPORARY_TABLE" in rules)
    }

    // ---------- PG 文本树 ----------

    @Test
    fun testPGSeqScan超阈提示() {
        val rows = listOf(
            listOf("Seq Scan on big_table  (cost=0.00..35000.00 rows=300000 width=64)"),
        )
        val hints = ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), rows)
        assertEquals(1, hints.size)
        assertEquals("SEQ_SCAN", hints[0].rule)
        assertEquals("big_table", hints[0].target)
    }

    @Test
    fun testPG小表SeqScan不提示() {
        val rows = listOf(listOf("Seq Scan on tiny  (cost=0.00..12.00 rows=5 width=8)"))
        assertTrue(ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), rows).isEmpty())
    }

    // ---------- 降级与零差异 ----------

    @Test
    fun test不支持方言静默降级() {
        val rows = listOf(listOf("1", "SIMPLE", "t", "ALL", "NULL", "999999", ""))
        assertTrue(ExplainIndexAdvisor.advise("DM", mysqlHeaders, rows).isEmpty())
        assertTrue(ExplainIndexAdvisor.advise("Oracle", mysqlHeaders, rows).isEmpty())
    }

    @Test
    fun test无命中渲染零差异() {
        assertEquals("", ExplainIndexAdvisor.formatHints(emptyList()))
    }

    @Test
    fun test列名形态不符不猜() {
        // FORMAT=TREE 变体等非预期列名——不猜（空输出）
        assertTrue(ExplainIndexAdvisor.advise("MySQL", listOf("EXPLAIN"), listOf(listOf("foo"))).isEmpty())
    }
}
