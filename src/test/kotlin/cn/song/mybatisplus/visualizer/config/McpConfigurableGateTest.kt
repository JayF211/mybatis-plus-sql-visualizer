package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.mcp.McpToolGate
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * MCP 设置页门控区测试（T-McpToolset2 / T-MTS2-00）：组件构建（总开关 + 10 工具复选框）、
 * isModified/apply/reset 往返、全部启用/全部禁用状态切换单按钮、apply 后 McpToolGate 即时跟随。
 */
class McpConfigurableGateTest : BasePlatformTestCase() {

    private val state get() = PluginSettings.getInstance().state

    override fun tearDown() {
        state.mcpToolsEnabled = true
        state.mcpDisabledTools.clear()
        super.tearDown()
    }

    private fun newConfigurable(): McpConfigurable {
        val configurable = McpConfigurable()
        Disposer.register(testRootDisposable) { configurable.disposeUIResources() }
        configurable.createComponent()
        return configurable
    }

    fun test构建_默认全启用且未修改() {
        val configurable = newConfigurable()
        assertTrue(configurable.createComponent() != null)
        configurable.apply() // 基线空跑
        assertTrue(state.mcpToolsEnabled)
        assertTrue(state.mcpDisabledTools.isEmpty())
        assertFalse(configurable.isModified)
    }

    fun test总开关apply写入状态且门控即时跟随() {
        val configurable = newConfigurable()
        configurable.apply() // 基线
        configurable.masterForTest()!!.isSelected = false
        assertTrue("改动后应判 modified", configurable.isModified)
        configurable.apply()
        assertFalse(state.mcpToolsEnabled)
        assertFalse("门控即时跟随设置", McpToolGate.isEnabled("sqlv_render_sql"))
    }

    fun test工具级禁用apply写入禁用清单() {
        val configurable = newConfigurable()
        configurable.apply() // 基线
        configurable.toolCheckBoxForTest("sqlv_explain")!!.isSelected = false
        configurable.toolCheckBoxForTest("sqlv_mapper_check")!!.isSelected = false
        assertTrue(configurable.isModified)
        configurable.apply()
        assertEquals(listOf("sqlv_explain", "sqlv_mapper_check"), state.mcpDisabledTools)
        assertFalse(McpToolGate.isEnabled("sqlv_explain"))
        assertTrue(McpToolGate.isEnabled("sqlv_render_sql"))
    }

    fun testreset恢复设置态() {
        state.mcpToolsEnabled = false
        state.mcpDisabledTools.add("sqlv_explain")
        val configurable = newConfigurable()
        configurable.reset()
        configurable.apply()
        assertFalse(state.mcpToolsEnabled)
        assertEquals(listOf("sqlv_explain"), state.mcpDisabledTools)
        assertFalse(configurable.isModified)
    }

    fun test全部启用禁用切换按钮() {
        val configurable = newConfigurable()
        configurable.apply() // 基线
        val button = configurable.toggleAllButtonForTest()!!
        // 初始全勾 → 按钮为「全部禁用」；点击 → 全不勾 → 按钮翻转「全部启用」
        assertEquals(MpSqlVisualizerBundle.message("settings.mcp.tools.none"), button.text)
        button.doClick()
        assertEquals(MpSqlVisualizerBundle.message("settings.mcp.tools.all"), button.text)
        configurable.apply()
        assertEquals(McpToolGate.TOOL_NAMES.sorted(), state.mcpDisabledTools.sorted())
        button.doClick()
        configurable.apply()
        assertTrue(state.mcpDisabledTools.isEmpty())
    }
}
