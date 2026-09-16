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
     */
    private final JBLoadingPanel contentLayer;
    private final JBLabel placeholderLabel;
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

    public void showDiagram(DiagramModel model) {
        if (model.isEmpty()) {
            showPlaceholder();
            return;
        }
        currentModel = model;
        swapContent(scrollPane);
        diagramCanvas.setModel(model, zoom);
        revalidate();
        repaint();
    }

    public void clear() {
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
        contentLayer.setLoadingText(MpSqlVisualizerBundle.message("diagram.render.loading"));
        contentLayer.startLoading();
        revalidate();
        repaint();
    }

    /** T-UiNative T-UN-14：退出加载态（渲染/换页完成后调用）。 */
    public void hideLoading() {
        contentLayer.stopLoading();
        revalidate();
        repaint();
    }

    /** T-UiNative T-UN-14：加载态（测试断言用）。 */
    public boolean isLoadingForTest() {
        return contentLayer.isLoading();
    }

    public void zoomIn() {
        zoom = Math.min(zoom * 1.25, 5.0);
        diagramCanvas.setModel(currentModel, zoom);
        revalidate();
        repaint();
    }

    public void zoomOut() {
        zoom = Math.max(zoom / 1.25, 0.2);
        diagramCanvas.setModel(currentModel, zoom);
        revalidate();
        repaint();
    }

    public void zoomReset() {
        zoom = 1.0;
        diagramCanvas.setModel(currentModel, zoom);
        revalidate();
        repaint();
    }

    public double zoomForTest() {
        return zoom;
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
