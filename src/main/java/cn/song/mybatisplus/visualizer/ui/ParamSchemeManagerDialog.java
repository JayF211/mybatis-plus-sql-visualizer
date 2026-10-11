// T-SyncK2JMaster482 自 master ui/ParamSchemeManagerDialog.kt 移植（R22 T-04）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.UIUtil;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.border.EmptyBorder;
import kotlin.Pair;
import org.jetbrains.annotations.Nullable;

/**
 * 参数命名方案管理对话框（R22 T-04）：左方案列表 + 按钮行 + 状态行。
 *
 * <p><b>UI 形态照抄 {@code log/SqlSuppressManagerDialog} 全套</b>（真机退回教训——禁自创）：
 * 非模态（{@code DialogWrapper(project, false, IdeModalityType.MODELESS)}）、
 * {@code isResizable = true} + {@link #getDimensionServiceKey()} 尺寸/位置记忆（O-80 惯例）、
 * 左列表 + {@code FlowLayout(FlowLayout.LEFT, 4, 2)} 按钮行、inactive 色 {@link #statusLabel}、
 * {@code createActions = arrayOf(cancelAction)}（关闭即生效，无确定/应用缓存层——操作直达
 * {@link #cache}，与持久化单源）。</p>
 *
 * <p>操作 = 另存为 / 重命名 / 删除；列表首项恒为默认方案（{@code ParamHistoryCache.DEFAULT_SCHEME}，
 * 显示名经 bundle 本地化）且禁改名删除（{@code DEFAULT_PROTECTED}）。删除为不可恢复操作，
 * {@code showYesNoDialog} 确认一次（弹窗克制红线，同 SqlSuppressManagerDialog 清空确认依据）。</p>
 *
 * <p>数据交互面：方案名清单/条目数读 {@link ParamHistoryCache}；<b>当前参数快照由
 * {@link #snapshotProvider} 注入</b>（对话框不知道预览面板——面板闭包供给
 * {@code historyCacheKey() to buildHistorySnapshot()}，无预览时 null）；任何方案变更后回调
 * {@link #onSchemesChanged} 由面板同步下拉（激活方案被另存为切走时整面重召回）。</p>
 *
 * <p>本类不持有 editor/平台监听等需释放资源（SqlSuppressManagerDialog 的 dispose 释放配对
 * 仅针对其 Editor，此处无对可配）；实现 {@link Disposable} 是 ParamImportDialog 同款形态——
 * 经公开接口方法放大 {@code dispose()} 可见性，供测试 teardown 显式释放未 show 的实例。</p>
 */
public class ParamSchemeManagerDialog extends DialogWrapper implements Disposable {

    private final Project project;
    private final ParamHistoryCache cache;
    private final Supplier<Pair<String, Map<String, String>>> snapshotProvider;
    private final Runnable onSchemesChanged;

    /** 列表模型存<b>原始方案名</b>（默认方案 = 空串），显示名经渲染器本地化——改名/删除直取，免查表。 */
    private final CollectionListModel<String> listModel = new CollectionListModel<>();

    private final JBList<String> schemeList = new JBList<>(listModel);

    private final JBLabel statusLabel = new JBLabel();

    private final JButton saveAsButton = new JButton(MpSqlVisualizerBundle.message("panel.scheme.dialog.saveAs"));

    private final JButton renameButton = new JButton(MpSqlVisualizerBundle.message("panel.scheme.dialog.rename"));

    private final JButton deleteButton = new JButton(MpSqlVisualizerBundle.message("panel.scheme.dialog.delete"));

    public ParamSchemeManagerDialog(Project project,
                                    ParamHistoryCache cache,
                                    Supplier<Pair<String, Map<String, String>>> snapshotProvider,
                                    Runnable onSchemesChanged) {
        super(project, false, DialogWrapper.IdeModalityType.MODELESS);
        this.project = project;
        this.cache = cache;
        this.snapshotProvider = snapshotProvider;
        this.onSchemesChanged = onSchemesChanged;

        // Kotlin 属性初始化 apply{} 的等价形态（字段初始化期执行一次）
        statusLabel.setForeground(UIUtil.getInactiveTextColor());

        saveAsButton.setFocusable(false);
        saveAsButton.addActionListener(e -> saveAsClicked());
        renameButton.setFocusable(false);
        renameButton.setEnabled(false);
        renameButton.addActionListener(e -> renameClicked());
        deleteButton.setFocusable(false);
        deleteButton.setEnabled(false);
        deleteButton.addActionListener(e -> deleteClicked());

        setTitle(MpSqlVisualizerBundle.message("panel.scheme.dialog.title"));
        setCancelButtonText(MpSqlVisualizerBundle.message("panel.scheme.dialog.close"));
        setResizable(true);
        init();
        setSize(DIALOG_WIDTH, DIALOG_HEIGHT);
        refreshSchemeList(null);
    }

    /** O-80：尺寸/位置记忆（SqlSuppressManagerDialog/ErrorReportDialog 同惯例，零自持久化）。 */
    @Override
    protected String getDimensionServiceKey() {
        return DIMENSION_SERVICE_KEY;
    }

    @Override
    protected JComponent createCenterPanel() {
        schemeList.setCellRenderer(new SchemeRowRenderer());
        schemeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        schemeList.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) return;
            syncActionButtons();
        });

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttonRow.setOpaque(false);
        buttonRow.add(saveAsButton);
        buttonRow.add(renameButton);
        buttonRow.add(deleteButton);
        JPanel listPanel = new JPanel(new BorderLayout());
        listPanel.add(new JBScrollPane(schemeList), BorderLayout.CENTER);
        listPanel.add(buttonRow, BorderLayout.SOUTH);

        // 常驻说明（JTextArea 自动折行——JBLabel 不折行会撑宽对话框，SqlSuppressManagerDialog 同教训）
        JTextArea noteArea = new JTextArea(MpSqlVisualizerBundle.message("panel.scheme.dialog.note"));
        noteArea.setEditable(false);
        noteArea.setOpaque(false);
        noteArea.setLineWrap(true);
        noteArea.setWrapStyleWord(true);
        noteArea.setBorder(null);
        noteArea.setForeground(UIUtil.getInactiveTextColor());
        noteArea.setBackground(null);
        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.add(statusLabel, BorderLayout.NORTH);
        southPanel.add(noteArea, BorderLayout.SOUTH);

        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.add(listPanel, BorderLayout.CENTER);
        centerPanel.add(southPanel, BorderLayout.SOUTH);
        // O-80：最小尺寸兜底（SqlSuppressManagerDialog 同款——DialogWrapper 无 setMinimumSize）
        centerPanel.setMinimumSize(new Dimension(MIN_WIDTH, MIN_HEIGHT));
        return centerPanel;
    }

    /** 方案行渲染器：SqlSuppressManagerDialog.RuleRowRenderer 同形态（行底分隔线 + 内边距 + 选中色）。 */
    private final class SchemeRowRenderer extends JPanel implements ListCellRenderer<String> {

        private final SimpleColoredComponent nameLine = new SimpleColoredComponent();

        private SchemeRowRenderer() {
            super(new BorderLayout());
            setOpaque(true);
            nameLine.setOpaque(false);
            add(nameLine, BorderLayout.CENTER);
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                new EmptyBorder(4, 8, 4, 8)
            ));
        }

        @Override
        public Component getListCellRendererComponent(javax.swing.JList<? extends String> list,
                                                      String value,
                                                      int index,
                                                      boolean selected,
                                                      boolean hasFocus) {
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            java.awt.Color fg = selected ? list.getSelectionForeground() : list.getForeground();
            nameLine.setForeground(fg);
            nameLine.clear();
            nameLine.append(ParamSchemeManagerDialogKt.displaySchemeName(value), SimpleTextAttributes.REGULAR_ATTRIBUTES);
            if (ParamHistoryCache.DEFAULT_SCHEME.equals(value)) {
                nameLine.append(
                    "  ·  " + MpSqlVisualizerBundle.message("panel.scheme.default"),
                    SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES
                );
            } else {
                // T-DevAcceptFix2 ①：条目摘要改为**当前方法**口径（预设已按方法/SQL 隔离，
                // 全局缓存键数对当前方法无意义；本键下 1 = 已存、0 = 未存）
                String key = currentKey();
                String here = (key != null && cache.recallFromScheme(value, key) != null) ? "1" : "0";
                nameLine.append(
                    "  ·  " + MpSqlVisualizerBundle.message("panel.scheme.dialog.entry", here),
                    SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES
                );
            }
            return this;
        }
    }

    // ---------- 方案操作 ----------

    /** T-DevAcceptFix2 ①：当前预览的缓存键（对话框不自持状态——经 {@link #snapshotProvider} 取）；
     *  null = 无预览上下文（名单只列默认方案）。 */
    @Nullable
    private String currentKey() {
        try {
            Pair<String, Map<String, String>> snapshot = snapshotProvider.get();
            return snapshot != null ? snapshot.getFirst() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 列表模型按 cache 当前方案重建（默认方案恒首位）；{@code select} 非 null 时选中。
     *  T-DevAcceptFix2 ①：名单与选中项均按**当前方法键**取（每个方法只列自己的预设）。 */
    private void refreshSchemeList(@Nullable String select) {
        List<String> names = schemeNamesWithDefault();
        listModel.replaceAll(names);
        String target = select != null ? select : cache.activeSchemeFor(currentKey());
        if (names.contains(target)) {
            schemeList.setSelectedValue(target, false);
        } else {
            schemeList.clearSelection();
        }
        statusLabel.setText(names.size() == 1 ? MpSqlVisualizerBundle.message("panel.scheme.dialog.empty") : "");
        syncActionButtons();
    }

    private List<String> schemeNamesWithDefault() {
        List<String> names = new ArrayList<>();
        names.add(ParamHistoryCache.DEFAULT_SCHEME);
        names.addAll(cache.schemeNamesFor(currentKey()));
        return names;
    }

    /** 重命名/删除按钮可用性：默认方案（或未选中）禁用——与 DEFAULT_PROTECTED 数据面语义双保险。 */
    private void syncActionButtons() {
        String selected = schemeList.getSelectedValue();
        boolean protectedOrNone = selected == null || ParamHistoryCache.DEFAULT_SCHEME.equals(selected);
        renameButton.setEnabled(!protectedOrNone);
        deleteButton.setEnabled(!protectedOrNone);
    }

    /** 另存为：输入方案名 → 快照由 {@link #snapshotProvider} 注入 → {@link ParamHistoryCache#saveAsScheme}。
     *  弹窗克制红线白名单场景：用户必须输入方案名才能继续（BannedApiSourceScanTest 白名单登记）。 */
    private void saveAsClicked() {
        String name = Messages.showInputDialog(
            project,
            MpSqlVisualizerBundle.message("panel.scheme.dialog.saveAsPrompt"),
            MpSqlVisualizerBundle.message("panel.scheme.dialog.title"),
            Messages.getQuestionIcon()
        );
        if (name == null) return;
        performSaveAs(name);
    }

    /** 重命名：预填当前名输入新名 → {@link ParamHistoryCache#renameScheme}。 */
    private void renameClicked() {
        String selected = schemeList.getSelectedValue();
        if (selected == null) return;
        String newName = Messages.showInputDialog(
            project,
            MpSqlVisualizerBundle.message("panel.scheme.dialog.renamePrompt", selected),
            MpSqlVisualizerBundle.message("panel.scheme.dialog.title"),
            Messages.getQuestionIcon(),
            selected,
            null
        );
        if (newName == null) return;
        performRename(newName);
    }

    /** 删除：确认一次（不可恢复）→ {@link ParamHistoryCache#deleteScheme}。
     *  弹窗克制红线白名单场景：破坏性知情确认，与 SqlSuppressManagerDialog 清空前确认同类。 */
    private void deleteClicked() {
        String selected = schemeList.getSelectedValue();
        if (selected == null) return;
        int confirm = Messages.showYesNoDialog(
            project,
            MpSqlVisualizerBundle.message("panel.scheme.dialog.deleteConfirm", selected),
            MpSqlVisualizerBundle.message("panel.scheme.dialog.title"),
            Messages.getQuestionIcon()
        );
        if (confirm != Messages.YES) return;
        performDelete();
    }

    /** 另存为核心（输入对话框旁路共用于测试缝）：返回是否成功落库。 */
    private boolean performSaveAs(String rawName) {
        String name = rawName.trim();
        if (name.isEmpty()) {
            statusLabel.setText(MpSqlVisualizerBundle.message("panel.scheme.dialog.blank"));
            return false;
        }
        if (ParamHistoryCache.DEFAULT_SCHEME.equals(name) || cache.hasScheme(name)) {
            statusLabel.setText(MpSqlVisualizerBundle.message("panel.scheme.dialog.duplicate"));
            return false;
        }
        Pair<String, Map<String, String>> snapshot = snapshotProvider.get();
        if (snapshot == null) {
            statusLabel.setText(MpSqlVisualizerBundle.message("panel.scheme.dialog.noSnapshot"));
            return false;
        }
        ParamHistoryCache.SchemeOpResult result = cache.saveAsScheme(name, snapshot.getFirst(), snapshot.getSecond());
        if (result == ParamHistoryCache.SchemeOpResult.LIMIT_REACHED) {
            // F49：方案个数上限（ParamHistoryCache.MAX_SCHEME_COUNT）——状态行提示，不落库
            statusLabel.setText(MpSqlVisualizerBundle.message(
                "panel.scheme.dialog.limitReached", String.valueOf(ParamHistoryCache.MAX_SCHEME_COUNT)));
            return false;
        }
        refreshSchemeList(name);
        statusLabel.setText(MpSqlVisualizerBundle.message("panel.scheme.dialog.saved", name));
        onSchemesChanged.run();
        return true;
    }

    /** 重命名核心（输入对话框旁路共用于测试缝）：返回是否成功落库。 */
    private boolean performRename(String rawNewName) {
        String oldName = schemeList.getSelectedValue();
        if (oldName == null) return false;
        if (ParamHistoryCache.DEFAULT_SCHEME.equals(oldName)) {
            statusLabel.setText(MpSqlVisualizerBundle.message("panel.scheme.dialog.defaultProtected"));
            return false;
        }
        String newName = rawNewName.trim();
        if (newName.isEmpty()) {
            statusLabel.setText(MpSqlVisualizerBundle.message("panel.scheme.dialog.blank"));
            return false;
        }
        ParamHistoryCache.SchemeOpResult result = cache.renameScheme(oldName, newName);
        switch (result) {
            case OK:
                refreshSchemeList(newName);
                statusLabel.setText(MpSqlVisualizerBundle.message("panel.scheme.dialog.renamed", newName));
                onSchemesChanged.run();
                return true;
            case DUPLICATE:
                statusLabel.setText(MpSqlVisualizerBundle.message("panel.scheme.dialog.duplicate"));
                break;
            case NOT_FOUND:
                statusLabel.setText(MpSqlVisualizerBundle.message("panel.scheme.dialog.notFound"));
                break;
            case DEFAULT_PROTECTED:
                statusLabel.setText(MpSqlVisualizerBundle.message("panel.scheme.dialog.defaultProtected"));
                break;
            // F49 不可达守卫：renameScheme 不产 LIMIT_REACHED/INVALID（仅 saveAsScheme 产），穷尽性收口
            case LIMIT_REACHED:
            case INVALID:
                statusLabel.setText(MpSqlVisualizerBundle.message("panel.scheme.dialog.notFound"));
                break;
            default:
                break;
        }
        return false;
    }

    /** 删除核心（确认对话框旁路共用于测试缝）：返回是否成功落库。 */
    private boolean performDelete() {
        String name = schemeList.getSelectedValue();
        if (name == null) return false;
        if (ParamHistoryCache.DEFAULT_SCHEME.equals(name)) {
            statusLabel.setText(MpSqlVisualizerBundle.message("panel.scheme.dialog.defaultProtected"));
            return false;
        }
        ParamHistoryCache.SchemeOpResult result = cache.deleteScheme(name);
        switch (result) {
            case OK:
                refreshSchemeList(null);
                statusLabel.setText(MpSqlVisualizerBundle.message("panel.scheme.dialog.deleted", name));
                onSchemesChanged.run();
                return true;
            case NOT_FOUND:
                statusLabel.setText(MpSqlVisualizerBundle.message("panel.scheme.dialog.notFound"));
                break;
            case DEFAULT_PROTECTED:
                statusLabel.setText(MpSqlVisualizerBundle.message("panel.scheme.dialog.defaultProtected"));
                break;
            case DUPLICATE:
                statusLabel.setText(MpSqlVisualizerBundle.message("panel.scheme.dialog.duplicate"));
                break;
            // F49 不可达守卫：deleteScheme 不产 LIMIT_REACHED/INVALID（仅 saveAsScheme 产），穷尽性收口
            case LIMIT_REACHED:
            case INVALID:
                statusLabel.setText(MpSqlVisualizerBundle.message("panel.scheme.dialog.notFound"));
                break;
            default:
                break;
        }
        return false;
    }

    /** 只挂「关闭」（照 SqlSuppressManagerDialog：关闭即生效，无确定/应用缓存层）。 */
    @Override
    protected Action[] createActions() {
        return new Action[]{cancelAction};
    }

    /**
     * 可见性经 {@link Disposable} 公开接口方法放大（DialogWrapper.dispose 为 protected，测试
     * teardown 需显式释放未 show 的实例——ParamImportDialog 同款）；本类不持有
     * editor/平台监听等资源，无释放配对逻辑（见类 KDoc）。
     */
    @Override
    public void dispose() {
        super.dispose();
    }

    // ---------- 测试缝（headless 旁路输入/确认对话框） ----------

    /** 测试访问器：当前方案行数（含默认方案首位）。 */
    public int schemeCountForTest() {
        return listModel.getSize();
    }

    /** 测试访问器：按位置取方案原始名（默认方案断言「恒首位」用）。 */
    public String schemeNameAtForTest(int index) {
        return listModel.getElementAt(index);
    }

    /** 测试访问器：当前选中方案原始名。 */
    @Nullable
    public String selectedSchemeForTest() {
        return schemeList.getSelectedValue();
    }

    /** 测试访问器：状态行文本（操作结果断言）。 */
    public String statusTextForTest() {
        return statusLabel.getText();
    }

    /** 测试访问器：重命名按钮可用性（默认方案保护断言）。 */
    public boolean renameEnabledForTest() {
        return renameButton.isEnabled();
    }

    /** 测试访问器：删除按钮可用性（默认方案保护断言）。 */
    public boolean deleteEnabledForTest() {
        return deleteButton.isEnabled();
    }

    /** 测试访问器：DimensionService 键（UI 形态契约断言）。 */
    public String dimensionServiceKeyForTest() {
        return getDimensionServiceKey();
    }

    /** 测试访问器：程序化选中方案（同步按钮可用性）。 */
    public void selectSchemeForTest(String name) {
        schemeList.setSelectedValue(name, false);
        syncActionButtons();
    }

    /** 测试访问器：另存为（旁路输入对话框）。 */
    public boolean saveAsForTest(String name) {
        return performSaveAs(name);
    }

    /** 测试访问器：重命名选中方案（旁路输入对话框）。 */
    public boolean renameForTest(String newName) {
        return performRename(newName);
    }

    /** 测试访问器：删除选中方案（旁路确认对话框）。 */
    public boolean deleteForTest() {
        return performDelete();
    }

    public static final int DIALOG_WIDTH = 440;
    public static final int DIALOG_HEIGHT = 320;

    /** O-80：DimensionService 键（尺寸/位置记忆；getDimensionServiceKey 返回）。 */
    public static final String DIMENSION_SERVICE_KEY = "mpsv.paramscheme.manager.dialog";

    /** O-80：最小尺寸兜底（中心面板 minimumSize；量级=按钮行不折行+列表可读）。 */
    public static final int MIN_WIDTH = 360;
    public static final int MIN_HEIGHT = 220;
}
