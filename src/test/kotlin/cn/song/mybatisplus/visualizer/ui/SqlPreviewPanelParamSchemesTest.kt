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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    }

    override fun tearDown() {
        DocWriteContext.writeAllowedProbe = null
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

    private fun openPanel(): SqlPreviewPanel {
        val parsed = XmlMapperParser().parse(mapper)
        val model = XmlPreviewModel.from(
            parsed.statements.single(), parsed.errors,
            paramTypes = mapOf(
                "statusList" to ParamTypeInfo(ParamKind.COLLECTION, elementKind = ParamKind.NUMBER),
                "onlyActive" to ParamTypeInfo(ParamKind.UNKNOWN),
            ),
        )
        val rendered = model.renderSql()
        val p = SqlPreviewPanel(project)
        panel = p
        Disposer.register(testRootDisposable, p)
        p.showResult(
            PreviewResult(
                sql = rendered.sql,
                compactSql = rendered.sql.replace(Regex("\\s+"), " ").trim(),
                unresolvedParams = rendered.mappings.map { it.property },
                paramTable = ParameterReplacer.buildParamTable(rendered.mappings.map { it.property }),
                wrapperType = null,
                tableName = null,
                xmlModel = model,
            )
        )
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
        println("DIAG conditionCheckBoxes=${p.conditionCheckBoxCountForTest()} foreachNullBoxes=${p.forEachNullBoxCountForTest()} enabledIds=${p.enabledIdsForTest()}")
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
}
