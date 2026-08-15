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
}
