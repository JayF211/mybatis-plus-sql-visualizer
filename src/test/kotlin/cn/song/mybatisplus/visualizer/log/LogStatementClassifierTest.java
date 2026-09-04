package cn.song.mybatisplus.visualizer.log;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

import java.util.List;

/**
 * SQL 语句类型分类器单元测试（P-06，11 用例覆盖全部分支 + 边界）。
 */
public class LogStatementClassifierTest {

    @Test
    public void SELECT标准大写() throws Exception {
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify("SELECT * FROM user"));
    }

    @Test
    public void SELECT小写() throws Exception {
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify("select * from user"));
    }

    @Test
    public void SELECT前导空白() throws Exception {
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify("\n  SELECT * FROM user"));
    }

    @Test
    public void WITH_CTE() throws Exception {
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify("WITH t AS (SELECT 1) SELECT * FROM t"));
    }

    @Test
    public void INSERT() throws Exception {
        assertEquals(StatementType.INSERT, LogStatementClassifier.classify("INSERT INTO user VALUES (1, 'a')"));
    }

    @Test
    public void UPDATE() throws Exception {
        assertEquals(StatementType.UPDATE, LogStatementClassifier.classify("UPDATE user SET name = 'x'"));
    }

    @Test
    public void DELETE() throws Exception {
        assertEquals(StatementType.DELETE, LogStatementClassifier.classify("DELETE FROM user WHERE id = 1"));
    }

    @Test
    public void MERGE归OTHER() throws Exception {
        assertEquals(StatementType.OTHER, LogStatementClassifier.classify("MERGE INTO target USING source"));
    }

    @Test
    public void TRUNCATE归OTHER() throws Exception {
        assertEquals(StatementType.OTHER, LogStatementClassifier.classify("TRUNCATE TABLE user"));
    }

    @Test
    public void 空串归OTHER() throws Exception {
        assertEquals(StatementType.OTHER, LogStatementClassifier.classify(""));
    }

    @Test
    public void 仅空白归OTHER() throws Exception {
        assertEquals(StatementType.OTHER, LogStatementClassifier.classify("   "));
    }

    @Test
    public void 按条目分类取restoredSql() throws Exception {
        LogSqlEntry entry = new LogSqlEntry(
            "Preparing: SELECT 1",
            "SELECT * FROM t",
            List.<LogSqlParam>of(),
            0L
        );
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify(entry));
    }
}
