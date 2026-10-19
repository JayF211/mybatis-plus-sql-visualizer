package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * R31 历史/版本 SQL 对比测试：二级右键「对比选中两条」动作接线断言 + SqlDiffer 逐行 diff /
 * 结构指纹（[LogSqlEntry.structureKey]）比对结论断言。
 *
 * 死代码复活判据：SqlDiffer.computeDiff 经 historyDiffVerdict 获得真实调用方；
 * 结构等价（同指纹）时 [HistoryDiffVerdict.structurallyEquivalent] 为真——
 * 对应 UI 提示「结构等价，仅参数不同」。
 */
class HistorySqlDiffTest : BasePlatformTestCase() {

    private fun entry(sql: String, ts: Long) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = ts,
    )

    // ------------------------------------------------------------------
    // 接线：右键菜单含「对比选中两条」，两条可用、其他数量灰置
    // ------------------------------------------------------------------

    private fun compareAction(panel: HistorySqlLogPanel, hit: LogSqlEntry, selected: List<LogSqlEntry>): HistorySqlLogPanel.CompareSelectedAction =
        panel.buildSqlContextMenu(hit, selected)
            .getChildren(null)
            .filterIsInstance<HistorySqlLogPanel.CompareSelectedAction>()
            .firstOrNull()
            ?: throw AssertionError("右键菜单缺少「对比选中两条」动作（R31 接线断言失败）")

    private fun isEnabled(action: com.intellij.openapi.actionSystem.AnAction): Boolean {
        val event = AnActionEvent.createFromAnAction(action, null, "test", DataContext.EMPTY_CONTEXT)
        action.update(event)
        return event.presentation.isEnabled
    }

    fun test对比动作接线_两条在场且可用() {
        val e1 = entry("SELECT * FROM user WHERE id = 1", 1000L)
        val e2 = entry("SELECT * FROM user WHERE id = 2", 2000L)
        val panel = HistorySqlLogPanel(project)
        panel.refreshForTest()
        try {
            val action = compareAction(panel, e2, listOf(e1, e2))
            assertTrue("恰两条选中时对比动作应可用", isEnabled(action))
        } finally {
            panel.dispose()
        }
    }

    fun test对比动作_单条与三条灰置() {
        val e1 = entry("SELECT * FROM user WHERE id = 1", 1000L)
        val e2 = entry("SELECT * FROM user WHERE id = 2", 2000L)
        val e3 = entry("DELETE FROM log WHERE id = 7", 3000L)
        val panel = HistorySqlLogPanel(project)
        panel.refreshForTest()
        try {
            assertFalse("单条不可对比", isEnabled(compareAction(panel, e1, listOf(e1))))
            assertFalse("三条不可对比", isEnabled(compareAction(panel, e1, listOf(e1, e2, e3))))
        } finally {
            panel.dispose()
        }
    }

    // ------------------------------------------------------------------
    // 结论：SqlDiffer 逐行 diff + 结构指纹
    // ------------------------------------------------------------------

    fun test结构等价_仅参数不同_逐行差异仍被SqlDiffer统计() {
        val old = entry("SELECT * FROM user WHERE id = 1", 1000L)
        val new = entry("SELECT * FROM user WHERE id = 2", 2000L)
        val verdict = historyDiffVerdict(old, new)
        // 字面量参数化后同形状 → 结构等价（UI 据此提示「结构等价，仅参数不同」）
        assertTrue("同结构指纹应判等价", verdict.structurallyEquivalent)
        assertEquals(old.structureKey, new.structureKey)
        // 行级 diff 不为空：一行被改写 = DELETE + INSERT 成对（SqlDiffer 复活的真实调用）
        assertEquals(2, verdict.diffCount)
        assertEquals(1, verdict.deleteCount)
        assertEquals(1, verdict.insertCount)
    }

    fun test结构不同_标识符变更_不等价() {
        val old = entry("SELECT * FROM user WHERE id = 1", 1000L)
        val new = entry("SELECT * FROM user_profile WHERE id = 1", 2000L)
        val verdict = historyDiffVerdict(old, new)
        assertFalse("表名变更（标识符不参数化）应判结构不等价", verdict.structurallyEquivalent)
        assertEquals(old.structureKey != new.structureKey, true)
    }

    fun test时间序_旧左新右() {
        val older = entry("SELECT 1", 1000L)
        val newer = entry("SELECT 2", 2000L)
        // 入参顺序无所谓——结论按时间戳升序排列（旧在左）
        val verdict = historyDiffVerdict(newer, older)
        assertEquals(older.restoredSql, verdict.oldSql)
        assertEquals(newer.restoredSql, verdict.newSql)
    }

    fun test对话框标题_等价与差异两种文案() {
        val equivalent = HistoryDiffVerdict(structurallyEquivalent = true, diffCount = 2, deleteCount = 1, insertCount = 1, oldSql = "a", newSql = "b")
        val differing = HistoryDiffVerdict(structurallyEquivalent = false, diffCount = 3, deleteCount = 2, insertCount = 1, oldSql = "a", newSql = "c")
        val t1 = historyDiffDialogTitle(equivalent)
        val t2 = historyDiffDialogTitle(differing)
        assertTrue("等价标题应含「仅参数不同」提示：$t1", t1.contains("仅参数不同"))
        assertTrue("差异标题应含差异行数：$t2", t2.contains("3"))
        assertFalse("两种标题不应相同", t1 == t2)
    }

    // ------------------------------------------------------------------
    // T-EnhancedCaptureDefaultOn 复盘补测（G-B）：历史页二级右键「跳转到 Mapper XML」显隐规则。
    // 该动作 update 内 isEnabledAndVisible = resolved != null；无 mapperStatement 必须隐藏。
    // 近亲 RecentExecutionsDialogTest 有同规则用例，历史页此前缺对应用例——resolve 纯函数
    // 测试（SqlLogPanelXmlJumpTest）绿盖不住动作 update 的接线（与暂停按钮同族形态）。
    // ------------------------------------------------------------------

    private fun presentationOf(action: com.intellij.openapi.actionSystem.AnAction): com.intellij.openapi.actionSystem.Presentation {
        val event = AnActionEvent.createFromAnAction(action, null, "test", DataContext.EMPTY_CONTEXT)
        action.update(event)
        return event.presentation
    }

    private fun xmlJumpAction(panel: HistorySqlLogPanel, hit: LogSqlEntry, selected: List<LogSqlEntry>): HistorySqlLogPanel.JumpToMapperXmlAction =
        panel.buildSqlContextMenu(hit, selected)
            .getChildren(null)
            .filterIsInstance<HistorySqlLogPanel.JumpToMapperXmlAction>()
            .firstOrNull()
            ?: throw AssertionError("历史右键菜单缺少「跳转到 Mapper XML」动作（T-M55-01 接线断言失败）")

    fun test跳转XML动作_无mapperStatement时隐藏() {
        val panel = HistorySqlLogPanel(project)
        val e = entry("SELECT * FROM user WHERE id = 1", 1000L)
        try {
            val action = xmlJumpAction(panel, e, listOf(e))
            val p = presentationOf(action)
            assertFalse(
                "无 mapperStatement 时历史页「跳转到 Mapper XML」应隐藏（与日志页/最近执行弹框同规则）",
                p.isVisible,
            )
            assertFalse(p.isEnabled)
        } finally {
            panel.dispose()
        }
    }

    fun test跳转XML动作_有mapperStatement但XML不存在_同样隐藏() {
        val panel = HistorySqlLogPanel(project)
        // mapperStatement 在（MP 内置语句 ms.getId() 恒有值）但工程里没有对应 XML → resolve 失败 → 隐藏
        val e = entry("SELECT * FROM user WHERE id = 1", 1000L).copy(
            mapperStatement = "com.demo.mapper.NotExistMapper.selectById",
        )
        try {
            val action = xmlJumpAction(panel, e, listOf(e))
            val p = presentationOf(action)
            assertFalse(
                "XML 文件不存在时「跳转到 Mapper XML」应隐藏（回退「跳转到 Java」，不劣化）",
                p.isVisible,
            )
        } finally {
            panel.dispose()
        }
    }
}
