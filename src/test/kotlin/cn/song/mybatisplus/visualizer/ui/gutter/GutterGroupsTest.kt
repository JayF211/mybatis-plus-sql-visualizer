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

    /** 空壳类仅用于 enabledFor 的组解析路径。 */
    private class XmlSqlGutterLineMarkerProviderStub
}
