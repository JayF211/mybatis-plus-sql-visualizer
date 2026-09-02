package cn.song.mybatisplus.visualizer.sql.verify

import cn.song.mybatisplus.visualizer.sql.SqlKeywords.SQLITE_RESERVED
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SQLite 方言真实验证（P-立项-05 Tier 1+：sqlite-jdbc 内嵌**原生 SQLite 引擎**，100% 真实证据）。
 *
 * 覆盖（对齐开发计划 TC-01~08）：
 *  - 分页 `LIMIT count OFFSET offset`（SQLite 原生；`OFFSET..FETCH` SQLite 不支持——插件不输出 ✓）
 *  - 双引号标识符引用（保留字列 order/table）
 *  - UPSERT `ON CONFLICT (id) DO UPDATE SET col = EXCLUDED.col`（SQLite 3.24+ 原生，冲突+插入双路径）
 *  - IN 子句 / 聚合 / UPDATE / DELETE / INSERT 模板
 *  - 保留字全量扫描（SQLITE_RESERVED 官方关键字全量逐词 → 插件渲染 → 真实引擎执行）
 *  - 引擎保留字覆盖探测（漏包检测：引擎拒绝裸用的词必须 ∈ SQLITE_RESERVED）
 */
class DialectSqliteVerificationTest {

    @Test
    fun `TC01-08_SQLite方言_原生引擎真实执行`() {
        DialectVerifySupport.open(DialectVerifySupport.sqliteEngine, "unused").use { conn ->
            DialectVerifySupport.runTcSuite(conn, DialectVerifySupport.sqliteEngine)
        }
    }

    @Test
    fun `保留字全量扫描_SQLite方言_原生引擎`() {
        DialectVerifySupport.open(DialectVerifySupport.sqliteEngine, "unused").use { conn ->
            val failures = DialectVerifySupport.sweepReservedWords(
                conn,
                DialectVerifySupport.sqliteEngine,
                SQLITE_RESERVED,
            )
            assertTrue(
                "SQLite 方言保留字扫描失败 ${failures.size}/${SQLITE_RESERVED.size} 个词:\n" +
                    failures.joinToString("\n"),
                failures.isEmpty(),
            )
        }
    }

    @Test
    fun `引擎保留字覆盖_SQLite方言_漏包检测`() {
        DialectVerifySupport.open(DialectVerifySupport.sqliteEngine, "unused").use { conn ->
            val rejected = DialectVerifySupport.probeUnquotedRejected(conn, sqliteCoverageCandidates)
            val missing = rejected.filter { it !in SQLITE_RESERVED }
            assertTrue(
                "SQLite 引擎拒绝裸用（裸用即语法错误）但 SQLITE_RESERVED 缺失的词: $missing",
                missing.isEmpty(),
            )
        }
    }

    /** SQLite 覆盖候选词（官方关键字全量 + 常见业务列名）。 */
    private val sqliteCoverageCandidates: Set<String> = buildSet {
        addAll(SQLITE_RESERVED)
        addAll(
            listOf(
                "name", "status", "type", "value", "key", "comment", "desc", "number",
                "date", "level", "size", "mode", "user", "content", "code", "title",
                "sort", "state", "result", "data", "domain", "section", "password",
            ),
        )
    }
}
