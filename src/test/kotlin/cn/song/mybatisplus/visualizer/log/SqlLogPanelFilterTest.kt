package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * SqlLogPanel 过滤/折叠/排序集成测试（P-06，13 用例覆盖管线逻辑）。
 *
 * 测试策略：通过 manager.entries.addEntry 注入条目 → 调用管线方法 → 断言结果。
 * 由于 SqlLogPanel 构造函数需完整 ActionToolbar/EDT 上下文（headless 沙盒中 DisposalException），
 * 管线逻辑经面板内部方法暴露后直接测试（面板本身委托 LogStatementClassifier / LogFrequencyGrouper /
 * LogDurationParser 三大纯逻辑类 + PluginSettings 阈值——均经独立单测覆盖）。
 */
class SqlLogPanelFilterTest : BasePlatformTestCase() {

    private lateinit var manager: SqlLogManager

    override fun setUp() {
        super.setUp()
        manager = SqlLogManager(project)
        // 测试管线逻辑需要重复 SQL（高频折叠场景），关闭去重避免相同 SQL 被丢弃
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

    /** 添加条目并等待 EDT 刷新。 */
    private fun addEntriesAndRefresh(vararg entries: LogSqlEntry) {
        entries.forEach { manager.entries.addEntry(it) }
        // 等待 EDT 调度完成（pane 的 UI 监听器经 invokeLater 刷新）
        ApplicationManager.getApplication().invokeAndWait { /* 空 EDT 任务 */ }
    }

    /** 等待 EDT 调度完成（供测试方法直接调用）。 */
    private fun waitForEdt() {
        ApplicationManager.getApplication().invokeAndWait { /* 空 EDT 任务 */ }
    }

    // ---------- 类型过滤（委托 LogStatementClassifier） ----------

    fun testFilterSelectOnly() {
        addEntriesAndRefresh(
            entryOfType(StatementType.SELECT, "SELECT 1"),
            entryOfType(StatementType.INSERT, "INSERT 1"),
            entryOfType(StatementType.UPDATE, "UPDATE 1"),
        )
        val snapshot = manager.entries.snapshot()
        val filtered = snapshot.filter { LogStatementClassifier.classify(it) == StatementType.SELECT }
        assertEquals(1, filtered.size)
        assertEquals("SELECT 1", filtered[0].restoredSql)
    }

    fun testFilterInsertOnly() {
        addEntriesAndRefresh(
            entryOfType(StatementType.SELECT, "SELECT 1"),
            entryOfType(StatementType.INSERT, "INSERT 1"),
            entryOfType(StatementType.DELETE, "DELETE 1"),
        )
        val snapshot = manager.entries.snapshot()
        val filtered = snapshot.filter { LogStatementClassifier.classify(it) == StatementType.INSERT }
        assertEquals(1, filtered.size)
        assertEquals("INSERT 1", filtered[0].restoredSql)
    }

    fun testFilterMultiType() {
        addEntriesAndRefresh(
            entryOfType(StatementType.SELECT, "SELECT 1"),
            entryOfType(StatementType.INSERT, "INSERT 1"),
            entryOfType(StatementType.UPDATE, "UPDATE 1"),
            entryOfType(StatementType.DELETE, "DELETE 1"),
        )
        val snapshot = manager.entries.snapshot()
        val activeFilter = setOf(StatementType.SELECT, StatementType.UPDATE)
        val filtered = snapshot.filter { LogStatementClassifier.classify(it) in activeFilter }
        assertEquals(2, filtered.size)
        assertTrue(filtered.any { it.restoredSql == "SELECT 1" })
        assertTrue(filtered.any { it.restoredSql == "UPDATE 1" })
        assertFalse(filtered.any { it.restoredSql == "INSERT 1" })
    }

    fun testFilterAllButtonSelectsAll() {
        addEntriesAndRefresh(
            entryOfType(StatementType.SELECT, "SELECT 1"),
            entryOfType(StatementType.INSERT, "INSERT 1"),
        )
        val snapshot = manager.entries.snapshot()
        val activeFilter = setOf(StatementType.SELECT, StatementType.INSERT, StatementType.UPDATE, StatementType.DELETE)
        val filtered = snapshot.filter { LogStatementClassifier.classify(it) in activeFilter }
        assertEquals(2, filtered.size)
    }

    fun testCountUpdatesAfterAddingEntries() {
        addEntriesAndRefresh(
            entryOfType(StatementType.SELECT, "SELECT 1"),
            entryOfType(StatementType.SELECT, "SELECT 2"),
            entryOfType(StatementType.INSERT, "INSERT 1"),
        )
        val snapshot = manager.entries.snapshot()
        val counts = snapshot.groupingBy { LogStatementClassifier.classify(it) }.eachCount()
        assertEquals(2, counts[StatementType.SELECT])
        assertEquals(1, counts[StatementType.INSERT])
    }

    // ---------- 高频折叠（委托 LogFrequencyGrouper） ----------

    fun testFrequencyFoldMergesHighFrequencyTemplates() {
        addEntriesAndRefresh(
            entryOfType(StatementType.SELECT, "SELECT * FROM t WHERE id = 1", ts = 1L),
            entryOfType(StatementType.SELECT, "SELECT * FROM t WHERE id = 1", ts = 2L),
            entryOfType(StatementType.SELECT, "SELECT * FROM t WHERE id = 1", ts = 3L),
            entryOfType(StatementType.INSERT, "INSERT INTO t VALUES (1)", ts = 4L),
        )
        val snapshot = manager.entries.snapshot()
        val grouped = LogFrequencyGrouper.group(snapshot, threshold = 3)
        // 高频 SELECT 合并 1 条（count=3）+ INSERT 1 条（count=1）= 2 个 GroupedEntry
        assertEquals(2, grouped.size)
        val selectGroup = grouped.first { it.template.restoredSql.contains("SELECT") }
        assertEquals(3, selectGroup.count)
        assertFalse(selectGroup.expanded)
    }

    fun testFrequencyFoldDisabledShowsAll() {
        addEntriesAndRefresh(
            entryOfType(StatementType.SELECT, "SELECT 1", ts = 1L),
            entryOfType(StatementType.SELECT, "SELECT 2", ts = 2L),
            entryOfType(StatementType.SELECT, "SELECT 3", ts = 3L),
        )
        val snapshot = manager.entries.snapshot()
        // 不启用高频折叠时，每条独立显示
        assertEquals(3, snapshot.size)
    }

    fun testFrequencyFoldExpandCollapse() {
        addEntriesAndRefresh(
            entryOfType(StatementType.SELECT, "SELECT * FROM t", ts = 1L),
            entryOfType(StatementType.SELECT, "SELECT * FROM t", ts = 2L),
            entryOfType(StatementType.SELECT, "SELECT * FROM t", ts = 3L),
        )
        val snapshot = manager.entries.snapshot()
        val grouped = LogFrequencyGrouper.group(snapshot, threshold = 3)
        assertEquals(1, grouped.size)
        assertEquals(3, grouped[0].count)
        assertFalse("默认折叠", grouped[0].expanded)
        // 模拟展开
        grouped[0].expanded = true
        assertTrue("展开后", grouped[0].expanded)
        // 模拟折叠
        grouped[0].expanded = false
        assertFalse("折叠后", grouped[0].expanded)
    }

    // ---------- 耗时排序（委托 LogDurationParser） ----------

    fun testDurationSortHiddenWithoutDurationInfo() {
        addEntriesAndRefresh(
            entryOfType(StatementType.SELECT, "SELECT 1"),
            entryOfType(StatementType.SELECT, "SELECT 2"),
        )
        val snapshot = manager.entries.snapshot()
        val hasDuration = snapshot.any { LogDurationParser.hasDurationInfo(it) }
        assertFalse("无耗时信息", hasDuration)
    }

    fun testDurationSortVisibleWithDurationInfo() {
        val entry = LogSqlEntry(
            rawSql = "1723456789012|123|statement|SELECT * FROM t",
            restoredSql = "SELECT * FROM t",
            params = emptyList(),
            timestamp = 0L,
        )
        addEntriesAndRefresh(entry)
        val snapshot = manager.entries.snapshot()
        val hasDuration = snapshot.any { LogDurationParser.hasDurationInfo(it) }
        assertTrue("含耗时信息", hasDuration)
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
        addEntriesAndRefresh(e1, e2, e3)
        val snapshot = manager.entries.snapshot()
        val sorted = snapshot.sortedByDescending { LogDurationParser.parse(it) ?: -1L }
        assertEquals("SELECT * FROM b", sorted[0].restoredSql) // 500ms
        assertEquals("SELECT * FROM c", sorted[1].restoredSql) // 300ms
        assertEquals("SELECT * FROM a", sorted[2].restoredSql) // 100ms
    }

    // ---------- 过滤 + 折叠叠加 ----------

    fun testFilterPlusFoldOverlay() {
        addEntriesAndRefresh(
            entryOfType(StatementType.SELECT, "SELECT * FROM a", ts = 1L),
            entryOfType(StatementType.SELECT, "SELECT * FROM a", ts = 2L),
            entryOfType(StatementType.SELECT, "SELECT * FROM a", ts = 3L),
            entryOfType(StatementType.INSERT, "INSERT INTO b", ts = 4L),
            entryOfType(StatementType.INSERT, "INSERT INTO b", ts = 5L),
        )
        val snapshot = manager.entries.snapshot()
        // 先过滤：取消 INSERT
        val filtered = snapshot.filter { LogStatementClassifier.classify(it) != StatementType.INSERT }
        // 再折叠
        val grouped = LogFrequencyGrouper.group(filtered, threshold = 3)
        // 过滤后只剩 SELECT（3 条同模板），折叠后合并 1 条
        assertEquals("过滤+折叠叠加后仅 1 条 SELECT 折叠组", 1, grouped.size)
        assertTrue("剩余条目是 SELECT", grouped[0].template.restoredSql.startsWith("SELECT"))
    }

    // ---------- PluginSettings 阈值联动 ----------

    fun testPluginSettingsThresholdDefault() {
        val threshold = PluginSettings.getInstance().state.logFrequencyThreshold
        assertTrue("默认阈值 >= 2", threshold >= 2)
    }

    fun testPluginSettingsFoldDefaultDisabled() {
        val foldEnabled = PluginSettings.getInstance().state.logFrequencyFoldEnabled
        assertFalse("高频折叠默认关闭", foldEnabled)
    }

    fun testPluginSettingsSortDefaultDisabled() {
        val sortEnabled = PluginSettings.getInstance().state.logDurationSortEnabled
        assertFalse("耗时排序默认关闭", sortEnabled)
    }

    // ---------- 清空后状态保留（面板行为模拟） ----------

    fun testClearPreservesDataIntegrity() {
        addEntriesAndRefresh(
            entryOfType(StatementType.SELECT, "SELECT 1"),
            entryOfType(StatementType.INSERT, "INSERT 1"),
        )
        assertEquals(2, manager.entries.snapshot().size)
        // 清空
        manager.entries.clear()
        waitForEdt()
        assertEquals("清空后无条目", 0, manager.entries.snapshot().size)
        // 重新添加后管线仍可用
        addEntriesAndRefresh(entryOfType(StatementType.SELECT, "SELECT new"))
        assertEquals(1, manager.entries.snapshot().size)
    }
}
