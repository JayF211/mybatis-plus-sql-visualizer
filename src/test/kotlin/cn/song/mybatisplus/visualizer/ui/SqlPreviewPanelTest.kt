package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.ConditionToggle
import cn.song.mybatisplus.visualizer.model.ConditionView
import cn.song.mybatisplus.visualizer.model.LikePosition
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
    // M12/M13：动态条件复选框（手动勾选）+ 回填提示（LIKE 百分号 / 字段类型引号）
    // ------------------------------------------------------------------

    private fun tableInfo() = TableInfo(
        entityClassName = "DeviceInfoEntity",
        tableName = "car_device_info",
        columns = listOf(
            ColumnInfo("id", "id", isId = true, fieldType = "java.lang.Long"),
            ColumnInfo("stationId", "station_id", fieldType = "java.lang.Long"),
            ColumnInfo("name", "name", fieldType = "java.lang.String"),
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
                        toggle = ConditionToggle("c0", "true", true, "id = 1")),
                    Condition.Simple(ColumnRef(columnName = "station_id"), "=", SqlValue.Literal("2"),
                        toggle = ConditionToggle("c1", "false", false, "station_id = 2")),
                ),
                views = listOf(
                    ConditionView("c0", "id = 1", "true", true, null),
                    ConditionView("c1", "station_id = 2", "false", false, null),
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

    fun testSelectAllNoneTogglesAllConditions() {
        // 全选/全不选（M13.1）：c0 默认勾选、c1 默认不勾选 → 全选两者都在、全不选两者都移除
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"),
                        toggle = ConditionToggle("c0", "true", true, "id = 1")),
                    Condition.Simple(ColumnRef(columnName = "station_id"), "=", SqlValue.Literal("2"),
                        toggle = ConditionToggle("c1", "false", false, "station_id = 2")),
                ),
                views = listOf(
                    ConditionView("c0", "id = 1", "true", true, null),
                    ConditionView("c1", "station_id = 2", "false", false, null),
                ),
            ),
        )
        // 初始：仅 c0
        assertTrue(panel.editorForTest()!!.document.text.contains("WHERE id = 1"))
        assertFalse(panel.editorForTest()!!.document.text.contains("station_id = 2"))

        // 全选 → 两个条件都在，复选框状态同步
        panel.selectAllButtonForTest()!!.doClick()
        assertTrue(panel.editorForTest()!!.document.text.contains("AND station_id = 2"))
        assertTrue(panel.conditionCheckboxForTest(0)!!.isSelected)
        assertTrue(panel.conditionCheckboxForTest(1)!!.isSelected)

        // 全不选 → 动态条件全部移除（无 WHERE），复选框状态同步
        panel.selectNoneButtonForTest()!!.doClick()
        assertFalse(panel.editorForTest()!!.document.text.contains("WHERE"))
        assertFalse(panel.conditionCheckboxForTest(0)!!.isSelected)
        assertFalse(panel.conditionCheckboxForTest(1)!!.isSelected)
        assertTrue(panel.enabledIdsForTest().isEmpty())

        // 全选后再取消单个勾选 → 另一个保留
        panel.selectAllButtonForTest()!!.doClick()
        panel.conditionCheckboxForTest(1)!!.doClick()
        val sql = panel.editorForTest()!!.document.text
        assertTrue(sql.contains("id = 1"))
        assertFalse(sql.contains("station_id = 2"))
        panel.clear()
    }

    fun testDynamicConditionValueBlankKeepsChecked() {
        // M13 复选框纯手动：值空不联动勾选态——默认勾选保留 `?`，填写拼接，清空仍勾选，手动取消才排除
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "name"), "=", SqlValue.Placeholder("nameVar"),
                        toggle = ConditionToggle("c0", "StringUtils.isNotBlank(name)", true, "name = ?", "nameVar")),
                ),
                views = listOf(
                    ConditionView("c0", "name = ?", "StringUtils.isNotBlank(name)", true, "nameVar"),
                ),
            ),
        )
        // 初始：值空但默认勾选 → 保留 ?（不再按判空语义自动取消勾选）
        assertTrue(panel.enabledIdsForTest().contains("c0"))
        assertTrue(panel.editorForTest()!!.document.text.contains("name = ?"))

        // 填写值 → 拼接实际值
        panel.conditionValueFieldForTest(0)!!.text = "张三"
        assertTrue(panel.editorForTest()!!.document.text.contains("name = '张三'"))

        // 清空 → 勾选态不变、保留 ?
        panel.conditionValueFieldForTest(0)!!.text = ""
        assertTrue(panel.enabledIdsForTest().contains("c0"))
        assertTrue(panel.editorForTest()!!.document.text.contains("name = ?"))

        // 手动取消勾选 → 排除
        panel.conditionCheckboxForTest(0)!!.doClick()
        assertFalse(panel.editorForTest()!!.document.text.contains("WHERE"))
        panel.clear()
    }

    fun testDynamicConditionLikePlaceholderPercentBackfill() {
        // M13：LIKE 占位符回填自动拼百分号（name 为 String 列）→ name LIKE '%张三%'
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.Like(ColumnRef(columnName = "name"), negated = false,
                        pattern = SqlValue.Placeholder("likeVar"), position = LikePosition.ANYWHERE,
                        toggle = ConditionToggle("c0", "true", true, "name LIKE ?", "likeVar")),
                ),
                views = listOf(
                    ConditionView("c0", "name LIKE ?", "true", true, "likeVar"),
                ),
            ),
        )
        panel.conditionValueFieldForTest(0)!!.text = "张三"
        assertTrue(panel.editorForTest()!!.document.text.contains("name LIKE '%张三%'"))
        panel.clear()
    }

    fun testDynamicConditionStringColumnNumericValueQuoted() {
        // M13：String 列（java.lang.String）输入数字 → 恒加引号（不再被识别成数值）
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "name"), "=", SqlValue.Placeholder("nameVar"),
                        toggle = ConditionToggle("c0", "true", true, "name = ?", "nameVar")),
                ),
                views = listOf(
                    ConditionView("c0", "name = ?", "true", true, "nameVar"),
                ),
            ),
        )
        panel.conditionValueFieldForTest(0)!!.text = "123"
        assertTrue(panel.editorForTest()!!.document.text.contains("name = '123'"))
        panel.clear()
    }

    fun testDynamicConditionNumericColumnValueRaw() {
        // M13：数值列（java.lang.Long）输入数字 → 原样不带引号
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "station_id"), "=", SqlValue.Placeholder("stationVar"),
                        toggle = ConditionToggle("c0", "true", true, "station_id = ?", "stationVar")),
                ),
                views = listOf(
                    ConditionView("c0", "station_id = ?", "true", true, "stationVar"),
                ),
            ),
        )
        panel.conditionValueFieldForTest(0)!!.text = "2"
        assertTrue(panel.editorForTest()!!.document.text.contains("station_id = 2"))
        panel.clear()
    }
}
