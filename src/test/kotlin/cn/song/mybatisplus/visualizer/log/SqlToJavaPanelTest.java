package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.model.AssociationInfo;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator;
import cn.song.mybatisplus.visualizer.reverse.SqlReverseResult;
import cn.song.mybatisplus.visualizer.reverse.SqlToJavaConverter;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.icons.AllIcons;
import com.intellij.ide.util.ChooseByNameModel;
import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiClass;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;
import org.junit.Assert.assertEquals;
import org.junit.Assert.assertFalse;
import org.junit.Assert.assertNotNull;
import org.junit.Assert.assertNull;
import org.junit.Assert.assertTrue;
import org.junit.Test;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.GridBagLayout;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

/**
 * 「SQL → Java」tab 面板行为测试（T-M36.4；T-M37 P1 扩展）。
 *
 * Light fixture 下项目无实体类 → 实体查找返回空 → 回落字符串列名 QueryWrapper 路径可测；
 * 异步路径（AUTO 形态后台查找实体）用轮询等待（UIUtil.dispatchAllInvocationEvents +
 * 超时兜底）；QUERY 形态为同步路径（直测）。
 *
 * 覆盖边界说明（T-M37 P1）：
 *  - 副表多候选弹 [SqlSelectionDialog] 的 UI 交互在 Light 下无法真实弹出（无实体 → 候选恒空），
 *    面板级仅覆盖候选为空 → 回落路径；多候选逻辑（findSubEntityCandidates 候选列表/别名键）在
 *    converter 层直测（本项目测试工程无实体类，候选列表恒为空——弹窗分支属纯 UI 分发，靠代码走查）。
 */
public class SqlToJavaPanelTest extends BasePlatformTestCase {

    private SqlToJavaPanel panel;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        panel = new SqlToJavaPanel(getProject());
    }

    @Override
    protected void tearDown() throws Exception {
        panel.dispose();
        super.tearDown();
    }

    private String awaitResult() throws Exception {
        String result = panel.resultTextForTest();
        int waited = 0;
        while (result.isBlank() && waited < 100) {
            UIUtil.dispatchAllInvocationEvents();
            Thread.sleep(50);
            result = panel.resultTextForTest();
            waited++;
        }
        return result;
    }

    /** 等待结果区包含目标文本（区分新旧结果——切换形态重生成的异步路径）。 */
    private String awaitContains(String substring) throws Exception {
        String result = panel.resultTextForTest();
        int waited = 0;
        while (!result.contains(substring) && waited < 100) {
            UIUtil.dispatchAllInvocationEvents();
            Thread.sleep(50);
            result = panel.resultTextForTest();
            waited++;
        }
        return result;
    }

    @Test
    public void testConvertNoEntityFallsBackToQueryWrapper() {
        // 手动选择开关关闭 → 0 候选直接回落（开关开启的弹窗路径由 EntitySelectionDialogTest 覆盖）
        PluginSettings.State state = PluginSettings.getInstance().getState();
        boolean original = state.getReverseManualEntitySelection();
        state.setReverseManualEntitySelection(false);
        try {
            panel.inputEditorForTest().setSql("SELECT id, name FROM user WHERE name = '张三'");
            panel.convertForTest();
            String result = awaitResult();
            assertTrue(result, result.contains("new QueryWrapper<User>()"));
            assertTrue(result, result.contains(".eq(\"name\", \"张三\")"));
            assertTrue(panel.statusForTest(), panel.statusForTest().contains("未找到对应实体"));
        } finally {
            state.setReverseManualEntitySelection(original);
        }
    }

    @Test
    public void testConvertParseErrorShowsStatus() {
        panel.inputEditorForTest().setSql("SELECT * FROM t WHERE a = 'abc");
        panel.convertForTest();
        String result = awaitResult();
        assertEquals("", result);
        assertTrue(panel.statusForTest(), panel.statusForTest().contains("无法解析"));
    }

    @Test
    public void testConvertQueryFormatSyncPath() {
        // QUERY 形态：跳过实体查找（同步路径直测）
        PluginSettings.State state = PluginSettings.getInstance().getState();
        String original = state.getReverseWrapperFormat();
        state.setReverseWrapperFormat(PluginSettings.REVERSE_QUERY);
        try {
            panel.inputEditorForTest().setSql("SELECT * FROM user WHERE age > 18");
            panel.convertForTest();
            String result = panel.resultTextForTest();
            assertTrue(result, result.contains("new QueryWrapper<User>()"));
            assertTrue(result, result.contains(".gt(\"age\", 18)"));
        } finally {
            state.setReverseWrapperFormat(original);
        }
    }

    @Test
    public void testFormatInputSql() {
        String raw = "SELECT * FROM user WHERE a = 1 AND b = 2";
        panel.inputEditorForTest().setSql(raw);
        // 格式化按钮走 FormatAction——直接验证 SqlFormatter 幂等接入（按钮触发同路径）
        String formatted = SqlFormatter.format(raw);
        panel.inputEditorForTest().setSql(formatted);
        panel.convertForTest();
        String result = awaitResult();
        assertTrue(result, result.contains("new QueryWrapper<User>()"));
    }

    @Test
    public void testPanelDisposeReleasesEditors() {
        // dispose 幂等（重复调用不抛）
        panel.dispose();
        panel.dispose();
    }

    @Test
    public void testBundleKeysPresent() {
        // tab 标题与按钮文案 bundle key 存在（防拼写回归）
        assertTrue(MpSqlVisualizerBundle.message("log.tab.sqlToJava").isBlank() == false);
        assertTrue(MpSqlVisualizerBundle.message("reverse.panel.convert").isBlank() == false);
        assertTrue(MpSqlVisualizerBundle.message("reverse.panel.status.noEntity").isBlank() == false);
        assertTrue(MpSqlVisualizerBundle.message("reverse.panel.copy").isBlank() == false);
        assertTrue(MpSqlVisualizerBundle.message("reverse.panel.formatCode").isBlank() == false);
        assertTrue(MpSqlVisualizerBundle.message("reverse.copy.done").isBlank() == false);
        // T-M37 P1：日志列表右键「发送到 SQL → Java 转换」key（中英 bundle 均存在）
        assertTrue(MpSqlVisualizerBundle.message("log.panel.sendToReverse").isBlank() == false);
    }

    // ------------------------------------------------------------------
    // 日志列表联动 setInputSql（T-M37 P1）
    // ------------------------------------------------------------------

    @Test
    public void testSetInputSqlWritesInputArea() {
        String sql = "SELECT id FROM user WHERE id = 1";
        panel.setInputSql(sql);
        // 写入输入区 + 清空结果区与状态行提示
        assertEquals(sql, panel.inputEditorForTest().getText());
        assertEquals("", panel.resultTextForTest());
        assertEquals(" ", panel.statusForTest());
    }

    @Test
    public void testSetInputSqlThenConvertGeneratesResult() {
        // 发送 SQL 后用户点「转换」→ 完整链路产出（输入区已写入，无需再手动 setSql）
        panel.setInputSql("SELECT id, name FROM user WHERE name = '张三'");
        panel.convertForTest();
        String result = awaitResult();
        assertTrue(result, result.contains("new QueryWrapper<User>()"));
        assertTrue(result, result.contains(".eq(\"name\", \"张三\")"));
    }

    // ------------------------------------------------------------------
    // 副表多候选（T-M37 P1）——Light 无实体 → 回落路径 + converter 层直测
    // ------------------------------------------------------------------

    @Test
    public void testConvertJoinFallsBackToMpjQueryWithoutEntities() {
        // 无实体：手动选择开关关闭 → 副表候选为空 → 跳过（该表列回落字符串），主表也无候选 → MPJ_QUERY raw join
        PluginSettings.State state = PluginSettings.getInstance().getState();
        boolean original = state.getReverseManualEntitySelection();
        state.setReverseManualEntitySelection(false);
        try {
            panel.inputEditorForTest().setSql("SELECT t.id, t1.city FROM user t LEFT JOIN address t1 ON t1.user_id = t.id WHERE t.status = 1");
            panel.convertForTest();
            String result = awaitResult();
            assertTrue(result, result.contains("new MPJQueryWrapper<>"));
            assertTrue(result, result.contains("leftJoin(\"address t1 ON t1.user_id = t.id\")"));
        } finally {
            state.setReverseManualEntitySelection(original);
        }
    }

    @Test
    public void testFindSubEntityCandidatesKeysByAlias() {
        SqlReverseResult result = SqlToJavaConverter.parse(
            "SELECT t.id, t1.city FROM user t LEFT JOIN address t1 ON t1.user_id = t.id");
        assertEquals(1, result.joins().size());
        Map<String, List<SqlToJavaConverter.EntityCandidate>> candidates = ReadAction.compute(() ->
            SqlToJavaConverter.findSubEntityCandidates(getProject(), result.joins()));
        // 别名键存在（Light 无实体 → 空列表；面板层据此回落字符串列名）
        assertTrue(candidates.containsKey("t1"));
        assertTrue(candidates.get("t1").isEmpty());
        // 向后兼容：findSubEntities 仍返回同语义结果（空副实体表）
        Map<String, JavaCodeGenerator.EntityTarget> sub = ReadAction.compute(() ->
            SqlToJavaConverter.findSubEntities(getProject(), result.joins()));
        assertTrue(sub.isEmpty());
    }

    @Test
    public void testFindSubEntityCandidatesSkipsNoAliasJoin() {
        // 无别名的 join 不归属（P1 默认别名规则）→ 结果 map 为空
        SqlReverseResult result = SqlToJavaConverter.parse(
            "SELECT t.id, a.city FROM user t LEFT JOIN address ON a.user_id = t.id");
        assertTrue(result.joins().isEmpty() == false);
        Map<String, List<SqlToJavaConverter.EntityCandidate>> candidates = ReadAction.compute(() ->
            SqlToJavaConverter.findSubEntityCandidates(getProject(), result.joins()));
        assertTrue(candidates.isEmpty());
    }

    // ------------------------------------------------------------------
    // T-M49：唯一可推断时跳过实体选择弹窗
    // ------------------------------------------------------------------

    @Test
    public void testCanAutoInferUniqueMainAndSingleSub() {
        // 主表 1 候选 + 每个副表各 1 候选 → 唯一可推断 → 跳过弹窗
        List<SqlToJavaConverter.EntityCandidate> mainCandidates = List.of(mockCandidate("User"));
        Map<String, List<SqlToJavaConverter.EntityCandidate>> subCandidates = Map.of(
            "t1", List.of(mockCandidate("Address")),
            "t2", List.of(mockCandidate("Order")));
        assertTrue(
            "主表 1 候选 + 所有副表各 1 候选应唯一可推断",
            new SqlToJavaPanel(getProject()).canAutoInferEntitySelection(mainCandidates, subCandidates));
    }

    @Test
    public void testCanAutoInferMultipleMainCandidates() {
        // 主表多候选 → 有歧义 → 弹窗
        List<SqlToJavaConverter.EntityCandidate> mainCandidates = List.of(mockCandidate("User"), mockCandidate("UserEntity"));
        Map<String, List<SqlToJavaConverter.EntityCandidate>> subCandidates = Map.of("t1", List.of(mockCandidate("Address")));
        assertTrue(
            "主表多候选应有歧义（弹窗）",
            !new SqlToJavaPanel(getProject()).canAutoInferEntitySelection(mainCandidates, subCandidates));
    }

    @Test
    public void testCanAutoInferMultipleSubCandidates() {
        // 副表多候选 → 有歧义 → 弹窗
        List<SqlToJavaConverter.EntityCandidate> mainCandidates = List.of(mockCandidate("User"));
        Map<String, List<SqlToJavaConverter.EntityCandidate>> subCandidates = Map.of(
            "t1", List.of(mockCandidate("Address"), mockCandidate("AddressEntity")));
        assertTrue(
            "副表多候选应有歧义（弹窗）",
            !new SqlToJavaPanel(getProject()).canAutoInferEntitySelection(mainCandidates, subCandidates));
    }

    @Test
    public void testCanAutoInferEmptySubCandidates() {
        // 副表 0 候选 → 不可推断 → 弹窗
        List<SqlToJavaConverter.EntityCandidate> mainCandidates = List.of(mockCandidate("User"));
        Map<String, List<SqlToJavaConverter.EntityCandidate>> subCandidates = Map.of(
            "t1", List.<SqlToJavaConverter.EntityCandidate>of());
        assertTrue(
            "副表 0 候选应弹窗",
            !new SqlToJavaPanel(getProject()).canAutoInferEntitySelection(mainCandidates, subCandidates));
    }

    @Test
    public void testCanAutoInferNoSubTables() {
        // 主表 1 候选 + 无副表（空 map）→ all 对空集合返回 true → 唯一可推断
        List<SqlToJavaConverter.EntityCandidate> mainCandidates = List.of(mockCandidate("User"));
        assertTrue(
            "主表 1 候选 + 无副表应唯一可推断",
            new SqlToJavaPanel(getProject()).canAutoInferEntitySelection(mainCandidates, Map.of()));
    }

    /** 构造轻量 EntityCandidate（Proxy PsiClass 仅 name 有值；判定逻辑不触 PSI）。 */
    private static SqlToJavaConverter.EntityCandidate mockCandidate(String className) {
        PsiClass psiClass = (PsiClass) Proxy.newProxyInstance(
            PsiClass.class.getClassLoader(),
            new Class[]{PsiClass.class},
            (InvocationHandler) (proxy, method, args) -> "name".equals(method.getName()) ? className : null);
        return new SqlToJavaConverter.EntityCandidate(
            psiClass,
            new TableInfo(className, "user", List.<ColumnInfo>of(), null, null, null, List.<AssociationInfo>of()));
    }

    // ------------------------------------------------------------------
    // 形态下拉（T-M36.6）
    // ------------------------------------------------------------------

    @Test
    public void testOptionsRowUsesStretchLayout() {
        // T-M39 增量：选项（形态下拉/调用类/VO）独立成行 GridBag 拉伸——
        // 防回退到原 BorderLayout.EAST + FlowLayout 三控件挤一行（窄窗口下拉被挤压截断）
        javax.swing.JComboBox<?> combo = panel.formatComboForTest();
        Container row = combo.getParent();
        assertTrue("选项行应为 GridBag 拉伸布局（窄窗口各自收缩互不挤压）", row.getLayout() instanceof GridBagLayout);
        assertEquals("调用类输入框应位于同一选项行", row, panel.callerFieldForTest().getParent());
        assertEquals("VO 输入框应位于同一选项行", row, panel.voFieldForTest().getParent());
    }

    @Test
    public void testFormatComboCellTooltipShowsFullLabel() {
        // T-M39 五次增量：弹出列表长选项截断时 hover 应显示完整文案（JList cell renderer tooltip）
        javax.swing.JComboBox<?> combo = panel.formatComboForTest();
        ListCellRenderer<Object> renderer = (ListCellRenderer<Object>) combo.getCellRenderer();
        for (int i = 0; i < combo.getModel().getSize(); i++) {
            Object option = combo.getModel().getElementAt(i);
            // DefaultListCellRenderer 要求非 null list（读 componentOrientation）
            JComponent cell = (JComponent) renderer.getListCellRendererComponent(new JList<>(), option, i, false, false);
            assertEquals("第 " + i + " 项 tooltip 应为完整文案", option.toString(), cell.getToolTipText());
        }
    }

    @Test
    public void testFormatComboTooltipTracksSelection() {
        // 选中态（关闭态）截断时 hover 显示完整文案——combo tooltip 跟随选中项
        javax.swing.JComboBox<?> combo = panel.formatComboForTest();
        assertEquals("初始选中项 tooltip 应为完整文案", combo.getSelectedItem().toString(), combo.getToolTipText());
        combo.setSelectedItem(combo.getModel().getElementAt(combo.getModel().getSize() - 1));
        assertEquals("切换后 tooltip 应跟随", combo.getSelectedItem().toString(), combo.getToolTipText());
    }

    @Test
    public void testClassPickerPlaceholderTooltip() {
        // 占位文案截断时 hover 显示完整提示；选中后不遮蔽字段级全限定名 tooltip
        ClassPickerField caller = panel.callerFieldForTest();
        assertNull(caller.getToolTipText());
        assertEquals(
            "占位态文本标签 tooltip 应为完整占位文案",
            MpSqlVisualizerBundle.message("reverse.panel.callerHint"),
            caller.textTooltipForTest());
        caller.pickForTest("UserService", "com.demo.UserService", null);
        assertNull("选中态文本标签不应有 tooltip（字段级全限定名不遮蔽）", caller.textTooltipForTest());
    }

    @Test
    public void testFormatComboInitialFromSettings() {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        String original = state.getReverseWrapperFormat();
        try {
            state.setReverseWrapperFormat(PluginSettings.REVERSE_WRAPPERS);
            // 面板重建后下拉应反映设置值（创建时 refreshFormatFromSettings）
            SqlToJavaPanel panel2 = new SqlToJavaPanel(getProject());
            try {
                assertEquals(PluginSettings.REVERSE_WRAPPERS, panel2.selectedFormatForTest());
            } finally {
                panel2.dispose();
            }
        } finally {
            state.setReverseWrapperFormat(original);
        }
    }

    @Test
    public void testFormatComboWriteThroughAndRegenerate() {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        String original = state.getReverseWrapperFormat();
        try {
            state.setReverseWrapperFormat(PluginSettings.REVERSE_QUERY);
            panel.refreshFormatFromSettings();
            assertEquals(PluginSettings.REVERSE_QUERY, panel.selectedFormatForTest());

            panel.inputEditorForTest().setSql("SELECT id, name FROM user WHERE name = '张三'");
            panel.convertForTest();
            String queryResult = panel.resultTextForTest();
            assertTrue(queryResult, queryResult.contains("new QueryWrapper<User>()"));

            // 模拟用户下拉选择 WRAPPERS → 写穿设置 + 立即重生成（无实体 → Wrappers.query 字符串形态）
            panel.selectFormatForTest(PluginSettings.REVERSE_WRAPPERS);
            assertEquals(PluginSettings.REVERSE_WRAPPERS, state.getReverseWrapperFormat());
            String wrappersResult = awaitContains("Wrappers.<User>query()");
            assertTrue(wrappersResult, wrappersResult.contains(".eq(\"name\", \"张三\")"));

            // 再切 LAMBDA（无实体 → 回落 new QueryWrapper；无候选缓存 → 异步完整流程）
            panel.selectFormatForTest(PluginSettings.REVERSE_LAMBDA);
            assertEquals(PluginSettings.REVERSE_LAMBDA, state.getReverseWrapperFormat());
            String lambdaResult = awaitContains("new QueryWrapper<User>()");
            assertTrue(lambdaResult, lambdaResult.contains(".eq(\"name\", \"张三\")"));
        } finally {
            state.setReverseWrapperFormat(original);
        }
    }

    // ------------------------------------------------------------------
    // 调用类 / VO（T-M36.7；T-M39 增量改类选择器）
    // ------------------------------------------------------------------

    @Test
    public void testClassPickerSelectAndClear() {
        ClassPickerField caller = panel.callerFieldForTest();
        // 初始：空值 → 占位提示 + 无类图标
        assertEquals("", caller.getText());
        assertNull("空值不应显示类图标", caller.iconForTest());
        // 模拟 Goto Class 选择器选中（类图标 + 简单类名 + tooltip 全限定名）
        caller.pickForTest("UserService", "com.demo.UserService", null);
        assertEquals("UserService", caller.getText());
        assertEquals("com.demo.UserService", caller.getToolTipText());
        assertNotNull("选中后应显示类图标（IDEA 原生观感）", caller.iconForTest());
        // 模拟 ✕ 清除
        caller.clearForTest();
        assertEquals("", caller.getText());
        assertNull(caller.getToolTipText());
        assertNull(caller.iconForTest());
    }

    @Test
    public void testClassPickerWholeFieldClickable() {
        // 整块可点：iconLabel/textLabel/按钮宿主均挂鼠标监听 + 手型光标（Swing 事件不冒泡——
        // 仅挂父面板时子组件区域点击失效，用户反馈「只能点三点」）
        List<JComponent> targets = panel.pickerClickTargetsForTest();
        assertEquals("可点击子组件应为 3 个（图标 + 文本 + 按钮宿主）", 3, targets.size());
        for (JComponent target : targets) {
            assertTrue(target.getClass().getSimpleName() + " 应挂鼠标监听", target.getMouseListeners().length > 0);
            assertEquals(
                target.getClass().getSimpleName() + " 应有手型光标",
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR).getType(),
                target.getCursor().getType());
        }
        // 清除按钮 12×12 紧凑尺寸（16px 偏大反馈）
        JButton button = panel.pickerActionButtonForTest();
        assertEquals(12, button.getPreferredSize().width);
        assertEquals(12, button.getPreferredSize().height);
        assertEquals(12, button.getIcon().getIconWidth());
    }

    @Test
    public void testHintLabelsShowWhenEmptyAndHideOnContent() {
        // 空文档 → 提示可见且灰字；输入 SQL 后 → 隐藏（结果区同理）
        assertTrue("初始输入区空 → 提示应可见", panel.inputHintLabelForTest().isVisible());
        assertTrue("初始结果区空 → 提示应可见", panel.resultHintLabelForTest().isVisible());
        panel.inputEditorForTest().setSql("SELECT id FROM user");
        assertTrue("输入 SQL 后输入区提示应隐藏", !panel.inputHintLabelForTest().isVisible());
        assertTrue("结果区仍空 → 提示保持可见", panel.resultHintLabelForTest().isVisible());
        panel.inputEditorForTest().setSql("");
        assertTrue("清空输入 → 提示恢复可见", panel.inputHintLabelForTest().isVisible());
    }

    @Test
    public void testClassPickerSelectionRegenerates() {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        String original = state.getReverseWrapperFormat();
        try {
            state.setReverseWrapperFormat(PluginSettings.REVERSE_QUERY);
            panel.refreshFormatFromSettings();
            panel.inputEditorForTest().setSql("SELECT id, name FROM user WHERE name = '张三'");
            panel.convertForTest();
            assertTrue(panel.resultTextForTest().contains("new QueryWrapper"));
            // 选中调用类 → 自动重新生成（已有转换结果时；含调用语句）
            panel.callerFieldForTest().pickForTest("UserService", "com.demo.UserService", null);
            String result = panel.resultTextForTest();
            assertTrue(result, result.contains("userService.list"));
            // 清除 → 回到纯表达式
            panel.callerFieldForTest().clearForTest();
            String plain = panel.resultTextForTest();
            assertTrue(plain, !plain.contains("UserService"));
        } finally {
            state.setReverseWrapperFormat(original);
        }
    }

    @Test
    public void testCallerAndVoGenerateInvocation() {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        String original = state.getReverseWrapperFormat();
        try {
            state.setReverseWrapperFormat(PluginSettings.REVERSE_QUERY);
            panel.refreshFormatFromSettings();

            panel.inputEditorForTest().setSql("SELECT id, name FROM user WHERE name = '张三'");
            // 调用类输入（测试访问器直接写文本框）
            panel.callerFieldForTest().setText("UserService");
            panel.voFieldForTest().setText("UserVo");
            panel.convertForTest();
            String result = panel.resultTextForTest();
            // Light 环境无 MPJ 接口 → 调用类无 MPJ 能力 → VO 回落实体查询并注释（严禁生成不存在的 API）
            assertTrue(result, result.contains("QueryWrapper<User> wrapper = new QueryWrapper<User>()"));
            assertTrue(result, result.contains("List<User> list = userService.list(wrapper);"));
            assertTrue(result, result.contains("// 调用类无 MyBatis-Plus-Join（MPJ）能力，VO 查询不可用，已回落实体查询"));
            assertTrue(result, !result.contains("selectJoinList"));

            // 清空 VO → 普通 list 调用
            panel.voFieldForTest().setText("");
            panel.convertForTest();
            String plain = panel.resultTextForTest();
            assertTrue(plain, plain.contains("List<User> list = userService.list(wrapper);"));
        } finally {
            state.setReverseWrapperFormat(original);
        }
    }

    @Test
    public void testCallerWithMpjInjectedGeneratesSelectJoinList() throws Exception {
        // MPJ 能力真实解析依赖索引（Light 不可测，见 InvocationMpjHeavyTest）——此处注入验证面板接线：
        // callerMpj=true → buildInvocation 携带 mpj → VO 查询生成 selectJoinList + MPJ wrapper
        PluginSettings.State state = PluginSettings.getInstance().getState();
        String original = state.getReverseWrapperFormat();
        try {
            state.setReverseWrapperFormat(PluginSettings.REVERSE_QUERY);
            panel.refreshFormatFromSettings();
            panel.inputEditorForTest().setSql("SELECT id, name FROM user WHERE name = '张三'");
            panel.callerFieldForTest().setText("UserService");
            panel.voFieldForTest().setText("UserVo");
            panel.setCallerMpjForTest(true);
            panel.convertForTest();
            String result = awaitResult();
            assertTrue(result, result.contains("new MPJQueryWrapper<>(User.class)"));
            assertTrue(result, result.contains("List<UserVo> list = userService.selectJoinList(UserVo.class, wrapper);"));
        } finally {
            state.setReverseWrapperFormat(original);
            panel.callerFieldForTest().setText("");
            panel.voFieldForTest().setText("");
            panel.setCallerMpjForTest(false);
        }
    }

    @Test
    public void testPagedQueryGeneratesPageCall() throws Exception {
        // 分页 SQL（LIMIT ?, ?）+ 调用类 → 生成 page 调用（不再 last + list）
        PluginSettings.State state = PluginSettings.getInstance().getState();
        String original = state.getReverseWrapperFormat();
        try {
            state.setReverseWrapperFormat(PluginSettings.REVERSE_QUERY);
            panel.refreshFormatFromSettings();
            panel.inputEditorForTest().setSql("SELECT * FROM user WHERE id = 1 LIMIT ?, ?");
            panel.callerFieldForTest().setText("UserService");
            panel.convertForTest();
            String result = awaitResult();
            assertTrue(result, result.contains("Page<User> page = userService.page(new Page<>(1, 10), wrapper);"));
            assertTrue(result, !result.contains(".last(\"LIMIT"));
        } finally {
            state.setReverseWrapperFormat(original);
            panel.callerFieldForTest().setText("");
        }
    }

    // ------------------------------------------------------------------
    // T-M45：复制剥离注释 + 格式化代码（结果区工具栏）
    // ------------------------------------------------------------------

    @Test
    public void testCopyTextStripsComments() throws Exception {
        // 转换含说明注释的代码 → 复制文本 = 纯 Java 代码（无 // 注释行）
        PluginSettings.State state = PluginSettings.getInstance().getState();
        String original = state.getReverseWrapperFormat();
        try {
            state.setReverseWrapperFormat(PluginSettings.REVERSE_QUERY);
            panel.refreshFormatFromSettings();
            panel.inputEditorForTest().setSql("SELECT * FROM user WHERE id = 1 LIMIT ?, ?");
            panel.callerFieldForTest().setText("UserService");
            panel.convertForTest();
            String result = awaitContains("分页 current/size 已占位");
            // 结果区显示仍带注释（行注释形态——独立行）
            assertTrue(result, result.contains("// 分页 current/size 已占位，请替换为实际参数"));
            // 复制文本剥离全部注释，保留代码
            String copy = panel.copyTextForTest();
            assertFalse(copy, copy.contains("//"));
            assertTrue(copy, copy.contains("Page<User> page = userService.page(new Page<>(1, 10), wrapper);"));
        } finally {
            state.setReverseWrapperFormat(original);
            panel.callerFieldForTest().setText("");
        }
    }

    @Test
    public void testFormatCodeFormatsResult() throws Exception {
        // 结果区内容经「格式化代码」后代码完整保留；连续格式化结果稳定（幂等）
        PluginSettings.State state = PluginSettings.getInstance().getState();
        String original = state.getReverseWrapperFormat();
        try {
            state.setReverseWrapperFormat(PluginSettings.REVERSE_QUERY);
            panel.refreshFormatFromSettings();
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= 30; i++) {
                if (i > 1) sb.append(", ");
                sb.append("c").append(i);
            }
            panel.inputEditorForTest().setSql("SELECT " + sb + " FROM user");
            panel.convertForTest();
            String result = awaitResult();
            assertTrue(result, result.contains(".select(\"c1\", \"c2\""));
            panel.formatCodeForTest();
            String formatted = panel.resultTextForTest();
            // 格式化不丢代码（wrapper 构造 + select 链完整）
            assertTrue(formatted, formatted.contains("new QueryWrapper<User>()"));
            assertTrue(formatted, formatted.contains(".select(\"c1\""));
            // 幂等：再格式化结果不变
            panel.formatCodeForTest();
            assertEquals(formatted, panel.resultTextForTest());
        } finally {
            state.setReverseWrapperFormat(original);
        }
    }

    // ------------------------------------------------------------------
    // T-BatchSql：批量 SQL → Java 转换（面板 fixture）
    // ------------------------------------------------------------------

    @Test
    public void testBatchConvertTwoStatementsShowsTwoCodeBlocks() throws Exception {
        // 2 条有效 SQL → 结果区显示 2 段代码（双换行分隔）
        PluginSettings.State state = PluginSettings.getInstance().getState();
        String originalFormat = state.getReverseWrapperFormat();
        boolean originalManual = state.getReverseManualEntitySelection();
        state.setReverseWrapperFormat(PluginSettings.REVERSE_QUERY);
        state.setReverseManualEntitySelection(false);
        try {
            panel.inputEditorForTest().setSql("SELECT id, name FROM user WHERE name = '张三'; SELECT id FROM user WHERE age > 18");
            panel.convertForTest();
            String result = awaitResult();
            assertTrue(result, result.contains("new QueryWrapper<User>()"));
            int wrapperCount = result.split("new QueryWrapper", -1).length - 1;
            assertEquals("应生成 2 个 QueryWrapper", 2, wrapperCount);
        } finally {
            state.setReverseWrapperFormat(originalFormat);
            state.setReverseManualEntitySelection(originalManual);
        }
    }

    @Test
    public void testBatchConvertPartialFailureShowsStatus() throws Exception {
        // 1 有效 + 1 无效 → 状态行显示「1/2 条成功」
        PluginSettings.State state = PluginSettings.getInstance().getState();
        String originalFormat = state.getReverseWrapperFormat();
        boolean originalManual = state.getReverseManualEntitySelection();
        state.setReverseWrapperFormat(PluginSettings.REVERSE_QUERY);
        state.setReverseManualEntitySelection(false);
        try {
            panel.inputEditorForTest().setSql("SELECT id FROM user WHERE id = 1; INVALID SQL");
            panel.convertForTest();
            String result = awaitResult();
            assertTrue(result, result.contains("new QueryWrapper<User>()"));
            assertTrue(result, result.contains("// 第 2 条 SQL 转换失败"));
            assertTrue(panel.statusForTest(), panel.statusForTest().contains("1/2"));
        } finally {
            state.setReverseWrapperFormat(originalFormat);
            state.setReverseManualEntitySelection(originalManual);
        }
    }

    @Test
    public void testSingleStatementDoesNotEnterBatchPath() throws Exception {
        // 单条 SQL → 走现有单条路径（缓存生效，状态行显示「未找到对应实体」）
        PluginSettings.State state = PluginSettings.getInstance().getState();
        boolean originalManual = state.getReverseManualEntitySelection();
        state.setReverseManualEntitySelection(false);
        try {
            panel.inputEditorForTest().setSql("SELECT id, name FROM user WHERE name = '张三'");
            panel.convertForTest();
            String result = awaitResult();
            assertTrue(result, result.contains("new QueryWrapper<User>()"));
            assertTrue(panel.statusForTest(), panel.statusForTest().contains("未找到对应实体"));
        } finally {
            state.setReverseManualEntitySelection(originalManual);
        }
    }

    // ------------------------------------------------------------------
    // 调用类特殊值（T-M42：this / baseMapper）
    // ------------------------------------------------------------------

    @Test
    public void testClassPickerSpecialValuesPickable() {
        ClassPickerField caller = panel.callerFieldForTest();
        // 特殊项「this」：选中 → 文本 = this（字段图标 + 用途说明 tooltip）
        caller.pickForTest("this", null, AllIcons.Nodes.Field);
        assertEquals("this", caller.getText());
        assertNotNull("特殊项应显示字段图标", caller.iconForTest());
        caller.clearForTest();
        // 特殊项「baseMapper」：同上
        caller.pickForTest("baseMapper", null, AllIcons.Nodes.Field);
        assertEquals("baseMapper", caller.getText());
        assertNotNull("特殊项应显示字段图标", caller.iconForTest());
    }

    @Test
    public void testThisCallerRegeneratesServiceInvocation() {
        // 选中特殊项 this → 已有转换结果自动重生成 service 形态调用（this.list(wrapper)）
        PluginSettings.State state = PluginSettings.getInstance().getState();
        String original = state.getReverseWrapperFormat();
        try {
            state.setReverseWrapperFormat(PluginSettings.REVERSE_QUERY);
            panel.refreshFormatFromSettings();
            panel.inputEditorForTest().setSql("SELECT id, name FROM user WHERE name = '张三'");
            panel.convertForTest();
            assertTrue(panel.resultTextForTest().contains("new QueryWrapper"));
            panel.callerFieldForTest().pickForTest("this", null, AllIcons.Nodes.Field);
            String result = panel.resultTextForTest();
            assertTrue(result, result.contains("List<User> list = this.list(wrapper);"));
            // 清除 → 回到纯表达式
            panel.callerFieldForTest().clearForTest();
            String plain = panel.resultTextForTest();
            assertTrue(plain, !plain.contains("this.list"));
        } finally {
            state.setReverseWrapperFormat(original);
            panel.callerFieldForTest().setText("");
        }
    }

    @Test
    public void testBaseMapperCallerRegeneratesMapperInvocation() {
        // 选中特殊项 baseMapper → 自动重生成 mapper 形态调用（baseMapper.selectList(wrapper)）
        PluginSettings.State state = PluginSettings.getInstance().getState();
        String original = state.getReverseWrapperFormat();
        try {
            state.setReverseWrapperFormat(PluginSettings.REVERSE_QUERY);
            panel.refreshFormatFromSettings();
            panel.inputEditorForTest().setSql("SELECT id, name FROM user WHERE name = '张三'");
            panel.convertForTest();
            assertTrue(panel.resultTextForTest().contains("new QueryWrapper"));
            panel.callerFieldForTest().pickForTest("baseMapper", null, AllIcons.Nodes.Field);
            String result = panel.resultTextForTest();
            assertTrue(result, result.contains("List<User> list = baseMapper.selectList(wrapper);"));
        } finally {
            state.setReverseWrapperFormat(original);
            panel.callerFieldForTest().setText("");
        }
    }

    @Test
    public void testSpecialCallerMpjConsideredAvailableForJoin() throws Exception {
        // 特殊调用值恒视为具备 MPJ 能力（invocationHasMpj 开头纯字符串分支直出 true，不依赖索引）：
        // 面板注入 mpj=true 后 JOIN 生成不报「无 MPJ 能力」
        PluginSettings.State state = PluginSettings.getInstance().getState();
        String original = state.getReverseWrapperFormat();
        try {
            state.setReverseWrapperFormat(PluginSettings.REVERSE_QUERY);
            panel.refreshFormatFromSettings();
            panel.setCallerMpjForTest(true);
            panel.inputEditorForTest().setSql(
                "SELECT t.id, t1.city FROM user t LEFT JOIN address t1 ON t1.user_id = t.id WHERE t.status = 1");
            panel.callerFieldForTest().setText("this");
            panel.convertForTest();
            String result = awaitResult();
            assertTrue(result, result.contains("List<User> list = this.list(wrapper);"));
        } finally {
            state.setReverseWrapperFormat(original);
            panel.callerFieldForTest().setText("");
            panel.setCallerMpjForTest(false);
        }
    }

    @Test
    public void testInvocationHasMpjSpecialValuesShortCircuit() {
        // T-M42：this/baseMapper 非类名，索引反查必然落空——invocationHasMpj 开头纯字符串分支
        // 直出 true（Light 无索引也可验证；普通类名分支依赖索引见 InvocationMpjHeavyTest）
        assertTrue(SqlToJavaConverter.invocationHasMpj(getProject(), "this"));
        assertTrue(SqlToJavaConverter.invocationHasMpj(getProject(), "baseMapper"));
        // isBlank 守卫仍先于特殊分支：空串 false
        assertFalse(SqlToJavaConverter.invocationHasMpj(getProject(), ""));
    }

    @Test
    public void testCallerModelGetFullNameReadActionSafe() {
        // T-M44 增量：ChooseByNamePopup.close（EDT）→ getFullName → GotoClassModel2 委托读 PSI 全限定名，
        // 262 平台 stub 断言硬抛（用户实测堆栈 SqlToJavaPanel.kt CallerClassByNameModel.getFullName）——
        // 生产实现已包 ReadAction（EDT 同步执行）；测试在非 read 上下文直接调用（模拟无 read access
        // 的调用线程；unit test 模式平台豁免断言，此测试验证委托行为与特殊项直出，断言缺陷由沙盒/真机兜底）
        ChooseByNameModel model = panel.callerModelForTest();
        // 特殊项直出（不触 PSI）
        assertEquals("this", model.getFullName("this"));
        // PsiClass 委托：ReadAction 内取全限定名（configureByText 建内存 Java 文件，仿 psi 包测试约定）
        PsiClass psiClass = psiClassFixture();
        assertEquals("demo.UserService", model.getFullName(psiClass));
    }

    @Test
    public void testCallerModelGetElementNameReadActionSafe() {
        ChooseByNameModel model = panel.callerModelForTest();
        assertEquals("this", model.getElementName("this"));
        PsiClass psiClass = psiClassFixture();
        assertEquals("UserService", model.getElementName(psiClass));
    }

    @Test
    public void testPickPsiClassForTestSelection() {
        // elementChosen 的 PsiClass 分支同一路径：ReadAction 内读取 name/qualifiedName/getIcon 并写入字段
        PsiClass psiClass = psiClassFixture();
        panel.pickPsiClassForTest(psiClass);
        assertTrue(panel.callerFieldForTest().getText().equals("UserService"));
    }

    /** 内存 Java 类（仿 EntityResolverCommentTest.resolveEntity 约定：configureByText + PsiTreeUtil 取类）。 */
    private PsiClass psiClassFixture() {
        myFixture.configureByText("UserService.java", "package demo; public class UserService {}");
        return PsiTreeUtil.findChildOfType(myFixture.getFile(), PsiClass.class);
    }

    // ------------------------------------------------------------------
    // T-Mxx：调用类校验——VO/DTO/Utils/Controller 拒绝 + 合法类接受
    // ------------------------------------------------------------------

    @Test
    public void testVoClassRejected() {
        PsiClass psiClass = createPsiClassNamed("UserVO");
        panel.pickPsiClassForTest(psiClass);
        assertTrue("VO 不应被选中", panel.callerFieldForTest().getText().isEmpty());
        assertTrue("状态行应显示拒绝提示", panel.statusForTest().contains("VO/DTO"));
    }

    @Test
    public void testDtoClassRejected() {
        PsiClass psiClass = createPsiClassNamed("UserDTO");
        panel.pickPsiClassForTest(psiClass);
        assertTrue("DTO 不应被选中", panel.callerFieldForTest().getText().isEmpty());
    }

    @Test
    public void testBoClassRejected() {
        PsiClass psiClass = createPsiClassNamed("UserBO");
        panel.pickPsiClassForTest(psiClass);
        assertTrue("BO 不应被选中", panel.callerFieldForTest().getText().isEmpty());
    }

    @Test
    public void testPoClassRejected() {
        PsiClass psiClass = createPsiClassNamed("UserPO");
        panel.pickPsiClassForTest(psiClass);
        assertTrue("PO 不应被选中", panel.callerFieldForTest().getText().isEmpty());
    }

    @Test
    public void testUtilsClassRejected() {
        PsiClass psiClass = createPsiClassNamed("StringUtils");
        panel.pickPsiClassForTest(psiClass);
        assertTrue("Utils 不应被选中", panel.callerFieldForTest().getText().isEmpty());
    }

    @Test
    public void testControllerClassRejected() {
        PsiClass psiClass = createPsiClassNamed("UserController");
        panel.pickPsiClassForTest(psiClass);
        assertTrue("Controller 不应被选中", panel.callerFieldForTest().getText().isEmpty());
    }

    @Test
    public void testMapperClassAccepted() {
        PsiClass psiClass = createPsiClassNamed("UserMapper");
        panel.pickPsiClassForTest(psiClass);
        assertEquals("UserMapper", panel.callerFieldForTest().getText());
    }

    @Test
    public void testServiceClassAccepted() {
        PsiClass psiClass = createPsiClassNamed("UserService");
        panel.pickPsiClassForTest(psiClass);
        assertEquals("UserService", panel.callerFieldForTest().getText());
    }

    @Test
    public void testServiceImplClassAccepted() {
        PsiClass psiClass = createPsiClassNamed("UserServiceImpl");
        panel.pickPsiClassForTest(psiClass);
        assertEquals("UserServiceImpl", panel.callerFieldForTest().getText());
    }

    @Test
    public void testImplClassAccepted() {
        PsiClass psiClass = createPsiClassNamed("UserImpl");
        panel.pickPsiClassForTest(psiClass);
        assertEquals("UserImpl", panel.callerFieldForTest().getText());
    }

    // ------------------------------------------------------------------
    // T-Mxx：this/baseMapper tooltip 上下文感知（兼顾 PM + MPJ）
    // ------------------------------------------------------------------

    @Test
    public void testThisTooltipWithCurrentClass() {
        PsiClass currentClass = createPsiClassWithSuper("UserServiceImpl", "ServiceImpl<UserMapper, User>");
        panel.setCurrentEditorClassForTest(currentClass);
        panel.callerFieldForTest().pickSpecialForTest("this");
        assertTrue("tooltip 应含当前类名",
            panel.callerFieldForTest().getToolTipText() != null
                && panel.callerFieldForTest().getToolTipText().contains("UserServiceImpl"));
    }

    @Test
    public void testBaseMapperTooltipWithServiceImpl() {
        // PM 场景：UserServiceImpl extends ServiceImpl<UserMapper, User>
        PsiClass currentClass = createPsiClassWithSuper("UserServiceImpl", "ServiceImpl<UserMapper, User>");
        panel.setCurrentEditorClassForTest(currentClass);
        panel.callerFieldForTest().pickSpecialForTest("baseMapper");
        assertTrue("tooltip 应含当前类名",
            panel.callerFieldForTest().getToolTipText() != null
                && panel.callerFieldForTest().getToolTipText().contains("UserServiceImpl"));
        assertTrue("tooltip 应含实体信息（第 2 个泛型参数 = User）",
            panel.callerFieldForTest().getToolTipText() != null
                && panel.callerFieldForTest().getToolTipText().contains("User"));
    }

    @Test
    public void testBaseMapperTooltipWithBaseMapper() {
        // PM 场景：独立 UserMapper extends BaseMapper<User>
        PsiClass currentClass = createPsiClassWithSuper("UserMapper", "BaseMapper<User>");
        panel.setCurrentEditorClassForTest(currentClass);
        panel.callerFieldForTest().pickSpecialForTest("baseMapper");
        assertTrue("tooltip 应含当前类名",
            panel.callerFieldForTest().getToolTipText() != null
                && panel.callerFieldForTest().getToolTipText().contains("UserMapper"));
        assertTrue("tooltip 应含实体信息（第 1 个泛型参数 = User）",
            panel.callerFieldForTest().getToolTipText() != null
                && panel.callerFieldForTest().getToolTipText().contains("User"));
    }

    @Test
    public void testBaseMapperTooltipWithMpjMapper() {
        // MPJ 场景：UserMapper extends MPJBaseMapper<User>
        PsiClass currentClass = createPsiClassWithSuper("UserMapper", "MPJBaseMapper<User>");
        panel.setCurrentEditorClassForTest(currentClass);
        panel.callerFieldForTest().pickSpecialForTest("baseMapper");
        assertTrue("tooltip 应含当前类名",
            panel.callerFieldForTest().getToolTipText() != null
                && panel.callerFieldForTest().getToolTipText().contains("UserMapper"));
        assertTrue("tooltip 应含实体信息（MPJ 第 1 个泛型参数 = User）",
            panel.callerFieldForTest().getToolTipText() != null
                && panel.callerFieldForTest().getToolTipText().contains("User"));
    }

    @Test
    public void testBaseMapperTooltipNoSuperType() {
        // 无泛型父类 → 仅展示类名，不报错
        PsiClass currentClass = createPsiClassNamed("UserMapper");
        panel.setCurrentEditorClassForTest(currentClass);
        panel.callerFieldForTest().pickSpecialForTest("baseMapper");
        assertTrue("tooltip 应含当前类名",
            panel.callerFieldForTest().getToolTipText() != null
                && panel.callerFieldForTest().getToolTipText().contains("UserMapper"));
    }

    @Test
    public void testThisTooltipNoCurrentClass() {
        // 当前无打开 Java 文件 → tooltip 保持原样（无追加）
        panel.callerFieldForTest().pickSpecialForTest("this");
        assertTrue("无当前类时 tooltip 不含「当前：」",
            panel.callerFieldForTest().getToolTipText() == null
                || !panel.callerFieldForTest().getToolTipText().contains("当前："));
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    private PsiClass createPsiClassNamed(String simpleName) {
        myFixture.configureByText(simpleName + ".java", "package demo; public class " + simpleName + " {}");
        return PsiTreeUtil.findChildOfType(myFixture.getFile(), PsiClass.class);
    }

    private PsiClass createPsiClassWithSuper(String simpleName, String superDecl) {
        myFixture.configureByText(
            simpleName + ".java",
            "package demo; public class " + simpleName + " extends " + superDecl + " {}");
        return PsiTreeUtil.findChildOfType(myFixture.getFile(), PsiClass.class);
    }
}
