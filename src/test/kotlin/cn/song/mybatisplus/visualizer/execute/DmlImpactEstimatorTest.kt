package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.mcp.McpSelectGuard
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.OracleDialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R42 DML 影响面预演定向测试（V60 边界已证伪入批）。
 *
 * 覆盖：单表改写等价、JOIN/多表/无 WHERE/多语句/非 DML 的诚实降级、DELETE LIMIT 收窄（方言支持时）
 * 与不收窄（方言不支持时）、以及**只读护栏联动**（每条试算语句必过 [McpSelectGuard]）。
 * 普通 JUnit——纯逻辑层。
 */
class DmlImpactEstimatorTest {

    private fun probe(sql: String, dialect: cn.song.mybatisplus.visualizer.sql.Dialect = MySqlDialect):
        DmlImpactEstimator.Probe {
        val plan = DmlImpactEstimator.plan(sql, dialect)
        assertTrue("应可试算：$plan", plan is DmlImpactEstimator.Plan.Applicable)
        return (plan as DmlImpactEstimator.Plan.Applicable).probe
    }

    private fun reason(sql: String, dialect: cn.song.mybatisplus.visualizer.sql.Dialect = MySqlDialect): String {
        val plan = DmlImpactEstimator.plan(sql, dialect)
        assertTrue("应不适用：$plan", plan is DmlImpactEstimator.Plan.NotApplicable)
        return (plan as DmlImpactEstimator.Plan.NotApplicable).reasonKey
    }

    @Test
    fun `单表DELETE_改写为COUNT且无上限`() {
        val p = probe("DELETE FROM t_user WHERE id = 1")
        assertEquals("SELECT COUNT(*) FROM t_user WHERE id = 1", p.probeSql)
        assertEquals("t_user", p.targetTable)
        assertEquals(null, p.rowCap)
        assertEquals("id = 1", p.whereText)
    }

    @Test
    fun `单表UPDATE_改写为COUNT`() {
        val p = probe("UPDATE t_user SET user_name = 'x' WHERE created_at > '2020-01-01'")
        assertEquals("SELECT COUNT(*) FROM t_user WHERE created_at > '2020-01-01'", p.probeSql)
        assertEquals("t_user", p.targetTable)
    }

    @Test
    fun `DELETE带LIMIT_方言支持时收窄到n行`() {
        val p = probe("DELETE FROM t_user WHERE id > 0 LIMIT 10", MySqlDialect)
        assertEquals(10L, p.rowCap)
        assertTrue(p.probeSql, p.probeSql.contains("LIMIT 10"))
        assertTrue("收窄形态应为子查询包裹：" + p.probeSql, p.probeSql.contains("SELECT COUNT(*) FROM (SELECT 1 FROM"))
    }

    @Test
    fun `DELETE带LIMIT_方言不支持时不收窄_按真实影响面`() {
        // Oracle 不支持 DELETE LIMIT → 渲染产物本就不含上限，试算按完整匹配行数（不设上限）
        val p = probe("DELETE FROM t_user WHERE id > 0 LIMIT 10", OracleDialect)
        assertEquals(null, p.rowCap)
        assertEquals("SELECT COUNT(*) FROM t_user WHERE id > 0", p.probeSql)
    }

    @Test
    fun `JOIN与多表_不适用`() {
        // UPDATE ... JOIN（多表写）——COUNT 会把一条目标行按匹配次数多计
        assertEquals(
            "dml.impact.reason.multiTable",
            reason("UPDATE t_user u JOIN t_order o ON u.id = o.user_id SET u.user_name = 'x' WHERE o.amount > 1"),
        )
    }

    @Test
    fun `子查询单表_等价放行`() {
        val p = probe("DELETE FROM t_user WHERE id IN (SELECT user_id FROM t_order)")
        assertTrue(p.probeSql.contains("SELECT COUNT(*) FROM t_user WHERE id IN (SELECT user_id FROM t_order)"))
    }

    @Test
    fun `无WHERE_不适用_避免全表扫描`() {
        assertEquals("dml.impact.reason.noWhere", reason("DELETE FROM t_user"))
        assertEquals("dml.impact.reason.noWhere", reason("UPDATE t_user SET user_name = 'x'"))
    }

    @Test
    fun `多语句与非DML_不适用`() {
        assertEquals("dml.impact.reason.multiStatement", reason("DELETE FROM t WHERE id = 1; DELETE FROM t WHERE id = 2"))
        assertEquals("dml.impact.reason.notDml", reason("INSERT INTO t (id) VALUES (1)"))
        assertEquals("dml.impact.reason.notDml", reason("SELECT * FROM t WHERE id = 1"))
    }

    @Test
    fun `解析失败_不适用不抛`() {
        assertEquals("dml.impact.reason.unparseable", reason("DELETE FROM WHERE"))
    }

    @Test
    fun `长WHERE_试算使用完整谓词而提示仍有界`() {
        val predicate = (1..30).joinToString(" AND ") { "col_$it = $it" }
        assertTrue(predicate.length > 200)
        val p = probe("UPDATE t_user SET status = 1 WHERE $predicate")
        assertEquals("SELECT COUNT(*) FROM t_user WHERE $predicate", p.probeSql)
        assertTrue("提示文本不得无界增长", p.whereText.length <= 200)
    }

    @Test
    fun `长字符串值_截断提示不能截断试算引号`() {
        val value = "a".repeat(240)
        val p = probe("DELETE FROM t_user WHERE token = '$value'")
        assertEquals("SELECT COUNT(*) FROM t_user WHERE token = '$value'", p.probeSql)
        assertTrue("试算必须仍是合法只读语句", McpSelectGuard.checkSingleSelect(p.probeSql).allowed)
    }

    @Test
    fun `DELETE带LIMIT_尾部高选择性条件必须留在试算中`() {
        val prefix = (1..20).joinToString(" AND ") { "flag_$it = $it" }
        val p = probe("DELETE FROM t_user WHERE $prefix AND tenant_id = 42 LIMIT 10")
        assertTrue(p.probeSql, p.probeSql.contains("tenant_id = 42 LIMIT 10"))
        assertTrue("试算必须仍是合法只读语句", McpSelectGuard.checkSingleSelect(p.probeSql).allowed)
    }

    /**
     * V60 只读护栏联动：**每条试算语句必过 MCP 只读白名单**——即含 LIMIT 子查询/普通 COUNT
     * 两种形态都不得被判为写（否则 R42 的「只读试算」承诺不成立）。
     */
    @Test
    fun `试算语句全部通过MCP只读护栏`() {
        val probes = listOf(
            probe("DELETE FROM t_user WHERE id = 1", MySqlDialect),
            probe("DELETE FROM t_user WHERE id > 0 LIMIT 10", MySqlDialect),
            probe("UPDATE t_user SET user_name = 'x' WHERE created_at > '2020-01-01'"),
            probe("DELETE FROM t_user WHERE id IN (SELECT user_id FROM t_order)"),
        )
        for (p in probes) {
            val verdict = McpSelectGuard.checkSingleSelect(p.probeSql)
            assertTrue("试算语句应过只读护栏：${p.probeSql} → ${verdict.reason}", verdict.allowed)
        }
    }
}
