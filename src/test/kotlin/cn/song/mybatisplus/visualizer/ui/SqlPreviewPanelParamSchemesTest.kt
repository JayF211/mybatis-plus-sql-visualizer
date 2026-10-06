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
        // O-97 治理（T-TestPerf15）：进态恢复出厂默认，截断跨类全局设置态泄漏
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
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

    /** O-88 ①：缺省折叠——折叠件在场、isExpanded=false、锚点方案行在内容区但不可见。
     *  T-DevAcceptFix ①（20261006 验收）：缺省由展开翻折叠（预设低频，默认不占竖向空间）。 */
    fun testO88_预设折叠件缺省折叠() {
        val p = openPanel()
        val fold = p.presetFoldForTest()
        assertNotNull("参数区应有预设折叠件（O-88）", fold)
        assertFalse("缺省应折叠（20261006 验收定案）", fold!!.isExpanded)
        val row = p.schemeRowForTest()
        assertNotNull("折叠内容区仍应含 SCHEME_ROW_NAME 锚点行", row)
        assertFalse("折叠态下方案行不可见", row!!.isVisible)
        assertSame(
            "方案行应挂在折叠件内容区（非 paramPanel 直插）",
            fold,
            javax.swing.SwingUtilities.getAncestorOfClass(HideableTitledPanel::class.java, row),
        )
    }

    /** O-88 ②：展开后内容区显示（平台件源码语义：setOn(true) 内容 setVisible(true)）。 */
    fun testO88_展开后方案行可见() {
        val p = openPanel()
        val fold = p.presetFoldForTest()!!
        fold.setOn(true)
        assertTrue("isExpanded 应为 true", fold.isExpanded)
        assertTrue("展开态下方案行应可见", p.schemeRowForTest()!!.isVisible)
    }

    /** O-88 ③：展开态跨预览重建保留——展开后同一面板重新 showResult，重建的折叠件仍展开（不持久化）。 */
    fun testO88_展开态跨预览重建保留() {
        val p = openPanel()
        val oldFold = p.presetFoldForTest()!!
        oldFold.setOn(true)

        // 切换语句语义：同一面板重新 showResult → rebuildLeftPanel → paramPanel.removeAll + 方案行重建
        p.showResult(buildProbePreviewResult())
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()

        val newFold = p.presetFoldForTest()
        assertNotNull("重建后应有新的折叠件", newFold)
        assertNotSame("参数区重建应重建出新的折叠件实例", oldFold, newFold)
        assertTrue("重建后展开态应保留（静态字段）", newFold!!.isExpanded)
        assertTrue("重建后方案行可见", p.schemeRowForTest()!!.isVisible)
    }

    /**
     * O-88 ④：展开态跨面板实例保留——真机走查实证：gutter 换语句走「关旧弹窗、开新面板实例」，
     * 折叠态为实例字段时新实例归零丢失；静态化后新实例初始应继承折叠态（仍不持久化，非 PropertiesComponent）。
     *
     * 复验修复（dispose 链捕获）：真机「收起→取消→换语句」走 DialogWrapper.dispose→Disposer 级联
     * 释放面板，全程无 buildSchemeRow 调用——懒惰捕获不触发。本用例走真序列：展开后**不经重建**
     * 直接 dispose 释放 A，断言静态字段在 dispose 时被捕获（T-DevAcceptFix ① 后缺省=折叠，
     * 故本用例改以「展开态被捕获」验证同一机制），再开实例 B 验初始展开。
     */
    fun testO88_展开态跨面板实例保留() {
        // 实例 A：展开（测试缝 setOn(true)，等价用户点标题），不触发任何参数区重建
        val a = openPanel()
        a.presetFoldForTest()!!.setOn(true)
        assertTrue("懒惰捕获未触发（无重建路径），dispose 前静态字段应为缺省折叠", SqlPreviewPanel.presetCollapsedForTest())

        // 直接 dispose 释放 A（不经重建）——面板挂在宿主 disposable 树上，关闭路径级联走 panel.dispose()
        // （clear() 先走 releaseEditor——测试里组件不在容器、removeNotify 不会触发，直接 dispose 会漏编辑器；
        //   clear() 只 paramPanel.removeAll()，不触发 buildSchemeRow 懒惰捕获，序列仍为「直接释放」）
        a.clear()
        Disposer.dispose(a)
        panel = null
        assertFalse("dispose 释放 A 后静态字段应被捕获为展开（dispose 链捕获）", SqlPreviewPanel.presetCollapsedForTest())

        // 模拟 gutter 换语句：开新面板实例 B
        val b = SqlPreviewPanel(project)
        panel = b
        Disposer.register(testRootDisposable, b)
        b.showResult(buildProbePreviewResult())
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()

        assertTrue("新实例 B 初始应继承展开态（跨实例保留）", b.presetFoldForTest()!!.isExpanded)
        assertTrue("B 的方案行应可见", b.schemeRowForTest()!!.isVisible)
        assertFalse("静态字段仍是展开（B 未被重置）", SqlPreviewPanel.presetCollapsedForTest())
    }

    /**
     * O-88 ④b（保留）：懒惰捕获路径——展开后同实例触发重建（刷新/换语句不清弹窗的路径），
     * buildSchemeRow 开头懒惰回写静态字段，重建出的折叠件仍展开。
     */
    fun testO88_展开态懒惰捕获经重建回写() {
        val a = openPanel()
        a.presetFoldForTest()!!.setOn(true)
        a.showResult(buildProbePreviewResult())
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
        assertTrue("A 重建后应仍展开（懒惰捕获回写静态字段）", a.presetFoldForTest()!!.isExpanded)
        assertFalse("静态字段应已被 A 回写为展开", SqlPreviewPanel.presetCollapsedForTest())
    }

    // ==== T-DevAcceptFix ⑤：方案切换参数回写（source 锚点显式映射，禁插入序对位） ====

    /** 普通参数语料（无 xml/batch 模型 → 走参数行主链；paramFields 行序 = unresolvedParams 序）。
     *  [sql] 可指定不同语句文本以模拟「不同方法」（缓存键 = SQL 指纹 → 预设按方法隔离的判别面）。 */
    private fun buildPlainParamsResult(
        params: List<String>,
        sql: String = "SELECT * FROM t_order WHERE alpha = ? AND beta = ? AND gamma = ?",
    ): PreviewResult = PreviewResult(
        sql = sql,
        compactSql = sql,
        unresolvedParams = params,
        paramTable = ParameterReplacer.buildParamTable(params),
        wrapperType = null,
        tableName = null,
        xmlModel = null,
    )

    /**
     * T-DevAcceptFix2 ①（20261006 第二轮验收，用户反馈「应该是每个方法单独的预设互相隔离」）：
     * 预设按方法/SQL 隔离——A 方法的预设不出现在 B 方法的下拉里；同名预设可跨方法各自存值互不串；
     * 切回 A 恢复其自己的名单与激活态。
     */
    fun testDevAcceptFix2_预设按方法隔离_同名跨方法各自存值() {
        val p = SqlPreviewPanel(project)
        panel = p
        Disposer.register(testRootDisposable, p)
        val sqlA = "SELECT * FROM t_order WHERE alpha = ? AND beta = ? -- method A"
        val sqlB = "SELECT * FROM t_customer WHERE alpha = ? AND beta = ? -- method B"

        p.showResult(buildPlainParamsResult(listOf("alpha", "beta"), sqlA))
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
        val cache = p.paramHistoryCacheForTest()
        val keyA = p.historyCacheKeyForTest()!!
        cache.saveAsScheme("生产参数", keyA, linkedMapOf("alpha" to "A1"))
        p.onSchemesChangedForTest()
        assertEquals("A 方法应激活自己刚存的预设", "生产参数", p.schemeComboForTest()!!.selectedItem)
        assertEquals("A 方法下拉 = 默认 + 自己的预设", 2, p.schemeComboForTest()!!.itemCount)

        // 切到 B 方法（不同语句 → 不同缓存键）
        p.showResult(buildPlainParamsResult(listOf("alpha", "beta"), sqlB))
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
        val keyB = p.historyCacheKeyForTest()!!
        assertFalse("两个方法应是不同缓存键（键=SQL 指纹）", keyA == keyB)
        assertEquals("B 方法不应看到 A 的预设（按方法隔离）", 1, p.schemeComboForTest()!!.itemCount)
        assertEquals(
            "B 方法无自己的预设时激活态应回落默认",
            ParamHistoryCache.DEFAULT_SCHEME,
            p.schemeComboForTest()!!.selectedItem,
        )

        // B 方法下同名另存：不得因 A 已有同名而拒绝（查重口径=按键）
        assertEquals(
            "同名预设应可在 B 方法独立新建",
            ParamHistoryCache.SchemeOpResult.OK,
            cache.saveAsScheme("生产参数", keyB, linkedMapOf("alpha" to "B1")),
        )
        p.onSchemesChangedForTest()
        assertEquals("B 方法同名预设应可见并激活", "生产参数", p.schemeComboForTest()!!.selectedItem)
        assertEquals("A 键下值不受 B 影响", "A1", cache.recallFromScheme("生产参数", keyA)!!["alpha"])
        assertEquals("B 键下是自己的值", "B1", cache.recallFromScheme("生产参数", keyB)!!["alpha"])

        // 切回 A：名单与激活态各归其位
        p.showResult(buildPlainParamsResult(listOf("alpha", "beta"), sqlA))
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
        assertEquals("切回 A 应恢复其自己的激活预设", "生产参数", p.schemeComboForTest()!!.selectedItem)
        assertEquals("切回 A 名单仍只含自己的预设", 2, p.schemeComboForTest()!!.itemCount)
    }

    /**
     * T-DevAcceptFix ⑤ 红复现（20261006 验收缺陷「切换预设时下方参数没有变化」的错位形态）：
     * 方案快照键序与面板行构建序不一致时，切换方案后各参数输入框必须各归其位。
     *
     * 原实现以 `paramValues` 插入序对位 `paramFields` 行序（currentParamFieldIndex）——方案切换后
     * map 重填顺序=另一方案快照的保存顺序，与本面板行构建序漂移时回写错位（值写进别家输入框）。
     * 本用例刻意构造「快照键序 ≠ 行构建序」：修复前必红（alpha 框拿到 gamma 的值），修复后绿。
     */
    fun testDevAcceptFix05_跨方案快照键序漂移时参数回写各归其位() {
        val p = SqlPreviewPanel(project)
        panel = p
        Disposer.register(testRootDisposable, p)
        p.showResult(buildPlainParamsResult(listOf("alpha", "beta", "gamma")))
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()

        val cache = p.paramHistoryCacheForTest()
        val key = p.historyCacheKeyForTest()!!
        // 方案甲快照键序刻意与行构建序（alpha→beta→gamma）不同：gamma 打头
        cache.saveAsScheme("方案甲", key, linkedMapOf("gamma" to "G", "alpha" to "A", "beta" to "B"))

        p.switchSchemeForTest("方案甲")

        assertEquals("alpha 框应显示方案甲中 alpha 的值", "A", p.paramFieldForSourceForTest("alpha")?.text)
        assertEquals("beta 框应显示方案甲中 beta 的值", "B", p.paramFieldForSourceForTest("beta")?.text)
        assertEquals("gamma 框应显示方案甲中 gamma 的值", "G", p.paramFieldForSourceForTest("gamma")?.text)
    }
}
