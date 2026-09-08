package cn.song.mybatisplus.visualizer.log;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.List;

/**
 * SQL 日志面板「移除重复的sql」真实交互测试（F-44-2 用户反馈两项缺陷）：
 *
 * 场景复现用户操作：
 *  1. 项目运行累积重复 SQL（未勾选去重，重复全展示）——点击勾选 → 已存在条目**立即**去重（原缺陷 1.3）；
 *  2. 去重开启状态下连续捕获同 SQL —— ⨉N 徽章数据**即时**更新，无需等下一条 SQL 入列（原缺陷 1.4）。
 */
public class SqlLogPanelDedupeToggleTest extends BasePlatformTestCase {

    private SqlLogManager manager;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        manager = new SqlLogManager(getProject());
        manager.getEntries().setDedupeEnabled(false); // 模拟用户初始未勾选「移除重复的sql」
    }

    @Override
    protected void tearDown() throws Exception {
        manager.dispose();
        super.tearDown();
    }

    private static LogSqlEntry entry(String sql, long ts) {
        return new LogSqlEntry(sql, sql, List.<LogSqlParam>of(), ts);
    }

    /**
     * 泵空 EDT 队列：manager.notifyUi 用 invokeLater 调度刷新，headless 测试必须
     * dispatchAllInvocationEvents 才能让列表刷新执行（invokeAndWait 空任务不够——已实证）。
     */
    private static void waitForEdt() {
        UIUtil.dispatchAllInvocationEvents();
    }

    @Test
    public void test勾选去重_既有重复条目立即裁剪() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            // 用户未勾选时捕获了 4 条重复（真实场景：同一分页查询重复执行）
            String sql = "SELECT * FROM user WHERE status = 'ACTIVE' LIMIT 10";
            for (int i = 0; i < 4; i++) manager.getEntries().addEntry(entry(sql, 100L * i));
            waitForEdt();
            assertEquals("勾选前 4 条重复全部展示", 4, panel.getVisibleEntryCount());

            // 用户点击勾选「移除重复的sql」
            panel.getDedupeCheckBoxForTest().doClick();
            waitForEdt();

            // 已存在条目立即裁剪：4 → 1
            assertEquals("勾选后立即裁剪为 1 条", 1, panel.getVisibleEntryCount());
            assertEquals("列表只留首条", sql, panel.getVisibleEntries().get(0));
            assertEquals("徽章计数 3", 3, manager.getEntries().dedupeCount(sql));
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test勾选去重_不同SQL互不影响() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            String sqlA = "SELECT * FROM user WHERE id = 1";
            String sqlB = "UPDATE orders SET status = 'PAID' WHERE id = 99";
            for (int i = 0; i < 2; i++) manager.getEntries().addEntry(entry(sqlA, 100L * i));
            for (int i = 0; i < 3; i++) manager.getEntries().addEntry(entry(sqlB, 200L * i));
            waitForEdt();
            assertEquals("勾选前 5 条", 5, panel.getVisibleEntryCount());

            panel.getDedupeCheckBoxForTest().doClick();
            waitForEdt();

            assertEquals("两种 SQL 各留 1 条", 2, panel.getVisibleEntryCount());
            assertEquals("A 徽章 1", 1, manager.getEntries().dedupeCount(sqlA));
            assertEquals("B 徽章 2", 2, manager.getEntries().dedupeCount(sqlB));
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test取消勾选_清空徽章计数() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            String sql = "SELECT COUNT(*) FROM orders";
            for (int i = 0; i < 3; i++) manager.getEntries().addEntry(entry(sql, 100L * i));
            waitForEdt();

            panel.getDedupeCheckBoxForTest().doClick();
            waitForEdt();
            assertEquals("勾选后徽章 2", 2, manager.getEntries().dedupeCount(sql));
            assertEquals("裁剪后 1 条", 1, panel.getVisibleEntryCount());

            // 用户取消勾选 → 徽章清零（语义：关闭 = 本次累计清零），列表内容不变
            panel.getDedupeCheckBoxForTest().doClick();
            waitForEdt();
            assertEquals("取消勾选后徽章清零", 0, manager.getEntries().dedupeCount(sql));
            assertEquals("列表内容不变", 1, panel.getVisibleEntryCount());
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test去重开启下新重复_徽章计数即时更新() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            // 用户先勾选去重，再捕获首条 SQL
            panel.getDedupeCheckBoxForTest().doClick();
            waitForEdt();
            String sql = "SELECT * FROM user WHERE id = 42";
            manager.getEntries().addEntry(entry(sql, 0L));
            waitForEdt();
            assertEquals("首条入列", 1, panel.getVisibleEntryCount());
            assertEquals("初始徽章 0", 0, manager.getEntries().dedupeCount(sql));

            // 同一 SQL 再执行 2 次（去重窗口内）——徽章必须即时 +1，无需等第三条 SQL
            manager.getEntries().addEntry(entry(sql, 100L));
            waitForEdt();
            assertEquals("第 1 次重复后徽章即时更新为 1", 1, manager.getEntries().dedupeCount(sql));

            manager.getEntries().addEntry(entry(sql, 200L));
            waitForEdt();
            assertEquals("第 2 次重复后徽章即时更新为 2", 2, manager.getEntries().dedupeCount(sql));

            // 列表始终只有 1 条
            assertEquals("去重开启下列表恒为 1 条", 1, panel.getVisibleEntryCount());
            assertTrue(panel.getVisibleEntries().get(0) == sql);
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test去重关闭时重复SQL全展示_再开启累计() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            String sql = "SELECT * FROM user WHERE id = 7";
            for (int i = 0; i < 3; i++) manager.getEntries().addEntry(entry(sql, 100L * i));
            waitForEdt();
            assertEquals("去重关闭时 3 条全展示", 3, panel.getVisibleEntryCount());
            assertEquals("关闭时不累计徽章", 0, manager.getEntries().dedupeCount(sql));

            panel.getDedupeCheckBoxForTest().doClick();
            waitForEdt();
            assertEquals("开启后裁剪为 1 条", 1, panel.getVisibleEntryCount());
            assertEquals("徽章累计 2", 2, manager.getEntries().dedupeCount(sql));
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test勾选去重_跨格式空白差副本立即裁剪() throws Exception {
        // T-LogDedupeMpMark：JdbcTemplate 双路日志同一语句产出两副本（named trim / prepared 尾随空白），
        // 原文精确相等永不命中——勾选去重须按规范化键把这类副本一并裁掉
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            String base = "SELECT * FROM ipm_RunPlanProject WHERE (\"deleted\" = 0)";
            manager.getEntries().addEntry(entry(base, 100L));
            manager.getEntries().addEntry(entry(base + "  ", 200L)); // prepared 副本：尾随空白
            waitForEdt();
            assertEquals("勾选前 2 条副本全部展示", 2, panel.getVisibleEntryCount());

            panel.getDedupeCheckBoxForTest().doClick();
            waitForEdt();

            assertEquals("勾选后裁剪为 1 条", 1, panel.getVisibleEntryCount());
            assertEquals(base, panel.getVisibleEntries().get(0));
            assertEquals("徽章计数 1", 1, manager.getEntries().dedupeCount(base));
        } finally {
            panel.dispose();
        }
    }
}
