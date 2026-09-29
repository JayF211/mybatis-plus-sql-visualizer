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

    /** 空壳类仅用于 enabledFor 的组解析路径。 */
    private class XmlSqlGutterLineMarkerProviderStub
}
