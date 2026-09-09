package cn.song.mybatisplus.visualizer.psource

import org.junit.Test
import org.junit.Assert.*

/**
 * [JdbcTemplateMethodDetector] 纯函数单测（O(1) 文本匹配）。
 *
 * 覆盖：query 族 / update / execute 命中；非 JDBC 方法名排除；null 排除。
 */
class JdbcTemplateMethodDetectorTest {

    // ------------------------------------------------------------------
    // query 族命中
    // ------------------------------------------------------------------

    @Test
    fun `isJdbcTemplateMethodReference - query variants hit`() {
        assertTrue(JdbcTemplateMethodDetector.isJdbcTemplateMethodReference("query"))
        assertTrue(JdbcTemplateMethodDetector.isJdbcTemplateMethodReference("queryForObject"))
        assertTrue(JdbcTemplateMethodDetector.isJdbcTemplateMethodReference("queryForList"))
        assertTrue(JdbcTemplateMethodDetector.isJdbcTemplateMethodReference("queryForMap"))
        assertTrue(JdbcTemplateMethodDetector.isJdbcTemplateMethodReference("queryForRowSet"))
        assertTrue(JdbcTemplateMethodDetector.isJdbcTemplateMethodReference("queryForLong"))
        assertTrue(JdbcTemplateMethodDetector.isJdbcTemplateMethodReference("queryForInt"))
        assertTrue(JdbcTemplateMethodDetector.isJdbcTemplateMethodReference("queryForStream"))
    }

    // ------------------------------------------------------------------
    // update / execute 命中
    // ------------------------------------------------------------------

    @Test
    fun `isJdbcTemplateMethodReference - update and execute hit`() {
        assertTrue(JdbcTemplateMethodDetector.isJdbcTemplateMethodReference("update"))
        assertTrue(JdbcTemplateMethodDetector.isJdbcTemplateMethodReference("batchUpdate"))
        assertTrue(JdbcTemplateMethodDetector.isJdbcTemplateMethodReference("execute"))
    }

    // ------------------------------------------------------------------
    // 自研 DAO 工具层（DbHelper 等）的 delete / insert 命中
    // ------------------------------------------------------------------

    @Test
    fun `isJdbcTemplateMethodReference - dao helper delete and insert hit`() {
        assertTrue(JdbcTemplateMethodDetector.isJdbcTemplateMethodReference("delete"))
        assertTrue(JdbcTemplateMethodDetector.isJdbcTemplateMethodReference("insert"))
    }

    // ------------------------------------------------------------------
    // 非 JDBC 方法名排除
    // ------------------------------------------------------------------

    @Test
    fun `isJdbcTemplateMethodReference - non-jdbc methods miss`() {
        assertFalse(JdbcTemplateMethodDetector.isJdbcTemplateMethodReference("createQuery"))
        assertFalse(JdbcTemplateMethodDetector.isJdbcTemplateMethodReference("createNativeQuery"))
        assertFalse(JdbcTemplateMethodDetector.isJdbcTemplateMethodReference("findByQuery"))
        assertFalse(JdbcTemplateMethodDetector.isJdbcTemplateMethodReference("executeHql"))
        assertFalse(JdbcTemplateMethodDetector.isJdbcTemplateMethodReference("selectList"))
        assertFalse(JdbcTemplateMethodDetector.isJdbcTemplateMethodReference("eq"))
        assertFalse(JdbcTemplateMethodDetector.isJdbcTemplateMethodReference(null))
    }

    // ------------------------------------------------------------------
    // JDBC_TEMPLATE_METHODS 集合完整性
    // ------------------------------------------------------------------

    @Test
    fun `JDBC_TEMPLATE_METHODS - contains all expected methods`() {
        val expected = setOf(
            "query", "queryForObject", "queryForList", "queryForMap",
            "queryForRowSet", "queryForLong", "queryForInt", "queryForStream",
            "update", "batchUpdate", "delete", "insert", "execute",
        )
        assertEquals(expected, JdbcTemplateMethodDetector.JDBC_TEMPLATE_METHODS)
    }

    // ------------------------------------------------------------------
    // T-DaoSql-01：自研 DAO select 族（DAO_STRING_SQL_METHODS / isStringSqlMethodCandidate）
    // ------------------------------------------------------------------

    @Test
    fun `DAO_STRING_SQL_METHODS - user framework select family hits`() {
        assertTrue(JdbcTemplateMethodDetector.DAO_STRING_SQL_METHODS.contains("selectList"))
        assertTrue(JdbcTemplateMethodDetector.DAO_STRING_SQL_METHODS.contains("selectListPagination"))
        assertTrue(JdbcTemplateMethodDetector.DAO_STRING_SQL_METHODS.contains("selectOne"))
        assertTrue(JdbcTemplateMethodDetector.DAO_STRING_SQL_METHODS.contains("selectCount"))
        assertTrue(JdbcTemplateMethodDetector.DAO_STRING_SQL_METHODS.contains("selectPage"))
        assertTrue(JdbcTemplateMethodDetector.DAO_STRING_SQL_METHODS.contains("selectMaps"))
        assertTrue(JdbcTemplateMethodDetector.DAO_STRING_SQL_METHODS.contains("selectObjs"))
    }

    @Test
    fun `isStringSqlMethodCandidate - jdbc template and dao select family both hit`() {
        // Spring JDBC 模板方法名仍命中
        assertTrue(JdbcTemplateMethodDetector.isStringSqlMethodCandidate("queryForList"))
        assertTrue(JdbcTemplateMethodDetector.isStringSqlMethodCandidate("update"))
        assertTrue(JdbcTemplateMethodDetector.isStringSqlMethodCandidate("execute"))
        // 自研 DAO select 族命中（用户反馈场景：selectList / selectListPagination）
        assertTrue(JdbcTemplateMethodDetector.isStringSqlMethodCandidate("selectList"))
        assertTrue(JdbcTemplateMethodDetector.isStringSqlMethodCandidate("selectListPagination"))
    }

    @Test
    fun `isStringSqlMethodCandidate - non sql method names miss`() {
        assertFalse(JdbcTemplateMethodDetector.isStringSqlMethodCandidate("createQuery"))
        assertFalse(JdbcTemplateMethodDetector.isStringSqlMethodCandidate("findByQuery"))
        assertFalse(JdbcTemplateMethodDetector.isStringSqlMethodCandidate("eq"))
        assertFalse(JdbcTemplateMethodDetector.isStringSqlMethodCandidate("updateBatchById"))
        assertFalse(JdbcTemplateMethodDetector.isStringSqlMethodCandidate(null))
    }
}
