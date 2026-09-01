package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.UIUtil;
import org.junit.Assert.assertEquals;
import org.junit.Assert.assertFalse;
import org.junit.Assert.assertNotNull;
import org.junit.Assert.assertNull;
import org.junit.Assert.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;

/**
 * SQL 执行结果面板 UI 测试（F-44-6 续 · 补用例）：
 * 此前 0 测试，正是用户点名的「SqlPreviewPanel 错误态」薄弱点。覆盖：
 *   - 状态机 EMPTY/LOADING/ERROR/SUCCESS 正确切换（stateForTest 缝）；
 *   - showError：HTML 转义（防 < > & 破坏布局）、tooltip 存完整错误、errorCopyButton 显隐；
 *   - showIdle 保留 ERROR（防"一闪而过"）但清 LOADING/SUCCESS；
 *   - 自动可见性：有内容显示、空态隐藏（不占高度）。
 */
public class SqlResultPanelUiTest extends BasePlatformTestCase {

    private static void waitForEdt() {
        UIUtil.dispatchAllInvocationEvents();
    }

    private static List<Component> allComponents(Component c) {
        List<Component> list = new ArrayList<>();
        list.add(c);
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                list.addAll(allComponents(child));
            }
        }
        return list;
    }

    /** 错误复制按钮：JButton 且 text == bundle panel.copyError。 */
    private static JButton copyButton(SqlResultPanel panel) {
        for (Component c : allComponents(panel)) {
            if (c instanceof JButton) {
                JButton b = (JButton) c;
                if (MpSqlVisualizerBundle.message("panel.copyError").equals(b.getText())) {
                    return b;
                }
            }
        }
        return null;
    }

    /** 信息标签：唯一非空 JBLabel。 */
    private static JBLabel infoLabel(SqlResultPanel panel) {
        for (Component c : allComponents(panel)) {
            if (c instanceof JBLabel) {
                JBLabel l = (JBLabel) c;
                if (l.getText() != null && !l.getText().isEmpty()) {
                    return l;
                }
            }
        }
        return null;
    }

    public void testShowError_状态与复制按钮与可见性() {
        SqlResultPanel panel = new SqlResultPanel();
        try {
            panel.showError("boom: connection refused");
            waitForEdt();
            assertEquals("showError 应进入 ERROR 态", "ERROR", panel.stateForTest());
            assertEquals("currentError 应保留原始错误文本", "boom: connection refused", panel.currentErrorForTest());
            assertTrue("ERROR 态面板应可见", panel.isVisible());
            JButton btn = copyButton(panel);
            assertNotNull("错误复制按钮应存在", btn);
            assertTrue("错误复制按钮应可见", btn.isVisible());
        } finally {
            panel.clear();
        }
    }

    public void testShowError_HTML转义防布局破坏() {
        SqlResultPanel panel = new SqlResultPanel();
        try {
            // 含 < > & 与注入式序列——必须被转义，否则会破坏 infoLabel HTML 渲染
            String raw = "a < b & c > d </span><script>alert(1)</script>";
            panel.showError(raw);
            waitForEdt();
            String text = infoLabel(panel) != null ? infoLabel(panel).getText() : "";
            assertTrue("错误标签文本应包含 HTML 包裹", text.startsWith("<html>"));
            assertTrue("'<' 应被转义为 &lt;", text.contains("&lt;"));
            assertTrue("'>' 应被转义为 &gt;", text.contains("&gt;"));
            assertTrue("'&' 应被转义为 &amp;", text.contains("&amp;"));
            assertFalse("原始 '<' 不应直接出现（防注入/布局破坏）", text.contains("a < b"));
            // tooltip 保留完整原始错误（hover 可读）
            assertEquals("tooltip 应保留完整原始错误", raw, infoLabel(panel).getToolTipText());
        } finally {
            panel.clear();
        }
    }

    public void testShowIdle_保留错误态防一闪而过() {
        SqlResultPanel panel = new SqlResultPanel();
        try {
            panel.showError("boom");
            waitForEdt();
            panel.showIdle();
            waitForEdt();
            assertEquals("showIdle 在 ERROR 态下应保留 ERROR", "ERROR", panel.stateForTest());
            assertEquals("showIdle 应保留错误文本（可复制/可读）", "boom", panel.currentErrorForTest());
            assertTrue("showIdle 在 ERROR 态下面板仍可见", panel.isVisible());
            assertTrue("showIdle 在 ERROR 态下复制按钮仍可见", copyButton(panel).isVisible());
        } finally {
            panel.clear();
        }
    }

    public void testShowIdle_清除非错误态() {
        SqlResultPanel panel = new SqlResultPanel();
        try {
            panel.showLoading("ds1");
            waitForEdt();
            assertEquals("showLoading 应为 LOADING 态", "LOADING", panel.stateForTest());
            panel.showIdle();
            waitForEdt();
            assertEquals("showIdle 在 LOADING 态应回落 EMPTY", "EMPTY", panel.stateForTest());
            assertFalse("showIdle 后面板应隐藏（不占高度）", panel.isVisible());
            assertNull("showIdle 后 currentError 应为 null", panel.currentErrorForTest());
        } finally {
            panel.clear();
        }
    }

    public void test成功与加载态_隐藏复制按钮且可见() {
        SqlResultPanel panel = new SqlResultPanel();
        try {
            panel.showQueryResult(10, 25, "mysql");
            waitForEdt();
            assertEquals("showQueryResult 应为 SUCCESS 态", "SUCCESS", panel.stateForTest());
            assertTrue("结果态面板应可见", panel.isVisible());
            assertFalse("成功态不应显示错误复制按钮", copyButton(panel).isVisible());

            panel.showUpdateResult(3, 12, "mysql");
            waitForEdt();
            assertEquals("showUpdateResult 应为 SUCCESS 态", "SUCCESS", panel.stateForTest());

            panel.showLoading();
            waitForEdt();
            assertEquals("showLoading 应为 LOADING 态", "LOADING", panel.stateForTest());
            assertFalse("加载态不应显示错误复制按钮", copyButton(panel).isVisible());
        } finally {
            panel.clear();
        }
    }

    public void testShowEmpty_隐藏面板且清空错误() {
        SqlResultPanel panel = new SqlResultPanel();
        try {
            panel.showError("boom");
            waitForEdt();
            panel.showEmpty();
            waitForEdt();
            assertFalse("空态面板应隐藏", panel.isVisible());
            assertEquals("空态应为 EMPTY", "EMPTY", panel.stateForTest());
            assertNull("空态 currentError 应清空", panel.currentErrorForTest());
            assertFalse("空态复制按钮应隐藏", copyButton(panel).isVisible());
        } finally {
            panel.clear();
        }
    }
}
