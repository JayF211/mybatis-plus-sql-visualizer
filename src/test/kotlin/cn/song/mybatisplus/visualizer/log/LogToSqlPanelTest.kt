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

    // ---------- Hibernate 日志格式支持 ----------

    fun testConvertHibernateFormatWithParams() {
        panel.setInputForTest(
            "Hibernate: select user0_.id as id1_0_0_, user0_.name as name2_0_0_ from users user0_ where user0_.id=?\n" +
                "binding parameter [1] as [BIGINT] - [1]",
        )
        panel.convertForTest()
        val result = panel.resultTextForTest()
        // 默认多行格式化（= 两侧有空格）
        assertTrue(result, result.contains("user0_.id = 1"))
        assertEquals(
            MpSqlVisualizerBundle.message("logToSql.status.converted", 1),
            panel.statusForTest(),
        )
    }

    fun testConvertHibernateFormatNullParam() {
        panel.setInputForTest(
            "Hibernate: select * from users where email=?\n" +
                "binding parameter [1] as [VARCHAR] - [null]",
        )
        panel.convertForTest()
        val result = panel.resultTextForTest()
        assertTrue(result, result.contains("email = NULL"))
    }

    fun testConvertHibernateLogFormattedToMultiline() {
        // 验证 Hibernate 日志转换后 SQL 格式化为多行（面板需正确渲染多行输出）
        panel.setInputForTest(
            "Hibernate: select user0_.id as id1_0_0_, user0_.name as name2_0_0_, user0_.email as email3_0_0_ from users user0_ where user0_.id=? and user0_.name=?\n" +
                "binding parameter [1] as [BIGINT] - [42]\n" +
                "binding parameter [2] as [VARCHAR] - [Alice]",
        )
        panel.convertForTest()
        val result = panel.resultTextForTest()
        // SqlFormatter 格式化为多行输出
        assertTrue("应格式化为多行", result.contains("\n"))
        assertTrue(result, result.contains("user0_.id = 42"))
        assertTrue(result, result.contains("user0_.name = 'Alice'"))
        assertEquals(
            MpSqlVisualizerBundle.message("logToSql.status.converted", 1),
            panel.statusForTest(),
        )
    }

    fun testConvertHibernateCommentPrefix() {
        panel.setInputForTest(
            "/* load com.example.User */ select user0_.id from users user0_ where user0_.name=?\n" +
                "binding parameter [1] as [VARCHAR] - [Alice]",
        )
        panel.convertForTest()
        val result = panel.resultTextForTest()
        assertTrue(result, result.contains("name = 'Alice'"))
    }

    // ---------- JdbcTemplate 日志格式支持（M47） ----------

    fun testConvertJdbcTemplateFormatWithParams() {
        panel.setInputForTest(
            "Executing prepared SQL statement [SELECT * FROM users WHERE id = ? AND name = ?]\n" +
                "Setting SQL statement parameter value: column index 1, parameter value [42], value class [java.lang.Integer], SQL type unknown\n" +
                "Setting SQL statement parameter value: column index 2, parameter value [Alice], value class [java.lang.String], SQL type unknown",
        )
        panel.convertForTest()
        val result = panel.resultTextForTest()
        assertTrue(result, result.contains("id = 42"))
        assertTrue(result, result.contains("name = 'Alice'"))
        assertEquals(
            MpSqlVisualizerBundle.message("logToSql.status.converted", 1),
            panel.statusForTest(),
        )
    }

    fun testConvertJdbcTemplateNullParam() {
        panel.setInputForTest(
            "Executing prepared SQL statement [SELECT * FROM users WHERE deleted = ?]\n" +
                "Setting SQL statement parameter value: column index 1, parameter value [null], value class [null], SQL type unknown",
        )
        panel.convertForTest()
        val result = panel.resultTextForTest()
        assertTrue(result, result.contains("deleted = NULL"))
    }

    fun testConvertMixedMyBatisAndHibernate() {
        panel.setInputForTest(
            "==>  Preparing: SELECT * FROM config WHERE key = ?\n" +
                "==> Parameters: timeout(Integer)\n" +
                "Hibernate: select user0_.id from users user0_ where user0_.id=?\n" +
                "binding parameter [1] as [BIGINT] - [42]",
        )
        panel.convertForTest()
        val result = panel.resultTextForTest()
        assertTrue(result, result.contains("KEY = timeout"))
        assertTrue(result, result.contains("user0_.id = 42"))
        assertEquals(
            MpSqlVisualizerBundle.message("logToSql.status.converted", 2),
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

    fun testHintLabelsShowWhenEmptyAndHideOnContent() {
        // 空文档 → 提示可见；输入日志后 → 隐藏（结果区同理）
        assertTrue("初始输入区空 → 提示应可见", panel.inputHintLabelForTest().isVisible)
        assertTrue("初始结果区空 → 提示应可见", panel.resultHintLabelForTest().isVisible)
        panel.setInputForTest("==>  Preparing: SELECT * FROM user WHERE id = ?")
        assertTrue("输入日志后输入区提示应隐藏", !panel.inputHintLabelForTest().isVisible)
        panel.setInputForTest("")
        assertTrue("清空输入 → 提示恢复可见", panel.inputHintLabelForTest().isVisible)
    }
}
