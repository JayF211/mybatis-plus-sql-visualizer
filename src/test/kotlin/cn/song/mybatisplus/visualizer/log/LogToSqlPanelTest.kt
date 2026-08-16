package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * 「日志转 SQL」tab 面板行为测试（T-M38）。
 *
 * 转换为 EDT 同步纯函数（文本解析，无 PSI/实体查找异步路径）——直测无需轮询；
 * 状态文案断言用 bundle key 解析（语言无关，防中英文环境差异）。
 */
class LogToSqlPanelTest : BasePlatformTestCase() {

    private lateinit var panel: LogToSqlPanel

    override fun setUp() {
        super.setUp()
        panel = LogToSqlPanel(project)
    }

    override fun tearDown() {
        panel.dispose()
        super.tearDown()
    }

    fun testConvertSingleStatementReplacesParams() {
        panel.setInputForTest(
            "==>  Preparing: SELECT * FROM user WHERE id = ? AND name = ?\n" +
                "==> Parameters: 1(Long), 张三(String)",
        )
        panel.convertForTest()
        val result = panel.resultTextForTest()
        assertTrue(result, result.contains("id = 1"))
        assertTrue(result, result.contains("name = '张三'"))
        assertEquals(
            MpSqlVisualizerBundle.message("logToSql.status.converted", 1),
            panel.statusForTest(),
        )
    }

    fun testConvertNullParamRendersNull() {
        panel.setInputForTest("Preparing: SELECT * FROM t WHERE a = ?\nParameters: null")
        panel.convertForTest()
        val result = panel.resultTextForTest()
        assertTrue(result, result.contains("a = NULL"))
    }

    fun testConvertMultipleStatementsGrouped() {
        panel.setInputForTest(
            "==>  Preparing: SELECT * FROM a WHERE id = ?\n" +
                "==> Parameters: 1(Long)\n" +
                "==>  Preparing: UPDATE b SET x = ? WHERE id = ?\n" +
                "==> Parameters: 2(String), 3(Long)",
        )
        panel.convertForTest()
        val result = panel.resultTextForTest()
        assertTrue(result, result.contains("-- " + MpSqlVisualizerBundle.message("logToSql.status.group", 1)))
        assertTrue(result, result.contains("-- " + MpSqlVisualizerBundle.message("logToSql.status.group", 2)))
        assertTrue(result, result.contains("id = 1"))
        assertTrue(result, result.contains("x = '2'"))
        assertEquals(
            MpSqlVisualizerBundle.message("logToSql.status.converted", 2),
            panel.statusForTest(),
        )
    }

    fun testConvertNoLogLinesShowsError() {
        panel.setInputForTest("hello world 业务日志")
        panel.convertForTest()
        assertEquals("", panel.resultTextForTest())
        assertEquals(MpSqlVisualizerBundle.message("logToSql.panel.noEntry"), panel.statusForTest())
    }

    fun testConvertBlankInputShowsError() {
        panel.setInputForTest("")
        panel.convertForTest()
        assertEquals("", panel.resultTextForTest())
        assertEquals(MpSqlVisualizerBundle.message("logToSql.panel.empty"), panel.statusForTest())
    }

    fun testConvertMissingParamsKeepsPlaceholderAndWarns() {
        panel.setInputForTest(
            "==>  Preparing: SELECT * FROM t WHERE a = ? AND b = ?\n" +
                "==> Parameters: 1(Long)",
        )
        panel.convertForTest()
        val result = panel.resultTextForTest()
        assertTrue(result, result.contains("a = 1"))
        assertTrue(result, result.contains("b = ?"))
        assertEquals(
            MpSqlVisualizerBundle.message("logToSql.status.unresolved", 1),
            panel.statusForTest(),
        )
    }

    fun testDefaultMultiLineAndToggleToSingleLine() {
        panel.setInputForTest("==>  Preparing: SELECT * FROM user WHERE id = ?\n==> Parameters: 1(Long)")
        panel.convertForTest()
        val multi = panel.resultTextForTest()
        assertTrue("默认多行格式化", multi.contains("\n"))
        assertTrue(multi, multi.contains("id = 1"))
        // 切换单行 → 重转
        panel.toggleFormatForTest()
        panel.convertForTest()
        val single = panel.resultTextForTest()
        assertTrue("单行形态", !single.contains("\n"))
        assertTrue(single, single.contains("SELECT * FROM user WHERE id = 1;"))
        // 切回多行
        panel.toggleFormatForTest()
        panel.convertForTest()
        assertTrue(panel.resultTextForTest().contains("\n"))
    }

    fun testLoadFromEntryRoundTrip() {
        val entry = LogSqlEntry(
            rawSql = "SELECT * FROM t WHERE id = ? AND name = ?",
            restoredSql = "SELECT * FROM t WHERE id = 1 AND name = '张三'",
            params = listOf(LogSqlParam("1", "Long"), LogSqlParam("张三", "String")),
        )
        panel.loadFromEntryForTest(entry)
        val result = panel.resultTextForTest()
        assertTrue(result, result.contains("id = 1"))
        assertTrue(result, result.contains("name = '张三'"))
        assertEquals(
            MpSqlVisualizerBundle.message("logToSql.status.converted", 1),
            panel.statusForTest(),
        )
    }

    fun testPanelDisposeReleasesEditors() {
        panel.dispose()
        panel.dispose()
    }

    fun testBundleKeysPresent() {
        assertTrue(MpSqlVisualizerBundle.message("log.tab.logToSql").isNotBlank())
        assertTrue(MpSqlVisualizerBundle.message("logToSql.panel.convert").isNotBlank())
        assertTrue(MpSqlVisualizerBundle.message("logToSql.panel.copy").isNotBlank())
        assertTrue(MpSqlVisualizerBundle.message("logToSql.panel.toggleFormat").isNotBlank())
        assertTrue(MpSqlVisualizerBundle.message("logToSql.sendTo").isNotBlank())
    }
}
