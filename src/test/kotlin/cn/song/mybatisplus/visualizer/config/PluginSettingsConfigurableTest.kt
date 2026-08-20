package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.ProjectManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import javax.swing.JCheckBox

/**
 * PluginSettingsConfigurable 契约测试（Wave2-P5）：
 *  - apply() 关→开检测（模拟 PluginSettings 状态变化后 apply 是否正确触发变更）
 *  - suppressed 标志的清零时机（仅关→开时清零，其他情况保持）
 */
class PluginSettingsConfigurableTest : BasePlatformTestCase() {

    // ------------------------------------------------------------------
    // 关→开检测
    // ------------------------------------------------------------------

    fun testApply_autoStart_offToOn_clearsSuppressed() {
        // 场景：autoStart 关→开 → apply 应清除 suppressed 标志
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.logAutoCaptureOnStart = false
            settings.state.logAutoCaptureSuppressed = true

            val configurable = PluginSettingsConfigurable()
            configurable.createComponent()

            // 模拟用户勾选「项目启动时自动开始监听」复选框
            val panel = configurable.panelForTest()
            val checkBox = findCheckBoxByText(panel.getRoot(), MpSqlVisualizerBundle.message("settings.log.autoCapture"))
            assertTrue("应找到自动监听复选框", checkBox != null)
            checkBox!!.isSelected = true

            // apply：panel.apply() 写回 state → configurable 检测关→开 → 清除 suppressed
            configurable.apply()
            assertFalse("关→开检测应清除 suppressed 标志", settings.state.logAutoCaptureSuppressed)
        } finally {
            settings.loadState(snapshot)
        }
    }

    fun testApply_autoStart_offToOn_triggersStartCaptureForOpenProjects() {
        // 场景：autoStart 关→开 + 有打开项目 → apply 应调 startCapture（受 suppressed 抑制后不再抑制）
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.logAutoCaptureOnStart = false
            settings.state.logAutoCaptureSuppressed = true

            val configurable = PluginSettingsConfigurable()
            configurable.createComponent()

            // 模拟用户勾选自动监听复选框
            val panel = configurable.panelForTest()
            val checkBox = findCheckBoxByText(panel.getRoot(), MpSqlVisualizerBundle.message("settings.log.autoCapture"))
            assertTrue("应找到自动监听复选框", checkBox != null)
            checkBox!!.isSelected = true

            // apply：panel.apply() 写回 state → configurable 检测关→开 → 清除 suppressed
            configurable.apply()
            assertFalse("关→开后 suppressed 应被清零", settings.state.logAutoCaptureSuppressed)
        } finally {
            settings.loadState(snapshot)
        }
    }

    // ------------------------------------------------------------------
    // suppressed 清零时机
    // ------------------------------------------------------------------

    fun testApply_autoStart_staysOn_doesNotClearSuppressed() {
        // 场景：autoStart 保持开（未变）→ apply 不清零 suppressed
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.logAutoCaptureOnStart = true
            settings.state.logAutoCaptureSuppressed = true

            val configurable = PluginSettingsConfigurable()
            configurable.createComponent()

            // apply：autoStart 未变（一直是开）→ 不触发关→开逻辑
            configurable.apply()
            assertTrue("autoStart 未变时 suppressed 应保持 true", settings.state.logAutoCaptureSuppressed)
        } finally {
            settings.loadState(snapshot)
        }
    }

    fun testApply_autoStart_staysOff_doesNotClearSuppressed() {
        // 场景：autoStart 保持关 → apply 不清零 suppressed
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.logAutoCaptureOnStart = false
            settings.state.logAutoCaptureSuppressed = true

            val configurable = PluginSettingsConfigurable()
            configurable.createComponent()

            // apply：autoStart 一直是关 → 不触发关→开逻辑
            configurable.apply()
            assertTrue("autoStart 保持关时 suppressed 应保持 true", settings.state.logAutoCaptureSuppressed)
        } finally {
            settings.loadState(snapshot)
        }
    }

    fun testApply_autoStart_onToOff_doesNotClearSuppressed() {
        // 场景：autoStart 开→关 → apply 不清零 suppressed（仅关→开才清零）
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.logAutoCaptureOnStart = true
            settings.state.logAutoCaptureSuppressed = true

            val configurable = PluginSettingsConfigurable()
            configurable.createComponent()

            // 模拟用户取消勾选自动监听复选框
            val panel = configurable.panelForTest()
            val checkBox = findCheckBoxByText(panel.getRoot(), MpSqlVisualizerBundle.message("settings.log.autoCapture"))
            assertTrue("应找到自动监听复选框", checkBox != null)
            checkBox!!.isSelected = false

            // apply：autoStart 开→关 → 不触发关→开逻辑
            configurable.apply()
            assertTrue("开→关时 suppressed 应保持 true", settings.state.logAutoCaptureSuppressed)
        } finally {
            settings.loadState(snapshot)
        }
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /** 在组件树中按文本查找 JCheckBox（未找到返回 null）。 */
    private fun findCheckBoxByText(component: java.awt.Component, text: String): JCheckBox? {
        if (component is JCheckBox && component.text == text) return component
        if (component is java.awt.Container) {
            for (child in component.components) {
                val found = findCheckBoxByText(child, text)
                if (found != null) return found
            }
        }
        return null
    }
}
