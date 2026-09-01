package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.log.SqlLogPanelKt;
import org.junit.Assert.assertEquals;
import org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * 显示层 SQL 末尾分号规范化单测（withTerminator，复制/预览共用）。
 */
public class WithTerminatorTest {

    @Test
    public void emptyStringStaysEmpty() {
        assertEquals("", SqlLogPanelKt.withTerminator(""));
    }

    @Test
    public void plainSqlAppendsSemicolon() {
        assertEquals("SELECT 1;", SqlLogPanelKt.withTerminator("SELECT 1"));
    }

    @Test
    public void trailingSemicolonNotDuplicated() {
        assertEquals("SELECT 1;", SqlLogPanelKt.withTerminator("SELECT 1;"));
    }

    @Test
    public void spaceThenSemicolonNotDuplicated() {
        assertEquals("SELECT 1 ;", SqlLogPanelKt.withTerminator("SELECT 1 ;"));
    }

    @Test
    public void multilineWithoutSemicolonAppendsAtEnd() {
        String sql = "SELECT a\nFROM t\nWHERE id = 1";
        assertEquals(sql + ";", SqlLogPanelKt.withTerminator(sql));
        assertTrue("结尾为分号", SqlLogPanelKt.withTerminator(sql).endsWith(";"));
    }

    @Test
    public void multilineWithSemicolonNotDuplicated() {
        String sql = "SELECT a\nFROM t;";
        assertEquals(sql, SqlLogPanelKt.withTerminator(sql));
    }
}
