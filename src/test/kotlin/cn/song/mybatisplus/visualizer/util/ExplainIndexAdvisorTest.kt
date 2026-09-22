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

    // ---------- PG JSON 形态（T-ECH-03：PG/KingBase 渲染的是 FORMAT JSON，非文本树） ----------

    /**
     * 实测形态（真 PG 18.3，DBX 只读实跑 `EXPLAIN (FORMAT JSON, VERBOSE) SELECT * FROM
     * information_schema.tables`）：**单列**（列名 `QUERY PLAN`）**紧凑无空格** JSON 文本，
     * 顺序扫描节点的键为 `"Node Type":"Seq Scan"` + `"Relation Name"` + `"Plan Rows"`——
     * 文本树正则 `Seq Scan on (\w+)` 在该形态上永不命中（本任务 P1 真缺陷）。
     * 与解析无关的 `Output` 数组已略（字段形态与键名保持实测原样）。
     */
    private val pgJsonSmallScan = listOf(
        listOf(
            """[{"Plan":{"Node Type":"Hash Join","Join Type":"Left","Startup Cost":58.02,""" +
                """"Total Cost":108.00,"Plan Rows":123,"Plan Width":608,"Plans":[""" +
                """{"Node Type":"Hash Join","Parent Relationship":"Outer","Join Type":"Inner",""" +
                """"Startup Cost":2.00,"Total Cost":42.60,"Plan Rows":123,"Plan Width":141,"Plans":[""" +
                """{"Node Type":"Seq Scan","Parent Relationship":"Outer","Relation Name":"pg_class",""" +
                """"Schema":"pg_catalog","Alias":"c","Startup Cost":0.00,"Total Cost":40.08,""" +
                """"Plan Rows":184,"Plan Width":77}]}]}}]""",
        ),
    )

    /** 同上形态，仅把顺序扫描节点的 `"Plan Rows"` 提到阈值之上（可触发判定）。 */
    private val pgJsonBigScan = listOf(
        listOf(
            """[{"Plan":{"Node Type":"Hash Join","Plan Rows":123,"Plans":[""" +
                """{"Node Type":"Seq Scan","Relation Name":"pg_class","Schema":"pg_catalog",""" +
                """"Plan Rows":300000,"Plan Width":77}]}}]""",
        ),
    )

    @Test
    fun testPG_JSON形态_顺序扫描超阈提示() {
        val hints = ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), pgJsonBigScan)
        assertEquals(1, hints.size)
        assertEquals("SEQ_SCAN", hints[0].rule)
        assertEquals("pg_class", hints[0].target)
        assertTrue(hints[0].advice.contains("300000"))
    }

    @Test
    fun testPG_JSON形态_真实小表顺序扫描不提示() {
        // 实测真库形态（Plan Rows=184 < 阈值）：解析认得形态但按阈值降噪，不出建议
        assertTrue(ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), pgJsonSmallScan).isEmpty())
    }

    @Test
    fun testPG_JSON形态_空白不敏感_金仓多行美化同判() {
        // KingbaseES V8 实测返回多行美化 JSON（冒号后带空格）——按空白敏感的字符串匹配会在该侧失效
        val pretty = listOf(
            listOf(
                "[\n" +
                    "  {\n" +
                    "    \"Plan\": {\n" +
                    "      \"Node Type\": \"Seq Scan\",\n" +
                    "      \"Relation Name\": \"ceshi\",\n" +
                    "      \"Plan Rows\": 42000\n" +
                    "    }\n" +
                    "  }\n" +
                    "]",
            ),
        )
        val hints = ExplainIndexAdvisor.advise("KingBase（人大金仓）", listOf("QUERY PLAN"), pretty)
        assertEquals(1, hints.size)
        assertEquals("SEQ_SCAN", hints[0].rule)
        assertEquals("ceshi", hints[0].target)
    }

    @Test
    fun testPG_JSON形态_无顺序扫描节点不提示() {
        val json = listOf(listOf("""[{"Plan":{"Node Type":"Result","Plan Rows":1}}]"""))
        assertTrue(ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), json).isEmpty())
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
        assertTrue(ExplainIndexAdvisor.advise("MySQL", listOf("EXPLAIN"), rows = listOf(listOf("foo"))).isEmpty())
    }
}
