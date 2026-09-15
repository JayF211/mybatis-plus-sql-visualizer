package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-H1（20260907-1630）：hbm 命名查询解析测试——`<query>`（HQL）/`<sql-query>`（原生 SQL）
 * 提取、`<sql-query>` 子标签排除、负命中缓存、gutter 点击入口 previewHbmNamedQueryTag。
 * fixture 内容取自 demo 项目 LegacyItem.hbm.xml（用户真机问题一）。
 */
class HbmNamedQueryResolverTest : BasePlatformTestCase() {

    private val hbmContent = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE hibernate-mapping PUBLIC
        "-//Hibernate/Hibernate Mapping DTD 3.0//EN"
        "http://www.hibernate.org/dtd/hibernate-mapping-3.0.dtd">
<hibernate-mapping>
    <class name="cn.song.jpademo.entity.LegacyItem" table="legacy_item">
        <id name="id" type="java.lang.Long" column="id">
            <generator class="native"/>
        </id>
        <property name="itemCode" type="string" column="item_code" length="64"/>
        <property name="quantity" type="java.lang.Integer" column="quantity"/>
    </class>

    <query name="LegacyItem.findLowStockHql"><![CDATA[
        FROM LegacyItem li
        WHERE li.quantity <= :threshold AND li.deprecated = false
        ORDER BY li.quantity ASC
    ]]></query>

    <sql-query name="LegacyItem.countByDeprecatedNative">
        <return-scalar column="cnt" type="long"/>
        SELECT COUNT(*) AS cnt FROM legacy_item WHERE deprecated = :deprecated
    </sql-query>
</hibernate-mapping>
""".trimIndent()

    override fun setUp() {
        super.setUp()
        // resolver 是 object 级缓存，每用例清空（负命中会跨 fixture 污染）
        HbmXmlEntityResolver.clearCache()
        myFixture.addFileToProject("resources/hbm/LegacyItem.hbm.xml", hbmContent)
    }

    override fun tearDown() {
        HbmXmlEntityResolver.clearCache()
        super.tearDown()
    }

    fun testFindNamedQuery_hqlKind() {
        val q = HbmXmlEntityResolver.findNamedQuery("LegacyItem.findLowStockHql", project)
        assertNotNull("hbm <query> 应可按名找到", q)
        assertEquals(HbmXmlMapping.QueryKind.HQL, q!!.kind)
        assertTrue("CDATA 文本应展开提取", q.queryText.contains("FROM LegacyItem li"))
        assertTrue(q.queryText.contains(":threshold"))
    }

    fun testFindNamedQuery_nativeKind_excludesChildTags() {
        val q = HbmXmlEntityResolver.findNamedQuery("LegacyItem.countByDeprecatedNative", project)
        assertNotNull("hbm <sql-query> 应可按名找到", q)
        assertEquals(HbmXmlMapping.QueryKind.NATIVE, q!!.kind)
        assertTrue("SQL 文本应提取", q.queryText.contains("SELECT COUNT(*)"))
        assertTrue("子标签 <return-scalar> 属性不应混入文本", !q.queryText.contains("return-scalar") && !q.queryText.contains("cnt="))
    }

    fun testFindNamedQuery_miss_negativeCached() {
        assertNull("不存在的查询名应 null", HbmXmlEntityResolver.findNamedQuery("LegacyItem.notExist", project))
        // 二次查询走负命中（不抛即通过；封顶清空路径由 clearCache 覆盖）
        assertNull(HbmXmlEntityResolver.findNamedQuery("LegacyItem.notExist", project))
    }

    fun testPreviewHbmNamedQueryTag_native_paramReplaced() {
        val file = myFixture.configureByText("LegacyItem.hbm.xml", hbmContent) as XmlFile
        val tag = file.rootTag!!.findSubTags("sql-query").first()
        val service = project.getService(SqlPreviewService::class.java)
        val result = service.previewHbmNamedQueryTag(project, tag)
        assertNotNull(result)
        assertTrue("命名参数 :deprecated 应替换为 ?", result!!.sql.contains("deprecated = ?"))
        assertEquals(listOf("deprecated"), result.unresolvedParams)
    }

    fun testClassMapping_stillResolved_alongsideNamedQueries() {
        // 命名查询解析不得破坏既有类映射链路（resolveHqlTable 路径 3 依赖）
        val mapping = HbmXmlEntityResolver.resolve("LegacyItem", project)
        assertNotNull(mapping)
        assertEquals("legacy_item", mapping!!.tableName)
        assertTrue(mapping.properties.any { it.fieldName == "itemCode" && it.columnName == "item_code" })
    }
}
