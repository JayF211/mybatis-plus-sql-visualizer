package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表达式支持面分类器测试（T-ExprAstPrecheck T-EP-04）：表驱动钉死三档判定。
 *
 * 独立于 [OgnlEvaluatorTest]——那边 18 个主题方法是求值行为锚点，本类是分类面锚点，两者不混。
 * 分类面事实全部来自 Ognl.kt 自身（解析器可产出的 AST 与运算符、METHOD_CARRIERS 白名单、
 * 属性/getter 访问语义），本类只钉结论、不引入新语义。
 */
class OgnlExprClassifyTest {

    // ------------------------------------------------------------------
    // 三档总表（expr → 预期档位）
    // ------------------------------------------------------------------

    @Test
    fun `分类总表_三档`() {
        val cases = listOf(
            // ---- SUPPORTED：语法与语义都在受限子集内 ----
            "a != null and b == 1" to ExprSupport.SUPPORTED,
            "list != null and list.size() > 0" to ExprSupport.SUPPORTED,
            "list.size" to ExprSupport.SUPPORTED, // 属性形态与 size() 等价
            "list.isEmpty() == false" to ExprSupport.SUPPORTED,
            "list[0] != null" to ExprSupport.SUPPORTED,
            "map['k'] == 'v'" to ExprSupport.SUPPORTED,
            "_parameter.title != null" to ExprSupport.SUPPORTED,
            "_parameter.getTitle() != null" to ExprSupport.SUPPORTED, // getter 推断
            "_parameter.isFlag()" to ExprSupport.SUPPORTED, // is 前缀 getter
            "_parameter.inner.name == 'x'" to ExprSupport.SUPPORTED,
            "_parameter.title.toString() != ''" to ExprSupport.SUPPORTED, // toString 全载体有实现
            "1 in {1, 2, 3}" to ExprSupport.SUPPORTED,
            "'a' not in {'b', 'c'}" to ExprSupport.SUPPORTED,
            "'ell' in 'hello'" to ExprSupport.SUPPORTED,
            "'%' + name + '%'" to ExprSupport.SUPPORTED,
            "1 + 2 * 3 % 2 == 0" to ExprSupport.SUPPORTED,
            "not flag and -1 < 0" to ExprSupport.SUPPORTED,
            "true || false" to ExprSupport.SUPPORTED,
            // 字面量接收者：载体可静态判定且方法有实现
            "'hello'.length() > 0" to ExprSupport.SUPPORTED,
            "'hello'.startsWith('he')" to ExprSupport.SUPPORTED,
            "{1, 2}.size() == 2" to ExprSupport.SUPPORTED,

            // ---- UNSUPPORTED_SYNTAX：解析即 Err（三元/赋值/lambda/静态方法/new 等）----
            "a ? b : c" to ExprSupport.UNSUPPORTED_SYNTAX,
            "a = b" to ExprSupport.UNSUPPORTED_SYNTAX,
            "a -> b" to ExprSupport.UNSUPPORTED_SYNTAX,
            "@java.lang.Math@max(1, 2)" to ExprSupport.UNSUPPORTED_SYNTAX, // 静态方法调用
            "new java.util.ArrayList()" to ExprSupport.UNSUPPORTED_SYNTAX,
            "a ==" to ExprSupport.UNSUPPORTED_SYNTAX,
            "(a" to ExprSupport.UNSUPPORTED_SYNTAX,
            "" to ExprSupport.UNSUPPORTED_SYNTAX,

            // ---- EVAL_FALLBACK：语法可解析但求值走 null 降级 ----
            "map.length() == 0" to ExprSupport.EVAL_FALLBACK,
            "{1, 2}.length() == 0" to ExprSupport.EVAL_FALLBACK,
            "1.size() == 1" to ExprSupport.EVAL_FALLBACK,
            "list.remove(0) == null" to ExprSupport.EVAL_FALLBACK,
            "_parameter.customMethod() != null" to ExprSupport.EVAL_FALLBACK,
            "_parameter.title.startsWith('x')" to ExprSupport.EVAL_FALLBACK,
            "map.containsKey('k1')" to ExprSupport.EVAL_FALLBACK,
            "list.contains('a')" to ExprSupport.EVAL_FALLBACK,
            "@com.cyzc.DicConstants@DIC_VALUE == '1'" to ExprSupport.EVAL_FALLBACK,
            "'%' + @com.cyzc.DicConstants@DIC_VALUE + '%'" to ExprSupport.EVAL_FALLBACK,
        )
        cases.forEach { (expr, expected) ->
            assertEquals("classify('$expr')", expected, OgnlEvaluator.classify(expr))
        }
    }

    // ------------------------------------------------------------------
    // 理由码与细节（T-EP-03 标注 tooltip 的数据来源）
    // ------------------------------------------------------------------

    @Test
    fun `分类细节_理由码与细节`() {
        // 解析失败：理由 SYNTAX，细节为解析器消息（非空）
        val syntax = OgnlEvaluator.classifyDetail("a ? b : c")
        assertEquals(ExprSupport.UNSUPPORTED_SYNTAX, syntax.support)
        assertEquals(ExprReason.SYNTAX, syntax.reason)
        assertTrue("解析失败应带解析器消息：${syntax.detail}", !syntax.detail.isNullOrBlank())

        // 未知方法：细节为方法名
        val unknown = OgnlEvaluator.classifyDetail("list.remove(0) == null")
        assertEquals(ExprReason.UNKNOWN_METHOD, unknown.reason)
        assertEquals("remove", unknown.detail)

        // 白名单方法 + 载体受限：细节为方法名
        val narrow = OgnlEvaluator.classifyDetail("map.length() == 0")
        assertEquals(ExprReason.NARROW_CARRIER, narrow.reason)
        assertEquals("length", narrow.detail)

        // 静态字段引用：细节为 `@类@字段` 原文
        val static = OgnlEvaluator.classifyDetail("@com.cyzc.DicConstants@DIC_VALUE == '1'")
        assertEquals(ExprReason.STATIC_FIELD, static.reason)
        assertEquals("@com.cyzc.DicConstants@DIC_VALUE", static.detail)

        // 支持面内：理由与细节均为 null
        val supported = OgnlEvaluator.classifyDetail("a != null and a.size() > 0")
        assertEquals(ExprSupport.SUPPORTED, supported.support)
        assertNull(supported.reason)
        assertNull(supported.detail)
    }

    /**
     * T-EP-04 边界用例（计划点名形态）：**白名单方法 + 不匹配载体**归 EVAL_FALLBACK 档。
     *
     * `length` 在求值器里只对字符串有实现（见 METHOD_CARRIERS），`map.length()` 这类接收者类型
     * 未知的写法在真实运行时可能落到 Map/集合载体上静默返回 null——分类器必须标出来，而不是
     * 因为 `length` 在白名单里就算支持面内。对照：`map.size()` 载体无关 → SUPPORTED。
     */
    @Test
    fun `白名单方法不匹配载体归降级档`() {
        assertEquals(ExprSupport.EVAL_FALLBACK, OgnlEvaluator.classify("map.length() == 0"))
        assertEquals(ExprReason.NARROW_CARRIER, OgnlEvaluator.classifyDetail("map.length() == 0").reason)
        assertEquals(ExprSupport.SUPPORTED, OgnlEvaluator.classify("map.size() == 0"))
        assertEquals(ExprSupport.SUPPORTED, OgnlEvaluator.classify("map.isEmpty()"))
        // 载体可静态判定时按实际载体判：字符串上的 length 有实现
        assertEquals(ExprSupport.SUPPORTED, OgnlEvaluator.classify("'abc'.length() == 3"))
    }

    /**
     * 漂移防线（计划 §六 风险表）：SUPPORTED 档的表达式解析必须成功、求值不得报 Err——
     * 分类器若与求值器支持面脱钩（例如白名单换了名字），本断言先红。
     */
    @Test
    fun `SUPPORTED档必须可解析且求值不报错`() {
        val supported = listOf(
            "a != null and b == 1",
            "list.size() > 0",
            "'%' + name + '%'",
            "_parameter.getTitle() != null",
            "1 in {1, 2, 3}",
            "not flag and -1 < 0",
            "map['k'] == 'v'",
        )
        val bindings = mapOf(
            "_parameter" to MockObject(mapOf("title" to "t", "flag" to false)),
            "a" to "1",
            "b" to 1L,
            "name" to "1",
            "list" to listOf("a", "b"),
            "map" to mapOf("k" to "v"),
        )
        supported.forEach { expr ->
            assertEquals("classify('$expr') 应为 SUPPORTED", ExprSupport.SUPPORTED, OgnlEvaluator.classify(expr))
            assertTrue("SUPPORTED 档必须可解析：'$expr'", OgnlEvaluator.parse(expr) is ParseResult.Ok)
            val result = OgnlEvaluator.evaluate(expr, bindings)
            assertTrue("SUPPORTED 档求值不得报 Err：'$expr' → $result", result is EvalResult.Ok)
        }
    }
}
