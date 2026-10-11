package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.ui.EditorWhitespaceSettings;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter;
import cn.song.mybatisplus.visualizer.util.EditorCopyShortcuts;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.icons.AllIcons;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.EditorSettings;
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Splitter;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 「最近执行记录」浏览对话框（T-RecentExecDialog O-118）：gutter 右键菜单二级 ListPopup 的换代形态。
 *
 * <p>交互对齐 {@link SqlSuppressManagerDialog}（同包成熟范式）：非模态 + 可拖拽 + DimensionService 尺寸记忆 +
 * {@code com.intellij.openapi.ui.Splitter} 左右分栏 + 右侧高亮编辑器（<b>T-RecentEditable 起可编辑</b>，与单击预览
 * 浮窗同效果：可临时改字段值再复制；dispose 释放走 ParamImportDialog 成熟路径）；差异点：浏览视图、无增删清，
 * 主按钮「打开预览」开启后<b>不关闭</b>本对话框（连续对比多条）。
 *
 * <p>T-RecentMulti（用户令）：列表支持<b>多选</b>——语义照抄 SQL 日志页：Ctrl/Shift 多选、Ctrl+C 批量复制
 * （{@code SqlEntryList.itemToText} 逐行拼接）、右键命中行已在多选内则保留多选（{@link LogEntryFilteringKt#resolveContextMenuSelection}）、
 * 多选时编辑器按行序拼接显示（与复制产物同源）、状态行「已选 N 条」、主按钮打开 lead（最后交互行）。
 * 单选路径行为逐字节不变。
 *
 * <p>左侧列表直接复用日志面板条目渲染器 {@link LogEntryListCellRenderer}（同包 internal 直用）——
 * 两行形态、类型色条、耗时/来源角标与「SQL 日志」页完全一致（D86/D87 用户拍板：恒弹对话框
 * 含空态、编辑器内容=restoredSql）。
 *
 * <p>右侧编辑器三件（D88=A → D91 用户改定）：<b>恒为格式化显示</b>（{@link SqlFormatter#format}，与预览
 * 「格式化还原 SQL」同源口径；「查看原文」切换已按用户令删除）+「格式化」图标按钮（幂等重排 +
 * 格式化降级后的手动重试）+「复制」（{@link UiTextClipboard}，复制当前显示文本）——两枚按钮取日志转 SQL
 * 结果区同款图标（{@code AllIcons.Actions.ReformatCode} / {@code AllIcons.Actions.Copy}）。
 *
 * <p>列表右键菜单六件（T-RecentExecMenu O-119，形制对齐日志页）：复制时间戳 / 屏蔽此 SQL /
 * 发送到 SQL → Java / 发送到日志转 SQL / 跳转到 Java / 跳转到 Mapper XML——动作不自建，
 * 一律走共享件（{@link LogEntryJumpOps} / {@link LogEntryFilteringKt#batchCopyTimestamps} / {@link SqlSuppressRuleManager} /
 * {@link SqlLogManager#onOpenEntryInTab} 送页签通道）。
 *
 * <p>线程约定：构造与 show() 必须在 EDT（gutter 侧既有 invokeLater 保证）；条目匹配留在调用方
 * 池化线程（{@link EntrySourceMatcher#match} 纯函数），本对话框零 PSI、零 IO。
 *
 * <p>数据链不变（快照校验→后台匹配→本对话框），展示上限沿用 {@link EntrySourceMatcher#MAX_RESULTS}。
 */
public class RecentExecutionsDialog extends DialogWrapper {

    private final Project project;
    private final Consumer<LogSqlEntry> onOpenPreview;

    private final CollectionListModel<LogSqlEntry> listModel = new CollectionListModel<>();
    private final SqlEntryList entryList = new SqlEntryList(listModel);

    /** 日志面板条目渲染器（复用点；字段持有供测试钉断言——JBList getter 平台侧会包 ExpandedItemListCellRendererWrapper 壳）。 */
    private final LogEntryListCellRenderer entryRenderer = new LogEntryListCellRenderer();

    /** 高亮编辑器（T-RecentEditable 起可编辑）；null 仅在 dispose 之后（SqlSuppressManagerDialog 同生命周期约定）。 */
    private Editor editor = null;

    private final JBLabel statusLabel = new JBLabel();

    /** 弹框内可变视图（抑制成功后移除行用；构造期快照的本地副本，不回查日志缓冲）。 */
    private final List<LogSqlEntry> currentEntries = new ArrayList<>();

    /**
     * 「格式化」按钮（D91）：<b>图标形态</b>照抄日志转 SQL 结果区的格式动作（{@code AllIcons.Actions.ReformatCode}
     * 图标 + 悬停文案；实现在 {@code LogToSqlPanel.ToggleFormatAction}，此处取同款图标与 tooltip 形态，
     * 但语义为「重新格式化」——显示恒为格式化态，此按钮是幂等重排 + 格式化降级后的手动重试）。
     */
    private final JButton reformatButton = new JButton(AllIcons.Actions.ReformatCode);

    /**
     * 「复制」按钮（D91）：图标同日志转 SQL 结果区复制动作（{@code AllIcons.Actions.Copy}）；
     * 复制<b>当前显示文本</b>（格式化版），走 {@link UiTextClipboard} 统一收口通道。
     */
    private final JButton copyButton = new JButton(AllIcons.Actions.Copy);

    /**
     * @param entries       反查结果（可空列表——D86=A 空态也弹对话框，状态行显示空态文案）
     * @param anchorLabel   锚点展示片段（「文件名:行号」）；null = 标题不带锚点
     * @param onOpenPreview 打开预览动作（默认 {@link LogEntryPreviewOpener#open} 统一识别通道；
     *                      注入点仅供 headless 测试录制，生产调用方不传）
     */
    public RecentExecutionsDialog(@NotNull Project project, @NotNull List<LogSqlEntry> entries) {
        this(project, entries, null, entry -> LogEntryPreviewOpener.open(project, entry));
    }

    public RecentExecutionsDialog(@NotNull Project project, @NotNull List<LogSqlEntry> entries,
            @Nullable String anchorLabel) {
        this(project, entries, anchorLabel, entry -> LogEntryPreviewOpener.open(project, entry));
    }

    public RecentExecutionsDialog(@NotNull Project project, @NotNull List<LogSqlEntry> entries,
            @Nullable String anchorLabel, @NotNull Consumer<LogSqlEntry> onOpenPreview) {
        super(project, false, DialogWrapper.IdeModalityType.MODELESS);
        this.project = project;
        this.onOpenPreview = onOpenPreview;

        statusLabel.setForeground(UIUtil.getInactiveTextColor());

        reformatButton.setFocusable(false);
        reformatButton.setToolTipText(MpSqlVisualizerBundle.message("log.recent.dialog.format"));
        reformatButton.addActionListener(e -> updateEditorFromSelection());

        copyButton.setFocusable(false);
        copyButton.setToolTipText(MpSqlVisualizerBundle.message("log.recent.dialog.copy"));
        copyButton.addActionListener(e -> copyCurrentSql());

        setTitle(anchorLabel == null || anchorLabel.isBlank()
                ? MpSqlVisualizerBundle.message("log.recent.dialog.title")
                : MpSqlVisualizerBundle.message("log.recent.dialog.title.anchor", anchorLabel));
        setOKButtonText(MpSqlVisualizerBundle.message("log.recent.dialog.openPreview"));
        setCancelButtonText(MpSqlVisualizerBundle.message("log.recent.dialog.close"));
        // O-80 惯例（SqlSuppressManagerDialog/RollbackSqlDialog 同款）：可拖拽 + 尺寸/位置记忆
        setResizable(true);
        init();
        setSize(DIALOG_WIDTH, DIALOG_HEIGHT);
        refreshEntries(entries);
    }

    /** 尺寸/位置记忆键（O-80 惯例，零自持久化）。 */
    @Override
    protected String getDimensionServiceKey() {
        return DIMENSION_SERVICE_KEY;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        entryList.setCellRenderer(entryRenderer);
        // T-RecentMulti（用户令：本弹框也支持多选）：选择语义照抄 SQL 日志页——Ctrl/Shift 多选、
        // Ctrl+C 批量复制（走 SqlEntryList 的 itemToText 逐行拼接）、右键命中行已在多选内则保留多选。
        // 单选路径行为逐字节不变（编辑器显示单条、状态行计数、按钮动作对象同为该条）。
        entryList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        entryList.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) return;
            updateEditorFromSelection();
            syncSelectionScopedControls();
            syncStatusText();
        });
        // 双击 = 打开预览（与主按钮同路径）；右键 = 条目动作菜单（T-RecentExecMenu，形制同日志页）
        entryList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) doOKAction();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) showContextMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) showContextMenu(e);
            }
        });

        JBScrollPane leftPanel = new JBScrollPane(entryList);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttonRow.setOpaque(false);
        buttonRow.add(reformatButton);
        buttonRow.add(copyButton);
        JPanel rightPanel = new JPanel(new BorderLayout());
        // 编辑器按钮行（D91）：格式化 + 复制，两枚均为图标按钮（图标/形态对齐日志转 SQL 结果区）
        rightPanel.add(buttonRow, BorderLayout.NORTH);
        rightPanel.add(createEditorPane(), BorderLayout.CENTER);

        Splitter centerPanel = new Splitter(false, SPLIT_PROPORTION);
        centerPanel.setFirstComponent(leftPanel);
        centerPanel.setSecondComponent(rightPanel);
        // DialogWrapper 无 setMinimumSize，设在中心面板（SqlSuppressManagerDialog 同款兜底）
        centerPanel.setMinimumSize(new Dimension(MIN_WIDTH, MIN_HEIGHT));

        JPanel root = new JPanel(new BorderLayout());
        root.add(centerPanel, BorderLayout.CENTER);
        statusLabel.setBorder(new EmptyBorder(4, 8, 4, 8));
        root.add(statusLabel, BorderLayout.SOUTH);
        return root;
    }

    /**
     * 创建高亮编辑器（写法照抄 SqlSuppressManagerDialog.createEditorPane；T-RecentEditable 起可编辑：
     * LexerEditorHighlighter + 日志列表同款 token→配色映射，不依赖 Database 插件；行号开、软换行）。
     */
    private JComponent createEditorPane() {
        com.intellij.openapi.editor.Document document = EditorFactory.getInstance().createDocument("");
        Editor created = EditorFactory.getInstance().createEditor(document, project);
        if (created instanceof EditorEx ex) {
            ex.setHighlighter(
                    new LexerEditorHighlighter(
                            new cn.song.mybatisplus.visualizer.ui.highlight.SqlListSyntaxHighlighter(),
                            EditorColorsManager.getInstance().getGlobalScheme()));
            EditorSettings settings = ex.getSettings();
            settings.setLineNumbersShown(true);
            settings.setFoldingOutlineShown(false);
            settings.setIndentGuidesShown(false);
            settings.setAdditionalPageAtBottom(false);
            settings.setCaretRowShown(false);
            settings.setUseSoftWraps(true);
            EditorWhitespaceSettings.applyTo(ex);
            // T-RecentEditable（用户令）：编辑器改**可编辑**，与「SQL 日志单击列表弹出的预览浮窗」同效果
            //（原为 setViewer(true) 只读）。可临时改字段值再复制——复制按钮与 Ctrl+C 都取当前文档文本；
            // 改动随「切换条目 / 点格式化」重新加载而被覆盖（语义同预览浮窗：换条目 = 换内容）。
            // 行号保持开启：本弹框是常驻浏览面板（预览浮窗是临时浮层，采紧凑外观不留 gutter）。
        }
        editor = created;
        // T-SqlCopyShortcuts：无选中 Ctrl+C 复制整个 SQL、有选中只复制片段（与复制按钮同反馈）。
        // 本类此前只有按钮路径——编辑器上按 Ctrl+C 因平台取不到 COPY_PROVIDER 而落回 Swing 无选中 no-op。
        EditorCopyShortcuts.install(
                created,
                UiTextClipboard::copy,
                () -> cn.song.mybatisplus.visualizer.ui.CopyFeedbackKt.flashCopied(copyButton));
        return created.getComponent();
    }

    /**
     * 选中条目 → 编辑器换文（D91：恒为格式化显示，「查看原文」已按用户令退役；T-RecentEditable：编辑器可编辑，
     * runWriteAction 直写不重建 editor）。
     *
     * <p>T-RecentMulti：多选时按行序逐条格式化后换行拼接（与 Ctrl+C 复制产物同源——「所见即复制」）；
     * 单选时与改造前逐字节一致；空选中 → 清空。
     */
    private void updateEditorFromSelection() {
        Editor ed = editor;
        if (ed == null) return;
        List<LogSqlEntry> selected = entryList.getSelectedValuesList();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < selected.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(displayTextFor(selected.get(i)));
        }
        String text = sb.toString();
        ApplicationManager.getApplication().runWriteAction(() -> {
            if (!ed.getDocument().getText().equals(text)) {
                ed.getDocument().setText(text);
                // T-RecentEditable：内容变了就按设置重算程序性折叠（预览浮窗构建时同源调用；
                // 本面板内容随选中变化，故每次换文后重算——关闭态由 applyTo 内部清除，零外观变更）
                cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(ed, text);
            }
        });
    }

    /**
     * 条目 → 编辑器显示文本：格式化（与预览「格式化还原 SQL」同源 {@link SqlFormatter#format}，
     * 含语句结束符——口径对齐预览侧 {@code formatIfNeeded(withTerminator(restoredSql))}）；
     * 格式化异常按容错优先降级原文，不抛、不空屏（此时可用「格式化」按钮手动重试）。
     */
    private String displayTextFor(LogSqlEntry entry) {
        try {
            return SqlFormatter.INSTANCE.format(SqlLogPanelKt.withTerminator(entry.restoredSql()));
        } catch (Throwable ignored) {
            return entry.restoredSql();
        }
    }

    /**
     * 复制当前编辑器显示文本（格式化版）。
     * 通道={@link UiTextClipboard}（T-UiNative 收口：进 IDE 复制历史）；反馈复用通用键
     * {@code dialog.copy.done}（SqlSuppressManagerDialog 状态行承载操作反馈同款）。
     */
    private void copyCurrentSql() {
        Editor ed = editor;
        String text = ed != null ? ed.getDocument().getText() : null;
        if (text == null || text.isEmpty()) return;
        UiTextClipboard.copy(text);
        statusLabel.setText(MpSqlVisualizerBundle.message("dialog.copy.done"));
    }

    /** 列表装载 + 首条默认选中 + 状态行（空态文案/共 N 条）+ 选中态联动控件同步。 */
    private void refreshEntries(List<LogSqlEntry> entries) {
        currentEntries.clear();
        currentEntries.addAll(entries);
        listModel.replaceAll(currentEntries);
        LogSqlEntry first = currentEntries.isEmpty() ? null : currentEntries.get(0);
        if (first != null) entryList.setSelectedValue(first, false);
        syncStatusText();
        syncSelectionScopedControls();
    }

    /**
     * 状态行：空态复用 gutter 菜单既有「未找到」文案（不造重复键）；多选显示「已选 N 条」；
     * 否则「共 N 条」（T-RecentMulti 新增多选态）。
     */
    private void syncStatusText() {
        int selectedCount = entryList.getSelectedIndices().length;
        if (currentEntries.isEmpty()) {
            statusLabel.setText(MpSqlVisualizerBundle.message("gutter.popup.recent.empty"));
        } else if (selectedCount > 1) {
            statusLabel.setText(MpSqlVisualizerBundle.message("log.recent.dialog.selected", Integer.toString(selectedCount)));
        } else {
            statusLabel.setText(MpSqlVisualizerBundle.message("log.recent.dialog.count", Integer.toString(currentEntries.size())));
        }
    }

    /**
     * T-RecentExecMenu（D92=A）：抑制成功后<b>即刻从弹框列表移除该行</b>——与日志页「存量行立即消失」
     * 的最强反馈一致，避免「balloon 说已屏蔽、行还杵在那」。只动弹框本地视图，不回查日志缓冲。
     */
    private void removeEntryRow(LogSqlEntry entry) {
        int index = currentEntries.indexOf(entry);
        if (index < 0) return;
        currentEntries.remove(index);
        listModel.replaceAll(currentEntries);
        if (currentEntries.isEmpty()) {
            entryList.clearSelection();
            updateEditorFromSelection();
        } else {
            // 保持视点：选中原位置（末尾则左移一条）
            entryList.setSelectedIndex(Math.min(index, currentEntries.size() - 1));
        }
        syncStatusText();
        syncSelectionScopedControls();
    }

    /**
     * 选中态联动：主按钮「打开预览」/「格式化」/「复制」的可用态。
     * 空态（无选中）三者全禁用——双击/回车同样无动作。
     */
    private void syncSelectionScopedControls() {
        boolean hasSelection = entryList.getSelectedValue() != null;
        getOKAction().setEnabled(hasSelection);
        reformatButton.setEnabled(hasSelection);
        copyButton.setEnabled(hasSelection);
    }

    // ---------- T-RecentExecMenu O-119：列表右键菜单（六件，形制对齐日志页） ----------

    /**
     * 右键菜单：复制时间戳 / 屏蔽此 SQL / 发送到 SQL → Java / 发送到日志转 SQL /
     * 跳转到 Java / 跳转到 Mapper XML。
     *
     * <p>明确不做（与日志页的差异是刻意的）：<b>删除</b>（弹框是浏览视图，删的是全局日志缓冲条目，
     * 属越权副作用）、<b>方法级聚焦</b>（弹框锚点本身就是那个方法的位置）、<b>按请求聚焦</b>（跳走页签）、
     * <b>复制 SQL</b>（编辑器上方按钮已有，且口径更好：所见即复制）。
     */
    private void showContextMenu(MouseEvent e) {
        int index = entryList.locationToIndex(e.getPoint());
        if (index < 0) return;
        // T-RecentMulti：命中行已在多选集合内 → 保留原多选（复制项=批量）；否则收敛为该行
        //（与日志页 resolveContextMenuSelection 同一语义）
        entryList.setSelectedIndices(
                SqlLogPanelKt.resolveContextMenuSelection(entryList.getSelectedIndices(), index));
        LogSqlEntry entry = entryList.getModel().getElementAt(index);
        com.intellij.openapi.actionSystem.ActionPopupMenu popup = ActionManager.getInstance().createActionPopupMenu(
                POPUP_ID,
                buildMenuGroup(entry, entryList.getSelectedValuesList()));
        // 243 的 ActionPopupMenu 只有 setter 无 getter，Kotlin 不合成属性——显式调 setter
        popup.setTargetComponent(entryList);
        popup.getComponent().show(entryList, e.getX(), e.getY());
    }

    /**
     * 菜单动作清单（{@link #buildMenuGroup} 与测试访问器共用，单一来源）；{@code selectedEntries} 供批量项（复制时间戳）。
     * 返回顺序即菜单顺序——仓内测试按索引断言，勿重排。
     */
    private List<AnAction> menuActions(LogSqlEntry entry) {
        return menuActions(entry, List.of(entry));
    }

    private List<AnAction> menuActions(LogSqlEntry entry, List<LogSqlEntry> selectedEntries) {
        return List.of(
                new CopyTimestampAction(selectedEntries),
                new SuppressSqlAction(entry),
                new SendToSqlToJavaAction(entry),
                new SendToLogToSqlAction(entry),
                new JumpToJavaAction(entry),
                new JumpToMapperXmlAction(entry));
    }

    /**
     * 菜单动作组（{@link #showContextMenu} 用）。取动作清单请直接调 {@link #menuActions}——{@code DefaultActionGroup}
     * 唯一的子项访问器 {@code getChildren(AnActionEvent)} 是 override-only API，调用会被 verifyGate 判违规。
     */
    private DefaultActionGroup buildMenuGroup(LogSqlEntry entry) {
        return buildMenuGroup(entry, List.of(entry));
    }

    private DefaultActionGroup buildMenuGroup(LogSqlEntry entry, List<LogSqlEntry> selectedEntries) {
        return new DefaultActionGroup(menuActions(entry, selectedEntries).toArray(new AnAction[0]));
    }

    /** 复制选中条目的时间戳片段（与日志页同源 {@link LogEntryFilteringKt#batchCopyTimestamps}；多选批量、文案带条数；显示开关关闭时无片段 → 置灰）。 */
    private class CopyTimestampAction extends AnAction {
        private final List<LogSqlEntry> entries;
        private final String clip;

        CopyTimestampAction(List<LogSqlEntry> entries) {
            this.entries = entries;
            this.clip = SqlLogPanelKt.batchCopyTimestamps(entries);
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(
                    SqlLogPanelKt.copyMenuText("log.panel.copyTimestamp", "log.panel.copyTimestampSelected", entries.size()));
            e.getPresentation().setEnabled(!clip.isEmpty());
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            if (!clip.isEmpty()) UiTextClipboard.copy(clip);
        }
    }

    /**
     * 屏蔽此 SQL：规则入库（{@link SqlSuppressRuleManager} 公开 API）→ 从弹框列表移除该行（D92=A）→
     * balloon 反馈附「管理屏蔽规则…」直达（与日志页 {@code log.suppress.*} 文案/通知组同源）。
     */
    private class SuppressSqlAction extends AnAction {
        private final LogSqlEntry entry;

        SuppressSqlAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.suppress.action"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            SqlSuppressRuleManager.AddResult result = SqlSuppressRuleManager.getInstance().addRule(entry.restoredSql());
            if (result == SqlSuppressRuleManager.AddResult.ADDED) {
                removeEntryRow(entry);
                showSuppressBalloon(1);
            } else if (result == SqlSuppressRuleManager.AddResult.DUPLICATE) {
                // 已存在：幂等——行照旧移除（该 SQL 已在屏蔽态，弹框不应再展示它）
                removeEntryRow(entry);
            } else if (result == SqlSuppressRuleManager.AddResult.LIMIT_REACHED) {
                showSuppressLimitBalloon();
            }
        }
    }

    /** 发送到 SQL → Java（第三页签）：经 {@link SqlLogManager#onOpenEntryInTab} 通道（面板私有字段不可直达）。 */
    private class SendToSqlToJavaAction extends AnAction {
        private final LogSqlEntry entry;

        SendToSqlToJavaAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.sendToReverse"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            sendEntryToTab(entry, LogEntryTab.SQL_TO_JAVA);
        }
    }

    /** 发送到日志转 SQL（第二页签）。 */
    private class SendToLogToSqlAction extends AnAction {
        private final LogSqlEntry entry;

        SendToLogToSqlAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(MpSqlVisualizerBundle.message("logToSql.sendTo"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            sendEntryToTab(entry, LogEntryTab.LOG_TO_SQL);
        }
    }

    /** 跳转到 Java 调用方（T-M55-01 双目标之一；实时页口径——与日志页同文案同反馈）。 */
    private class JumpToJavaAction extends AnAction {
        private final LogSqlEntry entry;

        JumpToJavaAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.jumpToJava"));
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            LogEntryJumpOps.jumpToSourceRealtime(project, entry);
        }
    }

    /**
     * 跳转到 mapper XML 语句节点：mapperStatement 非空且定位成功才显示
     * （update 内解析一次并存引用，actionPerformed 复用——与日志页单次右键一次索引查询同款）。
     */
    private class JumpToMapperXmlAction extends AnAction {
        private final LogSqlEntry entry;
        private LogEntryJumpOps.XmlTarget resolved = null;

        JumpToMapperXmlAction(LogSqlEntry entry) {
            this.entry = entry;
        }

        @Override
        public void update(AnActionEvent e) {
            e.getPresentation().setText(MpSqlVisualizerBundle.message("log.panel.jumpToXml"));
            resolved = LogEntryJumpOps.resolveXml(project, entry);
            e.getPresentation().setEnabledAndVisible(resolved != null);
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            LogEntryJumpOps.XmlTarget target = resolved != null ? resolved : LogEntryJumpOps.resolveXml(project, entry);
            if (target == null) return;
            LogEntryJumpOps.navigate(project, target.virtualFile(), target.line());
        }
    }

    /**
     * 送条目到工具窗页签：通道由 {@link SqlLogPanel} 注册（工具窗未创建即未注册）——
     * 未就绪时状态行轻提示降级，不静默（对齐跳转侧「不静默」约定）。
     *
     * <p>取管理器走<b>只读入口</b> {@code sqlLogManagerIfCreated}（T-ST-02b2 定论：读路径不得发起服务创建，
     * 否则可能因 temporarily-disposed 窗口把服务在本 JVM 内永久毒化；由
     * {@code SqlLogManagerLookupGuardTest} 扫描守卫锁定）。
     */
    private void sendEntryToTab(LogSqlEntry entry, LogEntryTab tab) {
        SqlLogManager manager = SqlLogManagerLookupKt.sqlLogManagerIfCreated(project);
        java.util.function.BiConsumer<LogSqlEntry, LogEntryTab> open =
                manager != null ? manager.onOpenEntryInTab : null;
        if (open == null) {
            statusLabel.setText(MpSqlVisualizerBundle.message("log.recent.dialog.menuPanelNotReady"));
            return;
        }
        open.accept(entry, tab);
    }

    /** 屏蔽成功 balloon（与日志页 {@code showSuppressBalloon} 同通知组同文案；附管理直达）。 */
    private void showSuppressBalloon(int addedCount) {
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        try {
            com.intellij.notification.Notification notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(SUPPRESS_NOTIFICATION_GROUP_ID)
                    .createNotification(
                            MpSqlVisualizerBundle.message("log.suppress.added.title"),
                            MpSqlVisualizerBundle.message("log.suppress.added.body", Integer.toString(addedCount)),
                            NotificationType.INFORMATION);
            notification.addAction(
                    NotificationAction.createSimple(MpSqlVisualizerBundle.message("log.suppress.manage"), () -> {
                        notification.expire();
                        new SqlSuppressManagerDialog(project).show();
                    }));
            notification.notify(project);
        } catch (Throwable ignored) {
            // 通知失败不影响主流程
        }
    }

    /** 屏蔽规则达上限 balloon（D6：拒新增不淘汰）。 */
    private void showSuppressLimitBalloon() {
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        try {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(SUPPRESS_NOTIFICATION_GROUP_ID)
                    .createNotification(
                            MpSqlVisualizerBundle.message("log.suppress.added.title"),
                            MpSqlVisualizerBundle.message(
                                    "log.suppress.limit",
                                    Integer.toString(SqlSuppressRuleManager.MAX_RULES)),
                            NotificationType.WARNING)
                    .notify(project);
        } catch (Throwable ignored) {
            // 通知失败不影响主流程
        }
    }

    /**
     * 主按钮/回车/双击：打开预览后<b>不关闭</b>本对话框（浏览对比场景）。
     * T-RecentMulti：多选下取 <b>lead</b>（最后交互行）——与「我点的那条」意图一致；单选等价于原 selectedValue。
     * 无选中直接返回（okAction 已禁用，防御直达调用）。
     */
    @Override
    protected void doOKAction() {
        int lead = entryList.getLeadSelectionIndex();
        LogSqlEntry selected;
        if (lead >= 0) {
            selected = listModel.getElementAt(lead);
        } else {
            selected = entryList.getSelectedValue();
            if (selected == null) return;
        }
        onOpenPreview.accept(selected);
    }

    /** 程序化关闭（SqlPreviewDialog/RollbackSqlDialog 同款惯例；测试 tearDown 用）——DialogWrapper.dispose 平台侧 protected，同类内提升为 public 包装，语义与用户点关闭一致。 */
    public void doDispose() {
        dispose();
    }

    /** editor 释放（ParamImportDialog 成熟路径）：置 null 防二次释放。 */
    @Override
    public void dispose() {
        if (editor != null) {
            EditorFactory.getInstance().releaseEditor(editor);
        }
        editor = null;
        super.dispose();
    }

    // ---------- 测试访问器（headless 断言，RollbackSqlDialogTest 同款构造不 show） ----------

    /** 测试访问器：当前列表条目数。 */
    public int entryCountForTest() {
        return listModel.getSize();
    }

    /** 测试访问器：列表渲染器（复用钉断言用；返回对话框自持字段，非 JBList 包装壳）。 */
    public Object cellRendererForTest() {
        return entryRenderer;
    }

    /** 测试访问器：当前编辑器文本。 */
    public String editorTextForTest() {
        return editor != null ? editor.getDocument().getText() : "";
    }

    /** 测试访问器：编辑器实例（T-SqlCopyShortcuts：键盘复制接线断言用）。 */
    public Editor editorForTest() {
        return editor;
    }

    /** 测试访问器：条目列表实例（T-SqlCopyShortcuts：列表复制接线断言用）。 */
    public JList<?> entryListForTest() {
        return entryList;
    }

    /** 测试访问器：状态行文本。 */
    public String statusTextForTest() {
        return statusLabel.getText();
    }

    /** 测试访问器：按索引选中（驱动选中切换链）。 */
    public void selectIndexForTest(int index) {
        entryList.setSelectedIndex(index);
    }

    /** 测试访问器：多选（T-RecentMulti）。 */
    public void selectIndicesForTest(int[] indices) {
        entryList.setSelectedIndices(indices);
    }

    /** 测试访问器：当前选中条目。 */
    public LogSqlEntry selectedEntryForTest() {
        return entryList.getSelectedValue();
    }

    /** 测试访问器：主按钮可用态。 */
    public boolean openPreviewEnabledForTest() {
        return getOKAction().isEnabled();
    }

    /** 测试访问器：触发主按钮动作（含「不关闭」契约断言的前半段）。 */
    public void triggerOpenPreviewForTest() {
        doOKAction();
    }

    /** 测试访问器：「格式化」图标动作（D91 幂等重排）。 */
    public void triggerReformatForTest() {
        reformatButton.doClick();
    }

    /** 测试访问器：「格式化」按钮可用态。 */
    public boolean reformatEnabledForTest() {
        return reformatButton.isEnabled();
    }

    /** 测试访问器：「格式化」按钮 tooltip（图标按钮的可见文案载体）。 */
    public String reformatTooltipForTest() {
        return reformatButton.getToolTipText();
    }

    /** 测试访问器：「格式化」按钮图标（与日志转 SQL 结果区同源断言用）。 */
    public Icon reformatIconForTest() {
        return reformatButton.getIcon();
    }

    /** 测试访问器：触发复制。 */
    public void triggerCopyForTest() {
        copyCurrentSql();
    }

    /** 测试访问器：复制按钮可用态。 */
    public boolean copyEnabledForTest() {
        return copyButton.isEnabled();
    }

    /** 测试访问器：复制按钮图标是否与日志转 SQL 结果区复制动作同源（{@code AllIcons.Actions.Copy}）。 */
    public Icon copyIconForTest() {
        return copyButton.getIcon();
    }

    /**
     * 测试访问器：右键菜单动作清单（按当前选中条目构建；headless 断言在场/文案/置灰，
     * 用 {@code AnActionEvent.createFromAnAction(...)} 驱动 update——仓内既有测试同法）。
     */
    public List<AnAction> menuActionsForTest() {
        LogSqlEntry entry = entryList.getSelectedValue();
        if (entry == null) return List.of();
        return menuActions(entry);
    }

    /** 测试访问器：按<b>当前多选集合</b>构建菜单（T-RecentMulti：批量项文案断言用）。 */
    public List<AnAction> menuActionsForSelectionForTest() {
        int lead = entryList.getLeadSelectionIndex();
        LogSqlEntry entry = lead >= 0 ? listModel.getElementAt(lead) : entryList.getSelectedValue();
        if (entry == null) return List.of();
        return menuActions(entry, entryList.getSelectedValuesList());
    }

    /** 测试访问器：屏蔽规则经理（断言规则确实入库）。 */
    public int suppressRuleCountForTest() {
        return SqlSuppressRuleManager.getInstance().getRules().size();
    }

    public static final int DIALOG_WIDTH = 760;
    public static final int DIALOG_HEIGHT = 480;

    /** DimensionService 键（尺寸/位置记忆）。 */
    public static final String DIMENSION_SERVICE_KEY = "mpsv.recent.executions.dialog";

    /** 拖拽下限（中心面板 minimumSize；量级对齐 SqlSuppressManagerDialog）。 */
    public static final int MIN_WIDTH = 560;
    public static final int MIN_HEIGHT = 360;

    /** 左右分栏比（列表侧信息密度高于规则名列表，较屏蔽规则对话框 0.38 放宽）。 */
    public static final float SPLIT_PROPORTION = 0.45f;

    /** 列表右键 ActionPopupMenu 的 place（与日志页 {@code mpvs.log.list} 分开，两个菜单各自独立）。 */
    public static final String POPUP_ID = "mpvs.recent.exec.list";

    /** 屏蔽反馈通知组（plugin.xml 已注册；与日志页 {@code SUPPRESS_NOTIFICATION_GROUP_ID} 同组同文案）。 */
    public static final String SUPPRESS_NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.logsuppress";
}
