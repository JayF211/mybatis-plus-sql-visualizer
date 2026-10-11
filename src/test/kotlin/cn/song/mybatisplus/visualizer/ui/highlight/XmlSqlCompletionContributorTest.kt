package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProcess
import com.intellij.codeInsight.completion.CompletionResult
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionSorter
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.patterns.ElementPattern
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * XmlSqlCompletionContributor 测试（T-M27-03 + T-M27-04，BasePlatformTestCase）。
 *
 * 用例（编号对齐任务）：
 *  1. 关键字补全：`SELECT <caret>` → DISTINCT/FROM（SELECT 已出现在文本中被"无前缀过滤"剔除）；
 *  2. FROM 后表名：主表名 + 表注释 tailText；
 *  3. WHERE 后列名：主表列 id/name；
 *  4. 主表别名前缀：`SELECT u.<caret> FROM t u` → u.id/u.name（prefixedColumn，别名命中候选主表）；
 *  5. `#{}` 参数：`WHERE id = #{<caret>}` → @Param 值（userId）+ 参数名（name）；
 *  6. 动态标签：光标前 `<` → if/foreach/where（xmlTag，lookupString = 标签名）；
 *  7. 属性补全：`<foreach <caret>` → collection/item；
 *  8. OGNL：`<if test="<caret>">` → 参数名 entity + 主实体字段名 id/name（fieldName）；
 *  9. 守卫：根标签非 mapper → 补全为空（直接调用贡献器隔离断言，无平台 XML 补全干扰）；
 * 10. 降级：无 namespace → 仅关键字（无表名/列名）；
 * 14-15.（T-FCA-03）`collection` 属性补全含集合快捷名 `list`/`collection`/`array` 且排在既有候选之后；
 *    `test` 属性不含这三个名字（两者共用同一 OGNL 分支，靠属性名收窄）。
 *
 * **触发方式（直接调用，不用 myFixture.complete()）**：贡献器经 plugin.xml 注册由主控统一完成
 * （本里程碑不修改 plugin.xml），且用例 9 需要"我们的补全器产出为空"的隔离断言——complete()
 * 会混入平台 XML 补全项。故按任务契约降级方案：构造 [CompletionParameters]
 * （myFixture.editor + caretOffset + findElementAt(position)）+ 测试专用 [CollectingResultSet]
 * 直接调 contributor.addCompletions 收集（参考现有测试风格，不依赖补全会话复制文件/dummy 标识符）。
 *
 * fixture 约束（波次 1 实证）：Light 沙盒跨文件类型 resolve 为 null、FileBasedIndex 不可用——
 * 实体与 Mapper 接口**同文件**声明（全限定名 @TableName 注解），XML 用 configureByText 建
 * "UserMapper.xml"（@Param 短名可解析，对齐 XmlSqlCompletionContextResolverTest 先例）。
 */
class XmlSqlCompletionContributorTest : BasePlatformTestCase() {

    // ------------------------------------------------------------------
    // fixture 工具
    // ------------------------------------------------------------------

    /** 建一个 Mapper 接口文件（package com.example）。 */
    private fun addMapperFile(content: String) {
        myFixture.addFileToProject("UserMapper.java", "package com.example;\n\n${content.trimIndent()}")
    }

    /** 默认 fixture：@TableName("t_device_info") 主实体 + selectList(DeviceInfoEntity) 方法。 */
    private fun addDefaultMapperFile() {
        addMapperFile(
            """
            /** 设备信息表 */
            @com.baomidou.mybatisplus.annotation.TableName("t_device_info")
            public class DeviceInfoEntity {
                private Long id;
                private String name;
            }

            public interface UserMapper {
                DeviceInfoEntity selectList(DeviceInfoEntity entity);
            }
            """,
        )
    }

    /**
     * 在 caret 处直接触发贡献器：构造 CompletionParameters（真实编辑器 + caret 偏移 + 叶子 position）
     * 并经测试专用 [CollectingResultSet] 收集 addElement 产物。
     */
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
            /* invocationCount */ 1,
            myFixture.editor,
            object : CompletionProcess {
                override fun isAutopopupCompletion() = false
            },
        )
        val resultSet = CollectingResultSet()
        // 2025.3 平台 API 实证（javap）：CompletionContributor 无 addCompletions（已重构），
        // 补全入口为 fillCompletionVariants(parameters, resultSet)
        XmlSqlCompletionContributor().fillCompletionVariants(parameters, resultSet)
        return resultSet.collected
    }

    private fun lookupStrings(elements: List<LookupElement>): List<String> = elements.map { it.lookupString }

    // ------------------------------------------------------------------
    // 1. 关键字补全
    // ------------------------------------------------------------------

    fun test01KeywordCompletion() {
        addDefaultMapperFile()
        val strings = lookupStrings(
            completeAt(
                """
                <mapper namespace="com.example.UserMapper">
                  <select id="selectList">SELECT <caret></select>
                </mapper>
                """,
            ),
        )
        assertTrue("关键字应含 DISTINCT：$strings", strings.contains("DISTINCT"))
        assertTrue("关键字应含 FROM：$strings", strings.contains("FROM"))
        // 规范：无前缀时过滤已在文本中出现的关键字（避免重复建议）——SELECT 已出现故不重复建议
        assertFalse("已出现的关键字 SELECT 应被过滤：$strings", strings.contains("SELECT"))
    }

    // ------------------------------------------------------------------
    // 2. FROM 后表名（主表 + 表注释 tailText）
    // ------------------------------------------------------------------

    fun test02TableCompletionAfterFrom() {
        addDefaultMapperFile()
        val items = completeAt(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectList">SELECT * FROM <caret></select>
            </mapper>
            """,
        )
        val tableItem = items.firstOrNull { it.lookupString == "t_device_info" }
        assertNotNull("FROM 后应补主表名 t_device_info", tableItem)
        val presentation = LookupElementPresentation()
        tableItem!!.renderElement(presentation)
        assertEquals("表注释应作为灰色 tailText 展示", "  设备信息表", presentation.tailText)
    }

    // ------------------------------------------------------------------
    // 3. WHERE 后列名（主表列）
    // ------------------------------------------------------------------

    fun test03ColumnCompletionAfterWhere() {
        addDefaultMapperFile()
        val strings = lookupStrings(
            completeAt(
                """
                <mapper namespace="com.example.UserMapper">
                  <select id="selectList">SELECT * FROM t_device_info WHERE <caret></select>
                </mapper>
                """,
            ),
        )
        assertTrue("WHERE 后应补主表列 id：$strings", strings.contains("id"))
        assertTrue("WHERE 后应补主表列 name：$strings", strings.contains("name"))
    }

    fun testPrefixedColumnMainStringBareAndAttachedAliased() {
        // T-XmlCmpl3 二段工厂契约：主 lookupString=裸列名（插入只替换点后 token，防 o.o.user_id），
        // 附加 lookupString=别名.列名（平台按任一 lookup string 匹配已敲的 o.user_i）
        val item = SourceSqlSuggestionFactory.prefixedColumn("o", "user_id", null)
        assertEquals("主串应为裸列名（插入串）", "user_id", item.lookupString)
        assertEquals("allLookupStrings 应含主串与别名.列名", setOf("user_id", "o.user_id"), item.allLookupStrings)
    }

    // ------------------------------------------------------------------
    // 4. 主表别名前缀（prefixedColumn）
    // ------------------------------------------------------------------

    fun test04AliasPrefixedColumnCompletion() {
        // 联表机制在主表上的可测形式：FROM t u 中 "t" 命中 candidates 主表（@TableName("t")）→ 绑定成功
        addMapperFile(
            """
            @com.baomidou.mybatisplus.annotation.TableName("t")
            public class DeviceInfoEntity {
                private Long id;
                private String name;
            }

            public interface UserMapper {
                DeviceInfoEntity selectList(DeviceInfoEntity entity);
            }
            """,
        )
        val strings = lookupStrings(
            completeAt(
                """
                <mapper namespace="com.example.UserMapper">
                  <select id="selectList">SELECT u.<caret> FROM t u WHERE 1=1</select>
                </mapper>
                """,
            ),
        )
        // T-XmlCmpl3 二段：主 lookupString=裸列名（插入用，防别名重复）；附加串=别名.列名
        // （fixture.lookupElementStrings 只回主串，附加串由下方工厂直测钉住）
        assertTrue("别名前缀应补 id（插入串）：$strings", strings.contains("id"))
        assertTrue("别名前缀应补 name：$strings", strings.contains("name"))
    }

    // ------------------------------------------------------------------
    // 5. `#{}` 参数（@Param 值优先 + 参数名）
    // ------------------------------------------------------------------

    fun test05ParamCompletionInHashBraces() {
        addMapperFile(
            """
            public interface UserMapper {
                UserEntity selectByCondition(@Param("userId") Long userId, String name);
            }
            """,
        )
        val strings = lookupStrings(
            completeAt(
                """
                <mapper namespace="com.example.UserMapper">
                  <select id="selectByCondition">SELECT * FROM t WHERE id = #{<caret>}</select>
                </mapper>
                """,
            ),
        )
        assertTrue("`#{}` 内应补 @Param 值 userId：$strings", strings.contains("userId"))
        assertTrue("`#{}` 内应补参数名 name：$strings", strings.contains("name"))
        assertTrue("`#{}` 内应补 param1..N：$strings", strings.contains("param1"))
    }

    // ------------------------------------------------------------------
    // 6. 动态标签补全（T-M27-04，光标前 `<`）
    // ------------------------------------------------------------------

    fun test06DynamicTagCompletionAfterLt() {
        addDefaultMapperFile()
        val strings = lookupStrings(
            completeAt(
                """
                <mapper namespace="com.example.UserMapper">
                  <select id="selectList">SELECT * FROM t WHERE 1=1 <<caret>/select>
                </mapper>
                """,
            ),
        )
        assertTrue("`<` 后应补 <if>（lookupString=if）：$strings", strings.contains("if"))
        assertTrue("`<` 后应补 <foreach>：$strings", strings.contains("foreach"))
        assertTrue("`<` 后应补 <where>：$strings", strings.contains("where"))
    }

    // ------------------------------------------------------------------
    // 7. 属性补全（foreach 标签内属性名位置）
    // ------------------------------------------------------------------

    fun test07AttributeCompletionInsideForeach() {
        addDefaultMapperFile()
        val strings = lookupStrings(
            completeAt(
                """
                <mapper namespace="com.example.UserMapper">
                  <select id="selectList">SELECT * FROM t WHERE 1=1 <foreach <caret>></foreach></select>
                </mapper>
                """,
            ),
        )
        assertTrue("foreach 属性名位应补 collection：$strings", strings.contains("collection"))
        assertTrue("foreach 属性名位应补 item：$strings", strings.contains("item"))
        assertTrue("foreach 属性名位应补 open：$strings", strings.contains("open"))
    }

    // ------------------------------------------------------------------
    // 8. OGNL 值内补全（T-M27-04，test 属性值：参数名 + 主实体字段名）
    // ------------------------------------------------------------------

    fun test08OgnlCompletionInIfTest() {
        addDefaultMapperFile()
        val strings = lookupStrings(
            completeAt(
                """
                <mapper namespace="com.example.UserMapper">
                  <select id="selectList">SELECT * FROM t_device_info WHERE 1=1 <if test="<caret>"></if></select>
                </mapper>
                """,
            ),
        )
        assertTrue("OGNL 应补方法参数名 entity：$strings", strings.contains("entity"))
        assertTrue("OGNL 应补主实体 Java 字段名 id（fieldName）：$strings", strings.contains("id"))
        assertTrue("OGNL 应补主实体 Java 字段名 name（fieldName）：$strings", strings.contains("name"))
    }

    // ------------------------------------------------------------------
    // 9. 守卫：根标签非 mapper → 补全为空
    // ------------------------------------------------------------------

    fun test09GuardRejectsNonMapperXml() {
        val strings = lookupStrings(
            completeAt(
                """
                <page>
                  <select id="selectList">SELECT <caret></select>
                </page>
                """,
            ),
        )
        assertTrue("非 mapper 根标签应零补全（守卫拦截）：$strings", strings.isEmpty())
    }

    // ------------------------------------------------------------------
    // 10. 降级：无 namespace → 仅关键字（无表名/列名）
    // ------------------------------------------------------------------

    fun test10NoNamespaceDegradesToKeywordsOnly() {
        val strings = lookupStrings(
            completeAt(
                """
                <mapper>
                  <select id="selectList">SELECT * FROM <caret></select>
                </mapper>
                """,
            ),
        )
        assertTrue("无 namespace 时应保留关键字补全 DISTINCT：$strings", strings.contains("DISTINCT"))
        assertFalse("无 namespace 时不应有表名 t_user：$strings", strings.contains("t_user"))
        assertFalse("无 namespace 时不应有列名 id：$strings", strings.contains("id"))
    }

    // ------------------------------------------------------------------
    // 11-13. T-M27-06 联表端到端（Light 沙盒可测形式：多别名均绑定主实体——
    //        @TableName 实体候选索引不可用，跨实体联表由 Heavy/沙盒实测兜底）
    // ------------------------------------------------------------------

    fun test11CommaMultiAliasBothBindMainEntity() {
        addMapperFile(
            """
            @com.baomidou.mybatisplus.annotation.TableName("t")
            public class DeviceInfoEntity {
                private Long id;
                private String name;
            }

            public interface UserMapper {
                DeviceInfoEntity selectList(DeviceInfoEntity entity);
            }
            """,
        )
        val strings = lookupStrings(
            completeAt(
                """
                <mapper namespace="com.example.UserMapper">
                  <select id="selectList">SELECT a.<caret> FROM t a, t b</select>
                </mapper>
                """,
            ),
        )
        // T-XmlCmpl3 二段：主 lookupString=裸列名（b.id 形态已不存在，别名区分由附加串+平台过滤承担）
        assertTrue("逗号多表 a 别名应补 a 的列 id：$strings", strings.contains("id"))
    }

    fun test12UnknownAliasPrefixDegradesToMainColumns() {
        addMapperFile(
            """
            @com.baomidou.mybatisplus.annotation.TableName("t")
            public class DeviceInfoEntity {
                private Long id;
                private String name;
            }

            public interface UserMapper {
                DeviceInfoEntity selectList(DeviceInfoEntity entity);
            }
            """,
        )
        val strings = lookupStrings(
            completeAt(
                """
                <mapper namespace="com.example.UserMapper">
                  <select id="selectList">SELECT x.<caret> FROM t u</select>
                </mapper>
                """,
            ),
        )
        assertTrue("未知别名 x 应跳过 x. 前缀列但保留关键字：$strings", strings.contains("DISTINCT"))
        assertFalse("未知别名 x 不应产出 x.id：$strings", strings.contains("x.id"))
        assertFalse("未知别名前缀模式跳过列补全（不出无前缀 id）：$strings", strings.contains("id"))
    }

    fun test13OnClauseRightSideAliasColumn() {
        addMapperFile(
            """
            @com.baomidou.mybatisplus.annotation.TableName("t")
            public class DeviceInfoEntity {
                private Long id;
                private String name;
            }

            public interface UserMapper {
                DeviceInfoEntity selectList(DeviceInfoEntity entity);
            }
            """,
        )
        val strings = lookupStrings(
            completeAt(
                """
                <mapper namespace="com.example.UserMapper">
                  <select id="selectList">SELECT * FROM t u LEFT JOIN t d ON u.id = d.<caret></select>
                </mapper>
                """,
            ),
        )
        assertTrue("ON 右侧 d 别名应补 d 的列 id：$strings", strings.contains("id"))
    }

    // ------------------------------------------------------------------
    // 14-15. T-FCA-03：foreach collection 快捷别名（只对 collection 属性开放）
    // ------------------------------------------------------------------

    /**
     * `collection` 属性值内补全 = 既有候选（参数名 + 主实体字段名，**顺序与内容不变**）
     * 之后追加三个集合快捷名。逐项断言顺序，钉住「不挤压既有候选」。
     */
    fun test14CollectionAttributeAddsAliasCandidatesAfterExistingOnes() {
        addDefaultMapperFile()
        val strings = lookupStrings(
            completeAt(
                """
                <mapper namespace="com.example.UserMapper">
                  <select id="selectList">SELECT * FROM t_device_info WHERE 1=1 <foreach collection="<caret>"></foreach></select>
                </mapper>
                """,
            ),
        )
        assertEquals(
            "collection 候选 = 既有（参数名 → 字段名）在前 + 三个快捷名在后：$strings",
            listOf("entity", "id", "name", "list", "collection", "array"),
            strings,
        )
    }

    /**
     * 反向守卫：`test` 与 `collection` 共用同一个 OGNL 补全分支，快捷名**不得**灌进 `test`——
     * 本用例是该收窄的判别器（把 `collectionAliases = attributeName == "collection"` 放宽为恒真即红）。
     */
    fun test15TestAttributeHasNoCollectionAliases() {
        addDefaultMapperFile()
        val strings = lookupStrings(
            completeAt(
                """
                <mapper namespace="com.example.UserMapper">
                  <select id="selectList">SELECT * FROM t_device_info WHERE 1=1 <if test="<caret>"></if></select>
                </mapper>
                """,
            ),
        )
        assertEquals("test 候选仍只有参数名 + 字段名：$strings", listOf("entity", "id", "name"), strings)
        assertFalse("test 不应出现 list：$strings", strings.contains("list"))
        assertFalse("test 不应出现 collection：$strings", strings.contains("collection"))
        assertFalse("test 不应出现 array：$strings", strings.contains("array"))
    }

    // ------------------------------------------------------------------
    // 测试专用 CompletionResultSet（直接调用贡献器收集 addElement 产物）
    // ------------------------------------------------------------------

    /**
     * 直接调用 [XmlSqlCompletionContributor.addCompletions] 的收集器：
     * 重写 [CompletionResultSet] 的全部抽象方法（前缀恒匹配 [PrefixMatcher.ALWAYS_TRUE]——
     * 前缀过滤由调用方语义负责，本测试统一按无前缀触发，与用例 caret 均位于空白处一致）。
     */
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
