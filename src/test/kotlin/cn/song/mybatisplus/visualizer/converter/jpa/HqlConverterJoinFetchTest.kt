package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HqlConverter JOIN FETCH 专项测试。
 * 验证 SQL 不重复、格式正确。
 */
class HqlConverterJoinFetchTest {

    private val userTable = TableInfo(
        entityClassName = "UserEntity",
        tableName = "user_entity",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("username", "username"),
            ColumnInfo("departmentId", "department_id"),
        ),
    )

    @Test
    fun testJoinFetchSingleCallNoDuplication() {
        // 用户反馈的核心问题：jpqlJoinFetch 的 SQL 被重复显示
        val hql = "SELECT u FROM UserEntity u JOIN FETCH u.department d WHERE d.name = :deptName"
        val result = HqlConverter.convert(hql, userTable)

        // SQL 不应重复
        val selectCount = result.sql.split("SELECT").size - 1
        assertEquals("应只有一个 SELECT", 1, selectCount)
        val fromCount = result.sql.split("FROM").size - 1
        assertEquals("应只有一个 FROM", 1, fromCount)
        val whereCount = result.sql.split("WHERE").size - 1
        assertEquals("应只有一个 WHERE", 1, whereCount)

        // 验证格式正确
        assertTrue("SQL 应含 LEFT JOIN", result.sql.contains("LEFT JOIN"))
        assertTrue("SQL 应含 ON", result.sql.contains(" ON "))
        assertFalse("SQL 不应含 JOIN FETCH", result.sql.contains("JOIN FETCH"))

        // 验证完整输出
        // T-HQL-SELECT-01：SELECT u → 列清单；JOIN 副表 d 无列元数据，降级为 d.*
        val expected = "SELECT u.id, u.username, u.department_id FROM user_entity u " +
            "LEFT JOIN department d ON d.id = u.department_id /* 推断的ON条件，请验证 */ WHERE d.name = ?"
        assertEquals("SQL 应正确转换", expected, result.sql)
    }

    @Test
    fun testJoinFetchNoTableInfoNoDuplication() {
        // 无 TableInfo（实体不可解析）场景
        val hql = "SELECT u FROM UserEntity u JOIN FETCH u.department d WHERE d.name = :deptName"
        val result = HqlConverter.convert(hql, null)

        // SQL 不应重复
        val selectCount = result.sql.split("SELECT").size - 1
        assertEquals("应只有一个 SELECT", 1, selectCount)

        // 验证空格正确（不应出现 uLEFT）
        assertFalse("SQL 不应含 uLEFT（缺少空格）", result.sql.contains("uLEFT"))
        assertTrue("SQL 应含 LEFT JOIN", result.sql.contains("LEFT JOIN"))
    }

    @Test
    fun testJoinFetchAliasAppearsOnce() {
        // 别名 d 只应出现一次（在 JOIN department d ON d.id = ... 中）
        val hql = "SELECT u FROM UserEntity u JOIN FETCH u.department d WHERE d.name = :deptName"
        val result = HqlConverter.convert(hql, userTable)

        // 不应出现 "d d.id"（别名重复）
        assertFalse("SQL 不应含 d d.id（别名重复）", result.sql.contains("d d.id"))
    }

    @Test
    fun testJoinFetchLeftJoinType() {
        val hql = "SELECT u FROM UserEntity u LEFT JOIN FETCH u.department d WHERE d.name = :deptName"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("SQL 应含 LEFT JOIN", result.sql.contains("LEFT JOIN"))
        assertFalse("SQL 不应含 LEFT JOIN FETCH", result.sql.contains("LEFT JOIN FETCH"))
    }
}
