package cn.song.mybatisplus.visualizer.ui.gutter

import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

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

    // ------------------------------------------------------------------
    // T-M29.6：选择区内语句候选（statementsInRange）
    // ------------------------------------------------------------------

    private val selectionFixture = """
        <mapper namespace="com.example.OrderMapper">
          <sql id="cols">id, name</sql>
          <select id="selectList">
            SELECT <include refid="cols"/> FROM t
            <where><if test="name != null">AND name = #{name}</if></where>
          </select>
          <insert id="insertOne">INSERT INTO t VALUES (1)</insert>
          <update id="updateOne">UPDATE t SET a = 1</update>
        </mapper>
        """.trimIndent()

    fun testStatementsInRangeCollectsSelectionStatements() {
        myFixture.configureByText("OrderMapper.xml", selectionFixture)
        val first = tagByName("selectList")
        val last = tagByName("updateOne")
        val result = XmlSqlGutterLineMarkerProvider.statementsInRange(
            myFixture.file as XmlFile,
            first.textRange.startOffset,
            last.textRange.endOffset,
        )
        assertEquals(
            "选择区（selectList 开头 → updateOne 结束）应含 3 条语句且按 offset 排序",
            listOf("selectList", "insertOne", "updateOne"),
            result.map { it.getAttributeValue("id") },
        )
    }

    fun testStatementsInRangeExcludesNonStatementTags() {
        myFixture.configureByText("OrderMapper.xml", selectionFixture)
        val first = tagByName("selectList")
        val last = tagByName("updateOne")
        val result = XmlSqlGutterLineMarkerProvider.statementsInRange(
            myFixture.file as XmlFile,
            first.textRange.startOffset,
            last.textRange.endOffset,
        )
        // <sql> 片段、语句内嵌套 <include>/<where>/<if> 均不是语句标签，不得混入候选
        assertTrue("sql 片段不应是语句候选", result.none { it.name == "sql" })
        assertTrue("语句内嵌套 if 不应是语句候选", result.none { it.name == "if" })
        assertTrue("语句内嵌套 where 不应是语句候选", result.none { it.name == "where" })
        assertTrue("语句内嵌套 include 不应是语句候选", result.none { it.name == "include" })
    }

    fun testStatementsInRangeEmptyWhenSelectionContainsNoStatementStart() {
        myFixture.configureByText("OrderMapper.xml", selectionFixture)
        // 选择区从 selectList 内容中间开始（不含 selectList 起始点）、到 insertOne 起始之前 →
        // 无任何语句起始点（与 Java 选择区候选一致的 startOffset 判定）
        val inner = tagByName("selectList").textRange.startOffset + 10
        val insertStart = tagByName("insertOne").textRange.startOffset
        val result = XmlSqlGutterLineMarkerProvider.statementsInRange(myFixture.file as XmlFile, inner, insertStart)
        assertTrue("选择区内无语句起始点应返回空", result.isEmpty())
    }

    fun testStatementsInRangeExcludesStatementAtSelectionEnd() {
        myFixture.configureByText("OrderMapper.xml", selectionFixture)
        // until 语义：语句起始恰好等于选择区结束 → 不计入
        val select = tagByName("selectList")
        val insert = tagByName("insertOne")
        val result = XmlSqlGutterLineMarkerProvider.statementsInRange(
            myFixture.file as XmlFile,
            select.textRange.startOffset,
            insert.textRange.startOffset,
        )
        assertEquals(
            "选择区结束恰在 insertOne 起始 → 只含 selectList",
            listOf("selectList"),
            result.map { it.getAttributeValue("id") },
        )
    }
}
