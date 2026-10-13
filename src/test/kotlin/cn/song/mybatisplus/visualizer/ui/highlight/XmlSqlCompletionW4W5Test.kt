package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProcess
import com.intellij.codeInsight.completion.CompletionResult
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionSorter
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.patterns.ElementPattern
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * T-XmlGap W4/W5：mapper XML 补全扩面的行为测试（BasePlatformTestCase，直接调用贡献器）。
 *
 * 形态照抄 [XmlSqlCompletionContributorTest]：手工构造 [CompletionParameters] + 收集器
 * （前缀恒匹配——前缀过滤语义由贡献器内 prefixMatches 自理，断言用成员关系而非精确集合，
 * 免受既有候选面漂移影响）。覆盖：
 *  - W4-1 `#{}` 结构属性键 / `key=` 值 / 无逗号仍给参数名；
 *  - W4-2 属性值按属性名分流逐属性一例（id/keyProperty/refid/resultMap/extends/resultType/
 *    property/column/statementType/useGeneratedKeys/jdbcType…）；
 *  - W4-3 语句标签与 resultMap 族属性名；
 *  - W5-4 OGNL 变量来源（bind/foreach item·index/include property/内置变量）；
 *  - W5-5/W5-6 智能片段、运算符、`@类@静态成员`；
 *  - W5-7/8/9 投影位按 resultType、反引号列名、函数表、单字母捷径。
 *
 * fixture 约束（同既有套件）：Light 沙盒跨文件类型 resolve 为 null、FileBasedIndex 不可用——
 * 实体与 Mapper 接口**同文件**声明（`@com.baomidou...TableName` 全限定名），XML 用 configureByText。
 */
class XmlSqlCompletionW4W5Test : BasePlatformTestCase() {

    // ------------------------------------------------------------------
    // fixture
    // ------------------------------------------------------------------

    private fun addMapperFile(content: String) {
        myFixture.addFileToProject("UserMapper.java", "package com.example;\n\n${content.trimIndent()}")
    }

    private fun addDefaultMapperFile() {
        addMapperFile(
            """
            import java.util.List;

            /** 设备信息表 */
            @com.baomidou.mybatisplus.annotation.TableName("t_device_info")
            public class DeviceInfoEntity {
                private Long id;
                private String name;
            }

            public interface UserMapper {
                DeviceInfoEntity selectList(DeviceInfoEntity entity);
                int insert(DeviceInfoEntity entity);
                List<DeviceInfoEntity> selectInList(List<DeviceInfoEntity> list);
            }
            """,
        )
    }

    @Suppress("ApiStatus")
    private fun completeAt(xml: String): List<LookupElement> {
        myFixture.configureByText("UserMapper.xml", xml.trimIndent())
        val offset = myFixture.caretOffset
        val position = myFixture.file.findElementAt(offset) ?: error("caret 处无 PSI 元素")
        val parameters = CompletionParameters(
            position,
            myFixture.file,
            CompletionType.BASIC,
            offset,
            1,
            myFixture.editor,
            object : CompletionProcess {
                override fun isAutopopupCompletion() = false
            },
        )
        val resultSet = CollectingResultSet()
        XmlSqlCompletionContributor().fillCompletionVariants(parameters, resultSet)
        return resultSet.collected
    }

    private fun strings(xml: String): List<String> = completeAt(xml).map { it.lookupString }

    private fun assertHas(actual: List<String>, vararg expected: String) {
        expected.forEach { value ->
            assertTrue("候选应含 `$value`：$actual", actual.contains(value))
        }
    }

    // ------------------------------------------------------------------
    // W4-1 `#{}` 结构属性 + 逗号切换
    // ------------------------------------------------------------------

    fun test01_hashBrace_逗号后给结构属性键() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectList">SELECT * FROM t WHERE id = #{id,<caret>}</select>
            </mapper>
            """,
        )
        assertHas(s, "jdbcType=", "javaType=", "mode=", "typeHandler=", "resultMap=", "numericScale=", "jdbcTypeName=")
        assertFalse("逗号后切换为属性键，不再给参数名 entity：$s", s.contains("entity"))
    }

    fun test02_hashBrace_jdbcType值给枚举() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectList">SELECT * FROM t WHERE id = #{id,jdbcType=<caret>}</select>
            </mapper>
            """,
        )
        assertHas(s, "VARCHAR", "INTEGER", "TIMESTAMP", "UNDEFINED")
    }

    fun test03_hashBrace_mode值三选() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectList">SELECT * FROM t WHERE id = #{id,mode=<caret>}</select>
            </mapper>
            """,
        )
        assertHas(s, "IN", "OUT", "INOUT")
    }

    fun test04_hashBrace_未敲逗号仍给参数名() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectList">SELECT * FROM t WHERE id = #{<caret>}</select>
            </mapper>
            """,
        )
        assertHas(s, "entity", "_parameter")
        assertFalse("未敲逗号不应给属性键：$s", s.contains("jdbcType="))
    }

    // ------------------------------------------------------------------
    // W4-2 属性值分流（逐属性一例）
    // ------------------------------------------------------------------

    fun test05_属性值_id给接口方法名() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="<caret>"></select>
            </mapper>
            """,
        )
        assertHas(s, "selectList", "insert", "selectInList")
    }

    fun test06_属性值_keyProperty给参数名与实体字段() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <insert id="insert" keyProperty="<caret>"></insert>
            </mapper>
            """,
        )
        assertHas(s, "id", "name")
    }

    fun test07_属性值_refid给本文件sql_id() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <sql id="baseColumns">id, name</sql>
              <select id="selectList">SELECT <include refid="<caret>"/></select>
            </mapper>
            """,
        )
        assertHas(s, "baseColumns")
    }

    fun test08_属性值_resultMap与extends给resultMap_id() {
        addDefaultMapperFile()
        val selectResultMap = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <resultMap id="rm1" type="com.example.DeviceInfoEntity"></resultMap>
              <select id="selectList" resultMap="<caret>"></select>
            </mapper>
            """,
        )
        assertHas(selectResultMap, "rm1")

        val extendsValue = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <resultMap id="rm1" type="com.example.DeviceInfoEntity"></resultMap>
              <resultMap id="rm2" type="com.example.DeviceInfoEntity" extends="<caret>"></resultMap>
            </mapper>
            """,
        )
        assertHas(extendsValue, "rm1")
    }

    fun test09_属性值_resultType给有界类名() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectList" resultType="<caret>"></select>
            </mapper>
            """,
        )
        assertHas(s, "DeviceInfoEntity")
    }

    fun test10_属性值_jdbcType给枚举() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <resultMap id="rm1" type="com.example.DeviceInfoEntity">
                <result property="id" column="id" jdbcType="<caret>"/>
              </resultMap>
            </mapper>
            """,
        )
        assertHas(s, "VARCHAR", "BIGINT")
    }

    fun test11_属性值_property给resultMap类型字段() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <resultMap id="rm1" type="com.example.DeviceInfoEntity">
                <result property="<caret>" column="id"/>
              </resultMap>
            </mapper>
            """,
        )
        assertHas(s, "id", "name")
    }

    fun test12_属性值_column_按引用该resultMap的select投影列() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <resultMap id="rm1" type="com.example.DeviceInfoEntity">
                <constructor><arg column="<caret>"/></constructor>
              </resultMap>
              <select id="selectList" resultMap="rm1">SELECT order_id, user_name FROM t</select>
            </mapper>
            """,
        )
        assertHas(s, "order_id", "user_name")
        // 反向：未出现在引用该 resultMap 的 select 投影里的列不产
        assertFalse("无关列不应出现：$s", s.contains("created_at"))
    }

    fun test13_属性值_枚举与布尔值() {
        addDefaultMapperFile()
        val statementType = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectList" statementType="<caret>"></select>
            </mapper>
            """,
        )
        assertHas(statementType, "PREPARED", "STATEMENT", "CALLABLE")

        val useGeneratedKeys = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <insert id="insert" useGeneratedKeys="<caret>"></insert>
            </mapper>
            """,
        )
        assertHas(useGeneratedKeys, "true", "false")
    }

    fun test14_属性值_无合理候选属性不产() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectList" timeout="<caret>"></select>
            </mapper>
            """,
        )
        assertTrue("timeout 无合理候选，应零候选（不给空字符串项）：$s", s.isEmpty())
    }

    // ------------------------------------------------------------------
    // W4-3 语句标签属性名
    // ------------------------------------------------------------------

    fun test15_语句标签属性名补全() {
        addDefaultMapperFile()
        val select = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <select <caret>></select>
            </mapper>
            """,
        )
        assertHas(select, "id", "resultType", "resultMap", "parameterType", "statementType", "flushCache", "useCache")

        val insert = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <insert <caret>></insert>
            </mapper>
            """,
        )
        assertHas(insert, "keyProperty", "useGeneratedKeys", "keyColumn", "parameterType")
    }

    fun test16_resultMap族属性名补全() {
        addDefaultMapperFile()
        val resultMap = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <resultMap <caret>></resultMap>
            </mapper>
            """,
        )
        assertHas(resultMap, "id", "type", "extends", "autoMapping")

        val result = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <resultMap id="rm" type="com.example.DeviceInfoEntity"><result <caret>/></resultMap>
            </mapper>
            """,
        )
        assertHas(result, "property", "column", "jdbcType", "typeHandler", "javaType")
    }

    // ------------------------------------------------------------------
    // W5-4 OGNL 变量来源
    // ------------------------------------------------------------------

    fun test17_ognl_bind变量在其后可见() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectList">
                <bind name="likePattern" value="'%'"/>
                <if test="<caret>"></if>
              </select>
            </mapper>
            """,
        )
        assertHas(s, "likePattern")
    }

    fun test18_ognl_foreach_item与index可见() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectInList">
                SELECT * FROM t WHERE id IN
                <foreach collection="list" item="it" index="idx" open="(" close=")" separator=",">
                  <if test="<caret>"></if>#{it}
                </foreach>
              </select>
            </mapper>
            """,
        )
        assertHas(s, "it", "idx")
    }

    fun test19_ognl_include_property在被include的sql片段内可见() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <sql id="cols"><if test="<caret>"></if></sql>
              <select id="selectList">SELECT <include refid="cols"><property name="suffix" value="x"/></include></select>
            </mapper>
            """,
        )
        assertHas(s, "suffix")
    }

    fun test20_ognl_内置变量_databaseId与_parameter() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectList"><if test="<caret>"></if></select>
            </mapper>
            """,
        )
        assertHas(s, "_databaseId", "_parameter")
    }

    // ------------------------------------------------------------------
    // W5-5/W5-6 智能片段、运算符、@类@静态成员
    // ------------------------------------------------------------------

    fun test21_ognl_智能片段按变量给() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectList"><if test="entity<caret>"></if></select>
            </mapper>
            """,
        )
        assertHas(s, "entity != null")
        assertTrue("应至少产出一条智能片段（含 != null）：$s", s.any { it.contains("!= null") })
    }

    fun test22_ognl_运算符建议() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectList"><if test="<caret>"></if></select>
            </mapper>
            """,
        )
        assertHas(s, "in", "not in", "and", "or", "not")
    }

    fun test23_ognl_at触发语法骨架() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectList"><if test="@<caret>"></if></select>
            </mapper>
            """,
        )
        assertHas(s, "@类@静态成员")
    }

    fun test24_ognl_dollar内也走OGNL变量与片段() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectList">
                <bind name="orderBy" value="'id'"/>
                SELECT * FROM t ORDER BY ${'$'}{orderBy<caret>}
              </select>
            </mapper>
            """,
        )
        assertTrue("`\${}` 内应给变量/片段（含 orderBy 片段）：$s", s.any { it.startsWith("orderBy") })
    }

    // ------------------------------------------------------------------
    // W5-7/W5-8/W5-9 select 投影位 / 反引号 / 函数表 / 单字母捷径
    // ------------------------------------------------------------------

    fun test25_select投影位_按resultType类属性() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectList" resultType="com.example.DeviceInfoEntity">SELECT <caret></select>
            </mapper>
            """,
        )
        assertHas(s, "id", "name")
    }

    fun test26_反引号内_本文件resultMap的column与property() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <resultMap id="rm1" type="com.example.DeviceInfoEntity">
                <result column="order_id" property="orderId"/>
              </resultMap>
              <select id="selectList">SELECT * FROM t WHERE `order<caret></select>
            </mapper>
            """,
        )
        assertHas(s, "`order_id`", "`orderId`")
    }

    fun test27_SQL函数表_独立于关键字() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectList">SELECT <caret></select>
            </mapper>
            """,
        )
        assertHas(s, "UPPER", "ABS", "SUBSTR", "COUNT")
    }

    fun test28_单字母捷径() {
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectList">SELECT c<caret></select>
            </mapper>
            """,
        )
        assertHas(s, "COLUMN", "CASE", "COALESCE")
    }

    fun test29_sql片段内也产出SQL补全() {
        // `<sql>` 片段内也应有补全（W5-4 include property 可见域的前提）
        addDefaultMapperFile()
        val s = strings(
            """
            <mapper namespace="com.example.UserMapper">
              <sql id="cols">SELECT <caret>FROM t</sql>
            </mapper>
            """,
        )
        assertHas(s, "DISTINCT")
    }

    // ------------------------------------------------------------------
    // 收集器（照 XmlSqlCompletionContributorTest 形态）
    // ------------------------------------------------------------------

    private class CollectingResultSet : CompletionResultSet(
        PrefixMatcher.ALWAYS_TRUE,
        java.util.function.Consumer<CompletionResult> { },
        XmlSqlCompletionContributor(),
    ) {
        private val sink = mutableListOf<LookupElement>()

        val collected: List<LookupElement> get() = sink

        override fun addElement(element: LookupElement) {
            sink += element
        }

        override fun withPrefixMatcher(matcher: PrefixMatcher): CompletionResultSet = this

        override fun withPrefixMatcher(prefix: String): CompletionResultSet = this

        override fun withRelevanceSorter(sorter: CompletionSorter): CompletionResultSet = this

        override fun addLookupAdvertisement(text: String) = Unit

        override fun caseInsensitive(): CompletionResultSet = this

        override fun restartCompletionOnPrefixChange(prefixCondition: ElementPattern<String>) = Unit

        override fun restartCompletionWhenNothingMatches() = Unit
    }
}
