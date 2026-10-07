package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.awt.datatransfer.DataFlavor

/**
 * 「最近执行记录」浏览对话框测试（T-RecentExecDialog O-118 / T-RecentExecMenu O-119）：
 * 夹具形态照 [RollbackSqlDialogTest]——构造不 show（依赖真实屏幕），断言经 ForTest 访问器，
 * finally `doDispose()`；动作态断言用 `AnActionEvent.createFromAnAction(...)` 驱动 `update`
 * （HelpUiTest/SqlPreviewPanelTest 同法）。
 *
 * 锁定契约：
 *  - D86=A 恒弹：空列表也构造成功，状态行显示空态文案（复用 `gutter.popup.recent.empty` 键），三控件禁用；
 *  - D87/D91 显示口径：编辑器**恒为格式化**（[SqlFormatter.format] 同源；「查看原文」已退役）；
 *    两枚图标按钮取日志转 SQL 结果区同款图标（`AllIcons.Actions.ReformatCode` / `AllIcons.Actions.Copy`）；
 *  - T-RecentExecMenu：右键菜单六件在场（文案与日志页同源）、跳转 XML 无 mapperStatement 时隐藏、
 *    复制时间戳受显示开关门禁、屏蔽成功后行即刻移除且规则入库、送页签通道未注册时状态行提示；
 *  - 复用日志列表渲染器 [LogEntryListCellRenderer]；「打开预览」走注入动作且**不关闭**对话框。
 *
 * 预览动作注入录制（不真开 SqlPreviewDialog——headless 不驱真实弹窗）；复制断言走真实剪贴板
 * （UiTextClipboard 收口通道，测试沙盒可用）。
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

    /** 驱动动作 update 并返回事件（断言 presentation 文案/可用/可见）。 */
    private fun drive(action: AnAction): AnActionEvent {
        val event = AnActionEvent.createFromAnAction(action, null, "test", DataContext.EMPTY_CONTEXT)
        action.update(event)
        return event
    }

    private fun actionTexts(dialog: RecentExecutionsDialog): List<String> =
        dialog.menuActionsForTest().map { drive(it).presentation.text }

    // ======================== D86=A：空态也弹对话框 ========================

    fun test空列表构造_状态行空态文案_三控件禁用() {
        val dialog = newDialog(emptyList())
        try {
            assertEquals("空列表条目数应为 0", 0, dialog.entryCountForTest())
            assertEquals(
                "状态行应显示空态文案（复用 gutter 键，不造重复键）",
                MpSqlVisualizerBundle.message("gutter.popup.recent.empty"),
                dialog.statusTextForTest(),
            )
            assertFalse("无选中主按钮应禁用", dialog.openPreviewEnabledForTest())
            assertFalse("空态「格式化」应禁用", dialog.reformatEnabledForTest())
            assertFalse("空态「复制」应禁用", dialog.copyEnabledForTest())
            assertEquals("空态编辑器应为空文本", "", dialog.editorTextForTest())
            dialog.triggerOpenPreviewForTest() // 无动作不抛
        } finally {
            dialog.doDispose()
        }
    }

    // ======================== D87/D91：编辑器恒为格式化 ========================

    /** 多子句 SQL：格式化后必出现换行（未格式化则整条单行——判别性形态断言）。 */
    private val multiClauseSql =
        "SELECT t.id,t.name FROM car_info t LEFT JOIN station s ON s.id = t.id WHERE t.id = 1"

    fun test显示恒为格式化SQL_含换行且异于原文() {
        val dialog = newDialog(listOf(entry(multiClauseSql)))
        try {
            val shown = dialog.editorTextForTest()
            assertNotEquals("显示不应是未格式化的原文", multiClauseSql, shown)
            assertTrue("格式化后主子句应各占一行：\n$shown", shown.contains("\n"))
            assertTrue("应含换行后的 FROM 子句", shown.contains("\nFROM"))
            assertTrue("应含换行后的 WHERE 子句", shown.contains("\nWHERE"))
            assertEquals(
                "显示应与预览同源 formatter 产物一致（含语句结束符）",
                SqlFormatter.format("$multiClauseSql;"),
                shown,
            )
        } finally {
            dialog.doDispose()
        }
    }

    fun test格式化按钮_Copy与ReformatCode同源图标_幂等重排() {
        val dialog = newDialog(listOf(entry(multiClauseSql)))
        try {
            assertEquals(
                "「格式化」按钮图标应与日志转 SQL 结果区格式动作同源",
                AllIcons.Actions.ReformatCode,
                dialog.reformatIconForTest(),
            )
            assertEquals(
                "图标按钮的可见文案走 tooltip",
                MpSqlVisualizerBundle.message("log.recent.dialog.format"),
                dialog.reformatTooltipForTest(),
            )
            val before = dialog.editorTextForTest()
            dialog.triggerReformatForTest()
            assertEquals("重新格式化应幂等（文本不变）", before, dialog.editorTextForTest())
            dialog.triggerReformatForTest()
            assertEquals("连点两次仍幂等", before, dialog.editorTextForTest())
        } finally {
            dialog.doDispose()
        }
    }

    fun test复制_取当前显示文本_状态行反馈() {
        val dialog = newDialog(listOf(entry(multiClauseSql)))
        try {
            assertEquals(
                "「复制」按钮图标应与日志转 SQL 结果区复制动作同源",
                AllIcons.Actions.Copy,
                dialog.copyIconForTest(),
            )
            dialog.triggerCopyForTest()
            assertEquals(
                "复制内容应为当前编辑器显示文本（格式化版）",
                dialog.editorTextForTest(),
                CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor),
            )
            assertEquals(
                "状态行应给复制反馈（复用通用键）",
                MpSqlVisualizerBundle.message("dialog.copy.done"),
                dialog.statusTextForTest(),
            )
        } finally {
            dialog.doDispose()
        }
    }

    // ======================== T-RecentExecMenu：右键菜单六件 ========================

    fun test右键菜单六件在场_文案与日志页同源() {
        val dialog = newDialog(listOf(entry(multiClauseSql)))
        try {
            val actions = dialog.menuActionsForTest()
            assertEquals("菜单应恰为六件（不含删除/方法聚焦/请求聚焦/复制 SQL）", 6, actions.size)
            assertEquals(
                listOf(
                    MpSqlVisualizerBundle.message("log.panel.copyTimestamp"),
                    MpSqlVisualizerBundle.message("log.suppress.action"),
                    MpSqlVisualizerBundle.message("log.panel.sendToReverse"),
                    MpSqlVisualizerBundle.message("logToSql.sendTo"),
                    MpSqlVisualizerBundle.message("log.panel.jumpToJava"),
                    MpSqlVisualizerBundle.message("log.panel.jumpToXml"),
                ),
                actionTexts(dialog),
            )
        } finally {
            dialog.doDispose()
        }
    }

    fun test跳转XML动作_无mapperStatement时隐藏() {
        val dialog = newDialog(listOf(entry(multiClauseSql)))
        try {
            val xmlAction = dialog.menuActionsForTest().last()
            val event = drive(xmlAction)
            assertFalse(
                "无 mapperStatement 时「跳转到 Mapper XML」应隐藏（与日志页同规则，不劣化）",
                event.presentation.isVisible,
            )
            assertFalse(event.presentation.isEnabled)
        } finally {
            dialog.doDispose()
        }
    }

    fun test复制时间戳动作_受显示开关门禁() {
        val state = PluginSettings.getInstance().state
        val saved = state.logTimestampEnabled
        val dialog = newDialog(listOf(entry(multiClauseSql)))
        try {
            // 菜单动作在「右键时」构建（与日志页同款：clip 随构建期设置取值），故每次现取现断言
            state.logTimestampEnabled = true
            assertTrue("显示开关开启时应可用", drive(dialog.menuActionsForTest().first()).presentation.isEnabled)
            state.logTimestampEnabled = false
            assertFalse(
                "显示开关关闭时无片段可复制 → 应置灰（镜像日志页）",
                drive(dialog.menuActionsForTest().first()).presentation.isEnabled,
            )
        } finally {
            state.logTimestampEnabled = saved
            dialog.doDispose()
        }
    }

    fun test屏蔽动作_规则入库且该行即刻移除() {
        val manager = SqlSuppressRuleManager.getInstance()
        val beforeIds = manager.rules.map { it.id }.toSet()
        val entries = listOf(entry("SELECT 1"), entry("SELECT 2"))
        val dialog = newDialog(entries)
        try {
            // 屏蔽第二行（先选中它）
            dialog.selectIndexForTest(1)
            val suppress = dialog.menuActionsForTest()[1]
            suppress.actionPerformed(AnActionEvent.createFromAnAction(suppress, null, "test", DataContext.EMPTY_CONTEXT))

            assertEquals("规则应已入库", beforeIds.size + 1, manager.rules.size)
            assertEquals("D92=A：屏蔽后该行应即刻从弹框移除", 1, dialog.entryCountForTest())
            assertEquals("剩余行应为第一条", entries[0], dialog.selectedEntryForTest())
        } finally {
            // 只清理本用例新增的规则（不动进程内既有规则）
            manager.rules.filter { it.id !in beforeIds }.forEach { manager.removeRule(it.id) }
            dialog.doDispose()
        }
    }

    fun test发送到页签_通道未注册时状态行提示() {
        // 本用例不创建 SqlLogPanel（工具窗口未创建）→ 送页签通道未注册
        val dialog = newDialog(listOf(entry(multiClauseSql)))
        try {
            assertNull(
                "前置：测试环境应无面板注册的送页签通道",
                sqlLogManagerIfCreated(project)?.onOpenEntryInTab,
            )
            val send = dialog.menuActionsForTest()[2]
            send.actionPerformed(AnActionEvent.createFromAnAction(send, null, "test", DataContext.EMPTY_CONTEXT))
            assertEquals(
                "通道未就绪时状态行应提示（不静默）",
                MpSqlVisualizerBundle.message("log.recent.dialog.menuPanelNotReady"),
                dialog.statusTextForTest(),
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
