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
        // O-72 延伸修复判别式：本平台 XmlText.text 对 CDATA 返回带包裹字面全文，
        // 旧断言（contains 字面量）在展开/不展开两态下恒真——此处钉死「包裹不得残留」。
        assertFalse("CDATA 包裹不得残留（否则 HQL 转换会把 <![CDATA[ 带进 SQL）", q.queryText.contains("<![CDATA["))
        assertFalse("CDATA 收尾标记不得残留", q.queryText.contains("]]>"))
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

    fun testPreviewHbmNamedQueryTag_hqlCdata_noWrapperLeak() {
        // O-72 延伸修复判别式：gutter 点击入口（previewHbmNamedQueryTag）对 CDATA 形态
        // <query> 的提取不得把 <![CDATA[ 包裹带进 HQL 转换（旧实现裸拼接会泄漏）。
        val file = myFixture.configureByText("LegacyItem.hbm.xml", hbmContent) as XmlFile
        val tag = file.rootTag!!.findSubTags("query").first()
        val service = project.getService(SqlPreviewService::class.java)
        val result = service.previewHbmNamedQueryTag(project, tag)
        assertNotNull("CDATA 形态 <query> 应可预览", result)
        assertFalse("预览 SQL 不得含 CDATA 包裹: ${result!!.sql}", result.sql.contains("<![CDATA["))
        assertFalse("预览 SQL 不得含 CDATA 收尾标记", result.sql.contains("]]>"))
        assertTrue("HQL 实体应可解析渲染", result.sql.isNotBlank())
    }

    fun testClassMapping_stillResolved_alongsideNamedQueries() {
        // 命名查询解析不得破坏既有类映射链路（resolveHqlTable 路径 3 依赖）
        val mapping = HbmXmlEntityResolver.resolve("LegacyItem", project)
        assertNotNull(mapping)
        assertEquals("legacy_item", mapping!!.tableName)
        assertTrue(mapping.properties.any { it.fieldName == "itemCode" && it.columnName == "item_code" })
    }
}
