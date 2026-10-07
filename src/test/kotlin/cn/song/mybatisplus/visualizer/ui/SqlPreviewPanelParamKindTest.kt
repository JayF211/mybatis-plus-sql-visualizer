package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.xml.ParamKind
import cn.song.mybatisplus.visualizer.xml.ParamTypeInfo
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-PT-02 / T-PT-04 参数面板类型契约测试（headless）：
 *  - 占位提示（emptyText）按类型类别渲染；UNKNOWN 走参数命名启发式（`s` 结尾 / 含 ids·list）
 *  - NUMBER / TEMPORAL 输入校验警示（提示不是闸门——预览仍按原样渲染）
 *  - 空参数填充策略两档（NULL 字面量 / 空字符串；未填写的参数仍保留 `?`）
 *
 * 类型类别由预览模型携带（无需 PSI：直接构造 `paramTypes` 注入），故本测试在 Light 沙盒可跑。
 */
class SqlPreviewPanelParamKindTest : BasePlatformTestCase() {

    private var panel: SqlPreviewPanel? = null
    private var oldEmptyParamFill: String = PluginSettings.DEFAULT_EMPTY_PARAM_FILL

    override fun setUp() {
        super.setUp()
        runCatching { PluginSettings.getInstance().state.formatSql = false }
        oldEmptyParamFill = runCatching { PluginSettings.getInstance().state.emptyParamFill }
            .getOrDefault(PluginSettings.DEFAULT_EMPTY_PARAM_FILL)
    }

    override fun tearDown() {
        runCatching { panel?.clear() }
        runCatching { PluginSettings.getInstance().state.formatSql = true }
        runCatching { PluginSettings.getInstance().state.emptyParamFill = oldEmptyParamFill }
        panel = null
        super.tearDown()
    }

    private val mapper = """
        <mapper namespace="com.example.ProbeMapper">
          <select id="probe">
            SELECT * FROM t
            WHERE page_num = #{pageNum} AND created = #{startDate}
              AND id IN (#{ids}) AND status = #{status}
              AND name = #{userIds} AND addr = #{address}
              AND ext = #{params}
              AND loc = #{locationStatus} AND ord = #{orderStatus}
          </select>
        </mapper>
    """.trimIndent()

    /** 构造带类型类别的 XML 预览结果：前四参数有类型，userIds/address 无类型信息（UNKNOWN）。 */
    private fun result(): PreviewResult {
        val parsed = XmlMapperParser().parse(mapper)
        val model = XmlPreviewModel.from(
            parsed.statements.single(), parsed.errors,
            paramTypes = mapOf(
                "pageNum" to ParamTypeInfo(ParamKind.NUMBER, nullable = false),
                "startDate" to ParamTypeInfo(ParamKind.TEMPORAL),
                "ids" to ParamTypeInfo(ParamKind.COLLECTION),
                "status" to ParamTypeInfo(ParamKind.TEXT),
                "params" to ParamTypeInfo(ParamKind.OBJECT),
            ),
        )
        val rendered = model.renderSql()
        return PreviewResult(
            sql = rendered.sql,
            compactSql = rendered.sql.replace(Regex("\\s+"), " ").trim(),
            unresolvedParams = rendered.mappings.map { it.property },
            paramTable = ParameterReplacer.buildParamTable(rendered.mappings.map { it.property }),
            wrapperType = null,
            tableName = null,
            xmlModel = model,
        )
    }

    private fun openPanel(): SqlPreviewPanel {
        val p = SqlPreviewPanel(project)
        panel = p
        Disposer.register(testRootDisposable, p)
        p.showResult(result())
        return p
    }

    private fun sqlText(p: SqlPreviewPanel): String = p.editorForTest()!!.document.text

    // ------------------------------------------------------------------
    // 占位提示（emptyText）
    // ------------------------------------------------------------------

    fun testEmptyTextByParamKind() {
        val p = openPanel()
        assertEquals("NUMBER", MpSqlVisualizerBundle.message("panel.param.emptyText.number"), p.paramEmptyTextForTest(0))
        assertEquals("TEMPORAL", MpSqlVisualizerBundle.message("panel.param.emptyText.temporal"), p.paramEmptyTextForTest(1))
        assertEquals("COLLECTION", MpSqlVisualizerBundle.message("panel.param.emptyText.collection"), p.paramEmptyTextForTest(2))
        assertEquals("TEXT 无占位提示（维持现状）", "", p.paramEmptyTextForTest(3))
        assertEquals("OBJECT（Map 参数）", MpSqlVisualizerBundle.message("panel.param.emptyText.object"), p.paramEmptyTextForTest(6))
    }

    fun testUnknownKindFallsBackToNamingHeuristic() {
        val p = openPanel()
        // userIds：无类型信息但含 ids → 数组提示
        assertEquals(
            "UNKNOWN 含 ids → 数组提示",
            MpSqlVisualizerBundle.message("panel.param.emptyText.collection"),
            p.paramEmptyTextForTest(4),
        )
        // address：以 s 结尾但在单数词排除表内 → 无提示（宁缺勿错）
        assertEquals("UNKNOWN 单数词无提示", "", p.paramEmptyTextForTest(5))
        // O-111 追加（用户 demo 实测）：复合驼峰词以单数词结尾 → 无提示
        // （改前排除表只做全名匹配，locationStatus 被误配集合占位 [1, 2, 3]）
        assertEquals(
            "UNKNOWN 复合词 locationStatus 无提示",
            "",
            p.paramEmptyTextForTest(7),
        )
        assertEquals(
            "UNKNOWN 复合词 orderStatus 无提示",
            "",
            p.paramEmptyTextForTest(8),
        )
    }

    // ------------------------------------------------------------------
    // 校验警示（不阻塞渲染）
    // ------------------------------------------------------------------

    fun testNumberWarningDoesNotBlockRender() {
        val p = openPanel()
        val field = p.paramFieldForTest(0)!!
        field.text = "abc"
        assertEquals(
            "NUMBER 非数字应出警示",
            MpSqlVisualizerBundle.message("panel.param.warn.number"),
            p.paramWarningForTest(0),
        )
        assertTrue("警示不阻塞渲染（值原样进 SQL）: ${sqlText(p)}", sqlText(p).contains("page_num = abc"))
        field.text = "42"
        assertNull("数字输入无警示", p.paramWarningForTest(0))
    }

    fun testTemporalWarningAndAcceptedForms() {
        val p = openPanel()
        val field = p.paramFieldForTest(1)!!
        field.text = "abc"
        assertEquals(
            "TEMPORAL 非日期应出警示",
            MpSqlVisualizerBundle.message("panel.param.warn.temporal"),
            p.paramWarningForTest(1),
        )
        field.text = "2026-01-01 12:00:00"
        assertNull("完整日期时间无警示", p.paramWarningForTest(1))
        field.text = "2025"
        assertNull("仅年份视为可解析日期", p.paramWarningForTest(1))
    }

    // ------------------------------------------------------------------
    // 空参数填充策略（T-PT-04）
    // ------------------------------------------------------------------

    fun testEmptyParamFillStrategyThreeModes() {
        // O-79：默认占位档——未填写保留 ? 待填提示（NULL 会静默过滤行不易察觉）
        PluginSettings.getInstance().state.emptyParamFill = PluginSettings.EMPTY_PARAM_FILL_PLACEHOLDER
        val p = openPanel()
        assertTrue("? 档（默认）：空输入出 ?: ${sqlText(p)}", sqlText(p).contains("page_num = ?"))
        assertTrue("? 档：IN 列表同档: ${sqlText(p)}", sqlText(p).contains("id IN (?)"))

        PluginSettings.getInstance().state.emptyParamFill = PluginSettings.EMPTY_PARAM_FILL_NULL
        p.renderSqlForTest()
        assertTrue("NULL 档：空输入出 NULL: ${sqlText(p)}", sqlText(p).contains("page_num = NULL"))
        assertTrue("NULL 档：IN 列表空输入同档: ${sqlText(p)}", sqlText(p).contains("id IN (NULL)"))

        PluginSettings.getInstance().state.emptyParamFill = PluginSettings.EMPTY_PARAM_FILL_EMPTY_STRING
        p.renderSqlForTest()
        assertTrue("空字符串档：空输入出 '': ${sqlText(p)}", sqlText(p).contains("created = ''"))
        assertTrue("空字符串档：IN 列表同档: ${sqlText(p)}", sqlText(p).contains("id IN ('')"))

        // 有值输入不受策略影响（策略只作用于空输入）
        p.paramFieldForTest(0)!!.text = "42"
        assertTrue("有值输入照常渲染: ${sqlText(p)}", sqlText(p).contains("page_num = 42"))
    }
}
