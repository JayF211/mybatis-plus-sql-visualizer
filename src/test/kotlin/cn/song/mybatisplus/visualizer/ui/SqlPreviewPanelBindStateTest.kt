package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * O-95 T-PreviewEdtGuard S3：预览面板绑定数据源常驻指示行测试。
 *
 * 覆盖三件事：①无绑定态显示未绑定灰字文案（与 R33 回退通知同源，headless 真实链
 * fail-closed 即未绑定）；②绑定态显示「已绑定：<源名>」（名字经 [SqlPreviewPanel.bindStateNameProvider]
 * 测试缝注入——生产同型读数走 DataSourceSelector.boundConsoleDataSourceName，无 Database
 * 插件的沙盒测试任务不可达，与 ExecTempFileFixTest 同款口径）并随渲染刷新（绑定丢失回流未绑定）；
 * ③版式：指示行在预设行之下（D2=A 灰字文本行定案，BoxLayout Y_AXIS 组件序=视觉序）。
 */
class SqlPreviewPanelBindStateTest : BasePlatformTestCase() {

    private var panel: SqlPreviewPanel? = null

    override fun setUp() {
        super.setUp()
        runCatching { PluginSettings.getInstance().state.formatSql = false }
        // O-88：折叠态已静态化——复位防跨用例串扰（ParamSchemes 同款）
        runCatching { SqlPreviewPanel.resetPresetCollapsedForTest() }
    }

    override fun tearDown() {
        panel?.bindStateNameProvider = null
        runCatching { panel?.clear() }
        panel = null
        super.tearDown()
    }

    /** 普通参数语料（无 xml/batch 模型 → 走方案行+指示行+参数行主链）。 */
    private fun buildResult(vararg params: String): PreviewResult = PreviewResult(
        sql = "SELECT * FROM t WHERE id = ?",
        compactSql = "SELECT * FROM t WHERE id = ?",
        unresolvedParams = params.toList(),
        paramTable = ParameterReplacer.buildParamTable(params.toList()),
        wrapperType = null,
        tableName = null,
        xmlModel = null,
    )

    private fun openPanel(vararg params: String): SqlPreviewPanel {
        val p = SqlPreviewPanel(project)
        panel = p
        Disposer.register(testRootDisposable, p)
        p.showResult(buildResult(*params))
        UIUtil.dispatchAllInvocationEvents()
        return p
    }

    fun test无绑定时显示未绑定灰字文案() {
        val p = openPanel("id")
        val label = p.bindStateLabelForTest()
        assertNotNull("参数区应建绑定指示行标签", label)
        assertEquals(
            "未绑定应显示未绑定文案（panel.bindstate.unbound，与 R33 回退通知同源文案族）",
            MpSqlVisualizerBundle.message("panel.bindstate.unbound"),
            label!!.text,
        )
        assertEquals(
            "灰字形态=平台 contextHelp 前景（与面板既有灰字形态一致，非自创颜色）",
            UIUtil.getContextHelpForeground(),
            label.foreground,
        )
    }

    fun test有绑定态显示数据源名并随渲染刷新回流() {
        val p = openPanel("id")
        p.bindStateNameProvider = { "mp_demo@localhost" }
        p.renderSqlForTest() // renderSql 尾部单点刷新（绑定态变更的既有刷新通道，零监听器口径）
        assertEquals(
            "绑定态应显示「已绑定：<源名>」（数据源名与 R33 结构化视图头部同源）",
            MpSqlVisualizerBundle.message("panel.bindstate.bound", "mp_demo@localhost"),
            p.bindStateLabelForTest()!!.text,
        )
        // 变更回流：绑定丢失（控制台 clearPrepared/失效 → 名取不到）→ 下一轮渲染回到未绑定灰字
        p.bindStateNameProvider = null
        p.renderSqlForTest()
        assertEquals(
            "绑定丢失后应回流未绑定文案",
            MpSqlVisualizerBundle.message("panel.bindstate.unbound"),
            p.bindStateLabelForTest()!!.text,
        )
    }

    fun test指示行位于预设行之下() {
        val p = openPanel("id")
        val fold = p.presetFoldForTest()
        assertNotNull("带参语料应建预设折叠件", fold)
        val components = p.paramPanelComponentsForTest()
        val foldIdx = components.indexOfFirst { it === fold }
        val label = p.bindStateLabelForTest()
        assertNotNull(label)
        val bindIdx = components.indexOfFirst { it === label!!.parent }
        assertTrue("指示行行容器应在参数区内", bindIdx >= 0)
        assertTrue("指示行必须在预设行之下（D2=A 版式定案：组件序=BoxLayout 视觉序，fold=$foldIdx bind=$bindIdx）", bindIdx > foldIdx)
    }
}
