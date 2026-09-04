package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel;
import cn.song.mybatisplus.visualizer.ui.SqlResultPanel;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL 执行错误处理测试（M36.3）：验证错误回调链路和 UI 状态恢复。
 *
 * 测试场景：
 *  1. Database 插件不可用时，错误回调被调用
 *  2. 执行链失败时，错误回调被调用且 UI 从「执行中」恢复
 *  3. 超时提示机制正常触发
 */
public class ExecuteSqlErrorHandlingTest extends BasePlatformTestCase {

    // ======================== 错误回调链路 ========================

    /** 测试：Database 插件不可用时，onError 回调被调用。 */
    @Test
    public void testErrorCallbackInvokedWhenPluginUnavailable() throws Exception {
        List<String> errors = new ArrayList<>();
        boolean[] callbackResult = {false};

        DataSourceSelector.selectAndExecute(
                getProject(),
                "SELECT * FROM users",
                null,
                null,
                msg -> errors.add(msg),
                success -> callbackResult[0] = success
        );

        assertTrue("onError should be called when plugin unavailable", !errors.isEmpty());
        assertTrue(
                "Error message should mention Database plugin",
                errors.stream().anyMatch(it -> it.contains("Database") || it.contains("database"))
        );
        assertEquals("callback should return false", false, callbackResult[0]);
    }

    /** 测试：未设置 onError 回调时不会空指针异常。 */
    @Test
    public void testNoErrorCallbackDoesNotThrow() throws Exception {
        DataSourceSelector.selectAndExecute(
                getProject(),
                "SELECT * FROM users",
                null,
                null,
                null,
                null
        );
    }

    /** 测试：runners 为空（无数据源）时，onError 回调被调用。 */
    @Test
    public void testErrorCallbackInvokedWhenRunnersEmpty() throws Exception {
        List<String> errors = new ArrayList<>();
        boolean[] callbackResult = {false};

        DataSourceSelector.selectAndExecute(
                getProject(),
                "SELECT * FROM users",
                null,
                null,
                msg -> errors.add(msg),
                success -> callbackResult[0] = success
        );

        assertTrue("onError should be called when runners empty", !errors.isEmpty());
        assertEquals("callback should return false", false, callbackResult[0]);
    }

    // ======================== SqlResultPanel 状态恢复 ========================

    /** 测试：SqlResultPanel.showError 正确设置错误文本和颜色。 */
    @Test
    public void testResultPanelShowError() throws Exception {
        SqlResultPanel panel = new SqlResultPanel();
        panel.showError("Connection failed");

        assertTrue(
                "Error text should contain the message",
                infoLabel(panel).getText().contains("Connection failed")
        );
        assertEquals("Error color should be red", java.awt.Color.RED, infoLabel(panel).getForeground());
        assertEquals("currentError should hold the full error text", "Connection failed", panel.currentErrorForTest());
    }

    /** 测试：SqlResultPanel 从 loading 状态切换到 error 状态。 */
    @Test
    public void testResultPanelLoadingToErrorTransition() throws Exception {
        SqlResultPanel panel = new SqlResultPanel();

        panel.showLoading((String) null);
        assertTrue("Loading text should be set", !infoLabel(panel).getText().isEmpty());

        panel.showError("Test error");
        assertTrue(
                "Error text should replace loading text",
                infoLabel(panel).getText().contains("Test error")
        );
    }

    // ======================== M36.5 自动可见性 ========================

    /** 测试：SqlResultPanel 初始/空状态隐藏，有内容（错误/loading）时显示。 */
    @Test
    public void testResultPanelAutoVisibility() throws Exception {
        SqlResultPanel panel = new SqlResultPanel();
        assertTrue("Panel should start hidden (no blank placeholder)", !panel.isVisible());

        panel.showError("boom");
        assertTrue("Panel should be visible with error content", panel.isVisible());

        panel.showLoading((String) null);
        assertTrue("Panel should be visible while loading", panel.isVisible());

        panel.showEmpty();
        assertTrue("Panel should hide when empty (no blank placeholder)", !panel.isVisible());
    }

    /** 测试（M36.5.49）：showError 后 showIdle 保留错误。 */
    @Test
    public void testShowIdlePreservesError() throws Exception {
        SqlResultPanel panel = new SqlResultPanel();
        panel.showError("Table 'xx' doesn't exist");

        panel.showIdle();
        assertEquals("showIdle should keep error text", "Table 'xx' doesn't exist", panel.currentErrorForTest());
        assertTrue("panel should stay visible when error preserved", panel.isVisible());
        assertEquals("state should remain ERROR", "ERROR", panel.stateForTest());

        panel.showEmpty();
        assertEquals("showEmpty should clear error", null, panel.currentErrorForTest());
        assertEquals("state should become EMPTY", "EMPTY", panel.stateForTest());
    }

    // ======================== M36.5 控制台准备/执行拆分 ========================

    /** 测试：executePrepared 在控制台未就绪时返回 false（不抛异常）。 */
    @Test
    public void testExecutePreparedNotReadyReturnsFalse() throws Exception {
        DataSourceSelector.clearPrepared();
        boolean executed = DataSourceSelector.executePrepared(getProject(), null, null);
        assertTrue("executePrepared should return false when console not prepared", !executed);
    }

    /** 测试（M36.5.2）：executePrepared 未就绪时不触发 onResultsReady。 */
    @Test
    public void testExecutePreparedNotReadyDoesNotFireResultsReady() throws Exception {
        DataSourceSelector.clearPrepared();
        boolean[] resultsReadyFired = {false};
        boolean executed = DataSourceSelector.executePrepared(
                getProject(),
                c -> resultsReadyFired[0] = true,
                null
        );
        assertTrue("executePrepared should return false when console not prepared", !executed);
        assertTrue("onResultsReady should not fire when console not prepared", !resultsReadyFired[0]);
    }

    /** 测试（M36.5.2）：SqlExecutor.executePrepared 同样在未就绪时返回 false。 */
    @Test
    public void testSqlExecutorExecutePreparedNotReadyReturnsFalse() throws Exception {
        SqlExecutor.clearPrepared();
        assertTrue(
                "SqlExecutor.executePrepared should return false when console not prepared",
                !SqlExecutor.executePrepared(getProject(), null, null)
        );
    }

    // ======================== M36.5.2 控制台内嵌防抖 ========================

    /** 测试（M36.5.2）：embedConsole 替换旧控制台；同一组件重复回调不重复内嵌（防闪烁）。 */
    @Test
    public void testEmbedConsoleReplacesAndDebounces() throws Exception {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        JPanel first = new JPanel();
        JPanel second = new JPanel();

        embedConsoleForTest(panel, first);
        assertTrue("first embed should attach to holder", first.getParent() != null);

        embedConsoleForTest(panel, second);
        assertTrue("second embed should replace as current", embeddedConsoleForTest(panel) == second);
        assertTrue("second embed should be attached", second.getParent() != null);
        assertTrue("first embed should be detached after replace", first.getParent() == null);

        JComponent parentBefore = (JComponent) second.getParent();
        embedConsoleForTest(panel, second);
        assertTrue("same instance re-embed should keep current", embeddedConsoleForTest(panel) == second);
        assertTrue("same instance re-embed should keep parent", second.getParent() == parentBefore);
    }

    /** 测试：SqlExecutor.prepareConsole 在插件不可用环境（单元测试沙盒）静默回退。 */
    @Test
    public void testPrepareConsoleSilentFallbackWhenPluginUnavailable() throws Exception {
        boolean[] callbackResult = {false};
        boolean[] errorInvoked = {false};

        SqlExecutor.prepareConsole(
                getProject(),
                "SELECT 1",
                null,
                null,
                error -> errorInvoked[0] = true,
                success -> callbackResult[0] = success
        );

        assertEquals("callback should be invoked with false in test sandbox", false, callbackResult[0]);
        assertTrue("onError should NOT be invoked (silent fallback)", !errorInvoked[0]);
    }

    /** 测试：clearPrepared 重置就绪状态。 */
    @Test
    public void testClearPrepared() throws Exception {
        DataSourceSelector.clearPrepared();
        assertTrue("isPrepared should be false after clearPrepared", !DataSourceSelector.isPrepared());
    }

    /** 测试：SqlResultPanel.clear 清空状态。 */
    @Test
    public void testResultPanelClear() throws Exception {
        SqlResultPanel panel = new SqlResultPanel();
        panel.showError("Some error");
        assertTrue("Error should be set", !infoLabel(panel).getText().isEmpty());

        panel.clear();
        assertTrue("Cleared panel should have empty text", infoLabel(panel).getText().isEmpty());
    }

    // ======================== SqlExecutor 错误透传 ========================

    /** 测试：SqlExecutor.execute 在插件不可用时调用 onError。 */
    @Test
    public void testSqlExecutorPropagatesError() throws Exception {
        List<String> errors = new ArrayList<>();

        SqlExecutor.execute(
                getProject(),
                "SELECT 1",
                null,
                true,
                null,
                null,
                msg -> errors.add(msg)
        );

        assertTrue("SqlExecutor should propagate error to onError", !errors.isEmpty());
    }

    /** 测试：SqlExecutor.execute 未设置 onError 时不抛异常。 */
    @Test
    public void testSqlExecutorNoErrorCallbackDoesNotThrow() throws Exception {
        SqlExecutor.execute(
                getProject(),
                "SELECT 1",
                null,
                true,
                null,
                null,
                null
        );
    }

    // ======================== 辅助方法 ========================

    /** 通过反射访问 SqlResultPanel 的 private infoLabel（测试用）。 */
    private static JLabel infoLabel(SqlResultPanel panel) {
        try {
            Field field = SqlResultPanel.class.getDeclaredField("infoLabel");
            field.setAccessible(true);
            return (JLabel) field.get(panel);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 跨包调用 SqlPreviewPanel 的包私有测试辅助方法（生产 Java 转换时 public 被降级）。 */
    private static void embedConsoleForTest(SqlPreviewPanel panel, JComponent component) {
        try {
            Method m = SqlPreviewPanel.class.getDeclaredMethod("embedConsoleForTest", JComponent.class);
            m.setAccessible(true);
            m.invoke(panel, component);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static JComponent embeddedConsoleForTest(SqlPreviewPanel panel) {
        try {
            Method m = SqlPreviewPanel.class.getDeclaredMethod("embeddedConsoleForTest");
            m.setAccessible(true);
            return (JComponent) m.invoke(panel);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
