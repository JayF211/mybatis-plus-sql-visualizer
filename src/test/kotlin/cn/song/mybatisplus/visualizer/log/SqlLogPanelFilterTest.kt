package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * SqlLogPanel 过滤/搜索/排序集成测试（2026-08-27 筛选项精简改造，覆盖管线逻辑）。
 *
 * 测试策略：核心管线抽为顶层纯函数（filterByType / filterBySearch），直接注入条目断言；
 * 排序委托 LogDurationParser（已有独立单测）。原高频折叠测试随折叠功能移除而删除。
 */
class SqlLogPanelFilterTest : BasePlatformTestCase() {

    private lateinit var manager: SqlLogManager

    override fun setUp() {
        super.setUp()
        manager = SqlLogManager(project)
        // 管线测试需要重复 SQL 场景，关闭去重避免相同 SQL 被丢弃
        manager.entries.dedupeEnabled = false
    }

    override fun tearDown() {
        manager.dispose()
        super.tearDown()
    }

    /** 构造指定类型的 LogSqlEntry。 */
    private fun entryOfType(type: StatementType, sql: String? = null, ts: Long = 0L): LogSqlEntry {
        val actualSql = sql ?: when (type) {
            StatementType.SELECT -> "SELECT * FROM t"
            StatementType.INSERT -> "INSERT INTO t VALUES (1)"
            StatementType.UPDATE -> "UPDATE t SET x = 1"
            StatementType.DELETE -> "DELETE FROM t"
            StatementType.OTHER -> "TRUNCATE TABLE t"
        }
        return LogSqlEntry(rawSql = actualSql, restoredSql = actualSql, params = emptyList(), timestamp = ts)
    }

    // ---------- 类型过滤（filterByType 纯函数） ----------

    fun testFilterSelectOnly() {
        val entries = listOf(
            entryOfType(StatementType.SELECT, "SELECT 1"),
            entryOfType(StatementType.INSERT, "INSERT 1"),
            entryOfType(StatementType.UPDATE, "UPDATE 1"),
        )
        val filtered = filterByType(entries, setOf(StatementType.SELECT))
        assertEquals(listOf("SELECT 1"), filtered.map { it.restoredSql })
    }

    fun testFilterMultiType() {
        val entries = listOf(
            entryOfType(StatementType.SELECT, "SELECT 1"),
            entryOfType(StatementType.INSERT, "INSERT 1"),
            entryOfType(StatementType.UPDATE, "UPDATE 1"),
            entryOfType(StatementType.DELETE, "DELETE 1"),
        )
        val filtered = filterByType(entries, setOf(StatementType.SELECT, StatementType.UPDATE))
        assertEquals(listOf("SELECT 1", "UPDATE 1"), filtered.map { it.restoredSql })
    }

    fun testFilterAllPassesOtherThrough() {
        // 全选（= FILTERABLE_TYPES 全集）时 OTHER 透传——修正原筛选条 OTHER 被隐性过滤的缺陷
        val entries = listOf(
            entryOfType(StatementType.SELECT, "SELECT 1"),
            entryOfType(StatementType.OTHER, "TRUNCATE TABLE t"),
        )
        val filtered = filterByType(entries, FILTERABLE_TYPES.toSet())
        assertEquals(2, filtered.size)
    }

    fun testFilterPartialHidesOther() {
        // 非全选时 OTHER 不透传（不在可筛选集合中）
        val entries = listOf(
            entryOfType(StatementType.SELECT, "SELECT 1"),
            entryOfType(StatementType.OTHER, "TRUNCATE TABLE t"),
        )
        val filtered = filterByType(entries, setOf(StatementType.SELECT))
        assertEquals(listOf("SELECT 1"), filtered.map { it.restoredSql })
    }

    fun testFilterEmptySelectionShowsNothing() {
        val entries = listOf(
            entryOfType(StatementType.SELECT, "SELECT 1"),
            entryOfType(StatementType.INSERT, "INSERT 1"),
        )
        assertTrue(filterByType(entries, emptySet()).isEmpty())
    }

    // ---------- 搜索过滤（filterBySearch 纯函数） ----------

    fun testSearchEmptyQueryPassesAll() {
        val entries = listOf(
            entryOfType(StatementType.SELECT, "SELECT * FROM user"),
            entryOfType(StatementType.SELECT, "SELECT * FROM orders"),
        )
        assertEquals(2, filterBySearch(entries, "").size)
        assertEquals(2, filterBySearch(entries, "   ").size) // 纯空白同空
    }

    fun testSearchCaseInsensitive() {
        val entries = listOf(entryOfType(StatementType.SELECT, "SELECT * FROM user"))
        assertEquals(1, filterBySearch(entries, "USER").size)
        assertEquals(1, filterBySearch(entries, "select").size)
    }

    fun testSearchMatchesParameterValue() {
        // 摘要（restoredSql 首行截断）含参数还原值——按订单号等参数值检索是常见场景
        val entry = LogSqlEntry(
            rawSql = "SELECT * FROM orders WHERE id = ?",
            restoredSql = "SELECT * FROM orders WHERE id = 'NO-20260827-001'",
            params = emptyList(),
            timestamp = 0L,
        )
        assertEquals(1, filterBySearch(listOf(entry), "NO-20260827-001").size)
        assertEquals(0, filterBySearch(listOf(entry), "NO-99999").size)
    }

    fun testSearchCombinedWithTypeFilter() {
        // 过滤 + 搜索叠加：先类型后搜索（与面板 refreshList 顺序一致）
        val entries = listOf(
            entryOfType(StatementType.SELECT, "SELECT * FROM user"),
            entryOfType(StatementType.INSERT, "INSERT INTO user VALUES (1)"),
        )
        val filtered = filterByType(entries, setOf(StatementType.SELECT))
        val searched = filterBySearch(filtered, "user")
        assertEquals(listOf("SELECT * FROM user"), searched.map { it.restoredSql })
    }

    // ---------- 耗时排序（委托 LogDurationParser） ----------

    fun testDurationSortHiddenWithoutDurationInfo() {
        val snapshot = listOf(
            entryOfType(StatementType.SELECT, "SELECT 1"),
            entryOfType(StatementType.SELECT, "SELECT 2"),
        )
        assertFalse(snapshot.any { LogDurationParser.hasDurationInfo(it) })
    }

    fun testDurationSortOrderDesc() {
        val e1 = LogSqlEntry(
            rawSql = "1723456789012|100|statement|SELECT * FROM a",
            restoredSql = "SELECT * FROM a",
            params = emptyList(),
            timestamp = 0L,
        )
        val e2 = LogSqlEntry(
            rawSql = "1723456789012|500|statement|SELECT * FROM b",
            restoredSql = "SELECT * FROM b",
            params = emptyList(),
            timestamp = 0L,
        )
        val e3 = LogSqlEntry(
            rawSql = "1723456789012|300|statement|SELECT * FROM c",
            restoredSql = "SELECT * FROM c",
            params = emptyList(),
            timestamp = 0L,
        )
        val snapshot = listOf(e1, e2, e3)
        val sorted = snapshot.sortedByDescending { LogDurationParser.parse(it) ?: -1L }
        assertEquals("SELECT * FROM b", sorted[0].restoredSql) // 500ms
        assertEquals("SELECT * FROM c", sorted[1].restoredSql) // 300ms
        assertEquals("SELECT * FROM a", sorted[2].restoredSql) // 100ms
    }

    // ---------- 缓冲层去重计数联动（面板徽章数据源） ----------

    fun testDedupeBadgeDataFromBuffer() {
        manager.entries.dedupeEnabled = true
        manager.entries.addEntry(entryOfType(StatementType.SELECT, "SELECT * FROM t WHERE id = 1", ts = 0L))
        manager.entries.addEntry(entryOfType(StatementType.SELECT, "SELECT * FROM t WHERE id = 1", ts = 100L)) // 丢弃
        manager.entries.addEntry(entryOfType(StatementType.SELECT, "SELECT * FROM t WHERE id = 1", ts = 200L)) // 再丢弃
        // 等待 EDT 刷新（面板 UI 监听器经 invokeLater）
        waitForEdt()
        val counts = manager.entries.dedupeCountsSnapshot()
        assertEquals(2, counts[sqlFingerprint("SELECT * FROM t WHERE id = 1")]) // T-Perf-19：键=SQL指纹
    }

    fun testClearResetsBadgeCounts() {
        manager.entries.dedupeEnabled = true
        manager.entries.addEntry(entryOfType(StatementType.SELECT, "SELECT dup", ts = 0L))
        manager.entries.addEntry(entryOfType(StatementType.SELECT, "SELECT dup", ts = 100L)) // 丢弃
        assertEquals(1, manager.entries.dedupeCount("SELECT dup"))
        manager.entries.clear()
        assertEquals(0, manager.entries.dedupeCount("SELECT dup"))
    }

    // ---------- PluginSettings 默认值（改造后残留状态检查） ----------

    fun testPluginSettingsDefaultsAfterCleanup() {
        // 去重开关在 buffer 层（SqlLogEntryBuffer.dedupeEnabled 默认 true，T-M31.13），
        // 不再断言 PluginSettings（筛选条相关字段已随精简改造移除）
        val state = PluginSettings.getInstance().state
        assertFalse("历史自动保存默认关闭", state.logAutoSaveEnabled)
    }

    /** 等待 EDT 调度完成。 */
    private fun waitForEdt() {
        ApplicationManager.getApplication().invokeAndWait { java.lang.Runnable { } }
    }
}
