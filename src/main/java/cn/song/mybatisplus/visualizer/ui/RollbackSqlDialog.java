// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/RollbackSqlDialog.kt 移植
// T-SyncK2JMaster115 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/RollbackSqlDialog.kt 移植（O-44a 空白符可视化接线）
// T-SyncK2JMaster184 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/RollbackSqlDialog.kt 移植（O-52/O-59：粘贴回填带 dialect + identityColumns，消费点解析自增列）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore;
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator;
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 回滚 SQL 视图（T-RollbackSql P1-F3 / P2-F5，dbx 精华融合）。
 *
 * <p>MODELESS 独立弹窗（仿 SqlPreviewDialog 惯例）——刻意不写进预览面板 native 文档、
 * 不切显示模式：`showSql` 三分支管线（T-RefreshDbSteal 治理后）零侵入。
 *
 * <p>形态按评级分层（dbx「评级不足阻断执行」的对应物）：
 * <ul>
 *  <li>Complete：绿色横幅 + 只读回滚 SQL 编辑器 + 底部「执行回滚 / 复制 / 关闭」；</li>
 *  <li>Partial：橙色横幅（风险警示）+ COUNT 自查模板展示，其余同上；</li>
 *  <li>Unable（可解析的 UPDATE/DELETE）：红色横幅 + 人工恢复指引 + **快照工作流**——
 *      快照查询模板一键执行（结果落面板控制台网格，用户亲手取得 before-image）+
 *      粘贴结果生成完整回滚（dbx「有 before-image 才回滚」的半自动落地，P2-F5 降级方案；
 *      262 连接协议不可程序化快照的定夺见 {@link RollbackSnapshotStore} KDoc）——粘贴生成的回填 INSERT
 *      在快照条目持有 identity 信息（O-52：{@link RollbackSnapshotStore.SnapshotEntry} 的
 *      {@code dialect} / {@code identityColumns}）时按方言包装，否则不包装；</li>
 *  <li>Unable（完全不可解析/MERGE/DDL）：不进本视图，面板入口直接消息框（计划 §2.2）。</li>
 * </ul>
 *
 * <p>「执行回滚」不走
 * {@code cn.song.mybatisplus.visualizer.execute.SqlExecutor.execute} 全链弹数据源
 * 选择——持预览面板引用走 {@link SqlPreviewPanel#executeAdhocSql}，T-RefreshDbSteal 所有权卫队天然生效；
 * 执行提交后快照条目置「已执行」（F6 留痕）+ 横幅更新。
 */
public class RollbackSqlDialog extends DialogWrapper {

    private final Project project;
    /** 预览面板引用——「执行回滚/执行快照查询」复用 executeAdhocSql 管线（计划 §2.4）。 */
    private final SqlPreviewPanel panel;
    /** 生成结果（面板入口已按「Unable 且无快照工作流」过滤）。 */
    private final RollbackSqlGenerator.RollbackResult result;
    /** 原语句关键字（INSERT/UPDATE/DELETE）——快照回填模式分派。 */
    private final String statementKind;
    /** 经执行按钮执行过的 UPDATE/DELETE 登记条目（F6 留痕；非执行路径入口为 null）。 */
    private final RollbackSnapshotStore.SnapshotEntry snapshotEntry;

    private final JBLabel bannerLabel = new JBLabel();
    private final JBLabel warningLabel = new JBLabel();
    private final JBLabel countCheckLabel = new JBLabel();
    private final JBLabel snapshotQueryLabel = new JBLabel();

    private final JBTextArea pasteArea = createPasteArea();
    private final JBTextField pkField = new JBTextField(20);
    @Nullable
    private Editor editor = null;

    private static JBTextArea createPasteArea() {
        JBTextArea area = new JBTextArea(4, 60);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }

    // 底部动作以字段持有——DialogWrapper 无公开 getActions()（javap 实证），粘贴生成后
    // 启用态只能经自身引用刷新；字段初始化先于 init()（createActions 在 init() 内调用）
    private final Action executeAction = new DialogWrapperAction(MpSqlVisualizerBundle.message("rollback.action.execute")) {
        @Override
        protected void doAction(java.awt.event.ActionEvent actionEvent) {
            executeRollback();
        }
    };
    private final Action copyAction = new DialogWrapperAction(MpSqlVisualizerBundle.message("rollback.action.copy")) {
        @Override
        protected void doAction(java.awt.event.ActionEvent actionEvent) {
            copyRollback();
        }
    };

    /** 粘贴生成后的最新回滚结果（执行回滚/复制按钮取此值）。 */
    private RollbackSqlGenerator.RollbackResult currentResult;

    public RollbackSqlDialog(@Nullable Project project,
                             @Nullable SqlPreviewPanel panel,
                             RollbackSqlGenerator.RollbackResult result,
                             String statementKind) {
        this(project, panel, result, statementKind, null);
    }

    public RollbackSqlDialog(@Nullable Project project,
                             @Nullable SqlPreviewPanel panel,
                             RollbackSqlGenerator.RollbackResult result,
                             String statementKind,
                             @Nullable RollbackSnapshotStore.SnapshotEntry snapshotEntry) {
        super(project, false, DialogWrapper.IdeModalityType.MODELESS);
        this.project = project;
        this.panel = panel;
        this.result = result;
        this.statementKind = statementKind;
        this.snapshotEntry = snapshotEntry;
        this.currentResult = result;
        setTitle(MpSqlVisualizerBundle.message("rollback.dialog.title"));
        setCancelButtonText(MpSqlVisualizerBundle.message("rollback.action.close"));
        init();
        assembleBanner();
        assembleSnapshotSection();
    }

    /** 生成结果（面板入口已按「Unable 且无快照工作流」过滤）。 */
    public RollbackSqlGenerator.RollbackResult getResult() {
        return result;
    }

    /** 经执行按钮执行过的 UPDATE/DELETE 登记条目（F6 留痕；非执行路径入口为 null）。 */
    @Nullable
    public RollbackSnapshotStore.SnapshotEntry getSnapshotEntry() {
        return snapshotEntry;
    }

    /** 粘贴生成后的最新回滚结果（执行回滚/复制按钮取此值）。 */
    public RollbackSqlGenerator.RollbackResult getCurrentResult() {
        return currentResult;
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel body = new JBPanel<>(new BorderLayout());
        body.setOpaque(false);
        body.add(buildTopBlock(), BorderLayout.NORTH);
        body.add(buildEditorBlock(), BorderLayout.CENTER);
        if (hasSnapshotWorkflow()) body.add(buildSnapshotBlock(), BorderLayout.SOUTH);
        body.setPreferredSize(new Dimension(DIALOG_WIDTH, DIALOG_HEIGHT));
        return body;
    }

    // ------------------------------------------------------------------
    // 组装
    // ------------------------------------------------------------------

    private JComponent buildTopBlock() {
        JPanel block = new JBPanel<>(new BorderLayout());
        block.setOpaque(false);
        block.setBorder(JBUI.Borders.empty(6, 10, 2, 10));
        block.add(bannerLabel, BorderLayout.NORTH);
        block.add(warningLabel, BorderLayout.CENTER);
        if (currentResult.countCheckSql() != null) {
            countCheckLabel.setText(countCheckHtml());
            countCheckLabel.setToolTipText(currentResult.countCheckSql());
            block.add(countCheckLabel, BorderLayout.SOUTH);
        }
        return block;
    }

    private JComponent buildEditorBlock() {
        String sqlText = String.join("\n", currentResult.statements());
        com.intellij.openapi.editor.Document doc = EditorFactory.getInstance().createDocument(sqlText);
        Editor created;
        if (project != null) {
            created = EditorFactory.getInstance().createEditor(doc, project, sqlFileType(), true);
        } else {
            created = EditorFactory.getInstance().createViewer(doc);
        }
        // T-PreviewWhitespaceToggle O-44a：空白符可视化（默认关=不设置该属性、继承 IDE 全局）。
        // 本编辑器此前零外观设置（只读回滚 SQL），故是唯一不落在 EditorEx.apply 块内的接线点。
        EditorWhitespaceSettings.applyTo(created);
        editor = created;
        // 编辑器随弹窗 disposable 树级联释放（Disposer 树铁律）
        Disposer.register(getDisposable(), this::releaseEditor);
        JBScrollPane pane = new JBScrollPane(created.getComponent());
        pane.setPreferredSize(new Dimension(DIALOG_WIDTH, EDITOR_HEIGHT));
        return pane;
    }

    private boolean hasSnapshotWorkflow() {
        return currentResult.snapshotSelectSql() != null && panel != null;
    }

    private JComponent buildSnapshotBlock() {
        JPanel block = new JBPanel<>(new BorderLayout());
        block.setOpaque(false);
        block.setBorder(JBUI.Borders.empty(4, 10, 6, 10));

        JPanel topRow = new JBPanel<>(new FlowLayout(FlowLayout.LEFT, 4, 2));
        topRow.setOpaque(false);
        topRow.add(snapshotQueryLabel);
        JButton runButton = new JButton(MpSqlVisualizerBundle.message("rollback.snapshot.run"));
        runButton.addActionListener(e -> runSnapshotQuery());
        topRow.add(runButton);
        JButton copyButton = new JButton(MpSqlVisualizerBundle.message("rollback.snapshot.copy"));
        copyButton.addActionListener(e -> {
            String sql = currentResult.snapshotSelectSql();
            if (sql == null) return;
            copyToClipboard(sql);
        });
        topRow.add(copyButton);
        block.add(topRow, BorderLayout.NORTH);

        JPanel center = new JBPanel<>(new BorderLayout(0, 4));
        center.setOpaque(false);
        center.add(new JBScrollPane(pasteArea), BorderLayout.CENTER);
        JPanel bottomRow = new JBPanel<>(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bottomRow.setOpaque(false);
        bottomRow.add(new JBLabel(MpSqlVisualizerBundle.message("rollback.snapshot.pk")));
        bottomRow.add(pkField);
        JButton generateButton = new JButton(MpSqlVisualizerBundle.message("rollback.snapshot.generate"));
        generateButton.addActionListener(e -> generateFromPaste());
        bottomRow.add(generateButton);
        center.add(bottomRow, BorderLayout.SOUTH);
        block.add(center, BorderLayout.CENTER);
        return block;
    }

    private void assembleBanner() {
        String textKey;
        JBColor color;
        if (snapshotEntry != null && snapshotEntry.status() == RollbackSnapshotStore.Status.ROLLBACK_EXECUTED) {
            textKey = "rollback.banner.executed";
            color = SUCCESS_COLOR;
        } else if (currentResult.rating() == RollbackSqlGenerator.Rating.COMPLETE) {
            textKey = "rollback.banner.complete";
            color = SUCCESS_COLOR;
        } else if (currentResult.rating() == RollbackSqlGenerator.Rating.PARTIAL) {
            textKey = "rollback.banner.partial";
            color = WARNING_COLOR;
        } else {
            textKey = "rollback.banner.unable";
            color = ERROR_COLOR;
        }
        bannerLabel.setText(MpSqlVisualizerBundle.message(textKey));
        bannerLabel.setForeground(color);
        warningLabel.setText(warningsHtml(currentResult));
        warningLabel.setForeground(JBColor.foreground());
    }

    private void assembleSnapshotSection() {
        String sql = currentResult.snapshotSelectSql();
        if (sql == null) return;
        String shown = sql.length() > SNAPSHOT_DISPLAY_LIMIT ? sql.substring(0, SNAPSHOT_DISPLAY_LIMIT) + "…" : sql;
        snapshotQueryLabel.setText("<html><b>" + MpSqlVisualizerBundle.message("rollback.snapshot.section") + "</b> <code>" + shown + "</code></html>");
        snapshotQueryLabel.setToolTipText(sql);
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    @Override
    protected Action[] createActions() {
        boolean hasStatements = !currentResult.statements().isEmpty();
        executeAction.setEnabled(hasStatements);
        copyAction.setEnabled(hasStatements);
        return new Action[]{executeAction, copyAction, getCancelAction()};
    }

    /** 「执行回滚」：经预览面板 executeAdhocSql 复用执行管线（DML 二次确认内置）。 */
    public void executeRollback() {
        String sql = String.join("\n", currentResult.statements());
        if (sql.isBlank()) return;
        if (panel != null) panel.executeAdhocSql(sql, false, snapshotEntry);
        // F6 留痕：回滚已提交执行——快照条目置终态 + 横幅更新（执行结果反馈走面板 resultPanel）
        if (snapshotEntry != null && project != null) {
            RollbackSnapshotStore store = project.getService(RollbackSnapshotStore.class);
            if (store != null) store.markRollbackExecuted(snapshotEntry);
        }
        bannerLabel.setText(MpSqlVisualizerBundle.message("rollback.banner.executed"));
        bannerLabel.setForeground(SUCCESS_COLOR);
    }

    /** 「复制」：回滚 SQL 入剪贴板。 */
    public boolean copyRollback() {
        String sql = String.join("\n", currentResult.statements());
        if (sql.isBlank()) return false;
        copyToClipboard(sql);
        return true;
    }

    /** 「执行快照查询」：查询类语句经 executeAdhocSql 免确认直跑，结果落面板控制台网格。 */
    public void runSnapshotQuery() {
        String sql = currentResult.snapshotSelectSql();
        if (sql == null) return;
        if (panel != null) panel.executeAdhocSql(sql);
    }

    /** 「生成回滚」：粘贴 TSV → 快照回填（按原语句类型分派）→ 刷新编辑器与横幅。 */
    public void generateFromPaste() {
        String table = currentResult.targetTable();
        if (table == null) return;
        String pasteText = pasteArea.getText();
        kotlin.Pair<List<String>, List<List<String>>> parsed =
                RollbackSqlGenerator.parseSnapshotTsv(pasteText != null ? pasteText : "");
        if (parsed == null) {
            warningLabel.setText("<html>" + escapeHtml(MpSqlVisualizerBundle.message("rollback.snapshot.parseFailed")) + "</html>");
            return;
        }
        List<String> columns = parsed.getFirst();
        List<List<String>> rows = parsed.getSecond();
        List<String> pk = null;
        String pkText = pkField.getText();
        if (pkText != null) {
            List<String> trimmed = new ArrayList<>();
            for (String part : pkText.split(",")) {
                String p = part.trim();
                if (!p.isEmpty()) trimmed.add(p);
            }
            if (!trimmed.isEmpty()) pk = trimmed;
        }
        RollbackSqlGenerator.SnapshotMode mode;
        switch (statementKind.toUpperCase()) {
            case "UPDATE":
                mode = RollbackSqlGenerator.SnapshotMode.REVERT_UPDATE;
                break;
            case "DELETE":
                mode = RollbackSqlGenerator.SnapshotMode.BACKFILL_INSERT;
                break;
            default:
                mode = RollbackSqlGenerator.SnapshotMode.REVERT_INSERT;
                break;
        }
        // O-59 T-IIR-02：identity 列**在消费点解析**（而非登记点）。登记点位于执行前调用链上，
        // 在那里同步走 DAS 模型遍历有 EDT 慢操作断言风险（O-59 计划 §六 风险表首条即点名）；
        // 本对话框由用户点击动作打开，且 DatabaseSchemaResolver 本身是缓存优先 + 有界扫描的
        // UI 面，在此解析既避开执行链、又复用既有缓存。条目已带非空清单时优先用条目。
        // （Kotlin: snapshotEntry?.identityColumns?.takeIf { it.isNotEmpty() } ?: autoGeneratedColumnsOf(table)）
        List<String> identityColumns;
        if (snapshotEntry != null && snapshotEntry.identityColumns() != null
                && !snapshotEntry.identityColumns().isEmpty()) {
            identityColumns = snapshotEntry.identityColumns();
        } else {
            identityColumns = autoGeneratedColumnsOf(table);
        }
        RollbackSqlGenerator.RollbackResult generated = RollbackSqlGenerator.generateFromSnapshotRows(
                table,
                columns,
                rows,
                mode,
                pk,
                currentResult.setColumns(),
                currentResult.setValues(),
                // O-52（D1=A）：identity 知识随快照条目留存，此处取回传给生成器——条目未持有
                // （旧条目/未传参的登记点）时保持 null/空 → 不包装，与改前逐字节相同
                snapshotEntry != null ? snapshotEntry.dialect() : null,
                identityColumns);
        if (generated.rating() == RollbackSqlGenerator.Rating.UNABLE) {
            warningLabel.setText(warningsHtml(generated));
            return;
        }
        currentResult = generated;
        refreshAfterPaste();
    }

    /**
     * O-59 T-IIR-02：按表名解析该表的<b>自增列</b>（{@code DasColumn.Attribute.AUTO_GENERATED}）。
     *
     * <p>取不到（无数据源 / 模型未内省 / 表不在模型里 / 反射链异常）一律返回<b>空清单 = 不包装</b>——
     * 与 O-52 D1=A 的「未知不臆造」契约一致（宁可少包装，也不误包装）。
     * 解析走 {@link DatabaseSchemaResolver#columnMeta}（缓存优先；扫描预算与负缓存口径见该类 KDoc）。</p>
     */
    private List<String> autoGeneratedColumnsOf(@Nullable String table) {
        if (table == null || table.isBlank()) return List.of();
        if (project == null) return List.of();
        try {
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, DatabaseSchemaResolver.ColumnMeta> entry
                    : DatabaseSchemaResolver.columnMeta(project, table).entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue().autoGenerated())) out.add(entry.getKey());
            }
            return out;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** 粘贴生成成功后的视图刷新（编辑器内容 + 横幅 + 底部动作启用态）。 */
    private void refreshAfterPaste() {
        String text = String.join("\n", currentResult.statements());
        Editor ed = editor;
        if (ed != null) {
            com.intellij.openapi.editor.Document doc = ed.getDocument();
            if (doc != null && !doc.getText().equals(text)) {
                // 文档写入须写操作语境（测试实证：裸 setText 在平台断言下硬抛）
                if (project != null) {
                    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, () -> doc.setText(text));
                } else {
                    doc.setText(text);
                }
            }
        }
        assembleBanner();
        boolean hasStatements = !currentResult.statements().isEmpty();
        executeAction.setEnabled(hasStatements);
        copyAction.setEnabled(hasStatements);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private String countCheckHtml() {
        String countCheckSql = currentResult.countCheckSql();
        return "<html><span style='color:gray'>" + escapeHtml(MpSqlVisualizerBundle.message("rollback.countcheck.hint")) + "</span> "
                + "<code>" + escapeHtml(countCheckSql != null ? countCheckSql : "") + "</code></html>";
    }

    private String warningsHtml(RollbackSqlGenerator.RollbackResult r) {
        if (r.warnings().isEmpty()) return "";
        StringBuilder items = new StringBuilder();
        for (RollbackSqlGenerator.RollbackWarning warning : r.warnings()) {
            String text;
            if (warning.messageArgs().isEmpty()) {
                text = MpSqlVisualizerBundle.message(warning.messageKey());
            } else {
                text = MpSqlVisualizerBundle.message(warning.messageKey(), warning.messageArgs().toArray());
            }
            items.append("<li>").append(escapeHtml(text)).append("</li>");
        }
        return "<html><ul style='margin-left:12px'>" + items + "</ul></html>";
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private void releaseEditor() {
        Editor ed = editor;
        if (ed != null) {
            EditorFactory.getInstance().releaseEditor(ed);
            editor = null;
        }
    }

    private com.intellij.openapi.fileTypes.FileType sqlFileType() {
        return com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getStdFileType("SQL");
    }

    @Override
    public String getDimensionServiceKey() {
        return DIMENSION_KEY;
    }

    /** T-RollbackSql：程序化关闭（同 SqlPreviewDialog.doDispose 惯例；测试 tearDown 用）。 */
    public void doDispose() {
        dispose();
    }

    /** 释放自建编辑器（DialogWrapper 不感知 EditorFactory 产物，须自行兜底）。 */
    @Override
    public void dispose() {
        releaseEditor();
        super.dispose();
    }

    // ------------------------------------------------------------------
    // 测试访问（同模块测试使用，仿 SqlPreviewPanel 访问器惯例）
    // ------------------------------------------------------------------

    public String bannerTextForTest() {
        return bannerLabel.getText();
    }

    public String warningsTextForTest() {
        return warningLabel.getText();
    }

    public String sqlTextForTest() {
        Editor ed = editor;
        return ed != null && ed.getDocument() != null ? ed.getDocument().getText() : "";
    }

    public JBTextArea pasteAreaForTest() {
        return pasteArea;
    }

    public JBTextField pkFieldForTest() {
        return pkField;
    }

    public Action executeActionForTest() {
        return executeAction;
    }

    public Action copyActionForTest() {
        return copyAction;
    }

    public JBLabel snapshotQueryLabelForTest() {
        return snapshotQueryLabel;
    }

    /** 尺寸记忆 key。 */
    public static final String DIMENSION_KEY = "cn.song.mybatisplus.visualizer.RollbackSqlDialog";

    private static final int DIALOG_WIDTH = 720;
    private static final int DIALOG_HEIGHT = 460;
    private static final int EDITOR_HEIGHT = 180;

    /** 快照 SQL 行内展示截断长度（全文在 tooltip）。 */
    private static final int SNAPSHOT_DISPLAY_LIMIT = 80;

    private static final JBColor SUCCESS_COLOR = JBColor.namedColor("RollbackSql.complete", new java.awt.Color(0x2E7D32));
    private static final JBColor WARNING_COLOR = JBColor.namedColor("RollbackSql.partial", new java.awt.Color(0xB26A00));
    private static final JBColor ERROR_COLOR = JBColor.namedColor("ErrorForeground", JBColor.RED);
}
