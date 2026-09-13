package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JComponent
import javax.swing.JList

/**
 * SQL 日志/历史列表「冻结根因」防回退守卫（T-TestGuard-06，T-LogListFreeze 家族收口）。
 *
 * EDT 冻结三因叠加（pitfall-sqllogpanel-edt-freeze-html-render / pitfall-swing-html-list-render /
 * project-ide-freeze-20260824）：DefaultListModel 逐条事件风暴 × JLabel.setText("<html>")
 * 逐行 BasicHTML 解析 × fixedCellHeight=-1 可变行高逐条测 preferred size。
 *
 * 红线（T-LogListFreeze-04 定稿语义）：
 *  1. 渲染器必须是本插件 SimpleColoredComponent 系片段通道（JLabel-HTML 通道不允许回潮）；
 *  2. SINGLE_LINE 模式下 fixedCellHeight 必须为正（O(1) 布局快路径）；
 *     KEYWORD_WRAP/FORMATTED 允许 -1 可变行高（条目行数不等），但渲染器须守住红线 1
 *     （多行片段有条目级缓存，无 HTML 解析）。
 *
 * 实现注记：JBList 会把面板设置的渲染器包一层 ExpandedItemListCellRendererWrapper
 * （ExpandableItemsHandler 委托机制），断言前须解包验委托；历史面板的列表挂树时机在
 * 首次进入 SQL 视图之后，行高断言走面板自带的 *ForTest 访问器而非组件树遍历。
 * 渲染器逐项行为已有 SqlLogPanelRendererUiTest / HistoryCellRendererTest 锁定。
 */
class SqlLogListFreezeGuardTest : BasePlatformTestCase() {

    /** 解开平台 ExpandableItems 包装，取真实委托渲染器（wrapper 公开静态 unwrap）。 */
    private fun effectiveRenderer(list: JList<*>): Any? =
        com.intellij.ui.ExpandedItemListCellRendererWrapper.unwrap(list.cellRenderer)

    /** 深度优先收集面板内全部 JList。 */
    private fun allLists(component: JComponent): List<JList<*>> {
        val found = mutableListOf<JList<*>>()
        fun walk(c: java.awt.Component) {
            if (c is JList<*>) found.add(c)
            if (c is JComponent) c.components.forEach { walk(it) }
        }
        walk(component)
        return found
    }

    /** 临时切 SINGLE_LINE 模式跑断言（结束后恢复），避免依赖用户默认模式。 */
    private fun withSingleLineMode(block: () -> Unit) {
        val settings = PluginSettings.getInstance()
        val old = settings.state.logListSqlDisplayMode
        settings.state.logListSqlDisplayMode = SqlListDisplayMode.SINGLE_LINE.name
        try {
            block()
        } finally {
            settings.state.logListSqlDisplayMode = old
        }
    }

    fun testSQL日志列表_单行模式固定行高加片段通道渲染器() {
        val manager = SqlLogManager(project)
        val panel = SqlLogPanel(project, manager)
        try {
            // 只认「片段通道渲染器所归属的条目列表」：面板组件树里可能内嵌其他子面板的列表
            // （如历史文件列表用 HistoryFileCellRenderer），不属于本守卫对象。
            // 渲染器若回潮成 JLabel-HTML 通道，本过滤即落空 → 守卫红。
            val entryLists = allLists(panel).filter { effectiveRenderer(it) is LogEntryListCellRenderer }
            assertTrue(
                "面板内应有片段通道（LogEntryListCellRenderer）渲染的 SQL 条目 JList——" +
                    "渲染器回潮即在此暴露；实际: " +
                    allLists(panel).map { effectiveRenderer(it)?.javaClass?.name },
                entryLists.isNotEmpty(),
            )
            // 红线 2：SINGLE_LINE 模式固定行高（O(1) 布局快路径）
            withSingleLineMode {
                panel.applyListCellHeight()
                for (list in entryLists) {
                    assertTrue(
                        "SINGLE_LINE 模式 SQL 日志列表 fixedCellHeight 必须为正" +
                            "（-1 = O(N²) 行高测量冻结回潮），实际 ${list.fixedCellHeight}",
                        list.fixedCellHeight > 0,
                    )
                }
            }
        } finally {
            panel.dispose()
            manager.dispose()
        }
    }

    fun test历史列表_单行模式固定行高() {
        val panel = HistorySqlLogPanel(project)
        try {
            withSingleLineMode {
                panel.applySqlListCellHeight()
                assertTrue(
                    "SINGLE_LINE 模式历史列表 fixedCellHeight 必须为正，实际 ${panel.sqlListFixedCellHeightForTest}",
                    panel.sqlListFixedCellHeightForTest > 0,
                )
            }
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
        }
    }
}
