package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.history.SqlHistoryPanel
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBList
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.awt.Point
import javax.swing.DefaultListModel
import javax.swing.JButton

/**
 * 单击 SQL 预览接线测试（T-ClickPreview）：
 *
 * 用户反馈「SQL 日志列表条目多后鼠标悬停卡顿」——根因 = 渲染器 toolTipText = 完整 SQL，
 * 悬停逐行弹气泡 × 可变行高重排。改造为「单击出预览、双击开完整弹窗、悬停零成本」。
 * 二轮反馈：① 点其他地方后未再单击 SQL，预览又出现 → 排程严格绑定"点到真实行"（点击时
 * 捕获该行 SQL，空白区点击立即停表关预览，不借旧选中复活）；② 预览弹窗要可编辑、带复制/
 * 关闭按钮；③ 弹窗不得遮住被点行（锚点 = 行下缘）。
 * 三轮反馈：预览要 SQL 高亮且可编辑（临时改字段值再复制）→ 内容升级为纯文档 + SQL FileType
 * 驱动词法高亮的可编辑编辑器（无 PSI，规避 EDT read access 陷阱；fileType 缺席降级纯文本）。
 *
 * headless 约束：SqlClickPreview.show 依赖真实屏幕（RelativePoint → getLocationOnScreen），
 * 弹窗「构建/内容」与「交互排程/取消/锚点」分别断言；显示行为由 runIde 真机覆盖。
 */
class SqlClickPreviewTest : BasePlatformTestCase() {

    private fun entry(sql: String) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = 1L,
    )

    /** 定高行高列表（getCellBounds/locationToIndex 纯数学计算，headless 可用）。 */
    private fun fixedList(vararg sqls: String): JBList<LogSqlEntry> {
        val model = DefaultListModel<LogSqlEntry>()
        sqls.forEach { model.addElement(entry(it)) }
        return JBList(model).apply {
            fixedCellHeight = 20
            setSize(100, 100)
        }
    }

    private fun releaseEditor(editor: Editor) {
        runCatching { EditorFactory.getInstance().releaseEditor(editor) }
    }

    private fun findButtons(root: java.awt.Container): List<JButton> =
        UIUtil.uiTraverser(root)
            .preOrderDfsTraversal()
            .filterIsInstance<JButton>()
            .toList()

    // ---------- 弹窗构建：SQL 高亮编辑器 + 可编辑 + 复制/关闭按钮 ----------

    fun test预览内容为可编辑SQL编辑器_文本等于还原SQL_带复制关闭按钮() {
        val sql = "SELECT * FROM user WHERE id = 1;"
        val content = SqlClickPreview.buildContent(
            project,
            sql,
            copyText = {},
            close = {},
        )
        try {
            assertEquals("编辑器文档应等于传入 SQL", sql, content.editor.document.text)
            assertFalse("编辑器应可编辑（非 viewer）", content.editor.isViewer)
            assertTrue("长 SQL 应软换行免横向滚动", content.editor.settings.isUseSoftWraps)

            val buttons = findButtons(content.component)
            val tooltips = buttons.map { it.toolTipText }.toSet()
            assertTrue("应含复制按钮（tooltip = i18n 文案）", tooltips.contains(MpSqlVisualizerBundle.message("log.preview.copy")))
            assertTrue("应含关闭按钮（tooltip = i18n 文案）", tooltips.contains(MpSqlVisualizerBundle.message("log.preview.close")))
        } finally {
            releaseEditor(content.editor)
        }
    }

    fun test复制按钮回调携带当前文本_关闭按钮回调触发() {
        var copied: String? = null
        var closed = false
        val content = SqlClickPreview.buildContent(
            project,
            "SELECT 1;",
            copyText = { copied = it },
            close = { closed = true },
        )
        try {
            findButtons(content.component)
                .first { it.toolTipText == MpSqlVisualizerBundle.message("log.preview.copy") }
                .doClick()
            assertEquals("复制按钮应回调当前文本", "SELECT 1;", copied)
            findButtons(content.component)
                .first { it.toolTipText == MpSqlVisualizerBundle.message("log.preview.close") }
                .doClick()
            assertTrue("关闭按钮应触发关闭回调", closed)
        } finally {
            releaseEditor(content.editor)
        }
    }

    fun test复制按钮复制用户编辑后的内容() {
        var copied: String? = null
        val content = SqlClickPreview.buildContent(
            project,
            "SELECT 1;",
            copyText = { copied = it },
            close = {},
        )
        try {
            // 模拟用户临时改字段值（文档写入须 write action）
            WriteCommandAction.runWriteCommandAction(project) {
                content.editor.document.setText("SELECT edited_price FROM user;")
            }
            findButtons(content.component)
                .first { it.toolTipText == MpSqlVisualizerBundle.message("log.preview.copy") }
                .doClick()
            assertEquals("复制应取编辑后的文本", "SELECT edited_price FROM user;", copied)
        } finally {
            releaseEditor(content.editor)
        }
    }

    // ---------- 交互语义：命中行才排程，空白区不复活 ----------

    fun test单击行排程预览_点空白区取消并隐藏() {
        val list = fixedList("SELECT 1;")
        val cp = SqlClickPreview(project, list) { i -> withTerminator(list.model.getElementAt(i).restoredSql) }
        cp.onClick(1, Point(5, 10)) // 落在第 0 行（行高 20）内
        assertTrue("命中行的单击应排程预览", cp.isScheduled)
        assertNull("延迟内不应立即弹窗", cp.currentPopup)

        cp.onClick(1, Point(5, 500)) // 列表空白区（无行）
        assertFalse("空白区点击应立即取消排程（杜绝借旧选中复活预览）", cp.isScheduled)
        assertNull("空白区点击不应残留弹窗", cp.currentPopup)

        cp.onClick(1, Point(5, 10))
        cp.onClick(2, Point(5, 10))
        assertFalse("双击应取消单击预览排程", cp.isScheduled)
        assertNull("双击不应残留预览弹窗", cp.currentPopup)
    }

    fun test锚点在被点行下缘不遮行() {
        val list = fixedList("SELECT 1;", "SELECT 2;")
        val cp = SqlClickPreview(project, list) { i -> list.model.getElementAt(i).restoredSql }
        val anchor = cp.anchorPointFor(0)
        assertNotNull("有效行应给出锚点", anchor)
        assertTrue("锚点应在被点行下缘（不遮行）", anchor!!.y >= 20)
        assertEquals(22, anchor.y) // 行底 20 + 2px 缝
        val anchor1 = cp.anchorPointFor(1)
        assertEquals(42, anchor1!!.y) // 第二行下缘
    }

    // ---------- 三面板接线 ----------

    fun testSqlLogPanel空列表单击不排程_双击关预览() {
        val manager = SqlLogManager(project)
        manager.entries.dedupeEnabled = false
        val panel = SqlLogPanel(project, manager)
        try {
            val cp = panel.clickPreviewForTest
            cp.onClick(1, Point(5, 5))
            assertFalse("无行可命中时单击不应排程预览", cp.isScheduled)
            assertNull(cp.currentPopup)
            cp.onClick(2, Point(5, 5))
            assertFalse(cp.isScheduled)
            assertNull(cp.currentPopup)
        } finally {
            panel.dispose()
        }
    }

    fun testSqlLogPanelDispose后定时器停止() {
        val manager = SqlLogManager(project)
        manager.entries.dedupeEnabled = false
        val panel = SqlLogPanel(project, manager)
        panel.clickPreviewForTest.onClick(1, Point(5, 5))
        panel.dispose()
        assertFalse("面板销毁应停掉预览定时器（防泄漏）", panel.clickPreviewForTest.isScheduled)
    }

    fun testHistorySqlLogPanel空列表单击不排程_双击关预览() {
        val panel = HistorySqlLogPanel(project)
        try {
            val cp = panel.sqlClickPreviewForTest
            cp.onClick(1, Point(5, 5))
            assertFalse(cp.isScheduled)
            assertNull(cp.currentPopup)
            cp.onClick(2, Point(5, 5))
            assertFalse(cp.isScheduled)
            assertNull(cp.currentPopup)
        } finally {
            panel.dispose()
        }
    }

    fun testSqlHistoryPanel空列表单击不排程_双击关预览() {
        val panel = SqlHistoryPanel(project)
        try {
            val cp = panel.clickPreviewForTest
            cp.onClick(1, Point(5, 5))
            assertFalse(cp.isScheduled)
            assertNull(cp.currentPopup)
            cp.onClick(2, Point(5, 5))
            assertFalse(cp.isScheduled)
            assertNull(cp.currentPopup)
        } finally {
            panel.dispose()
        }
    }
}
