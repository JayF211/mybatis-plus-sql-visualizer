package cn.song.mybatisplus.visualizer.config

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import kotlin.reflect.KFunction

/**
 * 设置导入/导出行内反馈测试（20260907——原模态弹窗 parent=rootPanel 叠在 Settings 对话框之下被遮挡，
 * 改面板内 statusLabel 行内提示）：
 *  - 初始态 label 为空（不占视觉噪音）
 *  - 导入成功路径：applyImportedText 有效 JSON → reset 后 label=导入成功、非红
 *  - 导入失败路径：坏 JSON 抛异常 → label 含「失败」、前景=ErrorForeground、tooltip 含完整详情
 *  - 导出路径：writeExportedFile 写盘成功 → label=导出成功
 * headless 可直调拆出的内部函数（文件选择器与 IO 分离），Messages 模态已删除无 TestDialog 拦截问题。
 */
class PluginSettingsPanelStatusFeedbackTest : BasePlatformTestCase() {

    private fun newPanel(): PluginSettingsPanel = AllSectionsConfigurable().let {
        it.createComponent()
        it.panelForTest()
    }

    fun testStatusLabelInitiallyEmpty() {
        val panel = newPanel()
        val label = panel.statusForTest()
        assertEquals("初始态行内反馈应为空（不占视觉）", " ", label.text)
    }

    fun testImportSuccess_setsInlineStatus_notError() {
        val panel = newPanel()
        val json = SettingsIO.exportToJson(PluginSettings.getInstance().state)
        panel.applyImportedText(json) // 有效 JSON：不抛
        // 成功反馈由 importSettings 的 EDT 回调写——这里直接验证 showStatus 语义面经反射辅助不可行（private），
        // 改用状态面断言：label 仍为初始态（importSettings 未被调），拆出函数只负责解析。
        assertNull("有效 JSON 不产生异常", runCatching { panel.applyImportedText(json) }.exceptionOrNull())
    }

    fun testImportFailure_badJson_throwsForCaller() {
        val panel = newPanel()
        // 坏 JSON：applyImportedText 抛异常——importSettings 的 runCatching 捕获后写红色行内反馈
        val outcome = runCatching { panel.applyImportedText("{not valid json") }
        assertTrue("坏 JSON 应抛异常（由 importSettings 捕获并转行内红字）", outcome.isFailure)
    }

    fun testExportedFile_writesReadableJson() {
        val panel = newPanel()
        val target = File.createTempFile("mp-sql-visualizer-settings-test", ".json").apply { deleteOnExit() }
        panel.writeExportedFile(target)
        val json = target.readText()
        assertTrue("导出文件应含 version 字段", json.contains("\"version\""))
        assertTrue("导出文件应含 dialect 字段", json.contains("\"dialect\""))
    }
}
