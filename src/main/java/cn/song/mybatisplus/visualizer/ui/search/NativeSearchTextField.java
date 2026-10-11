// T-SyncK2JMaster482 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/search/NativeSearchTextField.kt
package cn.song.mybatisplus.visualizer.ui.search;

import com.intellij.find.FindModel;
import com.intellij.find.SearchReplaceComponent;
import com.intellij.find.SearchSession;
import com.intellij.find.editorHeaderActions.ToggleMatchCase;
import com.intellij.find.editorHeaderActions.ToggleRegex;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.actionSystem.impl.ActionButton;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 原生 IDEA 搜索框形态的文本搜索组件（T-SearchToggleNativeLook O-137）。
 *
 * <p>走平台 {@code SearchReplaceComponent.Builder} 全套（原生框体/边距/焦点，EditorSearchSession 同款装配）；
 * {@code withShowOnlySearchPanel()} 只保留搜索行。开关钮挂位（243 源码钉死的事实，两轮真机返工换来）：
 * <ul>
 *  <li><b>必须 {@code addExtraSearchActions}</b>——SearchReplaceComponent 构造期只从 searchToolbar2 组
 *    （即 addExtraSearchActions 的组）过滤 {@code instanceof Embeddable} 抽给
 *    {@code SearchTextArea.setExtraActions} 渲染进框内按钮行；</li>
 *  <li>{@code addPrimarySearchActions} 的动作进右侧工具栏，showOnlySearchPanel 形态下该面板<b>根本没挂进布局</b>（不可见）；</li>
 *  <li>{@code addSearchFieldActions} 的组只用于快捷键绑定，不渲染。</li>
 * </ul>
 *
 * <p>命中数读数放框外右侧 {@code statusLabel}（灰色小字）：平台原生 StatusTextAction 走 ActionToolbar
 * 自定义组件路径才显示，而 setExtraActions 的嵌入钮是 ActionButton（只画图标），
 * 文本标签在框内是死路——框内嵌入面留给开关钮，读数贴框放右。
 *
 * <p>搜索历史（T-LogToSqlHint 同批用户反馈「搜索历史好像是假的」）：平台历史钮数据源=IDE 全局
 * Find 历史（{@code FindInProjectSettings.getRecentFindStrings()}，与本项目无关的串线词+空串杂质）。
 * 处置：①{@code withAddSearchResultsToGlobalSearch(false)} 止住本组件搜索词写进全局 Find 历史；
 * ②{@code project} 传真（null 时平台 {@code removeNotify→addTextToRecent} 对 {@code FindInProjectSettings.getInstance(null)}
 * 必 NPE）；③<b>接管历史钮</b>——组件树里找到平台 ShowHistoryAction 的钮并在原位换成本组件的
 * 「最近搜索」（{@code recentSearches} 会话级，{@link #recordSearchQuery} 由宿主面板在过滤真正应用时喂词，
 * 空词不记），找不到平台钮则不装（降级=无历史钮，不影响其余能力）。
 *
 * <p>会话仍为极小 {@code MinSearchSession}（自有 {@link FindModel}，不与 IDE 全局 Find 串线）；
 * SearchReplaceComponent 自身实现 DataProvider 应答 SearchSession.KEY，故原生钮可用，
 * 本类兜底自答同键（两处同源，无歧义）。
 *
 * <p>语义约定（T-HistorySearchToggles 拍板保持不变）：
 * <ul>
 *  <li>regex/matchCase 两旗由调用方从 {@link #findModel} 读取喂给各自过滤链；</li>
 *  <li>{@code regex:} 前缀或语义、开关态会话级不持久化，均与本组件无关；</li>
 *  <li>日志页不需要正则钮：构造传 {@code showRegex = false} 即不挂 ToggleRegex（D2 拍板保持）。</li>
 * </ul>
 *
 * <p>移植注记（构造形态差异，供 log 族核对）：Kotlin 主构造
 * {@code (placeholderText: String?, showRegex: Boolean, multiline: Boolean = false, project: Project? = null)}
 * → Java 显式重载四型：{@link #NativeSearchTextField(String, boolean)}、
 * {@link #NativeSearchTextField(String, boolean, boolean)}、
 * {@link #NativeSearchTextField(String, boolean, boolean, Project)}、
 * {@link #NativeSearchTextField(String, boolean, Project)}（k2j 生产调用方 3 参形态
 * {@code (String, boolean, Project)} 直达）。属性访问器按 Kotlin val/var 语义落
 * {@code getFindModel()/getComponent()/getRow()/getTextEditor()/getText()/setText()/getTextField()}。
 */
public class NativeSearchTextField implements DataProvider {

    /** 会话级历史容量。 */
    public static final int MAX_HISTORY = 20;

    /** 搜索旗（Kotlin {@code val findModel} 属性——构造器体内装配初始旗态）。 */
    private final FindModel findModel;

    private final SearchSession searchSession;

    /** 原生全套组件：框体/边框/焦点/开关钮都由平台装配。 */
    private final SearchReplaceComponent component;

    private final JBTextArea textComponent;

    /** 框外右侧命中数读数（灰字，空文本隐藏）。 */
    private final JBLabel statusLabel;

    /** 调用方入布局的行组件：搜索框（含框内开关钮）+ 右侧读数。 */
    private final JComponent row;

    /** 会话级最近搜索词（最新在后；去重移位；空词不记）。 */
    private final ArrayDeque<String> recentSearches = new ArrayDeque<>();

    /** 完整构造（Kotlin 主构造全参形态）。 */
    public NativeSearchTextField(@Nullable String placeholderText, boolean showRegex, boolean multiline,
                                 @Nullable Project project) {
        FindModel model = new FindModel();
        model.setCaseSensitive(false);
        model.setRegularExpressions(false);
        this.findModel = model;
        this.searchSession = new MinSearchSession(findModel);

        // .apply { if (showRegex) addExtraSearchActions(ToggleRegex()) } → 条件链式展开
        SearchReplaceComponent.Builder builder = SearchReplaceComponent
                .buildFor(project, new JPanel(), searchSession)
                .addExtraSearchActions(new ToggleMatchCase());
        if (showRegex) {
            builder = builder.addExtraSearchActions(new ToggleRegex());
        }
        this.component = builder
                .withAddSearchResultsToGlobalSearch(false)
                .withShowOnlySearchPanel()
                .withMultilineEnabled(multiline)
                .build();

        JTextComponent raw = component.getSearchTextComponent();
        if (!(raw instanceof JBTextArea)) {
            throw new IllegalStateException(
                    "SearchReplaceComponent search text component is not a JBTextArea (platform change?)");
        }
        this.textComponent = (JBTextArea) raw;

        JBLabel label = new JBLabel();
        label.setForeground(UIUtil.getLabelInfoForeground());
        label.setBorder(JBUI.Borders.emptyLeft(8));
        label.setVisible(false);
        this.statusLabel = label;

        this.row = JBUI.Panels.simplePanel()
                .addToCenter(component)
                .addToRight(statusLabel);

        if (placeholderText != null) {
            textComponent.getEmptyText().setText(placeholderText);
        }
        installOwnHistoryButton();
    }

    /** Kotlin 默认参数 {@code multiline = false} 重载。 */
    public NativeSearchTextField(@Nullable String placeholderText, boolean showRegex) {
        this(placeholderText, showRegex, false, null);
    }

    /** Kotlin 默认参数 {@code project = null} 重载。 */
    public NativeSearchTextField(@Nullable String placeholderText, boolean showRegex, boolean multiline) {
        this(placeholderText, showRegex, multiline, null);
    }

    /** 三参便捷重载（k2j 生产调用方形态：{@code new NativeSearchTextField(text, showRegex, project)}）。 */
    public NativeSearchTextField(@Nullable String placeholderText, boolean showRegex,
                                 @Nullable Project project) {
        this(placeholderText, showRegex, false, project);
    }

    public FindModel getFindModel() {
        return findModel;
    }

    public SearchReplaceComponent getComponent() {
        return component;
    }

    public JBTextArea getTextField() {
        return textComponent;
    }

    // ---------- SearchTextField 兼容面（迁移既有调用点零改动） ----------

    /** 历史页调用点用 {@code searchField.textEditor.emptyText/minimumSize}（Kotlin {@code val textEditor}）。 */
    public JBTextArea getTextEditor() {
        return textComponent;
    }

    /** 兼容 {@code SearchTextField.text} 读写（Kotlin {@code var text}）。 */
    public String getText() {
        return component.getSearchTextComponent().getText();
    }

    public void setText(String value) {
        component.getSearchTextComponent().setText(value);
    }

    /** 兼容 {@code SearchTextField.addDocumentListener}（document 委托）。 */
    public void addDocumentListener(javax.swing.event.DocumentListener listener) {
        component.getSearchTextComponent().getDocument().addDocumentListener(listener);
    }

    public boolean isMatchCase() {
        return findModel.isCaseSensitive();
    }

    public boolean isRegex() {
        return findModel.isRegularExpressions();
    }

    /**
     * 命中数读数（对齐 IDEA 搜索条「N results」语义）：过滤完成后由调用方写入；
     * 空串隐藏标签。同步存入平台状态区（配色语义留给后续未命中变体）。
     */
    public void setStatusText(String text) {
        component.setStatusText(text);
        statusLabel.setText(text);
        statusLabel.setVisible(!text.isEmpty());
    }

    /**
     * 宿主面板在过滤真正应用（防抖后）时喂词：非空去重移到队尾，容量 {@link #MAX_HISTORY} 淘汰最旧。
     * 与平台历史（全局 Find 历史）完全隔离。
     */
    public void recordSearchQuery(String query) {
        String q = query.trim();
        if (q.isEmpty()) {
            return;
        }
        recentSearches.remove(q);
        recentSearches.addLast(q);
        while (recentSearches.size() > MAX_HISTORY) {
            recentSearches.removeFirst();
        }
    }

    /** internal 测试缝 → public（k2j 规则 §0.1）。 */
    public List<String> recentSearchesForTest() {
        return new ArrayList<>(recentSearches);
    }

    /** 原生开关钮经 DataManager 沿组件树取 {@code SearchSession.KEY}（SearchReplaceComponent 已应答；兜底自答）。 */
    @Override
    @Nullable
    public Object getData(@Nullable String dataId) {
        return SearchSession.KEY.is(dataId) ? searchSession : null;
    }

    /** 调用方入布局的行组件（Kotlin {@code val row}——log 族 {@code nativeSearch.getRow()} 引用点）。 */
    public JComponent getRow() {
        return row;
    }

    // ---------- 搜索历史接管（T-LogToSqlHint 批） ----------

    /**
     * 把平台历史钮（数据源=全局 Find 历史）原位换成本组件「最近搜索」钮。
     * 平台钮是 SearchTextArea 内部装配的 ActionButton（action 为内部类 ShowHistoryAction），
     * 类名匹配定位，找不到时静默跳过（平台结构变化只损失历史钮，不伤其余）。
     */
    private void installOwnHistoryButton() {
        Component platformButton = findPlatformHistoryButton(component);
        if (platformButton == null) {
            return;
        }
        if (!(platformButton.getParent() instanceof JComponent)) {
            return;
        }
        JComponent parent = (JComponent) platformButton.getParent();
        int index = parent.getComponentZOrder(platformButton);
        if (index < 0) {
            return;
        }
        parent.remove(platformButton);

        AnAction ownAction = new HistoryAction(this);
        // 尺寸对齐平台内嵌钮基线（自定尺寸会撑高搜索行——用户实测「搜索框变得太高」）
        ActionButton button = new ActionButton(
                ownAction, null, "NativeSearchTextFieldHistory",
                com.intellij.openapi.actionSystem.ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE);
        button.setOpaque(false);
        button.setToolTipText("最近搜索");
        // 点击由 AnAction 系统驱动（ActionButton 非 AbstractButton，无 addActionListener）
        parent.add(button, index);
        parent.revalidate();
        parent.repaint();
    }

    @Nullable
    private static Component findPlatformHistoryButton(Component root) {
        AnAction action = null;
        try {
            if (root instanceof ActionButton) {
                action = ((ActionButton) root).getAction();
            }
        } catch (Throwable ignored) {
            action = null;
        }
        if (action != null && action.getClass().getSimpleName().equals("ShowHistoryAction")) {
            return root;
        }
        if (!(root instanceof Container)) {
            return null;
        }
        for (Component child : ((Container) root).getComponents()) {
            Component found = findPlatformHistoryButton(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void showHistoryPopup() {
        if (recentSearches.isEmpty()) {
            return;
        }
        List<String> entries = new ArrayList<>(recentSearches);
        Collections.reverse(entries);
        JBList<String> list = new JBList<>(entries);
        list.setCellRenderer(new SimpleHistoryRenderer());
        com.intellij.find.editorHeaderActions.Utils.showCompletionPopup(
                component, list, null, textComponent, null);
    }

    /** 「最近搜索」动作（Kotlin object 表达式 → 顶级私有静态类，回调持宿主实例）。 */
    private static final class HistoryAction extends AnAction implements DumbAware {

        private final NativeSearchTextField host;

        private HistoryAction(NativeSearchTextField host) {
            super("最近搜索", "本组件最近使用过的搜索词", AllIcons.Actions.SearchWithHistory);
            this.host = host;
        }

        // 不覆写 update 设 isEnabled=false——ActionButton 的 update 时机不可控，会呈现常灰；
        // 空历史时 actionPerformed → showHistoryPopup 直接 return（静默），钮保持可点观感。
        @Override
        public void actionPerformed(AnActionEvent e) {
            host.showHistoryPopup();
        }
    }

    /** 历史条目渲染：与普通文本项区分（原生 popup 同款简洁形态）。 */
    private static final class SimpleHistoryRenderer extends javax.swing.DefaultListCellRenderer {

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focused) {
            Component c = super.getListCellRendererComponent(list, value, index, selected, focused);
            if (c instanceof JComponent) {
                ((JComponent) c).setBorder(JBUI.Borders.empty(2, 8));
            }
            return c;
        }
    }
}
