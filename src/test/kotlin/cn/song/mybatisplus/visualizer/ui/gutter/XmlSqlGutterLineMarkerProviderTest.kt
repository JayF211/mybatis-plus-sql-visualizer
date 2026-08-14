package cn.song.mybatisplus.visualizer.ui.gutter

import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * XmlSqlGutterLineMarkerProvider 测试（T-M24-04）：
 *  - mapper 语句标签（select/insert/update/delete）产生 marker（O(1) 判定）
 *  - 非语句标签（sql/resultMap）零 marker
 *  - 非 mapper 直接父级（普通 XML 中的同名标签）零 marker
 */
class XmlSqlGutterLineMarkerProviderTest : BasePlatformTestCase() {

    private val provider: LineMarkerProvider = XmlSqlGutterLineMarkerProvider()

    private fun tagByName(name: String): XmlTag =
        PsiTreeUtil.findChildrenOfType(myFixture.file, XmlTag::class.java)
            .first { it.getAttributeValue("id") == name }

    /** 语句标签的 name token（leaf 锚点，LineMarker 必须注册在 leaf 元素上）；按 id 或 name 属性定位。 */
    private fun tagNameToken(name: String): XmlToken =
        PsiTreeUtil.collectElementsOfType(myFixture.file, XmlToken::class.java)
            .first {
                it.tokenType == XmlTokenType.XML_NAME &&
                    (it.parent as? XmlTag)?.let { tag ->
                        tag.getAttributeValue("id") == name || tag.getAttributeValue("name") == name
                    } == true
            }

    fun testStatementTagsProduceMarker() {
        myFixture.configureByText(
            "OrderMapper.xml",
            """
            <mapper namespace="com.example.OrderMapper">
              <select id="selectList">SELECT * FROM t</select>
              <insert id="insertOne">INSERT INTO t VALUES (1)</insert>
              <update id="updateOne">UPDATE t SET a = 1</update>
              <delete id="deleteOne">DELETE FROM t</delete>
            </mapper>
            """.trimIndent(),
        )
        assertNotNull("select 语句应产生 gutter marker", provider.getLineMarkerInfo(tagNameToken("selectList")))
        assertNotNull("insert 语句应产生 gutter marker", provider.getLineMarkerInfo(tagNameToken("insertOne")))
        assertNotNull("update 语句应产生 gutter marker", provider.getLineMarkerInfo(tagNameToken("updateOne")))
        assertNotNull("delete 语句应产生 gutter marker", provider.getLineMarkerInfo(tagNameToken("deleteOne")))
    }

    fun testNonStatementTagsZeroMarker() {
        myFixture.configureByText(
            "OrderMapper.xml",
            """
            <mapper namespace="com.example.OrderMapper">
              <sql id="cols">id, name</sql>
              <resultMap id="rm" type="x"></resultMap>
              <select id="selectList">SELECT * FROM t</select>
            </mapper>
            """.trimIndent(),
        )
        assertNull("sql 片段不应产生 marker", provider.getLineMarkerInfo(tagNameToken("cols")))
        assertNull("resultMap 不应产生 marker", provider.getLineMarkerInfo(tagNameToken("rm")))
        assertNotNull("select 语句应产生 marker", provider.getLineMarkerInfo(tagNameToken("selectList")))
    }

    fun testNonMapperParentZeroMarker() {
        myFixture.configureByText(
            "page.xml",
            """
            <page>
              <select name="region">options</select>
            </page>
            """.trimIndent(),
        )
        // 父级不是 <mapper> → 不产生 marker（避免非 mapper XML 误显）
        assertNull("非 mapper 直接父级的 select 标签不应产生 marker", provider.getLineMarkerInfo(tagNameToken("region")))
    }
}
