package cn.song.mybatisplus.visualizer.sql.extract

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JOIN 提取器测试（F-3）：覆盖 [JoinExtractor] 从 SQL 文本统计 JOIN 数量。
 */
class JoinExtractorTest {

    @Test
    fun `count_无JOIN返回0`() {
        assertEquals(0, JoinExtractor.count("SELECT * FROM t_user"))
    }

    @Test
    fun `count_单JOIN`() {
        assertEquals(1, JoinExtractor.count(
            "SELECT u.id, o.id FROM t_user u JOIN t_order o ON u.id = o.user_id"
        ))
    }

    @Test
    fun `count_多JOIN`() {
        assertEquals(3, JoinExtractor.count(
            "SELECT u.id, o.id, p.id FROM t_user u " +
                "JOIN t_order o ON u.id = o.user_id " +
                "JOIN t_product p ON o.product_id = p.id " +
                "JOIN t_category c ON p.category_id = c.id"
        ))
    }

    @Test
    fun `count_LEFT_JOIN`() {
        assertEquals(1, JoinExtractor.count(
            "SELECT u.id, o.id FROM t_user u LEFT JOIN t_order o ON u.id = o.user_id"
        ))
    }

    @Test
    fun `count_RIGHT_JOIN`() {
        assertEquals(1, JoinExtractor.count(
            "SELECT u.id, o.id FROM t_user u RIGHT JOIN t_order o ON u.id = o.user_id"
        ))
    }

    @Test
    fun `count_INNER_JOIN`() {
        assertEquals(1, JoinExtractor.count(
            "SELECT u.id, o.id FROM t_user u INNER JOIN t_order o ON u.id = o.user_id"
        ))
    }

    @Test
    fun `count_FULL_OUTER_JOIN`() {
        assertEquals(1, JoinExtractor.count(
            "SELECT u.id, o.id FROM t_user u FULL OUTER JOIN t_order o ON u.id = o.user_id"
        ))
    }

    @Test
    fun `count_CROSS_JOIN`() {
        assertEquals(1, JoinExtractor.count(
            "SELECT u.id, o.id FROM t_user u CROSS JOIN t_order o"
        ))
    }

    @Test
    fun `count_混合JOIN类型`() {
        assertEquals(4, JoinExtractor.count(
            "SELECT u.id, o.id, p.id FROM t_user u " +
                "LEFT JOIN t_order o ON u.id = o.user_id " +
                "RIGHT JOIN t_product p ON o.product_id = p.id " +
                "INNER JOIN t_category c ON p.category_id = c.id " +
                "FULL OUTER JOIN t_tag t ON p.id = t.product_id"
        ))
    }

    @Test
    fun `count_子查询内JOIN计入总数`() {
        // 主查询 1 JOIN + 子查询 1 JOIN = 2 JOIN
        assertEquals(2, JoinExtractor.count(
            "SELECT u.id FROM t_user u JOIN t_order o2 ON u.id = o2.user_id WHERE u.id IN " +
                "(SELECT o.user_id FROM t_order o JOIN t_product p ON o.product_id = p.id)"
        ))
    }

    @Test
    fun `count_空SQL返回0`() {
        assertEquals(0, JoinExtractor.count(""))
    }

    @Test
    fun `count_逗号分隔不计为JOIN`() {
        assertEquals(0, JoinExtractor.count(
            "SELECT u.id, o.id FROM t_user u, t_order o WHERE u.id = o.user_id"
        ))
    }

    @Test
    fun `joinTypes_返回JOIN类型列表`() {
        val types = JoinExtractor.joinTypes(
            "SELECT u.id, o.id FROM t_user u " +
                "LEFT JOIN t_order o ON u.id = o.user_id " +
                "INNER JOIN t_product p ON o.product_id = p.id"
        )
        assertEquals(2, types.size)
        assertEquals("LEFT", types[0])
        assertEquals("INNER", types[1])
    }
}
