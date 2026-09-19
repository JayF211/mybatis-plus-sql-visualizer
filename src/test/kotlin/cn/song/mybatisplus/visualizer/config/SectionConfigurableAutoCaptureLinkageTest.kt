package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.testutil.AlreadyDisposedDiagnostics
import cn.song.mybatisplus.visualizer.testutil.SqlLogManagerProjectState
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.serviceContainer.AlreadyDisposedException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.lang.reflect.Proxy
import javax.swing.JCheckBox

/**
 * SectionConfigurable 自动监听「关→开」边沿联动契约测试
 * （20260907-1617：原 PluginSettingsConfigurableTest 迁移——该类未注册进 plugin.xml 是死类，
 * 边沿联动迁入 SectionConfigurable.apply() 基类；用户实际勾选走 SqlLogConfigurable 等子页链路）：
 *  - apply() 关→开检测（panel.apply() 写回后比对，清 suppressed + openProjects startCapture）
 *  - suppressed 标志的清零时机（仅关→开时清零，其他情况保持）
 *  - **T-ST-02b2**：`linkageAutoStartForProjects` 在取服务**之前**判 `Project.isDisposed`——项目处于
 *    temporarily-disposed 窗口时**不发起那次服务创建**（否则平台 `LazyInstanceHolder` 会把
 *    `CannotInitialize` 缓存下来、该服务在该项目实例上永久重抛），正常项目仍取服务、仍联动。
 */
class SectionConfigurableAutoCaptureLinkageTest : BasePlatformTestCase() {

    /** T-TestIsolation：本类经 `apply()` → `onAutoStartSettingEnabled()` → `startCapture()`
     *  改的是**项目服务级**共享 SqlLogManager（light 项目片内跨类复用）——捕获态 / 暂停态 /
     *  桥轮询都会被改动，存初值供 tearDown 复位。 */
    private lateinit var serviceState: SqlLogManagerProjectState.Snapshot

    override fun setUp() {
        super.setUp()
        serviceState = SqlLogManagerProjectState.capture(
            AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "setUp.capture")
        )
    }

    override fun tearDown() {
        SqlLogManagerProjectState.restore(
            AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "tearDown.restore"),
            serviceState,
        )
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
    // T-ST-02b2：取服务前判 isDisposed（不发起那次注定失败的创建）
    // ------------------------------------------------------------------

    /**
     * 项目桩（接口代理；本仓先例 `execute/CachedTempPsiFileTest.kt:35`——`Project` 是接口可代理）。
     *
     *  - `isDisposed` 应答构造时给定的值（模拟 temporarily-disposed 窗口：平台 `ProjectImpl.isDisposed()`
     *    在窗口内为 true）；
     *  - `getService(SqlLogManager)` **记一次调用**后返回 [service]；[service] 为 null 时抛
     *    `AlreadyDisposedException("Container is already disposed")`——复刻「窗口内首次创建服务失败」的形态。
     */
    private class ProjectStub(val disposed: Boolean, val service: SqlLogManager?) {
        var getServiceCalls = 0
        lateinit var proxy: Project
    }

    /** 造一个 [ProjectStub]（代理 handler 是三参 lambda——非零参，不触发 JUnit3 合成方法误判）。 */
    private fun projectStub(disposed: Boolean, service: SqlLogManager?): ProjectStub {
        val stub = ProjectStub(disposed, service)
        stub.proxy = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(Project::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "isDisposed" -> stub.disposed
                "getService" -> {
                    stub.getServiceCalls++
                    stub.service ?: throw AlreadyDisposedException("Container is already disposed")
                }
                "getName" -> "ProjectStub(disposed=${stub.disposed})"
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ProjectStub(disposed=${stub.disposed})"
                else -> null
            }
        } as Project
        return stub
    }

    /**
     * T-ST-02b2 核心：项目已 dispose（含 temporarily-disposed 窗口）时 **不得发起那次服务创建**。
     *
     * 为什么不是「抛异常了也算通过」：平台服务容器**一次创建失败即永久毒化**该服务在该项目实例上的
     * holder——`LazyInstanceHolder.initialize$1` 的异常表是 `catch java.lang.Throwable`（不过滤
     * `CancellationException`），catch 后无条件 `new CannotInitialize(instanceClass, throwable)` +
     * `complete(...)`；此后 `tryGetInstance()` 见 `_state instanceof CannotInitialize` 就重抛缓存异常。
     * `runCatching` 只吞结果、**阻止不了中毒**，故断言口径是「getService 调用数 == 0」（不发起），
     * 而不是「没抛出去」。
     */
    fun testLinkage_disposedProject_skipsBeforeCreatingService() {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            val stub = projectStub(disposed = true, service = null)
            newConfigurable().linkageAutoStartForProjects(listOf(stub.proxy))
            assertEquals(
                "已 dispose 的项目不得发起服务创建（否则把服务 holder 永久毒化）",
                0,
                stub.getServiceCalls,
            )
        } finally {
            settings.loadState(snapshot)
        }
    }

    /** 正常项目（isDisposed=false）happy path 不变：仍取服务一次、仍调 `onAutoStartSettingEnabled()`。 */
    fun testLinkage_aliveProject_stillCreatesServiceAndAppliesLinkage() {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.logAutoCaptureSuppressed = true
            val real = AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "linkage.happyPath")
            val stub = projectStub(disposed = false, service = real)
            newConfigurable().linkageAutoStartForProjects(listOf(stub.proxy))
            assertEquals("正常项目仍应取服务一次（跳过判据不得误伤）", 1, stub.getServiceCalls)
            assertFalse("联动后 suppressed 应被清零", settings.state.logAutoCaptureSuppressed)
        } finally {
            settings.loadState(snapshot)
        }
    }

    /**
     * 项目未被判为 disposed、但取服务仍失败（模拟容器已释放）→ 异常不得穿出（T-ST-02b 既有保证，
     * 即「已消除 `apply()` 穿出这一表现」）。异常若穿出，本用例即以 error 失败。
     */
    fun testLinkage_serviceCreationFailure_doesNotPropagate() {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            val stub = projectStub(disposed = false, service = null)
            newConfigurable().linkageAutoStartForProjects(listOf(stub.proxy))
            assertEquals("失败路径仍应尝试过一次取服务（降级由 runCatching 收口）", 1, stub.getServiceCalls)
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
