package cn.song.mybatisplus.visualizer.sql.verify

import cn.song.mybatisplus.visualizer.model.BatchKind
import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.sql.DmDialect
import cn.song.mybatisplus.visualizer.sql.OracleDialect
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.junit.Test
import java.io.File

/**
 * P-立项-05 真机验证渲染导出（Tier 2 · DBX/MCP 执行路径）。
 *
 * 仅当提供 `-Dmpvs.dump.dir` 时运行（日常 `./gradlew test` 零影响）：把注册表全量方言的 TC-01~08
 * 渲染产物导出为 JSON——SQL 全部出自插件渲染链路（与 [DialectVerifySupport] 完全相同的
 * SqlGenerator / BatchModel 调用），由外部执行器（DBX MCP / 人工）在真实引擎上执行。
 *
 * 设计动机：外部实例（DBX 测试服务器）的 JDBC 凭据不进测试进程——渲染在本地 gradle 完成，
 * 执行在 MCP 侧完成，两段拼出「插件渲染 SQL → 真实引擎执行」的完整证据链。
 */
class DialectRenderDumpTest {

    @Test
    fun `导出各方言TC套件渲染产物`() {
        val dir = System.getProperty("mpvs.dump.dir")?.takeIf { it.isNotBlank() } ?: return
        val out = File(dir)
        out.mkdirs()
        val mapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
        for (dialect in DialectRegistry.INSTANCE.dialects) {
            mapper.writeValue(File(out, dumpFileName(dialect) + ".json"), RenderSet.of(dialect))
        }
    }

    /** 导出文件名取方言名的 ASCII 前缀（"DM（达梦）" → "DM"）。 */
    private fun dumpFileName(dialect: Dialect): String =
        dialect.name.takeWhile { it.code in 33..126 }.replace(" ", "")
}

/** 单方言渲染产物集（Jackson 直接序列化的 LinkedHashMap 结构，供 MCP 执行器消费）。 */
object RenderSet {

    fun of(dialect: Dialect): Map<String, Any?> {
        val q = dialect::quoteIdentifier
        val multiRowValues = !(dialect === OracleDialect.INSTANCE || dialect === DmDialect.INSTANCE)
        val upsertNative = dialect.renderUpsert("id", listOf("name")) != null ||
            dialect.renderUpsertStatement("t", listOf("id", "name"), "id") != null

        // 夹具 DDL 与种子数据（非插件产物，与 DialectVerifySupport.runTcSuite 一致：
        // 保留字列用方言引用符号，使插件渲染的按需引用 quoteIfKeyword 命中后能正确解析）
        val fixtureDdl = "CREATE TABLE dev_info (id INT PRIMARY KEY, biz_name VARCHAR(50), " +
            "${q("order")} INT, ${q("table")} VARCHAR(50), status INT)"
        val seedBase = "INSERT INTO dev_info (id, biz_name, ${q("order")}, ${q("table")}, status) VALUES "
        val seeds = if (multiRowValues) {
            listOf("$seedBase (1, 'alpha', 10, 't1', 0), (2, 'beta', 20, 't2', 1), (3, 'gamma', 30, 't3', 0)")
        } else {
            listOf(
                "$seedBase (1, 'alpha', 10, 't1', 0)",
                "$seedBase (2, 'beta', 20, 't2', 1)",
                "$seedBase (3, 'gamma', 30, 't3', 0)",
            )
        }

        val statements = mutableListOf<Map<String, Any?>>()
        fun add(id: String, sql: String, values: List<Any?> = emptyList()) {
            statements.add(linkedMapOf("id" to id, "sql" to sql, "values" to values))
        }

        add("TC-01", DialectVerifySupport.renderPagedSelect(dialect, offset = null, count = 10))
        add("TC-02", DialectVerifySupport.renderPagedSelect(dialect, offset = 1, count = 2))
        add("TC-03/04", DialectVerifySupport.renderReservedColumnSelect(dialect))
        add("TC-07", DialectVerifySupport.renderInSelect(dialect))
        add("TC-08", DialectVerifySupport.renderAggregateSelect(dialect))
        add("UPDATE", DialectVerifySupport.renderUpdate(dialect))
        add("INSERT", DialectVerifySupport.renderBatch(dialect, BatchKind.INSERT), listOf(100, "v", 1, "v"))
        if (upsertNative) {
            add("UPSERT-conflict", DialectVerifySupport.renderBatch(dialect, BatchKind.UPSERT), listOf(1, "v", 1, "v"))
            add("UPSERT-insert", DialectVerifySupport.renderBatch(dialect, BatchKind.UPSERT), listOf(200, "v", 1, "v"))
        } else {
            add("UPSERT-degrade", DialectVerifySupport.renderBatch(dialect, BatchKind.UPSERT), listOf(200, "v", 1, "v"))
        }
        add("DELETE", DialectVerifySupport.renderDelete(dialect))

        return linkedMapOf(
            "dialect" to dialect.name,
            "multiRowValues" to multiRowValues,
            "upsertNative" to upsertNative,
            "fixtureDdl" to fixtureDdl,
            "seeds" to seeds,
            "statements" to statements,
            "reservedWords" to dialect.reservedWords.toList(),
        )
    }
}
