package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-FS-07 守卫：嵌套子上下文内层 `<if test="非法表达式">` 求值失败必须可见，且逐迭代去重。
 *
 * 缺陷原状：`ForeachSqlNode` 的 Prefixed/Filtered 上下文与 `TrimSqlNode` 的 filtered 上下文
 * 各自持有 `DynamicContext.errors` 而未转发 → 内层 if 求值失败被丢弃（静默）；
 * 而 `${}` / bind / 顶层 if 的失败都记 errors 并走 `⚠` 警示行——只有嵌套层消失。
 *
 * 修法：`errors` 转 open + 子上下文按 `bindings`/`staticFieldValues` 同款委托覆写；
 * 去重落在写入侧（`DynamicContext.errors` 为判重表，键=完整错误文案），
 * 因 `<foreach>` 逐迭代会把同一表达式同一原因重复登记 N 次。
 */
class ForeachInnerIfErrorTest {

    /** 解析 mapper 文本（首个语句）并渲染。 */
    private fun render(
        mapper: String,
        bindings: Map<String, Any?> = emptyMap(),
        overrides: Map<String, Boolean> = emptyMap(),
    ): RenderedXmlSql {
        val parsed = XmlMapperParser().parse(mapper)
        return XmlSqlRenderer().render(
            parsed.statements.single().node,
            bindings,
            overrides,
        )
    }

    private val foreachWithBadIf = """
        <mapper namespace="n">
          <select id="s1">SELECT * FROM t WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
              <if test="not valid @@ expression">#{id}</if>
            </foreach>
          </select>
        </mapper>
    """.trimIndent()

    @Test
    fun `foreach内非法if_错误可见且逐迭代只留一行`() {
        val rendered = render(foreachWithBadIf, bindings = mapOf("ids" to listOf(1, 2, 3)))
        // 默认展开行为不变（容错 true），三个迭代都应产出参数位
        assertEquals("迭代展开行为不应变：${rendered.sql}", 3, rendered.mappings.size)
        assertTrue("foreach 体内 if 求值失败必须留下可见错误：${rendered.errors}", rendered.errors.isNotEmpty())
        val conditionErrors = rendered.errors.filter { it.contains("条件表达式无法求值") }
        assertEquals("同一表达式同一原因只应记一行：${rendered.errors}", 1, conditionErrors.size)
        assertTrue(
            "错误应含 test 原文（用户据此定位条件）：${rendered.errors}",
            conditionErrors.single().contains("not valid @@ expression"),
        )
    }

    @Test
    fun `where内非法if_错误可见`() {
        // trim/where/set 的 filtered 上下文是同族静默点（同一缺陷形态）
        val rendered = render(
            """
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t
                <where>
                  <if test="not valid @@ expression">a = 1</if>
                </where>
              </select>
            </mapper>
            """.trimIndent(),
        )
        assertEquals("SELECT * FROM t WHERE a = 1", rendered.sql.replace(Regex("\\s+"), " ").trim())
        assertTrue("where 体内 if 求值失败必须留下可见错误：${rendered.errors}", rendered.errors.isNotEmpty())
        assertEquals(
            "同一表达式同一原因只应记一行：${rendered.errors}",
            1,
            rendered.errors.count { it.contains("条件表达式无法求值") },
        )
    }

    @Test
    fun `不同表达式各自留一行_去重不塌缩异因`() {
        val rendered = render(
            """
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t
                <if test="bad @@ one">AND a = 1</if>
                <foreach collection="ids" item="id" separator=",">
                  <if test="bad @@ two">#{id}</if>
                </foreach>
              </select>
            </mapper>
            """.trimIndent(),
            bindings = mapOf("ids" to listOf(1, 2)),
        )
        val conditionErrors = rendered.errors.filter { it.contains("条件表达式无法求值") }
        assertEquals("两条不同表达式应各留一行：${rendered.errors}", 2, conditionErrors.size)
        assertTrue(conditionErrors.any { it.contains("bad @@ one") })
        assertTrue(conditionErrors.any { it.contains("bad @@ two") })
    }

    @Test
    fun `开关覆盖命中非法表达式_跳过求值不记错误`() {
        val rendered = render(
            foreachWithBadIf,
            bindings = mapOf("ids" to listOf(1, 2, 3)),
            overrides = mapOf("not valid @@ expression" to true),
        )
        assertTrue("开关命中应跳过表达式求值，不记错误：${rendered.errors}", rendered.errors.isEmpty())
        assertEquals("覆盖为 true → 三迭代照常展开", 3, rendered.mappings.size)
    }

    @Test
    fun `开关覆盖为false_不展开且不记错误`() {
        val rendered = render(
            foreachWithBadIf,
            bindings = mapOf("ids" to listOf(1, 2, 3)),
            overrides = mapOf("not valid @@ expression" to false),
        )
        assertTrue("开关命中应跳过表达式求值，不记错误：${rendered.errors}", rendered.errors.isEmpty())
        assertEquals("覆盖为 false → if 体不展开", 0, rendered.mappings.size)
    }

    @Test
    fun `错误表判重_同一文案只留首现一行`() {
        val ctx = DynamicContext(emptyMap())
        ctx.errors += "dup"
        ctx.errors += "dup"
        ctx.errors += "other"
        assertEquals("判重键=完整错误文案，保首现顺序", listOf("dup", "other"), ctx.errors.toList())
    }
}
