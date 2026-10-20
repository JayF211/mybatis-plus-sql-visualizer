package cn.song.mybatisplus.visualizer.share.ui

import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel
import cn.song.mybatisplus.visualizer.ui.showResult
import cn.song.mybatisplus.visualizer.ui.clear
import cn.song.mybatisplus.visualizer.log.toPreviewResult
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import javax.swing.JRadioButton

/**
 * 分享对话框构造与默认态测试（F-44 续 · 补用例大波次）：
 * 此前 0 测试。仅验证**构造即可靠成型**的部分（不触发 doOKAction，避免触及剪贴板/文件选择器/浏览器）：
 *   - 6 个分享选项（Markdown / Markdown 表格 / 可执行 SQL / HTML / R50 诊断报告 Markdown / R50 诊断报告 HTML）
 *     且默认仅选中 Markdown
 *   - 来源输入框（JBTextField）存在
 *   - 首选焦点组件为来源输入框
 * 构造用最小 SqlPreviewPanel（并 dispose 释放内部编辑器，防 teardown 报未释放）。
 */
class ShareDialogTest : BasePlatformTestCase() {

    fun testShardingActual原文只读附注不进入分享或诊断脱敏投影() {
        val panel = SqlPreviewPanel(myFixture.project)
        com.intellij.openapi.util.Disposer.register(testRootDisposable, panel)
        val clipboard = cn.song.mybatisplus.visualizer.testutil.installFakeClipboard(testRootDisposable)
        val parser = cn.song.mybatisplus.visualizer.log.ShardingSphereLogParser()
        parser.feed("Logic SQL: SELECT * FROM ui_order WHERE reference_code = ?")
        parser.feed("Actual SQL: PRIVATE_SHARD ::: SELECT * FROM private_order WHERE reference_code = ? ::: [ROUTE_SECRET_001,true,BOOK,100]")
        parser.closePending()
        val entry = parser.takeEntries().single()
        panel.showResult(entry.toPreviewResult(false, false))
        val text = panel.editorDocumentText()
        assertNotNull(text)
        assertTrue(text!!.contains("?"))
        assertTrue(panel.paramValuesSnapshot().isEmpty())
        val dialog = ShareDialog(myFixture.project, panel)
        val draft = dialog.feedbackDraft(text)
        val manager = cn.song.mybatisplus.visualizer.share.SqlShareManager(myFixture.project)
        val copies = listOf<() -> Unit>(
            { manager.copyAsMarkdown(panel) },
            { manager.copyAsMarkdownTable(panel) },
            { manager.copyExecutableSql(panel) },
        )
        val forbidden = listOf("PRIVATE_SHARD", "private_order", "ROUTE_SECRET", "BOOK,100", "001")
        forbidden.forEach { assertFalse(draft.body.contains(it)) }
        copies.forEach { copy ->
            copy()
            val shared = clipboard.text()!!
            forbidden.forEach { assertFalse("分享不应读取 Actual 原文：$shared", shared.contains(it)) }
            assertTrue(shared.contains("?"))
        }
        panel.clear()
    }

    private fun allComponents(c: java.awt.Component): List<java.awt.Component> {
        val list = mutableListOf(c)
        if (c is java.awt.Container) c.components.forEach { list.addAll(allComponents(it)) }
        return list
    }

    fun test构造_七个分享选项且默认选中Markdown() {
        val panel = SqlPreviewPanel(myFixture.project)
        try {
            val dialog = ShareDialog(myFixture.project, panel)
            // DialogWrapper 的 rootPane/contentPane 仅在 show() 时构建，headless 下用测试缝取中心面板
            val pane = dialog.centerPanelForTest()
            val radios = allComponents(pane).filterIsInstance<JRadioButton>()
            assertEquals("应有 7 个分享选项", 7, radios.size)
            assertTrue(radios.any { it.text == MpSqlVisualizerBundle.message("share.dialog.diagnosticFeedback") })
            val draft = dialog.feedbackDraft("SELECT private_name FROM private_table WHERE password='SENTINEL_SECRET'")
            assertFalse(draft.body.contains("SENTINEL_SECRET"))
            assertFalse(draft.body.contains("private_table"))
            assertNull("新建诊断草稿不能直接取得可发送快照", draft.takeSubmission())
            val ambiguous = dialog.feedbackDraft("SELECT count FROM json WHERE private_flag = TRUE")
            val sqlSection = ambiguous.sections.single { it.id == "sql" }.text
            listOf("count", "json", "private_flag", "TRUE").forEach {
                assertFalse("自动草稿不能泄漏同名标识符/布尔值：$sqlSection", sqlSection.contains(it, ignoreCase = true))
            }
            val selected = radios.filter { it.isSelected }
            assertEquals("默认应仅选中 1 个", 1, selected.size)
            val markdownText = MpSqlVisualizerBundle.message("share.dialog.markdown")
            assertEquals("默认选中的应为 Markdown 选项", markdownText, selected.first().text)
            val fields = allComponents(pane).filterIsInstance<JBTextField>()
            assertEquals("应有 1 个来源输入框", 1, fields.size)
        } finally {
            panel.dispose()
        }
    }

    fun test构造_首选焦点组件为来源输入框() {
        val panel = SqlPreviewPanel(myFixture.project)
        try {
            val dialog = ShareDialog(myFixture.project, panel)
            val focus = dialog.preferredFocusedComponent
            assertNotNull("首选焦点组件不应为 null", focus)
            assertTrue("首选焦点应为来源输入框", focus is JBTextField)
        } finally {
            panel.dispose()
        }
    }
}
