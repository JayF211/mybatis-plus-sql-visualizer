package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        // k2j 适配：具名实参改 7 参位置形态（hints/onBackToConsole 缺省）
        val view = ExplainPlanResultView(
            "MySQL",
            "local@db",
            42L,
            McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows, false),
            ExplainOutputFormat.TABLE,
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
            "PostgreSQL",
            "pg@local",
            12L,
            McpExplainFormatter.RawResult(listOf("QUERY PLAN"), listOf(listOf(json)), false),
            ExplainOutputFormat.JSON,
        )
        val treeModel = view.treeModelForTest()
        assertNotNull("JSON 格式应构建树模型", treeModel)
        assertEquals(1, treeModel!!.getChildCount(treeModel.root))
        assertNull("JSON 格式不应构建表格模型", view.tableModelForTest())
    }

    fun test索引建议_有建议显示_无建议不占位() {
        val withHints = ExplainPlanResultView(
            "MySQL",
            null,
            1L,
            McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows, false),
            ExplainOutputFormat.TABLE,
            listOf(
                ExplainIndexAdvisor.Hint("FULL_SCAN", "tb_user", "type=ALL full table scan"),
            ),
        )
        val hintText = withHints.hintsTextForTest()
        assertNotNull("有建议应显示建议条", hintText)
        assertTrue("建议条应含规则名", hintText!!.contains("FULL_SCAN"))
        assertTrue("建议条应含目标对象", hintText.contains("tb_user"))

        val noHints = ExplainPlanResultView(
            "MySQL",
            null,
            1L,
            McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows, false),
            ExplainOutputFormat.TABLE,
        )
        assertFalse("无建议不占位", noHints.hintsComponentForTest()?.isVisible ?: false)
    }

    fun test回到原生控制台入口_显隐与回调() {
        var clicked = 0
        val view = ExplainPlanResultView(
            "MySQL",
            null,
            1L,
            McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows, false),
            ExplainOutputFormat.TABLE,
            emptyList(),
            { clicked++ },
        )
        val button = view.backButtonForTest()
        assertNotNull(button)
        assertTrue(button!!.isVisible)
        button.doClick()
        assertEquals(1, clicked)

        val noEntry = ExplainPlanResultView(
            "MySQL",
            null,
            1L,
            McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows, false),
            ExplainOutputFormat.TABLE,
        )
        val hidden = noEntry.backButtonForTest()
        assertNotNull(hidden)
        assertFalse("无回退动作时入口不可见（保守增量：不挂死按钮）", hidden!!.isVisible)
    }

    fun test头部含方言与数据源() {
        val view = ExplainPlanResultView(
            "MariaDB",
            "prod@10.0.0.3",
            88L,
            McpExplainFormatter.RawResult(mysqlHeaders, mysqlRows, false),
            ExplainOutputFormat.TABLE,
        )
        val header = view.headerTextForTest()
        assertNotNull(header)
        assertTrue("头部应含方言", header!!.contains("MariaDB"))
        assertTrue("头部应含数据源名", header.contains("prod@10.0.0.3"))
    }
}
