package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import java.awt.datatransfer.DataFlavor

/**
 * 「最近执行记录」浏览对话框测试（T-RecentExecDialog O-118）：夹具形态照 [RollbackSqlDialogTest]——
 * 构造不 show（依赖真实屏幕），断言经 ForTest 访问器，finally `doDispose()`。
 *
 * 锁定契约：
 *  - D86=A 恒弹：空列表也构造成功，状态行显示空态文案（复用 `gutter.popup.recent.empty` 键），主按钮禁用；
 *  - D87=A 原文形态 = restoredSql（「查看原文」态精确钉）；D88=A 默认显示格式化 SQL
 *    （[SqlFormatter.format]，与预览同源口径）＋「查看原文 / 格式化」切换＋「复制」取当前显示文本；
 *  - 复用日志列表渲染器 [LogEntryListCellRenderer]（「复用 sql 日志中的 sql 列表」的钉子）；
 *  - 「打开预览」走注入动作且**不关闭**对话框（浏览对比场景）。
 *
 * 预览动作注入录制（不真开 SqlPreviewDialog——headless 不驱真实弹窗；真实链路 LogEntryPreviewOpener
 * 由既有测试与真机走查 V118 覆盖）。复制断言走真实剪贴板（UiTextClipboard 收口通道，测试沙盒可用）。
 */
class RecentExecutionsDialogTest : BasePlatformTestCase() {

    /** 既有用例锁定单行渲染语义（模式相关行为由专区用例管，这里恒单行）。 */
    private var savedDisplayMode: String? = null

    override fun setUp() {
        super.setUp()
        val state = PluginSettings.getInstance().state
        savedDisplayMode = state.logListSqlDisplayMode
        state.logListSqlDisplayMode = "SINGLE_LINE"
    }

    override fun tearDown() {
        PluginSettings.getInstance().state.logListSqlDisplayMode =
            savedDisplayMode ?: PluginSettings.DEFAULT_LOG_LIST_DISPLAY_MODE
        super.tearDown()
    }

    private fun entry(sql: String, restored: String = sql) = LogSqlEntry(
        rawSql = sql,
        restoredSql = restored,
        params = emptyList(),
        timestamp = 1_700_000_000_000L,
        sourceFile = "src/main/java/A.java",
        sourceLine = 42,
    )

    private fun newDialog(
        entries: List<LogSqlEntry>,
        anchorLabel: String? = null,
        onOpen: ((LogSqlEntry) -> Unit)? = null,
    ) = RecentExecutionsDialog(
        project = project,
        entries = entries,
        anchorLabel = anchorLabel,
        onOpenPreview = onOpen ?: {},
    )

    // ======================== D86=A：空态也弹对话框 ========================

    fun test空列表构造_状态行空态文案_主按钮禁用() {
        val dialog = newDialog(emptyList())
        try {
            assertEquals("空列表条目数应为 0", 0, dialog.entryCountForTest())
            assertEquals(
                "状态行应显示空态文案（复用 gutter 键，不造重复键）",
                MpSqlVisualizerBundle.message("gutter.popup.recent.empty"),
                dialog.statusTextForTest(),
            )
            assertFalse("无选中主按钮应禁用", dialog.openPreviewEnabledForTest())
            assertFalse("空态「查看原文/格式化」切换应禁用", dialog.formatToggleEnabledForTest())
            assertFalse("空态「复制」应禁用", dialog.copyEnabledForTest())
            assertEquals("空态编辑器应为空文本", "", dialog.editorTextForTest())
            // 触发主按钮应无动作不抛（okAction 已禁用，防御直达调用）
            dialog.triggerOpenPreviewForTest()
        } finally {
            dialog.doDispose()
        }
    }

    // ======================== D88=A：默认格式化 + 切换 + 复制 ========================

    /** 多子句 SQL：格式化后必出现换行（未格式化则整条单行——判别性形态断言）。 */
    private val multiClauseSql =
        "SELECT t.id,t.name FROM car_info t LEFT JOIN station s ON s.id = t.id WHERE t.id = 1"

    fun test默认显示格式化SQL_含换行且异于原文() {
        val dialog = newDialog(listOf(entry(multiClauseSql)))
        try {
            val shown = dialog.editorTextForTest()
            assertNotEquals("默认不应再是未格式化的原文", multiClauseSql, shown)
            assertTrue("格式化后主子句应各占一行：\n$shown", shown.contains("\n"))
            assertTrue("应含换行后的 FROM 子句", shown.contains("\nFROM"))
            assertTrue("应含换行后的 WHERE 子句", shown.contains("\nWHERE"))
            assertEquals(
                "默认显示应与预览同源 formatter 产物一致",
                SqlFormatter.format("$multiClauseSql;"),
                shown,
            )
            assertEquals(
                "当前为格式化态 → 按钮标签应为「查看原文」",
                MpSqlVisualizerBundle.message("log.recent.dialog.showOriginal"),
                dialog.formatToggleLabelForTest(),
            )
        } finally {
            dialog.doDispose()
        }
    }

    fun test切换查看原文_精确回还原文本_再切回格式化() {
        val dialog = newDialog(listOf(entry(multiClauseSql)))
        try {
            dialog.triggerToggleFormatForTest()
            assertEquals(
                "「查看原文」态应精确等于 restoredSql（D87 原文语义保留）",
                multiClauseSql,
                dialog.editorTextForTest(),
            )
            assertEquals(
                "当前为原文态 → 按钮标签应为「格式化」",
                MpSqlVisualizerBundle.message("log.recent.dialog.format"),
                dialog.formatToggleLabelForTest(),
            )
            dialog.triggerToggleFormatForTest()
            assertEquals(
                "再切回应恢复格式化显示",
                SqlFormatter.format("$multiClauseSql;"),
                dialog.editorTextForTest(),
            )
        } finally {
            dialog.doDispose()
        }
    }

    fun test复制_取当前显示文本_状态行反馈() {
        val dialog = newDialog(listOf(entry(multiClauseSql)))
        try {
            dialog.triggerCopyForTest()
            assertEquals(
                "复制内容应为当前编辑器显示文本（格式化态）",
                dialog.editorTextForTest(),
                CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor),
            )
            assertEquals(
                "状态行应给复制反馈（复用通用键）",
                MpSqlVisualizerBundle.message("dialog.copy.done"),
                dialog.statusTextForTest(),
            )
            // 切原文后再复制 → 取原文形态
            dialog.triggerToggleFormatForTest()
            dialog.triggerCopyForTest()
            assertEquals(
                "原文态复制应取还原原文",
                multiClauseSql,
                CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor),
            )
        } finally {
            dialog.doDispose()
        }
    }

    // ======================== 列表装载与选中切换 ========================

    fun test三条构造_首条默认选中_编辑器载入格式化SQL_状态行计数() {
        val entries = listOf(
            entry("SELECT * FROM user WHERE id = 1", restored = "SELECT * FROM user WHERE id = 1 /* restored */"),
            entry("UPDATE user SET name = 'Tom' WHERE id = 1"),
            entry("DELETE FROM user WHERE id = 2"),
        )
        val dialog = newDialog(entries)
        try {
            assertEquals(3, dialog.entryCountForTest())
            assertEquals("首条应默认选中", entries[0], dialog.selectedEntryForTest())
            assertTrue(
                "编辑器应载入首条 restoredSql 的格式化形态：${dialog.editorTextForTest()}",
                dialog.editorTextForTest().contains("/* restored */") &&
                    dialog.editorTextForTest().contains("\nWHERE"),
            )
            assertTrue(
                "状态行应含计数：${dialog.statusTextForTest()}",
                dialog.statusTextForTest().contains("3"),
            )
            assertTrue("有选中主按钮应可用", dialog.openPreviewEnabledForTest())
        } finally {
            dialog.doDispose()
        }
    }

    fun test选中切换_编辑器换文() {
        val entries = listOf(
            entry("SELECT 1"),
            entry("SELECT 2"),
        )
        val dialog = newDialog(entries)
        try {
            dialog.selectIndexForTest(1)
            assertEquals(entries[1], dialog.selectedEntryForTest())
            assertEquals(
                "选中切换后编辑器应换文为第二条的格式化形态",
                SqlFormatter.format("SELECT 2;"),
                dialog.editorTextForTest(),
            )
        } finally {
            dialog.doDispose()
        }
    }

    // ======================== 复用日志列表渲染器 ========================

    fun test列表渲染器为LogEntryListCellRenderer复用() {
        val dialog = newDialog(listOf(entry("SELECT 1")))
        try {
            val renderer = dialog.cellRendererForTest()
            assertTrue(
                "列表应复用日志面板条目渲染器（同两行形态/类型色条/角标；实际=${renderer?.javaClass?.name}）",
                renderer is LogEntryListCellRenderer,
            )
        } finally {
            dialog.doDispose()
        }
    }

    // ======================== 打开预览：注入录制 + 不关闭 ========================

    fun test打开预览_走注入动作_对话框保持在场() {
        val entries = listOf(entry("SELECT 1"), entry("SELECT 2"))
        val opened = mutableListOf<LogSqlEntry>()
        val dialog = newDialog(entries, onOpen = { opened.add(it) })
        try {
            dialog.triggerOpenPreviewForTest()
            assertEquals("应打开当前选中条目的预览", listOf(entries[0]), opened)
            assertFalse("打开预览后对话框应保持在场（浏览对比场景，不随预览关闭）", dialog.isDisposed)
            // 切换选中再开 → 录制第二条
            dialog.selectIndexForTest(1)
            dialog.triggerOpenPreviewForTest()
            assertEquals(listOf(entries[0], entries[1]), opened)
        } finally {
            dialog.doDispose()
        }
    }

    // ======================== 标题锚点片段 ========================

    fun test标题带锚点片段() {
        val dialog = newDialog(listOf(entry("SELECT 1")), anchorLabel = "UserService.kt:48")
        try {
            assertTrue(
                "标题应含锚点文件名:行号：${dialog.title}",
                dialog.title.contains("UserService.kt:48"),
            )
        } finally {
            dialog.doDispose()
        }
    }

    fun test无锚点标题用无参键() {
        val dialog = newDialog(listOf(entry("SELECT 1")))
        try {
            assertEquals(MpSqlVisualizerBundle.message("log.recent.dialog.title"), dialog.title)
        } finally {
            dialog.doDispose()
        }
    }
}
