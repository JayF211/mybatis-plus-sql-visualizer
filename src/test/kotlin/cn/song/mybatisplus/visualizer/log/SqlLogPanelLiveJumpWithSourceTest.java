package cn.song.mybatisplus.visualizer.log;

import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * 实时日志「精确跳转」回归（F-44-6）：[SqlLogPanel] 在条目携带源码位置时，
 * 应走与历史面板一致的 file:line 精确跳转（[LogJumpTarget.Precise]），
 * 而非退化为不可靠的表名搜索；仅在无源码位置时回退表名启发式。
 */
public class SqlLogPanelLiveJumpWithSourceTest extends BasePlatformTestCase {

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
        return entry(restoredSql, null, 0);
    }

    private static LogSqlEntry entry(String restoredSql, String sourceFile, int sourceLine) {
        return new LogSqlEntry(restoredSql, restoredSql, List.<LogSqlParam>of(), 1L, sourceFile, sourceLine, null);
    }

    private File writeRealSource(String relativePath, String content) throws Exception {
        File f = new File(getProject().getBasePath(), relativePath);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), content);
        VfsUtil.markDirtyAndRefresh(false, true, true, f);
        return f;
    }

    @Test
    public void test携带源码位置_精确跳转_Precise() throws Exception {
        writeRealSource("com/example/service/UserService.java", "class UserService {}\n");
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            LogJumpTarget target = panel.resolveEntryJump(entry("SELECT * FROM user WHERE id = 1", "com/example/service/UserService.java", 10));
            assertTrue("有源码位置应精确跳转", target instanceof LjtPrecise);
            LjtPrecise precise = (LjtPrecise) target;
            assertEquals("行号 = sourceLine-1", 9, precise.getLine());
            com.intellij.openapi.vfs.VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(
                new File(getProject().getBasePath(), "com/example/service/UserService.java").getAbsolutePath());
            assertEquals(vf, precise.getVirtualFile());
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test携带源码位置_真实跳转不抛异常_链路可达() throws Exception {
        writeRealSource("com/example/service/NavService.java", "class NavService {}\n");
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            LogSqlEntry e = entry("SELECT * FROM nav WHERE id = 1", "com/example/service/NavService.java", 3);
            assertTrue("解析应为 Precise", panel.resolveEntryJump(e) instanceof LjtPrecise);
            panel.jumpToSourceForTest(e); // 等价右键跳转，真实文件下不抛异常即证明 navigate 链路可达
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test源码文件不存在_NotFound_提示() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            LogJumpTarget target = panel.resolveEntryJump(entry("SELECT 1", "missing/Missing.java", 5));
            assertTrue(target instanceof LjtNotFound);
            assertEquals("missing/Missing.java", ((LjtNotFound) target).getSourceFile());
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test无源码位置_回退表名搜索_SearchByTable() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            LogJumpTarget target = panel.resolveEntryJump(entry("SELECT * FROM user u WHERE u.id = 1"));
            // 无源码位置 → 走原表名启发式（尽力而为，不可靠）
            assertTrue("无源码位置应回退表名搜索", target instanceof LjtSearchByTable);
            assertEquals("user", ((LjtSearchByTable) target).getTableName());
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test无源码位置_无表名_NoTable() throws Exception {
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            LogJumpTarget target = panel.resolveEntryJump(entry("SELECT 1"));
            assertTrue(target instanceof LjtNoTable);
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test旧版条目_sourceFile为null_回退路径不崩() throws Exception {
        // 旧版 F-43 保存的实时条目 sourceFile 恒为 null → 必须回退表名搜索，不得 NPE
        // （headless 下不实际调用 jumpToSourceForTest，因其会触发 Messages 弹窗；仅断言决策分支）
        SqlLogPanel panel = new SqlLogPanel(getProject(), manager);
        try {
            LogJumpTarget target = panel.resolveEntryJump(entry("SELECT * FROM t", null, 0));
            assertTrue(
                "无源码位置应回退（SearchByTable / NoTable），不得抛异常",
                target instanceof LjtSearchByTable || target instanceof LjtNoTable
            );
        } finally {
            panel.dispose();
        }
    }
}
