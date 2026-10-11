// T-SyncK2JMaster187 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlSuppressManagerDialog.kt 移植
package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.ui.EditorWhitespaceSettings;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlListSyntaxHighlighter;
import cn.song.mybatisplus.visualizer.util.EditorCopyShortcuts;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.Splitter;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * SQL 屏蔽规则管理对话框（T-SqlSuppress O-69，F5）：左规则列表 + 右只读高亮编辑器。
 *
 * <p>入口（D1 定案双入口开同一对话框）：日志面板工具栏「管理屏蔽规则…」（主）+ 列表右键 /
 * 屏蔽 balloon「管理屏蔽规则…」动作（辅）。非模态（与 SqlPreviewDialog 同形态）。
 *
 * <p>操作（关闭即生效——规则变更实时经 {@link SqlSuppressRuleManager} 通知链落地，<b>不设</b>
 * 「确定/应用」缓存层，避免与持久化服务双源）：新增（右侧编辑器进入可编辑态，粘贴 SQL 后确认，
 * 按规范化文本成规则）、删除单条、清空（确认后全清）。
 *
 * <p>右侧编辑器照抄 {@code SqlClickPreview.buildContent} 的写法：EditorFactory.createDocument/createEditor
 * + {@code (editor as? EditorEx)} 挂 {@link LexerEditorHighlighter}({@link SqlListSyntaxHighlighter},
 * globalScheme)（与日志列表共用同一 token→配色映射，不依赖 Database 插件，缺席环境仍高亮）；
 * 行号按需求截图开。切换选中用 runWriteAction 直写 document 换文，<b>不重建 editor</b>。
 *
 * <p>editor 释放路径：{@code ParamImportDialog} 的成熟路径 = 覆写 {@link #dispose()}，在
 * {@code super.dispose()} 前 {@code EditorFactory.releaseEditor} 并置 null。注意 N3 教训：本类内部如需
 * 挂平台监听，父 disposable 必须用 {@code disposable} 而非 {@code this}（本类目前无平台监听，仅防回归记录）。
 *
 * <p>移植注记（构造期回调纪律）：平台在 {@link #init()}（构造体内）回调 createCenterPanel 等
 * override——被回调方法引用的字段全部以字段初始化器声明（Java 字段初始化先于构造体执行，
 * 与 Kotlin「属性声明先于 init 块」语义对齐）。
 */
public class SqlSuppressManagerDialog extends DialogWrapper {

    private final Project project;

    private final SqlSuppressRuleManager ruleManager = SqlSuppressRuleManager.getInstance();

    /**
     * 时间格式化（ThreadLocal 复用；照 LogTimestampParser 范式）。
     *
     * <p><b>声明位置是契约</b>（T-DevAcceptFix2 ③，20261006 验收 NPE 修复）：本字段必须声明在 {@link #init()} 块
     * <b>之前</b>——{@code init { … refreshRuleList(select = null) }} 在构造期就触发列表渲染，渲染器回调
     * {@link #createdAtText} 读本字段。Kotlin 属性按声明序初始化，声明在后 = 构造期读取时为 null
     * （引用类型默认值，编译期不报错）→ 规则数 ≥1 时每次打开对话框必 NPE。
     */
    private final ThreadLocal<SimpleDateFormat> createdAtFormat = ThreadLocal.withInitial(
            () -> new SimpleDateFormat("yyyy-MM-dd HH:mm"));

    private final CollectionListModel<SqlSuppressRule> listModel = new CollectionListModel<>();
    private final JBList<SqlSuppressRule> ruleList = new JBList<>(listModel);

    /** 只读高亮编辑器（F5）；null 仅在 dispose 之后。 */
    private Editor editor = null;

    /** 新增模式标记：新增期间右侧编辑器可编辑，选中切换不改写文档。 */
    private boolean adding = false;

    private final JBLabel statusLabel = new JBLabel();

    /**
     * T-SqlSuppressUx 二段：结构匹配开关（用户定案手动开启）。作用于<b>选中规则</b>，标签随选中态
     * 切换（开启/关闭结构匹配）；批量入口 = {@link #structureAllCheckBox}（全部开/全部关）。
     */
    private final JButton structureButton = new JButton(
            MpSqlVisualizerBundle.message("log.suppress.dialog.structure.toggleOn"));

    /** 批量开关：勾选 = 全部规则结构匹配开；取消 = 全关。派生状态由 {@link #syncStructureControls()} 回写。 */
    private final JBCheckBox structureAllCheckBox = new JBCheckBox(
            MpSqlVisualizerBundle.message("log.suppress.dialog.structure.all"));

    private final JButton addButton = new JButton(MpSqlVisualizerBundle.message("log.suppress.dialog.add"));

    private final JButton cancelAddButton = new JButton(MpSqlVisualizerBundle.message("log.suppress.dialog.cancelAdd"));

    public SqlSuppressManagerDialog(@NotNull Project project) {
        super(project, false, DialogWrapper.IdeModalityType.MODELESS);
        this.project = project;
        statusLabel.setForeground(UIUtil.getInactiveTextColor());
        structureButton.setFocusable(false);
        structureButton.setEnabled(false);
        structureButton.addActionListener(e -> toggleStructureForSelected());
        structureAllCheckBox.setFocusable(false);
        structureAllCheckBox.addActionListener(e -> toggleStructureAll(structureAllCheckBox.isSelected()));
        addButton.setFocusable(false);
        addButton.addActionListener(e -> onAddClicked());
        cancelAddButton.setFocusable(false);
        cancelAddButton.setVisible(false);
        cancelAddButton.addActionListener(e -> {
            exitAddMode();
            refreshRuleList(null); // 放弃新增：恢复选中项内容
        });
        setTitle(MpSqlVisualizerBundle.message("log.suppress.dialog.title"));
        setCancelButtonText(MpSqlVisualizerBundle.message("log.suppress.dialog.close"));
        // T-SqlSuppressDialogResize（O-80，20260924 用户走查）：DialogWrapper 默认 fixed 不可拖拽缩放，
        // 显式开启（照 ErrorReportDialog「拖拽：isResizable=true + getDimensionServiceKey 记忆尺寸」惯例）；
        // 有记忆时 DimensionService 直接给尺寸，setSize 仅作首次打开（无记忆）的缺省。
        setResizable(true);
        init();
        setSize(DIALOG_WIDTH, DIALOG_HEIGHT);
        refreshRuleList(null);
    }

    /** O-80：尺寸/位置记忆（RollbackSqlDialog/SqlPreviewDialog/ErrorReportDialog 同惯例，零自持久化）。 */
    @Override
    @Nullable
    protected String getDimensionServiceKey() {
        return DIMENSION_SERVICE_KEY;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        ruleList.setCellRenderer(new RuleRowRenderer());
        ruleList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ruleList.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || adding) {
                return;
            }
            updateEditorFromSelection();
            syncStructureControls();
        });

        // 左：规则列表 + 操作按钮行；右：只读高亮编辑器 + 状态行（新增提示/重名/上限）+ 常驻说明
        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttonRow.setOpaque(false);
        buttonRow.add(addButton);
        buttonRow.add(cancelAddButton);
        JButton deleteButton = new JButton(MpSqlVisualizerBundle.message("log.suppress.dialog.delete"));
        deleteButton.setFocusable(false);
        deleteButton.addActionListener(e -> deleteSelectedRule());
        buttonRow.add(deleteButton);
        JButton clearButton = new JButton(MpSqlVisualizerBundle.message("log.suppress.dialog.clear"));
        clearButton.setFocusable(false);
        clearButton.addActionListener(e -> clearAllRules());
        buttonRow.add(clearButton);
        buttonRow.add(structureButton);
        buttonRow.add(structureAllCheckBox);

        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.add(new JBScrollPane(ruleList), BorderLayout.CENTER);
        leftPanel.add(buttonRow, BorderLayout.SOUTH);

        // 常驻说明（恢复语义落差，计划 §六）：JTextArea 自动折行（JBLabel 不折行会撑宽对话框）
        JTextArea noteArea = new JTextArea(MpSqlVisualizerBundle.message("log.suppress.dialog.restoreNote"));
        noteArea.setEditable(false);
        noteArea.setOpaque(false);
        noteArea.setLineWrap(true);
        noteArea.setWrapStyleWord(true);
        noteArea.setBorder(null);
        noteArea.setForeground(UIUtil.getInactiveTextColor());
        noteArea.setBackground(null);

        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.add(createEditorPane(), BorderLayout.CENTER);
        rightPanel.add(noteArea, BorderLayout.SOUTH);

        // 左右分栏：com.intellij.openapi.ui.Splitter（LogToSqlPanel/SqlPreviewPanel/SqlToJavaPanel 同款
        // 主题化分栏）。此前误 import com.intellij.ui.Splitter 编译失败，误判「不在 classpath」退回原生
        // JSplitPane——其分隔条在新 UI 深色主题下呈白条（20260924 用户走查截图），换仓内既有 Splitter 根除。
        Splitter centerPanel = new Splitter(false, SPLIT_PROPORTION);
        centerPanel.setFirstComponent(leftPanel);
        JPanel secondPanel = new JPanel(new BorderLayout());
        secondPanel.add(rightPanel, BorderLayout.CENTER);
        secondPanel.add(statusLabel, BorderLayout.SOUTH);
        centerPanel.setSecondComponent(secondPanel);
        // O-80：最小尺寸兜底（SqlPreviewDialog 同款——DialogWrapper 无 setMinimumSize，设在中心面板；
        // 下限=按钮行不折行 + 编辑器/列表可读，D3 默认 A 单行按钮）
        centerPanel.setMinimumSize(new Dimension(MIN_WIDTH, MIN_HEIGHT));
        return centerPanel;
    }

    /**
     * 规则行渲染器：对齐日志列表（LogEntryListCellRenderer）的两行形态——SQL 主体行在上、
     * 元信息行（创建时间 · 命中计数）在下灰字；行底分隔线与内边距同款
     * （matte bottom {@link JBColor#border()} + EmptyBorder）；选中态背景/前景取平台列表选中色。
     */
    private class RuleRowRenderer extends JPanel implements ListCellRenderer<SqlSuppressRule> {

        private final SimpleColoredComponent patternLine = new SimpleColoredComponent();
        private final SimpleColoredComponent metaLine = new SimpleColoredComponent();
        private final JPanel linesPanel = new JPanel(new GridLayout(0, 1));

        RuleRowRenderer() {
            super(new BorderLayout());
            setOpaque(true);
            patternLine.setOpaque(false);
            metaLine.setOpaque(false);
            linesPanel.setOpaque(false);
            linesPanel.add(patternLine);
            linesPanel.add(metaLine);
            add(linesPanel, BorderLayout.CENTER);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                    new EmptyBorder(4, 8, 4, 8)));
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends SqlSuppressRule> list,
                SqlSuppressRule value,
                int index,
                boolean selected,
                boolean hasFocus) {
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            Color fg = selected ? list.getSelectionForeground() : list.getForeground();
            patternLine.setForeground(fg);
            patternLine.clear();
            patternLine.append(value.patternSummary(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
            metaLine.setForeground(selected ? fg : UIUtil.getInactiveTextColor());
            metaLine.clear();
            metaLine.append(createdAtText(value), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            metaLine.append("  ·  ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            metaLine.append(
                    MpSqlVisualizerBundle.message(
                            "log.suppress.dialog.hitCount",
                            String.valueOf(ruleManager.hitCount(value.getId()))),
                    SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            if (value.isStructureMatch()) {
                metaLine.append(
                        "  ·  " + MpSqlVisualizerBundle.message("log.suppress.dialog.structure.badge"),
                        SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
            }
            return this;
        }
    }

    /**
     * 创建只读高亮编辑器（高亮写法照抄 {@code SqlClickPreview.buildContent}；行号开 = 需求截图外观）。
     * 初始为 viewer 态；新增模式下临时切可编辑。
     */
    private JComponent createEditorPane() {
        Document document = EditorFactory.getInstance().createDocument("");
        Editor created = EditorFactory.getInstance().createEditor(document, project);
        if (created instanceof EditorEx) {
            EditorEx editorEx = (EditorEx) created;
            // createEditor 静态类型为 Editor，setHighlighter 在 EditorEx；非 EditorEx 实现时跳过（降级纯文本）
            editorEx.setHighlighter(new LexerEditorHighlighter(
                    new SqlListSyntaxHighlighter(),
                    EditorColorsManager.getInstance().getGlobalScheme()));
            editorEx.getSettings().setLineNumbersShown(true);
            editorEx.getSettings().setFoldingOutlineShown(false);
            editorEx.getSettings().setIndentGuidesShown(false);
            editorEx.getSettings().setAdditionalPageAtBottom(false);
            editorEx.getSettings().setCaretRowShown(false);
            editorEx.getSettings().setUseSoftWraps(true); // 长 SQL 软换行免横向滚动
            EditorWhitespaceSettings.applyTo(editorEx);
        }
        // setViewer 在 EditorEx（243 javap 实证：Editor 只有 isViewer() 读、EditorEx 才有 setViewer(boolean) 写）；
        // 非 EditorEx 实现则只读不可切（防御）
        if (created instanceof EditorEx) {
            ((EditorEx) created).setViewer(true);
        }
        // T-SqlCopyShortcuts O-122：无选中 Ctrl+C 复制全文、有选中只复制片段
        //（层位与理由见 EditorCopyShortcuts 的 KDoc；本处此前无任何键盘复制绑定）
        EditorCopyShortcuts.install(created, UiTextClipboard::copy);
        editor = created;
        return created.getComponent();
    }

    /** 选中规则 → 只读编辑器换文（runWriteAction 直写 document，不重建 editor）。 */
    private void updateEditorFromSelection() {
        Editor ed = editor;
        if (ed == null) {
            return;
        }
        SqlSuppressRule selected = ruleList.getSelectedValue();
        String text = selected != null ? selected.getSampleSql() : "";
        ApplicationManager.getApplication().runWriteAction(() -> {
            if (!ed.getDocument().getText().equals(text)) {
                ed.getDocument().setText(text);
            }
        });
    }

    // ---------- 规则操作 ----------

    /** 列表模型按 manager 当前规则重建；{@code select} 非 null 时按 id 选中（增删后保持视点）。 */
    private void refreshRuleList(@Nullable SqlSuppressRule select) {
        List<SqlSuppressRule> rules = ruleManager.getRules();
        listModel.replaceAll(rules);
        SqlSuppressRule target = null;
        if (select != null) {
            for (SqlSuppressRule next : rules) {
                if (next.getId().equals(select.getId())) {
                    target = next;
                    break;
                }
            }
        }
        if (target == null && !rules.isEmpty()) {
            target = rules.get(0);
        }
        if (target != null) {
            ruleList.setSelectedValue(target, false);
        } else {
            ruleList.clearSelection();
        }
        updateEditorFromSelection();
        statusLabel.setText(rules.isEmpty()
                ? MpSqlVisualizerBundle.message("log.suppress.dialog.empty") : "");
        syncStructureControls();
    }

    /** 新增：未在新增态 → 进入（编辑器清空可编辑）；已在新增态 → 确认（读编辑器文本成规则）。 */
    private void onAddClicked() {
        if (!adding) {
            adding = true;
            ruleList.clearSelection();
            Editor ed = editor;
            if (ed == null) {
                return;
            }
            if (ed instanceof EditorEx) {
                ((EditorEx) ed).setViewer(false);
            }
            ApplicationManager.getApplication().runWriteAction(() -> {
                if (!ed.getDocument().getText().isEmpty()) {
                    ed.getDocument().setText("");
                }
            });
            addButton.setText(MpSqlVisualizerBundle.message("log.suppress.dialog.addConfirm"));
            cancelAddButton.setVisible(true);
            statusLabel.setText(MpSqlVisualizerBundle.message("log.suppress.dialog.addHint"));
            return;
        }
        Editor ed = editor;
        if (ed == null) {
            return;
        }
        String text = ed.getDocument().getText();
        if (text.isBlank()) {
            statusLabel.setText(MpSqlVisualizerBundle.message("log.suppress.dialog.blank"));
            return;
        }
        SqlSuppressRuleManager.AddResult result = ruleManager.addRule(text);
        if (result == SqlSuppressRuleManager.AddResult.ADDED) {
            String normalized = LogSqlModelsKt.normalizeSqlWhitespace(text);
            SqlSuppressRule created = null;
            for (SqlSuppressRule it : ruleManager.getRules()) {
                if (it.getPattern().equals(normalized)) {
                    created = it;
                    break;
                }
            }
            exitAddMode();
            refreshRuleList(created);
        } else if (result == SqlSuppressRuleManager.AddResult.DUPLICATE) {
            statusLabel.setText(MpSqlVisualizerBundle.message("log.suppress.dialog.duplicate"));
        } else if (result == SqlSuppressRuleManager.AddResult.LIMIT_REACHED) {
            statusLabel.setText(MpSqlVisualizerBundle.message(
                    "log.suppress.limit",
                    String.valueOf(SqlSuppressRuleManager.MAX_RULES)));
        }
    }

    /** 退出新增态：编辑器回 viewer，按钮复位（列表/编辑器内容由调用方刷新）。 */
    private void exitAddMode() {
        adding = false;
        if (editor instanceof EditorEx) {
            ((EditorEx) editor).setViewer(true);
        }
        addButton.setText(MpSqlVisualizerBundle.message("log.suppress.dialog.add"));
        cancelAddButton.setVisible(false);
    }

    /**
     * 切换选中规则的结构匹配（T-SqlSuppressUx 二段：手动开启/关闭），刷新列表徽标、按钮标签与提示。
     * 语义提示走 {@link #statusLabel}（结构匹配会把「参数值不同的同形状 SQL」一并拦下，须让用户可见）。
     */
    private void toggleStructureForSelected() {
        SqlSuppressRule rule = ruleList.getSelectedValue();
        if (rule == null) {
            return;
        }
        Boolean newState = ruleManager.setStructureMatch(rule.getId(), !rule.isStructureMatch());
        refreshRuleList(rule);
        statusLabel.setText(MpSqlVisualizerBundle.message(
                Boolean.TRUE.equals(newState)
                        ? "log.suppress.dialog.structure.onHint"
                        : "log.suppress.dialog.structure.offHint"));
    }

    /** 批量：全部规则结构匹配开/关（「全部按结构匹配」勾选框）。 */
    private void toggleStructureAll(boolean enabled) {
        ruleManager.setStructureMatchAll(enabled);
        refreshRuleList(ruleList.getSelectedValue());
        statusLabel.setText(MpSqlVisualizerBundle.message(
                enabled ? "log.suppress.dialog.structure.onHint" : "log.suppress.dialog.structure.offHint"));
    }

    /** 同步结构匹配控件态：按钮标签/可用性随选中规则；勾选框派生自全体规则（全开才勾选）。 */
    private void syncStructureControls() {
        SqlSuppressRule rule = ruleList.getSelectedValue();
        structureButton.setEnabled(rule != null);
        structureButton.setText(MpSqlVisualizerBundle.message(
                rule != null && rule.isStructureMatch()
                        ? "log.suppress.dialog.structure.toggleOff"
                        : "log.suppress.dialog.structure.toggleOn"));
        List<SqlSuppressRule> rules = ruleManager.getRules();
        boolean all = !rules.isEmpty();
        for (SqlSuppressRule it : rules) {
            if (!it.isStructureMatch()) {
                all = false;
                break;
            }
        }
        structureAllCheckBox.setSelected(all);
    }

    /** 删除单条选中规则；删除后仍在缓冲内的条目重新显示（闸门已丢弃的不可恢复——底部常驻说明）。 */
    private void deleteSelectedRule() {
        SqlSuppressRule selected = ruleList.getSelectedValue();
        if (selected == null) {
            return;
        }
        ruleManager.removeRule(selected.getId());
        refreshRuleList(null);
    }

    /** 清空全部规则（确认后执行）。 */
    private void clearAllRules() {
        if (ruleManager.getRules().isEmpty()) {
            return;
        }
        // 弹窗克制红线准入依据（BannedApiSourceScanTest 白名单在册）：清空是不可恢复的破坏性操作，
        // 属「用户必须知情确认」场景——本对话框本体非模态，仅此处用一次模态确认。
        int confirm = Messages.showYesNoDialog(
                project,
                MpSqlVisualizerBundle.message("log.suppress.dialog.clearConfirm"),
                MpSqlVisualizerBundle.message("log.suppress.dialog.title"),
                Messages.getQuestionIcon());
        if (confirm != Messages.YES) {
            return;
        }
        ruleManager.clearRules();
        refreshRuleList(null);
    }

    /** createdAt 展示片段（yyyy-MM-dd HH:mm）。F22（T-PerfSweep9）：SDF ThreadLocal 缓存化
     *  （照 LogTimestampParser 范式；此前每行每次重绘新建一个实例，分配面与 PS7-04 缓存化方向相逆）。
     *  复用实例输出与逐次新建逐字节一致（format 不读旧状态）。
     *  T-DevAcceptFix2 ③：字段本体已上移至类首（构造期渲染回调要读，声明序即契约，见该处注释）。 */
    private String createdAtText(SqlSuppressRule rule) {
        return createdAtFormat.get().format(new Date(rule.getCreatedAt()));
    }

    /** 只挂「关闭」（F5：关闭即生效，无确定/应用缓存层）。 */
    @Override
    protected Action[] createActions() {
        return new Action[]{getCancelAction()};
    }

    /**
     * editor 释放（ParamImportDialog 成熟路径）：平台 close() → dispose() →
     * releaseEditor → super.dispose()。置 null 防二次释放。
     */
    @Override
    public void dispose() {
        if (editor != null) {
            EditorFactory.getInstance().releaseEditor(editor);
            editor = null;
        }
        super.dispose();
    }

    /** 测试访问器：当前规则行数（headless 断言列表同步）。 */
    public int ruleCountForTest() {
        return listModel.getSize();
    }

    /** 测试访问器：触发新增按钮（headless 驱动新增态切换）。 */
    public void triggerAddForTest() {
        onAddClicked();
    }

    /** 测试访问器：当前编辑器文本。 */
    public String editorTextForTest() {
        Editor ed = editor;
        return ed != null ? ed.getDocument().getText() : "";
    }

    /** 测试访问器：设置编辑器文本（模拟用户粘贴）。 */
    public void setEditorTextForTest(String text) {
        if (editor != null) {
            Document doc = editor.getDocument();
            if (doc != null) {
                WriteCommandAction.runWriteCommandAction(project, () -> doc.setText(text));
            }
        }
    }

    public static final int DIALOG_WIDTH = 760;
    public static final int DIALOG_HEIGHT = 480;

    /** O-80：DimensionService 键（尺寸/位置记忆；getDimensionServiceKey 返回）。 */
    public static final String DIMENSION_SERVICE_KEY = "mpsv.sqlsuppress.manager.dialog";

    /** O-80：拖拽下限（中心面板 minimumSize；量级取「按钮行不折行+两侧可读」，真机观感定）。 */
    public static final int MIN_WIDTH = 560;
    public static final int MIN_HEIGHT = 360;
    public static final float SPLIT_PROPORTION = 0.38f;
}
