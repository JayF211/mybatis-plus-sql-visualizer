package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.UIUtil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

    public void testShowError_状态与复制按钮与可见性() throws Exception {
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

    public void testShowError_HTML转义防布局破坏() throws Exception {
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

    public void testShowIdle_保留错误态防一闪而过() throws Exception {
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

    public void testShowIdle_清除非错误态() throws Exception {
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

    public void test成功与加载态_隐藏复制按钮且可见() throws Exception {
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

            panel.showLoading("");
            waitForEdt();
            assertEquals("showLoading 应为 LOADING 态", "LOADING", panel.stateForTest());
            assertFalse("加载态不应显示错误复制按钮", copyButton(panel).isVisible());
        } finally {
            panel.clear();
        }
    }

    public void testShowEmpty_隐藏面板且清空错误() throws Exception {
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

    // ------------------------------------------------------------------
    // T-ErrorLocateUsability T-ELU-01（O-49）：定位按钮可用性判定
    // （master 侧为 SqlResultPanelUiTest.kt 增量；本文件是 k2j 的 Java 对应件，按 Java 翻译落。
    //   master 六例已随资源束补移植全部落齐。）
    // ------------------------------------------------------------------

    /** 定位按钮：JButton 且 text == bundle execute.error.locate。 */
    private static JButton locateButton(SqlResultPanel panel) {
        for (Component c : allComponents(panel)) {
            if (c instanceof JButton) {
                JButton b = (JButton) c;
                if (MpSqlVisualizerBundle.message("execute.error.locate").equals(b.getText())) {
                    return b;
                }
            }
        }
        return null;
    }

    /** k2j 适配：ExecutionErrorInfo 为 record（7 组件，含 executedSqlLength），Java 无具名实参/默认参数，走全参构造。 */
    private static cn.song.mybatisplus.visualizer.execute.ExecutionErrorInfo info(Integer positionChar, Integer lineInSql) {
        return new cn.song.mybatisplus.visualizer.execute.ExecutionErrorInfo(
                "boom", null, null, positionChar, lineInSql, null, null);
    }

    /**
     * 本任务的核心判据（面板侧契约）：`locate == null` → 按钮**不出现**。
     * 宿主（`SqlPreviewPanel`）负责在锚点不可解析时传 null——改前它恒传非 null lambda，
     * 于是按钮可见但点了什么都不发生。
     */
    public void testShowErrorRich_不可定位时不显示定位按钮() throws Exception {
        SqlResultPanel panel = new SqlResultPanel();
        try {
            panel.showErrorRich(info(null, null), null);
            waitForEdt();
            assertEquals("仍应进入 ERROR 态", "ERROR", panel.stateForTest());
            JButton btn = locateButton(panel);
            assertNotNull("定位按钮组件应存在（仅隐藏）", btn);
            assertFalse("locate=null 时定位按钮不得可见", btn.isVisible());
            assertTrue("错误复制按钮不受影响", copyButton(panel).isVisible());
        } finally {
            panel.clear();
        }
    }

    public void testShowErrorRich_可定位时显示按钮且tooltip如实标注两态() throws Exception {
        SqlResultPanel panel = new SqlResultPanel();
        try {
            panel.showErrorRich(info(24, null), () -> { });
            waitForEdt();
            JButton btn = locateButton(panel);
            assertNotNull(btn);
            assertTrue("locate 非 null 时定位按钮应可见", btn.isVisible());
            String tip = btn.getToolTipText();
            // 用 bundle 自身文案比对（与既有用例同款，不依赖 locale）——钉住「用的是带位置的 key」
            assertEquals(
                "tooltip 应为带驱动位置的文案",
                MpSqlVisualizerBundle.message("execute.error.locate.tooltip.position", 24),
                tip);
            assertTrue("tooltip 应含驱动报的位置", tip.contains("24"));
            // 测试环境 bundle 兜底中文（MpSqlVisualizerBundle KDoc：settings 未注册 → 中文）
            assertTrue("tooltip 应如实标注位置指向被执行的 SQL", tip.contains("被执行的 SQL"));
            assertTrue("tooltip 不得宣称能定位到 mapper 原文行", tip.contains("不一一对应"));
        } finally {
            panel.clear();
        }
    }

    /** 无位置信号时用「语句起始行」口径的 tooltip（同样如实标注两态）。 */
    public void testShowErrorRich_无位置信号用起始行口径() throws Exception {
        SqlResultPanel panel = new SqlResultPanel();
        try {
            panel.showErrorRich(info(null, null), () -> { });
            waitForEdt();
            String tip = locateButton(panel).getToolTipText();
            assertTrue("tooltip 应标注两态", tip.contains("被执行的 SQL"));
        } finally {
            panel.clear();
        }
    }

    /**
     * T-Round10FU（独立复核 ㉕）：**只有行号、没有字符位置**的驱动报错（MySQL 语法错误 `at line N`）
     * 用带行号的文案。改前 tooltip 只判 `positionChar`，这类报错落到「语句起始行」兜底文案——
     * 驱动明明报了行号却被丢弃（`ExecutionErrorInfo.lineInSql` 因此在 src/main 无任何消费方）。
     */
    public void testShowErrorRich_只带行号时tooltip用行号文案() throws Exception {
        SqlResultPanel panel = new SqlResultPanel();
        try {
            panel.showErrorRich(info(null, 3), () -> { });
            waitForEdt();
            JButton btn = locateButton(panel);
            assertNotNull(btn);
            assertTrue("locate 非 null 时定位按钮应可见", btn.isVisible());
            String tip = btn.getToolTipText();
            assertEquals(
                "tooltip 应为带驱动行号的文案",
                MpSqlVisualizerBundle.message("execute.error.locate.tooltip.line", 3),
                tip);
            assertTrue("tooltip 应含驱动报的行号", tip.contains("3"));
            assertTrue("tooltip 应如实标注位置指向被执行的 SQL", tip.contains("被执行的 SQL"));
            assertTrue("tooltip 不得宣称能定位到 mapper 原文行", tip.contains("不一一对应"));
        } finally {
            panel.clear();
        }
    }

    /** 富错误后再走普通 showError（无定位）→ 定位按钮必须收起（不残留可点却无动作的按钮）。 */
    public void testShowErrorRich_转普通错误后按钮收起() throws Exception {
        SqlResultPanel panel = new SqlResultPanel();
        try {
            panel.showErrorRich(info(null, null), () -> { });
            waitForEdt();
            assertTrue(locateButton(panel).isVisible());
            panel.showError("boom again");
            waitForEdt();
            assertFalse("普通错误路径应隐藏定位按钮", locateButton(panel).isVisible());
        } finally {
            panel.clear();
        }
    }
}
