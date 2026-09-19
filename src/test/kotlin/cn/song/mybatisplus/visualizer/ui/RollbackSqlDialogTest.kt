package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 回滚视图组装测试（T-RollbackSql F3/P2-F5）：评级横幅分层、编辑器内容、底部动作
 * 启用态、粘贴快照生成回滚的委托链。不调用 show()（依赖真实屏幕），只构造与断言。
 */
class RollbackSqlDialogTest : BasePlatformTestCase() {

    private val panel = lazy { SqlPreviewPanel(project) }

    override fun tearDown() {
        if (panel.isInitialized()) panel.value.dispose()
        super.tearDown()
    }

    private fun newDialog(
        result: RollbackSqlGenerator.RollbackResult,
        kind: String,
        entry: RollbackSnapshotStore.SnapshotEntry? = null,
    ) = RollbackSqlDialog(project, panel.value, result, kind, entry)

    private fun completeInsertResult() = RollbackSqlGenerator.generate(
        "INSERT INTO user (id, name) VALUES (1, 'Tom')",
        listOf("id"),
    )

    private fun unableUpdateResult() = RollbackSqlGenerator.generate(
        "UPDATE user SET age = 3 WHERE id = 1",
    )

    // ======================== Complete ========================

    fun testCompleteResultShowsExecutableView() {
        val result = completeInsertResult()
        val dialog = newDialog(result, "INSERT")
        try {
            assertEquals(RollbackSqlGenerator.Rating.COMPLETE, dialog.currentResult.rating)
            assertEquals(
                MpSqlVisualizerBundle.message("rollback.banner.complete"),
                dialog.bannerTextForTest(),
            )
            assertEquals("编辑器应载入回滚语句", "DELETE FROM user WHERE id = 1;", dialog.sqlTextForTest())
            assertTrue("Complete 应启用执行动作", dialog.executeActionForTest().isEnabled)
            assertTrue(dialog.copyActionForTest().isEnabled)
            assertTrue("INSERT 无快照工作流", dialog.snapshotQueryLabelForTest().text.isEmpty())
        } finally {
            dialog.doDispose()
        }
    }

    // ======================== Unable UPDATE + 快照工作流 ========================

    fun testUnableUpdateShowsSnapshotWorkflowAndDisablesExecute() {
        val result = unableUpdateResult()
        val dialog = newDialog(result, "UPDATE")
        try {
            assertEquals(RollbackSqlGenerator.Rating.UNABLE, dialog.currentResult.rating)
            assertEquals(MpSqlVisualizerBundle.message("rollback.banner.unable"), dialog.bannerTextForTest())
            assertTrue(
                "UPDATE 应展示快照工作流",
                dialog.snapshotQueryLabelForTest().text.contains("SELECT * FROM user WHERE id = 1"),
            )
            assertFalse("无语句不得启用执行动作", dialog.executeActionForTest().isEnabled)
            assertTrue(dialog.sqlTextForTest().isEmpty())
        } finally {
            dialog.doDispose()
        }
    }

    fun testGenerateFromPasteProducesExecutableRollback() {
        val dialog = newDialog(unableUpdateResult(), "UPDATE")
        try {
            // 模拟用户粘贴快照查询结果（DBN 网格 TSV：首行列名）
            dialog.pasteAreaForTest().text = "id\tage\n1\t20"
            dialog.pkFieldForTest().text = "id"
            dialog.generateFromPaste()

            assertEquals(RollbackSqlGenerator.Rating.COMPLETE, dialog.currentResult.rating)
            assertEquals("UPDATE user SET age = 20 WHERE id = 1 AND age = 3;", dialog.sqlTextForTest())
            assertTrue("生成成功后应启用执行动作", dialog.executeActionForTest().isEnabled)
            assertTrue(
                "横幅应转为可执行",
                dialog.bannerTextForTest() == MpSqlVisualizerBundle.message("rollback.banner.complete"),
            )
        } finally {
            dialog.doDispose()
        }
    }

    fun testGenerateFromPasteRejectsNonTsv() {
        val dialog = newDialog(unableUpdateResult(), "UPDATE")
        try {
            dialog.pasteAreaForTest().text = "只有一行无制表符"
            dialog.generateFromPaste()
            assertEquals("解析失败不更新结果", RollbackSqlGenerator.Rating.UNABLE, dialog.currentResult.rating)
            assertFalse(dialog.executeActionForTest().isEnabled)
            assertTrue(dialog.warningsTextForTest().contains(MpSqlVisualizerBundle.message("rollback.snapshot.parseFailed")))
        } finally {
            dialog.doDispose()
        }
    }

    fun testGenerateFromPasteDeleteBackfillInsert() {
        val deleteResult = RollbackSqlGenerator.generate("DELETE FROM user WHERE id = 1")
        val dialog = newDialog(deleteResult, "DELETE")
        try {
            dialog.pasteAreaForTest().text = "id\tname\n1\tTom"
            dialog.generateFromPaste()
            assertEquals(RollbackSqlGenerator.Rating.PARTIAL, dialog.currentResult.rating)
            assertEquals("INSERT INTO user (id, name) VALUES (1, 'Tom');", dialog.sqlTextForTest())
            assertTrue("快照回填执行动作应启用", dialog.executeActionForTest().isEnabled)
        } finally {
            dialog.doDispose()
        }
    }

    // ======================== F6 留痕：已执行横幅 ========================

    fun testExecutedEntryShowsExecutedBanner() {
        val store = project.getService(RollbackSnapshotStore::class.java)
        store.register(
            "UPDATE user SET age = 3 WHERE id = 1", "user",
            "SELECT * FROM user WHERE id = 1;", listOf("age"),
        )
        store.markRollbackExecuted(store.findBySql("UPDATE user SET age = 3 WHERE id = 1")!!)
        // data class 不可变——markRollbackExecuted 后须经 store 重取新副本（status 已置终态）
        val entry = store.findBySql("UPDATE user SET age = 3 WHERE id = 1")
        val dialog = newDialog(unableUpdateResult(), "UPDATE", entry)
        try {
            assertEquals(
                MpSqlVisualizerBundle.message("rollback.banner.executed"),
                dialog.bannerTextForTest(),
            )
        } finally {
            dialog.doDispose()
            store.clearForTest()
        }
    }

    // ======================== Partial ========================

    fun testPartialResultShowsCountCheck() {
        val result = RollbackSqlGenerator.generate("INSERT INTO user (id, name) VALUES (1, 'Tom')")
        val dialog = newDialog(result, "INSERT")
        try {
            assertEquals(RollbackSqlGenerator.Rating.PARTIAL, dialog.currentResult.rating)
            assertEquals(MpSqlVisualizerBundle.message("rollback.banner.partial"), dialog.bannerTextForTest())
            assertTrue(
                "Partial 应带 COUNT 自查提示",
                dialog.warningsTextForTest().contains(MpSqlVisualizerBundle.message("rollback.warn.fullRowPredicate", "user")),
            )
        } finally {
            dialog.doDispose()
        }
    }
}
