package cn.song.mybatisplus.visualizer.ui.gutter

import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * T-H2（20260907-1630）：hbm 命名查询 gutter 图标测试——`<query>`/`<sql-query>` 行命中、
 * 非命名查询标签不命中（class/property/父级非 hibernate-mapping）、与 XmlSql provider 去重。
 */
class HbmNamedQueryGutterLineMarkerProviderTest : BasePlatformTestCase() {

    private val hbmContent = """<?xml version="1.0" encoding="UTF-8"?>
<hibernate-mapping>
    <class name="cn.song.jpademo.entity.LegacyItem" table="legacy_item">
        <id name="id" column="id"/>
        <property name="itemCode" column="item_code"/>
    </class>
    <query name="LegacyItem.findLowStockHql"><![CDATA[FROM LegacyItem li WHERE li.quantity <= :threshold]]></query>
    <sql-query name="LegacyItem.countByDeprecatedNative">SELECT COUNT(*) FROM legacy_item WHERE deprecated = :deprecated</sql-query>
</hibernate-mapping>
""".trimIndent()

    private fun tokenAt(file: XmlFile, tagName: String): XmlToken? =
        PsiTreeUtil.collectElementsOfType(file, XmlToken::class.java)
            .firstOrNull { it.tokenType == XmlTokenType.XML_NAME && it.text == tagName && it.parent is XmlTag }

    fun testIcon_onQueryAndSqlQueryTags() {
        val file = myFixture.configureByText("LegacyItem.hbm.xml", hbmContent) as XmlFile
        val provider = HbmNamedQueryGutterLineMarkerProvider()
        for (tag in listOf("query", "sql-query")) {
            val token = tokenAt(file, tag)
            assertNotNull("标签 $tag 的 XML_NAME token 应存在", token)
            assertNotNull("标签 $tag 行应产生预览图标", provider.getLineMarkerInfo(token!!))
        }
    }

    fun testNoIcon_onClassMappingTags() {
        val file = myFixture.configureByText("LegacyItem.hbm.xml", hbmContent) as XmlFile
        val provider = HbmNamedQueryGutterLineMarkerProvider()
        for (tag in listOf("class", "id", "property")) {
            val token = tokenAt(file, tag)
            assertNotNull(token)
            assertNull("映射标签 $tag 不应产生命名查询图标", provider.getLineMarkerInfo(token!!))
        }
    }

    fun testNoIcon_whenParentNotHibernateMapping() {
        // mapper XML 的同名标签（本插件 XmlSql provider 领域）不认领——父标签互斥去重
        val content = """<mapper namespace="x">
    <query name="foo.bar">SELECT 1</query>
</mapper>
""".trimIndent()
        val file = myFixture.configureByText("FooMapper.xml", content) as XmlFile
        val token = tokenAt(file, "query")
        assertNotNull(token)
        assertNull("父标签非 hibernate-mapping 不应认领", HbmNamedQueryGutterLineMarkerProvider().getLineMarkerInfo(token!!))
    }

    fun testDedup_withXmlSqlProvider_onHbmFile() {
        // hbm 文件里 query 行恰 1 图标（XmlSql 只认 mapper 父级，此处 0）
        val file = myFixture.configureByText("LegacyItem.hbm.xml", hbmContent) as XmlFile
        val token = tokenAt(file, "query")!!
        val count = listOf(HbmNamedQueryGutterLineMarkerProvider(), XmlSqlGutterLineMarkerProvider())
            .count { it.getLineMarkerInfo(token) != null }
        assertEquals("hbm <query> 行应恰 1 图标（与 XmlSql 去重）", 1, count)
    }

    fun testKindOf_tagName() {
        assertEquals(cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlMapping.QueryKind.HQL, HbmNamedQueryGutterLineMarkerProvider.kindOf("query"))
        assertEquals(cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlMapping.QueryKind.NATIVE, HbmNamedQueryGutterLineMarkerProvider.kindOf("sql-query"))
        assertNull(HbmNamedQueryGutterLineMarkerProvider.kindOf("class"))
    }
}
