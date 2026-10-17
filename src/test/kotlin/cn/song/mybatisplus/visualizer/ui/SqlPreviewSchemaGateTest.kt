package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.execute.DataSourceSelector
import cn.song.mybatisplus.visualizer.execute.SchemaValidationGate
import cn.song.mybatisplus.visualizer.execute.SqlExecutor
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.PostgreSQLDialect
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JPanel

/** UI023：只替代 DBN 边界，驱动真实 executeSql、缓存、校验器及 Messages。 */
class SqlPreviewSchemaGateTest : BasePlatformTestCase() {
    private var panel: SqlPreviewPanel? = null
    private var input: Editor? = null
    private val source = Any()
    private val dialogs = mutableListOf<String>()
    private val notifications = mutableListOf<Notification>()
    private var attempts = 0
    private var answer = Messages.NO
    private val sql = "UPDATE user SET usernme='U26_SYNTHETIC' WHERE id=71"

    override fun setUp() {
        super.setUp()
        DatabaseSchemaResolver.invalidateAll()
        DatabaseSchemaResolver.clearNegativeCache()
        DatabaseSchemaResolver.dataSourceDatabaseForTest = { "mp_demo" }
        TestDialogManager.setTestDialog(TestDialog { message -> dialogs += message; answer }, testRootDisposable)
        SqlExecutor.executionAttemptForTest = { attempts++ }
        schemaNotificationForTest = { notifications += it }
        project.getService(RollbackSnapshotStore::class.java).clearForTest()
    }

    override fun tearDown() {
        try {
            schemaNotificationForTest = null
            SqlExecutor.executionAttemptForTest = null
            DataSourceSelector.schemaTargetReaderForTest = null
            DataSourceSelector.preparedEditor = null
            DataSourceSelector.preparedConsole = null
            panel?.let { it.nativeConsoleEditor = null; it.nativeEditorDoc = null; Disposer.dispose(it) }
            input?.let { EditorFactory.getInstance().releaseEditor(it) }
            DatabaseSchemaResolver.dataSourceDatabaseForTest = null
            DatabaseSchemaResolver.invalidateAll()
            DatabaseSchemaResolver.clearNegativeCache()
            project.getService(RollbackSnapshotStore::class.java).clearForTest()
        } finally {
            super.tearDown()
        }
    }

    private fun warm(ds: Any = source, columns: Set<String> = setOf("id", "username")) {
        DatabaseSchemaResolver.resolveFromSources("user", listOf(ds)) { _, _ ->
            DatabaseSchemaResolver.TableMeta(
                "users", columns.associateWith { DatabaseSchemaResolver.ColumnMeta(null, "varchar(64)") },
                allColumnNames = columns,
            )
        }
    }

    private fun preview(text: String = sql, bound: Boolean = true): SqlPreviewPanel {
        val p = SqlPreviewPanel(project)
        panel = p
        // 不调用 showResult，避免无关异步预热；当前模型仍为原 SELECT，验证实际读取手改文档。
        p.current = PreviewResult("SELECT id FROM user WHERE id=1", "SELECT id FROM user WHERE id=1", emptyList(), null, null, tableName = "user")
        val factory = EditorFactory.getInstance()
        val editor = factory.createEditor(factory.createDocument(text), project)
        input = editor
        p.nativeConsoleEditor = editor
        p.nativeEditorDoc = editor.document
        val component = JPanel()
        p.consoleHost.add(component)
        p.embeddedConsole = component
        if (bound) {
            DataSourceSelector.preparedEditor = editor
            DataSourceSelector.preparedConsole = source
            DataSourceSelector.schemaTargetReaderForTest = { SchemaValidationGate.Target(source, "mp_demo") }
        }
        return p
    }

    private fun assertNoSubmission() {
        assertEquals("NO 必须在任一执行入口之前返回", 0, attempts)
        assertEquals("NO 不登记回滚快照", 0, project.getService(RollbackSnapshotStore::class.java).size())
        assertEquals("EMPTY", panel!!.resultPanelForTest().stateForTest())
    }

    fun testWarmedTypoUsesActualMessagesAndNoSubmitsNothing() {
        warm()
        preview().executeSql()
        assertEquals(1, dialogs.size)
        assertTrue(dialogs.single(), dialogs.single().contains("usernme"))
        assertTrue(dialogs.single(), dialogs.single().contains("username"))
        assertTrue(notifications.isEmpty())
        assertNoSubmission()
    }

    fun testColdTargetSkipsWithoutInventingColumnWarning() {
        preview().executeSql()
        assertEquals(listOf(MpSqlVisualizerBundle.message("execute.confirm.dml.message", "UPDATE")), dialogs)
        assertNoSubmission()
    }

    fun testUnboundDoesNotBorrowWarmedSource() {
        warm()
        preview(bound = false).executeSql()
        assertFalse(dialogs.single(), dialogs.single().contains("usernme"))
        assertNoSubmission()
    }

    fun testOtherSourceSameTableDoesNotPopulateTargetCache() {
        warm(Any())
        preview().executeSql()
        assertFalse(dialogs.single(), dialogs.single().contains("usernme"))
        assertNoSubmission()
    }

    fun testTargetColumnsWinOverOtherSourceSameTable() {
        warm(Any(), setOf("id", "usernme"))
        warm()
        preview().executeSql()
        assertTrue(dialogs.single(), dialogs.single().contains("usernme"))
        assertNoSubmission()
    }

    fun testDetachedConsoleDoesNotBorrowPreparedTarget() {
        warm()
        val p = preview()
        p.consoleHost.removeAll()
        p.executeSql()
        assertFalse(dialogs.single(), dialogs.single().contains("usernme"))
        assertNoSubmission()
    }

    fun testPreparedEditorMismatchDoesNotBorrowAnotherPreviewTarget() {
        warm()
        val p = preview()
        DataSourceSelector.preparedEditor = null
        p.executeSql()
        assertFalse(dialogs.single(), dialogs.single().contains("usernme"))
        assertNoSubmission()
    }

    fun testChangedDatabaseDoesNotBorrowOriginalDatabaseCache() {
        warm()
        val p = preview()
        DataSourceSelector.schemaTargetReaderForTest = { SchemaValidationGate.Target(source, "other_db") }
        p.executeSql()
        assertFalse(dialogs.single(), dialogs.single().contains("usernme"))
        assertNoSubmission()
    }

    fun testComplexSubquerySkipsSchemaWarningButKeepsWriteConfirmation() {
        warm()
        preview("UPDATE user SET usernme=(SELECT username FROM user) WHERE id=71").executeSql()
        assertEquals(1, dialogs.size)
        assertFalse(dialogs.single(), dialogs.single().contains("usernme"))
        assertNoSubmission()
    }

    fun testExplicitOtherDatabaseSkipsSchemaWarning() {
        warm()
        preview("UPDATE other_db.user SET usernme='x' WHERE id=71").executeSql()
        assertFalse(dialogs.single(), dialogs.single().contains("usernme"))
        assertNoSubmission()
    }

    fun testQueryNotifiesWithoutWriteDialogAndStillReachesExecution() {
        warm()
        preview("SELECT usernme FROM user WHERE id=71").executeSql()
        assertTrue(dialogs.isEmpty())
        assertEquals(1, notifications.size)
        assertEquals(NotificationType.WARNING, notifications.single().type)
        assertEquals(MpSqlVisualizerBundle.message("schema.check.notification.title"), notifications.single().title)
        assertTrue(notifications.single().content.contains("usernme"))
        assertTrue(notifications.single().content.contains("username"))
        assertEquals("查询轻提示不得阻断", 1, attempts)
    }

    fun testNoSubmissionProbeHasPositiveControl() {
        answer = Messages.YES
        preview("UPDATE user SET username='x' WHERE id=71").executeSql()
        assertEquals(1, dialogs.size)
        assertEquals("同一提交探针在YES路径必须可达", 1, attempts)
    }

    fun testCurrentEffectiveDialectStillFeedsWriteConfirmation() {
        val p = preview("DELETE FROM user WHERE id=71 LIMIT 1", bound = false)
        p.current = p.current!!.copy(renderedDialect = PostgreSQLDialect)
        p.executeSql()
        assertEquals(1, dialogs.size)
        assertTrue(dialogs.single(), dialogs.single().contains("PostgreSQL"))
        assertNoSubmission()
    }
}
