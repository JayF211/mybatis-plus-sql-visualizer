package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier.SqlType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SQL 类型分类器测试（M36）：验证查询类/非查询类语句分类逻辑。
 *
 * 纯字符串解析，无需 Database 插件或 IDE 沙盒。
 * 覆盖各方言通用的标准 SQL 关键字。
 */
class SqlTypeClassifierTest {

    // ======================== 查询类（免确认） ========================

    @Test
    fun testSelectIsQuery() {
        assertTrue("SELECT 应为查询", SqlTypeClassifier.isQuery("SELECT * FROM users"))
    }

    @Test
    fun testSelectWithLowerCase() {
        assertTrue("小写 select 应为查询", SqlTypeClassifier.isQuery("select * from users"))
    }

    @Test
    fun testSelectWithMixedCase() {
        assertTrue("混合大小写 Select 应为查询", SqlTypeClassifier.isQuery("Select * FROM users"))
    }

    @Test
    fun testShowIsQuery() {
        assertTrue("SHOW 应为查询", SqlTypeClassifier.isQuery("SHOW TABLES"))
    }

    @Test
    fun testExplainIsQuery() {
        assertTrue("EXPLAIN 应为查询", SqlTypeClassifier.isQuery("EXPLAIN SELECT * FROM users"))
    }

    @Test
    fun testDescribeIsQuery() {
        assertTrue("DESCRIBE 应为查询", SqlTypeClassifier.isQuery("DESCRIBE users"))
    }

    @Test
    fun testDescIsQuery() {
        assertTrue("DESC 应为查询", SqlTypeClassifier.isQuery("DESC users"))
    }

    @Test
    fun testWithCteIsQuery() {
        assertTrue("WITH (CTE) 应为查询", SqlTypeClassifier.isQuery("WITH cte AS (SELECT 1) SELECT * FROM cte"))
    }

    // ======================== DML（需确认） ========================

    @Test
    fun testInsertIsNotQuery() {
        assertFalse("INSERT 应为非查询", SqlTypeClassifier.isQuery("INSERT INTO users (name) VALUES ('test')"))
    }

    @Test
    fun testUpdateIsNotQuery() {
        assertFalse("UPDATE 应为非查询", SqlTypeClassifier.isQuery("UPDATE users SET name = 'test' WHERE id = 1"))
    }

    @Test
    fun testDeleteIsNotQuery() {
        assertFalse("DELETE 应为非查询", SqlTypeClassifier.isQuery("DELETE FROM users WHERE id = 1"))
    }

    @Test
    fun testMergeIsNotQuery() {
        assertFalse("MERGE 应为非查询", SqlTypeClassifier.isQuery("MERGE INTO target USING source ON target.id = source.id"))
    }

    // ======================== DDL（需确认） ========================

    @Test
    fun testCreateIsNotQuery() {
        assertFalse("CREATE 应为非查询", SqlTypeClassifier.isQuery("CREATE TABLE users (id INT)"))
    }

    @Test
    fun testAlterIsNotQuery() {
        assertFalse("ALTER 应为非查询", SqlTypeClassifier.isQuery("ALTER TABLE users ADD COLUMN email VARCHAR(255)"))
    }

    @Test
    fun testDropIsNotQuery() {
        assertFalse("DROP 应为非查询", SqlTypeClassifier.isQuery("DROP TABLE users"))
    }

    @Test
    fun testTruncateIsNotQuery() {
        assertFalse("TRUNCATE 应为非查询", SqlTypeClassifier.isQuery("TRUNCATE TABLE users"))
    }

    // ======================== 多语句混合 ========================

    @Test
    fun testMultipleSelectsAreQuery() {
        assertTrue("多条 SELECT 全为查询", SqlTypeClassifier.isQuery("SELECT 1; SELECT 2; SELECT 3"))
    }

    @Test
    fun testSelectAndInsertMixedIsNotQuery() {
        assertFalse("SELECT + INSERT 混合 → 需确认", SqlTypeClassifier.isQuery("SELECT 1; INSERT INTO t VALUES (1)"))
    }

    @Test
    fun testInsertAndSelectMixedIsNotQuery() {
        assertFalse("INSERT + SELECT 混合 → 需确认", SqlTypeClassifier.isQuery("INSERT INTO t VALUES (1); SELECT 1"))
    }

    // ======================== 注释处理 ========================

    @Test
    fun testSelectWithLineComment() {
        assertTrue("含 -- 注释的 SELECT 应为查询",
            SqlTypeClassifier.isQuery("-- 查询用户\nSELECT * FROM users"))
    }

    @Test
    fun testSelectWithBlockComment() {
        assertTrue("含 /* */ 注释的 SELECT 应为查询",
            SqlTypeClassifier.isQuery("/* 查询用户 */ SELECT * FROM users"))
    }

    @Test
    fun testInsertWithComment() {
        assertFalse("含注释的 INSERT 应为非查询",
            SqlTypeClassifier.isQuery("-- 新增用户\nINSERT INTO users (name) VALUES ('test')"))
    }

    @Test
    fun testCommentOnlyReturnsFalse() {
        assertFalse("纯注释 → 无有效语句 → 非查询", SqlTypeClassifier.isQuery("-- just a comment"))
    }

    // ======================== 首关键字提取 ========================

    @Test
    fun testGetFirstKeywordSelect() {
        assertEquals("SELECT", SqlTypeClassifier.getFirstKeyword("SELECT * FROM users"))
    }

    @Test
    fun testGetFirstKeywordInsert() {
        assertEquals("INSERT", SqlTypeClassifier.getFirstKeyword("INSERT INTO users VALUES (1)"))
    }

    @Test
    fun testGetFirstKeywordWithWhitespace() {
        assertEquals("UPDATE", SqlTypeClassifier.getFirstKeyword("   UPDATE users SET x=1"))
    }

    @Test
    fun testGetFirstKeywordWithComment() {
        assertEquals("DELETE", SqlTypeClassifier.getFirstKeyword("-- comment\nDELETE FROM users"))
    }

    @Test
    fun testGetFirstKeywordEmpty() {
        assertNull("空 SQL → null", SqlTypeClassifier.getFirstKeyword(""))
    }

    @Test
    fun testGetFirstKeywordBlank() {
        assertNull("空白 SQL → null", SqlTypeClassifier.getFirstKeyword("   \n  "))
    }

    // ======================== 单语句分类 ========================

    @Test
    fun testClassifySelect() {
        assertEquals(SqlType.QUERY, SqlTypeClassifier.classify("SELECT * FROM users"))
    }

    @Test
    fun testClassifyInsert() {
        assertEquals(SqlType.DML, SqlTypeClassifier.classify("INSERT INTO users VALUES (1)"))
    }

    @Test
    fun testClassifyUpdate() {
        assertEquals(SqlType.DML, SqlTypeClassifier.classify("UPDATE users SET name = 'x'"))
    }

    @Test
    fun testClassifyDelete() {
        assertEquals(SqlType.DML, SqlTypeClassifier.classify("DELETE FROM users"))
    }

    @Test
    fun testClassifyCreate() {
        assertEquals(SqlType.DDL, SqlTypeClassifier.classify("CREATE TABLE t (id INT)"))
    }

    @Test
    fun testClassifyUnknown() {
        assertEquals(SqlType.UNKNOWN, SqlTypeClassifier.classify("GRANT SELECT ON users TO role"))
    }

    // ======================== 语句分割 ========================

    @Test
    fun testSplitStatements() {
        val statements = SqlTypeClassifier.splitStatements("SELECT 1; SELECT 2; SELECT 3")
        assertEquals(3, statements.size)
    }

    @Test
    fun testSplitStatementsWithEmpty() {
        val statements = SqlTypeClassifier.splitStatements("SELECT 1;; SELECT 2")
        assertEquals(2, statements.size)
    }

    // ======================== 注释剥离 ========================

    @Test
    fun testStripLineComment() {
        assertEquals("SELECT 1 ", SqlTypeClassifier.stripComments("SELECT 1 -- comment"))
    }

    @Test
    fun testStripBlockComment() {
        assertEquals("SELECT 1 ", SqlTypeClassifier.stripComments("SELECT 1 /* comment */"))
    }

    @Test
    fun testStripMixedComments() {
        // 行注释去除注释内容但保留换行符；块注释整体去除
        assertEquals("\nSELECT 1 ",
            SqlTypeClassifier.stripComments("-- line comment\nSELECT 1 /* block */"))
    }

    // ======================== 方言兼容性 ========================

    @Test
    fun testOracleMergeIsNotQuery() {
        assertFalse("Oracle MERGE 应为非查询",
            SqlTypeClassifier.isQuery("MERGE INTO target t USING source s ON (t.id = s.id) WHEN MATCHED THEN UPDATE SET t.name = s.name"))
    }

    @Test
    fun testSqlServerExecIsNotQuery() {
        // EXEC 不在已知关键字列表中 → UNKNOWN → 非查询（需确认）
        assertFalse("SQL Server EXEC 应为非查询",
            SqlTypeClassifier.isQuery("EXEC sp_executesql N'SELECT 1'"))
    }

    @Test
    fun testPostgresSelectIsQuery() {
        assertTrue("PostgreSQL SELECT 应为查询",
            SqlTypeClassifier.isQuery("SELECT * FROM users LIMIT 10 OFFSET 5"))
    }

    @Test
    fun testMysqlSelectIsQuery() {
        assertTrue("MySQL SELECT 应为查询",
            SqlTypeClassifier.isQuery("SELECT * FROM users LIMIT 5, 10"))
    }

    // ======================== 只读二级证明（T-TxnReadOnlyProof N-21） ========================

    // ---- 盲区 1：可写 CTE ----

    @Test
    fun testWritableCteDeleteIsNotQuery() {
        assertFalse("可写 CTE（WITH 内 DELETE）应需确认——首关键字 WITH 曾免确认直通真执行 DELETE",
            SqlTypeClassifier.isQuery("WITH del AS (DELETE FROM users WHERE id = 1) SELECT * FROM del"))
    }

    @Test
    fun testWritableCteUpdateInTrailingStatementIsNotQuery() {
        assertFalse("尾随主语句为 UPDATE 的 CTE 应需确认",
            SqlTypeClassifier.isQuery("WITH cte AS (SELECT 1) UPDATE users SET name = 'x'"))
    }

    @Test
    fun testWritableCteInsertKeywordIsNotQuery() {
        assertFalse("CTE 体内 INSERT 关键字应需确认",
            SqlTypeClassifier.isQuery("WITH ins AS (INSERT INTO t VALUES (1) RETURNING id) SELECT * FROM ins"))
    }

    @Test
    fun testPlainCteRemainsQuery() {
        assertTrue("普通 CTE 查询仍免确认",
            SqlTypeClassifier.isQuery("WITH cte AS (SELECT id FROM users) SELECT * FROM cte"))
    }

    @Test
    fun testCteColumnNamedDeletedAtRemainsQuery() {
        // 字边界匹配：列名 deleted_at 是一个词，不误判为 DELETE
        assertTrue("列名 deleted_at 的普通 CTE 查询仍免确认",
            SqlTypeClassifier.isQuery("WITH recent AS (SELECT deleted_at FROM logs) SELECT * FROM recent"))
    }

    @Test
    fun testCteKeywordInStringLiteralRemainsQuery() {
        // 字符串字面量屏蔽：常量值 'delete from t' 不参与词法扫描
        assertTrue("字符串字面量内写关键字不误判",
            SqlTypeClassifier.isQuery("WITH c AS (SELECT 'delete from t' AS v) SELECT * FROM c"))
    }

    // ---- 盲区 2：EXPLAIN ANALYZE ----

    @Test
    fun testExplainAnalyzeDeleteIsNotQuery() {
        assertFalse("EXPLAIN ANALYZE DELETE 应需确认——PG 语义 ANALYZE 真实执行被修饰语句",
            SqlTypeClassifier.isQuery("EXPLAIN ANALYZE DELETE FROM users WHERE id = 1"))
    }

    @Test
    fun testExplainAnalyzeUpdateIsNotQuery() {
        assertFalse("EXPLAIN ANALYZE UPDATE 应需确认",
            SqlTypeClassifier.isQuery("EXPLAIN ANALYZE UPDATE users SET name = 'x'"))
    }

    @Test
    fun testExplainParenthesizedAnalyzeInsertIsNotQuery() {
        assertFalse("EXPLAIN (ANALYZE, BUFFERS) INSERT 变体应需确认",
            SqlTypeClassifier.isQuery("EXPLAIN (ANALYZE, BUFFERS) INSERT INTO t VALUES (1)"))
    }

    @Test
    fun testPlainExplainRemainsQuery() {
        assertTrue("普通 EXPLAIN SELECT 仍免确认",
            SqlTypeClassifier.isQuery("EXPLAIN SELECT * FROM users"))
    }

    @Test
    fun testExplainAnalyzeSelectRemainsQuery() {
        // EXPLAIN ANALYZE SELECT 只读执行查询——豁免维持（SELECT 只读已由二级证明独立保证）
        assertTrue("EXPLAIN ANALYZE SELECT 仍免确认",
            SqlTypeClassifier.isQuery("EXPLAIN ANALYZE SELECT * FROM users"))
    }

    // ---- 盲区 3：SELECT INTO ----

    @Test
    fun testSelectIntoOutfileIsNotQuery() {
        assertFalse("SELECT INTO OUTFILE 应需确认——MySQL 写服务端文件",
            SqlTypeClassifier.isQuery("SELECT * FROM users INTO OUTFILE '/tmp/users.csv'"))
    }

    @Test
    fun testSelectIntoDumpfileIsNotQuery() {
        assertFalse("SELECT INTO DUMPFILE 应需确认",
            SqlTypeClassifier.isQuery("SELECT * FROM users INTO DUMPFILE '/tmp/binary'"))
    }

    @Test
    fun testSelectIntoNewTableIsNotQuery() {
        assertFalse("PG SELECT INTO new_table 建表形态应需确认",
            SqlTypeClassifier.isQuery("SELECT * INTO users_backup FROM users"))
    }

    @Test
    fun testSelectIntoSessionVarRemainsQuery() {
        assertTrue("SELECT INTO @v 会话变量维持豁免",
            SqlTypeClassifier.isQuery("SELECT COUNT(*) INTO @total FROM users"))
    }

    @Test
    fun testSelectTrailingIntoOutfileIsNotQuery() {
        // MySQL 尾随形态：FROM … INTO OUTFILE
        assertFalse("MySQL 尾随 INTO OUTFILE 形态应需确认",
            SqlTypeClassifier.isQuery("SELECT * FROM users INTO OUTFILE '/tmp/x' FIELDS TERMINATED BY ','"))
    }

    // ---- 防回归：既有豁免路径逐字节不变 + 混合/UNKNOWN 既有语义 ----

    @Test
    fun testReadOnlyProofPlainSelectClassifyStaysQuery() {
        assertEquals("普通 SELECT 分类仍为 QUERY（豁免收窄只在盲区生效）",
            SqlType.QUERY, SqlTypeClassifier.classify("SELECT * FROM users WHERE status = 1"))
    }

    @Test
    fun testWritableCteClassifiesUnknownNotDml() {
        // 收窄落点：可写 CTE 改判 UNKNOWN（fail-closed 转确认），不冒充 DML
        assertEquals("可写 CTE 改判 UNKNOWN",
            SqlType.UNKNOWN, SqlTypeClassifier.classify("WITH d AS (DELETE FROM t) SELECT * FROM d"))
    }

    @Test
    fun testMixedSelectAndWritableCteIsNotQuery() {
        assertFalse("普通 SELECT + 可写 CTE 混合 → 任一非 QUERY 即需确认",
            SqlTypeClassifier.isQuery("SELECT 1; WITH d AS (DELETE FROM t) SELECT * FROM d"))
    }

    @Test
    fun testCommentKeywordNotMisjudged() {
        // 注释内写关键字不参与判定（拆句去注释既有能力，引 T-SemicolonGuard 后能力）
        assertTrue("注释内写关键字的普通 CTE 查询仍免确认",
            SqlTypeClassifier.isQuery("/* update rows */ WITH c AS (SELECT 1) SELECT * FROM c"))
    }

    @Test
    fun testTsqlControlFlowBatchFallsToUnknownNeedsConfirm() {
        // T-TsqlBatchSplit T-TB-02：整批 T-SQL 控制流（IF/BEGIN 起头）分类落 UNKNOWN → 需确认
        // （fail-closed 既有行为钉死——拆分器批首合并后控制流批以整批进确认流）
        assertEquals("裸 IF…ELSE 批判 UNKNOWN",
            SqlType.UNKNOWN,
            SqlTypeClassifier.classify("IF @x = 1 UPDATE t SET a = 1; ELSE INSERT INTO t VALUES (1)"))
        assertFalse("整批 T-SQL 控制流需确认",
            SqlTypeClassifier.isQuery("IF EXISTS (SELECT 1 FROM t) BEGIN DELETE FROM t; END"))
    }

    // ======================== DELIMITER 脚本分类（T-DA-03 / O-39 消费面②） ========================

    @Test
    fun testDelimiterProcedureScriptClassifiesDdlNotFragments() {
        // T-DA-02 前：`DELIMITER $$` 自成一类（UNKNOWN）+ 过程体碎成多段；现过程体整段判 DDL
        val script = "DELIMITER $$\nCREATE PROCEDURE p()\nBEGIN\n  SELECT 1;\n  SELECT 2;\nEND$$\n" +
            "DELIMITER ;\nSELECT 3;"
        val statements = SqlTypeClassifier.splitStatements(script)
        assertEquals("指令行不产出、界符不残留——两条语句", 2, statements.size)
        assertEquals("过程体整体判 DDL", SqlType.DDL, SqlTypeClassifier.classify(statements[0]))
        assertEquals("复位后的普通语句照常分类", SqlType.QUERY, SqlTypeClassifier.classify(statements[1]))
        assertEquals("首关键字不再是 DELIMITER", "CREATE", SqlTypeClassifier.getFirstKeyword(script))
        assertFalse("含 DDL 的脚本整体需确认（不因指令行误判免确认）", SqlTypeClassifier.isQuery(script))
    }
}
