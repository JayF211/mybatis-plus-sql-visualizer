package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.mcp.McpSelectGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EXPLAIN 包装链首关键字词法边界判定单测（T-ExplainChainHardening T-ECH-01 / O-48）。
 *
 * 覆盖计划 §四-1 的五组验收（`SELECTED`/`SELECT_foo`/DML/DDL 拒；`SELECT*`/`SELECT(1)`/
 * `VALUES(1)`/`TABLE users`/`WITH …` 放行），外加「IDE 与 MCP 两链同一判定」的**包含性**用例：
 * 结构白名单接受的输入必过本判定（否则两链对同一文本给出相反结论）。
 *
 * 落点说明（与计划 §三 T-ECH-01「`DialectExplainTest.kt` 新增边界用例」的偏离）：用例独立成
 * 本文件——判定属于 [ExplainGate] 而非方言渲染，混在方言形态用例里会让「渲染逐字节不变」与
 * 「边界判定」两类回归信号互相掩盖。
 */
class ExplainGateTest {

    // ------------------------------------------------------------------
    // 第一组：标识符不得被截断成关键字（DBX starts_with_explain_keyword_boundary 对齐）
    // ------------------------------------------------------------------

    @Test
    fun `拒绝_标识符前缀命中关键字`() {
        assertFalse("SELECTED 是标识符，不得按 SELECT 放行", ExplainGate.isExplainableSource("SELECTED 1"))
        assertFalse("SELECT_foo 是标识符，不得按 SELECT 放行", ExplainGate.isExplainableSource("SELECT_foo FROM t"))
        assertFalse("TABLE_NAME 是标识符，不得按 TABLE 放行", ExplainGate.isExplainableSource("TABLE_NAME"))
        assertFalse("VALUES_X 是标识符，不得按 VALUES 放行", ExplainGate.isExplainableSource("VALUES_X"))
        assertFalse("WITH_CTE 是标识符，不得按 WITH 放行", ExplainGate.isExplainableSource("WITH_CTE"))
    }

    // ------------------------------------------------------------------
    // 第二组：关键字后紧跟非词字符（合法查询形态）
    // ------------------------------------------------------------------

    @Test
    fun `放行_关键字后紧接非词字符`() {
        assertTrue("SELECT* 应放行", ExplainGate.isExplainableSource("SELECT* FROM t"))
        assertTrue("SELECT(1) 应放行", ExplainGate.isExplainableSource("SELECT(1)"))
        assertTrue("SELECT 1 应放行", ExplainGate.isExplainableSource("SELECT 1"))
        assertTrue("VALUES(1) 应放行", ExplainGate.isExplainableSource("VALUES(1)"))
        assertTrue("TABLE users 应放行", ExplainGate.isExplainableSource("TABLE users"))
        assertTrue("WITH CTE 应放行", ExplainGate.isExplainableSource("WITH c AS (SELECT 1) SELECT * FROM c"))
    }

    // ------------------------------------------------------------------
    // 第三组：DML 全拒（本任务的根因面——此前一律被加前缀送进库）
    // ------------------------------------------------------------------

    @Test
    fun `拒绝_DML`() {
        assertFalse("INSERT 应拒", ExplainGate.isExplainableSource("INSERT INTO t(a) VALUES(1)"))
        assertFalse("UPDATE 应拒", ExplainGate.isExplainableSource("UPDATE t SET a = 1 WHERE id = 2"))
        assertFalse("DELETE 应拒", ExplainGate.isExplainableSource("DELETE FROM t WHERE id = 2"))
        assertFalse("小写 delete 应拒", ExplainGate.isExplainableSource("delete from t"))
    }

    // ------------------------------------------------------------------
    // 第四组：DDL / 会话类全拒
    // ------------------------------------------------------------------

    @Test
    fun `拒绝_DDL与会话类`() {
        assertFalse("CREATE 应拒", ExplainGate.isExplainableSource("CREATE TABLE t(id int)"))
        assertFalse("ALTER 应拒", ExplainGate.isExplainableSource("ALTER TABLE t ADD COLUMN c int"))
        assertFalse("DROP 应拒", ExplainGate.isExplainableSource("DROP TABLE t"))
        assertFalse("TRUNCATE 应拒", ExplainGate.isExplainableSource("TRUNCATE TABLE t"))
        assertFalse("SET 应拒", ExplainGate.isExplainableSource("SET autocommit = 0"))
        assertFalse("EXPLAIN 自身不在白名单（幂等判定另走 startsWithExplainKeyword）",
            ExplainGate.isExplainableSource("EXPLAIN SELECT 1"))
    }

    // ------------------------------------------------------------------
    // 第五组：取词边界（空文本 / 前导注释 / 括号化查询表达式）
    // ------------------------------------------------------------------

    @Test
    fun `取词_跳过前导空白与注释与括号`() {
        assertEquals("select", ExplainGate.firstKeyword("  \n\t SELECT 1"))
        assertEquals("select", ExplainGate.firstKeyword("-- 查用户\nSELECT 1"))
        assertEquals("select", ExplainGate.firstKeyword("/* hint */ SELECT 1"))
        assertEquals("select", ExplainGate.firstKeyword("# mysql 行注释\nSELECT 1"))
        assertEquals("select", ExplainGate.firstKeyword("(SELECT 1) UNION (SELECT 2)"))
        assertNull("空文本取不到词", ExplainGate.firstKeyword("   "))
        assertNull("纯注释取不到词", ExplainGate.firstKeyword("-- 只有注释"))
        assertNull("纯符号取不到词", ExplainGate.firstKeyword("((("))
        assertNull("null 容错", ExplainGate.firstKeyword(""))
    }

    @Test
    fun `放行_前导注释与括号化的查询表达式`() {
        // 前导注释必须放行：mapper 预览里 `-- 说明\nSELECT …` 极常见，按「首个非空白字符不是词」
        // 判会误拒；MCP 侧结构白名单本来也接受带前导注释的 SELECT（两链必须同结论）
        assertTrue(ExplainGate.isExplainableSource("-- 查用户\nSELECT * FROM t"))
        assertTrue(ExplainGate.isExplainableSource("/* mapper: user */ SELECT * FROM t"))
        assertTrue(ExplainGate.isExplainableSource("(SELECT 1) UNION (SELECT 2)"))
        assertTrue(ExplainGate.isExplainableSource("(SELECT 1)"))
        // 括号里不是查询 → 仍拒
        assertFalse(ExplainGate.isExplainableSource("(DELETE FROM t)"))
    }

    @Test
    fun `null 与空白输入不抛且拒`() {
        assertFalse(ExplainGate.isExplainableSource(null))
        assertFalse(ExplainGate.isExplainableSource(""))
        assertFalse(ExplainGate.isExplainableSource("   "))
    }

    // ------------------------------------------------------------------
    // 幂等判定（T-ExplainDbl 原语义，迁移到本 object 后逐字不变）
    // ------------------------------------------------------------------

    @Test
    fun `EXPLAIN关键字词边界判定_原语义不变`() {
        assertTrue(ExplainGate.startsWithExplainKeyword("EXPLAIN SELECT 1"))
        assertTrue(ExplainGate.startsWithExplainKeyword("explain select 1"))
        assertTrue("仅 EXPLAIN 七字符", ExplainGate.startsWithExplainKeyword("EXPLAIN"))
        assertFalse("EXPLAINING_TABLE 是标识符", ExplainGate.startsWithExplainKeyword("EXPLAINING_TABLE"))
        assertFalse("EXPLAINED 是标识符", ExplainGate.startsWithExplainKeyword("EXPLAINED"))
        assertFalse(ExplainGate.startsWithExplainKeyword(" SELECT 1"))
    }

    // ------------------------------------------------------------------
    // 提交判定（IDE 链唯一决策点 planExplainSubmit —— 本任务修复的调用点行为）
    // ------------------------------------------------------------------

    @Test
    fun `提交判定_可解释语句按方言模板包装`() {
        val plan = ExplainGate.planExplainSubmit(MySqlDialect, "SELECT * FROM t")
        assertEquals(ExplainGate.SubmitPlan.Submit("EXPLAIN SELECT * FROM t"), plan)
        val pg = ExplainGate.planExplainSubmit(PostgreSQLDialect, "  SELECT 1  ")
        assertEquals(ExplainGate.SubmitPlan.Submit("EXPLAIN (FORMAT JSON, VERBOSE) SELECT 1"), pg)
    }

    /** 本任务的核心判据：DML/DDL/标识符**不再被包装提交**（改前此处会得到 Submit）。 */
    @Test
    fun `提交判定_DML与DDL与标识符一律拒绝且带实际首词`() {
        assertEquals(
            ExplainGate.SubmitPlan.NotExplainable("delete"),
            ExplainGate.planExplainSubmit(MySqlDialect, "DELETE FROM t"),
        )
        assertEquals(
            ExplainGate.SubmitPlan.NotExplainable("create"),
            ExplainGate.planExplainSubmit(MySqlDialect, "CREATE TABLE t(id int)"),
        )
        assertEquals(
            ExplainGate.SubmitPlan.NotExplainable("selected"),
            ExplainGate.planExplainSubmit(MySqlDialect, "SELECTED 1"),
        )
        assertEquals(
            ExplainGate.SubmitPlan.NotExplainable(null),
            ExplainGate.planExplainSubmit(MySqlDialect, "   "),
        )
    }

    @Test
    fun `提交判定_已包装文本原样提交_幂等不回归`() {
        // 二次点击时编辑器里已是包装产物——首词 EXPLAIN 不在白名单，但幂等分支必须放行
        val wrapped = "EXPLAIN SELECT * FROM t"
        assertEquals(ExplainGate.SubmitPlan.Submit(wrapped), ExplainGate.planExplainSubmit(MySqlDialect, wrapped))
        val withSpace = "  explain select 1;  "
        assertEquals(ExplainGate.SubmitPlan.Submit("explain select 1"), ExplainGate.planExplainSubmit(MySqlDialect, withSpace))
    }

    @Test
    fun `提交判定_方言不接入与语句不可解释_两类理由可区分`() {
        // 同一句 SELECT：Oracle 得到「方言不支持」，MySQL 得到可提交——理由不合并
        assertEquals(
            ExplainGate.SubmitPlan.DialectUnsupported("Oracle"),
            ExplainGate.planExplainSubmit(OracleDialect, "SELECT 1"),
        )
        assertEquals(
            ExplainGate.SubmitPlan.NotExplainable("delete"),
            ExplainGate.planExplainSubmit(OracleDialect, "DELETE FROM t"),
        )
    }

    // ------------------------------------------------------------------
    // 两链包含性：结构白名单接受 ⇒ 本判定必接受
    // ------------------------------------------------------------------

    /**
     * 包含性硬门禁：MCP 结构白名单（[McpSelectGuard.checkSingleSelect]）接受的每一条输入，
     * 都必须过 [ExplainGate.isExplainableSource]——否则同一文本在 IDE 链被包成 EXPLAIN、
     * 在 MCP 链被拒（或反之），两链口径分叉。
     *
     * 计划 §八 待验证前提 2（「结构白名单是否对 `VALUES(1)`/`TABLE users` 过严」）**实测证伪**：
     * JSqlParser 5.3 里 `Values` 与 `TableStatement` **都是 `Select` 的子类**
     * （`javap` 实证：`Values extends Select`、`TableStatement extends Select`），故结构白名单
     * 本来就放行这两条——两链白名单**完全对齐**，无分叉面需要登记。
     */
    @Test
    fun `包含性_白名单接受的输入必过首关键字判定`() {
        val corpus = listOf(
            "SELECT * FROM user WHERE id = 1",
            "select name, age from t_user limit 10",
            "WITH c AS (SELECT id FROM a) SELECT * FROM c",
            "SELECT id FROM a UNION SELECT id FROM b ORDER BY id DESC",
            "SELECT * FROM t ORDER BY created_at DESC",
            "SELECT REPLACE(name, 'a', 'b') FROM t",
            "SELECT * FROM t WHERE id IN (SELECT id FROM other)",
            "-- 带前导注释的查询\nSELECT 1",
            "/* 带块注释 */ SELECT 1",
            "(SELECT 1) UNION (SELECT 2)",
            "SELECT * FROM t WHERE deleted_at IS NULL",
            // 非 SELECT 但只读且可 EXPLAIN 的两条——白名单实测同样放行（前提 2 证伪点）
            "VALUES(1)",
            "TABLE users",
        )
        for (sql in corpus) {
            val verdict = McpSelectGuard.checkSingleSelect(sql)
            assertTrue("语料应被结构白名单接受（否则语料本身失效）: $sql", verdict.allowed)
            assertTrue(
                "白名单接受但首关键字判定拒绝 → 两链口径分叉: $sql (firstKeyword=${ExplainGate.firstKeyword(sql)})",
                ExplainGate.isExplainableSource(sql),
            )
        }
    }

    /**
     * 白名单对齐实证（前提 2 证伪的留证用例）：`VALUES(1)`/`TABLE users` 两条链**同判放行**。
     * 若将来 JSqlParser 升级后 `Values`/`TableStatement` 不再是 `Select` 子类，本用例转红，
     * 届时才需要重新评估「MCP 面更严」的分叉登记。
     */
    @Test
    fun `对齐_VALUES与TABLE两链同判放行`() {
        assertTrue(McpSelectGuard.checkSingleSelect("VALUES(1)").allowed)
        assertTrue(McpSelectGuard.checkSingleSelect("TABLE users").allowed)
        assertTrue(ExplainGate.isExplainableSource("VALUES(1)"))
        assertTrue(ExplainGate.isExplainableSource("TABLE users"))
    }
}
