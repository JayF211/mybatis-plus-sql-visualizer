package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PagesXmlParser 测试：<pages> XML → PagesModel——
 * 语句提取、CDATA SQL、columns 解析、异常路径。
 */
class PagesXmlParserTest {

    private val sampleXml = """
        <pages>
            <maintainPage id="maintainLangLibPage" title="maintainLangLibPage"
                          autoQuery="true" entityClass="com.vtradex.forest.model.lang.TfLangLib">
                <workflow/>
                <datasource><![CDATA[
                SELECT
                    t.id,
                    a.name,
                    t.name,
                    t.lang
                  from TfLangLib t
                  inner join TFApp a on t.appId = a.id
                 WHERE 1=1
                   ORDER BY t.id DESC
                ]]></datasource>
                <columns>
                    <column id="t.id" title="t.id" visible="false" sortable="true" dataType="long"/>
                    <column id="a.name" title="a.name" visible="true" sortable="true" dataType="string"/>
                    <column id="t.name" title="t.name" visible="true" sortable="true" dataType="string"/>
                    <column id="t.lang" title="t.lang" visible="true" sortable="true" dataType="enum"/>
                </columns>
            </maintainPage>
        </pages>
    """.trimIndent()

    @Test
    fun `标准单maintainPage_字段完整`() {
        val model = PagesXmlParser().parse(sampleXml)
        assertEquals(1, model.statements.size)
        val stmt = model.statements[0]
        assertEquals("maintainLangLibPage", stmt.id)
        assertEquals("maintainLangLibPage", stmt.title)
        assertTrue(stmt.autoQuery)
        assertEquals("com.vtradex.forest.model.lang.TfLangLib", stmt.entityClass)
        assertTrue(stmt.sql.contains("SELECT"))
        assertTrue(stmt.sql.contains("TfLangLib"))
        assertTrue(stmt.sql.contains("ORDER BY t.id DESC"))
    }

    @Test
    fun `CDATA_SQL_trim后完整`() {
        val model = PagesXmlParser().parse(sampleXml)
        val sql = model.statements[0].sql
        // 首尾无空白，内容完整
        assertEquals(sql, sql.trim())
        assertTrue(sql.startsWith("SELECT"))
        assertTrue(sql.endsWith("DESC"))
    }

    @Test
    fun `columns_解析4列_属性正确`() {
        val model = PagesXmlParser().parse(sampleXml)
        val columns = model.statements[0].columns
        assertEquals(4, columns.size)

        assertEquals("t.id", columns[0].id)
        assertEquals("t.id", columns[0].title)
        assertFalse(columns[0].visible)
        assertTrue(columns[0].sortable)
        assertEquals("long", columns[0].dataType)

        assertEquals("a.name", columns[1].id)
        assertTrue(columns[1].visible)
        assertEquals("string", columns[1].dataType)

        assertEquals("t.lang", columns[3].id)
        assertEquals("enum", columns[3].dataType)
    }

    @Test
    fun `多个maintainPage_顺序一致`() {
        val xml = """
            <pages>
                <maintainPage id="page1" title="Page 1" autoQuery="true" entityClass="com.A">
                    <datasource><![CDATA[SELECT 1]]></datasource>
                </maintainPage>
                <maintainPage id="page2" title="Page 2" autoQuery="false" entityClass="com.B">
                    <datasource><![CDATA[SELECT 2]]></datasource>
                </maintainPage>
                <maintainPage id="page3" title="Page 3" autoQuery="true" entityClass="com.C">
                    <datasource><![CDATA[SELECT 3]]></datasource>
                </maintainPage>
            </pages>
        """.trimIndent()
        val model = PagesXmlParser().parse(xml)
        assertEquals(3, model.statements.size)
        assertEquals("page1", model.statements[0].id)
        assertEquals("page2", model.statements[1].id)
        assertEquals("page3", model.statements[2].id)
        assertFalse(model.statements[1].autoQuery)
    }

    @Test
    fun `空datasource_sql为空串`() {
        val xml = """
            <pages>
                <maintainPage id="p1" title="P1" autoQuery="true" entityClass="com.A">
                    <datasource></datasource>
                </maintainPage>
            </pages>
        """.trimIndent()
        val model = PagesXmlParser().parse(xml)
        assertEquals("", model.statements[0].sql)
    }

    @Test
    fun `无columns_columns为空列表`() {
        val xml = """
            <pages>
                <maintainPage id="p1" title="P1" autoQuery="true" entityClass="com.A">
                    <datasource><![CDATA[SELECT 1]]></datasource>
                </maintainPage>
            </pages>
        """.trimIndent()
        val model = PagesXmlParser().parse(xml)
        assertTrue(model.statements[0].columns.isEmpty())
    }

    @Test(expected = PagesXmlParseException::class)
    fun `非pages根元素_抛异常`() {
        PagesXmlParser().parse("<configuration></configuration>")
    }

    @Test(expected = PagesXmlParseException::class)
    fun `mapper根元素_抛异常`() {
        PagesXmlParser().parse("<mapper namespace='x'><select id='a'>SELECT 1</select></mapper>")
    }

    @Test(expected = PagesXmlParseException::class)
    fun `非良构XML_抛异常`() {
        PagesXmlParser().parse("<pages><maintainPage id='a'></pages>")
    }

    @Test
    fun `无maintainPage_空列表`() {
        val model = PagesXmlParser().parse("<pages></pages>")
        assertTrue(model.statements.isEmpty())
    }

    @Test
    fun `SQL含比较运算符_CDATA字面量保留`() {
        // CDATA 内文本为字面量，&lt; 不解码（与 MyBatis mapper 行为一致）
        val xml = """
            <pages>
                <maintainPage id="p1" title="P1" autoQuery="true" entityClass="com.A">
                    <datasource><![CDATA[SELECT * FROM t WHERE id < 10 AND name LIKE '%test%']]></datasource>
                </maintainPage>
            </pages>
        """.trimIndent()
        val model = PagesXmlParser().parse(xml)
        val sql = model.statements[0].sql
        assertTrue(sql.contains("id < 10"))
        assertTrue(sql.contains("%test%"))
    }

    @Test
    fun `HQL语句_实体类与字段正确提取`() {
        val xml = """
            <pages>
                <maintainPage id="userQuery" title="User Query" autoQuery="true"
                              entityClass="com.example.model.User">
                    <datasource><![CDATA[
                    select u.name, u.email from User u
                  where u.age > 18
                  order by u.createdDate desc
                    ]]></datasource>
                </maintainPage>
            </pages>
        """.trimIndent()
        val model = PagesXmlParser().parse(xml)
        val stmt = model.statements[0]
        assertEquals("com.example.model.User", stmt.entityClass)
        assertTrue(stmt.sql.contains("from User u"))
        assertTrue(stmt.sql.contains("order by u.createdDate desc"))
    }
}
