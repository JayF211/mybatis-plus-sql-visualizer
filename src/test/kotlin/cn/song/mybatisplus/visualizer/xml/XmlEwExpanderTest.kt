package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MP 占位符识别与片段提取测试（T-M26-B，P-立项-03）：
 * `${变量.customSqlSegment/sqlSelect/sqlSet}` 正则识别、WHERE/SELECT/SET 片段提取。
 */
class XmlEwExpanderTest {

    @Test
    fun `占位识别_变量与类型`() {
        val text = "WHERE \${ew.customSqlSegment} AND \${ew.sqlSelect} AND \${other.sqlSet}"
        val placeholders = XmlEwExpander.placeholders(text)
        assertEquals(3, placeholders.size)
        assertEquals(EwPlaceholder("ew", "customSqlSegment"), placeholders[0])
        assertEquals(EwPlaceholder("ew", "sqlSelect"), placeholders[1])
        assertEquals(EwPlaceholder("other", "sqlSet"), placeholders[2])
    }

    @Test
    fun `占位识别_去重与无匹配`() {
        val placeholders = XmlEwExpander.placeholders("\${ew.customSqlSegment} \${ew.customSqlSegment}")
        assertEquals(1, placeholders.size)
        assertTrue(XmlEwExpander.placeholders("SELECT 1").isEmpty())
        // 非 MP 占位（普通 ${}）不识别
        assertTrue(XmlEwExpander.placeholders("\${ew.otherProperty}").isEmpty())
    }

    @Test
    fun `提取WHERE片段`() {
        assertEquals(
            "WHERE (id = 1 AND name = 'x')",
            XmlEwExpander.extractWhere("SELECT * FROM t\nWHERE id = 1\nAND name = 'x'"),
        )
        assertNull(XmlEwExpander.extractWhere("SELECT 1"))
        assertNull(XmlEwExpander.extractWhere("SELECT * FROM t"))
    }

    @Test
    fun `提取SELECT列清单`() {
        assertEquals("id, name", XmlEwExpander.extractSelect("SELECT id, name FROM t WHERE x = 1"))
        assertNull(XmlEwExpander.extractSelect("UPDATE t SET a = 1"))
    }

    @Test
    fun `提取SET片段`() {
        assertEquals("name = 'x', age = 1", XmlEwExpander.extractSet("UPDATE t SET name = 'x', age = 1 WHERE id = 1"))
        assertNull(XmlEwExpander.extractSet("SELECT * FROM t"))
    }
}
