package cn.song.mybatisplus.visualizer.util

import cn.song.mybatisplus.visualizer.history.ExplainPlanKey
import cn.song.mybatisplus.visualizer.history.ExplainPlanSnapshot
import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult
import cn.song.mybatisplus.visualizer.share.ShareContentProjection
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import org.junit.Assert.*
import org.junit.Test

class AiContextV2ExportTest {
    private val key = ExplainPlanKey.create("project", "eb03ba8b-a414-4453-b6e0-5bf55b34fed2", "MySQL", ExplainOutputFormat.TABLE, "EXPLAIN SELECT 42")
    private fun plan(k: ExplainPlanKey = key) = ExplainPlanSnapshot.create(k, RawResult(
        listOf("type", "rows", "cost", "Extra", "table", "Filter"),
        listOf(listOf("ALL", "10", "1.25", "private_extra", "private_table", "private_filter")),
        notices = listOf("private_notice")))

    @Test fun strictSqlAndMissingSections() {
        val result = AiContextExport.buildV2("SELECT secret_column FROM secret_table WHERE token='secret_value' /* secret_path */", "MySQL")
        assertTrue(result.block.contains("AI context v2"))
        assertFalse(result.block.contains("secret_"))
        assertTrue(result.block.contains("EXPLAIN: not obtained"))
        assertTrue(result.block.contains("Static schema: not obtained"))
    }

    @Test fun matchingPlanOnlyAndWhiteList() {
        val good = AiContextExport.buildV2("SELECT 42", "MySQL", key, listOf(plan())).block
        assertTrue(good.contains("type=ALL; rows=10; cost=1.25"))
        assertFalse(good.contains("private_"))
        listOf(key.copy(projectIdentity = "other"), key.copy(dataSourceUuid = "other"),
            key.copy(dialect = "PostgreSQL"), key.copy(format = "JSON"), key.copy(exactFingerprint = "other"),
            key.copy(templateFingerprint = "other")).forEach { wrong ->
            assertTrue(AiContextExport.buildV2("SELECT 42", "MySQL", key, listOf(plan(wrong))).block.contains("EXPLAIN: not obtained"))
        }
        assertTrue(AiContextExport.buildV2("SELECT 42", "MySQL", null, listOf(plan())).block.contains("EXPLAIN: not obtained"))
    }

    @Test fun jsonNeverIncludesFiltersExtrasNoticeIdentifiersOrPaths() {
        val raw = RawResult(listOf("QUERY PLAN"), listOf(listOf("""[{"Plan":{"Node Type":"Seq Scan","Relation Name":"private_table","Plan Rows":15,"Total Cost":2.5,"Filter":"private_filter","Output":["private_column"],"Plans":[{"Node Type":"private_injected_type","Plan Rows":3,"Total Cost":"private_cost"}]},"NOTICE":"private_notice"}]""")), notices = listOf("private_notice"))
        val summary = AiContextPlanSummary.summarize(raw, ExplainOutputFormat.JSON)!!
        assertTrue(summary.contains("node=Seq Scan; rows=15; cost=2.5"))
        assertTrue(summary.contains("rows=3"))
        assertFalse(summary.contains("private_"))
        assertFalse(summary.contains("Filter"))
        assertNull(AiContextPlanSummary.summarize(raw.copy(rows = listOf(listOf("{private_broken"))), ExplainOutputFormat.JSON))
        assertNull(AiContextPlanSummary.summarize(raw.copy(truncated = true), ExplainOutputFormat.JSON))
    }

    @Test fun invalidNumericCellsAndNodeTypesNeverPass() {
        val raw = RawResult(listOf("type", "rows", "cost"), listOf(listOf("private_type", "1;private_rows", "NaN")))
        assertNull(AiContextPlanSummary.summarize(raw, ExplainOutputFormat.TABLE))
    }

    @Test fun sectionsOmittedRatherThanRawFallbackUnderUtf8Budget() {
        val raw = RawResult(listOf("type", "rows", "cost"), List(500) { listOf("ALL", "1234567890123456", "1234567890123456.12345678") })
        val snapshot = ExplainPlanSnapshot.create(key, raw)
        val block = AiContextExport.buildV2("SELECT 42", "MySQL", key, listOf(snapshot)).block
        assertTrue(block.toByteArray(Charsets.UTF_8).size <= ShareContentProjection.MAX_OUTPUT_BYTES)
        assertTrue(block.contains("EXPLAIN: omitted (16 KiB budget)"))
        val huge = AiContextExport.buildV2("SELECT " + "私密".repeat(150000), "MySQL").block
        assertTrue(huge.toByteArray(Charsets.UTF_8).size <= ShareContentProjection.MAX_OUTPUT_BYTES)
        assertFalse(huge.contains("私密"))
        assertTrue(huge.contains("SQL: omitted"))
    }

    @Test fun structuralKeywordsRemainVisibleInDefaultMode() {
        val block = AiContextExport.buildV2(
            "SELECT customer_name FROM customers WHERE id = 42 AND status = 'active'",
            "MySQL",
        ).block
        assertTrue("默认模式仍须声明标识符隐藏", block.contains("identifiers hidden"))
        listOf("FROM", "WHERE", "AND").forEach { keyword ->
            assertTrue(
                "结构词 $keyword 应保留，否则接收方读不出查询结构：$block",
                Regex("(?<![A-Za-z0-9_])$keyword(?![A-Za-z0-9_])").containsMatchIn(block),
            )
        }
        assertFalse("表列名仍须隐藏", block.contains("customer"))
        assertFalse("字面量仍须打码", block.contains("42"))
    }

    @Test fun includeIdentifiersHeaderAndStaticSchema() {
        val schema = AiContextExport.StaticSchema("customers", listOf("customer_name", "status", "customer_name"))
        val block = AiContextExport.buildV2(
            "SELECT customer_name FROM customers WHERE id = 42",
            "MySQL",
            schema = schema,
            includeIdentifiers = true,
        ).block
        assertTrue(
            "含名模式头部须明确告知标识符可见且引号内容仍隐藏",
            block.contains("identifiers visible as written (quoted tokens stay hidden)"),
        )
        assertTrue("SQL 段应保留表列名：$block", block.contains("SELECT customer_name FROM customers"))
        assertTrue(
            "静态摘要应列出表名与去重后的列名：$block",
            block.contains("Static schema: table=customers; columns=2: customer_name, status"),
        )
        assertFalse("含名模式仍须遮蔽字面量", block.contains("42"))
    }

    @Test fun includeIdentifiersStillHidesQuotedTokensAndValues() {
        val block = AiContextExport.buildV2(
            "SELECT customer_name, \"SENTINEL_QUOTED\" FROM customers WHERE note = 'SENTINEL_VALUE'",
            "MySQL",
            includeIdentifiers = true,
        ).block
        assertTrue(block.contains("customer_name"))
        assertFalse("引号内容在含名模式下仍不复制", block.contains("SENTINEL_QUOTED"))
        assertFalse("字面量在含名模式下仍打码", block.contains("SENTINEL_VALUE"))
    }

    @Test fun includeIdentifiersCapsSchemaColumns() {
        val columns = (1..60).map { "col_$it" }
        val block = AiContextExport.buildV2(
            "SELECT 1", "MySQL",
            schema = AiContextExport.StaticSchema("wide_table", columns),
            includeIdentifiers = true,
        ).block
        assertTrue(block.contains("columns=60: col_1"))
        assertTrue(block.contains("col_50"))
        assertFalse("超过 50 列不应继续列出", block.contains("col_51"))
        assertTrue("超限应标注剩余数量", block.contains("…(+10)"))
    }

    @Test fun v1GoldenBytesUnchanged() {
        assertArrayEquals("-- SQL Visualizer AI context (sensitive literals masked)\n-- dialect: MySQL\n-- masked literals: 0\nSELECT 1".toByteArray(),
            AiContextExport.build("SELECT 1", "MySQL").block.toByteArray())
    }
}
