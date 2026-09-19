package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.ui.DegradationBanner
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

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
