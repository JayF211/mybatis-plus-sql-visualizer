package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import java.io.File

/**
 * 历史面板预览构建测试（T-HistParam F3/F4；T-ListPreviewCopy 格式化跟随设置）：
 * openPreview 的 PreviewResult 构建路径（[HistorySqlLogPanel.buildPreviewResult]，internal 缝不弹窗）——
 * v2 带参条目参数行与日志页一致；字面量条目走 F4 恢复通道；
 * formatSql 跟随「预览时格式化还原 SQL」（logFormatSql）：关闭=原文（识别正交），开启=多行格式化。
 */
class HistorySqlLogPanelPreviewTest : BasePlatformTestCase() {

    private val historyDir: File
        get() = File(project.basePath!!, ".idea${File.separator}${SqlLogAutoSaver.DATA_DIR_NAME}${File.separator}sql-history")

    private var recoveryWasOn: Boolean? = null
    private var formatWasOn: Boolean? = null

    override fun setUp() {
        super.setUp()
        historyDir.mkdirs()
        historyDir.listFiles()?.forEach { it.delete() }
        // 应用级设置跨测试用例共享：显式钉住开关并记录原值，测后还原
        recoveryWasOn = PluginSettings.getInstance().state.literalParamRecovery
        PluginSettings.getInstance().state.literalParamRecovery = true
        // T-ListPreviewCopy：buildPreviewResult 改读 logFormatSql——识别断言钉关闭态（与格式化正交）
        formatWasOn = PluginSettings.getInstance().state.logFormatSql
        PluginSettings.getInstance().state.logFormatSql = false
    }

    override fun tearDown() {
        recoveryWasOn?.let { PluginSettings.getInstance().state.literalParamRecovery = it }
        formatWasOn?.let { PluginSettings.getInstance().state.logFormatSql = it }
        super.tearDown()
    }

    private fun writeHistoryFile(name: String, content: String): File {
        val f = File(historyDir, name)
        f.writeText(content)
        return f
    }

    fun testV2带参条目预览构建参数行与日志页一致() {
        val payload = SqlLogParamCodec.encode(
            "SELECT * FROM user WHERE id = ?",
            listOf(LogSqlParam("42", "Long")),
        )
        writeHistoryFile(
            "sql-history-2026-09-11.sql",
            "-- source: UserMapper.java:10 | time: 3ms | 2026-09-11 09:00:00.000 --\n" +
                "-- pd: $payload --\n" +
                "SELECT * FROM user WHERE id = 42\n\n",
        )

        val panel = HistorySqlLogPanel(project)
        panel.refreshForTest() // F87 懒首扫：构造不再自动扫盘，测试显式触发（同步路径）
        try {
            val fileEntry = panel.fileListEntries.single()
            panel.openFileForTest(fileEntry)
            val entry = panel.sqlListEntries.single()
            assertEquals("读回条目 params 齐全", listOf(LogSqlParam("42", "Long")), entry.params)

            val result = panel.buildPreviewResult(entry)
            assertEquals("参数行来源与日志页一致", listOf("param1"), result.unresolvedParams)
            assertEquals(mapOf("param1" to "42"), result.initialParamValues)
            assertNull("识别成功无警告", result.warning)
        } finally {
            panel.dispose()
        }
    }

    fun test字面量条目预览构建走恢复通道() {
        writeHistoryFile(
            "sql-history-2026-09-11.sql",
            "-- source: OrderMapper.java:5 | time: 2ms | 2026-09-11 09:00:00.000 --\n" +
                "SELECT * FROM tableA WHERE (\"id\" = '3251')\n\n",
        )

        val panel = HistorySqlLogPanel(project)
        panel.refreshForTest() // F87 懒首扫：构造不再自动扫盘，测试显式触发（同步路径）
        try {
            panel.openFileForTest(panel.fileListEntries.single())
            val entry = panel.sqlListEntries.single()

            val result = panel.buildPreviewResult(entry)
            assertEquals("字面量原位改写为 ?", "SELECT * FROM tableA WHERE (\"id\" = ?);", result.sql)
            assertEquals(listOf("param1"), result.unresolvedParams)
            assertEquals(mapOf("param1" to "3251"), result.initialParamValues)
            assertTrue(result.paramHints.single().quote == true)
        } finally {
            panel.dispose()
        }
    }

    fun test历史二级真实鼠标监听只提示不自动预览() {
        writeHistoryFile(
            "sql-history-2026-09-16.sql",
            "-- source: A.java:1 | time: 1ms | 2026-09-16 09:00:00.000 --\nSELECT 1\n\n",
        )
        val settings = PluginSettings.getInstance()
        val before = settings.state
        val panel = HistorySqlLogPanel(project)
        panel.refreshForTest()
        try {
            settings.loadState(before.copy(sqlListPreviewHintSeen = false))
            panel.openFileForTest(panel.fileListEntries.single())
            val list = panel.sqlListForTest
            list.fixedCellHeight = 20
            list.setSize(320, 200)
            list.selectedIndex = 0
            val event = java.awt.event.MouseEvent(list, java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, 5, 10, 1, false, java.awt.event.MouseEvent.BUTTON1)
            list.mouseListeners.forEach { it.mouseClicked(event) }
            assertTrue(panel.previewHostForTest.hintScheduledForTest)
            assertTrue(!panel.previewHostForTest.previewOpenForTest)
            assertNull(panel.dockedPreviewForTest.editorForTest)
        } finally {
            panel.dispose()
            settings.loadState(before)
        }
    }

    fun test历史二级预览随选择更新_返回文件列表清理旧内容() {
        writeHistoryFile(
            "sql-history-2026-09-15.sql",
            "-- source: A.java:1 | time: 1ms | 2026-09-15 09:00:00.000 --\n" +
                "SELECT 1\n\n" +
                "-- source: A.java:2 | time: 1ms | 2026-09-15 09:00:01.000 --\n" +
                "SELECT 2\n\n",
        )
        val panel = HistorySqlLogPanel(project)
        panel.refreshForTest()
        try {
            val actionEvent = com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(
                panel.previewToggleAction, null, com.intellij.openapi.actionSystem.ActionPlaces.TOOLBAR,
                com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT,
            )
            panel.previewToggleAction.update(actionEvent)
            assertFalse("历史文件列表无需单条快速预览图标", actionEvent.presentation.isVisible)
            panel.openFileForTest(panel.fileListEntries.single())
            panel.previewToggleAction.update(actionEvent)
            assertTrue("二级 SQL 列表应显示快速预览图标", actionEvent.presentation.isVisible)
            assertSame(com.intellij.icons.AllIcons.Actions.Preview, actionEvent.presentation.icon)
            val list = panel.sqlListForTest
            list.selectedIndex = 0
            panel.previewToggleAction.setSelected(actionEvent, true)
            assertTrue(com.intellij.openapi.actionSystem.Toggleable.isSelected(panel.previewToggleAction.templatePresentation))
            assertEquals("SELECT 1;", panel.dockedPreviewForTest.displayedSqlForTest)
            list.selectedIndex = 1
            assertEquals("SELECT 2;", panel.dockedPreviewForTest.displayedSqlForTest)
            panel.backToFilesForTest()
            assertTrue(panel.isShowingFiles)
            assertFalse(panel.previewHostForTest.previewOpenForTest)
            assertFalse(com.intellij.openapi.actionSystem.Toggleable.isSelected(panel.previewToggleAction.templatePresentation))
            panel.previewToggleAction.update(actionEvent)
            assertFalse("返回文件列表时快速预览图标应隐藏", actionEvent.presentation.isVisible)
            assertNull(panel.dockedPreviewForTest.displayedSqlForTest)
            panel.openFileForTest(panel.fileListEntries.single())
            panel.previewHostForTest.toggle()
            assertNull("重新进入历史文件时不显示上一文件的 SQL", panel.dockedPreviewForTest.displayedSqlForTest)
        } finally {
            panel.dispose()
        }
    }

    fun test预览构建跟随格式化设置开启时多行显示() {
        // T-ListPreviewCopy：默认开启 logFormatSql → 双击预览多行格式化（与日志 tab 一致）
        PluginSettings.getInstance().state.logFormatSql = true
        writeHistoryFile(
            "sql-history-2026-09-14.sql",
            "-- source: OrderMapper.java:5 | time: 2ms | 2026-09-14 10:00:00.000 --\n" +
                "SELECT t.id,t.name FROM car_device_info t LEFT JOIN station_info st ON (st.station_id = t.id) WHERE (t.id = 1)\n\n",
        )

        val panel = HistorySqlLogPanel(project)
        panel.refreshForTest() // F87 懒首扫：构造不再自动扫盘，测试显式触发（同步路径）
        try {
            panel.openFileForTest(panel.fileListEntries.single())
            val entry = panel.sqlListEntries.single()

            val result = panel.buildPreviewResult(entry)
            assertTrue("开启设置时预览应为多行格式化", result.sql.contains('\n'))
            assertTrue("分号收尾", result.sql.endsWith(";"))
        } finally {
            panel.dispose()
            PluginSettings.getInstance().state.logFormatSql = formatWasOn!!
        }
    }
}
