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
            "update", "batchUpdate", "execute",
        )
        assertEquals(expected, JdbcTemplateMethodDetector.JDBC_TEMPLATE_METHODS)
    }
}
