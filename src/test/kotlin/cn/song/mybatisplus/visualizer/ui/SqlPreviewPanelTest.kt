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

    fun testRefreshResetsModeToSettingsDefault() {
        // M19.3（用户实测：点刷新后 SQL 仍停留在单行模式）：
        // 刷新 = 模式复位为与设置「SQL 格式化输出」对齐（默认多行）——手动切到单行后刷新应回多行
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())
        panel.toggleCompact()   // 切到单行
        assertTrue(panel.compactForTest())
        assertEquals("SELECT id FROM tbl", panel.editorForTest()!!.document.text)

        panel.refreshView()
        assertFalse("刷新后应恢复默认多行模式", panel.compactForTest())
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
        // 标签截断显示不全）：标签宽度按文本自适应（FontMetrics 计算，夹在 140~300px）；
        // 文本超过 300px 上限时 tooltip 展示全名
        val panel = SqlPreviewPanel(project)
        val short = "id"
        val medium = "productInfo.getDicProtocolType()"   // 用户实测场景：约 200px，固定 140px 时截断
        val long = "productInfo.getDicProtocolType().getDeviceName().getStatusName().getDetailInfo()"
        panel.showResult(result(params = listOf(short, medium, long)))
        val shortLabel = panel.paramRowLabelForTest(0)!!
        val mediumLabel = panel.paramRowLabelForTest(1)!!
        val longLabel = panel.paramRowLabelForTest(2)!!
        // 短名：宽度按下限 140px（不再小于 140）
        assertEquals("短参数标签宽度下限 140", 140, shortLabel.preferredSize.width)
        // 用户实测场景：宽度按文本自适应（>140 不再固定 140 截断，且不超 300 上限）
        assertTrue("中等长度参数标签宽度按文本自适应（>140）", mediumLabel.preferredSize.width > 140)
        assertTrue("中等长度参数标签宽度不超过 300", mediumLabel.preferredSize.width <= 300)
        // 超长名：宽度夹在 300 上限 + tooltip 全名；短名不设 tooltip
        assertEquals("超长参数标签宽度上限 300", 300, longLabel.preferredSize.width)
        assertEquals("超长参数 tooltip 应为全名", "#$long", longLabel.toolTipText)
        assertNull("短参数不设 tooltip", shortLabel.toolTipText)
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
}
