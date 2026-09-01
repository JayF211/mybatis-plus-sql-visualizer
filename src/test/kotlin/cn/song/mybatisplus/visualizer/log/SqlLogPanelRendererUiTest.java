package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.junit.Assert.assertEquals;
import org.junit.Assert.assertFalse;
import org.junit.Assert.assertNotNull;
import org.junit.Assert.assertTrue;
import org.junit.Test;

import javax.swing.JLabel;
import javax.swing.JList;
import java.util.List;
import java.util.Map;

/**
 * 日志列表单元格渲染器 UI 测试（F-44-2 用户实测缺陷的回归锁）：
 *
 * 直接渲染 [LogEntryListCellRenderer]（internal 具名类，原匿名 object 不可测）覆盖此前测试抓不到的 UI 问题：
 *  - 空耗时 / 空源码条目渲染不崩溃（NPE 风险）
 *  - SQL 含 `<` `>` `&` 等特殊字符被 HTML 转义（否则列表显示原始标签/破版）
 *  - 超长 SQL（>160 摘要截断）渲染不异常
 *  - 耗时着色：慢查询红色 `#D32F2F`、正常灰色 `#9E9E9E`（耗时变色用户实测缺陷）
 *  - 去重 ⨉N 徽章随 dedupeCounts 渲染（去重徽章用户实测缺陷）
 */
public class SqlLogPanelRendererUiTest extends BasePlatformTestCase {

    private final LogEntryListCellRenderer renderer = new LogEntryListCellRenderer();
    /** 真实（非空）列表实例；DefaultListCellRenderer 不支持 null list（生产环境 list 恒非空）。 */
    private final JList<Object> list = new JList<>();

    /** 触发渲染并返回渲染文本。 */
    private String render(LogSqlEntry entry) {
        return render(entry, false);
    }

    private String render(LogSqlEntry entry, boolean selected) {
        java.awt.Component c = renderer.getListCellRendererComponent(list, entry, 0, selected, false);
        return ((JLabel) c).getText();
    }

    private static LogSqlEntry entry(String sql) {
        return entry(sql, null, null, 0);
    }

    private static LogSqlEntry entry(String sql, Long ms, String sourceFile, int sourceLine) {
        return new LogSqlEntry(sql, sql, List.<LogSqlParam>of(), 1_700_000_000_000L, sourceFile, sourceLine, ms);
    }

    @Test
    public void test空耗时空源码条目_渲染不崩溃且为HTML() {
        String text = render(entry("SELECT * FROM user"));
        assertTrue("渲染结果应为 HTML 片段", text.startsWith("<html>"));
        assertTrue("正常 SQL 高亮后含 SELECT", text.contains("SELECT"));
        // 无耗时、无源码：不应出现耗时/源码灰色段，但整体不崩溃
        assertFalse("无耗时不应出现 ms", text.contains("ms"));
    }

    @Test
    public void test含小于大于与号的特殊字符SQL_被HTML转义() {
        // WHERE a < 5 AND b > 3 AND c & d —— 若未转义，Swing 会把 <b 当作标签导致破版
        String text = render(entry("SELECT * FROM t WHERE a < 5 AND b > 3 AND c & d"));
        assertTrue("小于号应转义为 &lt;", text.contains("&lt;"));
        assertTrue("大于号应转义为 &gt;", text.contains("&gt;"));
        assertTrue("与号应转义为 &amp;", text.contains("&amp;"));
        // 转义后不应再出现裸的 `< 5` 这种未转义片段（即原始的 '<' 必须是 &lt;）
        assertFalse("不应残留未转义的小于号后接空格数字", text.contains("< 5"));
    }

    @Test
    public void testSQL含注入式闭合标签_不生成真实标签() {
        // 模拟用户 SQL 里夹带 </span><script> 企图破坏高亮——必须被转义，不得原样渲染
        String text = render(entry("SELECT '</span><script>'"));
        assertTrue("注入的闭合标签中的 < 应转义为 &lt;", text.contains("&lt;/span&gt;"));
        // 关键安全断言：原样未转义的注入序列绝不应出现在渲染文本中（否则会破坏高亮结构）
        assertFalse("不应出现原样 </span><script> 注入", text.contains("</span><script>"));
    }

    @Test
    public void test超长SQL_渲染不异常且含截断摘要() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 200; i++) {
            if (i > 1) sb.append(", ");
            sb.append("col_").append(i);
        }
        String longSql = "SELECT " + sb + " FROM wide_table WHERE id = 1";
        assertTrue("构造的 SQL 超过 160 字符", longSql.length() > 160);
        String text = render(entry(longSql));
        assertTrue("超长 SQL 仍能渲染为 HTML", text.startsWith("<html>"));
        // 摘要截断开头仍是 SELECT
        assertTrue(text.contains("SELECT"));
    }

    @Test
    public void test慢查询耗时着色为红色_正常为灰色() {
        PluginSettings.State pluginSettings = PluginSettings.getInstance().getState();
        long saved = pluginSettings.getSlowQueryThresholdMs();
        try {
            pluginSettings.setSlowQueryThresholdMs(1000);
            String slow = render(entry("SELECT slow", 2000L, null, 0));
            assertTrue("慢查询(2000ms)应渲染红色 #D32F2F", slow.contains("#D32F2F"));
            String normal = render(entry("SELECT ok", 50L, null, 0));
            assertTrue("正常查询(50ms)应渲染灰色 #9E9E9E", normal.contains("#9E9E9E"));
            String medium = render(entry("SELECT mid", 600L, null, 0));
            assertTrue("偏慢(600ms >= 阈值/2)应渲染橙色 #E67E22", medium.contains("#E67E22"));
        } finally {
            pluginSettings.setSlowQueryThresholdMs(saved);
        }
    }

    @Test
    public void test选中态耗时着色用白色_不抢SQL高亮() {
        String text = render(entry("SELECT x", 2000L, null, 0), true);
        // 选中态耗时套白色 #FFFFFF（避免与选中背景/SQL 高亮冲突）
        assertTrue(text.contains("color:#FFFFFF"));
    }

    @Test
    public void test去重徽章随dedupeCounts渲染() {
        String sql = "SELECT * FROM repeated WHERE id = 1";
        renderer.dedupeCounts = Map.of(sql, 4);
        String text = render(entry(sql));
        assertTrue("去重 ⨉4 徽章应出现在渲染文本", text.contains("⨉4"));

        // 计数为 0 或不存在 → 不显示徽章
        renderer.dedupeCounts = Map.of(sql, 0);
        String textZero = render(entry(sql));
        assertFalse("计数 0 不显示徽章", textZero.contains("⨉"));

        renderer.dedupeCounts = Map.of();
        String textNone = render(entry(sql));
        assertFalse("无计数不显示徽章", textNone.contains("⨉"));
    }

    @Test
    public void test渲染返回值非空_组件有效() {
        java.awt.Component c = renderer.getListCellRendererComponent(list, entry("SELECT 1"), 0, false, false);
        assertNotNull("渲染组件不应为 null", c);
        assertTrue("渲染组件应为 JLabel", c instanceof JLabel);
    }
}
