package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * R33 EXPLAIN 结构化渲染视图组件行为测试（渲染判别测试）。
 *
 * 覆盖：MySQL 表格格式 → 面板内只读表格（行列保真）；PG JSON 树 → 可折叠树（模型非空）；
 * 索引建议联动（有建议显示、无建议不占位）；「回到原生控制台」入口显隐与回调。
 */
class ExplainPlanResultViewUiTest : BasePlatformTestCase() {

    private val mysqlHeaders = listOf("id", "select_type", "table", "type", "key", "rows", "Extra")

    private val mysqlRows = listOf(
        listOf("1", "SIMPLE", "tb_user", "ALL", "NULL", "99500", "Using where"),
        listOf("1", "SIMPLE", "tb_order", "ref", "idx_user", "10", ""),
    )

    fun testMySQL表格格式_行列保真() {
        val view = ExplainPlanResultView(
            dialectName = "MySQL",
            dataSourceName = "local@db",
            elapsedMs = 42L,
            raw = McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows),
            format = ExplainOutputFormat.TABLE,
        )
        val model = view.tableModelForTest()
        assertNotNull("TABLE 格式应构建表格模型", model)
        assertEquals(7, model!!.columnCount)
        assertEquals(2, model.rowCount)
        assertEquals("ALL", model.getValueAt(0, 3))
        assertEquals("idx_user", model.getValueAt(1, 4))
    }

    fun testJSON格式_树模型非空且无表格() {
        val json = """[{"Plan": {"Node Type": "Seq Scan", "Relation Name": "t", "Plan Rows": 10000}}]"""
        val view = ExplainPlanResultView(
            dialectName = "PostgreSQL",
            dataSourceName = "pg@local",
            elapsedMs = 12L,
            raw = McpExplainFormatter.RawResult(listOf("QUERY PLAN"), listOf(listOf(json))),
            format = ExplainOutputFormat.JSON,
        )
        val treeModel = view.treeModelForTest()
        assertNotNull("JSON 格式应构建树模型", treeModel)
        assertEquals(1, treeModel!!.getChildCount(treeModel.root))
        assertNull("JSON 格式不应构建表格模型", view.tableModelForTest())
    }

    fun test索引建议_有建议显示_无建议不占位() {
        val withHints = ExplainPlanResultView(
            dialectName = "MySQL",
            dataSourceName = null,
            elapsedMs = 1L,
            raw = McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows),
            format = ExplainOutputFormat.TABLE,
            hints = listOf(
                ExplainIndexAdvisor.Hint("FULL_SCAN", "tb_user", "type=ALL full table scan"),
            ),
        )
        val hintText = withHints.hintsTextForTest()
        assertNotNull("有建议应显示建议条", hintText)
        assertTrue("建议条应含规则名", hintText!!.contains("FULL_SCAN"))
        assertTrue("建议条应含目标对象", hintText.contains("tb_user"))

        val noHints = ExplainPlanResultView(
            dialectName = "MySQL",
            dataSourceName = null,
            elapsedMs = 1L,
            raw = McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows),
            format = ExplainOutputFormat.TABLE,
        )
        assertFalse("无建议不占位", noHints.hintsComponentForTest()?.isVisible ?: false)
    }

    fun test回到原生控制台入口_显隐与回调() {
        var clicked = 0
        val view = ExplainPlanResultView(
            dialectName = "MySQL",
            dataSourceName = null,
            elapsedMs = 1L,
            raw = McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows),
            format = ExplainOutputFormat.TABLE,
            onBackToConsole = { clicked++ },
        )
        val button = view.backButtonForTest()
        assertNotNull(button)
        assertTrue(button!!.isVisible)
        button.doClick()
        assertEquals(1, clicked)

        val noEntry = ExplainPlanResultView(
            dialectName = "MySQL",
            dataSourceName = null,
            elapsedMs = 1L,
            raw = McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows),
            format = ExplainOutputFormat.TABLE,
        )
        val hidden = noEntry.backButtonForTest()
        assertNotNull(hidden)
        assertFalse("无回退动作时入口不可见（保守增量：不挂死按钮）", hidden!!.isVisible)
    }

    fun test头部含方言与数据源() {
        val view = ExplainPlanResultView(
            dialectName = "MariaDB",
            dataSourceName = "prod@10.0.0.3",
            elapsedMs = 88L,
            raw = McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows),
            format = ExplainOutputFormat.TABLE,
        )
        val header = view.headerTextForTest()
        assertNotNull(header)
        assertTrue("头部应含方言", header!!.contains("MariaDB"))
        assertTrue("头部应含数据源名", header.contains("prod@10.0.0.3"))
    }

    fun testR90_任意历史基线切换不重新执行SQL() {
        var executed = 0
        fun baseline(rows: String) = cn.song.mybatisplus.visualizer.history.ExplainPlanHistorySession.Baseline(
            rows, McpExplainFormatter.RawResult(mysqlHeaders, listOf(mysqlRows.first().toMutableList().apply { set(5, rows) })),
        )
        val view = ExplainPlanResultView(
            "MySQL", null, 1, McpExplainFormatter.RawResult(mysqlHeaders, listOf(mysqlRows.first())),
            ExplainOutputFormat.TABLE, onBackToConsole = { executed++ },
            baselines = listOf(baseline("100"), baseline("200"), baseline("300")),
            historyStatus = cn.song.mybatisplus.visualizer.history.ExplainPlanSaveResult.SAVED,
        )
        val selector = view.baselineSelectorForTest()
        assertEquals(3, selector.itemCount)
        selector.selectedIndex = 2
        assertTrue(view.compareTextForTest()!!.contains("300"))
        selector.selectedIndex = 1
        assertTrue(view.compareTextForTest()!!.contains("200"))
        assertEquals(0, executed)
        assertEquals("99500", view.tableModelForTest()!!.getValueAt(0, 5))
    }

    fun testR90_超限拒绝明确显示且当前结果不截断() {
        val cell = "x".repeat(270000)
        val view = ExplainPlanResultView("MySQL", null, 1,
            McpExplainFormatter.RawResult(listOf("plan"), listOf(listOf(cell))), ExplainOutputFormat.TABLE,
            historyStatus = cn.song.mybatisplus.visualizer.history.ExplainPlanSaveResult.TOO_LARGE)
        assertTrue(view.historyStatusForTest().contains("256 KiB"))
        assertEquals(cell, view.tableModelForTest()!!.getValueAt(0, 0))
        assertFalse(view.baselineSelectorForTest().isVisible)
    }

    // ==== R61：跨次对比条（照抄索引建议条形态——null 不占位、有内容才可见） ====

    fun testR61_无基线无对比条() {
        val view = ExplainPlanResultView(
            dialectName = "MySQL",
            dataSourceName = null,
            elapsedMs = 1L,
            raw = McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows),
            format = ExplainOutputFormat.TABLE,
        )
        assertNull("无 diff 参数不应有对比条", view.compareTextForTest())
    }

    fun testR61_对比条渲染增量与增删行() {
        val diff = ExplainPlanDiff.PlanDiff(
            changed = listOf(
                ExplainPlanDiff.NodeChange(
                    matchKey = "id=1 table=tb_user#0",
                    label = "id=1 table=tb_user (rows=99500)",
                    rowsDelta = ExplainPlanDiff.MetricDelta(99500L, 500L),
                    costDelta = null,
                    fieldChanges = listOf(ExplainPlanDiff.FieldChange("type", "ALL", "ref")),
                ),
            ),
            added = listOf("id=1 table=tb_order (rows=10)"),
            removed = emptyList(),
        )
        val view = ExplainPlanResultView(
            dialectName = "MySQL",
            dataSourceName = null,
            elapsedMs = 1L,
            raw = McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows),
            format = ExplainOutputFormat.TABLE,
            diff = diff,
        )
        val text = view.compareTextForTest()
        assertNotNull("有 diff 应有对比条", text)
        assertTrue("应含节点标签", text!!.contains("id=1 table=tb_user"))
        assertTrue("应含行数增量（旧值）", text.contains("99500"))
        assertTrue("应含行数增量（新值）", text.contains("500"))
        assertTrue("应含字段变化", text.contains("type"))
        assertTrue("应含新增行", text.contains("tb_order"))
        // 渲染行清单本体（bundle 文案 + Locale.ROOT 数字格式）
        val lines = view.renderCompareLines(diff)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("99500"))
        assertTrue(lines[1].contains("tb_order"))
    }

    fun testR61_计划等价对比条显式告知() {
        val view = ExplainPlanResultView(
            dialectName = "MySQL",
            dataSourceName = null,
            elapsedMs = 1L,
            raw = McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows),
            format = ExplainOutputFormat.TABLE,
            diff = ExplainPlanDiff.PlanDiff(emptyList(), emptyList(), emptyList()),
        )
        val text = view.compareTextForTest()
        assertNotNull("等价 diff 也应有对比条（显式无变化）", text)
        assertTrue(text!!.contains("unchanged") || text.contains("无变化"))
    }
}
