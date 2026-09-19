package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.execute.ExecuteConfirmMessages
import cn.song.mybatisplus.visualizer.execute.UnboundedWriteDetector
import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * T-ST-03：预览面板回滚注册链的**解析复用前提**守卫（普通 JUnit，零 IDE fixture）。
 *
 * 背景：T-PS5-02 的收益（一次大 DML 点击的最坏 4 次解析降到 1 次）由
 * `UnboundedWriteDetector.parseStatementsShared` 的 AST 单槽承载，面板**零改动**即命中——
 * 但面板的调用形状与既有用例（`ConfirmRollbackParseShareTest` 走 `detect(sql)` → `generate(sql)`
 * 的裸文本路径）**不同**：面板在 `SqlPreviewPanel.registerRollbackSnapshotIfNeeded` 里传入的是
 * `SqlCommentStripper.strip(sql)` **已剥注释的文本**，而 `generate` 内部会再剥一次。
 * 槽键命中因此**额外依赖 `strip` 幂等**（`strip(strip(sql)) == strip(sql)`）——本组用例把这条
 * 隐式前提显式钉住：
 *  1. `strip` 幂等（面板双重剥注释仍落到确认阶段同一槽键）；
 *  2. 面板一次点击的完整形状（确认阶段 `ExecuteConfirmMessages.build` → `detect`，再
 *     `generate(strip(sql))`）只解析一次；
 *  3. 该形状产出的回滚结果与「无槽独立解析」逐字节一致；
 *  4. 含注释文本（行注释/块注释/行内注释/字面量内 `--`）同样只解析一次且结果一致。
 *
 * 前提失效的后果是**多一次解析**（槽未命中即重解析），不影响正确性——这正是 T-ST-03 判定
 * 「显式传参改动面大于收益」时的实测依据（判定与留档见开发计划 T-ST-03 节）。
 */
class SqlPreviewPanelRollbackParseShapeTest {

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

    /** 注释形态覆盖：行注释/块注释/行内/行首尾/字面量内 `--`（不得被当注释）/未闭合块注释。 */
    private val commentCases = listOf(
        "UPDATE t SET a = 1 WHERE id = 2 -- 行尾注释",
        "-- 整行注释\nUPDATE t SET a = 1 WHERE id = 2",
        "/* 块注释 */\nUPDATE t SET a = 1 WHERE id = 2\n/* 尾块 */",
        "UPDATE t SET a = 1 /* 行内 */ WHERE id = 2",
        "UPDATE t SET a = '-- 不是注释' WHERE id = 2",
        "DELETE FROM t WHERE name = '/* 也不是 */'",
        "INSERT INTO t (id, note) VALUES (1, 'x -- y')\n-- 尾行\n",
    )

    @Test
    fun `strip幂等_面板双重剥注释仍落到同一槽键`() {
        for (sql in commentCases) {
            val once = SqlCommentStripper.strip(sql)
            val twice = SqlCommentStripper.strip(once)
            assertEquals("strip 必须幂等（面板传入 strip(sql)，generate 内部再剥一次）：$sql", once, twice)
        }
    }

    @Test
    fun `面板一次点击_确认流与回滚注册链只解析一次`() {
        val sql = "UPDATE user SET name = 'x' WHERE id = 1"
        // 确认阶段：SqlPreviewPanel.executeSql(:944) / executeAdhocSql(:778) 的确认文案构建
        ExecuteConfirmMessages.build(sql, "UPDATE")
        assertEquals("确认阶段解析一次", 1, parseCount)
        // 回滚注册链：SqlPreviewPanel.registerRollbackSnapshotIfNeeded(:848) 的调用形状
        val rollback = RollbackSqlGenerator.generate(SqlCommentStripper.strip(sql))
        assertEquals("回滚注册链复用确认阶段 AST，不新增解析", 1, parseCount)
        assertEquals("语义不变：UPDATE 无 before-image 诚实拒绝", RollbackSqlGenerator.Rating.UNABLE, rollback.rating)
    }

    @Test
    fun `面板形状_共享槽产物与无槽独立解析逐字节一致`() {
        val cases = commentCases + listOf(
            "INSERT INTO user (id, name, age) VALUES (1, 'Tom', 20)",
            "INSERT INTO t (id, name) VALUES (1, 'a'); DELETE FROM t WHERE id = 9",
            "MERGE INTO t USING s ON (t.id = s.id) WHEN MATCHED THEN UPDATE SET t.a = s.a",
        )
        for (sql in cases) {
            UnboundedWriteDetector.clearAstSlotForTest()
            val independent = RollbackSqlGenerator.generate(sql) // 无槽：独立解析路径
            UnboundedWriteDetector.clearAstSlotForTest()
            ExecuteConfirmMessages.build(sql, "UPDATE") // 确认阶段填槽
            val shared = RollbackSqlGenerator.generate(SqlCommentStripper.strip(sql)) // 面板形状命中槽
            assertEquals("面板形状产出必须与独立解析全等：${sql.take(40)}…", independent, shared)
        }
    }

    /** 探针自证：前提失效（槽被挤掉）时计数必然增加——证明上面的「只解析一次」不是空转断言。 */
    @Test
    fun `探针自证_槽未命中必然重新解析`() {
        val sql = "UPDATE user SET name = 'x' WHERE id = 1"
        ExecuteConfirmMessages.build(sql, "UPDATE")
        assertEquals(1, parseCount)
        UnboundedWriteDetector.clearAstSlotForTest() // 模拟前提失效（单槽被别的文本挤掉）
        RollbackSqlGenerator.generate(SqlCommentStripper.strip(sql))
        assertEquals("槽未命中即重解析——计数探针承重", 2, parseCount)
    }

    @Test
    fun `含注释文本_面板一次点击仍只解析一次`() {
        for (sql in commentCases) {
            parseCount = 0
            UnboundedWriteDetector.clearAstSlotForTest()
            ExecuteConfirmMessages.build(sql, "UPDATE")
            val afterConfirm = parseCount
            RollbackSqlGenerator.generate(SqlCommentStripper.strip(sql))
            assertEquals("含注释文本：确认阶段 + 回滚注册链共一次解析（确认阶段 $afterConfirm 次）：$sql", 1, parseCount)
        }
    }
}
