package cn.song.mybatisplus.visualizer.config

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 设置面板绑定生效性测试（T-xx 设置生效性审计）：
 *
 *  - 反向转换形态（reverseWrapperFormat）：此前**漏挂 isModified**——仅改此项时 Apply 按钮不启用，
 *    改动被平台静默丢弃（字段有真实读取点（ConvertSqlToJavaAction/SqlToJavaPanel）但保存链路断裂，形同假开关）；
 *  - 启动自动监听开关（logAutoCaptureOnStart）：勾选/取消 → apply 写回 state（D4 传播入口的状态部分）。
 */
class PluginSettingsPanelBindingTest : BasePlatformTestCase() {

    fun testReverseFormatChangeEnablesApplyAndPersists() {
        val settings = PluginSettings.getInstance()
        val old = settings.state.reverseWrapperFormat
        try {
            settings.state.reverseWrapperFormat = PluginSettings.REVERSE_AUTO
            val configurable = PluginSettingsConfigurable()
            configurable.createComponent()
            val panel = configurable.panelForTest()

            assertFalse("初始应与配置一致（无变化）", configurable.isModified())

            panel.selectReverseFormat(PluginSettings.REVERSE_QUERY)
            assertTrue(
                "仅改反向转换形态也应检测到变化（修复前漏挂 isModified → Apply 不启用、改动被静默丢弃）",
                configurable.isModified(),
            )

            configurable.apply()
            assertEquals("apply 后配置应写回 QUERY", PluginSettings.REVERSE_QUERY, settings.state.reverseWrapperFormat)

            configurable.reset()
            assertFalse("reset 后应回到与配置一致（无变化）", configurable.isModified())
        } finally {
            settings.state.reverseWrapperFormat = old
        }
    }

    fun testAutoCaptureToggleAppliesToState() {
        val settings = PluginSettings.getInstance()
        val old = settings.state.logAutoCaptureOnStart
        try {
            settings.state.logAutoCaptureOnStart = false
            val configurable = PluginSettingsConfigurable()
            configurable.createComponent()
            val panel = configurable.panelForTest()

            panel.setLogAutoCaptureCheck(true)
            assertTrue("勾选「启动自动监听」应检测到变化", configurable.isModified())
            configurable.apply()
            assertTrue("apply 后 state.logAutoCaptureOnStart 应为 true", settings.state.logAutoCaptureOnStart)

            panel.setLogAutoCaptureCheck(false)
            assertTrue("取消勾选同样应检测到变化", configurable.isModified())
            configurable.apply()
            assertFalse("apply 后 state.logAutoCaptureOnStart 应回 false", settings.state.logAutoCaptureOnStart)
        } finally {
            settings.state.logAutoCaptureOnStart = old
        }
    }

    fun testManualEntityToggleAppliesToState() {
        val settings = PluginSettings.getInstance()
        val old = settings.state.reverseManualEntitySelection
        try {
            settings.state.reverseManualEntitySelection = false
            val configurable = PluginSettingsConfigurable()
            configurable.createComponent()
            val panel = configurable.panelForTest()

            panel.setManualEntityCheck(true)
            assertTrue("勾选「手动选择实体类」应检测到变化", configurable.isModified())
            configurable.apply()
            assertTrue("apply 后 state.reverseManualEntitySelection 应为 true", settings.state.reverseManualEntitySelection)

            panel.setManualEntityCheck(false)
            assertTrue("取消勾选同样应检测到变化", configurable.isModified())
            configurable.apply()
            assertFalse("apply 后 state.reverseManualEntitySelection 应回 false", settings.state.reverseManualEntitySelection)

            configurable.reset()
            assertFalse("reset 后应回到与配置一致（无变化）", configurable.isModified())
        } finally {
            settings.state.reverseManualEntitySelection = old
        }
    }
}
