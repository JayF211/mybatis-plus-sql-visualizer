package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.BatchColumn
import cn.song.mybatisplus.visualizer.model.BatchKind
import cn.song.mybatisplus.visualizer.model.BatchModel
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
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext
import cn.song.mybatisplus.visualizer.ui.SqlInputEditor
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.testFramework.EditorTestUtil
import com.intellij.util.ui.UIUtil
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.awt.Color
import java.awt.Toolkit
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import javax.swing.plaf.basic.BasicSplitPaneUI

/**
 * SqlPreviewPanel 行为测试（M8/M9）：编辑器视图 + 单行多行切换 + 生命周期 + 参数回填。
 *
 * 需要 IDE fixture（EditorFactory/PSI 依赖应用环境），在 EDT 上运行。
 */
class SqlPreviewPanelTest : BasePlatformTestCase() {

    // 测试前快照系统剪贴板，tearDown 恢复，避免 copySql/copyError 污染用户剪贴板
    private var clipboardSnapshot: Transferable? = null

    // T-M27.4：渲染文本断言类测试关闭自动格式化（默认开启的格式化行为由
    // SqlFormatterTest + testRenderAutoFormatsSql 专项覆盖；此处模拟"关闭格式化"场景）
    override fun setUp() {
        super.setUp()
        runCatching { cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.formatSql = false }
        clipboardSnapshot = Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
    }

    override fun tearDown() {
        runCatching { cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.formatSql = true }
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(clipboardSnapshot, null) }
        super.tearDown()
    }

    private fun result(
        sql: String = "SELECT id\nFROM tbl",
        compact: String = "SELECT id FROM tbl",
        params: List<String> = emptyList(),
        columns: List<String> = listOf("id", "name"),
        tableName: String? = "tbl",
        candidateTables: List<TableInfo> = emptyList(),
    ) = PreviewResult(
        sql = sql,
        compactSql = compact,
        unresolvedParams = params,
        paramTable = if (params.isEmpty()) null else "-- 参数对照: [${params.joinToString(", ") { "$it=?" }}]",
        wrapperType = WrapperType.LAMBDA_QUERY,
        tableName = tableName,
        columns = columns,
        candidateTables = candidateTables,
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

    fun testRefreshResetsModeToSettingsDefault() {
        // M19.3（用户实测：点刷新后 SQL 仍停留在单行模式）：
        // 刷新 = 模式复位为与设置「SQL 格式化输出」对齐（默认多行）——手动切到单行后刷新应回多行
        // （setUp 关闭格式化以保持渲染断言——本测试需默认开启语义，显式恢复）
        cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.formatSql = true
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())
        panel.toggleCompact()   // 切到单行
        assertTrue(panel.compactForTest())
        assertEquals("SELECT id FROM tbl", panel.editorForTest()!!.document.text)

        panel.refreshView()
        assertFalse("刷新后应恢复默认多行模式", panel.compactForTest())
        // formatSql=true 时刷新渲染自动格式化
        assertEquals("SELECT\n    id\nFROM tbl", panel.editorForTest()!!.document.text)
        panel.clear()
    }

    fun testCopySqlWithContent() {
        // 无 project 时不弹成功提示（测试环境 Messages 对话框会抛异常）
        val panel = SqlPreviewPanel(null)
        panel.showResult(result())
        assertTrue("有内容时应复制成功", panel.copySql())
        panel.clear()
    }

    fun testCopySqlClosePanelCallbackInjectable() {
        // T-M23.6：onClosePanelRequested 回调默认 null、可注入（预览弹窗赋值 dispose）；
        // project null → 弹框跳过（回调不触发），注入的回调可被面板外部链路触发
        val panel = SqlPreviewPanel(null)
        assertNull("默认无关闭面板回调", panel.onClosePanelRequested)
        var closed = false
        panel.onClosePanelRequested = { closed = true }
        panel.showResult(result())
        assertTrue("有内容时应复制成功", panel.copySql())
        assertFalse("无 project 弹框跳过，回调不应触发", closed)
        // 链路：点「关闭 SQL 面板」→ onClosePanelRequested?.invoke()
        panel.onClosePanelRequested?.invoke()
        assertTrue("注入的回调应可触发", closed)
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

    fun testParamRowLabelWidthAdaptsAndTooltipForLongNames() {
        // T-M19.4-01（用户实测：`#productInfo.getDicProtocolType()` 这类长参数名被固定 140px
        // 标签截断显示不全）+ T-M26.3（标签列宽统一：按最长可见标签自适应 140~260，
        // 超宽中段省略 + tooltip 全名）
        val panel = SqlPreviewPanel(project)
        val short = "id"
        val medium = "productInfo.getDicProtocolType()"   // 用户实测场景：约 200px
        val long = "productInfo.getDicProtocolType().getDeviceName().getStatusName().getDetailInfo()"
        panel.showResult(result(params = listOf(short, medium, long)))
        val shortLabel = panel.paramRowLabelForTest(0)!!
        val mediumLabel = panel.paramRowLabelForTest(1)!!
        val longLabel = panel.paramRowLabelForTest(2)!!
        // T-M26.3：同一场景所有参数行共享标签列宽（最长可见标签夹取 140~260）
        val columnWidth = shortLabel.preferredSize.width
        assertTrue("列宽应不小于下限 140", columnWidth >= 140)
        assertTrue("列宽应不超过上限 260", columnWidth <= 260)
        assertEquals("标签列宽应统一", columnWidth, mediumLabel.preferredSize.width)
        assertEquals("标签列宽应统一", columnWidth, longLabel.preferredSize.width)
        // 超长名：截断显示（文本 != 全名）+ tooltip 全名；短/中名不截断、无 tooltip
        assertEquals("超长标签 tooltip 应为全名", "#$long", longLabel.toolTipText)
        assertNotEquals("超长标签应截断显示", "#$long", longLabel.text)
        assertNull("短参数不设 tooltip", shortLabel.toolTipText)
        assertNull("中等长度参数不设 tooltip", mediumLabel.toolTipText)
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

    // ------------------------------------------------------------------
    // T-M42：日志双击预览参数识别——initialParamValues 预填（参数行初始值 + SQL 初始渲染）
    // ------------------------------------------------------------------

    fun testInitialParamValuesPrefillFieldAndRender() {
        // 日志识别路径：param1 预填 "admin"（String → 引号提示）——初始显示与现状（值内联 'admin'）一致
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE username = ?"
        panel.showResult(
            PreviewResult(
                sql = sql,
                compactSql = sql,
                unresolvedParams = listOf("param1"),
                paramTable = null,
                wrapperType = WrapperType.LAMBDA_QUERY,
                tableName = "tbl",
                paramHints = listOf(ParamHint(quote = true)),
                initialParamValues = mapOf("param1" to "admin"),
            ),
        )
        assertEquals("参数行应预填日志参数值", "admin", panel.paramFieldForTest(0)!!.text)
        assertTrue(
            "SQL 初始渲染应含值内联 'admin'：" + panel.editorForTest()!!.document.text,
            panel.editorForTest()!!.document.text.contains("username = 'admin'"),
        )
        panel.clear()
    }

    fun testInitialParamValuesEditableAfterPrefill() {
        // 预填值可编辑：改参数行值 → SQL 实时回填（T-M42 验收：改 admin → 新值同步变化）
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE username = ?"
        panel.showResult(
            PreviewResult(
                sql = sql,
                compactSql = sql,
                unresolvedParams = listOf("param1"),
                paramTable = null,
                wrapperType = WrapperType.LAMBDA_QUERY,
                tableName = "tbl",
                paramHints = listOf(ParamHint(quote = true)),
                initialParamValues = mapOf("param1" to "admin"),
            ),
        )
        panel.paramFieldForTest(0)!!.text = "zhang"
        assertTrue(
            "修改参数行值后 SQL 应同步回填：" + panel.editorForTest()!!.document.text,
            panel.editorForTest()!!.document.text.contains("username = 'zhang'"),
        )
        assertFalse("原值不应残留", panel.editorForTest()!!.document.text.contains("admin"))
        panel.clear()
    }

    fun testInitialParamValuesMissingSourceFallsBackToEmpty() {
        // 预填表缺失的来源回落空串（参数行初始为空 → 渲染保留 `?`）
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE phone = ? AND type = ?"
        panel.showResult(
            PreviewResult(
                sql = sql,
                compactSql = sql,
                unresolvedParams = listOf("param1", "param2"),
                paramTable = null,
                wrapperType = WrapperType.LAMBDA_QUERY,
                tableName = "tbl",
                paramHints = listOf(ParamHint(quote = true), ParamHint(quote = false)),
                initialParamValues = mapOf("param1" to "admin"),
            ),
        )
        assertEquals("param1 应预填", "admin", panel.paramFieldForTest(0)!!.text)
        assertEquals("param2 无预填值应回落空串", "", panel.paramFieldForTest(1)!!.text)
        assertTrue(
            "param2 空值渲染保留 ?：" + panel.editorForTest()!!.document.text,
            panel.editorForTest()!!.document.text.contains("type = ?"),
        )
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

        // M16.2 双载体：Editor 上同样挂载补全上下文（编辑后 PSI 重建丢失时兜底）
        val editor = panel.editorForTest()
        assertNotNull("应创建编辑器", editor)
        val editorCtx = editor!!.getUserData(SqlCompletionContext.KEY)
        assertNotNull("Editor 上应挂补全上下文（M16.2）", editorCtx)
        assertEquals("tbl", editorCtx!!.tableName)
        assertEquals(listOf("id", "phone"), editorCtx.columns)
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
    private fun dynamicResult(
        conditions: List<Condition>,
        views: List<ConditionView>,
        params: List<String> = emptyList(),
    ): PreviewResult {
        val statement = SelectStatement(table = tableInfo(), conditions = conditions)
        val model = SqlPreviewModel(statement, MySqlDialect, formatSql = true, conditionViews = views)
        return PreviewResult(
            sql = "SELECT id, station_id, name\nFROM car_device_info",
            compactSql = "SELECT id, station_id, name FROM car_device_info",
            unresolvedParams = params,
            paramTable = if (params.isEmpty()) null else "-- 参数对照: [${params.joinToString(", ") { "$it=?" }}]",
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

    fun testClearButtonClearsConditionValues() {
        // 清除按钮：填写条件值 → 点击清除 → 输入框清空、勾选态保留、SQL 恢复含 ?
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "name"), "=", SqlValue.Placeholder("nameVar"),
                        toggle = ConditionToggle("c0", "StringUtils.isNotBlank(name)", true, "name = ?", "nameVar")),
                    Condition.Simple(ColumnRef(columnName = "station_id"), "=", SqlValue.Placeholder("stationIdVar"),
                        toggle = ConditionToggle("c1", "stationId != null", false, "station_id = ?", "stationIdVar")),
                ),
                views = listOf(
                    ConditionView("c0", "name = ?", "StringUtils.isNotBlank(name)", true, "nameVar"),
                    ConditionView("c1", "station_id = ?", "stationId != null", false, "stationIdVar"),
                ),
            ),
        )
        // 清除按钮存在
        assertNotNull("清除按钮应存在", panel.clearButtonForTest())
        // 填写条件值
        panel.conditionValueFieldForTest(0)!!.text = "zhang"
        panel.conditionValueFieldForTest(1)!!.text = "100"
        // 值回填到 SQL
        assertTrue(panel.editorForTest()!!.document.text.contains("name = 'zhang'"))
        // 点击清除
        panel.clearButtonForTest()!!.doClick()
        // 输入框清空
        assertEquals("清除后输入框应为空", "", panel.conditionValueFieldForTest(0)!!.text)
        assertEquals("清除后输入框应为空", "", panel.conditionValueFieldForTest(1)!!.text)
        // paramValues 清空
        assertEquals("paramValues 应为空字符串", "", panel.paramValuesForTest()["nameVar"])
        assertEquals("paramValues 应为空字符串", "", panel.paramValuesForTest()["stationIdVar"])
        // 勾选态保留（c0 默认勾选，c1 默认不勾选）
        assertTrue("清除不影响勾选态", panel.conditionCheckboxForTest(0)!!.isSelected)
        assertFalse("清除不影响勾选态", panel.conditionCheckboxForTest(1)!!.isSelected)
        // SQL 恢复含 ? 形态
        assertTrue("清除后 SQL 应恢复 ?", panel.editorForTest()!!.document.text.contains("name = ?"))
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

    fun testDynamicConditionDateTimeColumnValueQuoted() {
        // T-M23-03（用户实测：LocalDateTime 参数回填不带引号）：除确定的数值类型外其他类型默认带引号——
        // LocalDateTime 列回填 `2024-01-01 00:00:00` → `use_time = '2024-01-01 00:00:00'`（原实现无引号，SQL 无效）
        val statement = SelectStatement(
            table = tableInfo().copy(
                columns = tableInfo().columns + ColumnInfo("useTime", "use_time", fieldType = "java.time.LocalDateTime"),
            ),
            conditions = listOf(
                Condition.Simple(
                    ColumnRef(columnName = "use_time"), "=", SqlValue.Placeholder("now"),
                    toggle = ConditionToggle("c0", "true", true, "use_time = ?", "now"),
                ),
            ),
        )
        val model = SqlPreviewModel(
            statement, MySqlDialect, formatSql = true,
            conditionViews = listOf(ConditionView("c0", "use_time = ?", "true", true, "now")),
        )
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            PreviewResult(
                sql = "SELECT id, station_id, name, use_time\nFROM car_device_info",
                compactSql = "SELECT id, station_id, name, use_time FROM car_device_info",
                unresolvedParams = emptyList(),
                paramTable = null,
                wrapperType = WrapperType.LAMBDA_QUERY,
                tableName = "car_device_info",
                columns = listOf("id", "station_id", "name", "use_time"),
                previewModel = model,
            ),
        )
        panel.conditionValueFieldForTest(0)!!.text = "2024-01-01 00:00:00"
        assertTrue(panel.editorForTest()!!.document.text.contains("use_time = '2024-01-01 00:00:00'"))
        panel.clear()
    }

    // ------------------------------------------------------------------
    // T-M23-04：批量参数列级勾选（BatchModel 复选框 + 勾选重建 SQL）
    // ------------------------------------------------------------------

    /** 构造带 BatchModel 的批量预览结果（面板走批量勾选重生成路径）。 */
    private fun batchResult(model: BatchModel): PreviewResult {
        val active = (model.toggleColumns.filter { it.checked }.map { it.columnName } + model.fixedColumns.map { it.columnName }).toSet()
        val sql = model.renderSql(active)
        return PreviewResult(
            sql = sql,
            compactSql = sql,
            unresolvedParams = model.orderedSources(active),
            paramTable = null,
            wrapperType = null,
            tableName = model.tableName,
            columns = model.toggleColumns.map { it.columnName },
            batchModel = model,
        )
    }

    fun testBatchModelAutoIdDefaultUncheckedRendersWithoutId() {
        // T-M23-04（用户建议）：save(entity) 自增主键默认不勾选 → 初始 SQL 无 id 列
        val model = BatchModel(
            kind = BatchKind.INSERT,
            tableName = "biz_store_coupon",
            toggleColumns = listOf(
                BatchColumn("id", "entity.id", quote = false, checked = false),
                BatchColumn("name", "entity.name", quote = true, checked = true),
                BatchColumn("dic_status", "entity.dic_status", quote = false, checked = true),
            ),
        )
        val panel = SqlPreviewPanel(project)
        panel.showResult(batchResult(model))
        val sql = panel.editorForTest()!!.document.text
        assertTrue("初始 SQL 应含勾选列", sql.contains("INSERT INTO biz_store_coupon (name, dic_status)"))
        assertFalse("初始 SQL 不应含 id 列", sql.contains("id"))
        assertFalse("id 复选框默认不勾选", panel.batchCheckboxForTest(0)!!.isSelected)
        panel.clear()
    }

    fun testBatchModelCheckboxToggleRebuildsSql() {
        // T-M23-04：勾选 id 复选框 → SQL 列清单恢复 id；取消 name → SET/列清单移除 name
        val model = BatchModel(
            kind = BatchKind.INSERT,
            tableName = "biz_store_coupon",
            toggleColumns = listOf(
                BatchColumn("id", "entity.id", quote = false, checked = false),
                BatchColumn("name", "entity.name", quote = true, checked = true),
                BatchColumn("dic_status", "entity.dic_status", quote = false, checked = true),
            ),
        )
        val panel = SqlPreviewPanel(project)
        panel.showResult(batchResult(model))
        // 勾选 id → INSERT 恢复 id 列
        panel.batchCheckboxForTest(0)!!.doClick()
        assertTrue(panel.batchCheckboxForTest(0)!!.isSelected)
        assertTrue(
            "勾选后 SQL 应含 id",
            panel.editorForTest()!!.document.text.contains("INSERT INTO biz_store_coupon (id, name, dic_status)"),
        )
        // 取消 name → 列清单移除 name
        panel.batchCheckboxForTest(1)!!.doClick()
        assertTrue(
            "取消后 SQL 不应含 name",
            panel.editorForTest()!!.document.text.contains("INSERT INTO biz_store_coupon (id, dic_status)"),
        )
        // 回填值不丢：勾选态变更后重渲染仍按 source 绑定；未勾选列的输入值不进 SQL
        panel.paramFieldForTest(0)!!.text = "1"
        panel.paramFieldForTest(1)!!.text = "张三"
        assertTrue(panel.editorForTest()!!.document.text.contains("VALUES (1, ?)"))
        assertFalse("未勾选列（name）的输入值不应进入 SQL", panel.editorForTest()!!.document.text.contains("张三"))
        // 重新勾选 name → 值保留、按 String 列类型加引号回填
        panel.batchCheckboxForTest(1)!!.doClick()
        assertTrue(panel.editorForTest()!!.document.text.contains("VALUES (1, '张三', ?)"))
        panel.clear()
    }

    fun testBatchModelFixedColumnHasNoCheckbox() {
        // T-M23-04：UPDATE 的 WHERE 主键为固定列——仅值输入框（无复选框），复选框数 = toggle 列数
        val model = BatchModel(
            kind = BatchKind.UPDATE,
            tableName = "device_info",
            toggleColumns = listOf(
                BatchColumn("name", "entity.name", quote = true, checked = true),
            ),
            fixedColumns = listOf(BatchColumn("id", "entity.id", quote = false, checked = true)),
        )
        val panel = SqlPreviewPanel(project)
        panel.showResult(batchResult(model))
        assertNotNull("toggle 列应有复选框", panel.batchCheckboxForTest(0))
        assertNull("fixed 列不应有复选框（复选框仅 toggle 列）", panel.batchCheckboxForTest(1))
        // 值输入框：toggle(name) + fixed(id) 各一个
        assertEquals("name 值输入框", "#entity.name", panel.paramFieldForTest(0)!!.toolTipText)
        assertEquals("id 值输入框", "#entity.id", panel.paramFieldForTest(1)!!.toolTipText)
        assertTrue(panel.editorForTest()!!.document.text.contains("SET name = ?"))
        assertTrue(panel.editorForTest()!!.document.text.contains("WHERE id = ?"))
        panel.clear()
    }

    fun testBatchModelSelectAllNoneButtons() {
        // T-M23.2（用户要求：参数可选场景必须有【全选/全不选】按钮）：
        // 全不选 → 全部取消勾选、SQL 空列清单；全选 → 全部勾选、SQL 全列
        val model = BatchModel(
            kind = BatchKind.INSERT,
            tableName = "biz_store_coupon",
            toggleColumns = listOf(
                BatchColumn("id", "entity.id", quote = false, checked = false),
                BatchColumn("name", "entity.name", quote = true, checked = true),
                BatchColumn("dic_status", "entity.dic_status", quote = false, checked = true),
            ),
        )
        val panel = SqlPreviewPanel(project)
        panel.showResult(batchResult(model))
        assertNotNull("批量区应有全选按钮", panel.batchSelectAllButtonForTest())
        assertNotNull("批量区应有全不选按钮", panel.batchSelectNoneButtonForTest())
        // 全不选 → 空列清单（文档化边界）
        panel.batchSelectNoneButtonForTest()!!.doClick()
        assertFalse(panel.batchCheckboxForTest(0)!!.isSelected)
        assertFalse(panel.batchCheckboxForTest(1)!!.isSelected)
        assertFalse(panel.batchCheckboxForTest(2)!!.isSelected)
        assertTrue(panel.editorForTest()!!.document.text.contains("INSERT INTO biz_store_coupon ()"))
        // 全选 → 全列恢复
        panel.batchSelectAllButtonForTest()!!.doClick()
        assertTrue(panel.batchCheckboxForTest(0)!!.isSelected)
        assertTrue(panel.batchCheckboxForTest(1)!!.isSelected)
        assertTrue(panel.batchCheckboxForTest(2)!!.isSelected)
        assertTrue(
            panel.editorForTest()!!.document.text.contains("INSERT INTO biz_store_coupon (id, name, dic_status)"),
        )
        panel.clear()
    }

    fun testInListMultiValueBackfill() {
        // M19.6（2026-08-13 用户实测）：IN 列表占位符回填按逗号分割多值、每个值独立引号——
        // 输入 `1,2` → IN ('1','2')（原实现拼成 IN ('1,2') 单值；name 为 String 列恒引号）
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.In(
                        ColumnRef(columnName = "name"),
                        negated = false,
                        values = listOf(SqlValue.Placeholder("names")),
                        toggle = ConditionToggle("c0", null, true, "name IN (?)", "names", listOf("names")),
                    ),
                ),
                views = listOf(
                    ConditionView("c0", "name IN (?)", null, true, "names", listOf("names")),
                ),
            ),
        )
        panel.conditionValueFieldForTest(0)!!.text = "张三,李四"
        assertTrue(panel.editorForTest()!!.document.text.contains("name IN ('张三', '李四')"))
        // 单值输入不受影响（String 列恒引号）
        panel.conditionValueFieldForTest(0)!!.text = "王五"
        assertTrue(panel.editorForTest()!!.document.text.contains("name IN ('王五')"))
        panel.clear()
    }

    // ------------------------------------------------------------------
    // M17：执行 SQL（M17.4 起由弹窗底部按钮区调用面板 internal executeSql()；
    // 测试沙盒无 Database 插件 → Community 降级路径）
    // ------------------------------------------------------------------

    fun testExecuteSqlWithoutDatabasePluginNoThrow() {
        // 测试沙盒无 Database 插件 → 走 Community 降级提示（headless 下被兜底吞掉），不抛异常
        val panel = SqlPreviewPanel(project)
        panel.showResult(result(sql = "SELECT id FROM tbl", compact = "SELECT id FROM tbl"))
        panel.executeSql()
        panel.clear()
    }

    fun testExecuteSqlDisabledWithoutProject() {
        // 无项目面板（纯组件预览）：执行无动作（project null 直接返回），不抛异常
        val panel = SqlPreviewPanel(null)
        panel.showResult(result())
        panel.executeSql()
        panel.clear()
    }

    fun testExecuteSqlBlankSqlNoop() {
        // 空 SQL：不触发执行链路，不抛异常
        val panel = SqlPreviewPanel(project)
        panel.showResult(result(sql = "   ", compact = "   "))
        panel.executeSql()
        panel.clear()
    }

    fun testSplitDividerPaintedAsPanelBackground() {
        // T-M26.1：分隔条绘制应等于面板底色——真实 IDE 中平台 LAF 委托 Metal，
        // MetalSplitPaneDivider 以 Metal 主题浅色填充分隔条（与 setBackground 无关），
        // 深色主题下呈白色竖条纹；ThemedSplitPane 在 paint 尾部覆盖分隔条区域。
        // 渲染级断言：先人为把分隔条背景污染为白色（模拟 LAF 的浅色绘制），
        // 再断言渲染后分隔条像素 == 面板底色（覆盖生效；删掉覆盖则本测试失败）
        val panel = SqlPreviewPanel(project)
        val split = panel.splitPaneForTest()
        assertNotNull("应存在左右分栏", split)
        split!!.setSize(300, 100)
        split.doLayout()
        // 修正（headless 测试环境不自动布局分隔条）：手动定位到中间，模拟真实布局
        if (split.dividerLocation < 0) split.dividerLocation = 150
        val loc = split.dividerLocation
        assertTrue("分隔条应已布局", loc >= 0)
        // 污染：让 BasicSplitPaneDivider（opaque）以白色填充，模拟 LAF 浅色分隔条
        (split.ui as? BasicSplitPaneUI)?.divider?.background = Color.WHITE
        val img = BufferedImage(300, 100, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        split.paint(g)
        g.dispose()
        val expected = UIUtil.getPanelBackground()
        val expRgb = Color(expected.red, expected.green, expected.blue).rgb
        assertEquals("分隔条像素应等于面板底色", expRgb, img.getRGB(loc + split.dividerSize / 2, 50))
        panel.clear()
    }

    fun testSelectAllButtonsOnConditionSectionHeader() {
        // T-M26.3（方案 A）：全选/全不选回挂「动态条件」分区标题行右侧（IDEA 组操作惯例，
        // T-M26.2 的固定右上角已拆除）。长 test 表达式标签按视口宽度截断 → 标题行按钮不被推出
        // 视口：断言按钮在参数面板内、布局后仍可见于视口
        val long = "stationIds != null and stationIds.size() > 0"
        val panel = SqlPreviewPanel(project)
        val split = panel.splitPaneForTest()!!
        // 先布局再 showResult：截断上限按视口宽度计算（真实 IDE 中 rebuild 发生在面板布局后）
        split.setSize(500, 120)
        split.doLayout()
        if (split.dividerLocation < 0) split.dividerLocation = 250
        split.doLayout()
        (split.leftComponent as JBScrollPane).doLayout() // 布局 viewport bounds（否则宽度为 0，截断上限回落默认值）
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "station_id"), "IN", SqlValue.Placeholder("stationIds"),
                        toggle = ConditionToggle("c0", "true", true, "station_id IN (?)", "stationIds", listOf("stationIds"))),
                ),
                views = listOf(ConditionView("c0", long, "true", true, "stationIds", listOf("stationIds"))),
            ),
        )
        val scroll = split.leftComponent as JBScrollPane
        val viewport = scroll.viewport
        val button = panel.selectAllButtonForTest()!!
        // 按钮在参数面板内（回挂分区标题行，随列表滚动——T-M26.2 曾固定视口外）
        assertTrue("按钮应在参数面板内", SwingUtilities.isDescendingFrom(button, viewport.view))
        // 布局（doLayout 不递归，逐层布局组件树）后按钮仍可见于视口（长标签按视口截断 → 行宽受限）
        split.doLayout()
        layoutTree(scroll)
        val btnLeft = SwingUtilities.convertPoint(button, 0, 0, scroll).x
        assertTrue("按钮应位于视口内", btnLeft < viewport.width)
        panel.clear()
    }

    /** headless 未显示组件 validate() 不布局（peer 为 null）——同步 doLayout 递归布局组件树。 */
    private fun layoutTree(container: java.awt.Container) {
        container.doLayout()
        container.components.filterIsInstance<java.awt.Container>().forEach { layoutTree(it) }
    }

    fun testReformatShortcutRegistered() {
        // T-M26.6：格式化快捷键注册——action 实例存在（跟随 IDE keymap 的 Reformat Code，
        // 兜底 Ctrl+Alt+L），触发走 [SqlPreviewPanel.formatSql]（与「格式化」按钮同逻辑）
        val panel = SqlPreviewPanel(project)
        assertNotNull("应注册格式化 action", panel.reformatActionForTest())
        panel.clear()
    }

    fun testReformatShortcutFormatsSql() {
        // T-M26.6：触发格式化 action 后 SQL 被格式化（关键字大写 + 子句换行）
        val panel = SqlPreviewPanel(project)
        panel.showResult(result(sql = "select id from tbl", compact = "select id from tbl"))
        panel.reformatActionForTest().actionPerformed(
            AnActionEvent.createFromAnAction(panel.reformatActionForTest(), null, "test", DataContext.EMPTY_CONTEXT),
        )
        val text = panel.editorForTest()!!.document.text
        assertTrue("格式化后关键字应大写：" + text, text.contains("SELECT"))
        assertTrue("格式化后应含换行：" + text, text.contains("\n"))
        panel.clear()
    }

    fun testActionButtonGroupsSwitchByScene() {
        // T-M26.3：按钮组按场景构建——无内容无按钮、动态条件 → 条件组（标题行右侧）、
        // 批量 → 批量组、clear 复位（字段置 null）
        val panel = SqlPreviewPanel(project)
        // 无参数 → 无按钮
        panel.showResult(result())
        assertNull("无内容时应无全选按钮", panel.selectAllButtonForTest())
        assertNull("无内容时应无批量全选按钮", panel.batchSelectAllButtonForTest())
        // 动态条件 → 条件组
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"),
                        toggle = ConditionToggle("c0", "true", true, "id = 1")),
                ),
                views = listOf(ConditionView("c0", "id = 1", "true", true, null)),
            ),
        )
        assertNotNull("动态条件场景应有全选按钮", panel.selectAllButtonForTest())
        assertNull("动态条件场景不应有批量全选按钮", panel.batchSelectAllButtonForTest())
        // 批量列 → 批量组
        panel.showResult(
            batchResult(
                BatchModel(
                    kind = BatchKind.INSERT,
                    tableName = "biz_store_coupon",
                    toggleColumns = listOf(
                        BatchColumn("name", "entity.name", quote = true, checked = true),
                    ),
                ),
            ),
        )
        assertNull("批量场景不应有全选按钮", panel.selectAllButtonForTest())
        assertNotNull("批量场景应有批量全选按钮", panel.batchSelectAllButtonForTest())
        panel.clear()
        assertNull("clear 后应无全选按钮", panel.selectAllButtonForTest())
        assertNull("clear 后应无批量全选按钮", panel.batchSelectAllButtonForTest())
    }

    // ------------------------------------------------------------------
    // T-M27.1：SQL 面板补全触发（用户实测：配置数据源后 XML 有补全、面板无补全——
    // 根因：编辑器组件链无 DataProvider（2025.3 平台编辑器改 UiCompatibleDataProvider，
    // 传统 getDataContext 沿父链只认 DataProvider）→ Ctrl+Space 的 action 拿不到 EDITOR
    // 静默失败。修复：挂载时包装 DataProvider 提供 EDITOR）
    // ------------------------------------------------------------------

    fun testPanelEditorWrapsDataProviderForEditor() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())
        val editor = panel.editorForTest()
        assertNotNull("应挂载编辑器", editor)
        // 组件树中应存在提供 EDITOR 的 DataProvider（Ctrl+Space action 的 DataContext 来源）
        val provider = findDataProvider(panel)
        assertNotNull("编辑器组件链应有 DataProvider 包装", provider)
        assertEquals("DataProvider 应提供当前编辑器", editor, provider!!.getData(CommonDataKeys.EDITOR.name))
        panel.clear()
    }

    fun testPanelEditorCompletionOffersKeywordsAndColumns() {
        if (SqlInputEditor.resolveSqlFileType() == null) return // 测试环境无 Database 插件
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())
        val editor = panel.editorForTest()!!
        // 表名位：FROM 后（空格后空前缀 → 全量命中；测试环境 HeadlessDataManager 使 DataContext
        // 路径不可模拟，直接调平台补全处理器 = 编辑器内 Ctrl+Space 的最终路径）
        editor.caretModel.moveToOffset(editor.document.text.indexOf("FROM") + 5)
        val handler = CodeCompletionHandlerBase(CompletionType.BASIC, false, false, true)
        handler.invokeCompletion(project, editor, 1, false)
        val lookup = LookupManager.getInstance(project).getActiveLookup()
        assertNotNull("Ctrl+Space 应弹出补全（实际为 null——补全链路失效）", lookup)
        val items = lookup!!.items.map { it.lookupString }
        assertTrue("补全应含关键字 SELECT：$items", items.contains("SELECT"))
        assertTrue("补全应含关键字 DISTINCT：$items", items.contains("DISTINCT"))
        assertTrue("FROM 后应含表名 tbl：$items", items.contains("tbl"))
        LookupManager.getInstance(project).hideActiveLookup()
        panel.clear()

        // 列名位：WHERE 后（空格后空前缀）
        val panel2 = SqlPreviewPanel(project)
        panel2.showResult(result(sql = "SELECT id FROM tbl WHERE ", compact = "SELECT id FROM tbl"))
        val editor2 = panel2.editorForTest()!!
        editor2.caretModel.moveToOffset(editor2.document.text.indexOf("WHERE") + 6)
        CodeCompletionHandlerBase(CompletionType.BASIC, false, false, true).invokeCompletion(project, editor2, 1, false)
        val lookup2 = LookupManager.getInstance(project).getActiveLookup()
        assertNotNull("WHERE 后应弹出补全", lookup2)
        val items2 = lookup2!!.items.map { it.lookupString }
        assertTrue("WHERE 后应含列名 id：$items2", items2.contains("id"))
        assertTrue("WHERE 后应含列名 name：$items2", items2.contains("name"))
        LookupManager.getInstance(project).hideActiveLookup()
        panel2.clear()
    }

    /** 沿组件树找 DataProvider（编辑器挂载包装）。 */
    private fun findDataProvider(component: java.awt.Component): DataProvider? {
        if (component is DataProvider) return component
        if (component is java.awt.Container) {
            for (c in component.components) {
                findDataProvider(c)?.let { return it }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // T-M27.2：面板输入自动弹出补全（用户实测：输入字符无自动提示——平台 autopopup 对
    // 非文件编辑器显示空弹窗且不调度 contributor，面板自建：文档监听 + 手动模式补全处理器）
    // ------------------------------------------------------------------

    fun testTypingTriggersAutoPopup() {
        if (SqlInputEditor.resolveSqlFileType() == null) return // 测试环境无 Database 插件
        val panel = SqlPreviewPanel(project)
        // SQL 末尾空白处输入（新词起始，前缀 S 命中 SELECT）
        panel.showResult(result(sql = "SELECT id FROM ", compact = "SELECT id FROM tbl"))
        val editor = panel.editorForTest()!!
        // 格式化后 SQL 末尾无空格（format 的 trim）——先补空格再输入（模拟真实输入位置，避免连词）
        val insertOffset = editor.document.textLength
        editor.caretModel.moveToOffset(insertOffset)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(insertOffset, " S")
        }
        // 真实输入后 caret 自动在字符后（insertString 不移动 caret，测试手动模拟）
        editor.caretModel.moveToOffset(insertOffset + 2)
        var lookup = LookupManager.getInstance(project).getActiveLookup()
        var round = 0
        while (lookup == null && round < 30) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(50)
            lookup = LookupManager.getInstance(project).getActiveLookup()
            round++
        }
        assertNotNull("输入字符后应自动弹出补全（实际为 null——自建 autopopup 失效）", lookup)
        val items = lookup!!.items.map { it.lookupString }
        assertTrue("补全应含关键字 SELECT：$items", items.contains("SELECT"))
        LookupManager.getInstance(project).hideActiveLookup()
        panel.clear()
    }

    fun testTypingTriggersAutoPopupXmlScene() {
        if (SqlInputEditor.resolveSqlFileType() == null) return // 测试环境无 Database 插件
        // XML 预览场景：tableName/columns 为空（XmlPreviewService 不关联实体）——
        // 关键字补全应仍弹出（预览补全不依赖实体上下文）
        val panel = SqlPreviewPanel(project)
        panel.showResult(result(sql = "SELECT id FROM ", compact = "SELECT id FROM tbl", tableName = null, columns = emptyList()))
        val editor = panel.editorForTest()!!
        // 格式化后 SQL 末尾无空格（format 的 trim）——先补空格再输入（模拟真实输入位置，避免连词）
        val insertOffset = editor.document.textLength
        editor.caretModel.moveToOffset(insertOffset)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(insertOffset, " S")
        }
        editor.caretModel.moveToOffset(insertOffset + 2)
        var lookup = LookupManager.getInstance(project).getActiveLookup()
        var round = 0
        while (lookup == null && round < 30) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(50)
            lookup = LookupManager.getInstance(project).getActiveLookup()
            round++
        }
        assertNotNull("XML 场景（无实体上下文）输入字符后也应自动弹出补全", lookup)
        val items = lookup!!.items.map { it.lookupString }
        assertTrue("XML 场景应含关键字 SELECT：$items", items.contains("SELECT"))
        LookupManager.getInstance(project).hideActiveLookup()
        panel.clear()
    }

    fun testTypingTriggersAutoPopupXmlSceneWithEntity() {
        if (SqlInputEditor.resolveSqlFileType() == null) return // 测试环境无 Database 插件
        // T-M27.3：XML 预览注入实体上下文后——输入应弹**列名**（不止关键字）
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            result(
                sql = "SELECT i FROM ",
                compact = "SELECT i FROM tbl",
                columns = listOf("id", "name"),
                tableName = null,
            ),
        )
        val editor = panel.editorForTest()!!
        // 格式化后 SQL 末尾无空格（format 的 trim）——先补空格再输入（模拟真实输入位置，避免连词）
        val insertOffset = editor.document.textLength
        editor.caretModel.moveToOffset(insertOffset)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(insertOffset, " S")
        }
        editor.caretModel.moveToOffset(insertOffset + 2)
        var lookup = LookupManager.getInstance(project).getActiveLookup()
        var round = 0
        while (lookup == null && round < 30) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(50)
            lookup = LookupManager.getInstance(project).getActiveLookup()
            round++
        }
        assertNotNull("XML 预览（注入实体）输入字符后应弹出补全", lookup)
        val items = lookup!!.items.map { it.lookupString }
        assertTrue("应含关键字 SELECT：$items", items.contains("SELECT"))
        LookupManager.getInstance(project).hideActiveLookup()
        panel.clear()
    }

    fun testRenderAutoFormatsSql() {
        // T-M27.4：渲染自动格式化（设置开启）——关键字大写/主子句顶格/列清单换行/空行归一
        cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.formatSql = true
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            result(
                sql = "select  id ,name  from t  where id=1",
                compact = "select id,name from t where id=1",
            ),
        )
        val doc = panel.editorForTest()!!.document.text
        // T-M29.2：列清单逗号保留（原实现顶层逗号丢失 → 非法 SQL）
        assertEquals(
            "SELECT\n    id,\n    name\nFROM t\nWHERE id = 1",
            doc,
        )
        panel.clear()
        panel.clear()
    }

    fun testTableNameCompletionInWhereClauseWithCandidates() {
        if (SqlInputEditor.resolveSqlFileType() == null) return // 测试环境无 Database 插件
        // T-M27.4：表名全位置提示——WHERE 后输入表名前缀也提示（主表 + 联表候选），
        // 不再限定 FROM/JOIN 后（输入 me → merchant_entity 命中）
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            result(
                sql = "SELECT id FROM t WHERE ",
                compact = "SELECT id FROM t WHERE ",
                tableName = "t",
                columns = listOf("id"),
                candidateTables = listOf(TableInfo("MerchantEntity", "merchant_entity", emptyList())),
            ),
        )
        val editor = panel.editorForTest()!!
        val insertOffset = editor.document.textLength
        editor.caretModel.moveToOffset(insertOffset)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(insertOffset, "me")
        }
        editor.caretModel.moveToOffset(insertOffset + 2)
        var lookup = LookupManager.getInstance(project).getActiveLookup()
        var round = 0
        while (lookup == null && round < 30) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(50)
            lookup = LookupManager.getInstance(project).getActiveLookup()
            round++
        }
        assertNotNull("WHERE 后输入表名前缀应弹出补全", lookup)
        val items = lookup!!.items.map { it.lookupString }
        assertTrue("应含联表候选表名 merchant_entity：$items", items.contains("merchant_entity"))
        LookupManager.getInstance(project).hideActiveLookup()
        panel.clear()
    }
}
