// T-SyncK2JMaster482 波D2 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/StackFrameHover.kt
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.JBPopupListener;
import com.intellij.openapi.ui.popup.LightweightWindowEvent;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * T-StackHoverUi：SQL 预览面板 meta 行的调用栈悬停弹层（D84 拍板 A 案）。
 *
 * <p>交互契约（对照 IDEA 调试器 Frames 视图形态）：</p>
 * <ul>
 * <li>meta 行悬停 400ms 后弹层（单发 Timer 防抖，historyAlarm 同款惯用法）；栈顶帧在顶、
 *   首行默认选中（对应调试器「当前帧」高亮）；行内三段着色＝方法名（前景）+ 全限定类名（灰）+
 *   `:行号`（灰），类名超宽中段省略（行号始终可见）；agent 自身帧整行淡灰降权（模拟库帧弱化）。</li>
 * <li>鼠标在「meta 行 ∪ 弹层」区域内移动不关；离开 300ms 宽限后判定当前指针位置再关（事件坐标
 *   是越界瞬间的旧值，判定用 {@link MouseInfo} 实时指针），ESC / 点击外部 / 其他弹层打开由平台 popup
 *   栈兜底关闭。</li>
 * <li>点击帧跳源码（D85 拍板纳入）：全项目域按类名解析，命中即 {@link OpenFileDescriptor} 直跳行号
 *   （与日志面板跳转同基建）；未命中<b>弹层保持不关</b>——不制造已跳转的假象，也无声无成功提示。</li>
 * <li>底部「复制调用栈全文」复制原始快照串（原样，未解析）。</li>
 * </ul>
 *
 * <p>栈数据来源：{@code updateDigest} 由 {@code SqlPreviewPanel.updateMeta} 每次预览喂入；栈非空时 tooltip
 * 不再承载栈文本（旧实现把截断摘要+全文重复拼进 tooltip、超长单行折成文本墙——本类是栈文本
 * 在悬停面的唯一载体）。</p>
 */
public final class StackFrameHover implements Disposable {

    private final Project project;
    private JComponent target = null;
    private String digest = null;
    private JBPopup popup = null;
    private JComponent contentPanel = null;
    private Point pendingAnchor = null;

    /** 悬停延迟（ms）：防误触——meta 行是高频掠过区域，立即弹层会干扰正常阅读。 */
    private final Timer hoverTimer = new Timer(HOVER_DELAY_MS, e -> showPopup());

    /** 移出宽限（ms）：跨「meta 行 ↔ 弹层」边界的空档不立即关，宽限后按实时指针位置判定。 */
    private final Timer hideTimer = new Timer(HIDE_GRACE_MS, e -> hideIfPointerOutside());

    /** 弹层内容区的显隐联动：移入取消关闭宽限、移出重新宽限判定。 */
    private final MouseAdapter hideManager = new MouseAdapter() {
        @Override
        public void mouseEntered(MouseEvent e) {
            hideTimer.stop();
        }

        @Override
        public void mouseExited(MouseEvent e) {
            if (popup != null && !popup.isDisposed()) hideTimer.restart();
        }
    };

    public StackFrameHover(@Nullable Project project) {
        this.project = project;
        hoverTimer.setRepeats(false);
        hideTimer.setRepeats(false);
    }

    /** 挂到 meta 行（悬停触发点）；面板 init 里调用一次。 */
    public void attach(JComponent target) {
        this.target = target;
        target.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (digest == null) return;
                if (popup != null && !popup.isDisposed()) return;
                pendingAnchor = e.getPoint();
                hoverTimer.restart();
            }

            // 锚点跟随悬停落点：mouseEntered 只给边缘穿越点（自下而上进入＝行底），弹层锚定位置随
            // 进入方向漂移；移动中持续更新才保证「锚在哪弹在哪」
            @Override
            public void mouseMoved(MouseEvent e) {
                if (digest == null) return;
                if (popup != null && !popup.isDisposed()) return;
                pendingAnchor = e.getPoint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hoverTimer.stop();
                if (popup != null && !popup.isDisposed()) hideTimer.restart();
            }
        });
    }

    /** 每次预览更新栈数据；栈变化即关旧弹层（下次悬停按新数据重弹）。 */
    public void updateDigest(@Nullable String newDigest) {
        digest = newDigest;
        cancelPopup();
    }

    /** 当前栈快照（面板接线单测断言用）。 */
    @Nullable
    public String currentDigestForTest() {
        return digest;
    }

    @Override
    public void dispose() {
        hoverTimer.stop();
        hideTimer.stop();
        cancelPopup();
    }

    // ------------------------------------------------------------------
    // 弹层构建与展示
    // ------------------------------------------------------------------

    private void showPopup() {
        Point anchor = pendingAnchor;
        if (anchor == null) return;
        String d = digest;
        if (d == null) return;
        JComponent t = target;
        if (t == null) return;
        if (popup != null && !popup.isDisposed()) return;
        List<StackFrameEntry> frames = StackDigestFrames.parse(d);
        if (frames.isEmpty()) return;

        JComponent root = buildContent(frames, d);
        com.intellij.openapi.ui.popup.ComponentPopupBuilder builder =
                JBPopupFactory.getInstance().createComponentPopupBuilder(root, null)
                        .setTitle(MpSqlVisualizerBundle.message("panel.stackPopup.title"))
                        .setRequestFocus(false)
                        .setFocusable(false)
                        .setCancelOnClickOutside(true);
        builder.addListener(new JBPopupListener() {
            @Override
            public void onClosed(LightweightWindowEvent event) {
                popup = null;
                contentPanel = null;
            }
        });
        JBPopup p = builder.createPopup();
        popup = p;
        contentPanel = root;
        p.show(new RelativePoint(t, anchor));
    }

    private JComponent buildContent(List<StackFrameEntry> frames, String fullDigest) {
        JBList<StackFrameEntry> list = new JBList<>(frames);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectedIndex(0);
        // 视口按全量帧数放开：JList 默认 visibleRowCount=8 会把链尾业务帧折叠进滚动区，
        // 「弹层默认锚定最深的业务帧、一眼看到 Controller → Service 的调用路径」落空；
        // 上限对齐栈帧预算钳位（64），超出才回退滚动
        list.setVisibleRowCount(Math.min(frames.size(), MAX_VISIBLE_ROWS));
        FontMetrics fm = list.getFontMetrics(list.getFont());
        int maxRow = 0;
        for (StackFrameEntry f : frames) {
            int w = fm.stringWidth(f.method()) + fm.stringWidth(ROW_GAP) + fm.stringWidth(f.fqcn())
                    + (f.line() > 0 ? fm.stringWidth(":" + f.line()) : 0);
            if (w > maxRow) maxRow = w;
        }
        int contentW = Math.max(MIN_POPUP_WIDTH, Math.min(MAX_POPUP_WIDTH, maxRow + POPUP_WIDTH_PADDING));
        list.setCellRenderer(new FrameRenderer(fm, contentW));
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                int idx = list.locationToIndex(e.getPoint());
                if (idx < 0) return;
                navigateTo(frames.get(idx));
            }
        });

        ActionLink copyLink = new ActionLink(MpSqlVisualizerBundle.message("panel.stackPopup.copy"), (ActionEvent e) -> {
            UiTextClipboard.copy(fullDigest);
            cancelPopup();
        });
        JBPanel<JBPanel<?>> south = new JBPanel<JBPanel<?>>(new BorderLayout());
        south.setOpaque(false);
        south.setBorder(JBUI.Borders.empty(4, 10, 6, 10));
        south.add(copyLink, BorderLayout.EAST);

        JBPanel<JBPanel<?>> root = new JBPanel<JBPanel<?>>(new BorderLayout());
        root.add(new JBScrollPane(list), BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        root.addMouseListener(hideManager);
        int w = Math.max(contentW + ROOT_WIDTH_PADDING, south.getPreferredSize().width + 20);
        root.setPreferredSize(new Dimension(w, root.getPreferredSize().height));
        return root;
    }

    // ------------------------------------------------------------------
    // 关闭判定与跳转
    // ------------------------------------------------------------------

    /** 宽限期到：按**实时**指针位置判定——仍落在「弹层（带边距）∪ meta 行」内则继续留。 */
    private void hideIfPointerOutside() {
        JBPopup p = popup;
        if (p == null) return;
        if (p.isDisposed()) return;
        Point pointer = MouseInfo.getPointerInfo() != null ? MouseInfo.getPointerInfo().getLocation() : null;
        if (pointer == null) {
            cancelPopup();
            return;
        }
        JComponent c = contentPanel;
        if (c != null && c.isShowing()) {
            Rectangle b = new Rectangle(c.getLocationOnScreen(), c.getSize());
            b.grow(HIDE_MARGIN_PX, HIDE_MARGIN_PX);
            if (b.contains(pointer)) return;
        }
        JComponent t = target;
        if (t != null && t.isShowing()
                && new Rectangle(t.getLocationOnScreen(), t.getSize()).contains(pointer)) return;
        cancelPopup();
    }

    private void cancelPopup() {
        JBPopup p = popup;
        if (p != null && !p.isDisposed()) p.cancel();
        popup = null;
        contentPanel = null;
    }

    /**
     * 点击帧跳源码：全项目域解析类名（库类落反编译视图，与调试器行为一致）；未命中静默返回
     * （弹层不关——不制造已跳转假象）。行号换算 `line - 1`（{@link OpenFileDescriptor} 0 基），与
     * 日志面板跳转链一致；无行号帧落类声明处。
     */
    private void navigateTo(StackFrameEntry frame) {
        Project proj = project;
        if (proj == null) return;
        PsiClass psiClass = ApplicationManager.getApplication().runReadAction(
                (Computable<PsiClass>) () -> JavaPsiFacade.getInstance(proj).findClass(frame.fqcn(), GlobalSearchScope.allScope(proj)));
        if (psiClass == null) return;
        com.intellij.openapi.vfs.VirtualFile vf = ApplicationManager.getApplication().runReadAction(
                (Computable<com.intellij.openapi.vfs.VirtualFile>) () ->
                        psiClass.getContainingFile() != null ? psiClass.getContainingFile().getVirtualFile() : null);
        cancelPopup();
        if (vf != null && frame.line() > 0) {
            new OpenFileDescriptor(proj, vf, frame.line() - 1, 0).navigate(true);
        } else if (vf != null) {
            new OpenFileDescriptor(proj, vf).navigate(true);
        } else {
            psiClass.navigate(true);
        }
    }

    // ------------------------------------------------------------------
    // 调试器 Frames 风格行渲染
    // ------------------------------------------------------------------

    /**
     * 行渲染：`method  fqcn:line` 三段着色——方法名前景（agent 自身帧淡灰降权）、类名灰、
     * 行号灰；类名超宽按 {@code ellipsizeMiddle} 中段省略（保留头部包名与尾部类名），行号恒可见。
     * 预算以弹层定宽 {@code contentW} 为准（与构建期测宽同字体同度量，保证逐行对齐不横向滚动）。
     */
    private final class FrameRenderer extends ColoredListCellRenderer<StackFrameEntry> {

        private final FontMetrics fm;
        private final int contentW;

        FrameRenderer(FontMetrics fm, int contentW) {
            this.fm = fm;
            this.contentW = contentW;
        }

        @Override
        protected void customizeCellRenderer(JList<? extends StackFrameEntry> list, StackFrameEntry value,
                                             int index, boolean selected, boolean hasFocus) {
            boolean agent = StackDigestFrames.isAgentFrame(value);
            append(value.method(), agent ? SimpleTextAttributes.GRAYED_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
            append(ROW_GAP, SimpleTextAttributes.GRAYED_ATTRIBUTES);
            String lineSuffix = value.line() > 0 ? ":" + value.line() : "";
            int classBudget = contentW - fm.stringWidth(value.method()) - fm.stringWidth(ROW_GAP)
                    - fm.stringWidth(lineSuffix) - POPUP_WIDTH_PADDING;
            ToIntFunction<String> widthOf = s -> fm.stringWidth(s);
            String classText = classBudget > 0
                    ? SqlPreviewPanelKt.ellipsizeMiddle(value.fqcn(), widthOf, classBudget)
                    : value.fqcn();
            append(classText, SimpleTextAttributes.GRAY_ATTRIBUTES);
            if (!lineSuffix.isEmpty()) append(lineSuffix, SimpleTextAttributes.GRAY_ATTRIBUTES);
        }
    }

    /** 悬停延迟：见 {@link #hoverTimer}。 */
    private static final int HOVER_DELAY_MS = 400;

    /** 移出宽限：见 {@link #hideTimer}。 */
    private static final int HIDE_GRACE_MS = 300;

    /** 弹层越界判定的外扩边距（px）：指针在边界抖动时不误关。 */
    private static final int HIDE_MARGIN_PX = 8;

    /** 行内「方法名 ↔ 类名」间隔串（同时参与测宽）。 */
    private static final String ROW_GAP = "  ";

    /** 弹层宽度上下界（px）：内容自适应，最长 2000 字符快照也封顶横向滚动外溢。 */
    private static final int MIN_POPUP_WIDTH = 280;
    private static final int MAX_POPUP_WIDTH = 680;

    /** 测宽加成：行内边距 + 竖向滚动条余量。 */
    private static final int POPUP_WIDTH_PADDING = 24;
    private static final int ROOT_WIDTH_PADDING = 8;

    /** 弹层视口最大可见行数：对齐 agent 栈帧预算钳位上限（StackDigest 12~64）。 */
    private static final int MAX_VISIBLE_ROWS = 64;
}
