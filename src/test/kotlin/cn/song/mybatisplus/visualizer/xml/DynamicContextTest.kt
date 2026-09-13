package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DynamicContext 纯逻辑单测（T-TestGuard-07 零覆盖补测：xml/DynamicContext 复刻 MyBatis
 * 渲染上下文，此前无任何测试）。
 *
 * 覆盖 appendSql 段间单空格（3.5.13+ StringJoiner 语义）、bind、唯一编号、条件开关覆盖、
 * bind 保护名、错误收集。
 */
class DynamicContextTest {

    @Test
    fun `初始bindings包含参数与databaseId`() {
        val ctx = DynamicContext(mapOf("_parameter" to null, "_databaseId" to "mysql"))
        assertTrue(ctx.bindings.containsKey(DynamicContext.PARAMETER_OBJECT_KEY))
        assertEquals("mysql", ctx.bindings[DynamicContext.DATABASE_ID_KEY])
    }

    @Test
    fun `appendSql段间单空格_首尾空白去除`() {
        val ctx = DynamicContext(emptyMap())
        ctx.appendSql("SELECT")
        ctx.appendSql("*")
        ctx.appendSql("FROM t")
        assertEquals("SELECT * FROM t", ctx.getSql())
    }

    @Test
    fun `空段不产生分隔`() {
        val ctx = DynamicContext(emptyMap())
        ctx.appendSql("a")
        ctx.appendSql("")
        ctx.appendSql("b")
        assertEquals("a b", ctx.getSql())
    }

    @Test
    fun `bind写入并可覆盖`() {
        val ctx = DynamicContext(emptyMap())
        ctx.bind("name", "n1")
        assertEquals("n1", ctx.bindings["name"])
        ctx.bind("name", "n2")
        assertEquals("n2", ctx.bindings["name"])
    }

    @Test
    fun `唯一编号从0自增`() {
        val ctx = DynamicContext(emptyMap())
        assertEquals(0, ctx.getUniqueNumber())
        assertEquals(1, ctx.getUniqueNumber())
        assertEquals(2, ctx.getUniqueNumber())
    }

    @Test
    fun `条件覆盖命中返回真值_未命中返回null`() {
        val ctx = DynamicContext(emptyMap(), conditionOverrides = mapOf("a == 1" to true, "b == 2" to false))
        assertEquals(true, ctx.conditionResult("a == 1"))
        assertEquals(false, ctx.conditionResult("b == 2"))
        assertNull("无覆盖时返回 null（走表达式求值）", ctx.conditionResult("c == 3"))
    }

    @Test
    fun `保护bind名_面板覆盖值优先`() {
        val ctx = DynamicContext(emptyMap(), protectedBindNames = setOf("limit"))
        assertTrue(ctx.isProtected("limit"))
        assertFalse(ctx.isProtected("other"))
    }

    @Test
    fun `错误可收集`() {
        val ctx = DynamicContext(emptyMap())
        ctx.errors.add("foreach collection 为 null")
        assertEquals(1, ctx.errors.size)
    }
}
