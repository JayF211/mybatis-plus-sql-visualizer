package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SQL 表名提取器单元测试（O-1 Phase A）：从 SQL 中提取第一个表名，用于日志 ↔ 源码关联搜索。
 *
 * 覆盖场景：单表查询、JOIN、子查询、UPDATE/DELETE、反引号表名、schema 前缀、无表名。
 */
class SqlTableExtractorTest {

    @Test
    fun `简单 SELECT 提取表名`() {
        assertEquals("user", SqlTableExtractor.extractFirst("SELECT * FROM user WHERE id = 1"))
    }

    @Test
    fun `反引号表名去除反引号`() {
        assertEquals("order_info", SqlTableExtractor.extractFirst("SELECT * FROM `order_info` WHERE id = 1"))
    }

    @Test
    fun `JOIN 取第一个表名`() {
        assertEquals(
            "user",
            SqlTableExtractor.extractFirst(
                "SELECT u.name, o.amount FROM user u JOIN order_info o ON u.id = o.user_id",
            ),
        )
    }

    @Test
    fun `子查询取外层首个表名`() {
        assertEquals(
            "user",
            SqlTableExtractor.extractFirst(
                "SELECT * FROM user WHERE id IN (SELECT user_id FROM order_info)",
            ),
        )
    }

    @Test
    fun `UPDATE 提取表名`() {
        assertEquals("user", SqlTableExtractor.extractFirst("UPDATE user SET name = 'x' WHERE id = 1"))
    }

    @Test
    fun `DELETE 提取表名`() {
        assertEquals("user", SqlTableExtractor.extractFirst("DELETE FROM user WHERE id = 1"))
    }

    @Test
    fun `INSERT 提取表名`() {
        assertEquals("user", SqlTableExtractor.extractFirst("INSERT INTO user (name, age) VALUES ('a', 1)"))
    }

    @Test
    fun `schema 前缀只取表名`() {
        assertEquals("user", SqlTableExtractor.extractFirst("SELECT * FROM mybatis.user WHERE id = 1"))
    }

    @Test
    fun `空 SQL 返回 null`() {
        assertNull(SqlTableExtractor.extractFirst(""))
    }

    @Test
    fun `无 FROM 子句返回 null`() {
        assertNull(SqlTableExtractor.extractFirst("SELECT 1"))
    }

    @Test
    fun `小写 from 关键字兼容`() {
        assertEquals("user", SqlTableExtractor.extractFirst("select * from user where id = 1"))
    }

    @Test
    fun `表名后紧跟逗号（多表）取第一个`() {
        assertEquals("user", SqlTableExtractor.extractFirst("SELECT * FROM user, order_info WHERE user.id = order_info.user_id"))
    }
}
