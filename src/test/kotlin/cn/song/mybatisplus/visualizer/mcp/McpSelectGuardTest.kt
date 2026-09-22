package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-McpToolset 只读白名单守卫单测（纯逻辑层，无平台/桩 classpath 依赖）。
 *
 * 覆盖计划 §五越权用例全拒矩阵：UPDATE / DDL / 多语句夹带 / 非白名单形态 / 解析失败 fail closed，
 * 以及合法 SELECT（含 CTE / UNION / ORDER BY DESC / REPLACE() 函数）放行。
 */
class McpSelectGuardTest {

    private fun assertDenied(sql: String?, code: String, message: String) {
        val verdict = McpSelectGuard.checkSingleSelect(sql)
        assertFalse(message, verdict.allowed)
        assertEquals(message, code, verdict.errorCode)
        assertTrue(message, !verdict.reason.isNullOrBlank())
    }

    private fun assertAllowed(sql: String) {
        val verdict = McpSelectGuard.checkSingleSelect(sql)
        assertTrue("应放行: $sql → ${verdict.errorCode}: ${verdict.reason}", verdict.allowed)
        assertNull(verdict.errorCode)
        assertNull(verdict.reason)
    }

    // ------------------------------------------------------------------
    // 合法 SELECT 放行
    // ------------------------------------------------------------------

    @Test
    fun `放行_单条普通SELECT`() {
        assertAllowed("SELECT * FROM user WHERE id = 1")
        assertAllowed("select name, age from t_user limit 10")
    }

    @Test
    fun `放行_CTE与UNION`() {
        assertAllowed("WITH c AS (SELECT id FROM a) SELECT * FROM c")
        assertAllowed("SELECT id FROM a UNION SELECT id FROM b ORDER BY id DESC")
    }

    @Test
    fun `放行_ORDERBY_DESC与REPLACE函数不误杀`() {
        assertAllowed("SELECT * FROM t ORDER BY created_at DESC")
        assertAllowed("SELECT REPLACE(name, 'a', 'b') FROM t")
    }

    @Test
    fun `放行_含子查询的SELECT`() {
        assertAllowed("SELECT * FROM t WHERE id IN (SELECT id FROM other)")
    }

    // ------------------------------------------------------------------
    // 写语句全拒（MCP_READ_ONLY）
    // ------------------------------------------------------------------

    @Test
    fun `拒绝_UPDATE`() {
        assertDenied("UPDATE user SET name = 'x' WHERE id = 1", McpToolErrors.READ_ONLY, "UPDATE 应拒")
        assertDenied("update t set a = 1", McpToolErrors.READ_ONLY, "小写 update 应拒")
    }

    @Test
    fun `拒绝_INSERT_DELETE_MERGE`() {
        assertDenied("INSERT INTO t(a) VALUES(1)", McpToolErrors.READ_ONLY, "INSERT 应拒")
        assertDenied("DELETE FROM t WHERE id = 1", McpToolErrors.READ_ONLY, "DELETE 应拒")
        assertDenied("MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET a = 1", McpToolErrors.READ_ONLY, "MERGE 应拒")
    }

    @Test
    fun `拒绝_DDL`() {
        assertDenied("CREATE TABLE t(id INT)", McpToolErrors.READ_ONLY, "CREATE 应拒")
        assertDenied("ALTER TABLE t ADD COLUMN c INT", McpToolErrors.READ_ONLY, "ALTER 应拒")
        assertDenied("DROP TABLE t", McpToolErrors.READ_ONLY, "DROP 应拒")
        assertDenied("TRUNCATE TABLE t", McpToolErrors.READ_ONLY, "TRUNCATE 应拒")
    }

    @Test
    fun `拒绝_会话与权限语句`() {
        assertDenied("GRANT SELECT ON t TO u", McpToolErrors.READ_ONLY, "GRANT 应拒")
        assertDenied("COMMIT", McpToolErrors.READ_ONLY, "COMMIT 应拒")
        assertDenied("USE other_db", McpToolErrors.READ_ONLY, "USE 应拒")
        assertDenied("SET @v = 1", McpToolErrors.READ_ONLY, "SET 应拒")
    }

    // ------------------------------------------------------------------
    // 语句形态拒绝（SQL_BLOCKED）
    // ------------------------------------------------------------------

    @Test
    fun `拒绝_多语句夹带`() {
        assertDenied(
            "SELECT 1; DROP TABLE t",
            McpToolErrors.SQL_BLOCKED,
            "分号多语句应拒",
        )
        assertDenied(
            "SELECT 1;; SELECT 2",
            McpToolErrors.SQL_BLOCKED,
            "双分号夹带应拒",
        )
    }

    @Test
    fun `拒绝_锁读_FOR_UPDATE`() {
        assertDenied("SELECT * FROM t WHERE id = 1 FOR UPDATE", McpToolErrors.SQL_BLOCKED, "FOR UPDATE 应拒")
        assertDenied("SELECT * FROM t FOR SHARE", McpToolErrors.SQL_BLOCKED, "FOR SHARE 应拒")
        assertDenied("SELECT * FROM t LOCK IN SHARE MODE", McpToolErrors.SQL_BLOCKED, "共享锁应拒")
    }

    @Test
    fun `拒绝_SELECT_INTO落盘`() {
        assertDenied("SELECT * FROM t INTO OUTFILE '/tmp/x'", McpToolErrors.SQL_BLOCKED, "INTO OUTFILE 应拒")
        assertDenied("SELECT * FROM t INTO DUMPFILE '/tmp/x'", McpToolErrors.SQL_BLOCKED, "INTO DUMPFILE 应拒")
    }

    @Test
    fun `拒绝_解析失败fail_closed`() {
        assertDenied("SELEC * FORM t", McpToolErrors.SQL_BLOCKED, "语法错误应按写处理")
        assertDenied(")))", McpToolErrors.SQL_BLOCKED, "乱码应拒")
    }

    // ------------------------------------------------------------------
    // 入参校验（INVALID_ARGUMENT）
    // ------------------------------------------------------------------

    @Test
    fun `拒绝_空入参`() {
        assertDenied(null, McpToolErrors.INVALID_ARGUMENT, "null 应拒")
        assertDenied("", McpToolErrors.INVALID_ARGUMENT, "空串应拒")
        assertDenied("   ", McpToolErrors.INVALID_ARGUMENT, "空白应拒")
    }

    @Test
    fun `拒绝_超长输入`() {
        val big = "SELECT " + "x".repeat(McpSelectGuard.MAX_SQL_LENGTH + 1)
        assertDenied(big, McpToolErrors.INVALID_ARGUMENT, "超长应拒")
    }

    // ------------------------------------------------------------------
    // 词边界防误伤
    // ------------------------------------------------------------------

    @Test
    fun `标识符含保留字子串不误杀`() {
        assertAllowed("SELECT last_update, update_time, updatex FROM t")
    }

    @Test
    fun `字符串字面量含保留字不误杀_结构判定兜底`() {
        // 字面量剥离后粗扫（'update' 在引号内）；整条仍是单条 SELECT → 放行
        assertAllowed("SELECT * FROM t WHERE remark LIKE '%update%'")
        assertAllowed("SELECT * FROM t WHERE name = 'drop table x'")
    }

    // ------------------------------------------------------------------
    // T-ExplainChainHardening T-ECH-04：analyze 双守卫防回退 + 与 IDE 链白名单对齐
    // ------------------------------------------------------------------

    @Test
    fun `拒绝_analyze 与 EXPLAIN ANALYZE 不弱化`() {
        // 守卫一（结构判定）：JSqlParser 5.3 把 EXPLAIN ANALYZE 解析为 ExplainStatement（非 Select）
        // → READ_ONLY 拒（实测读数见计划 §八）
        assertDenied(
            "EXPLAIN ANALYZE SELECT * FROM t",
            McpToolErrors.READ_ONLY,
            "EXPLAIN ANALYZE 应拒（analyze 会真实执行被修饰语句）",
        )
        // 守卫二（词面粗扫）：analyze 在 FORBIDDEN_WORDS，出现在查询正文同样拒（fail closed）
        assertDenied(
            "SELECT 1 FROM t WHERE analyze = 1",
            McpToolErrors.READ_ONLY,
            "analyze 词面命中即拒",
        )
        // 括号选项变体解析失败 → SQL_BLOCKED（同样拒；fail closed 两条路径任一都不放行）
        assertDenied(
            "EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM t",
            McpToolErrors.SQL_BLOCKED,
            "EXPLAIN (ANALYZE, …) 变体应拒",
        )
    }

    @Test
    fun `放行_可 EXPLAIN 的非 SELECT 形态（与 IDE 链白名单对齐）`() {
        // T-ECH-01 白名单含 VALUES/TABLE——本守卫结构判定不得比它更严，否则两链行为分叉
        // （计划 §八 待验证前提 2：实测 Values/TableStatement 均为 Select 子类 → 不过严）
        assertAllowed("VALUES (1)")
        assertAllowed("TABLE users")
    }

    @Test
    fun `拒绝_首词前缀形态标识符（与 T-ECH-01 门同向）`() {
        // 词法门（ExplainGate）与结构守卫（本类）对同一形态同向拒绝，两链不分叉
        assertDenied("SELECTED x FROM t", McpToolErrors.SQL_BLOCKED, "SELECTED 应拒")
        assertDenied("SELECT_foo FROM t", McpToolErrors.SQL_BLOCKED, "SELECT_foo 应拒")
    }
}
