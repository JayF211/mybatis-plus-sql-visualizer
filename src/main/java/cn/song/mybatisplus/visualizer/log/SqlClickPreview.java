package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.ui.highlight.SqlListSyntaxHighlighter;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
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
import java.awt.datatransfer.StringSelection;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
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
 *
 * 弹窗形态：头部复制/关闭图标按钮 + SQL 高亮可编辑编辑器（三轮用户要求：高亮展示 +
 * 可临时改字段值再复制）。高亮走 {@link SqlListSyntaxHighlighter}（SqlLexer 分词 +
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

    /** 列表单击处理：命中行才排程预览；空白区立即收表关预览；双击停表关旧预览。 */
    public void onClick(int clickCount, Point clickPoint) {
        if (clickCount >= 2) {
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

    /** Timer 到点：对点击时捕获的行弹出预览（新弹前先关旧的）。 */
    private void show() {
        String sql = pendingSql;
        if (sql == null) return;
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
                text -> CopyPasteManager.getInstance().setContents(new StringSelection(text)),
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
        }
        editor.getComponent().setPreferredSize(new Dimension(PREFERRED_WIDTH, PREFERRED_HEIGHT));

        JButton copyButton = new JButton(AllIcons.Actions.Copy);
        copyButton.setToolTipText(MpSqlVisualizerBundle.message("log.preview.copy"));
        copyButton.setFocusable(false);
        copyButton.addActionListener(e -> {
            copyText.accept(editor.getDocument().getText()); // 复制当前内容（含用户编辑后文本）
            flashCopied(copyButton); // 复制成功反馈：复制图标换对号，短暂后恢复
        });
        JButton closeButton = new JButton(AllIcons.Actions.Close);
        closeButton.setToolTipText(MpSqlVisualizerBundle.message("log.preview.close"));
        closeButton.setFocusable(false);
        closeButton.addActionListener(e -> close.run());
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

    /**
     * 复制成功反馈：复制按钮图标短暂换成对号（AllIcons.Actions.Checked）再恢复，
     * 用一次性 Timer（1500ms）复位——不引入常驻定时器，按钮复用即过期。
     */
    private static void flashCopied(JButton button) {
        javax.swing.Icon copyIcon = button.getIcon();
        button.setIcon(AllIcons.Actions.Checked);
        Timer reset = new Timer(1500, null);
        reset.setRepeats(false);
        reset.addActionListener(e -> button.setIcon(copyIcon));
        reset.start();
    }
}
