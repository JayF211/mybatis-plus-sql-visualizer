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
import com.intellij.ui.HideableTitledPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

/**
 * R22 T-02/T-03：参数历史快照勾选态与 foreach 权威态端到端测试。
 *
 * 缺口②（enabledIds/xmlForEachNulls 不入快照）、缺口③（applyXmlForEachValues 不触发保存）。
 * 修复前对应用例红：快照无 __chk__/__fnull__/__fev__ 键、foreach 改值不落历史。
 */
class SqlPreviewPanelParamSchemesTest : BasePlatformTestCase() {

    private var panel: SqlPreviewPanel? = null

    override fun setUp() {
        super.setUp()
        runCatching { PluginSettings.getInstance().state.formatSql = false }
        // O-88：折叠态已静态化（跨面板实例保留）——复位防静态态跨用例/跨测试类串扰
        runCatching { SqlPreviewPanel.resetPresetCollapsedForTest() }
    }

    override fun tearDown() {
        DocWriteContext.writeAllowedProbe = null
        // R22 T-04：必须清历史缓存——ParamHistoryCache 测试态持久化走静态 fallbackStorage，
        // panel.clear() 不清缓存，激活方案/方案快照会跨测试（乃至跨测试类）泄漏
        runCatching { panel?.paramHistoryCacheForTest()?.clear() }
        runCatching { panel?.clear() }
        runCatching { PluginSettings.getInstance().state.formatSql = true }
        panel = null
        super.tearDown()
    }

    /** XML 语料：if 条件（勾选态载体）+ foreach 集合（权威值/null 勾选载体）。 */
    private val mapper = """
        <mapper namespace="com.example.ProbeMapper">
          <select id="probe">
            SELECT * FROM t_order
            <where>
              <if test="onlyActive != null and onlyActive">
                AND status = 1
              </if>
              <if test="statusList != null and statusList.size() > 0">
                AND status IN
                <foreach collection="statusList" item="st" open="(" separator="," close=")">#{st}</foreach>
              </if>
            </where>
          </select>
        </mapper>
    """.trimIndent()

    /** 语料渲染为 PreviewResult（openPanel 与 O-88 跨重建用例共用）。 */
    private fun buildProbePreviewResult(): PreviewResult {
        val parsed = XmlMapperParser().parse(mapper)
        val model = XmlPreviewModel.from(
            parsed.statements.single(), parsed.errors,
            paramTypes = mapOf(
                "statusList" to ParamTypeInfo(ParamKind.COLLECTION, elementKind = ParamKind.NUMBER),
                "onlyActive" to ParamTypeInfo(ParamKind.UNKNOWN),
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
        p.showResult(buildProbePreviewResult())
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents() // EDT 排队的参数区构建（勾选框/字段列表）就位
        return p
    }

    /** T-02 快照编码：勾选态以 __chk__ 全量 1/0 入快照（关掉默认勾选的方案可复现）。 */
    fun testT02_快照含勾选态全量10编码() {
        val p = openPanel()
        val enabledBefore = p.enabledIdsForTest()
        assertTrue("语料应有默认勾选的条件", enabledBefore.isNotEmpty())
        val idToDisable = enabledBefore.first()
        p.toggleCheckForTest(idToDisable, false)

        val snapshot = p.buildHistorySnapshotForTest()
        val chkKeys = snapshot.keys.filter { it.startsWith("__chk__:") }
        assertTrue("快照应含 __chk__ 键（缺口②修复）", chkKeys.isNotEmpty())
                assertEquals("被取消勾选的 id 应编码为 0", "0", snapshot["__chk__:$idToDisable"])
        (enabledBefore - idToDisable).forEach { assertEquals("仍勾选项应编码为 1", "1", snapshot["__chk__:$it"]) }
    }

    /** T-02 召回还原：清状态后召回，enabledIds 与可见勾选框恢复快照形态。 */
    fun testT02_召回还原勾选态与可见勾选框() {
        val p = openPanel()
        val defaults = p.enabledIdsForTest()
        val idToDisable = defaults.first()
        p.toggleCheckForTest(idToDisable, false)
        val snapshot = HashMap(p.buildHistorySnapshotForTest())
        val expected = defaults - idToDisable

        // 模拟"新会话"：清状态 → 快照入缓存 → 召回
        p.clearStateForTest()
        p.loadSnapshotForTest(snapshot)
        p.recallFromHistoryForTest()

        assertEquals("勾选态集合应还原（默认勾选保留 + 被禁项不复活）", expected, p.enabledIdsForTest())
        assertEquals("可见勾选框应同步取消", false, p.toggleCheckSelectedForTest(idToDisable))
        expected.forEach { id -> assertEquals("勾选框视觉态应选中: $id", true, p.toggleCheckSelectedForTest(id)) }
    }

    /** T-02 foreach null 勾选：__fnull__ 入快照 + 召回还原可见勾选框。 */
    fun testT02_foreachNull勾选_入快照并召回() {
        val p = openPanel()
        val expr = "statusList"
        p.toggleForEachNullForTest(expr, true)
        val snapshot = p.buildHistorySnapshotForTest()
        assertEquals("1", snapshot["__fnull__:$expr"])

        p.clearStateForTest()
        p.loadSnapshotForTest(snapshot)
        p.recallFromHistoryForTest()
        assertTrue("xmlForEachNulls 应还原", expr in p.forEachNullsForTest())
        assertEquals("可见 null 勾选框应选中", true, p.forEachNullSelectedForTest(expr))
    }

    /** T-03：foreach 权威值以 __fev__ 入快照 + 召回还原权威 map 与可见字段文本。 */
    fun testT03_foreach权威值_入快照并召回() {
        val p = openPanel()
        p.setForEachValuesForTest("statusList", listOf("1", "2", "4", "5"))
        p.flushHistoryForTest()

        val cache = p.paramHistoryCacheForTest()
        val key = p.historyCacheKeyForTest()
        assertNotNull(key)
        val stored = cache.recall(key!!)
        assertNotNull(stored)
        assertEquals("权威值应入库（逗号分隔）", "1,2,4,5", stored!!["__fev__:statusList"])

        p.clearStateForTest()
        p.loadSnapshotForTest(stored)
        p.recallFromHistoryForTest()
        assertEquals("权威 map 应还原", listOf("1", "2", "4", "5"), p.forEachValuesForTest()["statusList"])
        assertEquals("可见字段文本应还原", "1,2,4,5", p.forEachValueFieldTextForTest("statusList"))
    }

    /** T-03 反向验证载体：改值后历史跟随（缺口③：原实现 applyXmlForEachValues 不触发保存）。 */
    fun testT03_改值触发保存_历史跟随() {
        val p = openPanel()
        p.flushHistoryForTest()
        val cache = p.paramHistoryCacheForTest()
        val key = p.historyCacheKeyForTest()!!

        // 走生产链路：权威 map + 字段文本（listener 语义由 seam 直驱 applyXmlForEachValues 等价面）
        p.setForEachValuesForTest("statusList", listOf("7", "9"))
        p.applyXmlForEachValuesForTest("statusList", "7,9")
        p.flushHistoryForTest()
        val stored = cache.recall(key)
        assertEquals("改值后历史应跟随（原实现此键缺失）", "7,9", stored!!["__fev__:statusList"])
    }

    // ==== R22 T-04：命名方案面板层 round-trip ====

    /** 方案行在场：默认选中默认方案；下拉含另存的命名方案（对话框回调同步）。 */
    fun testT04_方案行在场_默认选中() {
        val p = openPanel()
        val combo = p.schemeComboForTest()
        assertNotNull("参数区应有方案下拉（T-04）", combo)
        assertEquals("默认应选中默认方案（空串内部名）", ParamHistoryCache.DEFAULT_SCHEME, combo!!.selectedItem)
    }

    /** 面板层 round-trip：默认流快照 → 另存「边界流」→ 清态切回 → 参数/勾选/foreach 逐字节还原。 */
    fun testT04_面板方案roundTrip_切换还原() {
        val p = openPanel()
        val cache = p.paramHistoryCacheForTest()
        val key = p.historyCacheKeyForTest()!!
        // 默认流：改 foreach 权威值 + 关掉一个默认勾选，快照落默认方案
        p.setForEachValuesForTest("statusList", listOf("1", "2"))
        p.applyXmlForEachValuesForTest("statusList", "1,2")
        val defaultDisabled = p.enabledIdsForTest().first()
        p.toggleCheckForTest(defaultDisabled, false)
        p.flushHistoryForTest()
        val defaultSnapshot = cache.recall(key)!!

        // 边界流：另一组参数（foreach 权威值不同），快照直取不走 flush——
        // flush 会把边界值写进当前激活的默认方案，覆盖掉要断言的默认快照
        p.clearStateForTest()
        p.setForEachValuesForTest("statusList", listOf("8", "9"))
        p.applyXmlForEachValuesForTest("statusList", "8,9")
        val boundarySnapshot = p.buildHistorySnapshotForTest()
        cache.saveAsScheme("边界流", key, boundarySnapshot)
        p.onSchemesChangedForTest() // 对话框回调链：同步下拉
        assertEquals("另存后下拉应选中「边界流」", "边界流", p.schemeComboForTest()!!.selectedItem)

        // 边界流 → 默认：清态切换后默认快照逐字节还原
        p.switchSchemeForTest(ParamHistoryCache.DEFAULT_SCHEME)
        assertEquals("切回默认方案应还原默认快照", defaultSnapshot, cache.recall(key))
        assertEquals("默认被禁勾选应保持禁用", false, p.toggleCheckSelectedForTest(defaultDisabled))
        assertEquals("默认 foreach 权威值应还原", listOf("1", "2"), p.forEachValuesForTest()["statusList"])

        // 默认 → 边界流：边界快照还原（清场不得残留默认流残值——防串桶的用户可见面）
        p.switchSchemeForTest("边界流")
        assertEquals("切到边界流应还原边界快照", boundarySnapshot, cache.recall(key))
        assertEquals("边界 foreach 权威值应还原", listOf("8", "9"), p.forEachValuesForTest()["statusList"])
    }

    /** 防串桶（面板层）：flushHistoryToCache 必须以「取快照时刻」的激活方案落库——
     *  在命名方案下取快照后（模拟池化线程延迟写回前）切走，写回不得串进默认方案。 */
    fun testT04_防串桶_快照随捕获方案落库() {
        val p = openPanel()
        val cache = p.paramHistoryCacheForTest()
        val key = p.historyCacheKeyForTest()!!
        // 默认方案先落一份基准快照
        p.setForEachValuesForTest("statusList", listOf("1"))
        p.applyXmlForEachValuesForTest("statusList", "1")
        p.flushHistoryForTest()
        val defaultSnapshot = cache.recall(key)!!
        // 激活命名方案，取快照（flushForTest 内部捕获激活名），随后立刻切回默认
        cache.saveAsScheme("边界流", key, mapOf("id" to "9", "__fev__:statusList" to "8"))
        p.onSchemesChangedForTest()
        p.setForEachValuesForTest("statusList", listOf("5"))
        p.applyXmlForEachValuesForTest("statusList", "5")
        p.flushHistoryForTest() // 捕获方案 = 边界流
        p.switchSchemeForTest(ParamHistoryCache.DEFAULT_SCHEME)
        // 写回已完成：默认方案快照必须仍是默认流（未被边界流的延迟写回覆盖）
        assertEquals("默认方案快照不得被命名方案的写回串写", defaultSnapshot, cache.recall(key))
        assertEquals("边界流快照应含其捕获的值", "5", cache.recallFromScheme("边界流", key)!!["__fev__:statusList"])
    }

    // ==== O-88：预设行折叠件（平台标准 HideableTitledPanel） ====

    /** O-88 ①：缺省展开——折叠件在场、isExpanded=true、锚点方案行可见且挂在折叠件内容区。 */
    fun testO88_预设折叠件缺省展开() {
        val p = openPanel()
        val fold = p.presetFoldForTest()
        assertNotNull("参数区应有预设折叠件（O-88）", fold)
        assertTrue("缺省应展开", fold!!.isExpanded)
        val row = p.schemeRowForTest()
        assertNotNull("折叠内容里应有 SCHEME_ROW_NAME 锚点行", row)
        assertTrue("展开态下方案行可见", row!!.isVisible)
        assertSame(
            "方案行应挂在折叠件内容区（非 paramPanel 直插）",
            fold,
            javax.swing.SwingUtilities.getAncestorOfClass(HideableTitledPanel::class.java, row),
        )
    }

    /** O-88 ②：折叠后内容区收起（平台件源码语义：off() 将 content setVisible(false)，不占竖向空间）。 */
    fun testO88_折叠后内容区收起() {
        val p = openPanel()
        val fold = p.presetFoldForTest()!!
        fold.setOn(false)
        assertFalse("isExpanded 应为 false", fold.isExpanded)
        assertFalse("方案行应不可见（内容区收起）", p.schemeRowForTest()!!.isVisible)
    }

    /** O-88 ③：折叠态跨预览重建保留——折叠后同一面板重新 showResult，重建的折叠件仍收起（不持久化）。 */
    fun testO88_折叠态跨预览重建保留() {
        val p = openPanel()
        val oldFold = p.presetFoldForTest()!!
        oldFold.setOn(false)

        // 切换语句语义：同一面板重新 showResult → rebuildLeftPanel → paramPanel.removeAll + 方案行重建
        p.showResult(buildProbePreviewResult())
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()

        val newFold = p.presetFoldForTest()
        assertNotNull("重建后应有新的折叠件", newFold)
        assertNotSame("参数区重建应重建出新的折叠件实例", oldFold, newFold)
        assertFalse("重建后折叠态应保留（静态字段）", newFold!!.isExpanded)
        assertFalse("重建后方案行仍收起", p.schemeRowForTest()!!.isVisible)
    }

    /**
     * O-88 ④：折叠态跨面板实例保留——真机走查实证：gutter 换语句走「关旧弹窗、开新面板实例」，
     * 折叠态为实例字段时新实例归零丢失；静态化后新实例初始应继承折叠态（仍不持久化，非 PropertiesComponent）。
     */
    fun testO88_折叠态跨面板实例保留() {
        // 实例 A：折叠后触发一次重建，懒惰捕获把折叠态回写静态字段（等价换语句/清空路径）
        val a = openPanel()
        a.presetFoldForTest()!!.setOn(false)
        a.showResult(buildProbePreviewResult())
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
        assertFalse("A 重建后应仍折叠（懒惰捕获回写静态字段）", a.presetFoldForTest()!!.isExpanded)
        assertTrue("静态字段应已被 A 回写为折叠", SqlPreviewPanel.presetCollapsedForTest())

        // 模拟 gutter 换语句：关旧弹窗（清空+dispose A）→ 开新面板实例 B
        // （clear() 先走 releaseEditor——测试里组件不在容器、removeNotify 不会触发，直接 dispose 会漏编辑器）
        a.clear()
        Disposer.dispose(a)
        panel = null
        val b = SqlPreviewPanel(project)
        panel = b
        Disposer.register(testRootDisposable, b)
        b.showResult(buildProbePreviewResult())
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()

        assertFalse("新实例 B 初始应继承折叠态（跨实例保留）", b.presetFoldForTest()!!.isExpanded)
        assertFalse("B 的方案行应收起", b.schemeRowForTest()!!.isVisible)
        assertTrue("静态字段仍是折叠（B 未被重置）", SqlPreviewPanel.presetCollapsedForTest())
    }
}
