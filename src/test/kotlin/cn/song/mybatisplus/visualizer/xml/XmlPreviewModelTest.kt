package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览模型测试（T-M24-04）：变量收集（排除保留键）、启发式默认真值（`== null` 关闭）、
 * 渲染状态切换（条件开关 / foreach 次数与 null / bind 覆盖）、默认渲染单源。
 */
class XmlPreviewModelTest {

    private fun model(mapper: String, statementId: String = "s1"): XmlPreviewModel {
        val parsed = XmlMapperParser().parse(mapper)
        return XmlPreviewModel.from(parsed.statements.first { it.id == statementId }, parsed.errors)
    }

    private fun compact(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()

    // ------------------------------------------------------------------
    // 变量收集
    // ------------------------------------------------------------------

    @Test
    fun `变量收集_来自bind与dollar表达式_排除保留键`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">
                <bind name="p" value="'%' + _parameter.title + '%'"/>
                <bind name="q" value="list.size() + 1"/>
                SELECT * FROM t WHERE a = ${'$'}{ew.customSqlSegment} AND b LIKE #{p}
              </select>
            </mapper>
        """.trimIndent())
        // _parameter 是保留键（不收集）；title 归模拟参数对象属性；list/ew 是普通根变量
        assertEquals(setOf("title"), m.paramProps)
        assertEquals(setOf("list", "ew"), m.variables)
        assertEquals(listOf("p", "q"), m.bindViews.map { it.name })
    }

    @Test
    fun `变量收集_if条件不参与`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t<if test="status != null and type == 1"> WHERE x = 1</if></select>
            </mapper>
        """.trimIndent())
        // test 表达式由开关驱动，不收集变量
        assertEquals(emptySet<String>(), m.variables)
        assertEquals(listOf("status != null and type == 1"), m.conditionViews.map { it.id })
    }

    // ------------------------------------------------------------------
    // 启发式默认值
    // ------------------------------------------------------------------

    @Test
    fun `启发式默认_非判空展开_判null关闭`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t
                <where>
                  <if test="a != null">AND a = 1</if>
                  <if test="b == null">AND b IS NULL</if>
                  <if test="c != null and c != ''">AND c = #{c}</if>
                  <if test="d">AND d = 1</if>
                  <if test="flag == false">AND e = 2</if>
                </where>
              </select>
            </mapper>
        """.trimIndent())
        val defaults = m.conditionViews.map { it.id to it.defaultEnabled }.toMap()
        assertEquals(true, defaults["a != null"])
        assertEquals(false, defaults["b == null"])
        assertEquals(true, defaults["c != null and c != ''"])
        assertEquals(true, defaults["d"])
        assertEquals(true, defaults["flag == false"])
    }

    // ------------------------------------------------------------------
    // 渲染状态
    // ------------------------------------------------------------------

    @Test
    fun `foreach默认展开两次_次数可调`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t WHERE id IN
                <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
              </select>
            </mapper>
        """.trimIndent())
        assertEquals(XmlPreviewModel.DEFAULT_FOREACH_SIZE, m.defaultState().forEachCounts["ids"])
        assertEquals("SELECT * FROM t WHERE id IN ( ? , ? )", compact(m.renderSql().sql))
        val state = m.defaultState().copy(forEachCounts = mapOf("ids" to 0))
        assertEquals("SELECT * FROM t WHERE id IN", compact(m.renderSql(state).sql))
    }

    @Test
    fun `foreach集合为null时错误提示`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t WHERE id IN
                <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
              </select>
            </mapper>
        """.trimIndent())
        val state = m.defaultState().copy(forEachNulls = setOf("ids"))
        val result = m.renderSql(state)
        assertEquals("SELECT * FROM t WHERE id IN", compact(result.sql))
        assertTrue(result.errors.any { it.contains("evaluated to a null value") })
    }

    @Test
    fun `bind覆盖优先于表达式求值`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">
                <bind name="pattern" value="'%' + name + '%'"/>
                SELECT * FROM t WHERE name LIKE #{pattern}
              </select>
            </mapper>
        """.trimIndent())
        // 默认：name 占位 "1" → '%1%'
        assertEquals("SELECT * FROM t WHERE name LIKE ?", compact(m.renderSql().sql))
        val state = m.defaultState().copy(bindOverrides = mapOf("pattern" to "'%x%'"))
        assertEquals("SELECT * FROM t WHERE name LIKE ?", compact(m.renderSql(state).sql))
        assertEquals("pattern", m.renderSql(state).mappings.single().property)
    }

    @Test
    fun `条件开关切换分支`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t
                <if test="a != null">WHERE a = 1</if>
                <if test="b != null"> AND b = 2</if>
              </select>
            </mapper>
        """.trimIndent())
        assertEquals("SELECT * FROM t WHERE a = 1 AND b = 2", compact(m.renderSql().sql))
        val state = m.defaultState().copy(enabledIds = setOf("a != null"))
        assertEquals("SELECT * FROM t WHERE a = 1", compact(m.renderSql(state).sql))
        val none = m.defaultState().copy(enabledIds = emptySet())
        assertEquals("SELECT * FROM t", compact(m.renderSql(none).sql))
    }

    @Test
    fun `表达式解析失败的test默认展开_开关可关闭`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t
                <if test="not valid @@ expression">WHERE a = 1</if>
              </select>
            </mapper>
        """.trimIndent())
        // 解析失败 → 默认 true（预览容错）
        assertEquals("SELECT * FROM t WHERE a = 1", compact(m.renderSql().sql))
        val state = m.defaultState().copy(enabledIds = emptySet())
        assertEquals("SELECT * FROM t", compact(m.renderSql(state).sql))
    }

    @Test
    fun `语句元信息透传`() {
        val m = model("""
            <mapper namespace="com.example.BizMapper">
              <update id="updateById" parameterType="map">UPDATE t SET a = 1 WHERE id = 2</update>
            </mapper>
        """.trimIndent(), statementId = "updateById")
        assertEquals("updateById", m.statementId)
        assertEquals(XmlStatementKind.UPDATE, m.kind)
        assertEquals("map", m.parameterType)
    }
}
