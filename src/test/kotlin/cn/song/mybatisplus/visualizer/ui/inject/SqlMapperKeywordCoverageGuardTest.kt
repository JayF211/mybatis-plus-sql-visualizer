package cn.song.mybatisplus.visualizer.ui.inject

import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.ui.highlight.SqlLexer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-XmlGap W3 #7 + 计划 §七 要求的**关键字集一致性守卫**。
 *
 * 守的是内部调研报告 §0.9 / §4.1 那条真实回归：补全链用 `SqlLexer.KEYWORDS`（66 词）、
 * 高亮链用 `SqlMapperLanguage.KEYWORDS`（原 41 词），差 25 词 —— 「补全能提示但编辑器里不着色」。
 * 判别位用**真实差集**（不是合成样本）：下方 [DIFF_WORDS] 就是那 25 词里去掉九个函数名后
 * 的点名清单，加上九个函数名与方言特有词。
 *
 * ## 反向验证（守卫有效性证据）
 *
 * 把 [SqlMapperLanguage.KEYWORDS] 临时改回旧的 41 词平集（去掉并集），本类前两个用例必须变红
 * ——两枚读数（先红后绿）已落实施报告。合成样本只能证「规则看得见我造的形态」，
 * 真实差集才能证「规则看得见真实形态」。
 */
class SqlMapperKeywordCoverageGuardTest {

    /** 内部报告 §0.9 点名的「补全能提示但编辑器不着色」词（不含九个函数名，函数名另列）。 */
    private val DIFF_WORDS = listOf(
        "TABLE", "CREATE", "DROP", "ALTER", "ADD", "COLUMN", "PRIMARY", "KEY",
        "FOREIGN", "CONSTRAINT", "DEFAULT", "INDEX", "FULL", "CROSS", "USING",
    )

    /** `SqlLexer.KEYWORDS` 独有的九个函数名（同报告 §0.9）。 */
    private val FUNCTION_WORDS = listOf("COUNT", "SUM", "MAX", "MIN", "AVG", "COALESCE", "NULLIF", "CAST", "CONVERT")

    @Test
    fun test补全关键字集_高亮集必须全覆盖() {
        val missing = SqlLexer.KEYWORDS - SqlMapperLanguage.KEYWORDS
        assertTrue("补全能提示但编辑器不着色的词（SqlLexer.KEYWORDS 有而高亮集没有）：$missing", missing.isEmpty())
    }

    @Test
    fun test方言保留字_高亮集必须全覆盖() {
        val dialectWords = DialectRegistry.dialects
            .flatMapTo(linkedSetOf()) { it.reservedWords }
            .mapTo(linkedSetOf()) { it.uppercase() }
        val missing = dialectWords - SqlMapperLanguage.KEYWORDS
        assertTrue("方言保留字有而高亮集没有（冷门方言关键字不着色）：$missing", missing.isEmpty())
    }

    @Test
    fun test真实差集逐词在场_补全侧与方言侧各一例() {
        val ks = SqlMapperLanguage.KEYWORDS
        DIFF_WORDS.forEach { assertTrue("§0.9 差集词 $it 不在高亮集内", it in ks) }
        FUNCTION_WORDS.forEach { assertTrue("SqlLexer 函数名 $it 不在高亮集内", it in ks) }
        // 方言特有判别位：MERGE 只在 SQL Server 集、RETURNING 只在 SQLite/DM/MariaDB/KingBase 集
        assertTrue("MERGE（SQL Server 保留字）不在高亮集内", "MERGE" in ks)
        assertTrue("RETURNING（SQLite/DM/MariaDB/KingBase 保留字）不在高亮集内", "RETURNING" in ks)
    }

    @Test
    fun test原平集只增不删_41词逐词仍在() {
        val missing = SqlMapperLanguage.BASE_KEYWORDS - SqlMapperLanguage.KEYWORDS
        assertTrue("原平集被删词（会让既有映射失色）：$missing", missing.isEmpty())
        assertEquals(41, SqlMapperLanguage.BASE_KEYWORDS.size)
    }

    @Test
    fun testOGNL词集_是高亮集子集() {
        val missing = SqlMapperLanguage.OGNL_KEYWORDS - SqlMapperLanguage.KEYWORDS
        assertTrue("OGNL 关键字不在高亮集内：$missing", missing.isEmpty())
    }

    @Test
    fun test词长分桶表_与全量集一致() {
        val fromBuckets = SqlMapperLanguage.KEYWORDS_BY_LENGTH.values.flatten().toSet()
        assertEquals(SqlMapperLanguage.KEYWORDS, fromBuckets)
        SqlMapperLanguage.KEYWORDS_BY_LENGTH.forEach { (len, words) ->
            assertTrue("桶 $len 里有长度不符的词：$words", words.all { it.length == len })
        }
    }

    @Test
    fun test词集规模_确实大于旧平集() {
        assertTrue(
            "统一后的词集应显著大于 41 词平集，实际 ${SqlMapperLanguage.KEYWORDS.size}",
            SqlMapperLanguage.KEYWORDS.size > SqlMapperLanguage.BASE_KEYWORDS.size,
        )
        assertFalse(SqlMapperLanguage.KEYWORDS.isEmpty())
    }
}
