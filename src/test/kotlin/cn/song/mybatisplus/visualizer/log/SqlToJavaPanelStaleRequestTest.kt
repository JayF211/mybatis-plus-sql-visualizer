package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.execute.DocWriteContext
import cn.song.mybatisplus.visualizer.ui.EntitySelectionDialog
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiClass
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

/**
 * UI010：控制生产后台闭包和 EDT 回调的执行顺序，不 sleep、不模拟解析结果。
 * 队列仅代替线程调度；convert → parse → findCandidates → 回填/弹窗/写文档仍是生产路径。
 * 弹窗宿主截获真实构造的表行与确认回调，不打开 GUI。
 *
 * 反向验证：临时将生产 isCurrent 改为恒 true，本类的过期回调断言必须变红；
 * 单独移除 setResultText 写安全闭包中的复查，延迟写入用例必须变红。
 */
class SqlToJavaPanelStaleRequestTest : BasePlatformTestCase() {
    private lateinit var panel: SqlToJavaPanel
    private val background = mutableListOf<() -> Unit>()
    private val ui = mutableListOf<() -> Unit>()
    private val dialogTables = mutableListOf<List<String>>()
    private val confirmations = mutableListOf<(Map<String, PsiClass?>) -> Unit>()
    private lateinit var originalFormat: String
    private var originalManual = false

    override fun setUp() {
        super.setUp()
        val state = PluginSettings.getInstance().state
        originalFormat = state.reverseWrapperFormat
        originalManual = state.reverseManualEntitySelection
        state.reverseWrapperFormat = PluginSettings.REVERSE_AUTO
        state.reverseManualEntitySelection = true
        panel = SqlToJavaPanel(project)
        panel.backgroundDispatcherForTest = { background.add(it) }
        panel.uiDispatcherForTest = { ui.add(it) }
        panel.entityDialogForTest = { rows, confirm ->
            dialogTables.add(rows.map { requireNotNull(it.tableName) })
            confirmations.add(confirm)
        }
    }

    override fun tearDown() {
        try {
            DocWriteContext.writeAllowedProbe = null
            Disposer.dispose(panel)
            background.clear()
            ui.clear()
            val state = PluginSettings.getInstance().state
            state.reverseWrapperFormat = originalFormat
            state.reverseManualEntitySelection = originalManual
            UIUtil.dispatchAllInvocationEvents()
        } finally {
            super.tearDown()
        }
    }

    private fun start(sql: String = OLD_SQL) {
        panel.inputEditorForTest().setSql(sql)
        panel.convertForTest()
    }

    private fun finishBackground(index: Int = 0) {
        assertTrue("Expected a production background task", background.size > index)
        background.removeAt(index).invoke()
    }

    private fun finishUi(index: Int = 0) {
        assertTrue("Expected a production UI callback", ui.size > index)
        ui.removeAt(index).invoke()
    }

    private fun cache(name: String): Any? = SqlToJavaPanel::class.java.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(panel)
    }

    private fun assertNewResult() {
        val result = panel.resultTextForTest()
        assertTrue(result, result.contains("QueryWrapper<User>"))
        assertTrue(result, result.contains("status"))
        assertTrue(result, result.contains(".having("))
        assertFalse(result, result.contains("UiProbe"))
    }

    fun testCurrentRequestReachesRealDialogAndFallback() {
        start()
        assertEquals("", panel.resultTextForTest())
        finishBackground()
        assertTrue(dialogTables.isEmpty())
        finishUi()
        assertEquals(listOf(listOf("ui_probe")), dialogTables)
        assertTrue(panel.resultTextForTest().contains("QueryWrapper<UiProbe>"))
        // 正对照：有效弹窗确认仍走后台解析和生产渲染。
        confirmations.single()(mapOf(EntitySelectionDialog.MAIN_KEY to null))
        finishBackground()
        finishUi()
        assertTrue(panel.resultTextForTest().contains("UiProbe"))
    }

    fun testQueryToAutoThenEditRejectsOldLookupFromFormatChange() {
        panel.selectFormatForTest(PluginSettings.REVERSE_QUERY)
        start()
        assertTrue(panel.resultTextForTest().contains("UiProbe"))
        assertTrue(background.isEmpty())
        panel.selectFormatForTest(PluginSettings.REVERSE_AUTO)
        assertEquals(1, background.size)
        panel.inputEditorForTest().setSql(NEW_SQL)
        panel.convertForTest()
        finishBackground(1)
        finishUi()
        assertNewResult()
        finishBackground()
        finishUi()
        assertEquals(listOf(listOf("user")), dialogTables)
        assertNewResult()
    }

    fun testEditDropsAlreadyQueuedOldDialogAndCandidateCache() {
        start()
        finishBackground()
        panel.inputEditorForTest().setSql(NEW_SQL)
        finishUi()
        assertTrue(dialogTables.isEmpty())
        assertEquals("", panel.resultTextForTest())
        assertNull(cache("cacheText"))
        assertNull(cache("cacheCandidates"))
    }

    fun testNewBackgroundCompletesBeforeOldWithoutOldPopupOrOverwrite() {
        start()
        start(NEW_SQL)
        finishBackground(1)
        finishUi()
        assertNewResult()
        val result = panel.resultTextForTest()
        val status = panel.statusForTest()
        finishBackground()
        finishUi()
        assertEquals(listOf(listOf("user")), dialogTables)
        assertEquals(result, panel.resultTextForTest())
        assertEquals(status, panel.statusForTest())
        assertEquals(NEW_SQL, cache("cacheText"))
        // 后续形态复用缓存也只能属于 user，不能串回旧 SQL。
        panel.selectFormatForTest(PluginSettings.REVERSE_LAMBDA)
        assertTrue(background.isEmpty())
        assertNewResult()
    }

    fun testAutomaticRenderWithoutDialogAlsoRejectsOldCompletion() {
        PluginSettings.getInstance().state.reverseManualEntitySelection = false
        start()
        start(NEW_SQL)
        finishBackground(1)
        finishUi()
        assertNewResult()
        val result = panel.resultTextForTest()
        finishBackground()
        finishUi()
        assertEquals(result, panel.resultTextForTest())
        assertTrue(dialogTables.isEmpty())
        assertEquals(NEW_SQL, cache("cacheText"))
    }

    fun testDeferredRenderDispatchCannotRunAfterNewSynchronousQuery() {
        PluginSettings.getInstance().state.reverseManualEntitySelection = false
        start()
        finishBackground()
        DocWriteContext.writeAllowedProbe = { false }
        val before = DocWriteContext.deferredCount.get()
        finishUi()
        assertTrue(DocWriteContext.deferredCount.get() > before)
        assertEquals("", panel.resultTextForTest())
        DocWriteContext.writeAllowedProbe = { true }
        panel.inputEditorForTest().setSql(NEW_SQL)
        panel.selectFormatForTest(PluginSettings.REVERSE_QUERY)
        assertNewResult()
        val status = panel.statusForTest()
        UIUtil.dispatchAllInvocationEvents()
        assertNewResult()
        assertEquals(status, panel.statusForTest())
    }

    fun testNewUiCallbackCompletesBeforeOldUiCallback() {
        start()
        finishBackground()
        start(NEW_SQL)
        finishBackground()
        finishUi(1)
        assertNewResult()
        finishUi()
        assertEquals(listOf(listOf("user")), dialogTables)
        assertNewResult()
    }

    fun testLaterRequestWithIdenticalInputStillSupersedesOldRequest() {
        start()
        panel.convertForTest()
        finishBackground(1)
        finishUi()
        finishBackground()
        finishUi()
        assertEquals("Only newest request may open a dialog", 1, dialogTables.size)
    }

    fun testInputChangedAwayAndBackStillInvalidatesOldRequest() {
        start()
        finishBackground()
        panel.inputEditorForTest().setSql(NEW_SQL)
        panel.inputEditorForTest().setSql(OLD_SQL)
        finishUi()
        assertTrue(dialogTables.isEmpty())
        assertEquals("", panel.resultTextForTest())
        assertNull(cache("cacheCandidates"))
    }

    fun testClearedInputDropsOldResult() {
        start()
        finishBackground()
        panel.inputEditorForTest().setSql("")
        finishUi()
        assertTrue(dialogTables.isEmpty())
        assertEquals("", panel.resultTextForTest())
        assertNull(cache("cacheResult"))
    }

    fun testQueryRemainsSynchronousAndAutoPendingResultCannotOverwriteIt() {
        start()
        finishBackground()
        panel.inputEditorForTest().setSql(NEW_SQL)
        panel.selectFormatForTest(PluginSettings.REVERSE_QUERY)
        assertNewResult()
        assertTrue("QUERY must not dispatch entity lookup", background.isEmpty())
        finishUi()
        assertTrue(dialogTables.isEmpty())
        assertNewResult()
        assertNull(cache("cacheCandidates"))
    }

    fun testSettingsFormatChangedAwayAndBackInvalidatesOldRequest() {
        start()
        finishBackground()
        val state = PluginSettings.getInstance().state
        state.reverseWrapperFormat = PluginSettings.REVERSE_QUERY
        panel.refreshFormatFromSettings()
        state.reverseWrapperFormat = PluginSettings.REVERSE_AUTO
        panel.refreshFormatFromSettings()
        finishUi()
        assertTrue(dialogTables.isEmpty())
        assertEquals("", panel.resultTextForTest())
    }

    fun testOldConfirmationCannotStartWorkAfterInputEdit() {
        start()
        finishBackground()
        finishUi()
        panel.inputEditorForTest().setSql(NEW_SQL)
        confirmations.single()(emptyMap())
        assertTrue("Stale dialog confirmation must not schedule resolution", background.isEmpty())
        assertNull(cache("cacheCandidates"))
    }

    fun testPendingConfirmationResolutionCannotReplaceNewQueryOrCache() {
        start()
        finishBackground()
        finishUi()
        confirmations.single()(emptyMap())
        finishBackground()
        panel.inputEditorForTest().setSql(NEW_SQL)
        panel.selectFormatForTest(PluginSettings.REVERSE_QUERY)
        assertNewResult()
        finishUi()
        assertNewResult()
        assertNull(cache("cacheCandidates"))
        assertEquals(NEW_SQL, cache("cacheText"))
    }

    fun testBatchCallbackCannotReplaceNewSingleQuery() {
        start("SELECT id FROM ui_probe; SELECT id FROM other_probe")
        finishBackground()
        panel.inputEditorForTest().setSql(NEW_SQL)
        panel.selectFormatForTest(PluginSettings.REVERSE_QUERY)
        assertNewResult()
        finishUi()
        assertNewResult()
        assertEquals(NEW_SQL, cache("cacheText"))
    }

    fun testParseFailureSupersedesPendingSuccess() {
        start()
        finishBackground()
        start("SELECT * FROM user WHERE name = 'unterminated")
        val error = panel.statusForTest()
        assertTrue(error.isNotBlank())
        finishUi()
        assertEquals(error, panel.statusForTest())
        assertEquals("", panel.resultTextForTest())
        assertTrue(dialogTables.isEmpty())
        assertNull(cache("cacheResult"))
    }

    fun testWriteSafeDeferredOldQueryIsRejectedAtActualDocumentWrite() {
        panel.selectFormatForTest(PluginSettings.REVERSE_QUERY)
        DocWriteContext.writeAllowedProbe = { false }
        val before = DocWriteContext.deferredCount.get()
        start()
        assertTrue("Production write gate must defer the old write", DocWriteContext.deferredCount.get() > before)
        assertEquals("", panel.resultTextForTest())
        DocWriteContext.writeAllowedProbe = { true }
        start(NEW_SQL)
        assertNewResult()
        UIUtil.dispatchAllInvocationEvents()
        assertNewResult()
    }

    fun testDisposeDropsQueuedCallbackBeforeAnyEditorOrDialogAccess() {
        start()
        finishBackground()
        Disposer.dispose(panel)
        finishUi()
        assertTrue(dialogTables.isEmpty())
        assertNull(cache("cacheCandidates"))
        assertNull(cache("cacheResult"))
    }

    companion object {
        private const val OLD_SQL = "SELECT \"id\" FROM \"ui_probe\" WHERE \"name\"='alpha'"
        private const val NEW_SQL = "SELECT status, COUNT(*) AS cnt FROM user GROUP BY status HAVING COUNT(*) > 1"
    }
}
