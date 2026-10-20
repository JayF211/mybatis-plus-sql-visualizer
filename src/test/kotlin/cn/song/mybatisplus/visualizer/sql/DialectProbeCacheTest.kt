package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings
import cn.song.mybatisplus.visualizer.log.agent.AgentUrlRegistry
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.lang.reflect.Proxy

/**
 * T-PS5-03：方言 URL 探测缓存守护测试（平台用例——[PluginSettings] 是 app 级服务，
 * 方言模式矩阵与 `ProjectPluginSettingsTest` 同归属地）。
 *
 * 缺陷背景（第八轮报告发现 3）：`DialectRegistry.effective` 无结果缓存，单次预览经
 * `SqlPreviewService` 九处调用点最多触发约 7 次重复反射探测（`DataSourceUrlProbe.firstUrl`
 * 每次都是一串 DatabaseTools 反射调用）。本组用例锁定：
 *  1. TTL 窗口内多次读取只探测一次；
 *  2. TTL 过期 / 显式失效后重探（数据源编辑后至多一个 TTL 窗口内方言滞后）；
 *  3. 异项目不命中同槽（不串味）；
 *  4. 手动方言模式路径**零探测**（回归）；自动推断结果与探测结果一致（语义等价）。
 */
class DialectProbeCacheTest : BasePlatformTestCase() {

    private var now = 1_000_000L

    override fun setUp() {
        super.setUp()
        now = 1_000_000L
        AgentUrlRegistry.clearAllForTest()
        DataSourceUrlProbe.probeForTest = null
        DataSourceUrlProbe.probeCandidatesForTest = null
        DataSourceUrlProbe.ttlMs = 5_000L
        DataSourceUrlProbe.clockMs = { now }
        DataSourceUrlProbe.probeCount.set(0)
        DataSourceUrlProbe.invalidateAllForTest()
    }

    override fun tearDown() {
        DataSourceUrlProbe.probeForTest = null
        DataSourceUrlProbe.clockMs = { System.currentTimeMillis() }
        DataSourceUrlProbe.ttlMs = 5_000L
        DataSourceUrlProbe.invalidateAllForTest()
        AgentUrlRegistry.clearAllForTest()
        super.tearDown()
    }

    /** 恒等语义项目桩（仅用于「异项目」身份判定）。 */
    private fun fakeProject(): Project {
        lateinit var proxy: Project
        proxy = Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { p, method, args ->
            when (method.name) {
                "equals" -> args?.isNotEmpty() == true && args[0] === p
                "hashCode" -> System.identityHashCode(p)
                "toString" -> "FakeProject"
                else -> null
            }
        } as Project
        return proxy
    }

    fun testTTL窗口内多次读取只探测一次() {
        var calls = 0
        DataSourceUrlProbe.probeForTest = { calls++; "jdbc:mysql://h:3306/db" }
        repeat(7) {
            assertEquals("jdbc:mysql://h:3306/db", DataSourceUrlProbe.firstUrl(project))
        }
        assertEquals("单次预览的 7 次读取只探测一次（报告实测约 7 次重复反射）", 1, calls)
        assertEquals(1, DataSourceUrlProbe.probeCount.get())
    }

    fun testTTL过期后重探() {
        var calls = 0
        DataSourceUrlProbe.probeForTest = { calls++; "jdbc:mysql://h:3306/db" }
        DataSourceUrlProbe.firstUrl(project)
        now += 5_001L // 越 TTL 上界
        DataSourceUrlProbe.firstUrl(project)
        assertEquals("TTL 过期 → 重探（数据源编辑后至多一个窗口滞后）", 2, calls)
    }

    fun test显式失效后重探() {
        var calls = 0
        DataSourceUrlProbe.probeForTest = { calls++; "jdbc:mysql://h:3306/db" }
        DataSourceUrlProbe.firstUrl(project)
        DataSourceUrlProbe.invalidate(project) // 会话建立/断开事件点
        DataSourceUrlProbe.firstUrl(project)
        assertEquals("显式失效 → 重探", 2, calls)
    }

    fun test异项目不命中同槽() {
        var calls = 0
        DataSourceUrlProbe.probeForTest = { calls++; "jdbc:mysql://h:3306/db" }
        val other = fakeProject()
        DataSourceUrlProbe.firstUrl(project)
        DataSourceUrlProbe.firstUrl(other)
        DataSourceUrlProbe.firstUrl(project)
        assertEquals("异项目各自探测（槽不串味），同项目第三次仍命中", 3, calls)
    }

    fun test手动方言模式零探测() {
        val global = PluginSettings.getInstance()
        val ps = project.service<ProjectPluginSettings>()
        val psSnapshot = ps.getState().copy()
        val globalSnapshot = global.getState().copy()
        try {
            ps.state.useProjectSettings = false
            global.state.dialectAuto = false
            global.state.dialect = "PostgreSQL"
            DataSourceUrlProbe.probeForTest = { error("手动方言模式不得探测数据源 URL") }
            assertEquals(PostgreSQLDialect, DialectRegistry.effective(project = project))
            assertEquals("手动模式路径零探测（回归）", 0, DataSourceUrlProbe.probeCount.get())
        } finally {
            ps.loadState(psSnapshot)
            global.loadState(globalSnapshot)
        }
    }

    fun test自动推断读缓存且结果等价() {
        val global = PluginSettings.getInstance()
        val ps = project.service<ProjectPluginSettings>()
        val psSnapshot = ps.getState().copy()
        val globalSnapshot = global.getState().copy()
        try {
            ps.state.useProjectSettings = false
            global.state.dialectAuto = true
            var calls = 0
            DataSourceUrlProbe.probeForTest = { calls++; "jdbc:postgresql://h:5432/db" }
            repeat(3) {
                assertEquals(
                    "推断结果与探测结果一致（缓存不改语义）",
                    PostgreSQLDialect,
                    DialectRegistry.effective(project = project),
                )
            }
            assertEquals("三次 effective 只探测一次", 1, calls)
        } finally {
            ps.loadState(psSnapshot)
            global.loadState(globalSnapshot)
        }
    }

    fun test异方言项目候选不取首个并在兼容结果中标证据不足() {
        val settings = PluginSettings.getInstance()
        val saved = settings.getState().copy()
        val projectSettings = project.service<ProjectPluginSettings>()
        val savedProject = projectSettings.getState().copy()
        try {
            projectSettings.state.useProjectSettings = false
            settings.state.dialectAuto = true
            var calls = 0
            DataSourceUrlProbe.probeCandidatesForTest = {
                calls++
                listOf(
                    DialectDecisionEngine.Candidate("pg", "PostgreSQL"),
                    DialectDecisionEngine.Candidate("my", "MySQL"),
                )
            }
            val decision = DialectRegistry.decision(project)
            assertEquals(DialectDecisionEngine.Status.AMBIGUOUS, decision.status)
            assertEquals(listOf("my", "pg"), decision.dataSourceIds)
            assertEquals("未知时兼容渲染不得伪称是明确推断", MySqlDialect, DialectRegistry.effective(project))
            assertEquals("同项目短窗内候选只查一次", 1, calls)
        } finally {
            projectSettings.loadState(savedProject)
            settings.loadState(saved)
        }
    }

    fun test两个数据源方言相同可确定渲染但不虚构唯一库() {
        val settings = PluginSettings.getInstance()
        val saved = settings.getState().copy()
        val projectSettings = project.service<ProjectPluginSettings>()
        val savedProject = projectSettings.getState().copy()
        try {
            projectSettings.state.useProjectSettings = false
            settings.state.dialectAuto = true
            DataSourceUrlProbe.probeCandidatesForTest = {
                listOf(DialectDecisionEngine.Candidate("pg2", "PostgreSQL"),
                    DialectDecisionEngine.Candidate("pg1", "PostgreSQL"))
            }
            val decision = DialectRegistry.decision(project)
            assertEquals(DialectDecisionEngine.Status.RESOLVED, decision.status)
            assertEquals(DialectDecisionEngine.Confidence.CONSENSUS, decision.confidence)
            assertEquals(listOf("pg1", "pg2"), decision.dataSourceIds)
        } finally {
            projectSettings.loadState(savedProject)
            settings.loadState(saved)
        }
    }
}
