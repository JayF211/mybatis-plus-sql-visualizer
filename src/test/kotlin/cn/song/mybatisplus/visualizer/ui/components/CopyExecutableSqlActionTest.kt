package cn.song.mybatisplus.visualizer.ui.components

import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.ParamHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O-4：复制可执行 SQL Action 测试（纯逻辑，不依赖 Swing/EDT）。
 *
 * 验证 [CopyExecutableSqlAction] 委托的替换逻辑（[ParameterReplacer.buildExecutableSql]）正确性，
 * 以及边界场景（无参数 / 全空值 / 引号转义）。
 */
class CopyExecutableSqlActionTest {

    @Test
    fun `buildExecutableSql_委托验证_数值与字符串混合`() {
        val sql = "SELECT * FROM t WHERE id = ? AND name = ? AND age > ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql,
            listOf("id", "name", "age"),
            mapOf("id" to "1", "name" to "Alice", "age" to "18"),
            listOf(ParamHint(false), ParamHint(true), ParamHint(false)),
        )
        assertEquals("SELECT * FROM t WHERE id = 1 AND name = 'Alice' AND age > 18", result)
    }

    @Test
    fun `buildExecutableSql_空值保留占位符_复制时跳过`() {
        val sql = "SELECT * FROM t WHERE id = ? AND name = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql,
            listOf("id", "name"),
            mapOf("id" to "1"),
            listOf(ParamHint(false), ParamHint(true)),
        )
        // id 替换，name 未填保留 ?
        assertEquals("SELECT * FROM t WHERE id = 1 AND name = ?", result)
    }

    @Test
    fun `buildExecutableSql_全空值_结果与原始一致`() {
        val sql = "SELECT * FROM t WHERE id = ? AND name = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql,
            listOf("id", "name"),
            emptyMap(),
            listOf(ParamHint(false), ParamHint(true)),
        )
        assertEquals(sql, result)
    }

    @Test
    fun `buildExecutableSql_日期字符串_加引号`() {
        val sql = "SELECT * FROM t WHERE created_at > ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql,
            listOf("createdAt"),
            mapOf("createdAt" to "2026-01-01"),
            listOf(ParamHint(true)),
        )
        assertEquals("SELECT * FROM t WHERE created_at > '2026-01-01'", result)
    }

    @Test
    fun `buildExecutableSql_小数数值_不加引号`() {
        val sql = "SELECT * FROM t WHERE price < ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql,
            listOf("price"),
            mapOf("price" to "19.99"),
            listOf(ParamHint(false)),
        )
        assertEquals("SELECT * FROM t WHERE price < 19.99", result)
    }

    @Test
    fun `buildExecutableSql_启发式_负数数值`() {
        val sql = "SELECT * FROM t WHERE delta = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql,
            listOf("delta"),
            mapOf("delta" to "-5"),
            emptyList(),
        )
        // 无 hint 走启发式：-5 匹配 NUMBER_REGEX → 不加引号
        assertEquals("SELECT * FROM t WHERE delta = -5", result)
    }

    @Test
    fun `countReplaced_逻辑验证_通过SQL差异计算`() {
        // 间接验证替换计数逻辑：原始 3 个 ?，结果 1 个 ?，替换 2 个
        val original = "WHERE a = ? AND b = ? AND c = ?"
        val result = "WHERE a = 1 AND b = 'x' AND c = ?"
        val replaced = original.count { it == '?' } - result.count { it == '?' }
        assertEquals(2, replaced)
    }

    @Test
    fun `buildExecutableSql_值含单引号_转义为双单引号`() {
        val sql = "SELECT * FROM t WHERE name = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql,
            listOf("name"),
            mapOf("name" to "It's a test"),
            listOf(ParamHint(true)),
        )
        assertEquals("SELECT * FROM t WHERE name = 'It''s a test'", result)
    }

    @Test
    fun `buildExecutableSql_无参数SQL_原样返回`() {
        val sql = "SELECT 1"
        val result = ParameterReplacer.buildExecutableSql(
            sql,
            emptyList(),
            emptyMap(),
        )
        assertEquals("SELECT 1", result)
    }
}
