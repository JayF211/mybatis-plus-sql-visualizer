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
import cn.song.mybatisplus.visualizer.model.JoinClause;
import cn.song.mybatisplus.visualizer.model.JoinType;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.model.OrderByItem;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SqlPreviewModel;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import cn.song.mybatisplus.visualizer.sql.MySqlDialect;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.testutil.FakeClipboardManager;
import cn.song.mybatisplus.visualizer.testutil.FakeClipboardKt;
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
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBLoadingPanel;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.UIUtil;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.swing.JCheckBox;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * SqlPreviewPanel 行为测试（M8/M9）：编辑器视图 + 单行多行切换 + 生命周期 + 参数回填。
 *
 * 需要 IDE fixture（EditorFactory/PSI 依赖应用环境），在 EDT 上运行。
 */
public class SqlPreviewPanelTest extends BasePlatformTestCase {

    /**
     * T-ClipboardIso：内存剪贴板（测试全程不碰系统剪贴板——原快照/还原写法在用户复制或
     * 并行会话占用剪贴板时会在 setUp 抛 cannot open system clipboard，且还原失败会污染用户剪贴板）。
     */
    private FakeClipboardManager clipboard;

    // T-M27.4：渲染文本断言类测试关闭自动格式化（默认开启的格式化行为由
    // SqlFormatterTest + testRenderAutoFormatsSql 专项覆盖；此处模拟"关闭格式化"场景）
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        PluginSettings.getInstance().getState().setFormatSql(false);
        clipboard = FakeClipboardKt.installFakeClipboard(getTestRootDisposable());
    }

    @Override
    protected void tearDown() throws Exception {
        PluginSettings.getInstance().getState().setFormatSql(true);
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

    private PreviewResult result(String sql, String compact, List<String> params, List<String> columns) {
        return result(sql, compact, params, columns, "tbl", List.of());
    }

    private PreviewResult result(String sql, String compact, List<String> params) {
        return result(sql, compact, params, List.of("id", "name"), "tbl", List.of());
    }


    private PreviewResult result(String sql, String compact, List<String> params, List<String> columns, String tableName) {
        return result(sql, compact, params, columns, tableName, List.of());
    }

    private PreviewResult result(String sql, String compact, List<String> params, List<String> columns,
                                 String tableName, List<TableInfo> candidateTables) {
        return mk(sql, compact, params, columns, tableName, candidateTables);
    }

    public void testShowResultCreatesEditorView() throws Exception {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result());
        Editor editor = panel.editorForTest();
        assertNotNull("应挂载编辑器视图", editor);
        assertEquals("SELECT id\nFROM tbl", editor.getDocument().getText());
        panel.clear();
    }

    public void testShowResultReplacesEditor() throws Exception {
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

    /**
     * T-F8：含 JOIN 的预览结果（驱动 updateDiagramTab → 后台 schema 富化 → 回 EDT 渲染）。
     *
     * @param mainTable     主表元数据；T-TS-FixA 用它注入「列清单取 size 即抛」的替身，触发
     *                      {@code JoinDiagramGenerator.buildDiagramModel} 的异常路径
     * @param selectColumns 显式 SELECT 列（默认空 = `SELECT *` 语义）
     */
    private PreviewResult joinResult(TableInfo mainTable, List<ColumnRef> selectColumns) {
        SelectStatement statement = new SelectStatement(
                mainTable,
                selectColumns,
                "u",
                List.of(new JoinClause(
                        JoinType.LEFT,
                        "user_address",
                        "a",
                        List.of(new Condition.ColumnComparison(
                                new ColumnRef("u", "id", null, null),
                                new ColumnRef("a", "user_id", null, null),
                                null)))),
                false,
                List.of(),
                List.of(),
                null,
                List.of(),
                null,
                null,
                null,
                null,
                List.of(),
                null,
                List.of());
        String sql = "SELECT u.id, u.name FROM car_device_info u "
                + "LEFT JOIN user_address a ON u.id = a.user_id";
        return new PreviewResult(
                sql,
                sql,
                List.of(),
                null,
                WrapperType.LAMBDA_QUERY,
                "car_device_info",
                null,
                List.of("id", "name"),
                Map.of(),
                null,
                new SqlPreviewModel(statement, MySqlDialect.INSTANCE, false, List.of()),
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

    private PreviewResult joinResult() {
        return joinResult(tableInfo(), List.of());
    }

    /**
     * T-F8 接线守卫：JOIN 图加载态必须包住「进后台富化 → 回 EDT 渲染 → 收尾」整段。
     *
     * 审计项 F8 的形态是 `DiagramPanel.showLoading/hideLoading` 全仓零调用点——T-UN-14 接的
     * JBLoadingPanel 转圈永远不会出现。本用例钉两件事：
     *  ① 探针序列为「进入 → 退出」（修复前为空序列，即红）；
     *  ② 退出时图**已换入**（停转圈若早于渲染，用户在唯一的耗时段里看不到任何反馈）。
     *     「已换入」按两条渲染路径取或：原生图路径（Diagrams 插件在测试沙盒里已加载 →
     *     {@link SqlPreviewPanel#hasNativeDiagramForTest}，与真机默认配置同路径）或自绘路径
     *     （{@code DiagramPanel.hasModelForTest}）。
     *
     * 富化在 `executeOnPooledThread` 上、结果经 `invokeLater` 回 EDT，故需排空 EDT 队列收口；
     * 有界等待避免测试环境异常时挂死。
     */
    public void testDiagramLoadingWrapsBackgroundEnrichment() throws Exception {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        List<Boolean> events = new ArrayList<>();
        boolean[] renderedWhenStopped = {false};
        panel.diagramLoadingProbeForTest(loading -> {
            events.add(loading);
            if (!loading) {
                renderedWhenStopped[0] = panel.hasNativeDiagramForTest()
                        || panel.diagramPanelForTest().hasModelForTest();
            }
        });
        panel.showResult(joinResult());
        long deadline = System.currentTimeMillis() + 10_000;
        while (events.size() < 2 && System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents();
            Thread.sleep(20);
        }
        assertEquals("加载态序列应为 进入 → 退出", List.of(true, false), events);
        assertTrue(
                "停转圈必须发生在图已换入之后（提前摘则富化窗口内零反馈；"
                        + "native=" + panel.hasNativeDiagramForTest()
                        + ", rendered=" + panel.diagramPanelForTest().hasModelForTest() + "）",
                renderedWhenStopped[0]);
        panel.clear();
        assertFalse("整图卸载后不得卡转圈", panel.diagramPanelForTest().isLoadingForTest());
    }

    /**
     * T-TS-05 第二层失效守卫（一）：JOIN 图标签页的内容**必须**是宿主加载面板。
     *
     * 为什么单独立项：`testDiagramLoadingWrapsBackgroundEnrichment` 断的「进/出序列 + 退出时图已换入」
     * 在「把 addTab 目标改回 `diagramTabHost`」之后**仍然全绿**——探针挂在 DiagramPanel 的显式 API 上，
     * 与转圈画在哪一层无关。而真机默认原生图路径下 `showJoinDiagram` 的 `diagramTabHost.removeAll()`
     * 会把图面板摘出层级，加载面不在标签页内容里的那一刻，转圈就再也画不出来（F8 的原始形态）。
     */
    public void testDiagramTabContentIsHostLoadingPanel() throws Exception {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(joinResult());
        JBLoadingPanel host = panel.diagramLoadingHostForTest();
        JTabbedPane tabPane = panel.contentTabPaneForTest();
        assertNotNull("JOIN 预览应切到标签页模式（否则没有加载面可挂）", tabPane);
        List<Component> tabContents = new ArrayList<>();
        for (int i = 0; i < tabPane.getTabCount(); i++) {
            tabContents.add(tabPane.getComponentAt(i));
        }
        boolean containsHost = false;
        for (Component c : tabContents) {
            if (c == host) containsHost = true;
        }
        assertTrue(
                "JOIN 图标签页内容必须是宿主加载面板（实际标签页内容=" + tabContents + "）；"
                        + "改回 diagramTabHost 则原生图路径下转圈被 removeAll 摘出层级、永不出现",
                containsHost);
        assertTrue("加载窗口内宿主层应在转（宿主即真机默认路径下唯一可见的加载面）", host.isLoading());
        panel.clear();
    }

    /**
     * T-TS-05 第二层失效守卫（二）：图面板的出圈层**必须**是宿主加载面板。
     *
     * 与用例（一）互补：`isLoadingForTest` 读的是委托结果，删掉 `DiagramPanel.bindHostLoadingPanel`
     * 注入后它照样自洽地反映面板内部 `contentLayer`——单看它为真断不出「转圈画在了哪一层」。
     * 故这里按对象身份断「出圈层 === 宿主」，让注入被删这件事必红。
     */
    public void testDiagramPanelLoadingTargetIsHostLoadingPanel() throws Exception {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(joinResult());
        JBLoadingPanel host = panel.diagramLoadingHostForTest();
        assertSame(
                "图面板出圈层必须是宿主加载面板（删掉 bindHostLoadingPanel 注入即回落到面板内部层——"
                        + "原生图路径下该层被 removeAll 摘出层级，转圈不可见）",
                host,
                panel.diagramPanelForTest().loadingTargetForTest());
        assertTrue(
                "面板加载态 API 必须反映宿主层（isLoadingForTest 断的是委托结果，宿主侧同步为真）",
                host.isLoading() && panel.diagramPanelForTest().isLoadingForTest());
        panel.clear();
    }

    /**
     * T-TS-05 异常兜底：建图抛异常时转圈必须被摘下。
     *
     * 原实现是「顺序执行 + 末尾摘转圈」：`JoinDiagramGenerator.buildDiagramModel` 与原生框架建图
     * 都在 try 之外/无 finally 保护，一旦抛异常（原生图反射链、文件编辑器创建、自绘测量），
     * 摘转圈那行直接被跳过——用户看到的是转圈永远留在宿主层；后台路径上异常还会让 pooled 线程
     * 终止、`invokeLater` 永不投递，收尾方（`showJoinDiagram`）根本不会被调用。
     * 修复 = `try { … } finally { hideLoading() }` + buildDiagramModel 移入 try + 调用方带日志兜底。
     */
    public void testDiagramRenderFailureClearsLoading() throws Exception {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.diagramRenderForTest = model -> {
            throw new IllegalStateException("diagram boom");
        };
        panel.showResult(joinResult());
        long deadline = System.currentTimeMillis() + 10_000;
        while (panel.diagramPanelForTest().isLoadingForTest() && System.currentTimeMillis() < deadline) {
            // 修复前建图异常会从 EDT 拍逃逸到事件泵（转圈卡住的同一根因）；本用例要断的是转圈状态，
            // 故吞掉逃逸异常继续排空队列——修复后（finally + 调用方带日志兜底）不会有异常逃逸。
            try {
                UIUtil.dispatchAllInvocationEvents();
            } catch (Throwable ignored) {
                // 见上
            }
            Thread.sleep(20);
        }
        assertFalse(
                "建图抛异常后必须摘转圈（原实现异常逃逸会跳过摘转圈那行，转圈永留宿主层）",
                panel.diagramPanelForTest().isLoadingForTest());
        assertFalse("宿主层同样不得卡转圈", panel.diagramLoadingHostForTest().isLoading());
        panel.clear();
    }

    /**
     * T-TS-FixA：列清单替身——只有 `size` 抛异常，迭代/按索引取值全部委托真实列表。
     *
     * 为什么只让 `size` 抛：`JoinDiagramGenerator.buildDiagramModel` 的首个可能抛点是
     * `pickColumns(tableInfo)` 的 `tableInfo.columns.size`；渲染链上唯一同样读 `size` 的是
     * `SELECT *` 的全列展开（`SqlGenerator` 读 `table.allColumnNames`），故用例显式给出
     * selectColumns 绕开它——其余列清单访问（按列名查字段类型等）走迭代/按名查找，不受影响。
     */
    private static List<ColumnInfo> sizeExplodingColumns(final List<ColumnInfo> delegate) {
        return new java.util.AbstractList<ColumnInfo>() {
            @Override
            public int size() {
                throw new IllegalStateException("diagram model boom");
            }

            @Override
            public ColumnInfo get(int index) {
                return delegate.get(index);
            }

            @Override
            public java.util.Iterator<ColumnInfo> iterator() {
                return delegate.iterator();
            }
        };
    }

    /** T-TS-FixA：主表替身构造（等价 Kotlin `tableInfo().copy(columns = …)`——tableInfo() 仅列清单可变）。 */
    private static TableInfo tableInfoWithColumns(List<ColumnInfo> columns) {
        return new TableInfo("DeviceInfoEntity", "car_device_info", columns, null, null, null, List.of());
    }

    /**
     * T-TS-FixA：无 project 的**同步分支**上建图模型抛异常，转圈同样必须被摘下。
     *
     * 与 {@link #testDiagramRenderFailureClearsLoading} 互补——后者走后台富化分支、在 `showJoinDiagram`
     * 内部注入；本用例走 `proj == null` 的同步分支，那里 `buildDiagramModel(stmt)` 是
     * `showJoinDiagram` 的**实参**：实参一抛，方法根本没进入，其 `finally`（唯一收口点）不会执行。
     * 原实现的 catch 只 warn 不摘圈，于是 `showLoading()` 打开的转圈永留宿主层（直到下一次预览
     * 或整图卸载）——两条分支对同一异常的处理不对称，本用例钉住补齐后的行为。
     *
     * 触发走**输入**而非注入缝：给主表列清单塞一个「取 size 即抛」的替身。显式给 selectColumns 是
     * 必需的——渲染拍的列展开（`SqlGenerator` 仅在 `selectColumns` 为空时读 `table.allColumnNames`）
     * 与建图拍读的是**同一个** `size` 访问器，不显式给列清单异常会先落在 renderSql，红的原因就跑偏了。
     */
    public void testDiagramModelBuildFailureWithoutProjectClearsLoading() throws Exception {
        SqlPreviewPanel panel = new SqlPreviewPanel(null);
        panel.showResult(joinResult(
                tableInfoWithColumns(sizeExplodingColumns(tableInfo().getColumns())),
                List.of(new ColumnRef("u", "id", null, null))));
        // 无 project → 同步分支：showResult 返回时该跑的都跑完了，无需排空 EDT 队列
        assertFalse(
                "无 project 同步分支：buildDiagramModel 抛在 showJoinDiagram 之前、finally 不会执行——"
                        + "catch 不摘圈则转圈永留宿主层",
                panel.diagramPanelForTest().isLoadingForTest());
        assertFalse("宿主层同样不得卡转圈", panel.diagramLoadingHostForTest().isLoading());
        panel.clear();
    }

    /**
     * T-PreviewBgFit（问题一守卫）：左侧参数滚动区必须自带面板底色——该区整条链是透明的
     * （paramTopAlign/viewport 非不透明），底色过去由祖先容器提供；T-UiNative P0a 把自绘分栏换成
     * `OnePixelSplitter` 时丢了 `background = UIUtil.getPanelBackground()`，深色主题下参数行下方
     * 直接透出分栏近黑背景（用户实测）。本用例钉死两点：滚动区不透明 + 其背景==平台面板背景；
     * 同时分栏容器也须显式带面板背景（不再依赖子组件自绘）。
     */
    public void testParamsAreaCarriesPanelBackground() throws Exception {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result());

        JBScrollPane scroll = panel.paramScrollForTest();
        assertTrue("参数滚动区应不透明（否则空区透出祖先容器底色）", scroll.isOpaque());
        assertEquals(
                "参数滚动区背景应为平台面板背景（主题命名色）",
                UIUtil.getPanelBackground(),
                scroll.getBackground());
        OnePixelSplitter split = panel.splitPaneForTest();
        assertNotNull("应能取到左右分栏容器", split);
        assertEquals(
                "分栏容器应恢复 T-UiNative P0a 丢失的面板背景",
                UIUtil.getPanelBackground(),
                split.getBackground());
        panel.clear();
    }

    public void testToggleCompactSwitchesSql() throws Exception {
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

    public void testRefreshResetsModeToSettingsDefault() throws Exception {
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

    public void testCopySqlWithContent() throws Exception {
        // 无 project 时不弹成功提示（测试环境 Messages 对话框会抛异常）
        SqlPreviewPanel panel = new SqlPreviewPanel(null);
        panel.showResult(result());
        assertTrue("有内容时应复制成功", panel.copySql());
        panel.clear();
    }

    public void testCopySqlClosePanelCallbackInjectable() throws Exception {
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

    public void testClearReleasesEditor() throws Exception {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result());
        assertNotNull(panel.editorForTest());
        panel.clear();
        assertNull("clear 后应释放编辑器", panel.editorForTest());
        assertNull(panel.currentForTest());
    }

    public void testShowResultBuildsParamFields() throws Exception {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result("SELECT id\nFROM tbl", "SELECT id FROM tbl", List.of("dto.getPhone()", "id")));
        assertEquals(2, panel.paramFieldForTest(1) != null ? 2 : 0);
        assertNotNull("应生成参数输入框", panel.paramFieldForTest(0));
        panel.clear();
    }

    public void testParamRowLabelWidthAdaptsAndTooltipForLongNames() throws Exception {
        // T-M19.4-01 + T-M26.3
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        String shortName = "id";
        String medium = "productInfo.getDicProtocolType()";   // 用户实测场景：约 200px
        String longName = "productInfo.getDicProtocolType().getDeviceName().getStatusName().getDetailInfo()";
        panel.showResult(result("SELECT id\nFROM tbl", "SELECT id FROM tbl", List.of(shortName, medium, longName)));
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

    public void testParamBackfillUpdatesSql() throws Exception {
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

    public void testParamBackfillEmptyKeepsPlaceholder() throws Exception {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        String sql = "SELECT id FROM tbl WHERE phone = ?";
        panel.showResult(result(sql, sql, List.of("phone")));
        // 留空 → 保留 ?
        assertEquals(sql, panel.editorForTest().getDocument().getText());
        panel.clear();
    }

    // T-PSG（N-33）：面板回填串感知——引号/注释内的 `?` 不是占位符，不参与回填
    public void testParamBackfillIgnoresQuestionMarkInStringLiteral() throws Exception {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        String sql = "SELECT id FROM tbl WHERE note = 'a?b' AND phone = ?";
        panel.showResult(result(sql, sql, List.of("phone")));
        panel.paramFieldForTest(0).setText("abc");
        assertEquals(
                "引号内 `?` 应保持原样、真占位符按 phone 回填",
                "SELECT id FROM tbl WHERE note = 'a?b' AND phone = 'abc'",
                panel.editorForTest().getDocument().getText());
        panel.clear();
    }

    public void testParamBackfillIgnoresQuestionMarkInComments() throws Exception {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        String sql = "SELECT id FROM tbl /* 占?位 */ WHERE phone = ?";
        panel.showResult(result(sql, sql, List.of("phone")));
        panel.paramFieldForTest(0).setText("abc");
        assertEquals(
                "块注释内 `?` 应保持原样",
                "SELECT id FROM tbl /* 占?位 */ WHERE phone = 'abc'",
                panel.editorForTest().getDocument().getText());
        panel.clear();
    }

    public void testParamBackfillNormalPathByteIdentical() throws Exception {
        // T-PSG 验收 1：不含引号/注释内 `?` 的正常 SQL，回填结果与改前逐字节一致（硬编码钉住）
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        String sql = "SELECT id FROM tbl WHERE note = 'hello' AND phone = ? AND age > ?";
        panel.showResult(result(sql, sql, List.of("phone", "age")));
        panel.paramFieldForTest(0).setText("abc");
        panel.paramFieldForTest(1).setText("1");
        assertEquals(
                "SELECT id FROM tbl WHERE note = 'hello' AND phone = 'abc' AND age > 1",
                panel.editorForTest().getDocument().getText());
        panel.clear();
    }

    // T-PSG-04：面板回填链的基数对账——位点数与参数条数不等时如实记录（不阻断）
    public void testPlaceholderReconciliationMismatchRecorded() throws Exception {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        String sql = "SELECT id FROM tbl WHERE a = ? AND b = ?";
        panel.showResult(result(sql, sql, List.of("a")));
        assertEquals(
                "位点数(2) != 参数条数(1) → 应如实记录对账标注",
                "-- placeholder/param count mismatch (2 placeholders vs 1 params)",
                panel.placeholderReconciliationForTest());
        panel.clear();
    }

    public void testPlaceholderReconciliationEqualNoMarker() throws Exception {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        String sql = "SELECT id FROM tbl WHERE phone = ?";
        panel.showResult(result(sql, sql, List.of("phone")));
        assertNull("位点数与参数条数相等 → 无标注", panel.placeholderReconciliationForTest());
        panel.clear();
    }

    // ------------------------------------------------------------------
    // T-M42：日志双击预览参数识别——initialParamValues 预填
    // ------------------------------------------------------------------

    public void testInitialParamValuesPrefillFieldAndRender() throws Exception {
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

    public void testInitialParamValuesEditableAfterPrefill() throws Exception {
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

    public void testInitialParamValuesMissingSourceFallsBackToEmpty() throws Exception {
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

    public void testEditorIsEditable() throws Exception {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result());
        Editor editor = panel.editorForTest();
        assertFalse("应为可编辑编辑器（非只读 viewer）", editor.isViewer());
        panel.clear();
    }

    public void testCompletionContextAttached() throws Exception {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
panel.showResult(result("SELECT id\nFROM tbl", "SELECT id FROM tbl", List.of(), List.of("id", "phone")));
        PsiFile psi = panel.psiFileForTest();
        assertNotNull("应创建预览 PSI 文件", psi);
        SqlCompletionContext ctx = psi.getUserData(SqlCompletionContext.KEY);
        assertNotNull("应挂补全上下文", ctx);
        assertEquals("tbl", ctx.tableName);
        assertEquals(List.of("id", "phone"), ctx.columns);

        // M16.2 双载体：Editor 上同样挂载补全上下文
        Editor editor = panel.editorForTest();
        assertNotNull("应创建编辑器", editor);
        SqlCompletionContext editorCtx = editor.getUserData(SqlCompletionContext.KEY);
        assertNotNull("Editor 上应挂补全上下文（M16.2）", editorCtx);
        assertEquals("tbl", editorCtx.tableName);
        assertEquals(List.of("id", "phone"), editorCtx.columns);
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

    public void testDynamicConditionCheckboxTogglesSql() throws Exception {
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

    public void testSelectAllNoneTogglesAllConditions() throws Exception {
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

        // T-SelToggle：单个切换按钮——初始非全勾（仅 c0）→ 文案「全选」→ 点击全勾、文案翻「全不选」
        javax.swing.JButton toggle = panel.toggleSelectButtonForTest();
        assertEquals("初始非全勾应为全选文案",
                cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("panel.condition.selectAll"), toggle.getText());
        toggle.doClick();
        assertTrue(panel.editorForTest().getDocument().getText().contains("AND station_id = 2"));
        assertTrue(panel.conditionCheckboxForTest(0).isSelected());
        assertTrue(panel.conditionCheckboxForTest(1).isSelected());
        assertEquals("全勾后应翻转为全不选文案",
                cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("panel.condition.selectNone"), toggle.getText());

        // 再点一次 → 动态条件全部移除（无 WHERE），复选框状态同步、文案回「全选」
        toggle.doClick();
        assertFalse(panel.editorForTest().getDocument().getText().contains("WHERE"));
        assertFalse(panel.conditionCheckboxForTest(0).isSelected());
        assertFalse(panel.conditionCheckboxForTest(1).isSelected());
        assertTrue(panel.enabledIdsForTest().isEmpty());
        assertEquals("全不选后应回到全选文案",
                cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("panel.condition.selectAll"), toggle.getText());

        // 全选后再取消单个勾选 → 另一个保留，文案随部分勾选态翻回「全选」
        toggle.doClick();
        panel.conditionCheckboxForTest(1).doClick();
        String sql = panel.editorForTest().getDocument().getText();
        assertTrue(sql.contains("id = 1"));
        assertFalse(sql.contains("station_id = 2"));
        assertEquals("部分勾选应为全选文案",
                cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("panel.condition.selectAll"), toggle.getText());
        panel.clear();
    }

    public void testToggleSelectLabelFollowsSingleCheckboxClick() throws Exception {
        // T-SelToggle：文案同步挂在 renderSql 单点收口——单个复选框点选即应翻转切换按钮文案
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(dynamicResult(
                List.of(
                        new Condition.Simple(new ColumnRef(null, "id", null, null, null, null, null), "=",
                                new SqlValue.Literal("1"),
                                new ConditionToggle("c0", "true", true, "id = 1", null, List.of()))),
                List.of(
                        new ConditionView("c0", "id = 1", "true", true, null, List.of()))));
        javax.swing.JButton toggle = panel.toggleSelectButtonForTest();
        // 初始：唯一条件已勾选 → 全勾 → 「全不选」
        assertEquals(cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("panel.condition.selectNone"), toggle.getText());
        panel.conditionCheckboxForTest(0).doClick(); // 取消唯一勾选 → 空集 → 「全选」
        assertEquals(cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("panel.condition.selectAll"), toggle.getText());
        panel.conditionCheckboxForTest(0).doClick(); // 勾回 → 全勾 → 「全不选」
        assertEquals(cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("panel.condition.selectNone"), toggle.getText());
        panel.clear();
    }

    public void testClearButtonClearsConditionValues() throws Exception {
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

    public void testDynamicConditionValueBlankKeepsChecked() throws Exception {
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

    public void testDynamicConditionLikePlaceholderPercentBackfill() throws Exception {
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

    public void testDynamicConditionStringColumnNumericValueQuoted() throws Exception {
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

    public void testDynamicConditionNumericColumnValueRaw() throws Exception {
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

    public void testDynamicConditionDateTimeColumnValueQuoted() throws Exception {
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

    public void testBatchModelAutoIdDefaultUncheckedRendersWithoutId() throws Exception {
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
        assertFalse("id 复选框默认不勾选", panel.batchCheckboxForTest(0).isSelected());
        panel.clear();
    }

    public void testBatchModelCheckboxToggleRebuildsSql() throws Exception {
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
        assertTrue(panel.batchCheckboxForTest(0).isSelected());
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

    public void testBatchModelFixedColumnHasNoCheckbox() throws Exception {
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

    public void testBatchModelSelectAllNoneButtons() throws Exception {
        // T-M23.2（用户要求：参数可选场景必须有【全选/全不选】按钮）
        BatchModel model = new BatchModel(BatchKind.INSERT, "biz_store_coupon",
                List.of(
                        new BatchColumn("id", "entity.id", false, false),
                        new BatchColumn("name", "entity.name", true, true),
                        new BatchColumn("dic_status", "entity.dic_status", false, true)),
                List.of(), null, null, MySqlDialect.INSTANCE);
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(batchResult(model));
        javax.swing.JButton toggle = panel.batchToggleSelectButtonForTest();
        assertNotNull("批量区应有全选/全不选切换按钮", toggle);
        // T-SelToggle：初始部分勾选（id 未勾）→ 文案「全选」；点击 → 全列恢复
        assertEquals(cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("panel.condition.selectAll"), toggle.getText());
        toggle.doClick();
        assertTrue(panel.batchCheckboxForTest(0).isSelected());
        assertTrue(panel.batchCheckboxForTest(1).isSelected());
        assertTrue(panel.batchCheckboxForTest(2).isSelected());
        assertEquals(cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("panel.condition.selectNone"), toggle.getText());
        assertTrue(
                panel.editorForTest().getDocument().getText().contains("INSERT INTO biz_store_coupon (id, name, dic_status)"));
        // 再点 → 全不选 → 空列清单（文档化边界），文案回「全选」
        toggle.doClick();
        assertFalse(panel.batchCheckboxForTest(0).isSelected());
        assertFalse(panel.batchCheckboxForTest(1).isSelected());
        assertFalse(panel.batchCheckboxForTest(2).isSelected());
        assertEquals(cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("panel.condition.selectAll"), toggle.getText());
        assertTrue(panel.editorForTest().getDocument().getText().contains("INSERT INTO biz_store_coupon ()"));
        panel.clear();
    }

    public void testInListMultiValueBackfill() throws Exception {
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

    public void testExecuteSqlWithoutDatabasePluginNoThrow() throws Exception {
        // 测试沙盒无 Database 插件 → 走 Community 降级提示，不抛异常
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result("SELECT id FROM tbl", "SELECT id FROM tbl"));
        panel.executeSql();
        panel.clear();
    }

    public void testExecuteSqlDisabledWithoutProject() throws Exception {
        // 无项目面板（纯组件预览）：执行无动作（project null 直接返回），不抛异常
        SqlPreviewPanel panel = new SqlPreviewPanel(null);
        panel.showResult(result());
        panel.executeSql();
        panel.clear();
    }

    public void testExecuteSqlBlankSqlNoop() throws Exception {
        // 空 SQL：不触发执行链路，不抛异常
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result("   ", "   "));
        panel.executeSql();
        panel.clear();
    }

    public void testSplitIsNativeOnePixelSplitter() throws Exception {
        // T-M26.1→T-UiNative P0：左右分栏换平台 OnePixelSplitter——分隔条由平台绘制（原生主题
        // 融合 + 比例持久化键 mpvs.preview.splitter），替代旧 ThemedSplitPane 覆写 paint 粉刷。
        // 断言：分栏是平台 Splitter（持久化键就位）且 proportion 契约可用
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        OnePixelSplitter split = panel.splitPaneForTest();
        assertNotNull("应存在左右分栏", split);
        assertEquals("mpvs.preview.splitter", split.getSplitterProportionKey());
        split.setSize(300, 100);
        split.doLayout();
        split.setProportion(0.4f);
        assertEquals(0.4f, split.getProportion(), 1e-6f);
        panel.clear();
    }

    public void testSelectAllButtonsOnConditionSectionHeader() throws Exception {
        // T-M26.3：全选/全不选回挂「动态条件」分区标题行右侧
        String longExpr = "stationIds != null and stationIds.size() > 0";
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        OnePixelSplitter split = panel.splitPaneForTest();
        // 先布局再 showResult：截断上限按视口宽度计算
        split.setSize(500, 120);
        split.doLayout();
        split.setProportion(0.5f);
        split.doLayout();
        ((JBScrollPane) split.getFirstComponent()).doLayout();
        panel.showResult(dynamicResult(
                List.of(
                        new Condition.Simple(new ColumnRef(null, "station_id", null, null, null, null, null), "IN",
                                new SqlValue.Placeholder("stationIds"),
                                new ConditionToggle("c0", "true", true, "station_id IN (?)", "stationIds", List.of("stationIds")))),
                List.of(new ConditionView("c0", longExpr, "true", true, "stationIds", List.of("stationIds")))));
        JBScrollPane scroll = (JBScrollPane) split.getFirstComponent();
        javax.swing.JViewport viewport = scroll.getViewport();
        javax.swing.JButton button = panel.toggleSelectButtonForTest();
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

    public void testReformatShortcutRegistered() throws Exception {
        // T-M26.6：格式化快捷键注册
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        assertNotNull("应注册格式化 action", panel.reformatActionForTest());
        panel.clear();
    }

    public void testReformatShortcutFormatsSql() throws Exception {
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

    // ------------------------------------------------------------------
    // T-VStack：竖向排列按钮 + T-VPairAnno 标注开关
    // ------------------------------------------------------------------

    public void testVerticalButtonPresentAndFormatsInsert() throws Exception {
        // T-VStack：竖向排列按钮在位（文案取 panel.vertical），formatVertical 对 INSERT 竖排 + 配对标注
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        String expectedText = cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("panel.vertical");
        javax.swing.JButton button = findButtonByText(panel, expectedText);
        assertNotNull("工具栏应存在竖向排列按钮", button);
        panel.showResult(result(
                "insert into `user` (username, password) values ('zhangsna', '123456')",
                "insert into `user` (username, password) values ('zhangsna', '123456')"));
        panel.formatVertical();
        String text = panel.editorForTest().getDocument().getText();
        assertEquals(
                "INSERT 应竖排且值行尾标注列名：" + text,
                """
                INSERT
                INTO `user`
                (
                  username,
                  password
                )
                VALUES
                (
                  'zhangsna', -- username
                  '123456'    -- password
                )
                """.stripTrailing(),
                text);
        panel.clear();
    }

    public void testVerticalNonInsertFallsBackToFormat() throws Exception {
        // T-VStack·扩面 + InAll：非 INSERT 输入 = format + 横向组展开（多元素 IN 列表一律纵向化，
        // 与格式化按钮可区分；单元素形态保持同行）
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result(
                "select id from tbl where id in (1, 2, 3, 4, 5)",
                "select id from tbl"));
        panel.formatVertical();
        String text = panel.editorForTest().getDocument().getText();
        assertTrue("SELECT 应按 format 全展开：" + text, text.contains("SELECT\n") && text.contains("FROM tbl"));
        assertTrue("长 IN 列表应纵向化：" + text, text.contains("IN\n"));
        panel.clear();

        SqlPreviewPanel panel2 = new SqlPreviewPanel(getProject());
        panel2.showResult(result(
                "select id from tbl where id in (1, 2)",
                "select id from tbl"));
        panel2.formatVertical();
        String shortText = panel2.editorForTest().getDocument().getText();
        assertTrue("2 元素 IN 也应纵向化：" + shortText, shortText.contains("IN\n"));
        panel2.clear();
    }

    public void testVerticalAnnotationFollowsSetting() throws Exception {
        // T-VPairAnno：设置关闭 → 竖排保持但行尾无配对标注；恢复开启（默认）→ 标注回来
        cn.song.mybatisplus.visualizer.config.PluginSettings.State settings =
                cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().getState();
        String sql = "insert into `user` (username, password) values ('zhangsna', '123456')";
        try {
            settings.setVerticalPairAnnotation(false);
            SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
            panel.showResult(result(sql, sql));
            panel.formatVertical();
            String off = panel.editorForTest().getDocument().getText();
            assertTrue("竖排形态应保持：" + off, off.contains("VALUES\n(") && off.contains("'zhangsna',"));
            assertFalse("行尾不应有配对标注：" + off, off.contains("-- username"));
            panel.clear();

            settings.setVerticalPairAnnotation(true);
            SqlPreviewPanel panel2 = new SqlPreviewPanel(getProject());
            panel2.showResult(result(sql, sql));
            panel2.formatVertical();
            String on = panel2.editorForTest().getDocument().getText();
            assertTrue("恢复开启后应重新标注：" + on, on.contains("-- username") && on.contains("-- password"));
            panel2.clear();
        } finally {
            settings.setVerticalPairAnnotation(true);
        }
    }

    // ------------------------------------------------------------------
    // T-PreviewDisplayMode：显示模式在重渲染后保持（用户实测：输入参数后竖排消失）
    // ------------------------------------------------------------------

    public void testVerticalModeSurvivesParamChange() throws Exception {
        // 用户复现路径：点竖向排列 → 左侧填参数 → 形态必须保持（旧实现：文档被重建抹平）
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        String sql = "insert into `user` (username, password, age) values ('zhangsna', '123456', ?)";
        panel.showResult(result(sql, sql, List.of("age")));
        panel.formatVertical();
        assertEquals(SqlPreviewPanel.PreviewDisplayMode.VERTICAL, panel.displayModeForTest());
        String vertical = panel.editorForTest().getDocument().getText();
        assertTrue("竖排应先生效：" + vertical, vertical.contains("-- username") && vertical.contains("VALUES\n("));

        // 模拟用户在参数行输入（DocumentListener → onParamChanged → renderSql 全量重建）
        panel.paramFieldForTest(0).setText("1");

        String after = panel.editorForTest().getDocument().getText();
        assertTrue("参数变更后应仍为竖排：" + after, after.contains("-- username") && after.contains("VALUES\n("));
        assertTrue("参数应已回填：" + after, after.contains("1"));
        panel.clear();
    }

    public void testKeywordWrapModeSurvivesParamChange() throws Exception {
        // 关键字换行同缺陷同修复：参数变更后仍为「子句顶格 + 内容同行」形态（非 format 全展开）
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        String sql = "SELECT id FROM tbl WHERE phone = ? AND type = ?";
        panel.showResult(result(sql, sql, List.of("phone", "type")));
        panel.setDisplayMode(SqlPreviewPanel.PreviewDisplayMode.KEYWORD_WRAP);
        assertEquals(
                "SELECT id\nFROM tbl\nWHERE phone = ?  AND type = ?",
                panel.editorForTest().getDocument().getText());

        panel.paramFieldForTest(0).setText("abc");

        assertEquals(
                "参数变更后应仍为关键字换行形态",
                "SELECT id\nFROM tbl\nWHERE phone = 'abc'  AND type = ?",
                panel.editorForTest().getDocument().getText());
        panel.clear();
    }

    public void testFormatModeSurvivesParamChangeWhenSettingOff() throws Exception {
        // 条件性丢失分支：设置「SQL 格式化输出」关闭时，点格式化后的形态此前会被参数变更抹平
        cn.song.mybatisplus.visualizer.config.PluginSettings.State state =
                cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().getState();
        boolean backup = state.getFormatSql();
        try {
            state.setFormatSql(false);
            SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
            String sql = "SELECT id FROM tbl WHERE phone = ?";
            panel.showResult(result(sql, sql, List.of("phone")));
            panel.formatSql();
            assertEquals(SqlPreviewPanel.PreviewDisplayMode.FORMAT, panel.displayModeForTest());
            String formatted = panel.editorForTest().getDocument().getText();
            assertTrue("FORMAT 模式应强制格式化：" + formatted, formatted.contains("SELECT\n"));

            panel.paramFieldForTest(0).setText("abc");

            String after = panel.editorForTest().getDocument().getText();
            assertTrue("设置关闭时格式化形态也应保持：" + after, after.contains("SELECT\n"));
            assertTrue("参数应已回填：" + after, after.contains("'abc'"));
            panel.clear();
        } finally {
            try {
                state.setFormatSql(backup);
            } catch (Throwable ignored) {
                // 沙盒拆卸期设置服务可能已注销
            }
        }
    }

    public void testCompactTakesPrecedenceOverDisplayMode() throws Exception {
        // 压缩优先级最高：压缩态下模式不套用（保持单行）；切回多行后既有模式恢复
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        String sql = "insert into `user` (username, age) values ('zhangsna', ?)";
        panel.showResult(result(sql, sql, List.of("age")));
        panel.setDisplayMode(SqlPreviewPanel.PreviewDisplayMode.VERTICAL);

        panel.toggleCompact();
        String compact = panel.editorForTest().getDocument().getText();
        assertFalse("压缩态应为单行：" + compact, compact.contains("\n"));
        assertEquals("压缩不应改变模式状态", SqlPreviewPanel.PreviewDisplayMode.VERTICAL, panel.displayModeForTest());

        panel.toggleCompact();
        assertTrue("切回多行后竖排应恢复", panel.editorForTest().getDocument().getText().contains("-- username"));
        panel.clear();
    }

    public void testSetDisplayModeExitsCompact() throws Exception {
        // 压缩态点格式族按钮：一并退出压缩（否则模式被短路、点击无可见效果）
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        String sql = "insert into `user` (username, age) values ('zhangsna', ?)";
        panel.showResult(result(sql, sql, List.of("age")));
        panel.toggleCompact();
        assertTrue("前置：应处于压缩态", panel.compactForTest());

        panel.setDisplayMode(SqlPreviewPanel.PreviewDisplayMode.VERTICAL);

        assertFalse("格式族模式切换应退出压缩", panel.compactForTest());
        assertTrue("应显示竖排形态", panel.editorForTest().getDocument().getText().contains("-- username"));
        panel.clear();
    }

    public void testRefreshResetsDisplayMode() throws Exception {
        // 刷新 = 回到默认状态（M19.3）：显示模式一并复位为 NORMAL（设置派生形态）
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result(
                "select id from tbl where id in (1, 2, 3, 4, 5)",
                "select id from tbl"));
        panel.setDisplayMode(SqlPreviewPanel.PreviewDisplayMode.VERTICAL);
        assertTrue("前置：应已竖排", panel.editorForTest().getDocument().getText().contains("IN\n"));

        panel.refreshView();

        assertEquals(SqlPreviewPanel.PreviewDisplayMode.NORMAL, panel.displayModeForTest());
        panel.clear();
    }

    public void testDisplayModeSurvivesConditionToggle() throws Exception {
        // 另一重建入口：动态条件勾选（renderSql 直调）同样不应抹掉竖排。
        // 断言物用 ORDER BY 多字段——竖排把它拆成逐行（format 保持同行），可区分模式是否真在生效
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        TableInfo table = tableInfo();
        SelectStatement statement = new SelectStatement(
                table,
                List.of(),
                null,
                List.of(),
                false,
                List.of(new Condition.Simple(new ColumnRef(null, "id", null, null, null, null, null), "=",
                        new SqlValue.Literal("1"),
                        new ConditionToggle("c0", "true", true, "id = 1", null, List.of()))),
                List.of(),
                null,
                List.of(new OrderByItem(new ColumnRef(null, "id", null, null, null, null, null), true),
                        new OrderByItem(new ColumnRef(null, "name", null, null, null, null, null), false)),
                null,
                null,
                null,
                null,
                List.of(),
                null,
                List.of());
        SqlPreviewModel model = new SqlPreviewModel(statement, MySqlDialect.INSTANCE, true,
                List.of(new ConditionView("c0", "id = 1", "true", true, null, List.of())));
        panel.showResult(new PreviewResult(
                "SELECT id, name\nFROM car_device_info",
                "SELECT id, name FROM car_device_info",
                List.of(),
                null,
                WrapperType.LAMBDA_QUERY,
                "car_device_info",
                null,
                List.of("id", "name"),
                java.util.Map.of(),
                null,
                model,
                List.of(),
                List.of(),
                null,
                null,
                java.util.Map.of(),
                null,
                null,
                null,
                null));
        panel.setDisplayMode(SqlPreviewPanel.PreviewDisplayMode.VERTICAL);
        assertTrue("前置：ORDER BY 应已逐行", panel.editorForTest().getDocument().getText().contains("ORDER BY\n"));

        panel.conditionCheckboxForTest(0).doClick();

        String after = panel.editorForTest().getDocument().getText();
        assertEquals("勾选重建后模式不应丢失", SqlPreviewPanel.PreviewDisplayMode.VERTICAL, panel.displayModeForTest());
        assertTrue("勾选重建后 ORDER BY 应仍逐行：" + after, after.contains("ORDER BY\n"));
        panel.clear();
    }

    /** 工具栏按钮按文案查找（组件树递归；master 侧 UIUtil.uiTraverser 的等价 Java 写法）。 */
    private static javax.swing.JButton findButtonByText(java.awt.Container root, String text) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof javax.swing.JButton) {
                javax.swing.JButton button = (javax.swing.JButton) c;
                if (text.equals(button.getText())) return button;
            } else if (c instanceof java.awt.Container) {
                javax.swing.JButton found = findButtonByText((java.awt.Container) c, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    public void testActionButtonGroupsSwitchByScene() throws Exception {
        // T-M26.3：按钮组按场景构建
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        // 无参数 → 无按钮
        panel.showResult(result());
        assertNull("无内容时应无全选按钮", panel.toggleSelectButtonForTest());
        assertNull("无内容时应无批量全选按钮", panel.batchToggleSelectButtonForTest());
        // 动态条件 → 条件组
        panel.showResult(dynamicResult(
                List.of(
                        new Condition.Simple(new ColumnRef(null, "id", null, null, null, null, null), "=",
                                new SqlValue.Literal("1"),
                                new ConditionToggle("c0", "true", true, "id = 1", null, List.of()))),
                List.of(new ConditionView("c0", "id = 1", "true", true, null, List.of()))));
        assertNotNull("动态条件场景应有全选按钮", panel.toggleSelectButtonForTest());
        assertNull("动态条件场景不应有批量全选按钮", panel.batchToggleSelectButtonForTest());
        // 批量列 → 批量组
        panel.showResult(batchResult(new BatchModel(BatchKind.INSERT, "biz_store_coupon",
                List.of(new BatchColumn("name", "entity.name", true, true)),
                List.of(), null, null, MySqlDialect.INSTANCE)));
        assertNull("批量场景不应有全选按钮", panel.toggleSelectButtonForTest());
        assertNotNull("批量场景应有批量全选按钮", panel.batchToggleSelectButtonForTest());
        panel.clear();
        assertNull("clear 后应无全选按钮", panel.toggleSelectButtonForTest());
        assertNull("clear 后应无批量全选按钮", panel.batchToggleSelectButtonForTest());
    }

    // ------------------------------------------------------------------
    // T-M27.1：SQL 面板补全触发
    // ------------------------------------------------------------------

    public void testPanelEditorWrapsDataProviderForEditor() throws Exception {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result());
        Editor editor = panel.editorForTest();
        assertNotNull("应挂载编辑器", editor);
        DataProvider provider = findDataProvider(panel);
        assertNotNull("编辑器组件链应有 DataProvider 包装", provider);
        assertEquals("DataProvider 应提供当前编辑器", editor, provider.getData(CommonDataKeys.EDITOR.getName()));
        panel.clear();
    }

    public void testPanelEditorCompletionOffersKeywordsAndColumns() throws Exception {
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

    public void testTypingTriggersAutoPopup() throws Exception {
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

    public void testTypingTriggersAutoPopupXmlScene() throws Exception {
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

    public void testTypingTriggersAutoPopupXmlSceneWithEntity() throws Exception {
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

    public void testRenderAutoFormatsSql() throws Exception {
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

    /**
     * T-PreviewConsoleFmt：控制台文档（= exec 文件文档）持有预览原文（裸单行）时，
     * 渲染链必须把**格式化文本**回填进该文档——真机日志实证：面板自建编辑器拿到 16 行格式化
     * 文本（len=274），但控制台挂载读的是文件内容（预热写的预览原文，1 行 len=231），挂载后
     * 无重渲染 → 用户看到裸单行 SQL。本用例钉死「渲染回填 nativeDoc」这一环（触发点见
     * prepareDefaultConsole 的 onConsole 回填，真机由 embedConsole 后 invokeLater 执行）。
     */
    public void testRenderRepublishesFormattedTextIntoNativeConsoleDoc() throws Exception {
        PluginSettings.getInstance().getState().setFormatSql(true);
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        panel.showResult(result(
                "select  id ,name  from t  where id=1",
                "select id,name from t where id=1"));
        // 模拟控制台就绪且已嵌入本面板（showSql 的 nativeDoc 分支要求 consoleAttachedToMe）
        panel.embedConsoleForTest(new javax.swing.JPanel());
        assertTrue("前置：控制台应视为已挂到本面板", panel.consoleAttachedToMeForTest());
        // 控制台文档持有「预览原文」裸单行（挂载时 DBN 读的正是文件内容）
        com.intellij.openapi.editor.Document consoleDoc = com.intellij.openapi.editor.EditorFactory
                .getInstance().createDocument("select  id ,name  from t  where id=1");
        panel.markNativeDocForTest(consoleDoc);

        panel.renderSqlForTest(); // = 挂载后的回填渲染

        assertEquals(
                "控制台文档应被回填为格式化文本（否则控制台显示裸单行）",
                "SELECT\n    id,\n    name\nFROM t\nWHERE id = 1",
                consoleDoc.getText());
        panel.clear();
    }

    public void testTableNameCompletionInWhereClauseWithCandidates() throws Exception {
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

    // ---------- T-AutoFold F4-a：showSql 程序性折叠 ----------

    private void withAutoFold(boolean enabled, Runnable block) {
        cn.song.mybatisplus.visualizer.config.PluginSettings.State state =
                cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().getState();
        boolean oldEnabled = state.getAutoFoldEnabled();
        int oldThreshold = state.getAutoFoldFieldThreshold();
        try {
            state.setAutoFoldEnabled(enabled);
            state.setAutoFoldFieldThreshold(3);
            block.run();
        } finally {
            state.setAutoFoldEnabled(oldEnabled);
            state.setAutoFoldFieldThreshold(oldThreshold);
        }
    }

    private java.util.List<com.intellij.openapi.editor.FoldRegion> ownFoldRegions(com.intellij.openapi.editor.Editor editor) {
        java.util.List<com.intellij.openapi.editor.FoldRegion> out = new ArrayList<>();
        for (com.intellij.openapi.editor.FoldRegion region : editor.getFoldingModel().getAllFoldRegions()) {
            if (region.getPlaceholderText().startsWith("…")) out.add(region);
        }
        return out;
    }

    public void test开启自动折叠后showResult产生折叠区域() throws Exception {
        withAutoFold(true, () -> {
            SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
            panel.showResult(result(
                    "SELECT\n    a1,\n    a2,\n    a3,\n    a4\nFROM t",
                    "SELECT a1, a2, a3, a4 FROM t"));
            com.intellij.openapi.editor.Editor editor = panel.editorForTest();
            java.util.List<com.intellij.openapi.editor.FoldRegion> regions = ownFoldRegions(editor);
            assertEquals("showSql 后应有本方折叠区域", 1, regions.size());
            assertFalse("区域应收起", regions.get(0).isExpanded());
            panel.clear();
        });
    }

    public void test关闭自动折叠后showResult零区域() throws Exception {
        withAutoFold(false, () -> {
            SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
            panel.showResult(result(
                    "SELECT\n    a1,\n    a2,\n    a3,\n    a4\nFROM t",
                    "SELECT a1, a2, a3, a4 FROM t"));
            assertEquals("关闭态零折叠区域（逐字节不变）", 0, ownFoldRegions(panel.editorForTest()).size());
            panel.clear();
        });
    }

    // ---------- T-Perf-04：detectInClauseParams 单趟扫描（与旧 O(L×P) 实现语义一致） ----------

    public void testDetectInClauseParams_InParens多参数命中() throws Exception {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        try {
            // 语义与旧实现（对每个 ? 前缀做 endsWith("IN ("/"IN(")）完全一致：
            // 仅紧贴 "IN (" 的首个 ? 命中（后续 ? 的前缀以 "?," 结尾不命中）。
            java.util.Set<Integer> detected = panel.detectInClauseParams(
                    "SELECT * FROM t WHERE id IN ( ?, ?, ? ) AND name = ?");
            assertEquals(java.util.Set.of(0), detected);
        } finally {
            panel.dispose();
        }
    }

    public void testDetectInClauseParams_紧凑形态与大小写() throws Exception {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        try {
            assertEquals(java.util.Set.of(0),
                    panel.detectInClauseParams("select * from t where id in(?)"));
        } finally {
            panel.dispose();
        }
    }

    public void testDetectInClauseParams_非In问号不命中() throws Exception {
        SqlPreviewPanel panel = new SqlPreviewPanel(getProject());
        try {
            assertEquals(java.util.Set.<Integer>of(),
                    panel.detectInClauseParams("SELECT * FROM t WHERE a = ? AND b = ?"));
        } finally {
            panel.dispose();
        }
    }
}
