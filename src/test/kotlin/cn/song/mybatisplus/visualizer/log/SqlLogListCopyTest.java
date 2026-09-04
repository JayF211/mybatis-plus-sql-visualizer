package cn.song.mybatisplus.visualizer.log;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import javax.swing.DefaultListModel;
import java.util.List;

/**
 * 日志列表 Ctrl+C 复制纯 SQL（T-M42）：JBList 内置复制链路调 `itemToText(index, element)`
 * （默认返回 cellRenderer 渲染文本 = HTML 源码，用户实测缺陷）——[SqlEntryList] 覆写后
 * 复制输出 `restoredSql + ";"` 纯文本（多选逐条换行拼接、CopyPasteManager 写入由平台
 * 既有链路承担，此处按计划 §4.4 以单条断言 + 拼接语义覆盖）。
 */
public class SqlLogListCopyTest extends BasePlatformTestCase {

    /** itemToText 为 protected 钩子——测试经子类公开访问（生产覆写路径不变）。 */
    private static class ExposedSqlEntryList extends SqlEntryList {
        public ExposedSqlEntryList() {
            super(new DefaultListModel<LogSqlEntry>());
        }

        public String textFor(int index, LogSqlEntry element) {
            return itemToText(index, element);
        }
    }

    private static LogSqlEntry entry(String restoredSql, String rawSql) {
        return new LogSqlEntry(rawSql, restoredSql, List.<LogSqlParam>of());
    }

    @Test
    public void testItemToTextReturnsPureSqlWithTerminator() throws Exception {
        ExposedSqlEntryList list = new ExposedSqlEntryList();
        LogSqlEntry e = entry("SELECT * FROM t WHERE username = 'admin'", "SELECT * FROM t WHERE username = 'admin'");
        String text = list.textFor(0, e);
        assertEquals("应等于还原 SQL + 分号", "SELECT * FROM t WHERE username = 'admin';", text);
        assertFalse("不应含 HTML 片段", text.contains("<html"));
        assertFalse("不应含 span 标签", text.contains("<span"));
        assertFalse("不应含时间戳前缀", text.contains("["));
    }

    @Test
    public void testItemToTextDoesNotDuplicateExistingSemicolon() throws Exception {
        ExposedSqlEntryList list = new ExposedSqlEntryList();
        LogSqlEntry e = entry("SELECT 1;", "SELECT 1;");
        assertEquals("已有分号不重复追加", "SELECT 1;", list.textFor(0, e));
    }

    @Test
    public void testItemToTextEmptySqlNoTerminator() throws Exception {
        ExposedSqlEntryList list = new ExposedSqlEntryList();
        LogSqlEntry e = entry("", "");
        assertEquals("空 SQL 不追加分号", "", list.textFor(0, e));
    }

    @Test
    public void testItemToTextMultiSelectionJoinSemantics() throws Exception {
        // 平台 doCopyToClipboardAction 对选中项逐条调 itemToText 后 StringUtil.join("\n")——
        // 断言各条输出与拼接形态（多选换行拼接链路为平台既有行为）
        ExposedSqlEntryList list = new ExposedSqlEntryList();
        String first = list.textFor(0, entry("SELECT * FROM t WHERE id = 1", "SELECT * FROM t WHERE id = 1"));
        String second = list.textFor(1, entry("UPDATE t SET name = 'a' WHERE id = 2", "UPDATE t SET name = 'a' WHERE id = 2"));
        String joined = String.join("\n", first, second);
        assertEquals("SELECT * FROM t WHERE id = 1;\nUPDATE t SET name = 'a' WHERE id = 2;", joined);
        assertTrue("拼接后仍为纯文本", !joined.contains("<html"));
    }

    @Test
    public void testItemToTextUsesRestoredSqlNotRawSql() throws Exception {
        // 复制内容应为值内联的还原 SQL（与右键「复制」一致），而非含 `?` 的 Preparing 原文
        ExposedSqlEntryList list = new ExposedSqlEntryList();
        LogSqlEntry e = entry(
            "SELECT * FROM t WHERE id = 10",
            "SELECT * FROM t WHERE id = ?"
        );
        assertEquals("SELECT * FROM t WHERE id = 10;", list.textFor(0, e));
    }
}
