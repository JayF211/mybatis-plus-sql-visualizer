package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JPanel

/**
 * SQL 日志面板布局 / 兼容性 UI 测试（F-44-5 全插件 UI 测试）：
 *
 * 用户反馈「布局错乱 / 不兼容」——重点锁定工具栏（尤其是 WrapLayout 换行后「SQL类型筛选项消失」缺陷）：
 *  - 工具栏关键组件（SQL类型标签/按钮、去重勾选、帮助/捕捉动作组）构建即存在；
 *  - 默认可见性正确（无耗时不显示排序按钮、搜索条默认隐藏）；
 *  - 窄侧边栏下 WrapLayout 不裁切 SQL类型按钮（按钮 y 落在面板高度内，而非被单行高度裁掉）。
 */
class SqlLogPanelLayoutTest : BasePlatformTestCase() {

    private lateinit var manager: SqlLogManager

    override fun setUp() {
        super.setUp()
        manager = SqlLogManager(project)
        manager.entries.dedupeEnabled = false
    }

    override fun tearDown() {
        manager.dispose()
        super.tearDown()
    }

    private fun waitForEdt() = com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()

    fun test工具栏关键组件存在_默认可见性正确() {
        val panel = SqlLogPanel(project, manager)
        try {
            waitForEdt()
            // 关键组件均存在（构建即无 NPE / 缺 i18n）
            assertNotNull("SQL类型标签应存在", panel.typeFilterLabelForTest)
            assertNotNull("SQL类型按钮应存在", panel.typeFilterButtonForTest)
            assertNotNull("去重勾选框应存在", panel.dedupeCheckBoxForTest)
            assertNotNull("搜索条应存在", panel.searchFieldForTest)
            assertTrue("工具栏动作组应含 ≥5 个动作（捕捉/清空/设置/搜索/帮助）", panel.toolbarGroup.childActionsOrStubs.size >= 5)

            // 默认可见性：排序方式选择器恒可见（默认时间升序）；搜索条默认隐藏；慢查询标签隐藏
            assertEquals("排序方式选择器应恒可见", true, panel.sortComboForTest.isVisible)
            assertEquals("搜索条默认隐藏", false, panel.searchFieldForTest.isVisible)
            assertEquals("默认无慢查询，计数标签应隐藏/为空", false, panel.slowQueryLabelForTest?.isVisible ?: false)
        } finally {
            panel.dispose()
        }
    }

    fun test注入耗时信息_排序方式选择器保持可见() {
        val panel = SqlLogPanel(project, manager)
        try {
            // p6spy 格式带耗时
            manager.entries.addEntry(
                LogSqlEntry(
                    rawSql = "1723456789012|1500|statement|SELECT * FROM t",
                    restoredSql = "SELECT * FROM t",
                    params = emptyList(),
                    timestamp = 1L,
                    executionTimeMs = 1500L,
                ),
            )
            waitForEdt()
            assertEquals("有耗时信息时排序方式选择器仍可见", true, panel.sortComboForTest.isVisible)
            assertEquals("默认排序方式为时间升序", SortMode.TIME_ASC, panel.currentSortMode)
        } finally {
            panel.dispose()
        }
    }

    fun test窄侧边栏_WrapLayout_类型筛选按钮不被裁切() {
        val panel = SqlLogPanel(project, manager)
        try {
            // 选中多个类型 → 按钮文案变长（更易触发换行/裁切）
            panel.applyTypeFilterForTest(setOf(StatementType.SELECT, StatementType.UPDATE))
            waitForEdt()

            // 用生产 WrapLayout 在窄容器里承载真实工具栏组件，验证不裁切
            val narrowWidth = 240
            val tallHeight = 260
            val holder = JPanel(BorderLayout())
            holder.size = Dimension(narrowWidth, tallHeight)
            val left = JPanel(SqlLogPanel.WrapLayout())
            left.add(panel.typeFilterLabelForTest)
            left.add(panel.typeFilterButtonForTest)
            if (panel.sortComboForTest.isVisible) left.add(panel.sortComboForTest)
            // 一个极宽占位件，强制 WrapLayout 折行
            left.add(JButton("W".repeat(50)))
            holder.add(left, BorderLayout.CENTER)
            holder.doLayout()
            waitForEdt()

            val btn = panel.typeFilterButtonForTest
            assertTrue("SQL类型按钮应可见", btn.isVisible)
            assertTrue("SQL类型按钮 y 不应为负（未上移裁切）", btn.y >= 0)
            assertTrue(
                "SQL类型按钮底部不应超出容器高度（未被单行高度裁掉）",
                btn.y + btn.height <= tallHeight,
            )
            // 折行后整行高度应大于单行（按钮高度）→ 证明 WrapLayout 增长而非裁切
            assertTrue("折行后容器高度应大于单行按钮高度", left.height > btn.height)
        } finally {
            panel.dispose()
        }
    }

    fun test切换历史Tab_历史面板正常装配() {
        val panel = SqlLogPanel(project, manager)
        try {
            waitForEdt()
            // 写入一个历史文件 → 构造时即显示历史 tab；否则模拟设置开启
            val dir = java.io.File(
                project.basePath + java.io.File.separator + ".idea" +
                    java.io.File.separator + SqlLogAutoSaver.DATA_DIR_NAME +
                    java.io.File.separator + "sql-history",
            )
            dir.mkdirs()
            java.io.File(dir, "sql-history-2026-08-31.sql").writeText(
                "-- source: A.java:1 | time: 1ms | 2026-08-31 17:30:00.000 --\nSELECT 1\n\n",
            )
            // 重新构建面板以纳入历史 tab（构造期基于 .idea 文件判断）
            panel.dispose()
            val panel2 = SqlLogPanel(project, manager)
            waitForEdt()
            // 切到历史 tab 不抛异常（兼容：历史面板正确装配进 JBTabbedPane）
            val tabCount = (panel2.getComponent(0) as? javax.swing.JTabbedPane)?.tabCount ?: -1
            assertTrue("含历史/转换等 tab，tab 数应 ≥ 3", tabCount >= 3)
            panel2.dispose()
        } finally {
            panel.dispose()
        }
    }
}
