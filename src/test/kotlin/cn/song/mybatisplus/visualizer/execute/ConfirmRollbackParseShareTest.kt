package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-PS5-02 确认流/回滚链解析共享测试（普通 JUnit，零 IDE fixture）。
 *
 * 背景：一次大 DML 点击最坏 4 次 JSqlParser 解析、3 次在 EDT——确认流
 * （`ExecuteConfirmMessages.build` → [UnboundedWriteDetector.detect]）与回滚链
 * （[RollbackSqlGenerator.generate]）对**同一文本**各解析一次。本组用例锁定：
 *  1. 同文本经两条链只解析一次（解析计数探针）；
 *  2. 共享 AST 产出的回滚结果与「无槽独立解析」逐字节一致（data class 全等，含语句/WHERE/
 *     SET 值/快照模板/评级）；
 *  3. 携解析入参重载与文本路径逐字节一致；
 *  4. 解析失败不入槽（不污染后续判定）；
 *  5. 共享 AST 不因回滚链消费而改变探测结果（只读纪律兜底）。
 */
class ConfirmRollbackParseShareTest {

    private var parseCount = 0

    @Before
    fun setUp() {
        parseCount = 0
        UnboundedWriteDetector.clearAstSlotForTest()
        UnboundedWriteDetector.parseStatementsForTest = { s ->
            parseCount++
            CCJSqlParserUtil.parseStatements(s)
        }
    }

    @After
    fun tearDown() {
        UnboundedWriteDetector.parseStatementsForTest = { s -> CCJSqlParserUtil.parseStatements(s) }
        UnboundedWriteDetector.clearAstSlotForTest()
    }

    @Test
    fun `确认流与回滚链同文本只解析一次`() {
        val sql = "UPDATE user SET name = 'x' WHERE id = 1"
        UnboundedWriteDetector.detect(sql) // 确认流（ExecuteConfirmMessages.build 同路径）
        assertEquals("确认流解析一次", 1, parseCount)
        val rollback = RollbackSqlGenerator.generate(sql) // 回滚链（预览面板快照登记同路径）
        assertEquals("同文本回滚链复用确认阶段 AST", 1, parseCount)
        assertEquals("语义不变：UPDATE 无 before-image 诚实拒绝", RollbackSqlGenerator.Rating.UNABLE, rollback.rating)
    }

    @Test
    fun `同文本共享AST与独立解析产物逐字节一致`() {
        val cases = listOf(
            "INSERT INTO user (id, name, age) VALUES (1, 'Tom', 20)",
            "INSERT INTO t (a, b) VALUES (1, 'x'), (2, NULL)",
            "INSERT INTO t (id, name) VALUES (1, 'a'); DELETE FROM t WHERE id = 9",
            "UPDATE t SET a = 1, b = 'y' WHERE id = 2",
            "DELETE FROM t WHERE id = 3",
            "MERGE INTO t USING s ON (t.id = s.id) WHEN MATCHED THEN UPDATE SET t.a = s.a",
            "-- 注释行\nINSERT INTO t (id) VALUES (7)",
        )
        for (sql in cases) {
            UnboundedWriteDetector.clearAstSlotForTest()
            val independent = RollbackSqlGenerator.generate(sql) // 无槽：独立解析路径
            UnboundedWriteDetector.clearAstSlotForTest()
            UnboundedWriteDetector.detect(sql) // 确认流先解析并填槽
            val shared = RollbackSqlGenerator.generate(sql) // 命中共享槽
            assertEquals("共享 AST 与独立解析产物必须全等：${sql.take(40)}…", independent, shared)
            assertEquals(
                "语句文本逐字节一致：${sql.take(40)}…",
                independent.statements,
                shared.statements,
            )
        }
    }

    @Test
    fun `携解析入参重载与文本路径逐字节一致`() {
        val sql = "INSERT INTO user (id, name, age) VALUES (1, 'Tom', 20)"
        val cleaned = SqlCommentStripper.strip(sql)
        val parsed = CCJSqlParserUtil.parseStatements(cleaned)
        val viaOverload = RollbackSqlGenerator.generateFromParsedStatements(cleaned, parsed, listOf("id"))
        UnboundedWriteDetector.clearAstSlotForTest()
        val viaText = RollbackSqlGenerator.generate(sql, listOf("id"))
        assertEquals(viaText, viaOverload)
        assertEquals(RollbackSqlGenerator.Rating.COMPLETE, viaOverload.rating)
        assertEquals(listOf("DELETE FROM user WHERE id = 1;"), viaOverload.statements)
    }

    @Test
    fun `解析失败不入槽_后续判定不受污染`() {
        val broken = "INSERT INTO"
        assertEquals(
            "解析失败仍走 UNABLE 语义",
            RollbackSqlGenerator.Rating.UNABLE,
            RollbackSqlGenerator.generate(broken).rating,
        )
        // 槽未被污染：后续有效文本照常解析并得出正确结论
        val good = "UPDATE t SET a = 1"
        assertTrue(
            "失败文本不得占槽",
            UnboundedWriteDetector.detect(good).kinds().contains(UnboundedWriteDetector.FindingKind.MISSING_WHERE),
        )
        assertEquals(2, parseCount) // 失败一次 + 有效一次
    }

    @Test
    fun `共享AST不因回滚链消费而改变探测结果`() {
        val sql = "UPDATE t SET a = 1"
        val before = UnboundedWriteDetector.detect(sql).kinds()
        RollbackSqlGenerator.generate(sql) // 消费同一 AST 实例
        val viaSlot = UnboundedWriteDetector.detect(sql).kinds() // 槽命中（同一实例二次判定）
        UnboundedWriteDetector.clearAstSlotForTest()
        val fresh = UnboundedWriteDetector.detect(sql).kinds() // 重新解析
        assertEquals("槽命中与重新解析结论一致", fresh, viaSlot)
        assertEquals("回滚链消费未改写 AST", before, viaSlot)
    }
}
