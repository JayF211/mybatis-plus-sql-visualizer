package cn.song.mybatisplus.visualizer.sql.verify

import cn.song.mybatisplus.visualizer.sql.SqlKeywords.H2_RESERVED
import cn.song.mybatisplus.visualizer.sql.SqlKeywords.MYSQL_RESERVED
import cn.song.mybatisplus.visualizer.sql.SqlKeywords.PG_RESERVED
import cn.song.mybatisplus.visualizer.sql.SqlKeywords.SQLITE_RESERVED
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1（Tier 1）：H2 嵌入式方言真实验证（P-立项-05，零外部依赖）。
 *
 * H2 2.3.232 兼容模式按官方文档模拟各数据库语法——插件渲染的 SQL（[SqlGenerator]/[BatchModel]
 * 产物）在各模式引擎上**真实执行**：
 *
 * | 引擎配对 | 验证方言 | 证据级别 |
 * |----------|----------|----------|
 * | H2 REGULAR | H2 | 原生形态 100% 真实 |
 * | MODE=MySQL | MySQL | 语法形态级（LIMIT x,y / 反引号 / ON DUPLICATE KEY UPDATE） |
 * | MODE=MariaDB | MariaDB | 同上 |
 * | MODE=PostgreSQL | PostgreSQL | 语法形态级（LIMIT/OFFSET / 双引号；ON CONFLICT DO UPDATE 为 H2 不支持，SKIP→Docker） |
 * | MODE=Oracle | Oracle | 语法形态级（OFFSET..FETCH / 双引号） |
 * | MODE=MSSQLServer | SQL Server | 语法形态级（OFFSET..FETCH / 方括号） |
 *
 * 用例对齐开发计划 TC-01~08：基础分页 / 带偏移分页 / 保留字引用（反引号/双引号/方括号）/
 * UPSERT / IN 子句 / 聚合函数，另含 UPDATE/DELETE/INSERT 模板与**保留字全量扫描**
 * （方言保留字集合逐词构造「插件渲染 SELECT+INSERT+UPDATE+UPSERT」并执行——
 * 「宁可多包」策略的全量合法性证明）。
 */
class DialectH2VerificationTest {

    // ------------------------------------------------------------------
    // TC-01~08 全套（插件渲染 SQL 逐条执行）
    // ------------------------------------------------------------------

    @Test
    fun `TC01-08_H2方言_正则模式真实执行`() = tc(DialectVerifySupport.h2Engines[0], "p05_h2_tc")

    @Test
    fun `TC01-08_MySQL方言_MySQL兼容模式执行`() = tc(DialectVerifySupport.h2Engines[1], "p05_mysql_tc")

    @Test
    fun `TC01-08_MariaDB方言_MariaDB兼容模式执行`() = tc(DialectVerifySupport.h2Engines[2], "p05_maria_tc")

    @Test
    fun `TC01-08_PostgreSQL方言_PG兼容模式执行`() = tc(DialectVerifySupport.h2Engines[3], "p05_pg_tc")

    @Test
    fun `TC01-08_Oracle方言_Oracle兼容模式执行`() = tc(DialectVerifySupport.h2Engines[4], "p05_ora_tc")

    @Test
    fun `TC01-08_SQLServer方言_MSSQLServer兼容模式执行`() = tc(DialectVerifySupport.h2Engines[5], "p05_mssql_tc")

    private fun tc(spec: DialectVerifySupport.EngineSpec, dbName: String) {
        DialectVerifySupport.open(spec, dbName).use { conn ->
            DialectVerifySupport.runTcSuite(conn, spec)
        }
    }

    // ------------------------------------------------------------------
    // 保留字全量扫描（各方言保留字集合逐词 → 插件渲染 → 引擎执行）
    // ------------------------------------------------------------------

    @Test
    fun `保留字全量扫描_H2方言`() = sweep(DialectVerifySupport.h2Engines[0], "p05_h2_sweep")

    @Test
    fun `保留字全量扫描_MySQL方言_MySQL兼容模式`() = sweep(DialectVerifySupport.h2Engines[1], "p05_mysql_sweep")

    @Test
    fun `保留字全量扫描_MariaDB方言_MariaDB兼容模式`() = sweep(DialectVerifySupport.h2Engines[2], "p05_maria_sweep")

    @Test
    fun `保留字全量扫描_PostgreSQL方言_PG兼容模式`() = sweep(DialectVerifySupport.h2Engines[3], "p05_pg_sweep")

    @Test
    fun `保留字全量扫描_Oracle方言_Oracle兼容模式`() = sweep(DialectVerifySupport.h2Engines[4], "p05_ora_sweep")

    @Test
    fun `保留字全量扫描_SQLServer方言_MSSQLServer兼容模式`() = sweep(DialectVerifySupport.h2Engines[5], "p05_mssql_sweep")

    private fun sweep(spec: DialectVerifySupport.EngineSpec, dbName: String) {
        DialectVerifySupport.open(spec, dbName).use { conn ->
            val failures = DialectVerifySupport.sweepReservedWords(conn, spec, spec.dialect.reservedWords)
            assertTrue(
                "${spec.label} 方言保留字扫描失败 ${failures.size}/${spec.dialect.reservedWords.size} 个词" +
                    "（包裹后在本引擎不可执行）:\n" + failures.joinToString("\n"),
                failures.isEmpty(),
            )
        }
    }

    // ------------------------------------------------------------------
    // 引擎保留字覆盖探测（漏包检测：引擎拒绝裸用的词必须在本方言集合内）
    // ------------------------------------------------------------------

    @Test
    fun `引擎保留字覆盖_H2方言_漏包检测`() {
        DialectVerifySupport.open(DialectVerifySupport.h2Engines[0], "p05_h2_cover").use { conn ->
            val rejected = DialectVerifySupport.probeUnquotedRejected(conn, h2CoverageCandidates)
            val missing = rejected.filter { it !in H2_RESERVED }
            assertTrue(
                "H2 引擎拒绝裸用（裸用即语法错误）但 H2Dialect 保留字集合缺失的词: $missing",
                missing.isEmpty(),
            )
        }
    }

    /**
     * H2 覆盖候选词：H2 官方关键字表候选 + 全部其他方言保留字集合 + 常见业务列名——
     * 探测引擎对每个词「裸用作列名」的接受度，拒绝项必须已被 H2Dialect 集合覆盖。
     */
    private val h2CoverageCandidates: Set<String> = buildSet {
        addAll(H2_RESERVED)
        addAll(PG_RESERVED)
        addAll(MYSQL_RESERVED)
        addAll(SQLITE_RESERVED)
        // 常见业务列名（真实系统高频字段——引擎若拒绝必须已在集合中）
        addAll(
            listOf(
                "name", "status", "type", "value", "key", "comment", "desc", "order",
                "domain", "section", "password", "text", "image", "page", "number",
                "date", "level", "size", "mode", "user", "content", "code", "title",
                "sort", "state", "result", "data", "group", "index", "table", "limit",
            ),
        )
    }
}
