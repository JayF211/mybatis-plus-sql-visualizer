package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.SimpleColoredComponent;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import javax.swing.JList;
import java.awt.Color;
import java.util.List;
import java.util.Map;

/**
 * 日志列表单元格渲染器 UI 测试（F-44-2/T-LogListFreeze-04 回归锁）：
 *
 * 直接渲染 {@link LogEntryListCellRenderer} 覆盖此前测试抓不到的 UI 问题。
 * 片段通道（双 {@link SimpleColoredComponent}，T-TwoLine 两行结构）下锁定：
 *  - 通道根治断言：渲染行为 SimpleColoredComponent 系（JLabel-HTML 通道即 BasicHTML 解析 = Freeze 根因，不允许回潮）
 *  - 两行结构：元信息行在上（时间戳/耗时/来源/徽章）、SQL 高亮行在下，SQL 不混入元信息行
 *  - 超长 SQL（>160 摘要截断）渲染不异常
 *  - 耗时着色分档：慢查询红 0xD32F2F、偏慢橙 0xE67E22、正常灰 0x9E9E9E、选中白 0xFFFFFF（原 HTML 配色语义平移）
 *  - 去重 ⨉N 徽章随 dedupeCounts 渲染
 */
public class SqlLogPanelRendererUiTest extends BasePlatformTestCase {

    private final LogEntryListCellRenderer renderer = new LogEntryListCellRenderer();
    /** 真实（非空）列表实例；渲染器取 selectionBackground 等需 list 非空（生产环境 list 恒非空）。 */
    private final JList<Object> list = new JList<>();

    /** 触发渲染并返回渲染组件（片段通道：元信息行/SQL 行经 metaLine/sqlLine 断言）。 */
    private LogEntryListCellRenderer render(LogSqlEntry entry) {
        return render(entry, false);
    }

    private LogEntryListCellRenderer render(LogSqlEntry entry, boolean selected) {
        java.awt.Component c = renderer.getListCellRendererComponent(list, entry, 0, selected, false);
        return (LogEntryListCellRenderer) c;
    }

    private String joinedText(SimpleColoredComponent component) {
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<String> it = component.iterator();
        while (it.hasNext()) {
            sb.append(it.next());
        }
        return sb.toString();
    }

    private static LogSqlEntry entry(String sql) {
        return entry(sql, null, null, 0);
    }

    private static LogSqlEntry entry(String sql, Long ms, String sourceFile, int sourceLine) {
        return new LogSqlEntry(sql, sql, List.<LogSqlParam>of(), 1_700_000_000_000L, sourceFile, sourceLine, ms);
    }

    @Test
    public void test渲染组件为片段通道_非HTML标签() throws Exception {
        LogEntryListCellRenderer r = render(entry("SELECT * FROM user"));
        assertTrue("元信息行应为 SimpleColoredComponent（JLabel-HTML 通道不允许回潮）",
                r.metaLine instanceof SimpleColoredComponent);
        assertTrue("SQL 行应含 SELECT", joinedText(r.sqlLine).contains("SELECT"));
        assertFalse("渲染文本不应为 HTML 包裹", joinedText(r.sqlLine).startsWith("<html>"));
        assertFalse("无耗时不应出现 ms", joinedText(r.metaLine).contains("ms"));
    }

    @Test
    public void test两行结构_元信息在上SQL在下() throws Exception {
        String sql = "SELECT * FROM repeated WHERE id = 1";
        renderer.dedupeCounts = Map.of(LogSqlModelsKt.sqlFingerprint(sql), 2);
        LogEntryListCellRenderer r = render(entry(sql, null, "src/main/java/A.java", 42));
        String meta = joinedText(r.metaLine);
        assertTrue("元信息行含来源 [文件:行]", meta.contains("[A.java:42]"));
        assertTrue("元信息行含去重徽章", meta.contains("⨉2"));
        assertFalse("元信息行不应混入 SQL", meta.contains("SELECT"));
        assertTrue("SQL 行完整保留语句（含分号结束符）",
                joinedText(r.sqlLine).contains("SELECT * FROM repeated WHERE id = 1;"));
    }

    @Test
    public void test特殊字符SQL_原文保真() throws Exception {
        // 片段通道无 HTML 解析面——< > & 原样绘制即安全（原通道需转义，此处断言不转义不破版）
        LogEntryListCellRenderer r = render(entry("SELECT * FROM t WHERE a < 5 AND b > 3 AND c & d"));
        String sqlText = joinedText(r.sqlLine);
        assertTrue("小于号原文保留", sqlText.contains("< 5"));
        assertTrue("大于号原文保留", sqlText.contains("> 3"));
        assertTrue("与号原文保留", sqlText.contains("& d"));
    }

    @Test
    public void testSQL含标签样文本_不构成HTML结构() throws Exception {
        LogEntryListCellRenderer r = render(entry("SELECT '</span><script>'"));
        assertTrue("标签样文本原样保留（片段通道无注入面）",
                joinedText(r.sqlLine).contains("</span><script>"));
    }

    @Test
    public void test超长SQL_渲染不异常且含截断摘要() throws Exception {
        StringBuilder sb = new StringBuilder("SELECT ");
        for (int i = 1; i <= 200; i++) {
            if (i > 1) sb.append(", ");
            sb.append("col_").append(i);
        }
        sb.append(" FROM wide_table WHERE id = 1");
        String longSql = sb.toString();
        assertTrue("构造的 SQL 超过 160 字符", longSql.length() > 160);
        LogEntryListCellRenderer r = render(entry(longSql));
        String sqlText = joinedText(r.sqlLine);
        assertTrue("摘要截断开头仍是 SELECT", sqlText.contains("SELECT"));
        assertTrue("超长 SQL 摘要含省略号", sqlText.contains("…"));
    }

    @Test
    public void test慢查询耗时着色分档_红橙灰() throws Exception {
        PluginSettings.State pluginSettings = PluginSettings.getInstance().getState();
        long saved = pluginSettings.getSlowQueryThresholdMs();
        try {
            pluginSettings.setSlowQueryThresholdMs(1000);
            assertEquals("慢查询(2000ms)应红色", new Color(0xD32F2F),
                    SqlLogPanelKt.formatDurationFragment(2000L, false).getSecond().getFgColor());
            assertEquals("偏慢(600ms >= 阈值/2)应橙色", new Color(0xE67E22),
                    SqlLogPanelKt.formatDurationFragment(600L, false).getSecond().getFgColor());
            // 分档语义：ms==null 才灰色；正常（< 阈值/2）为绿（原通道该断言蹭到时间戳灰，片段直读属性后修正）
            assertEquals("正常(50ms)应绿色", new Color(0x4CAF50),
                    SqlLogPanelKt.formatDurationFragment(50L, false).getSecond().getFgColor());
        } finally {
            pluginSettings.setSlowQueryThresholdMs(saved);
        }
    }

    @Test
    public void test选中态耗时着色用白色_不抢SQL高亮() throws Exception {
        kotlin.Pair<String, com.intellij.ui.SimpleTextAttributes> f = SqlLogPanelKt.formatDurationFragment(2000L, true);
        assertEquals("选中态耗时套白色（避免与选中背景/SQL 高亮冲突）", new Color(0xFFFFFF), f.getSecond().getFgColor());
    }

    @Test
    public void test去重徽章随dedupeCounts渲染() throws Exception {
        String sql = "SELECT * FROM repeated WHERE id = 1";
        renderer.dedupeCounts = Map.of(LogSqlModelsKt.sqlFingerprint(sql), 4);
        assertTrue("去重 ⨉4 徽章应渲染", joinedText(render(entry(sql)).metaLine).contains("⨉4"));

        renderer.dedupeCounts = Map.of(LogSqlModelsKt.sqlFingerprint(sql), 0);
        assertFalse("计数 0 不显示徽章", joinedText(render(entry(sql)).metaLine).contains("⨉"));

        renderer.dedupeCounts = Map.of();
        assertFalse("无计数不显示徽章", joinedText(render(entry(sql)).metaLine).contains("⨉"));
    }

    @Test
    public void test渲染返回值非空_组件有效() throws Exception {
        java.awt.Component c = renderer.getListCellRendererComponent(list, entry("SELECT 1"), 0, false, false);
        assertNotNull("渲染组件不应为 null", c);
        assertTrue("渲染组件应为渲染器自身（含双行组件面板）", c instanceof java.awt.Component);
    }
}
