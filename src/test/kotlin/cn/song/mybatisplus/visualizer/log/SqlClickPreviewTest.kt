package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.history.SqlHistoryPanel
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBList
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.awt.Font
import java.awt.Point
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JTextArea

/**
 * 单击 SQL 预览接线测试（T-ClickPreview）：
 *
 * 用户反馈「SQL 日志列表条目多后鼠标悬停卡顿」——根因 = 渲染器 toolTipText = 完整 SQL，
 * 悬停逐行弹气泡 × 可变行高重排。改造为「单击出预览、双击开完整弹窗、悬停零成本」。
 * 二轮反馈：① 点其他地方后未再单击 SQL，预览又出现 → 排程严格绑定"点到真实行"（点击时
 * 捕获该行 SQL，空白区点击立即停表关预览，不借旧选中复活）；② 预览弹窗要可编辑、带复制/
 * 关闭按钮；③ 弹窗不得遮住被点行（锚点 = 行下缘）。
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

    private fun findComponents(root: java.awt.Container, type: Class<out java.awt.Component>): List<java.awt.Component> =
        UIUtil.uiTraverser(root)
            .preOrderDfsTraversal()
            .filter { type.isInstance(it) }
            .toList()

    // ---------- 弹窗构建：可编辑 + 复制/关闭按钮 ----------

    fun test预览弹窗内容等于SQL_可编辑带复制关闭按钮() {
        val sql = "SELECT * FROM user WHERE id = 1;"
        val popup = SqlClickPreview.build(sql)
        try {
            // popup.content 是弹窗包裹层组件（含广告区/边框等），SQL 文本区在包裹层组件树内
            val area = UIUtil.uiTraverser(popup.content)
                .preOrderDfsTraversal()
                .filterIsInstance<JTextArea>()
                .firstOrNull()
            assertTrue("预览内容组件树内应有 SQL 文本区", area != null)
            assertEquals("预览文本应等于传入 SQL", sql, area!!.text)
            assertTrue("文本区应可编辑（用户可直接选择/编辑后复制）", area.isEditable)
            assertTrue("长 SQL 应自动换行", area.lineWrap)
            // 逻辑字体族名（MONOSPACED）按 name 断言；family 会被映射为物理字体（如 Courier New）
            assertEquals("SQL 应等宽字体展示", Font.MONOSPACED, area.font.name)

            val buttons = findComponents(popup.content, JButton::class.java).filterIsInstance<JButton>()
            val tooltips = buttons.map { it.toolTipText }.toSet()
            assertTrue("应含复制按钮（tooltip = i18n 文案）", tooltips.contains(MpSqlVisualizerBundle.message("log.preview.copy")))
            assertTrue("应含关闭按钮（tooltip = i18n 文案）", tooltips.contains(MpSqlVisualizerBundle.message("log.preview.close")))
        } finally {
            popup.cancel()
        }
    }

    fun test复制按钮回调携带当前文本_关闭按钮回调触发() {
        var copied: String? = null
        var closed = false
        val content = SqlClickPreview.buildContent(
            "SELECT 1;",
            copyText = { copied = it },
            close = { closed = true },
        )
        val buttons = findComponents(content, JButton::class.java).filterIsInstance<JButton>()
        val copyButton = buttons.first { it.toolTipText == MpSqlVisualizerBundle.message("log.preview.copy") }
        val closeButton = buttons.first { it.toolTipText == MpSqlVisualizerBundle.message("log.preview.close") }
        copyButton.doClick()
        assertEquals("复制按钮应回调当前文本", "SELECT 1;", copied)
        closeButton.doClick()
        assertTrue("关闭按钮应触发关闭回调", closed)
    }

    fun test复制按钮复制用户编辑后的内容() {
        var copied: String? = null
        val content = SqlClickPreview.buildContent(
            "SELECT 1;",
            copyText = { copied = it },
            close = {},
        )
        val area = findComponents(content, JTextArea::class.java).filterIsInstance<JTextArea>().first()
        area.text = "SELECT edited_column FROM user;" // 模拟用户编辑
        findComponents(content, JButton::class.java).filterIsInstance<JButton>()
            .first { it.toolTipText == MpSqlVisualizerBundle.message("log.preview.copy") }
            .doClick()
        assertEquals("复制应取编辑后的文本", "SELECT edited_column FROM user;", copied)
    }

    // ---------- 交互语义：命中行才排程，空白区不复活 ----------

    fun test单击行排程预览_点空白区取消并隐藏() {
        val list = fixedList("SELECT 1;")
        val cp = SqlClickPreview(list) { i -> withTerminator(list.model.getElementAt(i).restoredSql) }
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
        val cp = SqlClickPreview(list) { i -> list.model.getElementAt(i).restoredSql }
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
