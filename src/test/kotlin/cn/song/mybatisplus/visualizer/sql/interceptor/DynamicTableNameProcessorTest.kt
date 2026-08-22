package cn.song.mybatisplus.visualizer.sql.interceptor

import cn.song.mybatisplus.visualizer.config.TableNameRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * DynamicTableNameProcessor 单元测试（F-6 Phase B）：验证正则表名替换引擎。
 */
class DynamicTableNameProcessorTest {

    @Before
    fun setUp() {
        DynamicTableNameProcessor.clearCache()
    }

    @Test
    fun `simple replacement without regex group`() {
        val rules = listOf(TableNameRule("user", "order", enabled = true))
        assertEquals("order", DynamicTableNameProcessor.process("user", rules))
    }

    @Test
    fun `regex capture group reference`() {
        val rules = listOf(TableNameRule("user_(\\d+)", "user", enabled = true))
        assertEquals("user", DynamicTableNameProcessor.process("user_2024", rules))
    }

    @Test
    fun `tableName variable reference`() {
        val rules = listOf(TableNameRule("order", "archive_\$tableName", enabled = true))
        assertEquals("archive_order", DynamicTableNameProcessor.process("order", rules))
    }

    @Test
    fun `no matching rule returns null`() {
        val rules = listOf(TableNameRule("user", "order", enabled = true))
        assertNull(DynamicTableNameProcessor.process("product", rules))
    }

    @Test
    fun `disabled rule is skipped`() {
        val rules = listOf(TableNameRule("user", "order", enabled = false))
        assertNull(DynamicTableNameProcessor.process("user", rules))
    }

    @Test
    fun `first matching rule wins`() {
        val rules = listOf(
            TableNameRule("user_(\\d+)", "first_\$1", enabled = true),
            TableNameRule("user_.*", "second", enabled = true),
        )
        assertEquals("first_2024", DynamicTableNameProcessor.process("user_2024", rules))
    }

    @Test
    fun `empty rules list returns null`() {
        assertNull(DynamicTableNameProcessor.process("user", emptyList()))
    }

    @Test
    fun `blank pattern is skipped`() {
        val rules = listOf(TableNameRule("", "order", enabled = true))
        assertNull(DynamicTableNameProcessor.process("user", rules))
    }

    @Test
    fun `invalid regex is skipped gracefully`() {
        val rules = listOf(TableNameRule("user[invalid", "order", enabled = true))
        assertNull(DynamicTableNameProcessor.process("user", rules))
    }

    @Test
    fun `multiple capture groups`() {
        val rules = listOf(TableNameRule("(\\w+)_(\\d+)", "\$1_archive_\$2", enabled = true))
        assertEquals("order_archive_2024", DynamicTableNameProcessor.process("order_2024", rules))
    }

    @Test
    fun `partial match replaces entire table name`() {
        // 正则匹配部分即可命中，但替换模板指定的是完整新表名（非仅替换匹配部分）
        val rules = listOf(TableNameRule("_\\d+", "archive", enabled = true))
        assertEquals("archive", DynamicTableNameProcessor.process("order_2024", rules))
    }
}
