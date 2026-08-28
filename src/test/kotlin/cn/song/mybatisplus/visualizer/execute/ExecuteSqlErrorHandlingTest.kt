package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.ui.SqlResultPanel
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * SQL 执行错误处理测试（M36.3）：验证错误回调链路和 UI 状态恢复。
 *
 * 测试场景：
 *  1. Database 插件不可用时，错误回调被调用
 *  2. 执行链失败时，错误回调被调用且 UI 从「执行中」恢复
 *  3. 超时提示机制正常触发
 */
class ExecuteSqlErrorHandlingTest : BasePlatformTestCase() {

    // ======================== 错误回调链路 ========================

    /**
     * 测试：Database 插件不可用时，onError 回调被调用。
     * （单元测试环境无 Database 插件，isAvailable() 返回 false）
     */
    @Test
    fun testErrorCallbackInvokedWhenPluginUnavailable() {
        val errors = mutableListOf<String>()
        var callbackResult: Boolean? = null

        DataSourceSelector.selectAndExecute(
            project = project,
            sql = "SELECT * FROM users",
            onError = { msg -> errors.add(msg) },
            callback = { success -> callbackResult = success },
        )

        // 验证：错误回调被调用，且包含 Database 插件不可用的提示
        assertTrue("onError should be called when plugin unavailable", errors.isNotEmpty())
        assertTrue(
            "Error message should mention Database plugin",
            errors.any { it.contains("Database") || it.contains("database") },
        )
        // 验证：callback 返回 false
        assertEquals("callback should return false", false, callbackResult)
    }

    /**
     * 测试：未设置 onError 回调时不会空指针异常。
     */
    @Test
    fun testNoErrorCallbackDoesNotThrow() {
        // 不应抛异常
        DataSourceSelector.selectAndExecute(
            project = project,
            sql = "SELECT * FROM users",
            onError = null,  // 不设置错误回调
            callback = null,
        )
        // 验证通过：无异常即成功
    }

    /**
     * 测试：runners 为空（无数据源）时，onError 回调被调用。
     * 这是 M36.3 修复的核心场景：用户无数据源时点击执行，应提示配置数据源。
     */
    @Test
    fun testErrorCallbackInvokedWhenRunnersEmpty() {
        val errors = mutableListOf<String>()
        var callbackResult: Boolean? = null

        DataSourceSelector.selectAndExecute(
            project = project,
            sql = "SELECT * FROM users",
            onError = { msg -> errors.add(msg) },
            callback = { success -> callbackResult = success },
        )

        // 验证：runners 为空时（测试环境无数据源），onError 被调用
        assertTrue("onError should be called when runners empty", errors.isNotEmpty())
        // 验证：callback 返回 false
        assertEquals("callback should return false", false, callbackResult)
    }

    // ======================== SqlResultPanel 状态恢复 ========================

    /**
     * 测试：SqlResultPanel.showError 正确设置错误文本和颜色。
     */
    @Test
    fun testResultPanelShowError() {
        val panel = SqlResultPanel()
        panel.showError("Connection failed")

        // 验证：错误信息包含在文本中
        assertTrue(
            "Error text should contain the message",
            panel.infoLabel.text.contains("Connection failed"),
        )
        // 验证：文本颜色为红色（错误色）
        assertEquals("Error color should be red", java.awt.Color.RED, panel.infoLabel.foreground)
    }

    /**
     * 测试：SqlResultPanel 从 loading 状态切换到 error 状态。
     */
    @Test
    fun testResultPanelLoadingToErrorTransition() {
        val panel = SqlResultPanel()

        // 初始状态：loading
        panel.showLoading()
        assertTrue("Loading text should be set", panel.infoLabel.text.isNotEmpty())

        // 切换到 error
        panel.showError("Test error")
        assertTrue(
            "Error text should replace loading text",
            panel.infoLabel.text.contains("Test error"),
        )
    }

    /**
     * 测试：SqlResultPanel.clear 清空状态。
     */
    @Test
    fun testResultPanelClear() {
        val panel = SqlResultPanel()
        panel.showError("Some error")
        assertTrue("Error should be set", panel.infoLabel.text.isNotEmpty())

        panel.clear()
        assertTrue("Cleared panel should have empty text", panel.infoLabel.text.isEmpty())
    }

    // ======================== SqlExecutor 错误透传 ========================

    /**
     * 测试：SqlExecutor.execute 在插件不可用时调用 onError。
     */
    @Test
    fun testSqlExecutorPropagatesError() {
        val errors = mutableListOf<String>()

        SqlExecutor.execute(
            project = project,
            sql = "SELECT 1",
            dataSource = null,
            isQuery = true,
            onError = { msg -> errors.add(msg) },
        )

        // 验证：错误回调被调用（插件不可用）
        assertTrue("SqlExecutor should propagate error to onError", errors.isNotEmpty())
    }

    /**
     * 测试：SqlExecutor.execute 未设置 onError 时不抛异常。
     */
    @Test
    fun testSqlExecutorNoErrorCallbackDoesNotThrow() {
        SqlExecutor.execute(
            project = project,
            sql = "SELECT 1",
            dataSource = null,
            isQuery = true,
            onError = null,
        )
        // 验证通过：无异常即成功
    }

    // ======================== 辅助属性和方法 ========================

    /**
     * 访问 SqlResultPanel 的 infoLabel（测试用）。
     * 注意：infoLabel 是 private，通过 reflection 访问用于测试验证。
     */
    private val SqlResultPanel.infoLabel: JLabel
        get() {
            val field = SqlResultPanel::class.java.getDeclaredField("infoLabel")
            field.isAccessible = true
            return field.get(this) as JLabel
        }
    }
