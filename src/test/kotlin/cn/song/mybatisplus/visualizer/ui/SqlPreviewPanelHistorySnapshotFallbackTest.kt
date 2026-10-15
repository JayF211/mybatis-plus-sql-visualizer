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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * R51（O-105 余半·主体）：recall 链参数桥面板层测试——ParamHistoryCache 未命中时按缓存键
 * 回落 SqlHistoryEntry.paramSnapshot 回填参数面板（普通值 + R22 特殊键全格式兼容），
 * 缓存命中优先；flush 链把快照同步回写历史条目（sink seam 观察）。
 *
 * 生产链（真 SqlHistoryManager）在单测态禁用（APP 级服务跨用例残留会串扰既有 recall 断言），
 * 由末位源码扫描守卫钉住接线不缺（照 ExplainPanelWiringGuardTest 先例）。
 */
class SqlPreviewPanelHistorySnapshotFallbackTest : BasePlatformTestCase() {

    private var panel: SqlPreviewPanel? = null

    override fun setUp() {
        super.setUp()
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
        runCatching { PluginSettings.getInstance().state.formatSql = false }
        runCatching { SqlPreviewPanel.resetPresetCollapsedForTest() }
    }

    override fun tearDown() {
        DocWriteContext.writeAllowedProbe = null
        runCatching { panel?.paramHistoryCacheForTest()?.clear() }
        runCatching { panel?.clear() }
        runCatching { PluginSettings.getInstance().state.formatSql = true }
        panel = null
        super.tearDown()
    }

    /** 普通参数语料（无 xml/batch 模型 → 参数行主链）。 */
    private fun buildPlainParamsResult(
        params: List<String>,
        sql: String = "SELECT * FROM t_order WHERE alpha = ? AND beta = ?",
    ): PreviewResult = PreviewResult(
        sql = sql,
        compactSql = sql,
        unresolvedParams = params,
        paramTable = ParameterReplacer.buildParamTable(params),
        wrapperType = null,
        tableName = null,
        xmlModel = null,
    )

    private fun openPanel(result: PreviewResult): SqlPreviewPanel {
        val p = SqlPreviewPanel(project)
        panel = p
        Disposer.register(testRootDisposable, p)
        p.showResult(result)
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
        return p
    }

    /** R51 核心：缓存未命中（模拟 LRU 淘汰/清缓存）→ 历史参数快照回落，普通参数回填面板。 */
    fun testR51_缓存未命中回落历史参数快照() {
        val p = openPanel(buildPlainParamsResult(listOf("alpha", "beta")))
        val key = p.historyCacheKeyForTest()!!
        p.paramHistoryCacheForTest().clear() // 参数缓存整体丢失
        p.clearStateForTest()
        p.historySnapshotFallbackSource = { k -> if (k == key) mapOf("alpha" to "A1", "beta" to "B2") else null }

        p.recallFromHistoryForTest()

        assertEquals("alpha 框应从历史快照回填", "A1", p.paramFieldForSourceForTest("alpha")?.text)
        assertEquals("beta 框应从历史快照回填", "B2", p.paramFieldForSourceForTest("beta")?.text)
        assertEquals("paramValues 同步回填", mapOf("alpha" to "A1", "beta" to "B2"), p.paramValuesForTest())
    }

    /** 缓存命中优先——回落源不被咨询（缓存是活值，历史快照是时点值）。 */
    fun testR51_缓存命中时不咨询历史回落() {
        val p = openPanel(buildPlainParamsResult(listOf("alpha", "beta")))
        p.loadSnapshotForTest(mapOf("alpha" to "CACHE"))
        var consulted = false
        p.historySnapshotFallbackSource = { consulted = true; mapOf("alpha" to "HIST") }
        p.clearStateForTest()

        p.recallFromHistoryForTest()

        assertEquals("缓存值应胜出", "CACHE", p.paramFieldForSourceForTest("alpha")?.text)
        assertFalse("缓存命中不应查历史回落", consulted)
    }

    /** 回落源未命中（历史也无该键快照）→ 召回零动作（既有缺省行为不回归）。 */
    fun testR51_历史也无快照时召回零动作() {
        val p = openPanel(buildPlainParamsResult(listOf("alpha", "beta")))
        p.paramHistoryCacheForTest().clear()
        p.clearStateForTest()
        p.historySnapshotFallbackSource = { null }

        p.recallFromHistoryForTest()

        assertEquals("无源可落时应保持清空态", "", p.paramFieldForSourceForTest("alpha")?.text)
    }

    /** R22 特殊键全格式兼容：历史快照与缓存同源（buildHistorySnapshot），`__fev__`/`__chk__` 一并还原。 */
    fun testR51_历史快照特殊键foreach与勾选态还原() {
        val mapper = """
            <mapper namespace="com.example.ProbeMapper">
              <select id="probe">
                SELECT * FROM t_order
                <where>
                  <if test="onlyActive != null and onlyActive">AND status = 1</if>
                  <if test="statusList != null and statusList.size() > 0">
                    AND status IN
                    <foreach collection="statusList" item="st" open="(" separator="," close=")">#{st}</foreach>
                  </if>
                </where>
              </select>
            </mapper>
        """.trimIndent()
        val parsed = XmlMapperParser().parse(mapper)
        val model = XmlPreviewModel.from(
            parsed.statements.single(), parsed.errors,
            paramTypes = mapOf(
                "statusList" to ParamTypeInfo(ParamKind.COLLECTION, elementKind = ParamKind.NUMBER),
                "onlyActive" to ParamTypeInfo(ParamKind.UNKNOWN),
            ),
        )
        val rendered = model.renderSql()
        val result = PreviewResult(
            sql = rendered.sql,
            compactSql = rendered.sql.replace(Regex("\\s+"), " ").trim(),
            unresolvedParams = rendered.mappings.map { it.property },
            paramTable = ParameterReplacer.buildParamTable(rendered.mappings.map { it.property }),
            wrapperType = null,
            tableName = null,
            xmlModel = model,
        )
        val p = openPanel(result)
        val key = p.historyCacheKeyForTest()!!
        val defaults = p.enabledIdsForTest()
        assertTrue("语料应有默认勾选条件", defaults.isNotEmpty())
        val idToDisable = defaults.first()
        p.paramHistoryCacheForTest().clear()
        p.clearStateForTest()
        p.historySnapshotFallbackSource = { k ->
            if (k == key) mapOf("__fev__:statusList" to "1,2", "__chk__:$idToDisable" to "0") else null
        }

        p.recallFromHistoryForTest()

        assertEquals("foreach 权威值应从历史快照还原", listOf("1", "2"), p.forEachValuesForTest()["statusList"])
        assertEquals("可见字段文本应还原", "1,2", p.forEachValueFieldTextForTest("statusList"))
        assertEquals("被禁勾选应保持禁用（0 编码生效）", false, p.toggleCheckSelectedForTest(idToDisable))
    }

    /** flush 链同步回写：参数编辑落库时快照经 sink 进历史条目（回写链观察）。 */
    fun testR51_flush链同步回写历史快照() {
        val p = openPanel(buildPlainParamsResult(listOf("alpha", "beta")))
        val key = p.historyCacheKeyForTest()!!
        var capturedKey: String? = null
        var capturedSnapshot: Map<String, String>? = null
        p.historySnapshotSink = { k, s -> capturedKey = k; capturedSnapshot = s }

        p.paramFieldForSourceForTest("alpha")!!.text = "zhangsan"
        p.flushHistoryForTest()

        assertEquals("sink 应收到当前缓存键", key, capturedKey)
        assertEquals("sink 应收到含编辑值的快照", "zhangsan", capturedSnapshot!!["alpha"])
    }

    /** 单测态默认禁用真服务：未注入 seam 时回落恒空（既有 recall 断言面零扰动）。 */
    fun testR51_未注入seam时回落恒空() {
        val p = openPanel(buildPlainParamsResult(listOf("alpha", "beta")))
        p.paramHistoryCacheForTest().clear()
        p.clearStateForTest()

        p.recallFromHistoryForTest()

        assertEquals("", p.paramFieldForSourceForTest("alpha")?.text)
    }

    // ---------- 接线守卫（源码扫描，照 ExplainPanelWiringGuardTest 先例） ----------

    /** 生产链必须在位：回落/回写两向都接真 SqlHistoryManager（防 seam 默认化成死代码）。 */
    fun testR51_生产接线在位() {
        val source = sqlPreviewPanelSource()
        assertTrue(
            "recall 回落必须接 SqlHistoryManager.newestParamSnapshot（否则历史快照桥成死代码）",
            source.contains("SqlHistoryManager.getInstance().newestParamSnapshot"),
        )
        assertTrue(
            "flush 回写必须接 SqlHistoryManager.updateNewestParamSnapshot（否则历史条目永远无快照）",
            source.contains("SqlHistoryManager.getInstance().updateNewestParamSnapshot"),
        )
        assertTrue(
            "recall 链必须把回落源接进未命中分支",
            source.contains("val cached = paramHistoryCache.recall(key)") &&
                source.contains("val history = cached ?: historySnapshotFallback(key) ?: return"),
        )
        assertTrue(
            "flush 链必须调用历史快照回写",
            source.contains("updateHistoryEntrySnapshot(key, snapshot)"),
        )
    }

    /**
     * 面板源文件并集（T-M53-II D2-5 拆分后读取面：EXPLAIN/参数面板/执行链等成员已逐字符搬出到
     * 同包协作文件，只读主文件会在成员搬走后变成空扫描——断言假红或假绿的同一根因）。
     */
    private fun sqlPreviewPanelSource(): String {
        val dir = File(repoRoot(), "src/main/kotlin/cn/song/mybatisplus/visualizer/ui")
        return dir.listFiles { f -> f.name.startsWith("SqlPreviewPanel") && f.name.endsWith(".kt") }
            ?.sortedBy { it.name }
            ?.joinToString("\n") { it.readText() }
            ?: error("未找到面板源文件（SqlPreviewPanel*.kt）")
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile
        }
        error("未定位到仓库根: ${System.getProperty("user.dir")}")
    }
}
