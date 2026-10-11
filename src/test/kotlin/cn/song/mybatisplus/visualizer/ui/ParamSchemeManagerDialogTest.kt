package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * R22 T-04：[ParamSchemeManagerDialog] 行为测试（headless，输入/确认对话框经 `*ForTest` 缝旁路）。
 *
 * 覆盖：列表形态（默认方案恒首位）/ 另存为（快照由 snapshotProvider 注入）/ 重命名 / 删除 /
 * 默认方案保护 / 状态行分流。DimensionServiceKey 常量断言钉住 O-80 形态
 * 一并以常量断言钉住——照抄 SqlSuppressManagerDialog 形态防自创回归。
 */
class ParamSchemeManagerDialogTest : BasePlatformTestCase() {

    private lateinit var cache: ParamHistoryCache
    private var changedCount = 0
    private var dialog: ParamSchemeManagerDialog? = null

    /** 快照注入桩：返回固定 (key, params)；null 模拟「当前无预览可存」。 */
    private var snapshotStub: Pair<String, Map<String, String>>? =
        "stmt-key" to mapOf("id" to "1", "__chk__:flag" to "0")

    override fun setUp() {
        super.setUp()
        // O-97 治理（T-TestPerf15）：进态恢复出厂默认，截断跨类全局设置态泄漏
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
        runCatching { PluginSettings.getInstance().state.formatSql = false }
        cache = ParamHistoryCache()
        cache.clear()
        changedCount = 0
    }

    override fun tearDown() {
        runCatching { dialog?.dispose() }
        dialog = null
        runCatching { cache.clear() }
        runCatching { PluginSettings.getInstance().state.formatSql = true }
        super.tearDown()
    }

    /** bundle 取值（断言与文案同源，免硬编码中文受运行 locale 摆布）。 */
    private fun bundle(key: String, vararg args: String): String = MpSqlVisualizerBundle.message(key, *args)

    private fun openDialog(): ParamSchemeManagerDialog {
        val d = ParamSchemeManagerDialog(
            project,
            cache,
            { snapshotStub },
            { changedCount++ },
        )
        dialog = d
        return d
    }

    fun testT04_列表形态_默认方案恒首位() {
        cache.saveAsScheme("边界流", "stmt-key", mapOf("id" to "1"))
        val d = openDialog()
        assertEquals("列表 = 默认方案 + 命名方案", 2, d.schemeCountForTest())
        assertEquals("首位应为默认方案（空串内部名）", ParamHistoryCache.DEFAULT_SCHEME, d.schemeNameAtForTest(0))
        assertEquals("第二位应为命名方案", "边界流", d.schemeNameAtForTest(1))
        assertEquals("初始应选中激活方案", "边界流", d.selectedSchemeForTest())
        d.dispose()
    }

    fun testT04_默认方案保护_改名删除禁用与状态行() {
        cache.saveAsScheme("边界流", "stmt-key", mapOf("id" to "1"))
        val d = openDialog()
        d.selectSchemeForTest(ParamHistoryCache.DEFAULT_SCHEME) // 初始选中激活方案，显式切到默认方案
        // 首位 = 默认方案：重命名/删除按钮禁用（数据面 DEFAULT_PROTECTED 之外的 UI 层双保险）
        assertFalse("选中默认方案时重命名应禁用", d.renameEnabledForTest())
        assertFalse("选中默认方案时删除应禁用", d.deleteEnabledForTest())
        assertFalse("默认方案改名应拒绝", d.renameForTest("X"))
        assertEquals("状态行应提示保护", bundle("panel.scheme.dialog.defaultProtected"), d.statusTextForTest())
        d.dispose()
    }

    fun testT04_另存为_快照注入与回调() {
        val d = openDialog()
        assertTrue("另存为应成功", d.saveAsForTest("边界流"))
        assertEquals("状态行应确认保存", bundle("panel.scheme.dialog.saved", "边界流"), d.statusTextForTest())
        assertEquals("回调应触发一次", 1, changedCount)
        assertEquals("cache 应有新方案", listOf("边界流"), cache.schemeNames())
        assertEquals("另存应切换激活方案", "边界流", cache.activeSchemeName())
        assertEquals("快照应按注入内容落库", mapOf("id" to "1", "__chk__:flag" to "0"), cache.recallFromScheme("边界流", "stmt-key"))
        d.dispose()
    }

    fun testT04_另存为_重名与空名与无快照() {
        cache.saveAsScheme("边界流", "stmt-key", mapOf("id" to "1"))
        val d = openDialog()
        assertFalse("重名另存应拒绝", d.saveAsForTest("边界流"))
        assertEquals("状态行应提示重名", bundle("panel.scheme.dialog.duplicate"), d.statusTextForTest())
        assertFalse("空名另存应拒绝", d.saveAsForTest("   "))
        assertEquals("状态行应提示空名", bundle("panel.scheme.dialog.blank"), d.statusTextForTest())
        snapshotStub = null
        assertFalse("无快照另存应拒绝", d.saveAsForTest("新方案"))
        assertEquals("状态行应提示无快照", bundle("panel.scheme.dialog.noSnapshot"), d.statusTextForTest())
        assertEquals("回调不应触发", 0, changedCount)
        d.dispose()
    }

    fun testF49_另存为_方案个数上限状态行() {
        repeat(ParamHistoryCache.MAX_SCHEME_COUNT) { i ->
            cache.saveAsScheme("S$i", "stmt-key", mapOf("id" to "$i"))
        }
        val d = openDialog()
        assertFalse("达上限后另存应拒绝", d.saveAsForTest("溢出方案"))
        assertEquals(
            "状态行应提示上限",
            bundle("panel.scheme.dialog.limitReached", ParamHistoryCache.MAX_SCHEME_COUNT.toString()),
            d.statusTextForTest(),
        )
        assertEquals("回调不应触发", 0, changedCount)
        assertFalse("被拒方案不应入库", cache.hasScheme("溢出方案"))
        d.dispose()
    }

    fun testT04_重命名_成功与重名拒绝() {
        cache.saveAsScheme("A", "stmt-key", mapOf("id" to "1"))
        val d = openDialog()
        d.selectSchemeForTest("A")
        assertTrue("重命名应成功", d.renameForTest("A2"))
        assertEquals("状态行应确认重命名", bundle("panel.scheme.dialog.renamed", "A2"), d.statusTextForTest())
        assertEquals("cache 应只剩新名", listOf("A2"), cache.schemeNames())
        assertEquals("回调应触发（另存1次未发生，重命名1次）", 1, changedCount)
        d.selectSchemeForTest("A2")
        assertFalse("重命名到已存在名应拒绝", d.renameForTest("A2"))
        assertEquals("状态行应提示重名", bundle("panel.scheme.dialog.duplicate"), d.statusTextForTest())
        d.dispose()
    }

    fun testT04_删除_确认后移除且回调() {
        cache.saveAsScheme("A", "stmt-key", mapOf("id" to "1"))
        cache.switchScheme(ParamHistoryCache.DEFAULT_SCHEME)
        cache.saveAsScheme("B", "stmt-key", mapOf("id" to "2"))
        val d = openDialog()
        d.selectSchemeForTest("B")
        assertTrue("删除应成功", d.deleteForTest())
        assertEquals("状态行应确认删除", bundle("panel.scheme.dialog.deleted", "B"), d.statusTextForTest())
        assertEquals("cache 应移除 B", listOf("A"), cache.schemeNames())
        assertEquals("删除激活方案后激活应归位默认方案", ParamHistoryCache.DEFAULT_SCHEME, cache.activeSchemeName())
        assertEquals("回调应触发", 1, changedCount)
        d.dispose()
    }

    fun testT04_UI形态契约_照抄SqlSuppressManagerDialog() {
        // isResizable 断言缺席：DialogWrapper.isResizable 委托 peer 的 Window，
        // headless 未 show 恒 false（环境受限未验证）——真机走查覆盖（isResizable=true
        // + getDimensionServiceKey 与 SqlSuppressManagerDialog O-80 同款形态）
        val d = openDialog()
        assertEquals(
            "DimensionService 键应对齐设计定稿",
            "mpsv.paramscheme.manager.dialog",
            d.dimensionServiceKeyForTest(),
        )
        d.dispose()
    }
}
