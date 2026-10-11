package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.execute.DocWriteContext
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.xml.ParamKind
import cn.song.mybatisplus.visualizer.xml.ParamTypeInfo
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertTrue

/**
 * foreach 集合值回填端到端测试（O-78 T-ForeachItemRender）：
 * write-unsafe（与生产防抖 Timer 上下文同构，[DocWriteContext.writeAllowedProbe] 注入）下输入
 * `1,2,4,5` 应完整渲染 `IN ( 1, 2, 4, 5 )`——数值元素裸渲染（引号由元素类别派生）且四值齐全
 * （值播种不得依赖渲染后的同步键清单）。
 * 修复前本用例红：引号兜底 true 全加引号 + renderSql 重投递导致键清单滞后丢尾巴（`IN ( '1', '2', ?, ? )`）。
 */
class SqlPreviewPanelForeachValuesTest : BasePlatformTestCase() {

    private var panel: SqlPreviewPanel? = null

    private var oldEmptyParamFill: String = PluginSettings.DEFAULT_EMPTY_PARAM_FILL

    override fun setUp() {
        super.setUp()
        // O-97 治理（T-TestPerf15）：进态恢复出厂默认，截断跨类全局设置态泄漏
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
        runCatching { PluginSettings.getInstance().state.formatSql = false }
        oldEmptyParamFill = runCatching { PluginSettings.getInstance().state.emptyParamFill }
            .getOrDefault(PluginSettings.DEFAULT_EMPTY_PARAM_FILL)
        runCatching { PluginSettings.getInstance().state.emptyParamFill = PluginSettings.DEFAULT_EMPTY_PARAM_FILL }
    }

    override fun tearDown() {
        DocWriteContext.writeAllowedProbe = null
        runCatching { panel?.clear() }
        runCatching { PluginSettings.getInstance().state.formatSql = true }
        runCatching { PluginSettings.getInstance().state.emptyParamFill = oldEmptyParamFill }
        panel = null
        super.tearDown()
    }

    private val mapper = """
        <mapper namespace="com.example.ProbeMapper">
          <select id="probe">
            SELECT * FROM t_order
            <where>
              <if test="statusList != null and statusList.size() > 0">
                AND status IN
                <foreach collection="statusList" item="st" open="(" separator="," close=")">#{st}</foreach>
              </if>
            </where>
          </select>
        </mapper>
    """.trimIndent()

    private fun openPanel(): SqlPreviewPanel {
        val parsed = XmlMapperParser().parse(mapper)
        // k2j 适配：from 为 Java 7 参全量（paramTypes 落末位）；ParamTypeInfo 3 组件 record 无具名实参
        val model = XmlPreviewModel.from(
            parsed.statements().single(), parsed.errors(),
            emptySet(), emptyMap(), emptyMap(), emptyMap(),
            mapOf("statusList" to ParamTypeInfo(ParamKind.COLLECTION, true, ParamKind.NUMBER)),
        )
        val rendered = model.renderSql()
        val p = SqlPreviewPanel(project)
        panel = p
        Disposer.register(testRootDisposable, p)
        p.showResult(
            PreviewResult(
                // k2j 适配：20 参位置构造
                rendered.sql(),
                rendered.sql().replace(Regex("\\s+"), " ").trim(),
                rendered.mappings().map { it.property() },
                ParameterReplacer.buildParamTable(rendered.mappings().map { it.property() }),
                null,
                null,
                null,
                emptyList(),
                emptyMap(),
                null,
                null,
                emptyList(),
                emptyList(),
                null,
                model,
                emptyMap(),
                null,
                null,
                null,
                null,
            )
        )
        return p
    }

    private fun sqlText(p: SqlPreviewPanel): String = p.editorForTest()!!.document.text.replace(Regex("\\s+"), " ")

    fun testForeachValuesFullRenderUnderWriteUnsafeDeferral() {
        val p = openPanel()
        val field = p.paramFieldForTest(0)!!
        // 未输入值：item 键被既有链路播种空串，占位档（默认）下保留 ? 待填提示
        assertTrue(
            "初始默认展开 2 项：${sqlText(p)} / paramValues=${p.paramValuesForTest()}",
            sqlText(p).contains("IN (?,?)"),
        )
        // write-unsafe 注入：renderSql 走重投递（生产防抖 Timer 同构），值播种不得读滞后键清单
        DocWriteContext.writeAllowedProbe = { false }
        field.text = "1,2,4,5"
        DocWriteContext.writeAllowedProbe = null
        UIUtil.dispatchAllInvocationEvents()
        assertTrue("四值齐全且数值裸渲染：${sqlText(p)}", sqlText(p).contains("IN (1,2,4,5)"))
    }

    fun testForeachValuesDefaultTwoItemsKept() {
        val p = openPanel()
        // 不动输入框：默认 2 项，占位档下保留 ? 待填提示
        assertTrue(sqlText(p).contains("IN (?,?)"))
    }
}
