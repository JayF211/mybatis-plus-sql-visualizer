package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.history.SqlHistoryPanel
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.awt.Font
import javax.swing.JTextArea
import javax.swing.JComponent

/**
 * 单击 SQL 预览接线测试（T-ClickPreview）：
 *
 * 用户反馈「SQL 日志列表条目多后鼠标悬停卡顿」——根因 = 渲染器 toolTipText = 完整 SQL，
 * 悬停逐行弹气泡 × 可变行高重排。改造为「单击出轻量预览弹窗、双击开完整弹窗、悬停零成本」。
 *
 * headless 约束：SqlClickPreview.show 依赖真实屏幕（RelativePoint → getLocationOnScreen），
 * 弹窗「构建/内容」与「交互排程/取消」分别断言；显示行为由 runIde 真机覆盖。
 *
 * 覆盖面：共享助手（弹窗内容）+ 三个列表（主日志 / 历史 tab 二级 / F-7 历史窗）的
 * 单击排程、双击取消、渲染器 tooltip 移除回归锁。
 */
class SqlClickPreviewTest : BasePlatformTestCase() {

    private fun entry(sql: String) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = 1L,
    )

    // ---------- 共享助手：弹窗构建 ----------

    fun test预览弹窗内容等于SQL_只读等宽可换行() {
        val sql = "SELECT * FROM user WHERE id = 1;"
        val popup = SqlClickPreview.build(sql)
        try {
            // popup.content 是弹窗包裹层组件（含广告区/边框等），SQL 文本区在包裹层组件树内
            val area = com.intellij.util.ui.UIUtil.uiTraverser(popup.content)
                .preOrderDfsTraversal()
                .filterIsInstance<JTextArea>()
                .firstOrNull()
            assertTrue("预览内容组件树内应有 SQL 文本区", area != null)
            assertEquals("预览文本应等于传入 SQL", sql, area!!.text)
            assertFalse("预览应为只读", area.isEditable)
            assertTrue("长 SQL 应自动换行", area.lineWrap)
            // 逻辑字体族名（MONOSPACED）按 name 断言；family 会被映射为物理字体（如 Courier New）
            assertEquals("SQL 应等宽字体展示", Font.MONOSPACED, area.font.name)
        } finally {
            popup.cancel()
        }
    }

    // ---------- 渲染器 tooltip 移除回归锁（卡顿根因） ----------

    fun test主日志渲染器不再设置悬停tooltip() {
        val renderer = LogEntryListCellRenderer()
        val comp = renderer.getListCellRendererComponent(JBList<Any>(), entry("SELECT 1"), 0, false, false) as JComponent
        assertNull("主日志列表条目不应再设悬停 tooltip（逐行弹气泡 = 卡顿根因）", comp.toolTipText)
    }

    fun test历史tab渲染器不再设置悬停tooltip() {
        val renderer = HistoryCellRenderer()
        val comp = renderer.getListCellRendererComponent(JBList<Any>(), entry("SELECT 1"), 0, false, false) as JComponent
        assertNull("历史 tab 二级列表条目不应再设悬停 tooltip", comp.toolTipText)
    }

    // ---------- 三面板接线：单击排程 / 双击取消 ----------

    fun testSqlLogPanel单击排程预览_双击取消() {
        val manager = SqlLogManager(project)
        manager.entries.dedupeEnabled = false
        val panel = SqlLogPanel(project, manager)
        try {
            val cp = panel.clickPreviewForTest
            cp.onClick(1)
            assertTrue("单击后预览应进入排程（300ms 延迟区分双击）", cp.isScheduled)
            assertNull("延迟内不应立即弹窗", cp.currentPopup)
            cp.onClick(2)
            assertFalse("双击应取消单击预览排程", cp.isScheduled)
            assertNull("双击不应残留预览弹窗", cp.currentPopup)
        } finally {
            panel.dispose()
        }
    }

    fun testSqlLogPanelDispose后定时器停止() {
        val manager = SqlLogManager(project)
        manager.entries.dedupeEnabled = false
        val panel = SqlLogPanel(project, manager)
        panel.clickPreviewForTest.onClick(1)
        panel.dispose()
        assertFalse("面板销毁应停掉预览定时器（防泄漏）", panel.clickPreviewForTest.isScheduled)
    }

    fun testHistorySqlLogPanel单击排程预览_双击取消() {
        val panel = HistorySqlLogPanel(project)
        try {
            val cp = panel.sqlClickPreviewForTest
            cp.onClick(1)
            assertTrue(cp.isScheduled)
            assertNull(cp.currentPopup)
            cp.onClick(2)
            assertFalse(cp.isScheduled)
            assertNull(cp.currentPopup)
        } finally {
            panel.dispose()
        }
    }

    fun testSqlHistoryPanel单击排程预览_双击取消() {
        val panel = SqlHistoryPanel(project)
        try {
            val cp = panel.clickPreviewForTest
            cp.onClick(1)
            assertTrue(cp.isScheduled)
            assertNull(cp.currentPopup)
            cp.onClick(2)
            assertFalse(cp.isScheduled)
            assertNull(cp.currentPopup)
        } finally {
            panel.dispose()
        }
    }
}
