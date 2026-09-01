package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;
import org.junit.Assert.assertEquals;
import org.junit.Assert.assertNotNull;
import org.junit.Assert.assertTrue;
import org.junit.Test;

import javax.swing.JLabel;
import java.util.List;

/**
 * SQL 日志面板交互 UI 测试（真实点击驱动，headless 断言）：
 *  - 耗时排序按钮点击 → 切换 DURATION_DESC 且列表按耗时降序；再次点击回 NONE（F-44-2 耗时排序用户交互）
 *  - 慢查询计数标签：注入超过阈值的耗时条目 → 标签可见且文案含慢查询条数（O-1 慢查询计数 UI）
 *
 * 注意：[LogDurationParser.parse] 从 rawSql 的 p6spy/druid 格式取耗时，[SlowQueryCounter] 取
 * executionTimeMs 字段；两者在桥层一致，测试里同时设置以保证排序可见与计数正确。
 */
public class SqlLogPanelSortAndSlowQueryTest extends BasePlatformTestCase {

    private SqlLogManager manager;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        manager = new SqlLogManager(getProject());
        manager.getEntries().setDedupeEnabled(false);
    }

    @Override
    protected void tearDown() throws Exception {
        manager.dispose();
        super.tearDown();
    }

    private static void waitForEdt() {
        UIUtil.dispatchAllInvocationEvents();
    }

    /** 构造条目：rawSql 走 p6spy 格式（第二段=耗时）以保证排序解析命中；executionTimeMs 同步设置。 */
    private static LogSqlEntry entry(String sql, long ms, long ts) {
        return new LogSqlEntry(
            "1723456789012|" + ms + "|statement|" + sql,
            sql,
            List.<LogSqlParam>of(),
            ts,
            null,
            0,
            ms
        );
    }

    @Test
    public void test点击耗时排序按钮_列表按耗时降序_再次点击回正序() {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            manager.getEntries().addEntry(entry("SELECT a FROM t", 50L, 100L));
            manager.getEntries().addEntry(entry("SELECT b FROM t", 2000L, 200L));
            manager.getEntries().addEntry(entry("SELECT c FROM t", 300L, 300L));
            waitForEdt();

            // 有耗时信息 → 排序按钮可见，初始为正序（NONE）
            assertTrue("检测到耗时信息，排序按钮应可见", panel.getDurationSortLabel().isVisible());
            assertEquals("初始排序模式应为 NONE", SortMode.NONE, panel.getCurrentSortMode());
            assertEquals("正序：入列顺序 a,b,c", "SELECT a FROM t", panel.getVisibleEntries().get(0));

            // 点击排序按钮 → 降序
            panel.getDurationSortLabel().doClick();
            waitForEdt();
            assertEquals("点击后排序模式 DURATION_DESC", SortMode.DURATION_DESC, panel.getCurrentSortMode());
            // 按耗时降序：2000(b) > 300(c) > 50(a)
            assertEquals("降序首条应为 2000ms 的 b", "SELECT b FROM t", panel.getVisibleEntries().get(0));
            assertEquals("降序次条应为 300ms 的 c", "SELECT c FROM t", panel.getVisibleEntries().get(1));
            assertEquals("降序末条应为 50ms 的 a", "SELECT a FROM t", panel.getVisibleEntries().get(2));

            // 再次点击 → 回正序
            panel.getDurationSortLabel().doClick();
            waitForEdt();
            assertEquals("再次点击回 NONE", SortMode.NONE, panel.getCurrentSortMode());
            assertEquals("回正序首条为 a", "SELECT a FROM t", panel.getVisibleEntries().get(0));
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test无耗时信息_排序按钮隐藏() {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            // 纯 SQL，无 p6spy/druid 格式、executionTimeMs 为 null → 无耗时信息
            manager.getEntries().addEntry(new LogSqlEntry("SELECT 1", "SELECT 1", List.<LogSqlParam>of(), 1L));
            waitForEdt();
            assertEquals("无耗时信息排序按钮应隐藏", false, panel.getDurationSortLabel().isVisible());
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test注入慢查询_工具栏计数标签可见且含条数() {
        long saved = PluginSettings.getInstance().getState().getSlowQueryThresholdMs();
        PluginSettings.getInstance().getState().setSlowQueryThresholdMs(1000);
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            // 一条慢查询（2000ms >= 1000）+ 一条正常（50ms）
            manager.getEntries().addEntry(entry("SELECT slow FROM big_table", 2000L, 100L));
            manager.getEntries().addEntry(entry("SELECT fast FROM t", 50L, 200L));
            waitForEdt();

            JLabel label = panel.getSlowQueryLabelForTest();
            assertNotNull("慢查询标签应存在", label);
            assertEquals("存在慢查询，标签应可见", true, label.isVisible());
            assertTrue("标签文案应含慢查询条数 1", label.getText().contains("1"));
        } finally {
            PluginSettings.getInstance().getState().setSlowQueryThresholdMs(saved);
            panel.dispose();
        }
    }

    @Test
    public void test无慢查询_工具栏计数标签隐藏() {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            manager.getEntries().addEntry(entry("SELECT ok FROM t", 50L, 100L));
            waitForEdt();
            JLabel label = panel.getSlowQueryLabelForTest();
            assertEquals("无慢查询，标签应隐藏", false, label.isVisible());
        } finally {
            panel.dispose();
        }
    }
}
