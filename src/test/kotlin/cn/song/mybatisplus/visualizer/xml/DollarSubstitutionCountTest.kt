package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `${}` 直替计数测试（T-DollarInjectMark / T-DI-01/03）：
 * TextSqlNode 每求值一个 `${}` token 计数一次——引擎忠实口径（MyBatis 对字符串字面量内的
 * `${}` 同样直替，计数不排除；转义 `\${` 不经求值不计）。渲染文本逐字节零变化（只加元数据）。
 *
 * 计数口径与计划 §三 的偏差定案见计划 §八：计划原文「字符串内 ${} 不计」与引擎（复刻
 * MyBatis TextSqlNode，无 SQL 字符串感知）实际行为不符——按引擎忠实实现并以下列样例锁死。
 */
class DollarSubstitutionCountTest {

    private fun render(mapper: String): RenderedXmlSql {
        val parsed = XmlMapperParser().parse(mapper)
        val model = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        return model.renderSql()
    }

    private fun compact(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()

    @Test
    fun `文本内多处直替逐个计数`() {
        val result = render(
            """
            <mapper namespace="n">
              <select id="s1" resultType="map">SELECT * FROM t ORDER BY ${'$'}{'a'} , ${'$'}{'b'} , ${'$'}{'c'}</select>
            </mapper>
            """.trimIndent(),
        )
        assertEquals(3, result.dollarSubstitutions)
        assertEquals("SELECT * FROM t ORDER BY a , b , c", compact(result.sql))
    }

    @Test
    fun `if内直替计入`() {
        val result = render(
            """
            <mapper namespace="n">
              <select id="s1" resultType="map">
                SELECT * FROM t
                <if test="1 == 1">ORDER BY ${'$'}{'id'}</if>
              </select>
            </mapper>
            """.trimIndent(),
        )
        assertEquals("if 分支展开 → 其中的 ${'$'} 计入", 1, result.dollarSubstitutions)
    }

    @Test
    fun `foreach内直替逐次累计`() {
        // foreach 默认 2 次迭代 → 内部 ${} 每次求值累计（引擎对 foreach 体内文本逐轮求值）
        val result = render(
            """
            <mapper namespace="n">
              <select id="s1" resultType="map">
                SELECT
                <foreach collection="ids" item="id" separator=",">${'$'}{'v'}</foreach>
                FROM t
              </select>
            </mapper>
            """.trimIndent(),
        )
        assertEquals(2, result.dollarSubstitutions)
    }

    @Test
    fun `转义形态不计`() {
        val result = render(
            """
            <mapper namespace="n">
              <select id="s1" resultType="map">SELECT '\${'$'}{lit}' FROM t WHERE a = #{a}</select>
            </mapper>
            """.trimIndent(),
        )
        assertEquals("转义 \${'$'} 与 #{} 均不计入直替", 0, result.dollarSubstitutions)
        // 转义语义实证：含转义 token 的文本判静态（isDynamic=false），整段原样输出含反斜杠
        assertTrue("转义 token 原样保留（含反斜杠）", compact(result.sql).contains("'\\${'$'}{lit}'"))
    }

    @Test
    fun `字符串字面量内直替同样计数_引擎忠实口径`() {
        // MyBatis TextSqlNode 无 SQL 字符串感知——字符串内 ${} 同样直替；本引擎复刻同语义
        val result = render(
            """
            <mapper namespace="n">
              <select id="s1" resultType="map">SELECT * FROM t WHERE name = '${'$'}{'x'}' AND ${'$'}{'b'} = 1</select>
            </mapper>
            """.trimIndent(),
        )
        assertEquals(2, result.dollarSubstitutions)
    }

    @Test
    fun `纯井号语句计数为零`() {
        val result = render(
            """
            <mapper namespace="n">
              <select id="s1" resultType="map">SELECT * FROM t WHERE a = #{a} AND b = #{b}</select>
            </mapper>
            """.trimIndent(),
        )
        assertEquals(0, result.dollarSubstitutions)
        assertEquals("SELECT * FROM t WHERE a = ? AND b = ?", compact(result.sql))
    }

    @Test
    fun `渲染文本零变化_只加元数据`() {
        // 防回归：计数字段不改变既有渲染产物（where 标签剥离行首 AND 的既有语义一并锁定）
        val result = render(
            """
            <mapper namespace="n">
              <select id="s1" resultType="map">
                SELECT * FROM t_biz
                <where>
                  <if test="type != null">AND type = #{type}</if>
                  AND name LIKE ${'$'}{'%x%'}
                </where>
              </select>
            </mapper>
            """.trimIndent(),
        )
        assertEquals(
            // 直替输出为求值原文（%x%）——不带 SQL 引号；#{type} 照常换 ?
            "SELECT * FROM t_biz WHERE type = ? AND name LIKE %x%",
            compact(result.sql),
        )
        assertEquals(1, result.dollarSubstitutions)
    }

    private fun assertTrue(message: String, condition: Boolean) = org.junit.Assert.assertTrue(message, condition)
}
