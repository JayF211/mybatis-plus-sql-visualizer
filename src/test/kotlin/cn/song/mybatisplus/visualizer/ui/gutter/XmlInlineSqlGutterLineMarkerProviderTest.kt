package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.xml.XmlInlineSqlDetector
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * 通用 XML 内嵌 SQL gutter provider 测试（O-71 T-XmlInlineSql）。
 *
 * 覆盖：RF 样例认领、既有三套白名单文件不双图标（mapper/hbm/pages）、
 * 检测总开关、选择区候选收集。
 */
class XmlInlineSqlGutterLineMarkerProviderTest : BasePlatformTestCase() {

    private val provider = XmlInlineSqlGutterLineMarkerProvider()

    private fun xmlFile(text: String): XmlFile {
        myFixture.configureByText("sample.xml", text)
        return myFixture.file as XmlFile
    }

    private fun tokenNamed(file: XmlFile, name: String): XmlToken? =
        PsiTreeUtil.collectElementsOfType(file, XmlToken::class.java).filter { it.text == name }.firstOrNull()

    private fun markerAt(file: XmlFile, name: String, p: LineMarkerProvider = provider): LineMarkerInfo<*>? =
        tokenNamed(file, name)?.let { p.getLineMarkerInfo(it) }

    override fun tearDown() {
        // 开关隔离：恢复默认开
        PluginSettings.getInstance().state.xmlInlineSqlEnabled = true
        super.tearDown()
    }

    fun testRF样例_statement行有图标() {
        val file = xmlFile(
            "<RFListPage>" +
                "<RFTh id=\"id\" title=\"id\" hidden=\"true\"/>" +
                "<DataSource type=\"HQL\"><statement><![CDATA[SELECT * from xx]]></statement></DataSource>" +
                "</RFListPage>",
        )
        assertNotNull("statement 载体行应显示预览图标", markerAt(file, "statement"))
        assertNull("RFTh 不应显示图标", markerAt(file, "RFTh"))
        assertNull("DataSource 不应显示图标", markerAt(file, "DataSource"))
    }

    fun testmapper文件不双图标() {
        val file = xmlFile("<mapper><select id=\"x\">SELECT * FROM t</select></mapper>")
        assertNotNull("select 应由 XmlSql provider 认领", markerAt(file, "select", XmlSqlGutterLineMarkerProvider()))
        assertNull("通用 provider 不得重复认领 mapper 语句", markerAt(file, "select"))
    }

    fun testhbm文件不双图标() {
        val file = xmlFile("<hibernate-mapping><query name=\"q\"><![CDATA[FROM A a]]></query></hibernate-mapping>")
        assertNotNull("query 应由 HbmNamedQuery provider 认领", markerAt(file, "query", HbmNamedQueryGutterLineMarkerProvider()))
        assertNull("通用 provider 不得重复认领 hbm 命名查询", markerAt(file, "query"))
    }

    fun testpages文件不双图标() {
        val file = xmlFile(
            "<pages><maintainPage id=\"p1\"><datasource><![CDATA[SELECT 1]]></datasource></maintainPage></pages>",
        )
        assertNotNull("maintainPage 应由 Pages provider 认领", markerAt(file, "maintainPage", PagesXmlGutterLineMarkerProvider()))
        assertNull("通用 provider 不得认领 pages 的 datasource（图标已在 maintainPage）", markerAt(file, "datasource"))
    }

    fun test检测开关关闭后不认领() {
        PluginSettings.getInstance().state.xmlInlineSqlEnabled = false
        val file = xmlFile("<root><statement><![CDATA[SELECT 1]]></statement></root>")
        assertNull("总开关关闭后通用检测不认领", markerAt(file, "statement"))
        // 检测器主入口同样受闸（Action 兜底共用）
        val tag = PsiTreeUtil.collectElementsOfType(file, XmlTag::class.java).first { it.name == "statement" }
        org.junit.Assert.assertFalse(XmlInlineSqlDetector.isSqlCarrierTag(tag) && XmlInlineSqlDetector.detectionEnabled())
    }

    fun testcarriersInRange范围与排序() {
        val file = xmlFile(
            "<root>" +
                "<s1><![CDATA[SELECT 1]]></s1>" +
                "<other>文本</other>" +
                "<s2><![CDATA[UPDATE t SET a = 1]]></s2>" +
                "</root>",
        )
        val carriers = XmlInlineSqlGutterLineMarkerProvider.carriersInRange(file, 0, file.textLength)
        assertEquals("应收集 2 个载体（other 排除）", 2, carriers.size)
        assertEquals("按 offset 排序首项应为 s1", "s1", carriers[0].name)
        val end = carriers[1].textRange.startOffset
        val head = XmlInlineSqlGutterLineMarkerProvider.carriersInRange(file, 0, end)
        assertEquals("选区截断后应只剩 s1", 1, head.size)
    }
}
