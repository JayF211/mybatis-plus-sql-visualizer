package cn.song.mybatisplus.visualizer.log;

import com.intellij.openapi.project.Project;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.List;

/**
 * 实时 SQL 日志「跳转到源码」真实场景测试（F-44-5 全插件 UI 测试）：
 *
 * 用户反馈「sql 日志…缺代码位置导致无法跳转到代码位置」——
 * 实时日志来自控制台输出（p6spy/MyBatis/Hibernate），本身不含 file:line 调用位置，
 * 故 [SqlLogPanel] 的跳转实为「按表名定位对应实体 Wrapper」([SqlTableExtractor] + [WrapperSearchService])。
 *
 * 本测试锁定 [SqlLogPanel.resolveLiveJump] 的确定性分支（规避 headless 下 Messages 弹窗与后台搜索不确定性）：
 *  - SQL 无表名（如 `SELECT 1`）→ NoTable（无法定位任何实体，必然无法跳转）；
 *  - SQL 有表名但项目内无对应实体 → WrapperSearchService 返回 None（无候选，无法跳转到代码位置）；
 *  - SQL 有表名 → Proceed(tableName)（交由后台搜索，命中则跳转实体）。
 *
 * 同时以断言说明架构性缺口：实时日志条目不携带 sourceFile/sourceLine，无法做精确行跳转。
 */
public class SqlLogPanelJumpToSourceTest extends BasePlatformTestCase {

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

    private static LogSqlEntry entry(String restoredSql) {
        return new LogSqlEntry(restoredSql, restoredSql, List.<LogSqlParam>of(), 1L);
    }

    @Test
    public void testSQL无表名_无法跳转_NoTable() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            // `SELECT 1` 无 FROM/表名 → 无法定位任何实体
            LogSqlEntry e = entry("SELECT 1");
            LiveJumpResolution resolution = panel.resolveLiveJump(e);
            assertTrue("无表名 SQL 应判定为 NoTable（无法跳转）", resolution instanceof LjrNoTable);
            // 架构性缺口：实时日志条目本身不带源码位置，无法做精确行跳转
            assertNull("实时日志条目来源不含 sourceFile", e.sourceFile());
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void testSQL有表名_但项目无对应实体_搜索返回None_无法跳转到代码位置() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            String tableName = "some_random_table_xyz";
            LogSqlEntry e = entry("SELECT * FROM " + tableName + " WHERE id = 1");
            LiveJumpResolution resolution = panel.resolveLiveJump(e);
            assertTrue("有表名应进入 Proceed", resolution instanceof LjrProceed);
            assertEquals(tableName, ((LjrProceed) resolution).getTableName());

            // 项目内不存在该表对应实体 → 搜索无候选 → 生产侧将提示「未找到 Wrapper」（无法跳转）
            WrapperSearchResult search = WrapperSearchService.search(getProject(), tableName);
            assertTrue("无对应实体时搜索应返回 None（无法跳转到代码位置）", search instanceof WrapperSearchResult.None);
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void testSQL有表名_提取正确表名_进入Proceed() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            LogSqlEntry e = entry("SELECT u.id, u.name FROM user u WHERE u.id = 1");
            LiveJumpResolution resolution = panel.resolveLiveJump(e);
            assertTrue("含表名应进入 Proceed", resolution instanceof LjrProceed);
            assertEquals("应提取到 user 表", "user", ((LjrProceed) resolution).getTableName());
        } finally {
            panel.dispose();
        }
    }
}
