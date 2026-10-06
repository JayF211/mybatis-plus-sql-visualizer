package cn.song.mybatisplus.visualizer.reverse

import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反向转换往返门禁（T-SqlToJavaPromote M1）：全语料按档位断言。
 *
 * 四档语义（见 [RoundTripExpectation]）：
 *  - ROUND_TRIP：反解→桥接→SqlGenerator 再渲染，规范化后与输入语义等价（SELECT/UPDATE/DELETE）；
 *    INSERT 无正向模型走 [renderInsertSql] 测试渲染器比对。
 *  - PARSE_OK：能反解且 [RoundTripCorpusCase.assertResult] 结构断言通过。
 *  - RAW_DEGRADE：能反解且发生 Raw 降级（warnings 非空）。
 *  - PARSE_ERROR：当前抛 [SqlReverseException]（基线缺口，M2/M3 逐条升档先红后绿）。
 *
 * 基线分布 test 是 M1c 基线报告的断言载体——M2/M3 每升一档同步更新计数。
 */
class ReverseRoundTripGateTest {

    private val corpus: List<RoundTripCorpusCase> get() = ReverseRoundTripCorpus.cases

    @Test
    fun `ROUND_TRIP档_全语料往返规范化等价`() {
        val failures = mutableListOf<String>()
        for (case in corpus.filter { it.expectation == RoundTripExpectation.ROUND_TRIP }) {
            try {
                val result = SqlReverseParser.parse(case.sql)
                case.assertResult(result)
                val sql2 = when (result.kind) {
                    ReverseStatementKind.INSERT -> renderInsertSql(result)
                    else -> SqlGenerator.generate(result.toSqlStatement())
                }
                val expected = normalizeForCompare(case.sql)
                val actual = normalizeForCompare(sql2)
                if (expected != actual) {
                    failures += "${case.name}：往返不等\n  输入: $expected\n  渲染: $actual"
                }
            } catch (e: Throwable) {
                failures += "${case.name}：反解/桥接抛异常 ${e.message}"
            }
        }
        assertTrue(
            "ROUND_TRIP 档失败 ${failures.size} 项:\n${failures.joinToString("\n")}",
            failures.isEmpty(),
        )
    }

    @Test
    fun `PARSE_OK档_能反解且结构断言通过`() {
        val failures = mutableListOf<String>()
        for (case in corpus.filter { it.expectation == RoundTripExpectation.PARSE_OK }) {
            try {
                val result = SqlReverseParser.parse(case.sql)
                case.assertResult(result)
            } catch (e: Throwable) {
                failures += "${case.name}：反解失败 ${e.message}"
            }
        }
        assertTrue("PARSE_OK 档失败 ${failures.size} 项:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun `RAW_DEGRADE档_反解且发生降级`() {
        val failures = mutableListOf<String>()
        for (case in corpus.filter { it.expectation == RoundTripExpectation.RAW_DEGRADE }) {
            try {
                val result = SqlReverseParser.parse(case.sql)
                if (result.warnings.isEmpty()) {
                    failures += "${case.name}：未发生降级（warnings 为空）"
                }
                case.assertResult(result)
            } catch (e: Throwable) {
                failures += "${case.name}：反解失败 ${e.message}"
            }
        }
        assertTrue("RAW_DEGRADE 档失败 ${failures.size} 项:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun `PARSE_ERROR档_当前报错_基线缺口`() {
        val failures = mutableListOf<String>()
        for (case in corpus.filter { it.expectation == RoundTripExpectation.PARSE_ERROR }) {
            try {
                SqlReverseParser.parse(case.sql)
                failures += "${case.name}：应报错但解析成功（缺口已修复？升档）"
            } catch (e: SqlReverseException) {
                // 预期：当前报错
            } catch (e: Throwable) {
                failures += "${case.name}：抛非 SqlReverseException：${e.message}"
            }
        }
        assertTrue("PARSE_ERROR 档 ${failures.size} 项已不报错（缺口修复后须升档）:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    /**
     * 基线报告（M1c）：语料四档分布断言。
     * M2/M3 每升一档同步更新此处计数（先红后绿留痕）。
     */
    @Test
    fun `基线分布_四档计数`() {
        val counts = ReverseRoundTripCorpus.counts()
        val expected = mapOf(
            RoundTripExpectation.ROUND_TRIP to 45,
            RoundTripExpectation.PARSE_OK to 7,
            RoundTripExpectation.RAW_DEGRADE to 3,
            RoundTripExpectation.PARSE_ERROR to 15,
        )
        assertEquals("语料档位分布（M1 基线，修复升档时同步更新）", expected, counts)
    }
}
