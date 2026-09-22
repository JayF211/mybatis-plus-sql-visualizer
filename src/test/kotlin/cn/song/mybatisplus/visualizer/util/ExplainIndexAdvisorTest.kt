package cn.song.mybatisplus.visualizer.util

import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import cn.song.mybatisplus.visualizer.sql.ExplainSupport
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

    // ---------- PG JSON（T-ExplainChainHardening T-ECH-03：真缺陷修复） ----------

    /**
     * 真库实测形态钉住（PG 16.14，DBX 通道 2026-09-22）：
     * `EXPLAIN (FORMAT JSON, VERBOSE) SELECT * FROM bench_items` 返回**单列**（列名 `QUERY PLAN`）
     * 的 JSON 文本，关系名键为 `"Relation Name"`、行数键为 `"Plan Rows"`。
     *
     * 改前此形态必红：旧 PG 分支按 `Seq Scan on (\w+)` 文本树正则解析，JSON 里没有该文本形态
     * → PG 索引建议**永不触发**（真缺陷）。
     */
    private val pgJsonSeqScan = """[{"Plan":{"Node Type":"Seq Scan","Parallel Aware":false,""" +
        """"Async Capable":false,"Relation Name":"bench_items","Schema":"public",""" +
        """"Alias":"bench_items","Startup Cost":0.00,"Total Cost":256.00,"Plan Rows":10000,""" +
        """"Plan Width":87,"Output":["id","numeric_value","timestamp_value","text_value"]}}]"""

    /** 真库实测形态钉住：`SELECT 1` 的 JSON（节点无关系名，`Node Type` = `Result`）。 */
    private val pgJsonResult = """[{"Plan":{"Node Type":"Result","Parallel Aware":false,""" +
        """"Async Capable":false,"Startup Cost":0.00,"Total Cost":0.01,"Plan Rows":1,"Plan Width":4,""" +
        """"Output":["1"]}}]"""

    @Test
    fun testPG_JSON形态_SeqScan超阈提示() {
        val rows = listOf(listOf(pgJsonSeqScan))
        val hints = ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), rows)
        assertEquals(1, hints.size)
        assertEquals("SEQ_SCAN", hints[0].rule)
        assertEquals("bench_items", hints[0].target)
        assertTrue("建议文案应含实测行数", hints[0].advice.contains("10000"))
    }

    @Test
    fun testPG_JSON形态_小表SeqScan不提示() {
        val small = pgJsonSeqScan.replace(""""Plan Rows":10000""", """"Plan Rows":5""")
        assertTrue(
            ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), listOf(listOf(small))).isEmpty(),
        )
    }

    @Test
    fun testPG_JSON形态_非SeqScan节点不提示() {
        assertTrue(
            ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), listOf(listOf(pgJsonResult))).isEmpty(),
        )
    }

    @Test
    fun testPG_JSON形态_嵌套子计划可识别() {
        // PG 把子计划放 "Plans" 数组；顶层 Hash Join + 子节点 Seq Scan
        val nested = """[{"Plan":{"Node Type":"Hash Join","Plan Rows":10,"Plans":[""" +
            """{"Node Type":"Seq Scan","Relation Name":"child_tbl","Plan Rows":500000,""" +
            """"Plans":[]}]}}]"""
        val hints = ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), listOf(listOf(nested)))
        assertEquals(1, hints.size)
        assertEquals("child_tbl", hints[0].target)
    }

    @Test
    fun testPG_JSON被截断_分片扫描仍能识别顶层节点() {
        // 实测约束：McpExplainFormatter.MAX_CELL_CHARS=2000 会把长计划 JSON 截断（尾部丢 "…"）。
        // 截断只影响尾部，顶层节点对象仍完整 → 分片扫描路径必须仍产出建议（否则大计划永不提示）
        val truncated = pgJsonSeqScan.take(150) + "…"
        val hints = ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), listOf(listOf(truncated)))
        assertEquals(1, hints.size)
        assertEquals("bench_items", hints[0].target)
    }

    @Test
    fun testKingBase_JSON形态同PG() {
        val hints = ExplainIndexAdvisor.advise("KingBase（人大金仓）", listOf("QUERY PLAN"), listOf(listOf(pgJsonSeqScan)))
        assertEquals(1, hints.size)
        assertEquals("bench_items", hints[0].target)
    }

    @Test
    fun testPG文本树形态_仍支持_容错回落() {
        // JSON 档的容错回落：非 JSON 的文本计划（如驱动把计划渲染成文本树）仍走文本树规则
        val rows = listOf(listOf("Seq Scan on big_table  (cost=0.00..35000.00 rows=300000 width=64)"))
        val hints = ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), rows)
        assertEquals(1, hints.size)
        assertEquals("big_table", hints[0].target)
    }

    // ---------- 降级与零差异 ----------

    @Test
    fun test不支持方言静默降级() {
        val rows = listOf(listOf("1", "SIMPLE", "t", "ALL", "NULL", "999999", ""))
        // 独立复核 ⑳：分派键必须是**表键**（= Dialect.name = "DM（达梦）"）。此前本用例传品牌短名
        // "DM"——`ExplainSupport` 里查不到 → 落到 else 静默降级分支，而下面的 `.isEmpty()` 对
        // 「TEXT 档零命中」与「未登记降级」同形，肉眼区分不出 ⇒ 绿灯来自错误分支。
        // 故先把**路由**钉住（键写错时本行先红），再断言输出。
        val dmDialectName = "DM（达梦）"
        assertEquals(
            "DM 必须以表键（Dialect.name）路由到 TEXT 档，品牌短名 \"DM\" 不是表键",
            ExplainOutputFormat.TEXT,
            ExplainSupport.formatFor(dmDialectName),
        )
        // DM（达梦）在册且接入（TEXT 档），但表格式行不含文本树模式 → 零命中（不噪音、不猜）
        assertTrue(ExplainIndexAdvisor.advise(dmDialectName, mysqlHeaders, rows).isEmpty())
        // Oracle 在册但**有意不接入**（两步链另议）→ 静默降级
        assertTrue(ExplainIndexAdvisor.advise("Oracle", mysqlHeaders, rows).isEmpty())
        assertTrue("未知方言静默降级", ExplainIndexAdvisor.advise("no-such-dialect", mysqlHeaders, rows).isEmpty())
    }

    /** 分派改读格式后，MySQL 家族仍走表格式、PG 家族仍走 JSON——方言名大小写不敏感。 */
    @Test
    fun test方言名大小写不敏感() {
        val rows = listOf(listOf("1", "SIMPLE", "orders", "ALL", "NULL", "500000", ""))
        assertEquals(1, ExplainIndexAdvisor.advise("mysql", mysqlHeaders, rows).size)
        assertEquals(1, ExplainIndexAdvisor.advise("MySQL", mysqlHeaders, rows).size)
        assertEquals(
            1,
            ExplainIndexAdvisor.advise("postgresql", listOf("QUERY PLAN"), listOf(listOf(pgJsonSeqScan))).size,
        )
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
