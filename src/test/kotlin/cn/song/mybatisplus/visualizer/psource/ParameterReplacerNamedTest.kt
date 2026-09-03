package cn.song.mybatisplus.visualizer.psource

import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import org.junit.Test
import org.junit.Assert.*

/**
 * [ParameterReplacer.replaceNamedParams] 纯函数单测（按名回填，零 PSI 依赖）。
 *
 * 覆盖：按名替换、同名复用、空值保留、单引号转义、数值名裸渲染、`?`+`:name` 共存、双冒号守卫。
 */
class ParameterReplacerNamedTest {

    // ------------------------------------------------------------------
    // 基本按名替换
    // ------------------------------------------------------------------

    @Test
    fun `replaceNamedParams - single param replaced`() {
        val sql = "SELECT * FROM t WHERE id = :id"
        val values = mapOf("id" to "42")
        val hints = mapOf("id" to ParamHint(quote = false))
        val result = ParameterReplacer.replaceNamedParams(sql, values, hints)
        assertEquals("SELECT * FROM t WHERE id = 42", result)
    }

    @Test
    fun `replaceNamedParams - string param quoted`() {
        val sql = "SELECT * FROM t WHERE name = :name"
        val values = mapOf("name" to "O'Brien")
        val hints = mapOf("name" to ParamHint(quote = true))
        val result = ParameterReplacer.replaceNamedParams(sql, values, hints)
        assertEquals("SELECT * FROM t WHERE name = 'O''Brien'", result)
    }

    @Test
    fun `replaceNamedParams - duplicate name all occurrences replaced`() {
        // NamedParameterJdbcTemplate 语义：同名参数所有出现共用一值
        val sql = "WHERE a = :CUR_USER OR b = :CUR_USER OR c = :CUR_USER"
        val values = mapOf("CUR_USER" to "admin")
        val hints = mapOf("CUR_USER" to ParamHint(quote = true))
        val result = ParameterReplacer.replaceNamedParams(sql, values, hints)
        assertEquals("WHERE a = 'admin' OR b = 'admin' OR c = 'admin'", result)
    }

    @Test
    fun `replaceNamedParams - empty value keeps placeholder`() {
        val sql = "WHERE a = :CUR_USER"
        val values = mapOf("CUR_USER" to "")
        val hints = mapOf("CUR_USER" to ParamHint(quote = true))
        val result = ParameterReplacer.replaceNamedParams(sql, values, hints)
        // 空值 → 原样保留 `:CUR_USER`
        assertEquals("WHERE a = :CUR_USER", result)
    }

    @Test
    fun `replaceNamedParams - missing value keeps placeholder`() {
        val sql = "WHERE a = :CUR_USER AND b = :flowKey"
        val values = mapOf("CUR_USER" to "admin")
        val hints = mapOf("CUR_USER" to ParamHint(quote = true), "flowKey" to ParamHint(quote = true))
        val result = ParameterReplacer.replaceNamedParams(sql, values, hints)
        // :flowKey 未填 → 保留
        assertEquals("WHERE a = 'admin' AND b = :flowKey", result)
    }

    @Test
    fun `replaceNamedParams - numeric name bare render`() {
        val sql = "LIMIT :pageSize OFFSET :pageNumber"
        val values = mapOf("pageSize" to "20", "pageNumber" to "0")
        val hints = mapOf("pageSize" to ParamHint(quote = false), "pageNumber" to ParamHint(quote = false))
        val result = ParameterReplacer.replaceNamedParams(sql, values, hints)
        assertEquals("LIMIT 20 OFFSET 0", result)
    }

    @Test
    fun `replaceNamedParams - question mark untouched`() {
        // `?` 占位符不受影响（由 buildExecutableSql 按序回填）
        val sql = "WHERE a = ? AND b = :name"
        val values = mapOf("name" to "abc")
        val hints = mapOf("name" to ParamHint(quote = true))
        val result = ParameterReplacer.replaceNamedParams(sql, values, hints)
        assertEquals("WHERE a = ? AND b = 'abc'", result)
    }

    @Test
    fun `replaceNamedParams - double colon guard`() {
        // PostgreSQL 类型转换语法不应被替换
        val sql = "WHERE id::integer = :id"
        val values = mapOf("id" to "1")
        val hints = mapOf("id" to ParamHint(quote = false))
        val result = ParameterReplacer.replaceNamedParams(sql, values, hints)
        assertEquals("WHERE id::integer = 1", result)
    }

    @Test
    fun `replaceNamedParams - empty values returns original`() {
        val sql = "WHERE a = :id"
        val result = ParameterReplacer.replaceNamedParams(sql, emptyMap(), emptyMap())
        assertEquals(sql, result)
    }

    @Test
    fun `replaceNamedParams - empty hints returns original`() {
        val sql = "WHERE a = :id"
        val result = ParameterReplacer.replaceNamedParams(sql, mapOf("id" to "1"), emptyMap())
        assertEquals(sql, result)
    }

    @Test
    fun `replaceNamedParams - no named params returns original`() {
        val sql = "SELECT 1"
        val result = ParameterReplacer.replaceNamedParams(sql, mapOf("x" to "1"), mapOf("x" to ParamHint()))
        assertEquals(sql, result)
    }

    @Test
    fun `replaceNamedParams - single quote escaping`() {
        val sql = "WHERE name = :name"
        val values = mapOf("name" to "O'Brien")
        val hints = mapOf("name" to ParamHint(quote = true))
        val result = ParameterReplacer.replaceNamedParams(sql, values, hints)
        assertEquals("WHERE name = 'O''Brien'", result)
    }

    @Test
    fun `replaceNamedParams - null hint falls back to heuristic`() {
        // hint 为 null → 走 NUMBER_REGEX 启发式
        val sql = "WHERE a = :num AND b = :str"
        val values = mapOf("num" to "42", "str" to "hello")
        val hints = mapOf("num" to ParamHint(quote = false))  // :str 无 hint
        val result = ParameterReplacer.replaceNamedParams(sql, values, hints)
        // :num 裸渲染（hint quote=false），:str 走启发式 → 非数值 → 引号
        assertEquals("WHERE a = 42 AND b = 'hello'", result)
    }

    // ------------------------------------------------------------------
    // 示例一真实片段
    // ------------------------------------------------------------------

    @Test
    fun `replaceNamedParams - real world repeated param`() {
        val sql = "WHERE WT.OWNER = :CUR_USER OR WT.ASSIGNEE = :CUR_USER"
        val values = mapOf("CUR_USER" to "user123")
        val hints = mapOf("CUR_USER" to ParamHint(quote = true))
        val result = ParameterReplacer.replaceNamedParams(sql, values, hints)
        assertEquals("WHERE WT.OWNER = 'user123' OR WT.ASSIGNEE = 'user123'", result)
    }
}
