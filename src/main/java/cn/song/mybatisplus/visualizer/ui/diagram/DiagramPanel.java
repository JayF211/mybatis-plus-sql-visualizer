package cn.song.mybatisplus.visualizer.ui.diagram;
import java.util.List;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.icons.AllIcons;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBLoadingPanel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseWheelEvent;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.imageio.ImageIO;
import javax.swing.JTextArea;

/**
 * F-1/F-xx：JOIN 关系图渲染面板。
 *
 * 渲染方案：paintComponent 内直接 Graphics2D 变换绘制，无中间位图。
 * 组件尺寸 = 图表自然尺寸 × zoom，scroll pane 处理溢出。
 *
 * <p>T-UiNative T-UN-14：加载态/空态接原生件——加载态由 {@link JBLoadingPanel} 盖平台转圈
 * （原 indeterminate JProgressBar 自摆页删除），「无 JOIN」占位与错误态保留原样。
 *
 * <p>T-RA-02：错误态接线——渲染（{@link DiagramRenderer} 测量/布局）抛异常时落 {@link #errorScroll} 错误页，
 * 文案键 {@code diagram.render.failed}，由 {@link #renderGuarded} 统一兜底。此前该错误页只有声明没有接线
 * （84d4407「直接 Graphics2D 渲染」重写时删掉了 showError 与 try/catch，字段与文案键一起悬空），
 * 类 KDoc 宣称的错误态与实际不符——本次把实现补上，表述与实现对齐。
 */
public class DiagramPanel extends JBPanel<DiagramPanel> implements Disposable {

    private double zoom = 1.0;
    @Nullable
    private DiagramModel currentModel = null;

    /**
     * T-UiNative T-UN-14：加载态接原生件——JBLoadingPanel 作为 CENTER 容器包住 swapContent
     * 的目标区（占位/画布/错误态都在其内容层换页），加载时 startLoading 盖平台转圈，
     * 原自摆 JBLabel + indeterminate JProgressBar 加载页删除；文案沿用既有 "diagram.render.loading" 键。
     * Disposable 挂面板自身（本类实现 Disposable，dispose 走既有 Disposer.dispose(this) 链）。
     *
     * <p>T-F8：本层只在「未注入宿主加载面」时出转圈（独立使用/单测）；预览面板场景转圈由
     * {@link #hostLoadingPanel} 出，原因见该字段 KDoc。
     */
    private final JBLoadingPanel contentLayer;

    /**
     * T-F8：宿主加载面（预览面板注入）——本面板的转圈实际画在哪一层。
     *
     * <p>为什么必须由宿主出图：唯一真正的耗时段是 {@code SqlPreviewPanel.updateDiagramTab} 的<b>后台 schema
     * 富化</b>，而原生图路径（默认设置 {@code joinDiagramNative = true}，Ultimate 真机默认）下
     * {@code showJoinDiagram} 的 {@code diagramTabHost.removeAll()} 会把本面板摘出层级——自摆转圈根本不可见，
     * 这正是审计项 F8「showLoading/hideLoading 全仓零调用点」之外的第二层失效。宿主层
     * （预览面板把整个 JOIN 图标签页包进 JBLoadingPanel）对原生/自绘两条路径都可见。
     *
     * <p>为什么是「转交」而不是「两层同开」：同页两层 LoadingDecorator 各持一条独立动画时间线
     * （每个实例一个 AnimatedIcon/Animator，转圈相位互不同步），会叠成双圈；且宿主层已完整覆盖
     * 本面板区域（面板是其 CENTER 内容），面板侧再转一圈纯属重复。
     */
    @Nullable
    private JBLoadingPanel hostLoadingPanel = null;

    /** T-F8：注入宿主加载面（预览面板的标签页级 {@link JBLoadingPanel}）。 */
    public void bindHostLoadingPanel(JBLoadingPanel panel) {
        hostLoadingPanel = panel;
    }

    /** 当前出转圈的层：宿主注入优先，否则本面板 {@link #contentLayer}。 */
    private JBLoadingPanel loadingTarget() {
        return hostLoadingPanel != null ? hostLoadingPanel : contentLayer;
    }
    private final JBLabel placeholderLabel;
    /**
     * T-RA-02：渲染失败页——{@link #contentLayer} 的第三个换页目标（另两个是 {@link #placeholderLabel} 与 {@link #scrollPane}）。
     *
     * <p>84d4407 把渲染改为直接 Graphics2D 后删掉了 {@code showError}，这两个字段与 bundle 键
     * {@code diagram.render.failed} 一起悬空（全仓含测试零引用），类 KDoc 却仍宣称有错误态。
     * 现由 {@link #showError} 接线：渲染失败时用户看到失败原因，而不是上一态/空画布。
     * 用 JTextArea（非 JBLabel）是为了长异常摘要可换行、可选中复制。
     */
    private final JTextArea errorTextArea;
    private final JBScrollPane errorScroll;
    private final DiagramCanvas diagramCanvas;
    private final JBScrollPane scrollPane;

    @Nullable
    private JComponent currentCenter = null;

    @Nullable
    private final Project project;

    public DiagramPanel() {
        this(null);
    }

    public DiagramPanel(@Nullable Project project) {
        super(new BorderLayout());
        this.project = project;

        contentLayer = new JBLoadingPanel(new BorderLayout(), this);

        placeholderLabel = new JBLabel(
                MpSqlVisualizerBundle.message("diagram.placeholder.noJoin"), SwingConstants.CENTER);
        placeholderLabel.setForeground(JBColor.GRAY);

        errorTextArea = new JTextArea();
        errorTextArea.setEditable(false);
        errorTextArea.setFont(UIUtil.getLabelFont());
        errorTextArea.setBackground(UIUtil.getPanelBackground());
        errorTextArea.setBorder(JBUI.Borders.empty(8));
        errorTextArea.setLineWrap(true);
        errorTextArea.setWrapStyleWord(true);

        errorScroll = new JBScrollPane(errorTextArea);

        diagramCanvas = new DiagramCanvas();

        scrollPane = new JBScrollPane(diagramCanvas);
        scrollPane.setBorder(JBUI.Borders.empty());
        scrollPane.getViewport().setBackground(UIUtil.getPanelBackground());
        scrollPane.addMouseWheelListener((MouseWheelEvent e) -> {
            if (e.isControlDown()) {
                if (e.getWheelRotation() < 0) zoomIn();
                else zoomOut();
                e.consume();
            }
        });

        add(buildToolbar(), BorderLayout.NORTH);
        add(contentLayer, BorderLayout.CENTER);
        swapContent(placeholderLabel);
    }

    /**
     * T-UiNative T-UN-10：文本 JButton 按钮行 → 平台 ActionToolbar 图标动作组。
     * 图标均为 AllIcons 既有常量（javap 243 app-client.jar 核实：缩放/导出键都在 General，
     * Actions 组无 Zoom、Export 字段）；原按钮文案键转 tooltip（presentation.description），
     * 不新增 bundle 键。
     */
    private JComponent buildToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(diagramToolbarAction(AllIcons.General.ZoomIn, "diagram.zoomIn", this::zoomIn));
        group.add(diagramToolbarAction(AllIcons.General.ZoomOut, "diagram.zoomOut", this::zoomOut));
        group.add(diagramToolbarAction(AllIcons.General.ActualZoom, "diagram.zoomReset", this::zoomReset));
        group.addSeparator(); // 保留原「缩放 | 导出」分隔
        group.add(diagramToolbarAction(AllIcons.General.Export, "diagram.exportPng", this::exportPng));
        group.add(diagramToolbarAction(AllIcons.General.Export, "diagram.exportSvg", this::exportSvg));
        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar("mpvs.diagram.toolbar", group, true);
        toolbar.setTargetComponent(this);
        return toolbar.getComponent();
    }

    /** 图标动作（T-UN-10）：icon 固定、tooltip 沿用原按钮 bundle 键、回调直通原按钮逻辑。 */
    private AnAction diagramToolbarAction(javax.swing.Icon icon, String tipKey, Runnable action) {
        return new AnAction() {
            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setIcon(icon);
                e.getPresentation().setDescription(MpSqlVisualizerBundle.message(tipKey));
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                action.run();
            }
        };
    }

    /**
     * 展示模型（空模型 → 「无 JOIN」占位）。
     *
     * <p>T-RA-02：测量先于换页——测量失败时错误页直接顶上，不留半成品画布（原顺序是先换页再测量，
     * 测量一抛就只剩一个空画布）；失败判定与错误页切换都在 {@link #renderGuarded} 内。
     */
    public void showDiagram(DiagramModel model) {
        if (model.isEmpty()) {
            showPlaceholder();
            return;
        }
        currentModel = model;
        if (!renderGuarded(model, m -> diagramCanvas.setModel(m, zoom))) return;
        swapContent(scrollPane);
        revalidate();
        repaint();
    }

    /**
     * T-RA-02：渲染动作的统一兜底。
     *
     * <p>为什么必须有：本面板的渲染 try/catch 在 84d4407「直接 Graphics2D 渲染」重写时被删掉，
     * 只留下 {@link #errorTextArea}/{@link #errorScroll} 与文案键 {@code diagram.render.failed} 悬空；而调用链
     * {@code SqlPreviewPanel.showJoinDiagram}（:1187）→ {@code updateDiagramTab}（:1118）→ {@code showResult}（:1059）
     * → {@code SqlPreviewDialog.init}（:33）→ {@code SqlPreviewService.showResult}（:1977）<b>全程无 catch</b>——
     * 渲染一抛，异常直接穿到 DialogWrapper 构造，弹窗起不来且用户看不到任何原因。
     *
     * <p>单独成函数（而非把 try/catch 内联进 {@link #showDiagram}）是为了让失败路径可测：结构合法的模型在
     * {@link DiagramRenderer#measurePreferredSize} 里几乎不抛，公开 API 复现不出错误页。
     *
     * @param render 渲染动作（等价 Kotlin {@code (DiagramModel) -> Unit}）
     * @return true = 渲染成功；false = 已落错误页（调用方不得再换页/重绘）
     */
    public boolean renderGuarded(DiagramModel model, java.util.function.Consumer<DiagramModel> render) {
        try {
            render.accept(model);
            return true;
        } catch (Throwable t) {
            // 失败即回到「无图」语义：画布与 currentModel 一起清空，后续缩放/切页不再重试坏模型
            //（否则每次缩放都在同一处再抛一次）。warn 留痕：真机只有这里能看到渲染失败现场。
            com.intellij.openapi.diagnostic.Logger.getInstance(DiagramPanel.class)
                    .warn("JOIN diagram render failed", t);
            currentModel = null;
            diagramCanvas.setModel(null, zoom);
            showError(t.getClass().getSimpleName() + ": " + (t.getMessage() != null ? t.getMessage() : "unknown"));
            return false;
        }
    }

    /**
     * T-RA-02：渲染失败的错误页（{@link #errorScroll} 接到内容层换页目标）。
     *
     * @param message 异常摘要——调用方拼 {@code 异常类名: message}（比裸 message 更利于定位）。
     *                文案沿用既有键 {@code diagram.render.failed}（双语齐备、此前零引用），不新增键。
     */
    public void showError(String message) {
        errorTextArea.setText(MpSqlVisualizerBundle.message("diagram.render.failed", message));
        // 长摘要自动换行后默认停在末尾，从顶部看起更符合「读错误」的顺序
        errorTextArea.setCaretPosition(0);
        // T-F8：错误页顶上即「加载已结束」（渲染失败也是一种收尾），转圈必须同时摘下
        stopLoadingInternal();
        swapContent(errorScroll);
        revalidate();
        repaint();
    }

    /** T-RA-02：错误页是否当前页（测试断言用）。 */
    public boolean isErrorPageForTest() {
        return currentCenter == errorScroll;
    }

    /** T-RA-02：错误页文本（测试断言用）。 */
    public String errorTextForTest() {
        return errorTextArea.getText();
    }

    public void clear() {
        // T-F8：整图卸载必须复位加载态——加载期间（后台富化未回 EDT）用户切到无 JOIN 的 SQL
        // 或面板被清空时，转圈若不复位会留在已卸载的空页上永不消失
        stopLoadingInternal();
        currentModel = null;
        diagramCanvas.setModel(null, zoom);
        showPlaceholder();
    }

    public void refreshAfterVisible() {
        if (currentModel != null && isShowing()) {
            diagramCanvas.revalidate();
            repaint();
        }
    }

    private void showPlaceholder() {
        // T-F8：空态是「无图」而非「加载中」——空模型换页即收尾，转圈同时摘下
        stopLoadingInternal();
        swapContent(placeholderLabel);
        revalidate();
        repaint();
    }

    /**
     * T-UiNative T-UN-14：换页目标从面板本体改为 contentLayer 内容层
     * （加载转圈盖在内容层之上，画布/占位/错误态不受影响）。
     */
    private void swapContent(JComponent component) {
        if (currentCenter == component) return;
        if (currentCenter != null) contentLayer.remove(currentCenter);
        contentLayer.add(component, BorderLayout.CENTER);
        currentCenter = component;
    }

    /** T-UiNative T-UN-14：进入加载态——平台转圈 + 既有「加载中」文案键（原自摆进度条删除）。 */
    public void showLoading() {
        JBLoadingPanel target = loadingTarget();
        target.setLoadingText(MpSqlVisualizerBundle.message("diagram.render.loading"));
        target.startLoading();
        revalidate();
        repaint();
        if (loadingProbeForTest != null) {
            loadingProbeForTest.accept(true);
        }
    }

    /** T-UiNative T-UN-14：退出加载态（渲染/换页完成后调用）。 */
    public void hideLoading() {
        stopLoadingInternal();
        if (loadingProbeForTest != null) {
            loadingProbeForTest.accept(false);
        }
    }

    /**
     * T-F8：内部安全停止——不发探针（探针只报显式 API 调用，见 {@link #loadingProbeForTest}）。
     *
     * <p>供 {@link #clear}/{@link #showPlaceholder}/{@link #showError} 三条「加载态不再成立」的路径统一收口：
     * 加载期间无 JOIN 卸载、模型为空、渲染失败时都必须摘转圈，否则转圈留在空页/错误页上永不消失
     * （对齐 SqlResultPanel「仅 showLoading 打开、其余状态统一关闭」的既有约定）。
     */
    private void stopLoadingInternal() {
        loadingTarget().stopLoading();
        revalidate();
        repaint();
    }

    /** T-UiNative T-UN-14：加载态（测试断言用）。 */
    public boolean isLoadingForTest() {
        return loadingTarget().isLoading();
    }

    /**
     * T-TS-05：出转圈的层（测试断言用）。
     *
     * <p>与 {@link #isLoadingForTest} 的区别：后者读的是<b>委托结果</b>，宿主注入被删掉后仍能自洽地读回
     * {@link #contentLayer}——单看它断不出「转圈画在了哪一层」。真机默认原生图路径下本面板会被
     * {@code diagramTabHost.removeAll()} 摘出层级，{@link #contentLayer} 上的转圈根本不可见（F8 的原始形态），
     * 故第二层失效只能靠「出圈层 === 宿主加载面板」这种结构断言钉住。
     */
    public JBLoadingPanel loadingTargetForTest() {
        return loadingTarget();
    }

    /**
     * T-F8：加载态接线探针（测试用）——{@link #showLoading}/{@link #hideLoading} 末尾回调。
     *
     * <p>只报这两次「显式 API 调用」，内部安全停止（{@link #clear}/{@link #showPlaceholder}/{@link #showError}
     * 里的复位）不回调：接线用例要断的是「谁在什么时刻进了/出了加载态」，内部复位属状态自愈，由
     * {@link #isLoadingForTest} 观测即可，混进同一序列会让「进入→渲染→退出」的顺序断言失真。
     */
    @Nullable
    public volatile java.util.function.Consumer<Boolean> loadingProbeForTest = null;

    /** T-F8：是否已渲染出图（测试用）——接线用例据此断言「停转圈发生在渲染之后」。 */
    public boolean hasModelForTest() {
        return currentModel != null;
    }

    public void zoomIn() {
        zoom = Math.min(zoom * 1.25, 5.0);
        applyZoomToCanvas();
    }

    public void zoomOut() {
        zoom = Math.max(zoom / 1.25, 0.2);
        applyZoomToCanvas();
    }

    public void zoomReset() {
        zoom = 1.0;
        applyZoomToCanvas();
    }

    public double zoomForTest() {
        return zoom;
    }

    /**
     * T-RA-02：缩放重建画布尺寸走同一兜底（{@link #renderGuarded}）——缩放是工具栏动作回调，
     * 里面抛异常只进平台日志，用户侧表现为「点缩放没反应」（同族静默失效）。
     */
    private void applyZoomToCanvas() {
        DiagramModel model = currentModel;
        if (model == null) return;
        renderGuarded(model, m -> diagramCanvas.setModel(m, zoom));
        revalidate();
        repaint();
    }

    private void exportPng() {
        DiagramModel model = currentModel;
        if (model == null) return;
        Project proj = project;
        if (proj == null) return;
        // T-Perf-14：渲染 + 编码移后台（宽 SELECT 大模型全量重绘/图像编码在 EDT 构成卡顿）；
        // 保存对话框留在 EDT（用户交互），渲染/编码失败回 EDT 提示
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final byte[][] bytesHolder = new byte[1][];
            try {
                BufferedImage image = DiagramRenderer.render(model);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, "png", baos);
                if (baos.size() == 0) throw new RuntimeException("PNG encode produced no output");
                bytesHolder[0] = baos.toByteArray();
            } catch (Throwable t) {
                bytesHolder[0] = null;
            }
            byte[] bytes = bytesHolder[0];
            ApplicationManager.getApplication().invokeLater(() -> {
                if (bytes == null) {
                    showExportError(proj, "render/encode");
                    return;
                }
                try {
                    saveBytesToFile(proj, bytes, "join_diagram.png", "png");
                } catch (Exception e) {
                    showExportError(proj, e.getMessage() != null ? e.getMessage() : "unknown");
                }
            });
        });
    }

    private void exportSvg() {
        DiagramModel model = currentModel;
        if (model == null) return;
        Project proj = project;
        if (proj == null) return;
        // T-Perf-14：SVG 渲染/编码移后台（同 exportPng）
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final byte[][] bytesHolder = new byte[1][];
            try {
                bytesHolder[0] = DiagramRenderer.renderToSvg(model).getBytes(StandardCharsets.UTF_8);
            } catch (Throwable t) {
                bytesHolder[0] = null;
            }
            byte[] bytes = bytesHolder[0];
            ApplicationManager.getApplication().invokeLater(() -> {
                if (bytes == null) {
                    showExportError(proj, "render/encode");
                    return;
                }
                try {
                    saveBytesToFile(proj, bytes, "join_diagram.svg", "svg");
                } catch (Exception e) {
                    showExportError(proj, e.getMessage() != null ? e.getMessage() : "unknown");
                }
            });
        });
    }

    private void saveBytesToFile(Project proj, byte[] bytes, String defaultName, String ext) {
        FileSaverDescriptor descriptor = new FileSaverDescriptor(
                MpSqlVisualizerBundle.message("diagram.export.title"),
                MpSqlVisualizerBundle.message("diagram.export.desc"), ext);
        com.intellij.openapi.fileChooser.FileSaverDialog dialog =
                FileChooserFactory.getInstance().createSaveFileDialog(descriptor, proj);
        VirtualFileWrapper vfsFile = dialog.save((VirtualFile) null, defaultName);
        if (vfsFile != null) {
            File file = vfsFile.getFile();
            // T-Perf-14：磁盘写入移后台；成功提示保持 EDT
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    Files.write(file.toPath(), bytes);
                } catch (Exception ex) {
                    com.intellij.openapi.diagnostic.Logger.getInstance(DiagramPanel.class)
                            .warn("diagram export write failed: " + ex.getMessage(), ex);
                }
            });
            if (!ApplicationManager.getApplication().isUnitTestMode()) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    try {
                        Notification notification = new Notification(
                                NOTIFICATION_GROUP_ID,
                                MpSqlVisualizerBundle.message("diagram.export.title"),
                                MpSqlVisualizerBundle.message("diagram.export.success", file.getAbsolutePath()),
                                NotificationType.INFORMATION);
                        notification.notify(proj);
                    } catch (Throwable ignored) {
                        // 通知组缺失等异常路径静默跳过（文件本身已写出）
                    }
                });
            }
        }
    }

    /**
     * T-UiNative T-UN-02：导出失败改 ERROR 轻通知（原模态错误框——失败仍醒目，但不打断）。
     * 测试模式跳过（对齐 CopyExecutableSqlAction.showToast 约定）。
     */
    private void showExportError(Project proj, String detail) {
        if (ApplicationManager.getApplication().isUnitTestMode()) {
            return;
        }
        try {
            Notification notification = new Notification(
                    NOTIFICATION_GROUP_ID,
                    MpSqlVisualizerBundle.message("diagram.export.title"),
                    MpSqlVisualizerBundle.message("diagram.export.failed", detail),
                    NotificationType.ERROR);
            notification.notify(proj);
        } catch (Throwable ignored) {
            // 通知组缺失等异常路径静默跳过
        }
    }

    @Override
    public void dispose() {
        currentModel = null;
        Disposer.dispose(this);
    }

    /** T-UiNative T-UN-02：导出成功/失败轻通知组（plugin.xml 已注册）。 */
    private static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";

    /**
     * 直接 Graphics2D 渲染画布。
     * preferredSize = 图表自然尺寸 × zoom → scroll pane 自动处理溢出。
     */
    private final class DiagramCanvas extends JComponent {
        @Nullable
        private DiagramModel model = null;

        void setModel(@Nullable DiagramModel m, double z) {
            model = m;
            if (m != null && !m.isEmpty()) {
                DiagramRenderer.Size pref = DiagramRenderer.measurePreferredSize(m);
                int w = Math.max((int) (pref.width() * z), 100);
                int h = Math.max((int) (pref.height() * z), 60);
                setPreferredSize(new Dimension(w, h));
            } else {
                setPreferredSize(new Dimension(200, 100));
            }
        }

        @Override
        public void paintComponent(Graphics g) {
            super.paintComponent(g);
            DiagramModel m = model;
            if (m == null) return;
            if (m.isEmpty()) return;

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

            // 背景
            g2.setColor(UIUtil.getPanelBackground());
            g2.fillRect(0, 0, getWidth(), getHeight());

            DiagramRenderer.Size pref = DiagramRenderer.measurePreferredSize(m);
            double scale = Math.min(Math.max(zoom, 0.2), 5.0);
            double offsetX = Math.max((getWidth() - pref.width() * scale) / 2, 0.0);
            double offsetY = Math.max((getHeight() - pref.height() * scale) / 2, 0.0);
            g2.translate(offsetX, offsetY);
            g2.scale(scale, scale);

            Font font = UIUtil.getLabelFont();
            Font bold = font.deriveFont(Font.BOLD);
            FontRenderContext frc = g2.getFontRenderContext();

            List<DiagramRenderer.Line> lines = DiagramRenderer.prepareLinesFor(m);
            // Canvas 与 PNG/SVG 使用相同的绘制顺序和关系线
            DiagramRenderer.drawOrthoLinesForCanvas(g2, m, lines, font, bold);
            DiagramRenderer.drawCards(g2, m, font, bold);
            DiagramRenderer.drawLabels(g2, m, lines, font, frc);

            g2.dispose();
        }
    }
}
