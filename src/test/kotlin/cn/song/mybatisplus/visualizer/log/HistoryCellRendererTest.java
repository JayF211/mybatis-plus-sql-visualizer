package cn.song.mybatisplus.visualizer.log;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.junit.Assert.assertFalse;
import org.junit.Assert.assertTrue;
import org.junit.Test;

import javax.swing.JLabel;
import javax.swing.JList;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * 历史面板两级导航渲染器 UI 测试（F-44-3 渲染健壮性）：
 *
 * 直接渲染 [HistoryCellRenderer]（二级 SQL 条目）与 [HistoryFileCellRenderer]（一级文件条目）覆盖：
 *  - 二级条目：空耗时 / 空源码 / 特殊字符 / 超长 SQL 渲染不崩溃（此前测试抓不到的 UI 渲染隐患）
 *  - 一级文件：文件大小格式化边界（0 B / 1.0 KB / 1.0 MB）、条数随文件名展示
 */
public class HistoryCellRendererTest extends BasePlatformTestCase {

    private final HistoryCellRenderer sqlRenderer = new HistoryCellRenderer();
    private final HistoryFileCellRenderer fileRenderer = new HistoryFileCellRenderer();
    /** 真实（非空）列表实例；DefaultListCellRenderer 不支持 null list（生产环境 list 恒非空）。 */
    private final JList<Object> list = new JList<>();

    private String renderSql(LogSqlEntry entry) {
        java.awt.Component c = sqlRenderer.getListCellRendererComponent(list, entry, 0, false, false);
        return ((JLabel) c).getText();
    }

    private String renderFile(File file, int sqlCount) {
        java.awt.Component c = fileRenderer.getListCellRendererComponent(list, new HistoryFileEntry(file, sqlCount), 0, false, false);
        return ((JLabel) c).getText();
    }

    private static LogSqlEntry sqlEntry(String sql, String sourceFile, int sourceLine, Long ms) {
        return new LogSqlEntry(sql, sql, List.<LogSqlParam>of(), 1_700_000_000_000L, sourceFile, sourceLine, ms);
    }

    @Test
    public void test二级条目_空耗时空源码_渲染为HTML不崩溃() {
        String text = renderSql(sqlEntry("SELECT * FROM user", null, 0, null));
        assertTrue("应渲染为 HTML", text.startsWith("<html>"));
        assertTrue("含 SQL 关键字", text.contains("SELECT"));
        // 无源码 → 不应出现源码灰色段；无耗时 → 不应出现 ms
        assertFalse(text.contains("ms"));
    }

    @Test
    public void test二级条目_含源码位置_渲染源码段() {
        String text = renderSql(sqlEntry("SELECT 1", "UserMapper.java", 12, 3L));
        assertTrue("应渲染耗时 3ms", text.contains("3ms"));
        assertTrue("应渲染源码 UserMapper.java:12", text.contains("UserMapper.java:12"));
    }

    @Test
    public void test二级条目_特殊字符SQL被转义() {
        String text = renderSql(sqlEntry("SELECT * FROM t WHERE a < 5 AND b > 3", null, 0, null));
        assertTrue("小于号转义", text.contains("&lt;"));
        assertTrue("大于号转义", text.contains("&gt;"));
        assertFalse("不应残留裸 < 5", text.contains("< 5"));
    }

    @Test
    public void test二级条目_超长SQL渲染不异常() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 200; i++) {
            if (i > 1) sb.append(", ");
            sb.append("c").append(i);
        }
        String longSql = "SELECT " + sb + " FROM t WHERE id = 1";
        String text = renderSql(sqlEntry(longSql, null, 0, null));
        assertTrue("超长 SQL 渲染为 HTML", text.startsWith("<html>"));
    }

    @Test
    public void test一级文件_大小为0字节显示0B() {
        File f = File.createTempFile("hist0", ".sql");
        f.deleteOnExit();
        String text = renderFile(f, 0);
        assertTrue("0 字节应显示 0 B", text.contains("0 B"));
        assertTrue("应显示条数 0 条 SQL", text.contains("0"));
        assertTrue("应展示文件名(加粗)", text.contains(f.getName()));
    }

    @Test
    public void test一级文件_大小恰为1KB显示1点0KB() {
        File f = File.createTempFile("hist1k", ".sql");
        f.deleteOnExit();
        Files.write(f.toPath(), new byte[1024]);
        String text = renderFile(f, 3);
        assertTrue("1024 字节应显示 1.0 KB", text.contains("1.0 KB"));
        assertTrue("应显示 3 条 SQL", text.contains("3"));
    }

    @Test
    public void test一级文件_大小恰为1MB显示1点0MB() {
        File f = File.createTempFile("hist1m", ".sql");
        f.deleteOnExit();
        Files.write(f.toPath(), new byte[1024 * 1024]);
        String text = renderFile(f, 7);
        assertTrue("1MB 应显示 1.0 MB", text.contains("1.0 MB"));
        assertTrue("应显示 7 条 SQL", text.contains("7"));
    }

    @Test
    public void test一级文件_渲染含文件名与条数() {
        File f = File.createTempFile("my-history-file", ".sql");
        f.deleteOnExit();
        Files.write(f.toPath(), new byte[500]);
        String text = renderFile(f, 12);
        assertTrue("文件名(加粗)应展示", text.contains(f.getName()));
        // 条数文案来自 bundle：log.history.sqlCount = {0} 条 SQL
        assertTrue("应展示条数 12", text.contains("12"));
    }
}
