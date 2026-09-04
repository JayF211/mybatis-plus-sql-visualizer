package cn.song.mybatisplus.visualizer.log;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.File;
import java.io.FileWriter;
import java.util.List;
import java.util.Set;

/**
 * SQL 日志面板布局 / 兼容性 UI 测试（F-44-5 全插件 UI 测试）。
 */
public class SqlLogPanelLayoutTest extends BasePlatformTestCase {

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

    @Test
    public void testToolbarkeycomponentspresentdefaultvisibilitycorrect() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            waitForEdt();
            assertNotNull("SQL类型标签应存在", panel.getTypeFilterLabelForTest());
            assertNotNull("SQL类型按钮应存在", panel.getTypeFilterButtonForTest());
            assertNotNull("去重勾选框应存在", panel.getDedupeCheckBoxForTest());
            assertNotNull("搜索条应存在", panel.getSearchFieldForTest());
            assertTrue("工具栏动作组应含 ≥5 个动作（捕捉/清空/设置/搜索/帮助）",
                    panel.toolbarGroup.getChildActionsOrStubs().length >= 5);

            assertEquals("默认无耗时信息，排序按钮应隐藏", false, panel.getDurationSortLabel().isVisible());
            assertEquals("搜索条默认隐藏", false, panel.getSearchFieldForTest().isVisible());
            assertEquals("默认无慢查询，计数标签应隐藏/为空", false,
                    panel.getSlowQueryLabelForTest() == null ? false : panel.getSlowQueryLabelForTest().isVisible());
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void testInjectdurationinfosortsshows() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            manager.getEntries().addEntry(new LogSqlEntry(
                    "1723456789012|1500|statement|SELECT * FROM t",
                    "SELECT * FROM t",
                    List.of(),
                    1L,
                    null,
                    0,
                    1500L));
            waitForEdt();
            assertEquals("检测到耗时信息，排序按钮应显示", true, panel.getDurationSortLabel().isVisible());
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void testNarrowsidebarwraplayouttypefilternotclipped() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            panel.applyTypeFilterForTest(Set.of(StatementType.SELECT, StatementType.UPDATE));
            waitForEdt();

            int narrowWidth = 240;
            int tallHeight = 260;
            JPanel holder = new JPanel(new BorderLayout());
            holder.setSize(new Dimension(narrowWidth, tallHeight));
            JPanel left = new JPanel(new WrapLayout());
            left.add(panel.getTypeFilterLabelForTest());
            left.add(panel.getTypeFilterButtonForTest());
            if (panel.getDurationSortLabel().isVisible()) left.add(panel.getDurationSortLabel());
            left.add(new JButton("W".repeat(50)));
            holder.add(left, BorderLayout.CENTER);
            holder.doLayout();
            waitForEdt();

            JButton btn = panel.getTypeFilterButtonForTest();
            assertTrue("SQL类型按钮应可见", btn.isVisible());
            assertTrue("SQL类型按钮 y 不应为负（未上移裁切）", btn.getY() >= 0);
            assertTrue("SQL类型按钮底部不应超出容器高度（未被单行高度裁掉）",
                    btn.getY() + btn.getHeight() <= tallHeight);
            assertTrue("折行后容器高度应大于单行按钮高度", left.getHeight() > btn.getHeight());
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void testSwitchhistorytabhistorypanelassembles() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            waitForEdt();
            File dir = new File(getProject().getBasePath() + File.separator + ".idea"
                    + File.separator + SqlLogAutoSaver.DATA_DIR_NAME
                    + File.separator + "sql-history");
            dir.mkdirs();
            File histFile = new File(dir, "sql-history-2026-08-31.sql");
            try (FileWriter w = new FileWriter(histFile)) {
                w.write("-- source: A.java:1 | time: 1ms | 2026-08-31 17:30:00.000 --\nSELECT 1\n\n");
            }
            panel.dispose();
            SqlLogPanel panel2 = new SqlLogPanel(getProject(), manager);
            waitForEdt();
            int tabCount = (panel2.getComponent(0) instanceof JTabbedPane)
                    ? ((JTabbedPane) panel2.getComponent(0)).getTabCount() : -1;
            assertTrue("含历史/转换等 tab，tab 数应 ≥ 3", tabCount >= 3);
            panel2.dispose();
        } finally {
            panel.dispose();
        }
    }
}
