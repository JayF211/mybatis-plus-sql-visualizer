package cn.song.mybatisplus.visualizer.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.List;

/**
 * SQL 耗时解析器单元测试（P-06，6 用例覆盖 p6spy/druid 格式 + 边界）。
 */
public class LogDurationParserTest {

    private static LogSqlEntry entry(String rawSql, String restoredSql) {
        return new LogSqlEntry(rawSql, restoredSql, List.<LogSqlParam>of(), 0L);
    }

    @Test
    public void p6spy格式解析耗时() throws Exception {
        // p6spy: "1723456789012|123|statement|select ..."
        String raw = "1723456789012|123|statement|select * from user";
        LogSqlEntry e = entry(raw, "select * from user");
        assertEquals(Long.valueOf(123L), LogDurationParser.parse(e));
    }

    @Test
    public void druid格式解析耗时() throws Exception {
        // druid: 含 executeMillis:456
        String raw = "WallStatEx-{...,\"executeMillis\":456,...}";
        LogSqlEntry e = entry(raw, "SELECT * FROM t");
        assertEquals(Long.valueOf(456L), LogDurationParser.parse(e));
    }

    @Test
    public void 无耗时信息返回null() throws Exception {
        String raw = "SELECT * FROM user WHERE id = 1";
        LogSqlEntry e = entry(raw, raw);
        assertNull(LogDurationParser.parse(e));
    }

    @Test
    public void 空串返回null() throws Exception {
        LogSqlEntry e = entry("", "");
        assertNull(LogDurationParser.parse(e));
    }

    @Test
    public void hasDurationInfo_true_当p6spy格式() throws Exception {
        String raw = "1723456789012|456|statement|select 1";
        LogSqlEntry e = entry(raw, "select 1");
        assertTrue(LogDurationParser.hasDurationInfo(e));
    }

    @Test
    public void hasDurationInfo_false_当普通SQL() throws Exception {
        String raw = "SELECT * FROM t";
        LogSqlEntry e = entry(raw, raw);
        assertFalse(LogDurationParser.hasDurationInfo(e));
    }
}
