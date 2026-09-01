package cn.song.mybatisplus.visualizer.log;

import org.junit.Assert.assertEquals;
import org.junit.Assert.assertFalse;
import org.junit.Assert.assertNull;
import org.junit.Assert.assertTrue;
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
    public void p6spy格式解析耗时() {
        // p6spy: "1723456789012|123|statement|select ..."
        String raw = "1723456789012|123|statement|select * from user";
        LogSqlEntry e = entry(raw, "select * from user");
        assertEquals(123L, LogDurationParser.parse(e));
    }

    @Test
    public void druid格式解析耗时() {
        // druid: 含 executeMillis:456
        String raw = "WallStatEx-{...,\"executeMillis\":456,...}";
        LogSqlEntry e = entry(raw, "SELECT * FROM t");
        assertEquals(456L, LogDurationParser.parse(e));
    }

    @Test
    public void 无耗时信息返回null() {
        String raw = "SELECT * FROM user WHERE id = 1";
        LogSqlEntry e = entry(raw, raw);
        assertNull(LogDurationParser.parse(e));
    }

    @Test
    public void 空串返回null() {
        LogSqlEntry e = entry("", "");
        assertNull(LogDurationParser.parse(e));
    }

    @Test
    public void hasDurationInfo_true_当p6spy格式() {
        String raw = "1723456789012|456|statement|select 1";
        LogSqlEntry e = entry(raw, "select 1");
        assertTrue(LogDurationParser.hasDurationInfo(e));
    }

    @Test
    public void hasDurationInfo_false_当普通SQL() {
        String raw = "SELECT * FROM t";
        LogSqlEntry e = entry(raw, raw);
        assertFalse(LogDurationParser.hasDurationInfo(e));
    }
}
