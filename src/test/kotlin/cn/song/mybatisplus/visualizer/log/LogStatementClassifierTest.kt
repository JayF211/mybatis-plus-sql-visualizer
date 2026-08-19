package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SQL 语句类型分类器单元测试（P-06，11 用例覆盖全部分支 + 边界）。
 */
class LogStatementClassifierTest {

    @Test
    fun `SELECT 标准大写`() {
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify("SELECT * FROM user"))
    }

    @Test
    fun `SELECT 小写`() {
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify("select * from user"))
    }

    @Test
    fun `SELECT 前导空白`() {
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify("\n  SELECT * FROM user"))
    }

    @Test
    fun `WITH CTE`() {
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify("WITH t AS (SELECT 1) SELECT * FROM t"))
    }

    @Test
    fun `INSERT`() {
        assertEquals(StatementType.INSERT, LogStatementClassifier.classify("INSERT INTO user VALUES (1, 'a')"))
    }

    @Test
    fun `UPDATE`() {
        assertEquals(StatementType.UPDATE, LogStatementClassifier.classify("UPDATE user SET name = 'x'"))
    }

    @Test
    fun `DELETE`() {
        assertEquals(StatementType.DELETE, LogStatementClassifier.classify("DELETE FROM user WHERE id = 1"))
    }

    @Test
    fun `MERGE 归 OTHER`() {
        assertEquals(StatementType.OTHER, LogStatementClassifier.classify("MERGE INTO target USING source"))
    }

    @Test
    fun `TRUNCATE 归 OTHER`() {
        assertEquals(StatementType.OTHER, LogStatementClassifier.classify("TRUNCATE TABLE user"))
    }

    @Test
    fun `空串归 OTHER`() {
        assertEquals(StatementType.OTHER, LogStatementClassifier.classify(""))
    }

    @Test
    fun `仅空白归 OTHER`() {
        assertEquals(StatementType.OTHER, LogStatementClassifier.classify("   "))
    }

    @Test
    fun `按条目分类取 restoredSql`() {
        val entry = LogSqlEntry(
            rawSql = "Preparing: SELECT 1",
            restoredSql = "SELECT * FROM t",
            params = emptyList(),
            timestamp = 0L,
        )
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify(entry))
    }
}
