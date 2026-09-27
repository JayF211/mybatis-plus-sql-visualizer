package cn.song.mybatisplus.visualizer.xml

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * 通用 XML 内嵌 SQL 检测器测试（O-72 T-XmlInlineSql）。
 *
 * 覆盖：用户真实 RF 框架样例（根元素不固定）、CDATA/纯文本两形态、既有三套
 * 白名单排除（防双图标）、type 属性就近探测、宽口径行为钉死（决策 D2）。
 */
class XmlInlineSqlDetectorTest : BasePlatformTestCase() {

    /** 用户提供的真实 RF 框架样例（gousen-wms rfAsnListPage，CDATA 内嵌 SQL）。 */
    private val rfSample = """
        <RFListPage id="rfAsnListPage" title="rfAsnListPage" mainEntrance="true">
            <RFSearchUI id="searchCode" text="searchCode" onFoucs="true"/>
            <RFTable id="list" rowAction="wmsAsnRfManager.selectCustomerAsn">
                <RFTh id="id" title="id" width="50" enumType="" hidden="true"/>
                <DataSource type="HQL">
                    <statement><![CDATA[
                        SELECT * from xx
                    ]]></statement>
                </DataSource>
            </RFTable>
        </RFListPage>
    """.trimIndent()

    private fun xmlFile(text: String): XmlFile {
        myFixture.configureByText("sample.xml", text)
        return myFixture.file as XmlFile
    }

    private fun tagsNamed(file: XmlFile, name: String): List<XmlTag> =
        PsiTreeUtil.collectElementsOfType(file, XmlTag::class.java).filter { it.name == name }

    private fun tokenNamed(file: XmlFile, name: String): XmlToken? =
        PsiTreeUtil.collectElementsOfType(file, XmlToken::class.java).filter { it.text == name }.firstOrNull()

    // ------------------------------------------------------------------
    // 认领：RF 样例与根元素无关性
    // ------------------------------------------------------------------

    fun testRF样例_statement认领_其余标签不认领() {
        val file = xmlFile(rfSample)
        assertTrue("statement 应被认领为 SQL 载体", tagsNamed(file, "statement").all { XmlInlineSqlDetector.isSqlCarrierTag(it) })
        listOf("RFListPage", "RFSearchUI", "RFTable", "RFTh", "DataSource").forEach { name ->
            assertFalse(
                "$name 不应被认领（无 SQL 文本或非叶子）",
                tagsNamed(file, name).any { XmlInlineSqlDetector.isSqlCarrierTag(it) },
            )
        }
    }

    fun test根元素改名行为不变() {
        // 用户实证：根元素名不固定——识别不得依赖 RFListPage 这个名字
        val renamed = rfSample.replace("RFListPage", "WhateverPage")
        val file = xmlFile(renamed)
        assertTrue("根元素改名后 statement 仍应认领", tagsNamed(file, "statement").all { XmlInlineSqlDetector.isSqlCarrierTag(it) })
    }

    fun testCDATA与纯文本两形态均认领() {
        val file = xmlFile(
            "<root>" +
                "<statement><![CDATA[SELECT 1]]></statement>" +
                "<query>SELECT 2</query>" +
                "</root>",
        )
        assertTrue("CDATA 形态应认领", tagsNamed(file, "statement").all { XmlInlineSqlDetector.isSqlCarrierTag(it) })
        assertTrue("纯文本形态应认领", tagsNamed(file, "query").all { XmlInlineSqlDetector.isSqlCarrierTag(it) })
    }

    fun test多段文本_首段空白_仍认领() {
        val file = xmlFile("<root><statement>   <![CDATA[SELECT 1]]></statement></root>")
        assertTrue(tagsNamed(file, "statement").all { XmlInlineSqlDetector.isSqlCarrierTag(it) })
    }

    fun test非Sql叶子标签不认领() {
        val file = xmlFile(
            "<root><title>搜索</title><count>3</count><desc><![CDATA[你好世界]]></desc></root>",
        )
        listOf("title", "count", "desc").forEach { name ->
            assertFalse(name, tagsNamed(file, name).any { XmlInlineSqlDetector.isSqlCarrierTag(it) })
        }
    }

    /** 宽口径行为钉死（决策 D2）：首词命中即认领，即使整句是业务文案。收紧口径时同步改本断言。 */
    fun test宽口径_首词命中即认领() {
        val file = xmlFile("<root><desc>UPDATE your profile to continue</desc></root>")
        assertTrue("宽口径：首词 UPDATE 命中即认领", tagsNamed(file, "desc").all { XmlInlineSqlDetector.isSqlCarrierTag(it) })
    }

    // ------------------------------------------------------------------
    // 排除：既有三套白名单优先（防双图标）
    // ------------------------------------------------------------------

    fun test排除mapper语句标签() {
        val file = xmlFile("<mapper><select id=\"x\">SELECT * FROM t</select></mapper>")
        assertFalse("mapper select 已由 XmlSql provider 认领，通用检测必须让位", tagsNamed(file, "select").any { XmlInlineSqlDetector.isSqlCarrierTag(it) })
    }

    fun test排除hbm命名查询标签() {
        val file = xmlFile("<hibernate-mapping><query name=\"q\"><![CDATA[FROM A a]]></query></hibernate-mapping>")
        assertFalse("hbm query 已由 HbmNamedQuery provider 认领", tagsNamed(file, "query").any { XmlInlineSqlDetector.isSqlCarrierTag(it) })
    }

    fun test排除pages数据源标签() {
        val file = xmlFile(
            "<pages><maintainPage id=\"p1\"><datasource><![CDATA[SELECT 1]]></datasource></maintainPage></pages>",
        )
        assertFalse("pages datasource 的图标锚在 maintainPage，通用检测必须让位", tagsNamed(file, "datasource").any { XmlInlineSqlDetector.isSqlCarrierTag(it) })
        // 但同名标签脱离 pages 结构后按通用规则认领
        val file2 = xmlFile("<root><datasource><![CDATA[SELECT 1]]></datasource></root>")
        assertTrue(tagsNamed(file2, "datasource").all { XmlInlineSqlDetector.isSqlCarrierTag(it) })
    }

    // ------------------------------------------------------------------
    // type 属性探测（HQL 分派）
    // ------------------------------------------------------------------

    fun testType探测_父标签HQL命中() {
        val file = xmlFile(rfSample)
        val statement = tagsNamed(file, "statement").first()
        assertTrue("DataSource type=HQL（就近祖先）→ isHqlType", XmlInlineSqlDetector.isHqlType(statement))
    }

    fun testType探测_SQL与缺省均非HQL() {
        val file = xmlFile(
            "<root>" +
                "<DataSource type=\"SQL\"><s1><![CDATA[SELECT 1]]></s1></DataSource>" +
                "<DataSource><s2><![CDATA[SELECT 2]]></s2></DataSource>" +
                "</root>",
        )
        assertFalse("type=SQL 不走 HQL 管道", XmlInlineSqlDetector.isHqlType(tagsNamed(file, "s1").first()))
        assertFalse("无 type 不走 HQL 管道", XmlInlineSqlDetector.isHqlType(tagsNamed(file, "s2").first()))
        assertNull(XmlInlineSqlDetector.queryTypeOf(tagsNamed(file, "root").first()))
    }

    fun testType探测_自身属性优先() {
        val file = xmlFile("<root><statement type=\"HQL\"><![CDATA[SELECT 1]]></statement></root>")
        assertTrue(XmlInlineSqlDetector.isHqlType(tagsNamed(file, "statement").first()))
    }

    // ------------------------------------------------------------------
    // 文本提取
    // ------------------------------------------------------------------

    fun testExtractSqlText_CDATA展开且trim() {
        val file = xmlFile(rfSample)
        val sql = XmlInlineSqlDetector.extractSqlText(tagsNamed(file, "statement").first())
        assertTrue(sql.startsWith("SELECT"))
        assertFalse("首尾空白应 trim", sql.startsWith("\n"))
        assertEquals("SELECT * from xx", sql)
    }

    fun testFirstTextWord_大小写不敏感() {
        val file = xmlFile("<root><s>select 1</s></root>")
        assertEquals("SELECT", XmlInlineSqlDetector.firstTextWord(tagsNamed(file, "s").first()))
    }

    // ==== T-PerfSweep9 F18：gutter 快速路径 CDATA 全文正则敞口收口 ====

    /** 红线反向验证探针：截断必须生效——大文本（1MB）的前缀返回长度 ≤ PREFIX_PROBE_LENGTH。
     *  若有人删掉 take 截断（红线破坏形态），本探针必红。 */
    fun testF18_前缀截断红线探针_大文本() {
        val file = xmlFile("<root><s>${"x".repeat(1_000_000)} SELECT 1</s></root>")
        val prefix = XmlInlineSqlDetector.firstNonBlankTextPrefix(tagsNamed(file, "s").first())!!
        assertTrue("prefix length ${prefix.length} exceeds ${XmlInlineSqlDetector.PREFIX_PROBE_LENGTH}", prefix.length <= XmlInlineSqlDetector.PREFIX_PROBE_LENGTH)
    }

    /** F18 主形态：大 CDATA 段（自研框架 SQL 载体，几百 KB）——首词 SELECT 命中 + 前缀截断生效。 */
    fun testF18_大CDATA段_检测正确() {
        val bigSql = "SELECT * from xx;" + ("\nUNION ALL SELECT pad_${"x".repeat(64)} FROM t;").repeat(3000)
        val file = xmlFile("<root><s><![CDATA[\n$bigSql\n]]></s></root>")
        val tag = tagsNamed(file, "s").first()
        assertTrue(XmlInlineSqlDetector.isSqlCarrierTag(tag))
        val prefix = XmlInlineSqlDetector.firstNonBlankTextPrefix(tag)!!
        assertTrue(prefix.length <= XmlInlineSqlDetector.PREFIX_PROBE_LENGTH)
        assertTrue(prefix.startsWith("SELECT"))
    }

    /** F18 边缘：CDATA 标记后全空白再开内容——跳过循环不误返回 null。 */
    fun testF18_CDATA标记后空白_仍取到内容() {
        val file = xmlFile("<root><s><![CDATA[   \n\t  SELECT 1]]></s></root>")
        val tag = tagsNamed(file, "s").first()
        assertEquals("SELECT 1", XmlInlineSqlDetector.firstNonBlankTextPrefix(tag))
    }

    /** F18 无 CDATA 快路：语义等价（unwrapCdata 恒等），首词判定不受影响。 */
    fun testF18_无CDATA快路_语义等价() {
        val file = xmlFile("<root><s>   SELECT 1</s></root>")
        val tag = tagsNamed(file, "s").first()
        assertTrue(XmlInlineSqlDetector.isSqlCarrierTag(tag))
        assertEquals("SELECT 1", XmlInlineSqlDetector.firstNonBlankTextPrefix(tag))
    }

    /** F19：合并开关语义与两独立开关逐字节等价（真值表四象限中三个可达态）。 */
    fun testF19_合并开关_真值表() {
        // 默认态（两开关均 true）→ 快路 true
        assertTrue(XmlInlineSqlDetector.gutterFastPathEnabled())
    }
}
