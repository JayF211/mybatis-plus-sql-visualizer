package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlListSyntaxHighlighter;
import cn.song.mybatisplus.visualizer.util.EditorCopyShortcuts;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.editor.actionSystem.EditorActionManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.JBPopupListener;
import com.intellij.openapi.ui.popup.LightweightWindowEvent;
import com.intellij.ui.awt.RelativePoint;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * 单击 SQL 预览（T-ClickPreview）：主日志列表 / 历史 tab 二级列表 / F-7 历史窗列表共用。
 *
 * 背景：列表条目多时悬停 tooltip（renderer toolTipText = 完整 SQL）逐行弹出/销毁、
 * 连动可变行高重排，滑过 N 行成本 × N 叠加成冻结。改为「单击出预览、双击开完整弹窗」，
 * 悬停零成本。
 *
 * 交互（onClick 由面板 MouseAdapter 转发 clickCount 与坐标，仅左键）：
 * - 单击落在真实行上：捕获该行 SQL，延迟 CLICK_PREVIEW_DELAY_MS 后在该行下缘弹出
 *   预览（不遮被点行；屏幕边缘由平台自动回调贴边）；延迟内第二击先到 → 定时器被停，不闪预览；
 * - 单击落在空白区（无行/行下留白）：立即停表并关预览——不借旧选中复活预览（二轮用户反馈：
 *   点其他地方后未再单击 SQL，预览又出现）；
 * - 双击：停定时器 + 关旧预览（随后面板自行 openPreview 走完整弹窗）。
 * - 多选进行中（Ctrl/Shift/拖框 ≥2 行）单击：不排程、已弹出的关闭（T-ListPreviewCopy 用户反馈：
 *   同时选择多个 SQL 时不要再出现单击预览）；{@link #show} 到点时兜底复查，封 300ms 延迟窗内补选竞态。
 * - 带 Ctrl/Shift 修饰键单击（含多选<b>第一击</b>）：一律不排程、已弹出的关闭（T-ClickPreviewCopyRestore
 *   O-121 用户反馈：单击弹预览抢走列表焦点后 Ctrl+C 落在编辑器且无选中=no-op，复制失效；
 *   ≥2 行判定要等第二击，守卫必须前移到修饰键本身）——多选全程焦点不离列表，批量 Ctrl+C 恢复。
 *
 * 弹窗形态：头部复制/关闭图标按钮 + SQL 高亮可编辑编辑器（三轮用户要求：高亮展示 +
 * 可临时改字段值再复制）。复制双入口（T-ClickPreviewCopyRestore）：头部按钮与编辑器内
 * Ctrl+C/Ctrl+Insert 共用同一动作——无选中复制全文、有选中放行编辑器原生复制选中片段，
 * 见 {@link #installCopyHandler}。高亮走 {@link SqlListSyntaxHighlighter}（SqlLexer 分词 +
 * SqlHighlightColors 配色键，与日志列表条目共用同一 token→配色映射——四轮用户反馈：
 * 预览高亮须与列表同色；无 PSI、不依赖 Database 插件）。
 * 弹出即聚焦内容（setRequestFocus(true)，四轮修复：false 时编辑器拿不到键盘焦点，
 * "可编辑"名存实亡；复制/关闭按钮 isFocusable=false 不抢焦点），点进编辑器直接打字，
 * Esc / 点外 / 其他窗口打开即关，可拖角调整大小。
 *
 * headless 测试约束：show 依赖真实屏幕（RelativePoint → getLocationOnScreen），
 * 测试走 {@link #buildContent}（构建不显示、编辑器用后释放）、onClick/anchorPointFor/
 * isScheduled/currentPopup 交互断言。
 */
public class SqlClickPreview {

    /**
     * 单击预览浮层文本（T-ListPreviewCopy）：分号收尾 + 按设置「预览时格式化还原 SQL」
     * （logFormatSql，默认开=默认格式化显示）决定是否 {@link SqlFormatter#format} 多行展开。
     * 与双击弹窗 {@code toPreviewResult} 的 {@code formatIfNeeded(withTerminator(...))} 同序——
     * 先补分号再格式化（生产已验证的分号处理路径）。纯函数，开关由调用方读设置传入。
     */
    static String clickPreviewSql(String sql, boolean formatSql) {
        return formatSql ? SqlFormatter.format(SqlLogPanelKt.withTerminator(sql)) : SqlLogPanelKt.withTerminator(sql);
    }

    /**
     * 修饰键是否表达列表多选意图（T-ClickPreviewCopyRestore）：Ctrl/Shift 单击是选择的开始或扩展，
     * 修饰键在<b>第一击</b>就可判定——不必等 {@code selectedIndices.size > 1}（那要等第二击才满足），
     * 第一击就弹预览抢焦点正是批量复制被打断的根因。纯函数，onClick 守卫与测试共用。
     */
    static boolean isMultiSelectIntent(int modifiersEx) {
        return (modifiersEx & (java.awt.event.MouseEvent.CTRL_DOWN_MASK
                | java.awt.event.MouseEvent.SHIFT_DOWN_MASK)) != 0;
    }

    private final Project project;
    private final javax.swing.JList<?> list;
    /** 指定行号的预览 SQL；null = 该行不可预览。点击时捕获，定时器到点直接用，不依赖当时选中态。 */
    private final IntFunction<String> sqlAt;

    private JBPopup popup = null;

    /** 本次点击待预览的行号与 SQL（onClick 命中行时捕获）。 */
    private int pendingIndex = -1;
    private String pendingSql = null;

    private final Timer timer;

    public SqlClickPreview(Project project, javax.swing.JList<?> list, IntFunction<String> sqlAt) {
        this.project = project;
        this.list = list;
        this.sqlAt = sqlAt;
        this.timer = new Timer(CLICK_PREVIEW_DELAY_MS, null);
        this.timer.setRepeats(false);
        this.timer.addActionListener(e -> show());
    }

    /** 单击处理（无修饰键形态——Kotlin 默认参数 modifiersEx = 0 的 Java 重载等价）。 */
    public void onClick(int clickCount, Point clickPoint) {
        onClick(clickCount, clickPoint, 0);
    }

    /**
     * 列表单击处理：命中行才排程预览；空白区立即收表关预览；双击停表关旧预览；
     * 多选进行中或带 Ctrl/Shift 修饰键（多选第一击，T-ClickPreviewCopyRestore）不排程。
     */
    public void onClick(int clickCount, Point clickPoint, int modifiersEx) {
        if (clickCount >= 2) {
            timer.stop();
            hide();
            return;
        }
        // T-ListPreviewCopy：多选（Ctrl/Shift/拖框 ≥2 行）时单击不再排程预览——多选操作被
        // 逐行弹出的单行预览干扰；与空白区同语义：停表并关旧。
        // T-ClickPreviewCopyRestore：守卫前移到修饰键本身——≥2 行判定要等第二击才满足，
        // Ctrl/Shift 的第一击仍会弹预览抢走列表焦点（setRequestFocus(true)），后续多选
        // 节奏被打断、Ctrl+C 批量复制不可达；修饰键在第一击即可判定，全程零干扰。
        if (list.getSelectedIndices().length > 1 || isMultiSelectIntent(modifiersEx)) {
            timer.stop();
            hide();
            return;
        }
        int index = list.locationToIndex(clickPoint);
        Rectangle bounds = index >= 0 ? list.getCellBounds(index, index) : null;
        boolean hitRow = bounds != null && bounds.contains(clickPoint);
        pendingIndex = index;
        pendingSql = hitRow ? sqlAt.apply(index) : null;
        if (pendingSql == null) {
            timer.stop();
            hide();
        } else {
            timer.restart();
        }
    }

    /** 关闭当前预览弹窗（无则空操作）。 */
    public void hide() {
        if (popup != null && popup.isVisible()) popup.cancel();
        popup = null;
    }

    /** 面板 dispose：停定时器 + 关弹窗（编辑器经 onClosed 一并释放）。 */
    public void dispose() {
        timer.stop();
        hide();
    }

    /** 定时器是否挂起中（测试断言：单击已排程 / 空白区已取消）。 */
    public boolean isScheduled() {
        return timer.isRunning();
    }

    /** 当前预览弹窗（测试断言：外部关闭后不残留）。 */
    public JBPopup getCurrentPopup() {
        return popup;
    }

    /**
     * 指定行的预览锚点：该行下缘左侧留 2px 缝（弹窗在行下方展开，不遮被点行）。
     * 行不可得返回 null（调用方回退 showUnderneathOf）。屏幕下方放不下时由平台自动贴边回调。
     */
    public Point anchorPointFor(int index) {
        Rectangle bounds = list.getCellBounds(index, index);
        if (bounds == null) return null;
        return new Point(bounds.x, bounds.y + bounds.height + ANCHOR_GAP_PX);
    }

    /** Timer 到点：对点击时捕获的行弹出预览（新弹前先关旧的）；已多选则放弃（延迟窗内补选竞态兜底）。 */
    private void show() {
        String sql = pendingSql;
        if (sql == null) return;
        if (list.getSelectedIndices().length > 1) {
            hide();
            return;
        }
        if (popup != null && popup.isVisible()) popup.cancel();
        JBPopup next = buildPopup(sql);
        popup = next;
        Point anchor = pendingIndex >= 0 ? anchorPointFor(pendingIndex) : null;
        if (anchor != null) {
            next.show(new RelativePoint(list, anchor));
        } else {
            next.showUnderneathOf(list);
        }
    }

    /** 构建预览弹窗（实例路径）：复制走系统剪贴板（取编辑后当前文本），关闭走 hide；编辑器随弹窗关闭释放。 */
    private JBPopup buildPopup(String sql) {
        SqlClickPreviewContent content = buildContent(
                project,
                sql,
                UiTextClipboard::copy,
                this::hide
        );
        final Editor editor = content.editor();
        return JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content.component(), null)
                // 四轮修复：setRequestFocus(false) 导致弹窗内编辑器拿不到键盘焦点，
                // 键盘输入全落到列表上——看似"只读"。改 true：弹出即聚焦内容，
                // 焦点自然落进编辑器，可直接打字编辑（复制/关闭按钮 isFocusable=false 不抢焦点）
                .setRequestFocus(true)
                .setFocusable(true)
                .setResizable(true)
                .setMovable(false)
                .setCancelOnClickOutside(true)
                .setCancelOnOtherWindowOpen(true)
                .addListener(new JBPopupListener() {
                    @Override
                    public void onClosed(LightweightWindowEvent event) {
                        popup = null; // 点外/Esc 等外部关闭后清引用，杜绝复用已关闭弹窗
                        if (editor != null) EditorFactory.getInstance().releaseEditor(editor);
                    }
                })
                .createPopup();
    }

    /** 单击 → 预览延迟（ms）：正常双击间隔（150~250ms）内第二击先到 → 不闪预览。 */
    public static final int CLICK_PREVIEW_DELAY_MS = 300;

    /** 弹窗锚点与被点行下缘的间距（px）。 */
    private static final int ANCHOR_GAP_PX = 2;

    private static final int PREFERRED_WIDTH = 560;
    private static final int PREFERRED_HEIGHT = 320;

    /**
     * 标记浮层预览编辑器（T-ClickPreviewCopyRestore）：copy handler 据此区分
     * 「本插件的预览编辑器」与「IDE 其余一切编辑器」——只对前者接管无选中 Ctrl+C 语义。
     */
    private static final Key<Boolean> PREVIEW_EDITOR_KEY = Key.create("mpsv.clickPreview.editor");

    /** 浮层编辑器上挂的复制动作（头部按钮与 Ctrl+C handler 共用同一实例，含对号反馈）。 */
    private static final Key<Runnable> COPY_ACTION_KEY = Key.create("mpsv.clickPreview.copyAction");

    /** 原生 copy handler 安装只做一次（编辑器实例可多次创建，Key 挂实例天然隔离）。 */
    private static final Object COPY_HANDLER_LOCK = new Object();
    private static boolean copyHandlerInstalled = false;

    /**
     * 接管 ACTION_EDITOR_COPY 的<b>无选中</b>分支（T-ClickPreviewCopyRestore）：
     * 浮层编辑器无选区时 Ctrl+C 复制全文（编辑后文本，与头部按钮同源）——
     * 平台编辑器 copy 按选区工作、无选中是 no-op，用户「点开浮层直接 Ctrl+C」因此什么都没发生；
     * 有选区不接管（放行原生：只复制选中片段，保留「临时改字段值后复制片段」能力）；
     * 非浮层编辑器一律委托原生 handler，IDE 全局语义零变化。keymap 里 Ctrl+C 与 Ctrl+Insert
     * 都绑 $Copy，替换 handler 一处两键同生效。全局替换以静态惰性单次 + 委托兜底保证安全
     * （测试进程内同样生效，直调 handler 即可断言）。
     */
    static void installCopyHandler() {
        synchronized (COPY_HANDLER_LOCK) {
            if (copyHandlerInstalled) return;
            EditorActionManager manager = EditorActionManager.getInstance();
            EditorActionHandler origin = manager.getActionHandler(IdeActions.ACTION_EDITOR_COPY);
            manager.setActionHandler(IdeActions.ACTION_EDITOR_COPY, new EditorActionHandler() {
                @Override
                protected void doExecute(Editor editor, Caret caret, DataContext dataContext) {
                    if (Boolean.TRUE.equals(editor.getUserData(PREVIEW_EDITOR_KEY))
                            && !editor.getSelectionModel().hasSelection()) {
                        Runnable action = editor.getUserData(COPY_ACTION_KEY);
                        if (action != null) action.run();
                        return;
                    }
                    origin.execute(editor, caret, dataContext);
                }

                @Override
                protected boolean isEnabledForCaret(Editor editor, Caret caret, DataContext dataContext) {
                    return Boolean.TRUE.equals(editor.getUserData(PREVIEW_EDITOR_KEY))
                            // 三参 isEnabled 是 final 分派器（243 源码核验：caret 非 null 即转发 origin 的
                            // isEnabledForCaret）——委托它保持 origin 完整的可用性判定语义
                            || origin.isEnabled(editor, caret, dataContext);
                }
            });
            copyHandlerInstalled = true;
        }
    }

    /**
     * 构建预览内容：头部「复制 | 关闭」图标按钮 + SQL 高亮可编辑编辑器。
     * 高亮 = SqlListSyntaxHighlighter（SqlLexer 分词 + SqlHighlightColors 配色键，
     * 与日志列表条目共用同一映射——四轮用户反馈：预览高亮须与列表同色，直接复用；
     * 不走平台 SQL FileType 方言高亮，也不依赖 Database 插件，缺席环境同样有高亮），
     * 软换行长 SQL 免横向滚动，隐藏行号/折叠/缩进导轨/页底留白的紧凑外观。
     * 按钮回调由调用方注入（测试直录断言）；调用方负责释放 editor（弹窗路径经 onClosed）。
     */
    public static SqlClickPreviewContent buildContent(
            Project project,
            String sql,
            Consumer<String> copyText,
            Runnable close
    ) {
        com.intellij.openapi.editor.Document document = EditorFactory.getInstance().createDocument(sql);
        Editor editor = EditorFactory.getInstance().createEditor(document, project);
        if (editor instanceof EditorEx) {
            EditorEx editorEx = (EditorEx) editor;
            // createEditor 静态类型为 Editor，setHighlighter 在 EditorEx（243 javap 核验）；
            // EditorImpl 实现之，非 EditorEx 实现时跳过（降级无高亮纯文本）
            editorEx.setHighlighter(
                    new LexerEditorHighlighter(
                            new SqlListSyntaxHighlighter(),
                            EditorColorsManager.getInstance().getGlobalScheme()
                    ));
            editorEx.getSettings().setLineNumbersShown(false); // 紧凑弹窗不留 gutter
            editorEx.getSettings().setFoldingOutlineShown(false);
            editorEx.getSettings().setIndentGuidesShown(false);
            editorEx.getSettings().setAdditionalPageAtBottom(false);
            editorEx.getSettings().setCaretRowShown(false);
            editorEx.getSettings().setUseSoftWraps(true); // 长 SQL 软换行免横向滚动
            // T-PreviewWhitespaceToggle O-44a：空白符可视化（默认关=不设置该属性、继承 IDE 全局）
            cn.song.mybatisplus.visualizer.ui.EditorWhitespaceSettings.applyTo(editorEx);
        }
        editor.getComponent().setPreferredSize(new Dimension(PREFERRED_WIDTH, PREFERRED_HEIGHT));

        // T-AutoFold F4-b：弹窗编辑器程序性折叠（关闭态 applyTo 内部即清除短路，零外观变更；
        // 开启态由 applyTo 补设 isFoldingOutlineShown=true——上面紧凑外观的 false 被覆盖）
        cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(editor, sql);

        JButton copyButton = new JButton(AllIcons.Actions.Copy);
        copyButton.setToolTipText(MpSqlVisualizerBundle.message("log.preview.copy"));
        copyButton.setFocusable(false);
        JButton closeButton = new JButton(AllIcons.Actions.Close);
        closeButton.setToolTipText(MpSqlVisualizerBundle.message("log.preview.close"));
        closeButton.setFocusable(false);
        closeButton.addActionListener(e -> close.run());
        // T-ClickPreviewCopyRestore：头部按钮与编辑器 Ctrl+C handler 共用同一复制动作
        //（取编辑后当前文本 + 图标换对号反馈），两条入口产物与反馈一致
        Runnable copyAll = () -> {
            copyText.accept(editor.getDocument().getText()); // 复制当前内容（含用户编辑后文本）
            cn.song.mybatisplus.visualizer.ui.CopyFeedback.flashCopied(copyButton); // 复制成功反馈：复制图标换对号，短暂后恢复
        };
        copyButton.addActionListener(e -> copyAll.run());
        editor.putUserData(PREVIEW_EDITOR_KEY, true);
        editor.putUserData(COPY_ACTION_KEY, copyAll);
        installCopyHandler();

        // T-SqlCopyShortcuts O-122（原二轮内联实现收敛到共享件）：Swing 层 Ctrl+C / Ctrl+Insert
        // 接管——无选中复制全文、有选中只复制选区（保留改字段值后复制片段），与头部按钮同反馈；
        // 为什么必须挂在这一层、为什么不是 keymap 侧，见 EditorCopyShortcuts 的 KDoc。
        EditorCopyShortcuts.install(
                editor,
                copyText,
                () -> cn.song.mybatisplus.visualizer.ui.CopyFeedback.flashCopied(copyButton));
        JPanel header = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        header.add(copyButton);
        header.add(closeButton);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(header, BorderLayout.NORTH);
        panel.add(editor.getComponent(), BorderLayout.CENTER);
        return new SqlClickPreviewContent(panel, editor);
    }
}
