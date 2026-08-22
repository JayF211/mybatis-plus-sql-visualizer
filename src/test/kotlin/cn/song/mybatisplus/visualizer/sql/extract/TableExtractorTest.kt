package cn.song.mybatisplus.visualizer.sql.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表名提取器测试（F-3）：覆盖 [TableExtractor] 从 SQL 文本提取表名列表。
 */
class TableExtractorTest {

    @Test
    fun `extract_单表`() {
        val tables = TableExtractor.extract("SELECT * FROM t_user")
        assertEquals(listOf("t_user"), tables)
    }

    @Test
    fun `extract_多表逗号分隔`() {
        val tables = TableExtractor.extract("SELECT * FROM t_user, t_order")
        assertTrue(tables.contains("t_user"))
        assertTrue(tables.contains("t_order"))
        assertEquals(2, tables.size)
    }

    @Test
    fun `extract_JOIN多表`() {
        val tables = TableExtractor.extract(
            "SELECT u.id, o.id FROM t_user u JOIN t_order o ON u.id = o.user_id"
        )
        assertTrue(tables.contains("t_user"))
        assertTrue(tables.contains("t_order"))
        assertEquals(2, tables.size)
    }

    @Test
    fun `extract_子查询内表`() {
        val tables = TableExtractor.extract(
            "SELECT id FROM t_user WHERE id IN (SELECT user_id FROM t_vip WHERE level > 3)"
        )
        assertTrue(tables.contains("t_user"))
        assertTrue(tables.contains("t_vip"))
    }

    @Test
    fun `extract_UNION多分支表`() {
        val tables = TableExtractor.extract(
            "SELECT id FROM t_user UNION SELECT id FROM t_admin"
        )
        assertTrue(tables.contains("t_user"))
        assertTrue(tables.contains("t_admin"))
    }

    @Test
    fun `extract_带别名去重`() {
        val tables = TableExtractor.extract(
            "SELECT u.id, u2.name FROM t_user u JOIN t_user u2 ON u.parent_id = u2.id"
        )
        assertEquals(1, tables.count { it == "t_user" })
    }

    @Test
    fun `extract_无FROM返回空`() {
        val tables = TableExtractor.extract("SELECT 1")
        assertEquals(emptyList<String>(), tables)
    }

    @Test
    fun `extract_空SQL返回空`() {
        assertEquals(emptyList<String>(), TableExtractor.extract(""))
    }

    @Test
    fun `extract_CTE表`() {
        val tables = TableExtractor.extract(
            "WITH cte AS (SELECT id FROM t_user) SELECT * FROM cte"
        )
        assertTrue(tables.contains("t_user"))
    }

    @Test
    fun `extract_UPDATE表`() {
        val tables = TableExtractor.extract("UPDATE t_user SET name = 'test' WHERE id = 1")
        assertEquals(listOf("t_user"), tables)
    }

    @Test
    fun `extract_DELETE表`() {
        val tables = TableExtractor.extract("DELETE FROM t_user WHERE id = 1")
        assertEquals(listOf("t_user"), tables)
    }

    @Test
    fun `extract_INSERT表`() {
        val tables = TableExtractor.extract("INSERT INTO t_user (name) VALUES ('test')")
        assertEquals(listOf("t_user"), tables)
    }

    @Test
    fun `extract_反引号表名`() {
        val tables = TableExtractor.extract("SELECT * FROM `t_user`")
        assertEquals(listOf("t_user"), tables)
    }

    @Test
    fun `extract_多JOIN多种类型`() {
        val tables = TableExtractor.extract(
            "SELECT u.id, o.id, p.id FROM t_user u " +
                "LEFT JOIN t_order o ON u.id = o.user_id " +
                "RIGHT JOIN t_product p ON o.product_id = p.id " +
                "INNER JOIN t_category c ON p.category_id = c.id " +
                "FULL OUTER JOIN t_tag t ON p.id = t.product_id"
        )
        assertEquals(5, tables.size)
        assertTrue(tables.containsAll(listOf("t_user", "t_order", "t_product", "t_category", "t_tag")))
    }
}
