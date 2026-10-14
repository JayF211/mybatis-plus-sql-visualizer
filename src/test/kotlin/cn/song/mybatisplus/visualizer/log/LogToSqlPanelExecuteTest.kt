package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.ui.DegradationBanner
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import java.awt.Component
import java.awt.Container
import javax.swing.JButton

/**
 * T-PreviewUx-Pack #2/#4：日志转 SQL 面板——诚实降级横幅显隐 + 多语句执行拆分。
 *
 *  - 横幅三要素文案（未识别到条目 / 还原不完整）与恢复清除；
 *  - splitSqlStatements 语句数边界（0/1/多条、字符串内分号不切、注释内分号不切、无分号残余容错）；
 *  - 候选摘要模型（`SQL #N · 关键字`）。
 */
class LogToSqlPanelExecuteTest : BasePlatformTestCase() {

    private lateinit var panel: LogToSqlPanel

    override fun setUp() {
        super.setUp()
        panel = LogToSqlPanel(project)
    }

    override fun tearDown() {
        panel.dispose()
        super.tearDown()
    }

    private fun banner(): DegradationBanner = panel.degradationBannerForTest()

    private fun descendants(root: Component): List<Component> =
        listOf(root) + (root as? Container)?.components.orEmpty().flatMap(::descendants)

    fun testExecutionErrorHasReachableFullCopyAndClearsOnConversion() {
        // Directly drive the panel's execution-error sink, not a synthetic standalone result model.
        // Reflection keeps this test red at runtime on the old panel without adding a production test-only API.
        val onError = panel.javaClass.getDeclaredMethod("showExecutionError", String::class.java)
            .apply { isAccessible = true }
        val fullError = "Syntax error near <column> & value\nDriver detail: line 7"
        onError.invoke(panel, fullError)
        assertEquals(fullError, panel.statusForTest())

        val copyButtons = descendants(panel).filterIsInstance<JButton>().filter {
            it.text == MpSqlVisualizerBundle.message("panel.copyError") && it.isVisible
        }
        assertEquals("执行失败时复制完整错误按钮必须在真实面板组件树中可见", 1, copyButtons.size)
        assertFalse("日志没有 mapper 源定位锚点，不得虚构定位按钮", descendants(panel)
            .filterIsInstance<JButton>().any { it.isVisible && it.text == MpSqlVisualizerBundle.message("execute.error.locate") })
        UiTextClipboard.copy("clipboard sentinel")
        copyButtons.single().doClick()
        assertEquals("按钮须复制未截断的原始错误，不是状态行显示摘录", fullError, UiTextClipboard.readText())

        panel.setInputForTest("Preparing: SELECT 1")
        panel.convertForTest()
        assertFalse("下次转换成功后不留陈旧错误动作", copyButtons.single().isVisible)
    }

    // ---------- #2：诚实降级横幅 ----------

    fun test横幅_未识别到条目时显示三要素文案() {
        panel.setInputForTest("hello world 业务日志")
        panel.convertForTest()
        assertTrue("未识别到条目 → 横幅应显示", banner().isVisible)
        assertEquals(
            MpSqlVisualizerBundle.message("logToSql.banner.noEntry"),
            banner().textForTest(),
        )
    }

    fun test横幅_参数缺失时显示还原不完整文案() {
        panel.setInputForTest(
            "==>  Preparing: SELECT * FROM t WHERE a = ? AND b = ?\n" +
                "==> Parameters: 1(Long)",
        )
        panel.convertForTest()
        assertTrue("有未回填占位符 → 横幅应显示", banner().isVisible)
        assertTrue(
            banner().textForTest(),
            banner().textForTest() == MpSqlVisualizerBundle.message("logToSql.banner.unresolved", 1),
        )
    }

    fun test横幅_完整还原后清除() {
        panel.setInputForTest(
            "==>  Preparing: SELECT * FROM t WHERE a = ?\n" +
                "==> Parameters: 1(Long)",
        )
        panel.convertForTest()
        assertFalse("全部参数回填 → 横幅应隐藏", banner().isVisible)
    }

    // ---------- #4：多语句执行确认（拆分纯函数 + 候选模型） ----------

    fun test拆分_单条与多条() {
        assertEquals(0, LogToSqlPanel.splitSqlStatements("").size)
        assertEquals(0, LogToSqlPanel.splitSqlStatements("   ;  ; ").size)
        assertEquals(
            listOf("SELECT 1"),
            LogToSqlPanel.splitSqlStatements("SELECT 1;"),
        )
        // 组间注释随所属语句保留（原生控制台接受注释）
        assertEquals(
            listOf("-- SQL #1\nSELECT 1", "-- SQL #2\nUPDATE t SET a = 1 WHERE id = 1"),
            LogToSqlPanel.splitSqlStatements("-- SQL #1\nSELECT 1;\n-- SQL #2\nUPDATE t SET a = 1 WHERE id = 1;"),
        )
    }

    fun test拆分_字符串内分号不切分() {
        val parts = LogToSqlPanel.splitSqlStatements("UPDATE t SET name = 'a;b' WHERE id = 1;")
        assertEquals(1, parts.size)
        assertTrue(parts[0], parts[0].contains("'a;b'"))
    }

    fun test拆分_DELIMITER脚本整段一条不碎句() {
        // T-DA-03 消费面①（O-39）：多语句勾选浮层的输入即本拆分结果——改前 `DELIMITER $$`
        // 与 `SELECT 1$$` 各占一条候选（4 条），现过程体整段一条 + 复位后普通语句一条
        val parts = LogToSqlPanel.splitSqlStatements(
            "DELIMITER $$\nCREATE PROCEDURE p()\nBEGIN\n  SELECT 1;\n  SELECT 2;\nEND$$\n" +
                "DELIMITER ;\nSELECT 3;",
        )
        assertEquals("浮层候选两条（不碎句）", 2, parts.size)
        assertTrue("过程体整段保留", parts[0].startsWith("CREATE PROCEDURE p()"))
        assertTrue("过程体内部 `;` 保留", parts[0].contains("SELECT 1;"))
        assertFalse("`$$` 不残留", parts[0].contains("$$"))
        assertFalse("指令行不成候选", parts[0].uppercase().startsWith("DELIMITER"))
        assertEquals("SELECT 3", parts[1])
    }

    fun test拆分_注释内分号不切分() {
        val parts = LogToSqlPanel.splitSqlStatements(
            "-- 备注; 含分号\nSELECT 1;\n/* 块注释; 含分号 */\nSELECT 2;",
        )
        assertEquals(2, parts.size)
        assertTrue(parts[0], parts[0].contains("备注; 含分号"))
        assertTrue(parts[1], parts[1].contains("块注释; 含分号"))
    }

    fun test拆分_无分号残余段容错() {
        assertEquals(
            listOf("SELECT 1"),
            LogToSqlPanel.splitSqlStatements("SELECT 1"),
        )
        assertEquals(
            listOf("SELECT 1", "SELECT 2"),
            LogToSqlPanel.splitSqlStatements("SELECT 1; SELECT 2"),
        )
    }

    fun test候选摘要模型() {
        val candidate = LogToSqlPanel.StatementCandidate(2, "SELECT", "SELECT 1")
        assertEquals(
            MpSqlVisualizerBundle.message("logToSql.status.group", 2) + " · SELECT",
            candidate.summary(),
        )
    }
}
