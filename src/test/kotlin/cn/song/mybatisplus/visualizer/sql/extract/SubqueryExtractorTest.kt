package cn.song.mybatisplus.visualizer.sql.extract

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 子查询提取器测试（F-3）：覆盖 [SubqueryExtractor] 从 SQL 文本计算子查询最大嵌套深度。
 */
class SubqueryExtractorTest {

    @Test
    fun `maxDepth_无子查询返回0`() {
        assertEquals(0, SubqueryExtractor.maxDepth("SELECT * FROM t_user WHERE id = 1"))
    }

    @Test
    fun `maxDepth_单层子查询返回1`() {
        assertEquals(1, SubqueryExtractor.maxDepth(
            "SELECT id FROM t_user WHERE id IN (SELECT user_id FROM t_vip)"
        ))
    }

    @Test
    fun `maxDepth_双层嵌套子查询返回2`() {
        assertEquals(2, SubqueryExtractor.maxDepth(
            "SELECT id FROM t_user WHERE id IN " +
                "(SELECT user_id FROM t_vip WHERE level IN (SELECT level FROM t_config))"
        ))
    }

    @Test
    fun `maxDepth_三层嵌套子查询返回3`() {
        assertEquals(3, SubqueryExtractor.maxDepth(
            "SELECT id FROM t_user WHERE id IN " +
                "(SELECT user_id FROM t_vip WHERE level IN " +
                "(SELECT level FROM t_config WHERE group_id IN " +
                "(SELECT id FROM t_group)))"
        ))
    }

    @Test
    fun `maxDepth_多个同级子查询取最大`() {
        assertEquals(1, SubqueryExtractor.maxDepth(
            "SELECT id FROM t_user WHERE id IN (SELECT user_id FROM t_vip) " +
                "AND status IN (SELECT code FROM t_status)"
        ))
    }

    @Test
    fun `maxDepth_FROM子查询`() {
        assertEquals(1, SubqueryExtractor.maxDepth(
            "SELECT * FROM (SELECT id FROM t_user) sub"
        ))
    }

    @Test
    fun `maxDepth_空SQL返回0`() {
        assertEquals(0, SubqueryExtractor.maxDepth(""))
    }

    @Test
    fun `maxDepth_UNION内子查询`() {
        assertEquals(1, SubqueryExtractor.maxDepth(
            "SELECT id FROM t_user WHERE id IN (SELECT user_id FROM t_vip) " +
                "UNION " +
                "SELECT id FROM t_admin WHERE id IN (SELECT admin_id FROM t_role)"
        ))
    }

    @Test
    fun `count_子查询数量`() {
        assertEquals(2, SubqueryExtractor.count(
            "SELECT id FROM t_user WHERE id IN (SELECT user_id FROM t_vip) " +
                "AND status IN (SELECT code FROM t_status)"
        ))
    }

    @Test
    fun `count_嵌套子查询总数`() {
        assertEquals(2, SubqueryExtractor.count(
            "SELECT id FROM t_user WHERE id IN " +
                "(SELECT user_id FROM t_vip WHERE level IN (SELECT level FROM t_config))"
        ))
    }

    @Test
    fun `count_无子查询返回0`() {
        assertEquals(0, SubqueryExtractor.count("SELECT * FROM t_user"))
    }
}
