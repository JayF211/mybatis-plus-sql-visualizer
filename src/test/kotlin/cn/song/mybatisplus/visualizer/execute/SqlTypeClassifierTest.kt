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
 * 覆盖全部 9 种方言通用的标准 SQL 关键字。
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
}
