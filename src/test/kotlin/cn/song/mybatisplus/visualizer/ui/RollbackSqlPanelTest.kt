package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import javax.swing.JButton

/**
 * 回滚 SQL 面板级测试（T-RollbackSql F3/F4/P2-F5）：工具栏按钮、executeAdhocSql 的
 * 快照登记与执行管线委托（测试环境无 Database 插件 → 全链分支 + Community 静默降级，
 * 仿 T-RefreshDbSteal 面板级测试法）。弹窗视图组装见 RollbackSqlDialogTest。
 */
class RollbackSqlPanelTest : BasePlatformTestCase() {

    private fun newPanel() = SqlPreviewPanel(project)

    private fun store(): RollbackSnapshotStore = project.getService(RollbackSnapshotStore::class.java)

    override fun tearDown() {
        store().clearForTest()
        super.tearDown()
    }

    // ======================== F3：入口位置（T-UiCoord F1 起在「更多」菜单内） ========================

    /**
     * T-UiCoord F1（2026-09-21 用户要求）：【回滚 SQL】不再直显于按钮行，折叠进「更多」菜单**首位**。
     *
     * 本用例由改造前的「按钮存在性」反转而来（原 `testToolbarHasRollbackButton`）——防的是改造被
     * 静默回退：按钮重新直显、或菜单项丢失，两种回退都会让本用例变红。
     */
    fun testRollbackButtonFoldedIntoMoreMenu() {
        val panel = newPanel()
        val rollbackText = MpSqlVisualizerBundle.message("panel.rollbackSql")
        val buttons = panel.toolbarForTest().components.filterIsInstance<JButton>()
        assertNull("按钮行不应再直显【回滚 SQL】", buttons.firstOrNull { it.text == rollbackText })
        assertEquals("「更多」菜单首项应为【回滚 SQL】", rollbackText, panel.moreActionsForTest().firstOrNull())
        panel.dispose()
    }

    // ======================== F4/P2-F5：executeAdhocSql 快照登记 ========================

    fun testExecuteAdhocSqlRegistersSnapshotForUpdate() {
        val panel = newPanel()
        panel.executeAdhocSql("UPDATE user SET name = 'x' WHERE id = 1", true)
        val entry = store().findBySql("UPDATE user SET name = 'x' WHERE id = 1")
        assertNotNull("UPDATE 执行前应登记快照条目", entry)
        assertEquals("user", entry!!.targetTable)
        assertEquals(listOf("name"), entry.setColumns)
        assertEquals("SELECT * FROM user WHERE id = 1;", entry.snapshotSelectSql)
        assertEquals(RollbackSnapshotStore.Status.REGISTERED, entry.status)
        panel.dispose()
    }

    fun testExecuteAdhocSqlRegistersSnapshotForDelete() {
        val panel = newPanel()
        panel.executeAdhocSql("DELETE FROM log WHERE created < '2020-01-01'", true)
        val entry = store().findBySql("DELETE FROM log WHERE created < '2020-01-01'")
        assertNotNull("DELETE 执行前应登记快照条目", entry)
        assertEquals("log", entry!!.targetTable)
        assertTrue("DELETE 无 SET 列", entry.setColumns.isEmpty())
        panel.dispose()
    }

    fun testExecuteAdhocSqlSkipsRegistrationForQuery() {
        val panel = newPanel()
        panel.executeAdhocSql("SELECT * FROM user WHERE id = 1")
        assertEquals("查询类语句不登记快照", 0, store().size())
        panel.dispose()
    }

    fun testExecuteAdhocSqlSkipsRegistrationForUnparsableDml() {
        val panel = newPanel()
        // MERGE 属 DML 关键字但无法解析出目标表——登记无意义，不应注册
        panel.executeAdhocSql("MERGE INTO nothing", true)
        assertEquals("不可解析语句不登记快照", 0, store().size())
        panel.dispose()
    }

    fun testRollbackExecutionRegistersWithParentChain() {
        val panel = newPanel()
        val parent = store().register(
            "UPDATE user SET age = 3 WHERE id = 1", "user",
            "SELECT * FROM user WHERE id = 1;", listOf("age"),
        )
        val rollbackSql = "UPDATE user SET age = 20 WHERE id = 1 AND age = 3"
        panel.executeAdhocSql(rollbackSql, true, parent)

        val entry = store().findBySql(rollbackSql)
        assertNotNull("回滚语句（DML）也应登记", entry)
        assertEquals("回滚登记的 parentExecutionId 应指向被回滚的原执行（dbx rollback_of 语义）",
            parent.executionId, entry!!.parentExecutionId)
        panel.dispose()
    }

    // ======================== F4：执行管线委托（无 Database 插件 → 全链降级） ========================

    fun testExecuteAdhocSqlFullChainFeedsResultPanel() {
        val panel = newPanel()
        panel.executeAdhocSql("UPDATE user SET name = 'x' WHERE id = 1", true)
        // 测试 classpath 无 database jar：全链走 Community 静默降级，onError 回调置结果面板错误态
        assertTrue("结果面板应可见（执行反馈）", panel.resultPanelForTest().isVisible)
        panel.dispose()
    }

    fun testExecuteAdhocSqlNeverStealsOtherPanelsConsole() {
        val panelA = newPanel()
        val console = javax.swing.JPanel()
        panelA.embedConsoleForTest(console)
        assertTrue(panelA.consoleAttachedToMeForTest())

        val panelB = newPanel()
        panelB.executeAdhocSql("UPDATE user SET name = 'x' WHERE id = 1", true)

        // isPrepared 快路径在测试环境恒不可达（无 database 插件），全链分支不触碰他面控制台
        assertTrue("他人控制台不得被抢走", panelA.consoleAttachedToMeForTest())
        assertNull("本面板不得获得控制台", panelB.embeddedConsoleForTest())
        assertFalse(panelB.consoleAttachedToMeForTest())
        panelA.dispose()
        panelB.dispose()
    }

    fun testExecuteAdhocSqlIgnoresBlank() {
        val panel = newPanel()
        panel.executeAdhocSql("   ")
        assertEquals(0, store().size())
        panel.dispose()
    }
}
