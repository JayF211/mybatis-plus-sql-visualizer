package cn.song.mybatisplus.visualizer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T-StatementNaming（O-19）T-SN-01：语句注释命名提取表驱动。
 * DBX e561bd15 三规则（Name: 优先 / 最近行注释回退 / 块注释剔除）+ XML 注释方言扩展 +
 * 行内尾注释不误取 + 截断 + 无注释 null（消费方零差异的硬门禁前提）。
 */
class SqlStatementNamerTest {

    @Test
    fun testName前缀优先() {
        assertEquals("分页查询", SqlStatementNamer.nameOf("-- Name: 分页查询\nSELECT * FROM t"))
        assertEquals("p", SqlStatementNamer.nameOf("  -- name: p\nSELECT 1"))
        assertEquals("N", SqlStatementNamer.nameOf("-- NAME: N\nSELECT 1"))
    }

    @Test
    fun test最近一条行注释回退() {
        assertEquals("b", SqlStatementNamer.nameOf("-- a\n-- b\nSELECT 1"))
        // 空行不断链（回退取「最近一条非空行注释」）
        assertEquals("b", SqlStatementNamer.nameOf("-- a\n\n-- b\n\nSELECT 1"))
    }

    @Test
    fun test块注释剔除不当名() {
        assertNull(SqlStatementNamer.nameOf("/* 头部说明 */\nSELECT 1"))
        // 块注释前的行注释仍是回退候选
        assertEquals("a", SqlStatementNamer.nameOf("-- a\n/* 说明 */\nSELECT 1"))
    }

    @Test
    fun testXML注释剔除不当名() {
        assertNull(SqlStatementNamer.nameOf("<!-- mapper 注释 -->\nSELECT 1"))
    }

    @Test
    fun test行内尾注释不误取() {
        assertNull(SqlStatementNamer.nameOf("SELECT 1 -- 分页"))
    }

    @Test
    fun test代码行终止前导区() {
        // 语句后的注释不是名字
        assertNull(SqlStatementNamer.nameOf("SELECT 1\n-- 后置注释"))
    }

    @Test
    fun test纯分隔线不当名() {
        assertNull(SqlStatementNamer.nameOf("--\nSELECT 1"))
        assertNull(SqlStatementNamer.nameOf("-- ----\nSELECT 1"))
    }

    @Test
    fun test超长截断40() {
        val long = "很长的名称超过四十个字符上限很长的名称超过四十个字符上限很长的名称超过四十个字符上限"
        val got = SqlStatementNamer.nameOf("-- Name: $long\nSELECT 1")!!
        assertEquals(40, got.length)
        assertEquals(long.take(40), got)
    }

    @Test
    fun test无注释返回null() {
        assertNull(SqlStatementNamer.nameOf("SELECT 1"))
        assertNull(SqlStatementNamer.nameOf(""))
    }
}
