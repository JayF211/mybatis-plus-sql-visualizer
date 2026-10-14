package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cn.song.mybatisplus.visualizer.config.PluginSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * SQL 日志面板交互 UI 测试（真实点击驱动，headless 断言）：
 *  - 耗时排序按钮点击 → 切换 DURATION_DESC 且列表按耗时降序；再次点击回 NONE（F-44-2 耗时排序用户交互）
 *  - 慢查询计数标签：注入超过阈值的耗时条目 → 标签可见且文案含慢查询条数（O-1 慢查询计数 UI）
 *
 * 注意：[LogDurationParser.parse] 从 rawSql 的 p6spy/druid 格式取耗时，[SlowQueryCounter] 取
 * executionTimeMs 字段；两者在桥层一致，测试里同时设置以保证排序可见与计数正确。
 */
class SqlLogPanelSortAndSlowQueryTest : BasePlatformTestCase() {

    private lateinit var manager: SqlLogManager

    override fun setUp() {
        super.setUp()
        manager = SqlLogManager(project)
        manager.entries.dedupeEnabled = false
    }

    override fun tearDown() {
        manager.dispose()
        super.tearDown()
    }

    private fun waitForEdt() = com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()

    /** 构造条目：rawSql 走 p6spy 格式（第二段=耗时）以保证排序解析命中；executionTimeMs 同步设置。 */
    private fun entry(sql: String, ms: Long, ts: Long) = LogSqlEntry(
        rawSql = "1723456789012|${ms}|statement|${sql}",
        restoredSql = sql,
        params = emptyList(),
        timestamp = ts,
        executionTimeMs = ms,
    )

    fun test排序方式选择器_默认时间升序_切耗时降序再回时间升序() {
        val panel = SqlLogPanel(project, manager)
        try {
            manager.entries.addEntry(entry("SELECT a FROM t", 50L, 100L))
            manager.entries.addEntry(entry("SELECT b FROM t", 2000L, 200L))
            manager.entries.addEntry(entry("SELECT c FROM t", 300L, 300L))
            waitForEdt()

            assertEquals("默认排序方式应为时间升序", SortMode.TIME_ASC, panel.currentSortMode)
            assertEquals("时间升序：入列顺序 a,b,c", "SELECT a FROM t", panel.visibleEntries[0])

            // 选中「耗时降序」→ 2000(b) > 300(c) > 50(a)
            panel.selectSortModeForTest(SortMode.DURATION_DESC)
            waitForEdt()
            assertEquals("选择后排序模式 DURATION_DESC", SortMode.DURATION_DESC, panel.currentSortMode)
            assertEquals("降序首条应为 2000ms 的 b", "SELECT b FROM t", panel.visibleEntries[0])
            assertEquals("降序次条应为 300ms 的 c", "SELECT c FROM t", panel.visibleEntries[1])
            assertEquals("降序末条应为 50ms 的 a", "SELECT a FROM t", panel.visibleEntries[2])

            // 切回「时间升序」
            panel.selectSortModeForTest(SortMode.TIME_ASC)
            waitForEdt()
            assertEquals("回时间升序", SortMode.TIME_ASC, panel.currentSortMode)
            assertEquals("回时间升序首条为 a", "SELECT a FROM t", panel.visibleEntries[0])
        } finally {
            panel.dispose()
        }
    }

    fun test排序方式_时间降序_耗时升序() {
        val panel = SqlLogPanel(project, manager)
        try {
            manager.entries.addEntry(entry("SELECT a FROM t", 50L, 100L))
            manager.entries.addEntry(entry("SELECT b FROM t", 2000L, 200L))
            manager.entries.addEntry(entry("SELECT c FROM t", 300L, 300L))
            waitForEdt()

            panel.selectSortModeForTest(SortMode.TIME_DESC)
            waitForEdt()
            assertEquals("时间降序首条应为最晚的 c", "SELECT c FROM t", panel.visibleEntries[0])
            assertEquals("时间降序末条应为最早的 a", "SELECT a FROM t", panel.visibleEntries[2])

            panel.selectSortModeForTest(SortMode.DURATION_ASC)
            waitForEdt()
            assertEquals("耗时升序首条应为 50ms 的 a", "SELECT a FROM t", panel.visibleEntries[0])
            assertEquals("耗时升序末条应为 2000ms 的 b", "SELECT b FROM t", panel.visibleEntries[2])
        } finally {
            panel.dispose()
        }
    }

    fun test排序方式_行号() {
        val panel = SqlLogPanel(project, manager)
        try {
            manager.entries.addEntry(entry("SELECT a FROM t", 50L, 300L))
            manager.entries.addEntry(entry("SELECT b FROM t", 2000L, 100L))
            waitForEdt()
            // 行号 = 入列序（a 是 #1、b 是 #2），与时间无关
            panel.selectSortModeForTest(SortMode.SEQUENCE)
            waitForEdt()
            assertEquals("行号序首条应为先入列的 a", "SELECT a FROM t", panel.visibleEntries[0])
            assertEquals("行号序次条应为 b", "SELECT b FROM t", panel.visibleEntries[1])
        } finally {
            panel.dispose()
        }
    }

    fun test排序方式按钮_恒可见且含全部选项() {
        val panel = SqlLogPanel(project, manager)
        try {
            // 无任何耗时信息时排序方式仍可见（默认时间升序恒可用，不再按耗时显隐）
            manager.entries.addEntry(LogSqlEntry(rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList(), timestamp = 1L))
            waitForEdt()
            // 无任何耗时信息时排序动作仍恒在工具栏动作组内（默认时间升序恒可用，不再按耗时显隐）
            assertTrue(
                "排序动作应恒在工具栏动作组内（并入左侧按钮集，窄宽随组折叠）",
                panel.toolbarGroup.getChildren(null).contains(panel.sortActionForTest),
            )
            assertEquals("默认排序方式显示名应为时间升序", "时间升序", panel.sortButtonTextForTest)
            assertEquals(
                "菜单应含七种排序方式且时间升序居首",
                listOf("时间升序", "时间降序", "耗时升序", "耗时降序", "行号", "同结构聚类", "按目标库分组"),
                panel.sortMenuLabelsForTest(),
            )
        } finally {
            panel.dispose()
        }
    }

    fun test同结构聚类_同形状条目相邻() {
        val panel = SqlLogPanel(project, manager)
        try {
            manager.entries.addEntry(entry("SELECT * FROM alpha WHERE id = 1", 10L, 100L))
            manager.entries.addEntry(entry("SELECT * FROM beta WHERE id = 2", 10L, 200L))
            manager.entries.addEntry(entry("SELECT * FROM alpha WHERE id = 3", 10L, 300L))
            waitForEdt()
            panel.selectSortModeForTest(SortMode.STRUCTURE_CLUSTER)
            waitForEdt()
            val order = panel.visibleEntries
            val alphaIdx = order.indices.filter { order[it].contains("FROM alpha") }
            assertEquals("两条同结构（alpha 仅字面量不同）应各出现一次", 2, alphaIdx.size)
            assertEquals(
                "同结构条目应相邻（索引差应为 1，实际 ${alphaIdx[0]} / ${alphaIdx[1]}）",
                1, alphaIdx[1] - alphaIdx[0],
            )
            assertEquals("组内保持时间升序", "SELECT * FROM alpha WHERE id = 1", order[alphaIdx[0]])
        } finally {
            panel.dispose()
        }
    }

    fun test按目标库分组_库名升序且未知沉底() {
        val panel = SqlLogPanel(project, manager)
        try {
            manager.entries.addEntry(entry("SELECT pg", 10L, 100L).copy(databaseName = "PostgreSQL"))
            manager.entries.addEntry(entry("SELECT none", 10L, 200L))
            manager.entries.addEntry(entry("SELECT my", 10L, 300L).copy(databaseName = "MySQL"))
            waitForEdt()
            panel.selectSortModeForTest(SortMode.DATABASE_GROUP)
            waitForEdt()
            val order = panel.visibleEntries
            assertEquals("MySQL 应排最前", "SELECT my", order[0])
            assertEquals("PostgreSQL 次之", "SELECT pg", order[1])
            assertEquals("未知库名沉底", "SELECT none", order[2])
        } finally {
            panel.dispose()
        }
    }

    fun test只看慢查询_按阈值过滤且未知耗时不算慢() {
        val savedThreshold = PluginSettings.getInstance().state.slowQueryThresholdMs
        PluginSettings.getInstance().state.slowQueryThresholdMs = 1000
        val panel = SqlLogPanel(project, manager)
        try {
            manager.entries.addEntry(entry("SELECT slow_one", 2000L, 100L))
            manager.entries.addEntry(entry("SELECT fast_one", 10L, 200L))
            // 结构化耗时空（unknown）——不算慢查询
            manager.entries.addEntry(LogSqlEntry("SELECT unknown", "SELECT unknown", emptyList(), timestamp = 300L))
            waitForEdt()
            assertEquals("默认不过滤：三条都在", 3, panel.visibleEntries.size)

            panel.slowOnlyForTest = true
            waitForEdt()
            assertEquals("只看慢查询：仅保留 2000ms 那条", listOf("SELECT slow_one"), panel.visibleEntries)

            panel.slowOnlyForTest = false
            waitForEdt()
            assertEquals("取消勾选恢复全部", 3, panel.visibleEntries.size)
        } finally {
            PluginSettings.getInstance().state.slowQueryThresholdMs = savedThreshold
            panel.dispose()
        }
    }

    fun test只看慢查询勾选框_在类型弹窗内且文案双语在位() {
        val panel = SqlLogPanel(project, manager)
        try {
            panel.buildTypeFilterContentForTest()
            val box = panel.slowOnlyCheckBoxForTest
            assertNotNull("类型弹窗内应有「只看慢查询」勾选框", box)
            assertEquals("勾选框文案应走 bundle",
                cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("log.filter.slowOnly"),
                box!!.text)
            assertEquals("默认不勾选", false, box.isSelected)
        } finally {
            panel.dispose()
        }
    }

    fun test增强通道条目_仅结构化耗时_可按耗时排序() {
        // agent 通道形态：耗时只在结构化 executionTimeMs，SQL 文本内无耗时（旧实现仅看文本解析
        // → 排序取值全空；本用例锁死该回归）
        val panel = SqlLogPanel(project, manager)
        try {
            fun agentEntry(sql: String, ms: Long, ts: Long) = LogSqlEntry(
                rawSql = sql, restoredSql = sql, params = emptyList(), timestamp = ts,
                executionTimeMs = ms, source = SqlSource.AGENT,
            )
            manager.entries.addEntry(agentEntry("SELECT a FROM t", 12L, 100L))
            manager.entries.addEntry(agentEntry("SELECT b FROM t", 480L, 200L))
            manager.entries.addEntry(agentEntry("SELECT c FROM t", 3L, 300L))
            waitForEdt()

            panel.selectSortModeForTest(SortMode.DURATION_DESC)
            waitForEdt()
            assertEquals("降序首条应为 480ms 的 b", "SELECT b FROM t", panel.visibleEntries[0])
            assertEquals("降序次条应为 12ms 的 a", "SELECT a FROM t", panel.visibleEntries[1])
            assertEquals("降序末条应为 3ms 的 c", "SELECT c FROM t", panel.visibleEntries[2])

            panel.selectSortModeForTest(SortMode.DURATION_ASC)
            waitForEdt()
            assertEquals("升序首条应为 3ms 的 c", "SELECT c FROM t", panel.visibleEntries[0])
        } finally {
            panel.dispose()
        }
    }

    fun test注入慢查询_工具栏计数标签可见且含条数() {
        val saved = PluginSettings.getInstance().state.slowQueryThresholdMs
        PluginSettings.getInstance().state.slowQueryThresholdMs = 1000
        val panel = SqlLogPanel(project, manager)
        try {
            // 一条慢查询（2000ms >= 1000）+ 一条正常（50ms）
            manager.entries.addEntry(entry("SELECT slow FROM big_table", 2000L, 100L))
            manager.entries.addEntry(entry("SELECT fast FROM t", 50L, 200L))
            waitForEdt()

            val label = panel.slowQueryLabelForTest
            assertNotNull("慢查询标签应存在", label)
            assertEquals("存在慢查询，标签应可见", true, label?.isVisible)
            assertTrue("标签文案应含慢查询条数 1", label?.text?.contains("1") == true)
        } finally {
            PluginSettings.getInstance().state.slowQueryThresholdMs = saved
            panel.dispose()
        }
    }

    fun test无慢查询_工具栏计数标签隐藏() {
        val panel = SqlLogPanel(project, manager)
        try {
            manager.entries.addEntry(entry("SELECT ok FROM t", 50L, 100L))
            waitForEdt()
            val label = panel.slowQueryLabelForTest
            assertEquals("无慢查询，标签应隐藏", false, label?.isVisible)
        } finally {
            panel.dispose()
        }
    }
}
