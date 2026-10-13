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
 * SQL 候选的**大小写口径**守卫（T-XmlGap 收口第三轮真机实测后补）。
 *
 * 起因：沙盒走查里在注入片段内小写键入 `wh` **一个候选都不出**，大写 `WH` 出 `WHEN`/`WHERE`
 * （对照读数）——因为候选词表是大写关键字/函数，而编辑器给的前缀匹配器**区分大小写**。
 * 本类用**真实匹配器**（`CamelHumpMatcher`，而非既有套件的 `ALWAYS_TRUE`）钉住该口径：
 * 小写前缀也须出大写关键字/函数（SQL 语义大小写不敏感）。
 *
 * [test00] 是前提自证：若平台的 `CamelHumpMatcher` 本身不区分大小写，本类的前提即不成立，
 * 它会红并提示复核——避免「按一个错误前提去改产品行为」。
 */
class XmlSqlCompletionCaseInsensitiveTest : BasePlatformTestCase() {

    private fun addDefaultMapperFile() {
        myFixture.addFileToProject(
            "UserMapper.java",
            """
            package com.example;

            import java.util.List;

            public class DeviceInfoEntity {
                private Long id;
                private String name;
            }

            public interface UserMapper {
                DeviceInfoEntity selectList(DeviceInfoEntity entity);
            }
            """.trimIndent(),
        )
    }

    @Suppress("ApiStatus")
    private fun stringsAt(xml: String, prefix: String): List<String> {
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
        val resultSet = MatcherResultSet(com.intellij.codeInsight.completion.impl.CamelHumpMatcher(prefix))
        XmlSqlCompletionContributor().fillCompletionVariants(parameters, resultSet)
        return resultSet.collected.map { it.lookupString }
    }

    /** 前提自证：真实匹配器对「小写前缀 vs 大写候选」是**不匹配**的（否则本类前提不成立）。 */
    fun test00_自证_CamelHumpMatcher区分大小写() {
        val matcher = com.intellij.codeInsight.completion.impl.CamelHumpMatcher("wh")
        assertFalse(
            "前提：`CamelHumpMatcher(\"wh\").prefixMatches(\"WHERE\")` 应为 false（区分大小写）；" +
                "若这里为 true，说明平台匹配器已改为不区分大小写，本类的修复前提需重新评估",
            matcher.prefixMatches("WHERE"),
        )
    }

    /** 小写前缀（用户常态）也须出大写关键字候选。 */
    fun test01_小写前缀_出大写关键字候选() {
        addDefaultMapperFile()
        val s = stringsAt(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectList">SELECT * FROM t wh<caret></select>
            </mapper>
            """,
            "wh",
        )
        assertTrue("小写前缀 `wh` 应出 `WHERE`（SQL 关键字大小写不敏感），实际=$s", s.contains("WHERE"))
    }

    /** 大写前缀（既有行为）不得回归。 */
    fun test02_大写前缀_仍出大写关键字候选() {
        addDefaultMapperFile()
        val s = stringsAt(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectList">SELECT * FROM t WH<caret></select>
            </mapper>
            """,
            "WH",
        )
        assertTrue("大写前缀 `WH` 应出 `WHERE`，实际=$s", s.contains("WHERE"))
    }

    /** 小写前缀出大写函数候选（`CONCAT` 等来自 SQL 函数表）。 */
    fun test03_小写前缀_出大写函数候选() {
        addDefaultMapperFile()
        val s = stringsAt(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectList">SELECT sub<caret> FROM t</select>
            </mapper>
            """,
            "sub",
        )
        assertTrue("小写前缀 `sub` 应出 `SUBSTR`（`SQL_FUNCTIONS` 函数表成员），实际=$s", s.contains("SUBSTR"))
    }

    /** 恒匹配前缀器收集器（与既有套件同形，仅匹配器可配）。 */
    private class MatcherResultSet(matcher: PrefixMatcher) : CompletionResultSet(
        matcher,
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
