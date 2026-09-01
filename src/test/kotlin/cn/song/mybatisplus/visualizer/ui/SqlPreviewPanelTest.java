package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.model.BatchColumn;
import cn.song.mybatisplus.visualizer.model.BatchKind;
import cn.song.mybatisplus.visualizer.model.BatchModel;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.ColumnRole;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.ConditionToggle;
import cn.song.mybatisplus.visualizer.model.ConditionView;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SqlPreviewModel;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import cn.song.mybatisplus.visualizer.sql.MySqlDialect;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext;
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.lang.Language;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.UIUtil;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.Transferable;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.swing.JCheckBox;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import org.junit.Assert.assertEquals;
import org.junit.Assert.assertFalse;
import org.junit.Assert.assertNotEquals;
import org.junit.Assert.assertNotNull;
import org.junit.Assert.assertNull;
import org.junit.Assert.assertTrue;

/**
 * SqlPreviewPanel 行为测试（M8/M9）：编辑器视图 + 单行多行切换 + 生命周期 + 参数回填。
 *
 * 需要 IDE fixture（EditorFactory/PSI 依赖应用环境），在 EDT 上运行。
 */
public class SqlPreviewPanelTest extends BasePlatformTestCase {

    // 测试前快照系统剪贴板，tearDown 恢复，避免 copySql/copyError 污染用户剪贴板
    private Transferable clipboardSnapshot;

    // T-M27.4：渲染文本断言类测试关闭自动格式化（默认开启的格式化行为由
    // SqlFormatterTest + testRenderAutoFormatsSql 专项覆盖；此处模拟"关闭格式化"场景）
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        PluginSettings.getInstance().getState().setFormatSql(false);
        clipboardSnapshot = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
    }

    @Override
    protected void tearDown() throws Exception {
        PluginSettings.getInstance().getState().setFormatSql(true);
        if (clipboardSnapshot != null) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(clipboardSnapshot, null);
        }
        super.tearDown();
    }

    private static String computeParamTable(List<String> params) {
        return params.isEmpty() ? null
                : "-- 参数对照: [" + params.stream().map(p -> p + "=?").collect(Collectors.joining(", ")) + "]";
    }

    private PreviewResult mk(String sql, String compact, List<String> params, List<String> columns,
                             String tableName, List<TableInfo> candidateTables) {
        return new PreviewResult(
                sql,
                compact,
                params,
                computeParamTable(params),
                WrapperType.LAMBDA_QUERY,
                tableName,
                null,
                columns,
                Map.of(),
                null,
                null,
                List.of(),
                candidateTables,
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                null);
    }

    private PreviewResult result() {
        return result("SELECT id\nFROM tbl", "SELECT id FROM tbl", List.of(), List.of("id", "name"), "tbl", List.of());
    }

    private PreviewResult result(String sql, String compact) {
        return result(sql, compact, List.of(), List.of("id", "name"), "tbl", List.of());
    }

    private PreviewResult result(String sql, String compact, List<String> params) {
        return result(sql, compact, params, List.of("id", "name"), "tbl", List.of());
    }

    private PreviewResult result(String sql, String compact, List<String> params, List<String> columns) {
        return result(sql, compact, params, columns, "tbl", List.of());
    }

    private PreviewResult result(String sql, String compact, List<String> params, List<String> columns, String tableName) {
        return result(sql, compact, params, columns, tableName, List.of());
    }

    private PreviewResult result(String sql, String compact, List<String> params, List<String> columns,
                                 String tableName, List<TableInfo> candidateTables) {
        return mk(sql, compact, params, columns, tableName, candidateTables);
    }

    public void testShowResultCreatesEditorView() {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result());
        Editor editor = panel.editorForTest();
        assertNotNull("应挂载编辑器视图", editor);
        assertEquals("SELECT id\nFROM tbl", editor.getDocument().getText());
        panel.clear();
    }

    public void testShowResultReplacesEditor() {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result("SELECT 1 FROM t1", "SELECT 1 FROM t1"));
        Editor first = panel.editorForTest();
        panel.showResult(result("SELECT 2 FROM t2", "SELECT 2 FROM t2"));
        Editor second = panel.editorForTest();
        assertNotNull(first);
        assertNotNull(second);
        assertEquals("SELECT 2 FROM t2", second.getDocument().getText());
        panel.clear();
    }

    public void testToggleCompactSwitchesSql() {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result());
        assertEquals("SELECT id\nFROM tbl", panel.editorForTest().getDocument().getText());

        assertTrue("toggleCompact 应返回单行模式", panel.toggleCompact());
        assertTrue(panel.compactForTest());
        assertEquals("SELECT id FROM tbl", panel.editorForTest().getDocument().getText());

        panel.toggleCompact();
        assertEquals("SELECT id\nFROM tbl", panel.editorForTest().getDocument().getText());
        panel.clear();
    }

    public void testRefreshResetsModeToSettingsDefault() {
        // M19.3（用户实测：点刷新后 SQL 仍停留在单行模式）：
        // 刷新 = 模式复位为与设置「SQL 格式化输出」对齐（默认多行）——手动切到单行后刷新应回多行
        // （setUp 关闭格式化以保持渲染断言——本测试需默认开启语义，显式恢复）
        PluginSettings.getInstance().getState().setFormatSql(true);
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result());
        panel.toggleCompact();   // 切到单行
        assertTrue(panel.compactForTest());
        assertEquals("SELECT id FROM tbl", panel.editorForTest().getDocument().getText());

        panel.refreshView();
        assertFalse("刷新后应恢复默认多行模式", panel.compactForTest());
        // formatSql=true 时刷新渲染自动格式化
        assertEquals("SELECT\n    id\nFROM tbl", panel.editorForTest().getDocument().getText());
        panel.clear();
    }

    public void testCopySqlWithContent() {
        // 无 project 时不弹成功提示（测试环境 Messages 对话框会抛异常）
        SqlPreviewPanel panel = new SqlPreviewPanel(null);
        panel.showResult(result());
        assertTrue("有内容时应复制成功", panel.copySql());
        panel.clear();
    }

    public void testCopySqlClosePanelCallbackInjectable() {
        // T-M23.6：onClosePanelRequested 回调默认 null、可注入（预览弹窗赋值 dispose）；
        // project null → 弹框跳过（回调不触发），注入的回调可被面板外部链路触发
        SqlPreviewPanel panel = new SqlPreviewPanel(null);
        assertNull("默认无关闭面板回调", panel.onClosePanelRequested);
        AtomicBoolean closed = new AtomicBoolean(false);
        panel.onClosePanelRequested = () -> closed.set(true);
        panel.showResult(result());
        assertTrue("有内容时应复制成功", panel.copySql());
        assertFalse("无 project 弹框跳过，回调不应触发", closed.get());
        // 链路：点「关闭 SQL 面板」→ onClosePanelRequested?.invoke()
        if (panel.onClosePanelRequested != null) panel.onClosePanelRequested.run();
        assertTrue("注入的回调应可触发", closed.get());
        panel.clear();
    }

    public void testClearReleasesEditor() {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result());
        assertNotNull(panel.editorForTest());
        panel.clear();
        assertNull("clear 后应释放编辑器", panel.editorForTest());
        assertNull(panel.currentForTest());
    }

    public void testShowResultBuildsParamFields() {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result(List.of("dto.getPhone()", "id")));
        assertEquals(2, panel.paramFieldForTest(1) != null ? 2 : 0);
        assertNotNull("应生成参数输入框", panel.paramFieldForTest(0));
        panel.clear();
    }

    public void testParamRowLabelWidthAdaptsAndTooltipForLongNames() {
        // T-M19.4-01 + T-M26.3
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        String shortName = "id";
        String medium = "productInfo.getDicProtocolType()";   // 用户实测场景：约 200px
        String longName = "productInfo.getDicProtocolType().getDeviceName().getStatusName().getDetailInfo()";
        panel.showResult(result(List.of(shortName, medium, longName)));
        JBLabel shortLabel = panel.paramRowLabelForTest(0);
        JBLabel mediumLabel = panel.paramRowLabelForTest(1);
        JBLabel longLabel = panel.paramRowLabelForTest(2);
        // T-M26.3：同一场景所有参数行共享标签列宽（最长可见标签夹取 140~260）
        int columnWidth = shortLabel.getPreferredSize().width;
        assertTrue("列宽应不小于下限 140", columnWidth >= 140);
        assertTrue("列宽应不超过上限 260", columnWidth <= 260);
        assertEquals("标签列宽应统一", columnWidth, mediumLabel.getPreferredSize().width);
        assertEquals("标签列宽应统一", columnWidth, longLabel.getPreferredSize().width);
        // 超长名：截断显示（文本 != 全名）+ tooltip 全名；短/中名不截断、无 tooltip
        assertEquals("超长标签 tooltip 应为全名", "#" + longName, longLabel.getToolTipText());
        assertNotEquals("超长标签应截断显示", "#" + longName, longLabel.getText());
        assertNull("短参数不设 tooltip", shortLabel.getToolTipText());
        assertNull("中等长度参数不设 tooltip", mediumLabel.getToolTipText());
        panel.clear();
    }

    // ------------------------------------------------------------------
    // M9：参数回填 / 可编辑 / 补全上下文
    // ------------------------------------------------------------------

    public void testParamBackfillUpdatesSql() {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        String sql = "SELECT id FROM tbl WHERE phone = ? AND type = ?";
        panel.showResult(result(sql, sql, List.of("dto.getPhone()", "type")));
        // 输入第一个参数值（字符串）→ SQL 中第一个 ? 加单引号回填
        panel.paramFieldForTest(0).setText("abc");
        assertEquals(
                "SELECT id FROM tbl WHERE phone = 'abc' AND type = ?",
                panel.editorForTest().getDocument().getText());
        // 输入第二个（数字）参数值 → 原样
        panel.paramFieldForTest(1).setText("1");
        assertEquals(
                "SELECT id FROM tbl WHERE phone = 'abc' AND type = 1",
                panel.editorForTest().getDocument().getText());
        panel.clear();
    }

    public void testParamBackfillEmptyKeepsPlaceholder() {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        String sql = "SELECT id FROM tbl WHERE phone = ?";
        panel.showResult(result(sql, sql, List.of("phone")));
        // 留空 → 保留 ?
        assertEquals(sql, panel.editorForTest().getDocument().getText());
        panel.clear();
    }

    // ------------------------------------------------------------------
    // T-M42：日志双击预览参数识别——initialParamValues 预填
    // ------------------------------------------------------------------

    public void testInitialParamValuesPrefillFieldAndRender() {
        // 日志识别路径：param1 预填 "admin"（String → 引号提示）——初始显示与现状（值内联 'admin'）一致
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        String sql = "SELECT id FROM tbl WHERE username = ?";
        panel.showResult(new PreviewResult(
                sql,
                sql,
                List.of("param1"),
                null,
                WrapperType.LAMBDA_QUERY,
                "tbl",
                null,
                List.of(),
                Map.of(),
                null,
                null,
                List.of(new ParamHint(true, null, false)),
                List.of(),
                null,
                null,
                Map.of("param1", "admin"),
                null,
                null,
                null,
                null));
        assertEquals("参数行应预填日志参数值", "admin", panel.paramFieldForTest(0).getText());
        assertTrue(
                "SQL 初始渲染应含值内联 'admin'：" + panel.editorForTest().getDocument().getText(),
                panel.editorForTest().getDocument().getText().contains("username = 'admin'"));
        panel.clear();
    }

    public void testInitialParamValuesEditableAfterPrefill() {
        // 预填值可编辑：改参数行值 → SQL 实时回填
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        String sql = "SELECT id FROM tbl WHERE username = ?";
        panel.showResult(new PreviewResult(
                sql,
                sql,
                List.of("param1"),
                null,
                WrapperType.LAMBDA_QUERY,
                "tbl",
                null,
                List.of(),
                Map.of(),
                null,
                null,
                List.of(new ParamHint(true, null, false)),
                List.of(),
                null,
                null,
                Map.of("param1", "admin"),
                null,
                null,
                null,
                null));
        panel.paramFieldForTest(0).setText("zhang");
        assertTrue(
                "修改参数行值后 SQL 应同步回填：" + panel.editorForTest().getDocument().getText(),
                panel.editorForTest().getDocument().getText().contains("username = 'zhang'"));
        assertFalse("原值不应残留", panel.editorForTest().getDocument().getText().contains("admin"));
        panel.clear();
    }

    public void testInitialParamValuesMissingSourceFallsBackToEmpty() {
        // 预填表缺失的来源回落空串（参数行初始为空 → 渲染保留 `?`）
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        String sql = "SELECT id FROM tbl WHERE phone = ? AND type = ?";
        panel.showResult(new PreviewResult(
                sql,
                sql,
                List.of("param1", "param2"),
                null,
                WrapperType.LAMBDA_QUERY,
                "tbl",
                null,
                List.of(),
                Map.of(),
                null,
                null,
                List.of(new ParamHint(true, null, false), new ParamHint(false, null, false)),
                List.of(),
                null,
                null,
                Map.of("param1", "admin"),
                null,
                null,
                null,
                null));
        assertEquals("param1 应预填", "admin", panel.paramFieldForTest(0).getText());
        assertEquals("param2 无预填值应回落空串", "", panel.paramFieldForTest(1).getText());
        assertTrue(
                "param2 空值渲染保留 ?：" + panel.editorForTest().getDocument().getText(),
                panel.editorForTest().getDocument().getText().contains("type = ?"));
        panel.clear();
    }

    public void testEditorIsEditable() {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result());
        Editor editor = panel.editorForTest();
        assertFalse("应为可编辑编辑器（非只读 viewer）", editor.isViewer());
        panel.clear();
    }

    public void testCompletionContextAttached() {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result(List.of("id", "phone")));
        PsiFile psi = panel.psiFileForTest();
        assertNotNull("应创建预览 PSI 文件", psi);
        SqlCompletionContext ctx = psi.getUserData(SqlCompletionContext.KEY);
        assertNotNull("应挂补全上下文", ctx);
        assertEquals("tbl", ctx.getTableName());
        assertEquals(List.of("id", "phone"), ctx.getColumns());

        // M16.2 双载体：Editor 上同样挂载补全上下文
        Editor editor = panel.editorForTest();
        assertNotNull("应创建编辑器", editor);
        SqlCompletionContext editorCtx = editor.getUserData(SqlCompletionContext.KEY);
        assertNotNull("Editor 上应挂补全上下文（M16.2）", editorCtx);
        assertEquals("tbl", editorCtx.getTableName());
        assertEquals(List.of("id", "phone"), editorCtx.getColumns());
        panel.clear();
    }

    // ------------------------------------------------------------------
    // M12/M13：动态条件复选框 + 回填提示
    // ------------------------------------------------------------------

    private TableInfo tableInfo() {
        List<ColumnInfo> columns = List.of(
                new ColumnInfo("id", "id", true, false, "java.lang.Long", null, null, ColumnRole.BUSINESS),
                new ColumnInfo("stationId", "station_id", false, false, "java.lang.Long", null, null, ColumnRole.BUSINESS),
                new ColumnInfo("name", "name", false, false, "java.lang.String", null, null, ColumnRole.BUSINESS));
        return new TableInfo("DeviceInfoEntity", "car_device_info", columns, null, null, null, List.of());
    }

    /** 构造带动态条件模型的预览结果（面板走模型级重生成路径）。 */
    private PreviewResult dynamicResult(List<Condition> conditions, List<ConditionView> views, List<String> params) {
        TableInfo table = tableInfo();
        SelectStatement statement = new SelectStatement(table, List.of(), null, List.of(), false, conditions,
                List.of(), null, List.of(), null, null, null, null, List.of(), null, List.of());
        SqlPreviewModel model = new SqlPreviewModel(statement, MySqlDialect.INSTANCE, true, views);
        return new PreviewResult(
                "SELECT id, station_id, name\nFROM car_device_info",
                "SELECT id, station_id, name FROM car_device_info",
                params,
                computeParamTable(params),
                WrapperType.LAMBDA_QUERY,
                "car_device_info",
                null,
                List.of("id", "station_id", "name"),
                Map.of(),
                null,
                model,
                List.of(),
                List.of(),
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                null);
    }

    private PreviewResult dynamicResult(List<Condition> conditions, List<ConditionView> views) {
        return dynamicResult(conditions, views, List.of());
    }

    public void testDynamicConditionCheckboxTogglesSql() {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(dynamicResult(
                List.of(
                        new Condition.Simple(new ColumnRef(null, "id", null, null, null, null, null), "=",
                                new SqlValue.Literal("1"),
                                new ConditionToggle("c0", "true", true, "id = 1", null, List.of())),
                        new Condition.Simple(new ColumnRef(null, "station_id", null, null, null, null, null), "=",
                                new SqlValue.Literal("2"),
                                new ConditionToggle("c1", "false", false, "station_id = 2", null, List.of()))),
                List.of(
                        new ConditionView("c0", "id = 1", "true", true, null, List.of()),
                        new ConditionView("c1", "station_id = 2", "false", false, null, List.of()))));
        // 默认：eq(true) 保留、eq(false) 排除
        assertTrue(panel.editorForTest().getDocument().getText().contains("WHERE id = 1"));
        assertFalse(panel.editorForTest().getDocument().getText().contains("station_id = 2"));

        // 勾选第二个（eq(false)）→ 拼接 station_id = 2
        panel.conditionCheckboxForTest(1).doClick();
        assertTrue(panel.editorForTest().getDocument().getText().contains("AND station_id = 2"));

        // 取消第一个（eq(true)）→ 移除 id = 1，station_id 保留
        panel.conditionCheckboxForTest(0).doClick();
        String sql = panel.editorForTest().getDocument().getText();
        assertFalse(sql.contains("id = 1"));
        assertTrue(sql.contains("station_id = 2"));
        panel.clear();
    }

    public void testSelectAllNoneTogglesAllConditions() {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(dynamicResult(
                List.of(
                        new Condition.Simple(new ColumnRef(null, "id", null, null, null, null, null), "=",
                                new SqlValue.Literal("1"),
                                new ConditionToggle("c0", "true", true, "id = 1", null, List.of())),
                        new Condition.Simple(new ColumnRef(null, "station_id", null, null, null, null, null), "=",
                                new SqlValue.Literal("2"),
                                new ConditionToggle("c1", "false", false, "station_id = 2", null, List.of()))),
                List.of(
                        new ConditionView("c0", "id = 1", "true", true, null, List.of()),
                        new ConditionView("c1", "station_id = 2", "false", false, null, List.of()))));
        // 初始：仅 c0
        assertTrue(panel.editorForTest().getDocument().getText().contains("WHERE id = 1"));
        assertFalse(panel.editorForTest().getDocument().getText().contains("station_id = 2"));

        // 全选 → 两个条件都在，复选框状态同步
        panel.selectAllButtonForTest().doClick();
        assertTrue(panel.editorForTest().getDocument().getText().contains("AND station_id = 2"));
        assertTrue(panel.conditionCheckboxForTest(0).isSelected());
        assertTrue(panel.conditionCheckboxForTest(1).isSelected());

        // 全不选 → 动态条件全部移除（无 WHERE），复选框状态同步
        panel.selectNoneButtonForTest().doClick();
        assertFalse(panel.editorForTest().getDocument().getText().contains("WHERE"));
        assertFalse(panel.conditionCheckboxForTest(0).isSelected());
        assertFalse(panel.conditionCheckboxForTest(1).isSelected());
        assertTrue(panel.enabledIdsForTest().isEmpty());

        // 全选后再取消单个勾选 → 另一个保留
        panel.selectAllButtonForTest().doClick();
        panel.conditionCheckboxForTest(1).doClick();
        String sql = panel.editorForTest().getDocument().getText();
        assertTrue(sql.contains("id = 1"));
        assertFalse(sql.contains("station_id = 2"));
        panel.clear();
    }

    public void testClearButtonClearsConditionValues() {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(dynamicResult(
                List.of(
                        new Condition.Simple(new ColumnRef(null, "name", null, null, null, null, null), "=",
                                new SqlValue.Placeholder("nameVar"),
                                new ConditionToggle("c0", "StringUtils.isNotBlank(name)", true, "name = ?", "nameVar", List.of())),
                        new Condition.Simple(new ColumnRef(null, "station_id", null, null, null, null, null), "=",
                                new SqlValue.Placeholder("stationIdVar"),
                                new ConditionToggle("c1", "stationId != null", false, "station_id = ?", "stationIdVar", List.of()))),
                List.of(
                        new ConditionView("c0", "name = ?", "StringUtils.isNotBlank(name)", true, "nameVar", List.of()),
                        new ConditionView("c1", "station_id = ?", "stationId != null", false, "stationIdVar", List.of()))));
        // 清除按钮存在
        assertNotNull("清除按钮应存在", panel.clearButtonForTest());
        // 填写条件值
        panel.conditionValueFieldForTest(0).setText("zhang");
        panel.conditionValueFieldForTest(1).setText("100");
        // 值回填到 SQL
        assertTrue(panel.editorForTest().getDocument().getText().contains("name = 'zhang'"));
        // 点击清除
        panel.clearButtonForTest().doClick();
        // 输入框清空
        assertEquals("清除后输入框应为空", "", panel.conditionValueFieldForTest(0).getText());
        assertEquals("清除后输入框应为空", "", panel.conditionValueFieldForTest(1).getText());
        // paramValues 清空
        assertEquals("paramValues 应为空字符串", "", panel.paramValuesForTest().get("nameVar"));
        assertEquals("paramValues 应为空字符串", "", panel.paramValuesForTest().get("stationIdVar"));
        // 勾选态保留（c0 默认勾选，c1 默认不勾选）
        assertTrue("清除不影响勾选态", panel.conditionCheckboxForTest(0).isSelected());
        assertFalse("清除不影响勾选态", panel.conditionCheckboxForTest(1).isSelected());
        // SQL 恢复含 ? 形态
        assertTrue("清除后 SQL 应恢复 ?", panel.editorForTest().getDocument().getText().contains("name = ?"));
        panel.clear();
    }

    public void testDynamicConditionValueBlankKeepsChecked() {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(dynamicResult(
                List.of(
                        new Condition.Simple(new ColumnRef(null, "name", null, null, null, null, null), "=",
                                new SqlValue.Placeholder("nameVar"),
                                new ConditionToggle("c0", "StringUtils.isNotBlank(name)", true, "name = ?", "nameVar", List.of()))),
                List.of(
                        new ConditionView("c0", "name = ?", "StringUtils.isNotBlank(name)", true, "nameVar", List.of()))));
        // 初始：值空但默认勾选 → 保留 ?（不再按判空语义自动取消勾选）
        assertTrue(panel.enabledIdsForTest().contains("c0"));
        assertTrue(panel.editorForTest().getDocument().getText().contains("name = ?"));

        // 填写值 → 拼接实际值
        panel.conditionValueFieldForTest(0).setText("张三");
        assertTrue(panel.editorForTest().getDocument().getText().contains("name = '张三'"));

        // 清空 → 勾选态不变、保留 ?
        panel.conditionValueFieldForTest(0).setText("");
        assertTrue(panel.enabledIdsForTest().contains("c0"));
        assertTrue(panel.editorForTest().getDocument().getText().contains("name = ?"));

        // 手动取消勾选 → 排除
        panel.conditionCheckboxForTest(0).doClick();
        assertFalse(panel.editorForTest().getDocument().getText().contains("WHERE"));
        panel.clear();
    }

    public void testDynamicConditionLikePlaceholderPercentBackfill() {
        // M13：LIKE 占位符回填自动拼百分号（name 为 String 列）→ name LIKE '%张三%'
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(dynamicResult(
                List.of(
                        new Condition.Like(new ColumnRef(null, "name", null, null, null, null, null), false,
                                new SqlValue.Placeholder("likeVar"), LikePosition.ANYWHERE,
                                new ConditionToggle("c0", "true", true, "name LIKE ?", "likeVar", List.of()))),
                List.of(
                        new ConditionView("c0", "name LIKE ?", "true", true, "likeVar", List.of()))));
        panel.conditionValueFieldForTest(0).setText("张三");
        assertTrue(panel.editorForTest().getDocument().getText().contains("name LIKE '%张三%'"));
        panel.clear();
    }

    public void testDynamicConditionStringColumnNumericValueQuoted() {
        // M13：String 列（java.lang.String）输入数字 → 恒加引号
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(dynamicResult(
                List.of(
                        new Condition.Simple(new ColumnRef(null, "name", null, null, null, null, null), "=",
                                new SqlValue.Placeholder("nameVar"),
                                new ConditionToggle("c0", "true", true, "name = ?", "nameVar", List.of()))),
                List.of(
                        new ConditionView("c0", "name = ?", "true", true, "nameVar", List.of()))));
        panel.conditionValueFieldForTest(0).setText("123");
        assertTrue(panel.editorForTest().getDocument().getText().contains("name = '123'"));
        panel.clear();
    }

    public void testDynamicConditionNumericColumnValueRaw() {
        // M13：数值列（java.lang.Long）输入数字 → 原样不带引号
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(dynamicResult(
                List.of(
                        new Condition.Simple(new ColumnRef(null, "station_id", null, null, null, null, null), "=",
                                new SqlValue.Placeholder("stationVar"),
                                new ConditionToggle("c0", "true", true, "station_id = ?", "stationVar", List.of()))),
                List.of(
                        new ConditionView("c0", "station_id = ?", "true", true, "stationVar", List.of()))));
        panel.conditionValueFieldForTest(0).setText("2");
        assertTrue(panel.editorForTest().getDocument().getText().contains("station_id = 2"));
        panel.clear();
    }

    public void testDynamicConditionDateTimeColumnValueQuoted() {
        // T-M23-03（用户实测：LocalDateTime 参数回填不带引号）：除确定的数值类型外其他类型默认带引号
        TableInfo base = tableInfo();
        List<ColumnInfo> newCols = new ArrayList<>(base.getColumns());
        newCols.add(new ColumnInfo("useTime", "use_time", false, false, "java.time.LocalDateTime", null, null, ColumnRole.BUSINESS));
        TableInfo ti = new TableInfo(base.getEntityClassName(), base.getTableName(), newCols,
                base.getLogicDeleteColumn(), base.getVersionColumn(), base.getComment(), base.getAssociations());
        SelectStatement statement = new SelectStatement(ti, List.of(), null, List.of(), false,
                List.of(new Condition.Simple(new ColumnRef(null, "use_time", null, null, null, null, null), "=",
                        new SqlValue.Placeholder("now"),
                        new ConditionToggle("c0", "true", true, "use_time = ?", "now", List.of()))),
                List.of(), null, List.of(), null, null, null, null, List.of(), null, List.of());
        SqlPreviewModel model = new SqlPreviewModel(statement, MySqlDialect.INSTANCE, true,
                List.of(new ConditionView("c0", "use_time = ?", "true", true, "now", List.of())));
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(new PreviewResult(
                "SELECT id, station_id, name, use_time\nFROM car_device_info",
                "SELECT id, station_id, name, use_time FROM car_device_info",
                List.of(),
                null,
                WrapperType.LAMBDA_QUERY,
                "car_device_info",
                null,
                List.of("id", "station_id", "name", "use_time"),
                Map.of(),
                null,
                model,
                List.of(),
                List.of(),
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                null));
        panel.conditionValueFieldForTest(0).setText("2024-01-01 00:00:00");
        assertTrue(panel.editorForTest().getDocument().getText().contains("use_time = '2024-01-01 00:00:00'"));
        panel.clear();
    }

    // ------------------------------------------------------------------
    // T-M23-04：批量参数列级勾选（BatchModel 复选框 + 勾选重建 SQL）
    // ------------------------------------------------------------------

    /** 构造带 BatchModel 的批量预览结果（面板走批量勾选重生成路径）。 */
    private PreviewResult batchResult(BatchModel model) {
        Set<String> active = new LinkedHashSet<>();
        for (BatchColumn c : model.getToggleColumns()) {
            if (c.isChecked()) active.add(c.getColumnName());
        }
        for (BatchColumn c : model.getFixedColumns()) {
            active.add(c.getColumnName());
        }
        String sql = model.renderSql(active);
        List<String> unresolved = model.orderedSources(active);
        return new PreviewResult(
                sql,
                sql,
                unresolved,
                null,
                null,
                model.getTableName(),
                null,
                model.getToggleColumns().stream().map(BatchColumn::getColumnName).collect(Collectors.toList()),
                Map.of(),
                null,
                null,
                List.of(),
                List.of(),
                model,
                null,
                Map.of(),
                null,
                null,
                null,
                null);
    }

    public void testBatchModelAutoIdDefaultUncheckedRendersWithoutId() {
        // T-M23-04（用户建议）：save(entity) 自增主键默认不勾选 → 初始 SQL 无 id 列
        BatchModel model = new BatchModel(BatchKind.INSERT, "biz_store_coupon",
                List.of(
                        new BatchColumn("id", "entity.id", false, false),
                        new BatchColumn("name", "entity.name", true, true),
                        new BatchColumn("dic_status", "entity.dic_status", false, true)),
                List.of(), null, null, MySqlDialect.INSTANCE);
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(batchResult(model));
        String sql = panel.editorForTest().getDocument().getText();
        assertTrue("初始 SQL 应含勾选列", sql.contains("INSERT INTO biz_store_coupon (name, dic_status)"));
        assertFalse("初始 SQL 不应含 id 列", sql.contains("id"));
        assertFalse("id 复选框默认不勾选", panel.batchCheckboxForTest(0).isSelected);
        panel.clear();
    }

    public void testBatchModelCheckboxToggleRebuildsSql() {
        BatchModel model = new BatchModel(BatchKind.INSERT, "biz_store_coupon",
                List.of(
                        new BatchColumn("id", "entity.id", false, false),
                        new BatchColumn("name", "entity.name", true, true),
                        new BatchColumn("dic_status", "entity.dic_status", false, true)),
                List.of(), null, null, MySqlDialect.INSTANCE);
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(batchResult(model));
        // 勾选 id → INSERT 恢复 id 列
        panel.batchCheckboxForTest(0).doClick();
        assertTrue(panel.batchCheckboxForTest(0).isSelected);
        assertTrue(
                "勾选后 SQL 应含 id",
                panel.editorForTest().getDocument().getText().contains("INSERT INTO biz_store_coupon (id, name, dic_status)"));
        // 取消 name → 列清单移除 name
        panel.batchCheckboxForTest(1).doClick();
        assertTrue(
                "取消后 SQL 不应含 name",
                panel.editorForTest().getDocument().getText().contains("INSERT INTO biz_store_coupon (id, dic_status)"));
        // 回填值不丢：勾选态变更后重渲染仍按 source 绑定；未勾选列的输入值不进 SQL
        panel.paramFieldForTest(0).setText("1");
        panel.paramFieldForTest(1).setText("张三");
        assertTrue(panel.editorForTest().getDocument().getText().contains("VALUES (1, ?)"));
        assertFalse("未勾选列（name）的输入值不应进入 SQL", panel.editorForTest().getDocument().getText().contains("张三"));
        // 重新勾选 name → 值保留、按 String 列类型加引号回填
        panel.batchCheckboxForTest(1).doClick();
        assertTrue(panel.editorForTest().getDocument().getText().contains("VALUES (1, '张三', ?)"));
        panel.clear();
    }

    public void testBatchModelFixedColumnHasNoCheckbox() {
        // T-M23-04：UPDATE 的 WHERE 主键为固定列——仅值输入框（无复选框），复选框数 = toggle 列数
        BatchModel model = new BatchModel(BatchKind.UPDATE, "device_info",
                List.of(new BatchColumn("name", "entity.name", true, true)),
                List.of(new BatchColumn("id", "entity.id", false, true)), null, null, MySqlDialect.INSTANCE);
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(batchResult(model));
        assertNotNull("toggle 列应有复选框", panel.batchCheckboxForTest(0));
        assertNull("fixed 列不应有复选框（复选框仅 toggle 列）", panel.batchCheckboxForTest(1));
        // 值输入框：toggle(name) + fixed(id) 各一个
        assertEquals("name 值输入框", "#entity.name", panel.paramFieldForTest(0).getToolTipText());
        assertEquals("id 值输入框", "#entity.id", panel.paramFieldForTest(1).getToolTipText());
        assertTrue(panel.editorForTest().getDocument().getText().contains("SET name = ?"));
        assertTrue(panel.editorForTest().getDocument().getText().contains("WHERE id = ?"));
        panel.clear();
    }

    public void testBatchModelSelectAllNoneButtons() {
        // T-M23.2（用户要求：参数可选场景必须有【全选/全不选】按钮）
        BatchModel model = new BatchModel(BatchKind.INSERT, "biz_store_coupon",
                List.of(
                        new BatchColumn("id", "entity.id", false, false),
                        new BatchColumn("name", "entity.name", true, true),
                        new BatchColumn("dic_status", "entity.dic_status", false, true)),
                List.of(), null, null, MySqlDialect.INSTANCE);
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(batchResult(model));
        assertNotNull("批量区应有全选按钮", panel.batchSelectAllButtonForTest());
        assertNotNull("批量区应有全不选按钮", panel.batchSelectNoneButtonForTest());
        // 全不选 → 空列清单（文档化边界）
        panel.batchSelectNoneButtonForTest().doClick();
        assertFalse(panel.batchCheckboxForTest(0).isSelected);
        assertFalse(panel.batchCheckboxForTest(1).isSelected);
        assertFalse(panel.batchCheckboxForTest(2).isSelected);
        assertTrue(panel.editorForTest().getDocument().getText().contains("INSERT INTO biz_store_coupon ()"));
        // 全选 → 全列恢复
        panel.batchSelectAllButtonForTest().doClick();
        assertTrue(panel.batchCheckboxForTest(0).isSelected);
        assertTrue(panel.batchCheckboxForTest(1).isSelected);
        assertTrue(panel.batchCheckboxForTest(2).isSelected);
        assertTrue(
                panel.editorForTest().getDocument().getText().contains("INSERT INTO biz_store_coupon (id, name, dic_status)"));
        panel.clear();
    }

    public void testInListMultiValueBackfill() {
        // M19.6（用户实测）：IN 列表占位符回填按逗号分割多值、每个值独立引号
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(dynamicResult(
                List.of(
                        new Condition.In(new ColumnRef(null, "name", null, null, null, null, null), false,
                                List.of(new SqlValue.Placeholder("names")),
                                new ConditionToggle("c0", null, true, "name IN (?)", "names", List.of("names")))),
                List.of(
                        new ConditionView("c0", "name IN (?)", null, true, "names", List.of("names")))));
        panel.conditionValueFieldForTest(0).setText("张三,李四");
        assertTrue(panel.editorForTest().getDocument().getText().contains("name IN ('张三', '李四')"));
        // 单值输入不受影响（String 列恒引号）
        panel.conditionValueFieldForTest(0).setText("王五");
        assertTrue(panel.editorForTest().getDocument().getText().contains("name IN ('王五')"));
        panel.clear();
    }

    // ------------------------------------------------------------------
    // M17：执行 SQL
    // ------------------------------------------------------------------

    public void testExecuteSqlWithoutDatabasePluginNoThrow() {
        // 测试沙盒无 Database 插件 → 走 Community 降级提示，不抛异常
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result("SELECT id FROM tbl", "SELECT id FROM tbl"));
        panel.executeSql();
        panel.clear();
    }

    public void testExecuteSqlDisabledWithoutProject() {
        // 无项目面板（纯组件预览）：执行无动作（project null 直接返回），不抛异常
        SqlPreviewPanel panel = new SqlPreviewPanel(null);
        panel.showResult(result());
        panel.executeSql();
        panel.clear();
    }

    public void testExecuteSqlBlankSqlNoop() {
        // 空 SQL：不触发执行链路，不抛异常
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result("   ", "   "));
        panel.executeSql();
        panel.clear();
    }

    public void testSplitDividerPaintedAsPanelBackground() {
        // T-M26.1：分隔条绘制应等于面板底色
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        JSplitPane split = panel.splitPaneForTest();
        assertNotNull("应存在左右分栏", split);
        split.setSize(300, 100);
        split.doLayout();
        if (split.getDividerLocation() < 0) split.setDividerLocation(150);
        int loc = split.getDividerLocation();
        assertTrue("分隔条应已布局", loc >= 0);
        ((BasicSplitPaneUI) split.getUI()).getDivider().setBackground(Color.WHITE);
        BufferedImage img = new BufferedImage(300, 100, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics g = img.createGraphics();
        split.paint(g);
        g.dispose();
        Color expected = UIUtil.getPanelBackground();
        int expRgb = new Color(expected.getRed(), expected.getGreen(), expected.getBlue()).getRGB();
        assertEquals("分隔条像素应等于面板底色", expRgb, img.getRGB(loc + split.getDividerSize() / 2, 50));
        panel.clear();
    }

    public void testSelectAllButtonsOnConditionSectionHeader() {
        // T-M26.3：全选/全不选回挂「动态条件」分区标题行右侧
        String longExpr = "stationIds != null and stationIds.size() > 0";
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        JSplitPane split = panel.splitPaneForTest();
        // 先布局再 showResult：截断上限按视口宽度计算
        split.setSize(500, 120);
        split.doLayout();
        if (split.getDividerLocation() < 0) split.setDividerLocation(250);
        split.doLayout();
        ((JBScrollPane) split.getLeftComponent()).doLayout();
        panel.showResult(dynamicResult(
                List.of(
                        new Condition.Simple(new ColumnRef(null, "station_id", null, null, null, null, null), "IN",
                                new SqlValue.Placeholder("stationIds"),
                                new ConditionToggle("c0", "true", true, "station_id IN (?)", "stationIds", List.of("stationIds")))),
                List.of(new ConditionView("c0", longExpr, "true", true, "stationIds", List.of("stationIds")))));
        JBScrollPane scroll = (JBScrollPane) split.getLeftComponent();
        javax.swing.JViewport viewport = scroll.getViewport();
        javax.swing.JButton button = panel.selectAllButtonForTest();
        // 按钮在参数面板内
        assertTrue("按钮应在参数面板内", SwingUtilities.isDescendingFrom(button, viewport.getView()));
        // 布局后按钮仍可见于视口
        split.doLayout();
        layoutTree(scroll);
        int btnLeft = SwingUtilities.convertPoint(button, 0, 0, scroll).x;
        assertTrue("按钮应位于视口内", btnLeft < viewport.getWidth());
        panel.clear();
    }

    /** headless 未显示组件 validate() 不布局——同步 doLayout 递归布局组件树。 */
    private void layoutTree(Container container) {
        container.doLayout();
        for (Component c : container.getComponents()) {
            if (c instanceof Container) {
                layoutTree((Container) c);
            }
        }
    }

    public void testReformatShortcutRegistered() {
        // T-M26.6：格式化快捷键注册
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        assertNotNull("应注册格式化 action", panel.reformatActionForTest());
        panel.clear();
    }

    public void testReformatShortcutFormatsSql() {
        // T-M26.6：触发格式化 action 后 SQL 被格式化
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result("select id from tbl", "select id from tbl"));
        panel.reformatActionForTest().actionPerformed(
                AnActionEvent.createFromAnAction(panel.reformatActionForTest(), null, "test", DataContext.EMPTY_CONTEXT));
        String text = panel.editorForTest().getDocument().getText();
        assertTrue("格式化后关键字应大写：" + text, text.contains("SELECT"));
        assertTrue("格式化后应含换行：" + text, text.contains("\n"));
        panel.clear();
    }

    public void testActionButtonGroupsSwitchByScene() {
        // T-M26.3：按钮组按场景构建
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        // 无参数 → 无按钮
        panel.showResult(result());
        assertNull("无内容时应无全选按钮", panel.selectAllButtonForTest());
        assertNull("无内容时应无批量全选按钮", panel.batchSelectAllButtonForTest());
        // 动态条件 → 条件组
        panel.showResult(dynamicResult(
                List.of(
                        new Condition.Simple(new ColumnRef(null, "id", null, null, null, null, null), "=",
                                new SqlValue.Literal("1"),
                                new ConditionToggle("c0", "true", true, "id = 1", null, List.of()))),
                List.of(new ConditionView("c0", "id = 1", "true", true, null, List.of()))));
        assertNotNull("动态条件场景应有全选按钮", panel.selectAllButtonForTest());
        assertNull("动态条件场景不应有批量全选按钮", panel.batchSelectAllButtonForTest());
        // 批量列 → 批量组
        panel.showResult(batchResult(new BatchModel(BatchKind.INSERT, "biz_store_coupon",
                List.of(new BatchColumn("name", "entity.name", true, true)),
                List.of(), null, null, MySqlDialect.INSTANCE)));
        assertNull("批量场景不应有全选按钮", panel.selectAllButtonForTest());
        assertNotNull("批量场景应有批量全选按钮", panel.batchSelectAllButtonForTest());
        panel.clear();
        assertNull("clear 后应无全选按钮", panel.selectAllButtonForTest());
        assertNull("clear 后应无批量全选按钮", panel.batchSelectAllButtonForTest());
    }

    // ------------------------------------------------------------------
    // T-M27.1：SQL 面板补全触发
    // ------------------------------------------------------------------

    public void testPanelEditorWrapsDataProviderForEditor() {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result());
        Editor editor = panel.editorForTest();
        assertNotNull("应挂载编辑器", editor);
        DataProvider provider = findDataProvider(panel);
        assertNotNull("编辑器组件链应有 DataProvider 包装", provider);
        assertEquals("DataProvider 应提供当前编辑器", editor, provider.getData(CommonDataKeys.EDITOR.getName()));
        panel.clear();
    }

    public void testPanelEditorCompletionOffersKeywordsAndColumns() {
        if (Language.findLanguageByID("SQL") == null) return; // 测试环境无 SQL 语言，真实 IDE 才执行补全断言
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result());
        Editor editor = panel.editorForTest();
        editor.getCaretModel().moveToOffset(editor.getDocument().getText().indexOf("FROM") + 5);
        CodeCompletionHandlerBase handler = new CodeCompletionHandlerBase(CompletionType.BASIC, false, false, true);
        handler.invokeCompletion(getProject(), editor, 1, false);
        LookupManager lookupManager = LookupManager.getInstance(getProject());
        assertNotNull("Ctrl+Space 应弹出补全（实际为 null——补全链路失效）", lookupManager.getActiveLookup());
        List<String> items = lookupManager.getActiveLookup().getItems().stream()
                .map(LookupElement::getLookupString).collect(Collectors.toList());
        assertTrue("补全应含关键字 SELECT：" + items, items.contains("SELECT"));
        assertTrue("补全应含关键字 DISTINCT：" + items, items.contains("DISTINCT"));
        assertTrue("FROM 后应含表名 tbl：" + items, items.contains("tbl"));
        lookupManager.hideActiveLookup();
        panel.clear();

        // 列名位：WHERE 后
        SqlPreviewPanel panel2 = new SqlPreviewPanel(getProject());
        panel2.showResult(result("SELECT id FROM tbl WHERE ", "SELECT id FROM tbl"));
        Editor editor2 = panel2.editorForTest();
        editor2.getCaretModel().moveToOffset(editor2.getDocument().getText().indexOf("WHERE") + 6);
        new CodeCompletionHandlerBase(CompletionType.BASIC, false, false, true).invokeCompletion(getProject(), editor2, 1, false);
        LookupManager lookupManager2 = LookupManager.getInstance(getProject());
        assertNotNull("WHERE 后应弹出补全", lookupManager2.getActiveLookup());
        List<String> items2 = lookupManager2.getActiveLookup().getItems().stream()
                .map(LookupElement::getLookupString).collect(Collectors.toList());
        assertTrue("WHERE 后应含列名 id：" + items2, items2.contains("id"));
        assertTrue("WHERE 后应含列名 name：" + items2, items2.contains("name"));
        lookupManager2.hideActiveLookup();
        panel2.clear();
    }

    /** 沿组件树找 DataProvider（编辑器挂载包装）。 */
    private DataProvider findDataProvider(Component component) {
        if (component instanceof DataProvider) return (DataProvider) component;
        if (component instanceof Container) {
            for (Component c : ((Container) component).getComponents()) {
                DataProvider found = findDataProvider(c);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // T-M27.2：面板输入自动弹出补全
    // ------------------------------------------------------------------

    public void testTypingTriggersAutoPopup() {
        if (Language.findLanguageByID("SQL") == null) return;
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result("SELECT id FROM ", "SELECT id FROM tbl"));
        Editor editor = panel.editorForTest();
        int insertOffset = editor.getDocument().getTextLength();
        editor.getCaretModel().moveToOffset(insertOffset);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> editor.getDocument().insertString(insertOffset, " S"));
        editor.getCaretModel().moveToOffset(insertOffset + 2);
        LookupManager lookupManager = LookupManager.getInstance(getProject());
        com.intellij.codeInsight.lookup.LookupEx lookup = lookupManager.getActiveLookup();
        int round = 0;
        while (lookup == null && round < 30) {
            UIUtil.dispatchAllInvocationEvents();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            lookup = lookupManager.getActiveLookup();
            round++;
        }
        assertNotNull("输入字符后应自动弹出补全（实际为 null——自建 autopopup 失效）", lookup);
        List<String> items = lookup.getItems().stream().map(LookupElement::getLookupString).collect(Collectors.toList());
        assertTrue("补全应含关键字 SELECT：" + items, items.contains("SELECT"));
        lookupManager.hideActiveLookup();
        panel.clear();
    }

    public void testTypingTriggersAutoPopupXmlScene() {
        if (Language.findLanguageByID("SQL") == null) return;
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result("SELECT id FROM ", "SELECT id FROM tbl", List.of(), List.of(), null));
        Editor editor = panel.editorForTest();
        int insertOffset = editor.getDocument().getTextLength();
        editor.getCaretModel().moveToOffset(insertOffset);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> editor.getDocument().insertString(insertOffset, " S"));
        editor.getCaretModel().moveToOffset(insertOffset + 2);
        LookupManager lookupManager = LookupManager.getInstance(getProject());
        com.intellij.codeInsight.lookup.LookupEx lookup = lookupManager.getActiveLookup();
        int round = 0;
        while (lookup == null && round < 30) {
            UIUtil.dispatchAllInvocationEvents();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            lookup = lookupManager.getActiveLookup();
            round++;
        }
        assertNotNull("XML 场景（无实体上下文）输入字符后也应自动弹出补全", lookup);
        List<String> items = lookup.getItems().stream().map(LookupElement::getLookupString).collect(Collectors.toList());
        assertTrue("XML 场景应含关键字 SELECT：" + items, items.contains("SELECT"));
        lookupManager.hideActiveLookup();
        panel.clear();
    }

    public void testTypingTriggersAutoPopupXmlSceneWithEntity() {
        if (Language.findLanguageByID("SQL") == null) return;
        // T-M27.3：XML 预览注入实体上下文后——输入应弹**列名**
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result("SELECT i FROM ", "SELECT i FROM tbl", List.of(), List.of("id", "name"), null));
        Editor editor = panel.editorForTest();
        int insertOffset = editor.getDocument().getTextLength();
        editor.getCaretModel().moveToOffset(insertOffset);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> editor.getDocument().insertString(insertOffset, " S"));
        editor.getCaretModel().moveToOffset(insertOffset + 2);
        LookupManager lookupManager = LookupManager.getInstance(getProject());
        com.intellij.codeInsight.lookup.LookupEx lookup = lookupManager.getActiveLookup();
        int round = 0;
        while (lookup == null && round < 30) {
            UIUtil.dispatchAllInvocationEvents();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            lookup = lookupManager.getActiveLookup();
            round++;
        }
        assertNotNull("XML 预览（注入实体）输入字符后应弹出补全", lookup);
        List<String> items = lookup.getItems().stream().map(LookupElement::getLookupString).collect(Collectors.toList());
        assertTrue("应含关键字 SELECT：" + items, items.contains("SELECT"));
        lookupManager.hideActiveLookup();
        panel.clear();
    }

    public void testRenderAutoFormatsSql() {
        // T-M27.4：渲染自动格式化（设置开启）
        PluginSettings.getInstance().getState().setFormatSql(true);
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result("select  id ,name  from t  where id=1", "select id,name from t where id=1"));
        String doc = panel.editorForTest().getDocument().getText();
        // T-M29.2：列清单逗号保留
        assertEquals(
                "SELECT\n    id,\n    name\nFROM t\nWHERE id = 1",
                doc);
        panel.clear();
        panel.clear();
    }

    public void testTableNameCompletionInWhereClauseWithCandidates() {
        if (Language.findLanguageByID("SQL") == null) return;
        // T-M27.4：表名全位置提示
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result("SELECT id FROM t WHERE ", "SELECT id FROM t WHERE ",
                List.of(), List.of("id"), "t",
                List.of(new TableInfo("MerchantEntity", "merchant_entity", List.of(), null, null, null, List.of()))));
        Editor editor = panel.editorForTest();
        int insertOffset = editor.getDocument().getTextLength();
        editor.getCaretModel().moveToOffset(insertOffset);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> editor.getDocument().insertString(insertOffset, "me"));
        editor.getCaretModel().moveToOffset(insertOffset + 2);
        LookupManager lookupManager = LookupManager.getInstance(getProject());
        com.intellij.codeInsight.lookup.LookupEx lookup = lookupManager.getActiveLookup();
        int round = 0;
        while (lookup == null && round < 30) {
            UIUtil.dispatchAllInvocationEvents();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            lookup = lookupManager.getActiveLookup();
            round++;
        }
        assertNotNull("WHERE 后输入表名前缀应弹出补全", lookup);
        List<String> items = lookup.getItems().stream().map(LookupElement::getLookupString).collect(Collectors.toList());
        assertTrue("应含联表候选表名 merchant_entity：" + items, items.contains("merchant_entity"));
        lookupManager.hideActiveLookup();
        panel.clear();
    }
}
