package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.ConditionToggle
import cn.song.mybatisplus.visualizer.model.ConditionView
import cn.song.mybatisplus.visualizer.model.EmptyValueRule
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlPreviewModel
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * SqlPreviewPanel 行为测试（M8/M9）：编辑器视图 + 单行多行切换 + 生命周期 + 参数回填。
 *
 * 需要 IDE fixture（EditorFactory/PSI 依赖应用环境），在 EDT 上运行。
 */
class SqlPreviewPanelTest : BasePlatformTestCase() {

    private fun result(
        sql: String = "SELECT id\nFROM tbl",
        compact: String = "SELECT id FROM tbl",
        params: List<String> = emptyList(),
        columns: List<String> = listOf("id", "name"),
    ) = PreviewResult(
        sql = sql,
        compactSql = compact,
        unresolvedParams = params,
        paramTable = if (params.isEmpty()) null else "-- 参数对照: [${params.joinToString(", ") { "$it=?" }}]",
        wrapperType = WrapperType.LAMBDA_QUERY,
        tableName = "tbl",
        columns = columns,
    )

    fun testShowResultCreatesEditorView() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())
        val editor = panel.editorForTest()
        assertNotNull("应挂载编辑器视图", editor)
        assertEquals("SELECT id\nFROM tbl", editor!!.document.text)
        panel.clear()
    }

    fun testShowResultReplacesEditor() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result(sql = "SELECT 1 FROM t1", compact = "SELECT 1 FROM t1"))
        val first = panel.editorForTest()
        panel.showResult(result(sql = "SELECT 2 FROM t2", compact = "SELECT 2 FROM t2"))
        val second = panel.editorForTest()
        assertNotNull(first)
        assertNotNull(second)
        assertEquals("SELECT 2 FROM t2", second!!.document.text)
        panel.clear()
    }

    fun testToggleCompactSwitchesSql() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())
        assertEquals("SELECT id\nFROM tbl", panel.editorForTest()!!.document.text)

        assertTrue("toggleCompact 应返回单行模式", panel.toggleCompact())
        assertTrue(panel.compactForTest())
        assertEquals("SELECT id FROM tbl", panel.editorForTest()!!.document.text)

        panel.toggleCompact()
        assertEquals("SELECT id\nFROM tbl", panel.editorForTest()!!.document.text)
        panel.clear()
    }

    fun testCopySqlWithContent() {
        // 无 project 时不弹成功提示（测试环境 Messages 对话框会抛异常）
        val panel = SqlPreviewPanel(null)
        panel.showResult(result())
        assertTrue("有内容时应复制成功", panel.copySql())
        panel.clear()
    }

    fun testClearReleasesEditor() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())
        assertNotNull(panel.editorForTest())
        panel.clear()
        assertNull("clear 后应释放编辑器", panel.editorForTest())
        assertNull(panel.currentForTest())
    }

    fun testShowResultBuildsParamFields() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result(params = listOf("dto.getPhone()", "id")))
        assertEquals(2, panel.paramFieldForTest(1)?.let { 2 } ?: 0)
        assertNotNull("应生成参数输入框", panel.paramFieldForTest(0))
        panel.clear()
    }

    // ------------------------------------------------------------------
    // M9：参数回填 / 可编辑 / 补全上下文
    // ------------------------------------------------------------------

    fun testParamBackfillUpdatesSql() {
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE phone = ? AND type = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("dto.getPhone()", "type")))
        // 输入第一个参数值（字符串）→ SQL 中第一个 ? 加单引号回填
        val field = panel.paramFieldForTest(0)!!
        field.text = "abc"
        assertEquals(
            "SELECT id FROM tbl WHERE phone = 'abc' AND type = ?",
            panel.editorForTest()!!.document.text,
        )
        // 输入第二个（数字）参数值 → 原样
        panel.paramFieldForTest(1)!!.text = "1"
        assertEquals(
            "SELECT id FROM tbl WHERE phone = 'abc' AND type = 1",
            panel.editorForTest()!!.document.text,
        )
        panel.clear()
    }

    fun testParamBackfillEmptyKeepsPlaceholder() {
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE phone = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("phone")))
        // 留空 → 保留 ?
        assertEquals(sql, panel.editorForTest()!!.document.text)
        panel.clear()
    }

    fun testEditorIsEditable() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())
        val editor = panel.editorForTest()!!
        assertFalse("应为可编辑编辑器（非只读 viewer）", editor.isViewer)
        panel.clear()
    }

    fun testCompletionContextAttached() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result(columns = listOf("id", "phone")))
        val psi = panel.psiFileForTest()
        assertNotNull("应创建预览 PSI 文件", psi)
        val ctx = psi!!.getUserData(SqlCompletionContext.KEY)
        assertNotNull("应挂补全上下文", ctx)
        assertEquals("tbl", ctx!!.tableName)
        assertEquals(listOf("id", "phone"), ctx.columns)
        panel.clear()
    }

    // ------------------------------------------------------------------
    // M12：动态条件复选框 + 空值联动（模型级重生成）
    // ------------------------------------------------------------------

    private fun tableInfo() = TableInfo(
        entityClassName = "DeviceInfoEntity",
        tableName = "car_device_info",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("stationId", "station_id"),
            ColumnInfo("name", "name"),
        ),
    )

    /** 构造带动态条件模型的预览结果（面板走模型级重生成路径）。 */
    private fun dynamicResult(conditions: List<Condition>, views: List<ConditionView>): PreviewResult {
        val statement = SelectStatement(table = tableInfo(), conditions = conditions)
        val model = SqlPreviewModel(statement, MySqlDialect, formatSql = true, conditionViews = views)
        return PreviewResult(
            sql = "SELECT id, station_id, name\nFROM car_device_info",
            compactSql = "SELECT id, station_id, name FROM car_device_info",
            unresolvedParams = emptyList(),
            paramTable = null,
            wrapperType = WrapperType.LAMBDA_QUERY,
            tableName = "car_device_info",
            columns = listOf("id", "station_id", "name"),
            previewModel = model,
        )
    }

    fun testDynamicConditionCheckboxTogglesSql() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"),
                        toggle = ConditionToggle("c0", "true", true, "id = 1", EmptyValueRule.UNKNOWN)),
                    Condition.Simple(ColumnRef(columnName = "station_id"), "=", SqlValue.Literal("2"),
                        toggle = ConditionToggle("c1", "false", false, "station_id = 2", EmptyValueRule.UNKNOWN)),
                ),
                views = listOf(
                    ConditionView("c0", "id = 1", "true", true, EmptyValueRule.UNKNOWN, null),
                    ConditionView("c1", "station_id = 2", "false", false, EmptyValueRule.UNKNOWN, null),
                ),
            ),
        )
        // 默认：eq(true) 保留、eq(false) 排除
        assertTrue(panel.editorForTest()!!.document.text.contains("WHERE id = 1"))
        assertFalse(panel.editorForTest()!!.document.text.contains("station_id = 2"))

        // 勾选第二个（eq(false)）→ 拼接 station_id = 2
        panel.conditionCheckboxForTest(1)!!.doClick()
        assertTrue(panel.editorForTest()!!.document.text.contains("AND station_id = 2"))

        // 取消第一个（eq(true)）→ 移除 id = 1，station_id 保留
        panel.conditionCheckboxForTest(0)!!.doClick()
        val sql = panel.editorForTest()!!.document.text
        assertFalse(sql.contains("id = 1"))
        assertTrue(sql.contains("station_id = 2"))
        panel.clear()
    }

    fun testDynamicConditionBlankCheckEmptySkips() {
        // eq(isNotBlank(name), name, nameVar)：SKIP_ON_BLANK → 空值不拼接、填写拼接、清空再移除
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "name"), "=", SqlValue.Placeholder("nameVar"),
                        toggle = ConditionToggle("c0", "StringUtils.isNotBlank(name)", true, "name = ?", EmptyValueRule.SKIP_ON_BLANK, "nameVar")),
                ),
                views = listOf(
                    ConditionView("c0", "name = ?", "StringUtils.isNotBlank(name)", true, EmptyValueRule.SKIP_ON_BLANK, "nameVar"),
                ),
            ),
        )
        // 初始：值空 → 条件不拼接（无 WHERE）
        assertFalse(panel.editorForTest()!!.document.text.contains("WHERE"))
        assertFalse(panel.enabledIdsForTest().contains("c0"))

        // 填写值 → 勾选并拼接
        panel.conditionValueFieldForTest(0)!!.text = "张三"
        assertTrue(panel.editorForTest()!!.document.text.contains("name = '张三'"))
        assertTrue(panel.enabledIdsForTest().contains("c0"))

        // 清空 → 再次取消勾选并不拼接
        panel.conditionValueFieldForTest(0)!!.text = ""
        assertFalse(panel.editorForTest()!!.document.text.contains("WHERE"))
        assertFalse(panel.enabledIdsForTest().contains("c0"))
        panel.clear()
    }

    fun testDynamicConditionNullCheckEmptyStringStillConcatenates() {
        // eq(name != null, name, nameVar)：SKIP_ON_NULL → 空值仍拼接为 ''（空字符串是合法值）
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "name"), "=", SqlValue.Placeholder("nameVar"),
                        toggle = ConditionToggle("c0", "name != null", true, "name = ?", EmptyValueRule.SKIP_ON_NULL, "nameVar")),
                ),
                views = listOf(
                    ConditionView("c0", "name = ?", "name != null", true, EmptyValueRule.SKIP_ON_NULL, "nameVar"),
                ),
            ),
        )
        // 空值 → 拼接 name = ''
        assertTrue(panel.editorForTest()!!.document.text.contains("name = ''"))
        assertTrue(panel.enabledIdsForTest().contains("c0"))

        // 填写 → 拼接实际值
        panel.conditionValueFieldForTest(0)!!.text = "张三"
        assertTrue(panel.editorForTest()!!.document.text.contains("name = '张三'"))

        // 手动取消勾选 → 排除
        panel.conditionCheckboxForTest(0)!!.doClick()
        assertFalse(panel.editorForTest()!!.document.text.contains("WHERE"))
        panel.clear()
    }
}
