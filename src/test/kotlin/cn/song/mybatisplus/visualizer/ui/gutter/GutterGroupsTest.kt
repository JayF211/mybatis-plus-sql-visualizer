package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2（W2 体验包）：gutter 分组显隐纯逻辑测试——组映射完整性 + 组开关读取。
 */
class GutterGroupsTest {

    @Test
    fun testR2_14个provider_全有组映射() {
        val providers = listOf(
            "SqlGutterLineMarkerProvider", "AnnotationSqlGutterLineMarkerProvider",
            "XmlSqlGutterLineMarkerProvider", "PagesXmlGutterLineMarkerProvider",
            "HbmNamedQueryGutterLineMarkerProvider", "XmlInlineSqlGutterLineMarkerProvider",
            "JpaGutterLineMarkerProvider", "JpqlGutterLineMarkerProvider",
            "CriteriaQueryGutterLineMarkerProvider", "HqlGutterLineMarkerProvider",
            "CustomDaoHqlGutterLineMarkerProvider", "JdbcTemplateSqlGutterLineMarkerProvider",
            "NamedQueryGutterLineMarkerProvider", "StringSqlMethodGutterLineMarkerProvider",
        )
        providers.forEach { name ->
            val group = GutterGroups.groupOf(name)
            assertTrue("provider $name 应有合法组：实际 $group", group in GutterGroups.GROUPS)
        }
        // 未登记 provider 落 other
        assertEquals("other", GutterGroups.groupOf("UnknownProvider"))
    }

    @Test
    fun testR2_组枚举_六组齐全() {
        assertEquals(
            listOf("mapperXml", "annotation", "jpaHql", "jdbc", "inlineFallback", "other"),
            GutterGroups.GROUPS,
        )
    }

    @Test
    fun testR2_组开关读取_字段映射正确() {
        val s = PluginSettings.State()
        // 默认全开
        GutterGroups.GROUPS.forEach { group ->
            assertTrue("默认 $group 应开", GutterGroups.groupEnabled(group, s))
        }
        // 逐组关闭断言
        s.gutterGroupMapperXml = false
        assertFalse(GutterGroups.groupEnabled("mapperXml", s))
        s.gutterGroupInlineFallback = false
        assertFalse(GutterGroups.groupEnabled("inlineFallback", s))
        s.gutterGroupOther = false
        assertFalse(GutterGroups.groupEnabled("other", s))
    }

    @Test
    fun testR2_总闸关_组开关再开也关() {
        // enabledFor 语义：showGutterIcon=false 时无论组开关如何都 false
        assertFalse(GutterGroups.enabledFor(XmlSqlGutterLineMarkerProviderStub::class.java, false))
    }

    @Test
    fun testF36_enabledFor_state重载_真值表() {
        // F36（T-PerfSweep16）：(Class, State?) 重载的四态真值表。
        // stub 未登记 → 落 other 组；组映射本身已由 testR2_组开关读取 钉住，此处钉总闸×组的组合语义。
        val stub = XmlSqlGutterLineMarkerProviderStub::class.java

        // 组开 × 图标开 = true
        val allOn = PluginSettings.State()
        assertTrue(GutterGroups.enabledFor(stub, allOn))

        // 组关 × 图标开 = false
        val groupOff = PluginSettings.State().apply { gutterGroupOther = false }
        assertFalse(GutterGroups.enabledFor(stub, groupOff))

        // 任意 × 图标关 = false（组开/组关都关）
        val iconOffAllGroupsOn = PluginSettings.State().apply { showGutterIcon = false }
        assertFalse(GutterGroups.enabledFor(stub, iconOffAllGroupsOn))
        val iconOffAndGroupOff = PluginSettings.State().apply {
            showGutterIcon = false
            gutterGroupOther = false
        }
        assertFalse(GutterGroups.enabledFor(stub, iconOffAndGroupOff))

        // state = null（设置服务不可读的失败回退）= true
        assertTrue(GutterGroups.enabledFor(stub, null))
    }

    /**
     * F51（T-PerfQuick5）：XmlInline 快路径两道闸并为一道单次取 state 合取式——
     * 「旧两道闸串联」（gutterFastPathEnabled 语义 + enabledFor(Class, Boolean) 语义）
     * 与「新并闸」（provider 修复后逐行同构）在同 State 集上逐值等价。
     * 对照证明语义等价；性能面（2 次→1 次服务查找）由 GutterProviderSingleLookupGuardTest 钉住。
     */
    @Test
    fun testF51_xmlInline并闸合成_新旧形态逐值等价() {
        val stub = XmlSqlGutterLineMarkerProviderStub::class.java

        // 旧形态：闸1 = runCatching { s.xmlInlineSqlEnabled && s.showGutterIcon }.getOrDefault(true)；
        //         闸2 = enabledFor(javaClass, true) =（字面 true 跳过总闸短路）s 可读时 showGutterIcon && 组开，不可读回退 true
        fun oldComposition(s: PluginSettings.State?): Boolean {
            val fast = if (s == null) true else s.xmlInlineSqlEnabled && s.showGutterIcon
            val group = if (s == null) true else GutterGroups.enabledFor(stub, s)
            return fast && group
        }

        // 新形态（provider getLineMarkerInfo 修复后逐行同构）
        fun newComposition(s: PluginSettings.State?): Boolean {
            if (s == null) return true
            if (!s.xmlInlineSqlEnabled || !s.showGutterIcon) return false
            return GutterGroups.enabledFor(stub, s)
        }

        val samples = listOf(
            null,
            PluginSettings.State(),
            PluginSettings.State().apply { xmlInlineSqlEnabled = false },
            PluginSettings.State().apply { showGutterIcon = false },
            PluginSettings.State().apply { gutterGroupOther = false },
            PluginSettings.State().apply {
                xmlInlineSqlEnabled = false
                showGutterIcon = false
                gutterGroupOther = false
            },
        )
        samples.forEachIndexed { i, s ->
            assertEquals("样本 $i 新旧并闸判定须逐值等价", oldComposition(s), newComposition(s))
        }
        // 关键负样本独立可判（防「两边恒真/恒假」的假对照）
        assertTrue(newComposition(PluginSettings.State()))
        assertFalse(newComposition(PluginSettings.State().apply { xmlInlineSqlEnabled = false }))
        assertFalse(newComposition(PluginSettings.State().apply { gutterGroupOther = false }))
    }

    /** 空壳类仅用于 enabledFor 的组解析路径。 */
    private class XmlSqlGutterLineMarkerProviderStub
}
