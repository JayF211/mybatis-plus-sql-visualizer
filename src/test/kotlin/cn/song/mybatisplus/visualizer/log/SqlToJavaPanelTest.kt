package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * 「SQL → Java」tab 面板行为测试（T-M36.4）。
 *
 * Light fixture 下项目无实体类 → 实体查找返回空 → 回落字符串列名 QueryWrapper 路径可测；
 * 异步路径（AUTO 形态后台查找实体）用轮询等待（UIUtil.dispatchAllInvocationEvents +
 * 超时兜底）；QUERY 形态为同步路径（直测）。
 */
class SqlToJavaPanelTest : BasePlatformTestCase() {

    private lateinit var panel: SqlToJavaPanel

    override fun setUp() {
        super.setUp()
        panel = SqlToJavaPanel(project)
    }

    override fun tearDown() {
        panel.dispose()
        super.tearDown()
    }

    private fun awaitResult(): String {
        var result = panel.resultTextForTest()
        var waited = 0
        while (result.isBlank() && waited < 100) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(50)
            result = panel.resultTextForTest()
            waited++
        }
        return result
    }

    /** 等待结果区包含目标文本（区分新旧结果——切换形态重生成的异步路径）。 */
    private fun awaitContains(substring: String): String {
        var result = panel.resultTextForTest()
        var waited = 0
        while (!result.contains(substring) && waited < 100) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(50)
            result = panel.resultTextForTest()
            waited++
        }
        return result
    }

    fun testConvertNoEntityFallsBackToQueryWrapper() {
        panel.inputEditorForTest().setSql("SELECT id, name FROM user WHERE name = '张三'")
        panel.convertForTest()
        val result = awaitResult()
        assertTrue(result, result.contains("new QueryWrapper<User>()"))
        assertTrue(result, result.contains(".eq(\"name\", \"张三\")"))
        assertTrue(panel.statusForTest(), panel.statusForTest().contains("未找到对应实体"))
    }

    fun testConvertParseErrorShowsStatus() {
        panel.inputEditorForTest().setSql("SELECT * FROM t WHERE a = 'abc")
        panel.convertForTest()
        val result = awaitResult()
        assertEquals("", result)
        assertTrue(panel.statusForTest(), panel.statusForTest().contains("无法解析"))
    }

    fun testConvertQueryFormatSyncPath() {
        // QUERY 形态：跳过实体查找（同步路径直测）
        val state = PluginSettings.getInstance().state
        val original = state.reverseWrapperFormat
        state.reverseWrapperFormat = PluginSettings.REVERSE_QUERY
        try {
            panel.inputEditorForTest().setSql("SELECT * FROM user WHERE age > 18")
            panel.convertForTest()
            val result = panel.resultTextForTest()
            assertTrue(result, result.contains("new QueryWrapper<User>()"))
            assertTrue(result, result.contains(".gt(\"age\", 18)"))
        } finally {
            state.reverseWrapperFormat = original
        }
    }

    fun testFormatInputSql() {
        val raw = "SELECT * FROM user WHERE a = 1 AND b = 2"
        panel.inputEditorForTest().setSql(raw)
        // 格式化按钮走 FormatAction——直接验证 SqlFormatter 幂等接入（按钮触发同路径）
        val formatted = cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter.format(raw)
        panel.inputEditorForTest().setSql(formatted)
        panel.convertForTest()
        val result = awaitResult()
        assertTrue(result, result.contains("new QueryWrapper<User>()"))
    }

    fun testPanelDisposeReleasesEditors() {
        // dispose 幂等（重复调用不抛）
        panel.dispose()
        panel.dispose()
    }

    fun testBundleKeysPresent() {
        // tab 标题与按钮文案 bundle key 存在（防拼写回归）
        assertTrue(MpSqlVisualizerBundle.message("log.tab.sqlToJava").isNotBlank())
        assertTrue(MpSqlVisualizerBundle.message("reverse.panel.convert").isNotBlank())
        assertTrue(MpSqlVisualizerBundle.message("reverse.panel.status.noEntity").isNotBlank())
    }

    // ------------------------------------------------------------------
    // 形态下拉（T-M36.6）
    // ------------------------------------------------------------------

    fun testFormatComboInitialFromSettings() {
        val state = PluginSettings.getInstance().state
        val original = state.reverseWrapperFormat
        try {
            state.reverseWrapperFormat = PluginSettings.REVERSE_WRAPPERS
            // 面板重建后下拉应反映设置值（创建时 refreshFormatFromSettings）
            val panel2 = SqlToJavaPanel(project)
            try {
                assertEquals(PluginSettings.REVERSE_WRAPPERS, panel2.selectedFormatForTest())
            } finally {
                panel2.dispose()
            }
        } finally {
            state.reverseWrapperFormat = original
        }
    }

    fun testFormatComboWriteThroughAndRegenerate() {
        val state = PluginSettings.getInstance().state
        val original = state.reverseWrapperFormat
        try {
            state.reverseWrapperFormat = PluginSettings.REVERSE_QUERY
            panel.refreshFormatFromSettings()
            assertEquals(PluginSettings.REVERSE_QUERY, panel.selectedFormatForTest())

            panel.inputEditorForTest().setSql("SELECT id, name FROM user WHERE name = '张三'")
            panel.convertForTest()
            val queryResult = panel.resultTextForTest()
            assertTrue(queryResult, queryResult.contains("new QueryWrapper<User>()"))

            // 模拟用户下拉选择 WRAPPERS → 写穿设置 + 立即重生成（无实体 → Wrappers.query 字符串形态）
            panel.selectFormatForTest(PluginSettings.REVERSE_WRAPPERS)
            assertEquals(PluginSettings.REVERSE_WRAPPERS, state.reverseWrapperFormat)
            val wrappersResult = awaitContains("Wrappers.<User>query()")
            assertTrue(wrappersResult, wrappersResult.contains(".eq(\"name\", \"张三\")"))

            // 再切 LAMBDA（无实体 → 回落 new QueryWrapper；无候选缓存 → 异步完整流程）
            panel.selectFormatForTest(PluginSettings.REVERSE_LAMBDA)
            assertEquals(PluginSettings.REVERSE_LAMBDA, state.reverseWrapperFormat)
            val lambdaResult = awaitContains("new QueryWrapper<User>()")
            assertTrue(lambdaResult, lambdaResult.contains(".eq(\"name\", \"张三\")"))
        } finally {
            state.reverseWrapperFormat = original
        }
    }

    // ------------------------------------------------------------------
    // 调用类 / VO（T-M36.7）
    // ------------------------------------------------------------------

    fun testCallerAndVoGenerateInvocation() {
        val state = PluginSettings.getInstance().state
        val original = state.reverseWrapperFormat
        try {
            state.reverseWrapperFormat = PluginSettings.REVERSE_QUERY
            panel.refreshFormatFromSettings()

            panel.inputEditorForTest().setSql("SELECT id, name FROM user WHERE name = '张三'")
            // 调用类输入（测试访问器直接写文本框）
            panel.callerFieldForTest().text = "UserService"
            panel.voFieldForTest().text = "UserVo"
            panel.convertForTest()
            val result = panel.resultTextForTest()
            assertTrue(result, result.contains("QueryWrapper<User> wrapper = new QueryWrapper<User>()"))
            assertTrue(result, result.contains("List<UserVo> list = UserService.listVo(wrapper, UserVo.class);"))

            // 清空 VO → 普通 list 调用
            panel.voFieldForTest().text = ""
            panel.convertForTest()
            val plain = panel.resultTextForTest()
            assertTrue(plain, plain.contains("List<User> list = UserService.list(wrapper);"))
        } finally {
            state.reverseWrapperFormat = original
        }
    }
}
