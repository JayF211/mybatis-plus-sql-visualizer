package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 显示层 SQL 末尾分号规范化单测（withTerminator，复制/预览共用）：
 * 锁定复制纯 SQL 与预览弹窗的分号边界——此前用户实测复制出的 SQL 偶发缺分号或重复分号。
 */
class WithTerminatorTest {

    @Test
    fun `空串保持空`() {
        assertEquals("", withTerminator(""))
    }

    @Test
    fun `普通SQL补分号`() {
        assertEquals("SELECT 1;", withTerminator("SELECT 1"))
    }

    @Test
    fun `已以分号结尾不重复`() {
        assertEquals("SELECT 1;", withTerminator("SELECT 1;"))
    }

    @Test
    fun `以空格加分号结尾不重复`() {
        assertEquals("SELECT 1 ;", withTerminator("SELECT 1 ;"))
    }

    @Test
    fun `多行SQL不以分号结尾补充于末尾`() {
        val sql = "SELECT a\nFROM t\nWHERE id = 1"
        assertEquals("$sql;", withTerminator(sql))
        assertTrue("结尾为分号", withTerminator(sql).endsWith(";"))
    }

    @Test
    fun `已含尾随分号的多行SQL不重复`() {
        val sql = "SELECT a\nFROM t;"
        assertEquals(sql, withTerminator(sql))
    }
}
