package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.testutil.SqlLogManagerProjectState
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import javax.swing.JCheckBox

/**
 * SectionConfigurable 自动监听「关→开」边沿联动契约测试
 * （20260907-1617：原 PluginSettingsConfigurableTest 迁移——该类未注册进 plugin.xml 是死类，
 * 边沿联动迁入 SectionConfigurable.apply() 基类；用户实际勾选走 SqlLogConfigurable 等子页链路）：
 *  - apply() 关→开检测（panel.apply() 写回后比对，清 suppressed + openProjects startCapture）
 *  - suppressed 标志的清零时机（仅关→开时清零，其他情况保持）
 */
class SectionConfigurableAutoCaptureLinkageTest : BasePlatformTestCase() {

    /** T-TestIsolation：本类经 `apply()` → `onAutoStartSettingEnabled()` → `startCapture()`
     *  改的是**项目服务级**共享 SqlLogManager（light 项目片内跨类复用）——捕获态 / 暂停态 /
     *  桥轮询都会被改动，存初值供 tearDown 复位。 */
    private lateinit var serviceState: SqlLogManagerProjectState.Snapshot

    override fun setUp() {
        super.setUp()
        serviceState = SqlLogManagerProjectState.capture(project.getService(SqlLogManager::class.java))
    }

    override fun tearDown() {
        SqlLogManagerProjectState.restore(project.getService(SqlLogManager::class.java), serviceState)
        super.tearDown()
    }

    /** 承载 SQL 日志分区的真实子页（与用户勾选「项目启动时自动开始监听」同一路径）。 */
    private fun newConfigurable(): SectionConfigurable = SqlLogConfigurable()

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

            val configurable = newConfigurable()
            configurable.createComponent()

            // 模拟用户勾选「项目启动时自动开始监听」复选框
            val panel = configurable.panelForTest()
            val checkBox = findCheckBoxByText(panel.getRoot(), MpSqlVisualizerBundle.message("settings.log.autoCapture"))
            assertTrue("应找到自动监听复选框", checkBox != null)
            checkBox!!.isSelected = true

            // apply：panel.apply() 写回 state → 基类检测关→开 → 清除 suppressed
            configurable.apply()
            assertFalse("关→开检测应清除 suppressed 标志", settings.state.logAutoCaptureSuppressed)
        } finally {
            settings.loadState(snapshot)
        }
    }

    fun testApply_autoStart_offToOn_linkageDoesNotThrow() {
        // 场景：autoStart 关→开 → apply 联动 onAutoStartSettingEnabled 分流（headless 进程级监听
        // 订阅成功=被动模式不预 armed；降级才 armed。两种模式本用例都只验证不抛+状态正确）
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.logAutoCaptureOnStart = false
            settings.state.logAutoCaptureSuppressed = true

            val configurable = newConfigurable()
            configurable.createComponent()
            val panel = configurable.panelForTest()
            val checkBox = findCheckBoxByText(panel.getRoot(), MpSqlVisualizerBundle.message("settings.log.autoCapture"))
            checkBox!!.isSelected = true

            configurable.apply()
            assertFalse("关→开后 suppressed 应被清零", settings.state.logAutoCaptureSuppressed)
            assertTrue("联动后 state.logAutoCaptureOnStart 应为 true", settings.state.logAutoCaptureOnStart)
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

            val configurable = newConfigurable()
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

            val configurable = newConfigurable()
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

            val configurable = newConfigurable()
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
