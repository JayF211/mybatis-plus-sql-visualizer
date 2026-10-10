package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.ui.AnnotationSqlPreviewService
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProcess
import com.intellij.codeInsight.completion.CompletionResult
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionSorter
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.java.JavaLanguage
import com.intellij.patterns.ElementPattern
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue

/**
 * Java SQL 注解字符串代码补全测试（T-M27-05）：
 *  - @Select/@Update 字符串内关键字 / 列名 / 别名前缀列 / `#{}` 参数名补全
 *  - 常量拼接 / 数组 value 仍可补全（constantString 常量求值）
 *  - 负例：非 SQL 注解（@Deprecated）与 Provider 注解字符串不产任何项
 *
 * fixture 约束（波次 1 实证）：
 *  - Light 沙盒跨文件类型 resolve 为 null、FileBasedIndex 不可用——Mapper 接口与实体
 *    **同文件**声明（package com.example，@TableName 全限定名书写）；
 *  - @Select/@Param 用短名（沙盒不可解析 → qualifiedName 回落短名，isSqlAnnotation/paramNames
 *    短名兜底生效）；
 *  - **触发方式**：不用 myFixture.complete()——平台占位词语义（CodeInsightTestFixture 在 caret
 *    插入 "IntellijIdeaRulezzz" 使前缀 = 光标前标识符，如 "SELECT"）会过滤掉不以该词开头的项，
 *    与真实 IDE 空前缀行为不符（CompletionData.findPrefixStatic 实证：光标前空白 → 空前缀）。
 *    改用直接调用 fillCompletionVariants + [CollectingResultSet]（PrefixMatcher.ALWAYS_TRUE）——
 *    与 XmlSqlCompletionContributorTest 同款，前缀过滤语义由贡献器内部统一（真实 IDE 前缀行为
 *    已由 findPrefixStatic 实证，沙盒不再模拟）。
 *  - 断言适配：SourceSqlCompleter 对**已出现在语句文本中的关键字**不重复建议
 *    （"SELECT " 处不再给 SELECT 项）——关键字断言避开已出现词。
 */
class AnnotationSqlCompletionContributorTest : BasePlatformTestCase() {

    /** 配置 Mapper 文件（package com.example + 文件体）并在 `<caret>` 处触发补全，返回 lookup 字符串。 */
    private fun completeIn(mapperBody: String): List<String> {
        myFixture.configureByText("UserMapper.java", "package com.example;\n\n${mapperBody.trimIndent()}")
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
        AnnotationSqlCompletionContributor().fillCompletionVariants(parameters, resultSet)
        return resultSet.collected.map { it.lookupString }
    }

    private fun assertLookupContains(items: List<String>, vararg expected: String) {
        for (e in expected) {
            assertTrue("补全项应含 $e（实际：$items）", items.contains(e))
        }
    }

    private fun assertLookupEmpty(items: List<String>, message: String) {
        assertTrue("$message（实际：$items）", items.isEmpty())
    }

    // ------------------------------------------------------------------
    // 冒烟：贡献器已注册到 Java 语言（plugin.xml 注册项由主控统一添加）
    // ------------------------------------------------------------------

    fun testContributorRegisteredForJava() {
        val contributors = CompletionContributor.forLanguage(JavaLanguage.INSTANCE)
        assertTrue(
            "注解 SQL 补全贡献器应注册到 Java 语言（实际：${contributors.joinToString { it.javaClass.simpleName }}）",
            contributors.any { it is AnnotationSqlCompletionContributor },
        )
    }

    // ------------------------------------------------------------------
    // 1. @Select 字符串内关键字
    // ------------------------------------------------------------------

    fun testSelectKeywordCompletion() {
        // "SELECT " 后：SELECT 已出现在文本 → 不重复建议；FROM/DISTINCT 等在项中
        var items = completeIn(
            """
            public interface UserMapper {
                @Select("SELECT <caret>")
                int one();
            }
            """,
        )
        assertLookupContains(items, "FROM", "DISTINCT")

        // "WHERE " 后：WHERE 已出现；SELECT/FROM 未出现 → 均在项中
        items = completeIn(
            """
            public interface UserMapper {
                @Select(" WHERE <caret>")
                int one();
            }
            """,
        )
        assertLookupContains(items, "SELECT", "FROM")
    }

    // ------------------------------------------------------------------
    // 2. @Select 内 WHERE 子句列名（主实体 = 方法参数实体，同文件声明）
    // ------------------------------------------------------------------

    fun testSelectWhereClauseOffersEntityColumns() {
        val items = completeIn(
            """
            @com.baomidou.mybatisplus.annotation.TableName("t_device_info")
            public class DeviceInfoEntity {
                private Long id;
                private String name;
            }

            public interface UserMapper {
                @Select("SELECT * FROM t WHERE <caret>")
                DeviceInfoEntity select(DeviceInfoEntity entity);
            }
            """,
        )
        assertLookupContains(items, "id", "name")
    }

    // ------------------------------------------------------------------
    // 3. #{} 参数名补全（@Param 值 + param1..N）
    // ------------------------------------------------------------------

    fun testParamNameCompletionInsideHashBrace() {
        val items = completeIn(
            """
            public interface UserMapper {
                @Select("SELECT * FROM t WHERE id = #{<caret>")
                int selectByParam(@Param("userId") Long userId);
            }
            """,
        )
        assertLookupContains(items, "userId", "param1")
    }

    // ------------------------------------------------------------------
    // 4. 别名前缀列（FROM 表名匹配主实体 tableName → 别名.列）
    //    契约依赖：SourceSqlCompleter 的 parseTableAliases 别名绑定（共享契约，并行智能体 C）
    // ------------------------------------------------------------------

    fun testAliasPrefixedColumnCompletion() {
        val items = completeIn(
            """
            @com.baomidou.mybatisplus.annotation.TableName("t_device_info")
            public class DeviceInfoEntity {
                private Long id;
                private String name;
            }

            public interface UserMapper {
                @Select("SELECT u.<caret> FROM t_device_info u")
                DeviceInfoEntity select(DeviceInfoEntity entity);
            }
            """,
        )
        // T-XmlCmpl3 二段：主串裸列名（附加串 别名.列名 由工厂直测钉住）
        assertLookupContains(items, "id")
    }

    // ------------------------------------------------------------------
    // 5. 负例：非 SQL 注解字符串不触发（@Deprecated → 空）
    // ------------------------------------------------------------------

    fun testNonSqlAnnotationStringYieldsNothing() {
        val items = completeIn(
            """
            @Deprecated("SELECT <caret>")
            public interface UserMapper {
                int one();
            }
            """,
        )
        assertLookupEmpty(items, "非 SQL 注解字符串不应产出任何补全项")
    }

    // ------------------------------------------------------------------
    // 6. 负例：Provider 注解字符串不触发（SQL 由 Java 方法生成）
    // ------------------------------------------------------------------

    fun testProviderAnnotationStringYieldsNothing() {
        val items = completeIn(
            """
            public interface UserMapper {
                @SelectProvider(type = UserSqlProvider.class, method = "<caret>")
                int selectByStatus();

                class UserSqlProvider {
                    public String provideSql() {
                        return "SELECT 1";
                    }
                }
            }
            """,
        )
        assertLookupEmpty(items, "Provider 注解字符串不应产出任何补全项")
    }

    // ------------------------------------------------------------------
    // 7. 常量拼接仍补全（constantString 对整段 + 拼接求值）
    // ------------------------------------------------------------------

    fun testConstantConcatStillCompletes() {
        val items = completeIn(
            """
            @com.baomidou.mybatisplus.annotation.TableName("t_device_info")
            public class DeviceInfoEntity {
                private Long id;
            }

            public interface UserMapper {
                @Select("SELECT * FROM t WHERE id = " + "<caret>")
                DeviceInfoEntity select(DeviceInfoEntity entity);
            }
            """,
        )
        // 整段求值 "SELECT * FROM t WHERE id = "；SELECT/FROM/WHERE 已出现不重复建议；
        // 列 id 与未出现关键字（AND/IN 等）在项中
        assertLookupContains(items, "id", "AND")
    }

    // ------------------------------------------------------------------
    // 8. 数组 value 元素仍补全（守卫识别 value 数组元素）
    // ------------------------------------------------------------------

    fun testArrayValueElementCompletion() {
        val items = completeIn(
            """
            @com.baomidou.mybatisplus.annotation.TableName("t_device_info")
            public class DeviceInfoEntity {
                private Long id;
                private String name;
            }

            public interface UserMapper {
                @Select({"SELECT * FROM t WHERE <caret>"})
                DeviceInfoEntity select(DeviceInfoEntity entity);
            }
            """,
        )
        assertLookupContains(items, "id", "name")
    }

    // ------------------------------------------------------------------
    // 补充 1：value = 命名属性形态（PsiNameValuePair 包装）守卫/求值同生效
    // ------------------------------------------------------------------

    fun testNamedValueAttributeCompletion() {
        val items = completeIn(
            """
            @com.baomidou.mybatisplus.annotation.TableName("t_device_info")
            public class DeviceInfoEntity {
                private Long id;
            }

            public interface UserMapper {
                @Select(value = "SELECT * FROM t WHERE <caret>")
                DeviceInfoEntity select(DeviceInfoEntity entity);
            }
            """,
        )
        assertLookupContains(items, "id")
    }

    // ------------------------------------------------------------------
    // 补充 2：@Update（LITERAL_ANNOTATIONS 覆盖——SET 后无子句关键词 → UNKNOWN 全量）
    // ------------------------------------------------------------------

    fun testUpdateAnnotationCompletion() {
        val items = completeIn(
            """
            @com.baomidou.mybatisplus.annotation.TableName("t_device_info")
            public class DeviceInfoEntity {
                private Long id;
                private String name;
            }

            public interface UserMapper {
                @Update("UPDATE t SET name = <caret>")
                int update(DeviceInfoEntity entity);
            }
            """,
        )
        assertLookupContains(items, "name")
    }

    // ------------------------------------------------------------------
    // T-M27.5：<script> 动态标签补全（与 XML mapper 一致：标签名/属性/OGNL）
    // ------------------------------------------------------------------

    fun testScriptDynamicTagCompletion() {
        // 光标前 `<` → 补标签名
        val items = completeIn(
            """
            public interface UserMapper {
                @Select("<script>SELECT * FROM t WHERE 1=1 <caret></script>")
                int one();
            }
            """,
        )
        assertLookupContains(items, "if", "foreach", "where")
    }

    fun testScriptAttributeCompletion() {
        // `<foreach ` 后 → 补属性
        val items = completeIn(
            """
            public interface UserMapper {
                @Select("<script><foreach <caret> </foreach></script>")
                int one();
            }
            """,
        )
        assertLookupContains(items, "collection", "item", "separator")
    }

    fun testScriptOgnlCompletion() {
        // `<if test="` 值内 → 参数名 + 主表实体字段（同文件实体 fixture）
        val items = completeIn(
            """
            @com.baomidou.mybatisplus.annotation.TableName("t_device_info")
            public class DeviceInfoEntity {
                private Long id;
                private String name;
            }

            public interface UserMapper {
                @Select("<script><if test=\"<caret>\">SELECT * FROM t</if></script>")
                DeviceInfoEntity select(DeviceInfoEntity entity);
            }
            """,
        )
        assertLookupContains(items, "entity", "id", "name")
    }

    /**
     * 直接调用 [AnnotationSqlCompletionContributor.fillCompletionVariants] 的收集器：
     * 重写 [CompletionResultSet] 的全部抽象方法（前缀恒匹配 [PrefixMatcher.ALWAYS_TRUE]——
     * 前缀过滤由贡献器内部语义负责；沙盒不模拟平台占位词前缀，真实 IDE 空前缀行为已实证）。
     */
    private class CollectingResultSet : CompletionResultSet(
        PrefixMatcher.ALWAYS_TRUE,
        java.util.function.Consumer<CompletionResult> { },
        AnnotationSqlCompletionContributor(),
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
