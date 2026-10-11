package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R30 动态 SQL 条件求值探镜测试：IfSqlNode 求值分支把每条条件的本轮判定结果（真/假）回传到
 * [RenderedXmlSql.conditionResults]，渲染中未到达的条件（假分支体内 / choose 短路后的 when）
 * 不登记，模型侧 [XmlPreviewModel.conditionOutcomes] 统一映射为三态
 * （[ConditionOutcome.TRUE] / [ConditionOutcome.FALSE] / [ConditionOutcome.UNEVALUATED]）。
 *
 * 红线：探针只读判定结果——既有断言锁渲染文本语义（XmlSqlRendererTest / XmlPreviewModelTest 全绿不变）。
 */
class ConditionOutcomeProbeTest {

    private fun model(mapper: String): XmlPreviewModel {
        val parsed = XmlMapperParser().parse(mapper)
        return XmlPreviewModel.from(parsed.statements.first { it.id == "s1" }, parsed.errors)
    }

    private fun render(m: XmlPreviewModel, enabled: Set<String>): RenderedXmlSql = m.renderSql(
        XmlPreviewState(enabledIds = enabled, forEachCounts = m.defaultState().forEachCounts),
    )

    // ------------------------------------------------------------------
    // 引擎层：真 / 假 / 未到达
    // ------------------------------------------------------------------

    @Test
    fun `顶层条件_勾选记真_未勾选记假`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t
                <where>
                  <if test="a">AND a = 1</if>
                  <if test="b">AND b = 2</if>
                </where>
              </select>
            </mapper>
        """.trimIndent())
        val rendered = render(m, enabled = setOf("a"))
        assertEquals(true, rendered.conditionResults["a"])
        assertEquals(false, rendered.conditionResults["b"])
    }

    @Test
    fun `假分支体内层if未到达_不登记`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t
                <where>
                  <if test="outer">AND o = 1
                    <if test="inner">AND i = 1</if>
                  </if>
                </where>
              </select>
            </mapper>
        """.trimIndent())
        // outer 关 → 体内层 if 根本没走到：既非真也非假，是「未求值」
        val rendered = render(m, enabled = emptySet())
        assertEquals(false, rendered.conditionResults["outer"])
        assertTrue("inner 不应登记（未到达）", rendered.conditionResults.containsKey("inner").not())
    }

    @Test
    fun `假分支体内层if_外层勾选时被求值`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t
                <where>
                  <if test="outer">AND o = 1
                    <if test="inner">AND i = 1</if>
                  </if>
                </where>
              </select>
            </mapper>
        """.trimIndent())
        val rendered = render(m, enabled = setOf("outer", "inner"))
        assertEquals(true, rendered.conditionResults["outer"])
        assertEquals(true, rendered.conditionResults["inner"])
    }

    @Test
    fun `choose短路后未命中when_不登记`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t
                <choose>
                  <when test="w1">ORDER BY 1</when>
                  <when test="w2">ORDER BY 2</when>
                </choose>
              </select>
            </mapper>
        """.trimIndent())
        val rendered = render(m, enabled = setOf("w1"))
        assertEquals(true, rendered.conditionResults["w1"])
        assertTrue("w2 被 w1 短路，不应登记", rendered.conditionResults.containsKey("w2").not())
    }

    @Test
    fun `where与foreach体内条件_回传到根上下文`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t
                <where><if test="w">AND w = 1</if></where>
                <foreach collection="list" item="it" open="(" close=")" separator=",">
                  <if test="f">#{it}</if>
                </foreach>
              </select>
            </mapper>
        """.trimIndent())
        val rendered = render(m, enabled = setOf("w", "f"))
        assertEquals(true, rendered.conditionResults["w"])
        assertEquals(true, rendered.conditionResults["f"])
    }

    @Test
    fun `无条件语句_conditionResults为空`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t WHERE a = #{a}</select>
            </mapper>
        """.trimIndent())
        assertTrue(render(m, enabled = emptySet()).conditionResults.isEmpty())
    }

    // ------------------------------------------------------------------
    // 模型层：三态映射（含未到达 → UNEVALUATED）
    // ------------------------------------------------------------------

    @Test
    fun `模型三态映射_真假未求值齐备`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t
                <where>
                  <if test="a">AND a = 1
                    <if test="inner">AND i = 1</if>
                  </if>
                  <if test="b">AND b = 2</if>
                </where>
              </select>
            </mapper>
        """.trimIndent())
        val rendered = render(m, enabled = setOf("a"))
        val outcomes = m.conditionOutcomes(rendered)
        assertEquals(ConditionOutcome.TRUE, outcomes["a"])
        assertEquals(ConditionOutcome.FALSE, outcomes["b"])
        // 外层 a 真 → 体内层 if 被走到：inner 未勾选（开关缺席 = false）判假
        assertEquals(ConditionOutcome.FALSE, outcomes["inner"])

        val renderedOuterOff = render(m, enabled = setOf("b"))
        val outcomesOff = m.conditionOutcomes(renderedOuterOff)
        assertEquals(ConditionOutcome.FALSE, outcomesOff["a"])
        assertEquals(ConditionOutcome.TRUE, outcomesOff["b"])
        // 外层 a 假 → 内层 if 根本没走到：未求值（区别于「假」——勾掉与没走到是两回事）
        assertEquals(ConditionOutcome.UNEVALUATED, outcomesOff["inner"])
    }
}
