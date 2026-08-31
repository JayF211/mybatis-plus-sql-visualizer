package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * SQL 日志面板「移除重复的sql」真实交互测试（F-44-2 用户反馈两项缺陷）：
 *
 * 场景复现用户操作：
 *  1. 项目运行累积重复 SQL（未勾选去重，重复全展示）——点击勾选 → 已存在条目**立即**去重（原缺陷 1.3）；
 *  2. 去重开启状态下连续捕获同 SQL —— ⨉N 徽章数据**即时**更新，无需等下一条 SQL 入列（原缺陷 1.4）。
 */
class SqlLogPanelDedupeToggleTest : BasePlatformTestCase() {

    private lateinit var manager: SqlLogManager

    override fun setUp() {
        super.setUp()
        manager = SqlLogManager(project)
        manager.entries.dedupeEnabled = false // 模拟用户初始未勾选「移除重复的sql」
    }

    override fun tearDown() {
        manager.dispose()
        super.tearDown()
    }

    private fun entry(sql: String, ts: Long) =
        LogSqlEntry(rawSql = sql, restoredSql = sql, params = emptyList(), timestamp = ts)

    /**
     * 泵空 EDT 队列：manager.notifyUi 用 invokeLater 调度刷新，headless 测试必须
     * dispatchAllInvocationEvents 才能让列表刷新执行（invokeAndWait 空任务不够——已实证）。
     */
    private fun waitForEdt() {
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
    }

    fun test勾选去重_既有重复条目立即裁剪() {
        val panel = SqlLogPanel(project, manager)
        try {
            // 用户未勾选时捕获了 4 条重复（真实场景：同一分页查询重复执行）
            val sql = "SELECT * FROM user WHERE status = 'ACTIVE' LIMIT 10"
            repeat(4) { manager.entries.addEntry(entry(sql, 100L * it)) }
            waitForEdt()
            assertEquals("勾选前 4 条重复全部展示", 4, panel.visibleEntryCount)

            // 用户点击勾选「移除重复的sql」
            panel.dedupeCheckBoxForTest.doClick()
            waitForEdt()

            // 已存在条目立即裁剪：4 → 1
            assertEquals("勾选后立即裁剪为 1 条", 1, panel.visibleEntryCount)
            assertEquals("列表只留首条", sql, panel.visibleEntries[0])
            assertEquals("徽章计数 3", 3, manager.entries.dedupeCount(sql))
        } finally {
            panel.dispose()
        }
    }

    fun test勾选去重_不同SQL互不影响() {
        val panel = SqlLogPanel(project, manager)
        try {
            val sqlA = "SELECT * FROM user WHERE id = 1"
            val sqlB = "UPDATE orders SET status = 'PAID' WHERE id = 99"
            repeat(2) { manager.entries.addEntry(entry(sqlA, 100L * it)) }
            repeat(3) { manager.entries.addEntry(entry(sqlB, 200L * it)) }
            waitForEdt()
            assertEquals("勾选前 5 条", 5, panel.visibleEntryCount)

            panel.dedupeCheckBoxForTest.doClick()
            waitForEdt()

            assertEquals("两种 SQL 各留 1 条", 2, panel.visibleEntryCount)
            assertEquals("A 徽章 1", 1, manager.entries.dedupeCount(sqlA))
            assertEquals("B 徽章 2", 2, manager.entries.dedupeCount(sqlB))
        } finally {
            panel.dispose()
        }
    }

    fun test取消勾选_清空徽章计数() {
        val panel = SqlLogPanel(project, manager)
        try {
            val sql = "SELECT COUNT(*) FROM orders"
            repeat(3) { manager.entries.addEntry(entry(sql, 100L * it)) }
            waitForEdt()

            panel.dedupeCheckBoxForTest.doClick()
            waitForEdt()
            assertEquals("勾选后徽章 2", 2, manager.entries.dedupeCount(sql))
            assertEquals("裁剪后 1 条", 1, panel.visibleEntryCount)

            // 用户取消勾选 → 徽章清零（语义：关闭 = 本次累计清零），列表内容不变
            panel.dedupeCheckBoxForTest.doClick()
            waitForEdt()
            assertEquals("取消勾选后徽章清零", 0, manager.entries.dedupeCount(sql))
            assertEquals("列表内容不变", 1, panel.visibleEntryCount)
        } finally {
            panel.dispose()
        }
    }

    fun test去重开启下新重复_徽章计数即时更新() {
        val panel = SqlLogPanel(project, manager)
        try {
            // 用户先勾选去重，再捕获首条 SQL
            panel.dedupeCheckBoxForTest.doClick()
            waitForEdt()
            val sql = "SELECT * FROM user WHERE id = 42"
            manager.entries.addEntry(entry(sql, 0L))
            waitForEdt()
            assertEquals("首条入列", 1, panel.visibleEntryCount)
            assertEquals("初始徽章 0", 0, manager.entries.dedupeCount(sql))

            // 同一 SQL 再执行 2 次（去重窗口内）——徽章必须即时 +1，无需等第三条 SQL
            manager.entries.addEntry(entry(sql, 100L))
            waitForEdt()
            assertEquals("第 1 次重复后徽章即时更新为 1", 1, manager.entries.dedupeCount(sql))

            manager.entries.addEntry(entry(sql, 200L))
            waitForEdt()
            assertEquals("第 2 次重复后徽章即时更新为 2", 2, manager.entries.dedupeCount(sql))

            // 列表始终只有 1 条
            assertEquals("去重开启下列表恒为 1 条", 1, panel.visibleEntryCount)
            assertTrue(panel.visibleEntries[0] == sql)
        } finally {
            panel.dispose()
        }
    }

    fun test去重关闭时重复SQL全展示_再开启累计() {
        val panel = SqlLogPanel(project, manager)
        try {
            val sql = "SELECT * FROM user WHERE id = 7"
            repeat(3) { manager.entries.addEntry(entry(sql, 100L * it)) }
            waitForEdt()
            assertEquals("去重关闭时 3 条全展示", 3, panel.visibleEntryCount)
            assertEquals("关闭时不累计徽章", 0, manager.entries.dedupeCount(sql))

            panel.dedupeCheckBoxForTest.doClick()
            waitForEdt()
            assertEquals("开启后裁剪为 1 条", 1, panel.visibleEntryCount)
            assertEquals("徽章累计 2", 2, manager.entries.dedupeCount(sql))
        } finally {
            panel.dispose()
        }
    }
}
