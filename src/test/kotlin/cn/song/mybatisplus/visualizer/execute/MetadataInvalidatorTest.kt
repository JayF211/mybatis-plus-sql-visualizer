package cn.song.mybatisplus.visualizer.execute

import net.sf.jsqlparser.parser.CCJSqlParserUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 元数据失效器测试（T-DdlCacheInvalidate / T-DC-02，表驱动）：
 * DDL 正例/反例、提交关键字、解析失败 fail closed、多语句任一命中、失效编排（登记动作
 * 命中/不命中/异常不吞）。普通 JUnit——识别器仅 JSqlParser + SqlCommentStripper。
 *
 * T-PS5-01 追加：解析单槽缓存（同文本两次判定只解析一次）与批脚本廉价预筛（否定式排除
 * 与全解析判定逐例等价、排除路径零解析）。
 */
class MetadataInvalidatorTest {

    /** 解析计数探针（替换生产解析器计数，行为等价）。 */
    private var parseCount = 0

    @Before
    fun setUp() {
        parseCount = 0
        MetadataInvalidator.clearParseCacheForTest()
        MetadataInvalidator.batchPrefilterStatements = 50
        MetadataInvalidator.parseStatementsForTest = { s ->
            parseCount++
            CCJSqlParserUtil.parseStatements(s)
        }
    }

    @After
    fun tearDown() {
        MetadataInvalidator.parseStatementsForTest = { s -> SqlAstParser.parseStatements(s) }
        MetadataInvalidator.batchPrefilterStatements = 50
        MetadataInvalidator.clearParseCacheForTest()
    }

    private fun mayChange(sql: String) = MetadataInvalidator.mayChangeMetadata(sql)

    // ---------- DDL 正例 ----------

    @Test
    fun `DDL正例_建删改表索引视图`() {
        assertTrue(mayChange("CREATE TABLE t (id INT)"))
        assertTrue(mayChange("ALTER TABLE t ADD COLUMN c INT"))
        assertTrue(mayChange("DROP TABLE t"))
        assertTrue(mayChange("TRUNCATE TABLE t"))
        assertTrue(mayChange("CREATE INDEX idx ON t(c)"))
        assertTrue(mayChange("DROP VIEW v"))
        assertTrue(mayChange("RENAME TABLE t TO t2"))
        assertTrue(mayChange("CREATE OR REPLACE VIEW v AS SELECT 1"))
    }

    @Test
    fun `小写DDL同样命中`() {
        assertTrue(mayChange("alter table t add column c int"))
        assertTrue(mayChange("drop table if exists t"))
    }

    // ---------- 提交关键字（DBX object_cache.rs:41 对齐） ----------

    @Test
    fun `提交关键字命中`() {
        assertTrue(mayChange("COMMIT"))
        assertTrue(mayChange("commit work"))
        assertTrue(mayChange("ROLLBACK"))
        assertTrue("END（PL/SQL 块尾）解析失败或泛型形态 → 关键字路兜底", mayChange("END"))
        assertTrue(mayChange("ABORT"))
    }

    // ---------- 反例（DML/DQL 不改元数据） ----------

    @Test
    fun `DML与查询不命中`() {
        assertFalse(mayChange("SELECT * FROM t"))
        assertFalse(mayChange("INSERT INTO t (a) VALUES (1)"))
        assertFalse(mayChange("UPDATE t SET a = 1 WHERE id = 1"))
        assertFalse(mayChange("DELETE FROM t WHERE id = 1"))
        assertFalse(mayChange("SELECT 'DROP TABLE' AS s FROM t")) // 字符串内关键字不误伤
    }

    @Test
    fun `注释内DDL不误伤`() {
        assertFalse(mayChange("SELECT 1 -- DROP TABLE t"))
        assertFalse(mayChange("/* ALTER TABLE t */ SELECT 1"))
    }

    // ---------- 解析失败 fail closed ----------

    @Test
    fun `解析失败按可能改变处理`() {
        assertTrue("残缺语句 fail closed", mayChange("CREATE TABLE"))
        assertTrue("方言形态解析不出 fail closed", mayChange("BEGIN UPDATE t SET a=1; END"))
    }

    // ---------- 多语句任一命中 ----------

    @Test
    fun `多语句任一DDL命中即失效`() {
        assertTrue(mayChange("SELECT 1; ALTER TABLE t ADD COLUMN c INT"))
        assertTrue(mayChange("INSERT INTO t (a) VALUES (1); COMMIT"))
        assertFalse(mayChange("SELECT 1; INSERT INTO t (a) VALUES (2); DELETE FROM t WHERE id = 1"))
    }

    @Test
    fun `空与空白不命中`() {
        assertFalse(mayChange(""))
        assertFalse(mayChange("   "))
        assertFalse(mayChange("-- 只有注释"))
    }

    // ---------- 失效编排（登记动作/异常不吞） ----------

    @Test
    fun `失效编排_DDL触发登记动作`() {
        var invoked = false
        MetadataInvalidator.register("test.probe") { invoked = true }
        try {
            MetadataInvalidator.invalidateAfterExecution("ALTER TABLE t ADD COLUMN c INT")
            assertTrue("DDL 执行 → 登记动作被调用", invoked)
            MetadataInvalidator.invalidateAfterExecution("SELECT 1")
            // SELECT 不触发（invoked 已 true 无法区分——再置 false 验证）
            invoked = false
            MetadataInvalidator.invalidateAfterExecution("SELECT * FROM t WHERE a = 1")
            assertFalse("非 DDL 不触发失效动作", invoked)
        } finally {
            MetadataInvalidator.unregister("test.probe")
        }
    }

    @Test
    fun `失效编排_动作异常不外抛`() {
        MetadataInvalidator.register("test.throwing") { throw IllegalStateException("boom") }
        try {
            // 异常动作被吞（只记日志），不影响执行链也不影响后续动作
            var second = false
            MetadataInvalidator.register("test.second") { second = true }
            MetadataInvalidator.invalidateAfterExecution("DROP TABLE t")
            assertTrue("前序动作异常不阻断后续动作", second)
        } finally {
            MetadataInvalidator.unregister("test.throwing")
            MetadataInvalidator.unregister("test.second")
        }
    }

    @Test
    fun `内置登记_DatabaseSchemaResolver失效入口在册`() {
        // 内置登记不抛异常（Database 插件反射环境缺失时 invalidateAll 仍可安全执行）
        MetadataInvalidator.invalidateAfterExecution("CREATE TABLE probe (id INT)")
    }

    @Test
    fun `收尾兜底_入口收尾双调幂等_计数不翻倍`() {
        // T-DdlPostInvalidate（O-17）：同一语句入口+收尾双调（收尾含失败路径），幂等语义下
        // 每次调用都失效一次（引用清空语义无害），预判未命中则零动作——这里断言
        // 「收尾再调」不抛、动作可重复执行且非 DDL 零动作（对齐 runPostExecutionInvalidate 接线）。
        var calls = 0
        MetadataInvalidator.register("test.posthook") { calls++ }
        try {
            MetadataInvalidator.invalidateAfterExecution("CREATE TABLE t (id INT)") // 入口
            MetadataInvalidator.invalidateAfterExecution("CREATE TABLE t (id INT)") // 收尾（成功路径）
            MetadataInvalidator.invalidateAfterExecution("CREATE TABLE t (id INT)") // 收尾（失败路径同款）
            assertEquals(3, calls)
            MetadataInvalidator.invalidateAfterExecution("SELECT 1") // 非 DDL 不动作
            assertEquals(3, calls)
        } finally {
            MetadataInvalidator.unregister("test.posthook")
        }
    }

    @Test
    fun `DatabaseSchemaResolver失效幂等安全`() {
        // invalidateAll 幂等且无副作用（无 database 插件环境=空缓存清理；headless 下
        // columnComments 经 isDatabaseAvailable 探测短路返回空 Map——真机走查覆盖真实链路）
        cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.invalidateAll()
        cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.invalidateAll()
    }

    // ---------- T-PS5-01：解析单槽缓存 ----------

    @Test
    fun `T-PS5-01_同文本两次判定只解析一次`() {
        // 执行链形态：入口与收尾各判一次，文本内容相同但实例不同（document.text 每次新串）
        val entry = String("ALTER TABLE t ADD COLUMN c INT".toCharArray())
        val post = String("ALTER TABLE t ADD COLUMN c INT".toCharArray())
        assertTrue("入口判定", mayChange(entry))
        assertEquals("入口解析一次", 1, parseCount)
        assertTrue("收尾判定", mayChange(post))
        assertEquals("同文本收尾复用缓存（解析计数不增）", 1, parseCount)
    }

    @Test
    fun `T-PS5-01_不同文本不误命中`() {
        assertFalse(mayChange("SELECT * FROM t"))
        assertEquals(1, parseCount)
        assertTrue("异文本重新解析", mayChange("ALTER TABLE u ADD COLUMN c INT"))
        assertEquals(2, parseCount)
        // 同长度、异内容（末字符不同）——长度快判不足以命中，必须重新解析
        assertFalse(mayChange("DELETE FROM t11"))
        assertEquals("同长度异内容重新解析", 3, parseCount)
        assertFalse(mayChange("SELECT * FROM u"))
        assertEquals(4, parseCount)
    }

    @Test
    fun `T-PS5-01_解析失败结果同样入缓存且fail-closed不回退`() {
        assertTrue("残缺语句 fail closed", mayChange("CREATE TABLE"))
        assertEquals(1, parseCount)
        assertTrue("同文本二次判定仍 true（缓存复用）", mayChange("CREATE TABLE"))
        assertEquals(1, parseCount)
    }

    @Test
    fun `T-PS5-01_失效动作不复用_入口收尾各失效一次`() {
        var calls = 0
        MetadataInvalidator.register("test.ps501") { calls++ }
        try {
            val sql = "DROP TABLE t"
            MetadataInvalidator.invalidateAfterExecution(sql)
            MetadataInvalidator.invalidateAfterExecution(sql)
            assertEquals("复用解析不等于复用失效——两次调用各失效一次", 2, calls)
            assertEquals("但解析只发生一次", 1, parseCount)
        } finally {
            MetadataInvalidator.unregister("test.ps501")
        }
    }

    // ---------- T-PS5-01：批脚本廉价预筛 ----------

    /** 大批脚本造数（n 条 SELECT，无任何 DDL/提交关键字）。 */
    private fun bigBatch(n: Int, tail: String = ""): String =
        (1..n).joinToString("; ") { "SELECT * FROM t$it WHERE id = $it" } + tail

    @Test
    fun `T-PS5-01_批脚本预筛排除路径零解析`() {
        val sql = bigBatch(60)
        assertFalse("无 DDL 关键字的大批脚本直接判 false", mayChange(sql))
        assertEquals("预筛排除路径不触达 AST 解析", 0, parseCount)
    }

    @Test
    fun `T-PS5-01_批脚本预筛与全解析判定逐例等价`() {
        val cases = listOf(
            bigBatch(60) to false, // 纯 DML 大批
            bigBatch(60, "; ALTER TABLE t ADD COLUMN c INT") to true, // 尾部 DDL
            bigBatch(60, "; COMMIT") to true, // 尾部提交关键字
            bigBatch(60) + "; CREATE INDEX idx ON t(c)" to true, // 超阈值 + 尾部 DDL
            (1..60).joinToString("; ") { "SELECT 'DROP TABLE' AS s FROM t$it" } to false, // 字面量命中关键字→AST 权威判非 DDL
            (1..60).joinToString("; ") { "SELECT 1 -- DROP TABLE t$it" } to false, // 注释内关键字被剥离
            (1..60).joinToString("; ") { "INSERT INTO t (a) VALUES ($it)" } to false,
        )
        for ((sql, expected) in cases) {
            MetadataInvalidator.clearParseCacheForTest()
            MetadataInvalidator.batchPrefilterStatements = 50 // 预筛开
            val prefiltered = mayChange(sql)
            MetadataInvalidator.clearParseCacheForTest()
            MetadataInvalidator.batchPrefilterStatements = Int.MAX_VALUE // 预筛关（等价于全解析路径）
            val fullParse = mayChange(sql)
            assertEquals("预筛判定与全解析判定等价：${sql.take(40)}…", fullParse, prefiltered)
            assertEquals("期望值：${sql.take(40)}…", expected, prefiltered)
        }
    }

    @Test
    fun `T-PS5-01_阈值内批脚本维持AST路径`() {
        val sql = bigBatch(10, "; ALTER TABLE t ADD COLUMN c INT")
        assertTrue(mayChange(sql))
        assertEquals("阈值内仍走 AST（预筛不介入）", 1, parseCount)
    }
}
