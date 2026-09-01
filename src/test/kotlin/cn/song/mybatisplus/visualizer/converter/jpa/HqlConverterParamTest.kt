package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HqlConverter 参数提取专项测试。
 * 验证参数名从 HQL 字符串中正确提取。
 */
class HqlConverterParamTest {

    private val userTable = TableInfo("UserEntity", "user_entity", listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("username", "username"),
            ColumnInfo("age", "age"),
        ))

    @Test
    fun testNamedParamExtractedInSql() {
        // jpqlQuery 的 named 查询
        val hql = "SELECT u FROM UserEntity u WHERE u.username = :username"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("SQL 应含 ?", result.sql.contains("?"))
        assertFalse("SQL 不应含 :username", result.sql.contains(":username"))
    }

    @Test
    fun testPositionalParamsExtractedInSql() {
        // jpqlQuery 的 positional 查询
        val hql = "SELECT u FROM UserEntity u WHERE u.username = ?1 AND u.age >= ?2"
        val result = HqlConverter.convert(hql, userTable)
        val placeholderCount = result.sql.count { it == '?' }
        assertEquals("应有两个 ? 占位符", 2, placeholderCount)
        assertFalse("SQL 不应含 ?1", result.sql.contains("?1"))
    }

    @Test
    fun testJoinFetchSqlCorrect() {
        // jpqlJoinFetch 的完整转换
        val hql = "SELECT u FROM UserEntity u JOIN FETCH u.department d WHERE d.name = :deptName"
        val result = HqlConverter.convert(hql, userTable)

        // 验证 SQL 不重复
        val selectCount = result.sql.split("SELECT").size - 1
        assertEquals("应只有一个 SELECT", 1, selectCount)

        // 验证格式
        assertTrue("SQL 应含 LEFT JOIN", result.sql.contains("LEFT JOIN"))
        assertTrue("SQL 应含 ON", result.sql.contains(" ON "))
        assertTrue("SQL 应含 ?", result.sql.contains("?"))

        // 验证完整输出
        // T-HQL-SELECT-01：SELECT u → 列清单
        val expected = "SELECT u.id, u.username, u.age FROM user_entity u " +
            "LEFT JOIN department d ON d.id = u.department_id /* 推断的ON条件，请验证 */ WHERE d.name = ?"
        assertEquals("SQL 应正确转换", expected, result.sql)
    }

    @Test
    fun testInClauseParamsMarked() {
        // IN 子句参数应被标记
        val hql = "SELECT u FROM UserEntity u WHERE u.status IN :statuses"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("IN 子句参数应被标记", result.inClauseParams.contains("statuses"))
    }
}
