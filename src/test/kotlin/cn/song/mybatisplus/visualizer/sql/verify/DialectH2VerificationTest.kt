package cn.song.mybatisplus.visualizer.sql.verify

import cn.song.mybatisplus.visualizer.sql.H2Dialect
import cn.song.mybatisplus.visualizer.sql.IdentifierCaseSource
import cn.song.mybatisplus.visualizer.sql.IdentifierFolding
import cn.song.mybatisplus.visualizer.sql.SqlKeywords.H2_RESERVED
import cn.song.mybatisplus.visualizer.sql.SqlKeywords.MYSQL_RESERVED
import cn.song.mybatisplus.visualizer.sql.SqlKeywords.PG_RESERVED
import cn.song.mybatisplus.visualizer.sql.SqlKeywords.SQLITE_RESERVED
import org.junit.Assert.assertEquals
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

    // ------------------------------------------------------------------
    // 标识符折叠方向实测对照（O-51 / T-IdentifierFoldingFamily：H2 族归属）
    // ------------------------------------------------------------------

    /**
     * H2 折叠方向**引擎实测对照**——[H2Dialect] 折叠族归属的唯一判据来源。
     *
     * 实验设计：用**带引号**创建两个名字（H2 的引号内大小写原样保留，故能造出「字典里就是小写」
     * 与「字典里就是大写」两个真实存在的对象），再分别用**裸写**访问。裸写会被引擎折叠一次，
     * 命中哪一个对象即暴露折叠方向（引号形态作基线，证明两个对象都真实存在）：
     *
     * | 读数 | 语句 | 命中 ⇒ | 不命中 ⇒ |
     * |------|------|--------|----------|
     * | A | `SELECT COUNT(*) FROM fold_lower`（引号创建的小写名，裸写） | 折为**小写** | 折为**大写** |
     * | B | `SELECT COUNT(*) FROM FOLD_UPPER`（引号创建的大写名，裸写） | 折为**大写** | 折为**小写** |
     * | C/D | `FROM "fold_lower"` / `FROM "FOLD_UPPER"` | 基线（对象存在） | 夹具异常 |
     *
     * 本机读数（H2 2.3.232 REGULAR，2026-09-22 实测）：**A 失败**（引擎把裸写 `fold_lower`
     * 折叠成 `FOLD_LOWER` 去找 → `Table "FOLD_LOWER" not found`）、**B 成功**、C/D 成功
     * ⇒ **裸标识符折为大写（UPPER 族，与 Oracle 同向）**。
     *
     * 本用例把族归属钉在**真实引擎读数**上（而非钉在另一条断言的期望值里）：归属写错时先红。
     */
    @Test
    fun `标识符折叠方向_H2引擎实测对照`() {
        DialectVerifySupport.open(DialectVerifySupport.h2Engines[0], "p05_h2_fold").use { conn ->
            DialectVerifySupport.exec(conn, "CREATE TABLE \"fold_lower\" (id INT)")
            DialectVerifySupport.exec(conn, "CREATE TABLE \"FOLD_UPPER\" (id INT)")

            // 基线：引号形态必须命中（否则是夹具坏了，读到的不是折叠方向）
            assertEquals(1, DialectVerifySupport.exec(conn, "SELECT COUNT(*) FROM \"fold_lower\""))
            assertEquals(1, DialectVerifySupport.exec(conn, "SELECT COUNT(*) FROM \"FOLD_UPPER\""))

            // 读数 A / B：裸写访问（引擎折叠一次）
            val bareLowerHits = runCatching { DialectVerifySupport.exec(conn, "SELECT COUNT(*) FROM fold_lower") }
                .isSuccess
            val bareUpperHits = runCatching { DialectVerifySupport.exec(conn, "SELECT COUNT(*) FROM FOLD_UPPER") }
                .isSuccess
            val readings = "A 裸写小写名命中=$bareLowerHits / B 裸写大写名命中=$bareUpperHits"

            val measured = when {
                !bareLowerHits && bareUpperHits -> IdentifierFolding.UPPER
                bareLowerHits && !bareUpperHits -> IdentifierFolding.LOWER
                bareLowerHits && bareUpperHits -> IdentifierFolding.NONE
                else -> null // 两个裸写都 miss：引号大小写敏感且不折叠 → 按名判定无意义（NONE）
            }
            assertEquals("H2 引擎实测折叠方向（$readings）", IdentifierFolding.UPPER, measured)
            // k2j 适配：H2Dialect 为 Java 类，经 INSTANCE 访问
            assertEquals("族声明须与引擎实测一致（$readings）", measured, H2Dialect.INSTANCE.identifierFolding)

            // 决定性对照：插件对「字典里的真实小写名」（CATALOG 来源）渲染出的引用形态，
            // 必须在 H2 上真实命中该对象——NONE 族会裸写 → 被折叠为大写 → 表不存在。
            val catalogRef = H2Dialect.INSTANCE.quoteIfNeeded("fold_lower", IdentifierCaseSource.CATALOG)
            assertEquals("目录来源含小写 → 必须加引号（UPPER 族）", "\"fold_lower\"", catalogRef)
            assertTrue(
                "插件渲染的目录来源引用 $catalogRef 在 H2 上须可执行（$readings）",
                runCatching { DialectVerifySupport.exec(conn, "SELECT COUNT(*) FROM $catalogRef") }.isSuccess,
            )
        }
    }
}
