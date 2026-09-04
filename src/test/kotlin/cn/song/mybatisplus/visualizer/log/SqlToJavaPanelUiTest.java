package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.UIUtil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL 转 Java 面板「渲染 / 布局 / 交互」UI 测试（F-44-6 续 · 用户要求「整个插件 UI 用例」）：
 *
 * 此前仅覆盖转换逻辑（SqlToJavaPanelTest），ClassPicker 弹窗与代码格式化渲染未测——用户点名
 * 「ClassPicker 弹窗 / 代码格式化渲染未测」。本测试模拟真实面板打开：
 *  - 面板构建即出输入/结果编辑器、空态提示、生成形态下拉（均不崩、下拉有可选项）；
 *  - 真实转换：SQL → 生成 `new QueryWrapper<...>()` Java 代码；经代码格式化后含换行缩进（非塌缩单行）；
 *  - ClassPicker 渲染：选取类后 picker 渲染图标与类名 tooltip（弹窗选取落点渲染正确）；
 *  - 布局：非空标签与下拉有正首选尺寸（编辑器 headless 下 preferredSize 不可靠，跳过）。
 */
public class SqlToJavaPanelUiTest extends BasePlatformTestCase {

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

    @Test
    public void test面板构建_编辑器提示与格式下拉可见() throws Exception {
        SqlToJavaPanel panel = new SqlToJavaPanel(getProject());
        try {
            waitForEdt();
            assertNotNull("输入区空态提示应存在", panel.inputHintLabelForTest());
            assertNotNull("结果区空态提示应存在", panel.resultHintLabelForTest());

            // 生成形态下拉（代码格式化渲染相关控件，用户点名未测）
            JComboBox<?> combo = panel.formatComboForTest();
            assertNotNull("生成形态下拉应存在", combo);
            assertTrue("生成形态下拉应有可选项", combo.getModel().getSize() > 0);
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test转换_生成QueryWrapper且格式化渲染含换行() throws Exception {
        SqlToJavaPanel panel = new SqlToJavaPanel(getProject());
        PluginSettings.State state = PluginSettings.getInstance().getState();
        boolean old = state.getReverseManualEntitySelection();
        try {
            // 关闭手动选择 → 无实体直接回落字符串列名 QueryWrapper 路径（与 SqlToJavaPanelTest 一致）
            state.setReverseManualEntitySelection(false);
            panel.inputEditorForTest().setSql("SELECT id, name FROM user WHERE name = '张三'");
            panel.convertForTest();
            // convert 走后台线程，轮询等待结果（与既有测试 awaitResult 一致）
            String result = panel.resultTextForTest();
            int waited = 0;
            while (result.isBlank() && waited < 100) {
                waitForEdt();
                Thread.sleep(50);
                result = panel.resultTextForTest();
                waited++;
            }
            assertTrue("应生成 QueryWrapper Java 代码", result.contains("new QueryWrapper"));

            // 代码格式化渲染：格式化后含换行与缩进（非塌缩单行）
            panel.formatCodeForTest();
            String formatted = panel.resultTextForTest();
            assertTrue("格式化后代码应仍含 QueryWrapper", formatted.contains("new QueryWrapper"));
            assertTrue("格式化渲染应含换行（多行缩进）", formatted.contains("\n"));
        } finally {
            state.setReverseManualEntitySelection(old);
            panel.dispose();
        }
    }

    @Test
    public void testClassPicker_选取类后渲染图标与类名() throws Exception {
        SqlToJavaPanel panel = new SqlToJavaPanel(getProject());
        try {
            waitForEdt();
            SqlToJavaPanel.ClassPickerField picker = panel.callerFieldForTest();
            // 模拟用户在 ClassPicker 弹窗选中一个类（落点渲染）
            picker.pickForTest("Order", "com.example.Order");
            waitForEdt();
            assertNotNull("选取类后 picker 应渲染图标", picker.iconForTest());
            // 选中态：类名显示在文本标签（tooltip 为 null 不遮蔽全限定名）
            JBLabel textLabel = (JBLabel) picker.clickTargetsForTest().get(1);
            assertTrue("picker 文本标签应显示类名 Order", textLabel.getText().contains("Order"));
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test布局_非空标签与下拉有正首选尺寸() throws Exception {
        SqlToJavaPanel panel = new SqlToJavaPanel(getProject());
        try {
            waitForEdt();
            List<Component> targets = new ArrayList<>();
            for (Component c : allComponents(panel)) {
                if ((c instanceof JBLabel && !((JBLabel) c).getText().isBlank()) || c instanceof JComboBox<?>) {
                    targets.add(c);
                }
            }
            assertTrue("应存在标签/下拉控件", !targets.isEmpty());
            List<String> zeroNames = new ArrayList<>();
            for (Component c : targets) {
                if (c.getPreferredSize().width <= 0 || c.getPreferredSize().height <= 0) {
                    zeroNames.add(c.getClass().getSimpleName());
                }
            }
            assertEquals("非空标签与下拉应有正首选尺寸: " + zeroNames, 0, zeroNames.size());
        } finally {
            panel.dispose();
        }
    }
}
