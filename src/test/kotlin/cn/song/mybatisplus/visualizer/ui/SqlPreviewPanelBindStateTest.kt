package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.execute.DataSourceSelector
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
 *
 * O-96 T-PreviewBindAssociation S1 追加：指示行三级化的**记忆回退态**（无活控制台但
 * last-used 记忆命中在籍数据源 →「关联（上次使用）：<源名>」）——记忆经
 * [DataSourceSelector.plantLastUsedMemoryForTest] 直写 PropertiesComponent（生产同源存储），
 * 在籍名单/UUID 解析经 [DataSourceSelector.rememberedLookupForTest] 缝注入（无 Database
 * 插件沙盒真链恒空），生产链 DataSourceSelector.rememberedDataSourceName 原样经过。
 * 三态覆盖：记忆命中（UUID 精确）/ UUID 失效（数据源已删：名字不在籍回落未绑定、
 * 名字唯一命中按 T-SI-02 口径仍显示）/ 同名歧义（弃记忆回落未绑定，不猜首条）。
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
        // O-96：object 单例静态缝复位（防跨用例串扰）
        DataSourceSelector.rememberedLookupForTest = null
        runCatching { DataSourceSelector.plantLastUsedMemoryForTest(project, null, null) }
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

    // ------------------------------------------------------------------
    // O-96 T-PreviewBindAssociation S1：last-used 记忆回退态（指示行三级化）
    // ------------------------------------------------------------------

    /** O-96：在籍数据源缝注入（名单 + UUID→名解析），模拟 Database 插件在籍注册表。 */
    private fun plantRegisteredDataSources(names: List<String>, nameById: (String) -> String?) {
        DataSourceSelector.rememberedLookupForTest = { names to nameById }
    }

    fun test记忆命中显示关联上次使用文案() {
        plantRegisteredDataSources(
            listOf("mp_demo@localhost", "warehouse@db2"),
        ) { id -> if (id == "uuid-1") "mp_demo@localhost" else null }
        DataSourceSelector.plantLastUsedMemoryForTest(project, "uuid-1", "mp_demo@localhost")
        val p = openPanel("id")
        p.renderSqlForTest() // renderSql 尾部单点刷新（零监听器口径）
        assertEquals(
            "记忆命中应显示「关联（上次使用）：<源名>」（panel.bindstate.associated，文案区分不冒称已绑定）",
            MpSqlVisualizerBundle.message("panel.bindstate.associated", "mp_demo@localhost"),
            p.bindStateLabelForTest()!!.text,
        )
    }

    fun testUUID失效且名字不在籍回落未绑定() {
        plantRegisteredDataSources(
            listOf("warehouse@db2"),
        ) { _ -> null } // uuid-dead 指认的数据源已删除（getDataSourceById → null，前提 3 fail-closed）
        DataSourceSelector.plantLastUsedMemoryForTest(project, "uuid-dead", "gone@db")
        val p = openPanel("id")
        p.renderSqlForTest()
        assertEquals(
            "UUID 失效且记忆名不在籍应回落未绑定（禁静默猜）",
            MpSqlVisualizerBundle.message("panel.bindstate.unbound"),
            p.bindStateLabelForTest()!!.text,
        )
    }

    fun testUUID失效但名字唯一命中仍显示关联() {
        plantRegisteredDataSources(
            listOf("mp_demo@localhost", "warehouse@db2"),
        ) { _ -> null } // UUID 分量失效 → T-SI-02 口径退回名字判定
        DataSourceSelector.plantLastUsedMemoryForTest(project, "uuid-dead", "mp_demo@localhost")
        val p = openPanel("id")
        p.renderSqlForTest()
        assertEquals(
            "UUID 失效但名字唯一命中应仍显示关联（与执行链静默复用同口径，指示与执行不脱节）",
            MpSqlVisualizerBundle.message("panel.bindstate.associated", "mp_demo@localhost"),
            p.bindStateLabelForTest()!!.text,
        )
    }

    fun test同名歧义回落未绑定() {
        plantRegisteredDataSources(
            listOf("mp_demo@localhost", "mp_demo@localhost"),
        ) { id -> if (id == "uuid-1") "mp_demo@localhost" else null }
        DataSourceSelector.plantLastUsedMemoryForTest(project, "uuid-1", "mp_demo@localhost")
        val p = openPanel("id")
        p.renderSqlForTest()
        assertEquals(
            "同名多条歧义应回落未绑定（T-SI-02 同款：不猜首条；展示路径只读不清记忆）",
            MpSqlVisualizerBundle.message("panel.bindstate.unbound"),
            p.bindStateLabelForTest()!!.text,
        )
    }

    // ------------------------------------------------------------------
    // O-96fix2：控制台挂载即刷新指示行（执行路径无 renderSql 的补拍刷新）
    // ------------------------------------------------------------------

    /**
     * 真机缺陷复现（O-96 复测轮）：执行 SQL 挂载控制台的路径**不走 renderSql**（SqlExecutor
     * execute/executePrepared 的 onConsole → embedConsole，控制台文档已是执行 SQL，重渲染会冲掉），
     * 指示行停留在挂载前的记忆态「关联（上次使用）」，手动点刷新才升级「已绑定」。
     * 修复=embedConsole 单点补一拍刷新（全挂载路径统一钩子）。
     * 本用例：记忆态（关联）面板 → 模拟挂载（bindStateNameProvider 测试缝替代生产
     * boundConsoleDataSourceName，与 test有绑定态同款口径）→ 只 dispatch 不 renderSql →
     * 指示行应即时升级「已绑定」。
     */
    fun test挂载控制台后指示行即时升级无需重渲染() {
        plantRegisteredDataSources(
            listOf("mp_demo@localhost"),
        ) { id -> if (id == "uuid-1") "mp_demo@localhost" else null }
        DataSourceSelector.plantLastUsedMemoryForTest(project, "uuid-1", "mp_demo@localhost")
        val p = openPanel("id")
        assertEquals(
            "前置：无活控制台时应显示记忆态关联文案",
            MpSqlVisualizerBundle.message("panel.bindstate.associated", "mp_demo@localhost"),
            p.bindStateLabelForTest()!!.text,
        )
        // 活控制台上线（生产链在 onConsole 回调前已写 preparedConsole/记忆，测试缝同步在挂载前置好）
        p.bindStateNameProvider = { "mp_demo@localhost" }
        p.embedConsoleForTest(javax.swing.JPanel())
        UIUtil.dispatchAllInvocationEvents() // 挂载补拍的 invokeLater 刷新——不经 renderSql
        assertEquals(
            "挂载控制台后指示行应即时升级「已绑定」（无需手动刷新/重渲染）",
            MpSqlVisualizerBundle.message("panel.bindstate.bound", "mp_demo@localhost"),
            p.bindStateLabelForTest()!!.text,
        )
    }
}
