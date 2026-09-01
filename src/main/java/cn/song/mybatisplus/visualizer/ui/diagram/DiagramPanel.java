package cn.song.mybatisplus.visualizer.ui.diagram;
import java.util.List;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
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
 */
public class DiagramPanel extends JBPanel<DiagramPanel> implements Disposable {

    private double zoom = 1.0;
    @Nullable
    private DiagramModel currentModel = null;

    private final JBLabel loadingLabel;
    private final JProgressBar loadingProgress;
    private final JComponent loadingPanel;
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

        loadingLabel = new JBLabel(
                MpSqlVisualizerBundle.message("diagram.render.loading"), SwingConstants.CENTER);
        loadingLabel.setForeground(JBColor.GRAY);
        loadingLabel.setFont(UIUtil.getLabelFont());

        loadingProgress = new JProgressBar();
        loadingProgress.setIndeterminate(true);
        loadingProgress.setMaximum(100);
        loadingProgress.setForeground(JBColor.GRAY);
        loadingProgress.setBackground(UIUtil.getPanelBackground());
        loadingProgress.setBorder(new javax.swing.border.EmptyBorder(0, 0, 0, 0));

        loadingPanel = new JPanel();
        loadingPanel.setBackground(UIUtil.getPanelBackground());
        loadingPanel.setLayout(new javax.swing.BoxLayout(loadingPanel, javax.swing.BoxLayout.Y_AXIS));
        loadingPanel.add(loadingLabel);
        loadingPanel.add(loadingProgress);
        loadingPanel.setBorder(JBUI.Borders.empty(40, 20, 20, 20));

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
        swapContent(placeholderLabel);
    }

    private JComponent buildToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        toolbar.setOpaque(false);
        toolbar.setBorder(JBUI.Borders.empty(2, 8, 2, 4));
        JButton zoomInBtn = new JButton(MpSqlVisualizerBundle.message("diagram.zoomIn"));
        zoomInBtn.addActionListener(e -> zoomIn());
        toolbar.add(zoomInBtn);
        JButton zoomOutBtn = new JButton(MpSqlVisualizerBundle.message("diagram.zoomOut"));
        zoomOutBtn.addActionListener(e -> zoomOut());
        toolbar.add(zoomOutBtn);
        JButton zoomResetBtn = new JButton(MpSqlVisualizerBundle.message("diagram.zoomReset"));
        zoomResetBtn.addActionListener(e -> zoomReset());
        toolbar.add(zoomResetBtn);
        toolbar.add(new JSeparator(SwingConstants.VERTICAL));
        JButton exportPngBtn = new JButton(MpSqlVisualizerBundle.message("diagram.exportPng"));
        exportPngBtn.addActionListener(e -> exportPng());
        toolbar.add(exportPngBtn);
        JButton exportSvgBtn = new JButton(MpSqlVisualizerBundle.message("diagram.exportSvg"));
        exportSvgBtn.addActionListener(e -> exportSvg());
        toolbar.add(exportSvgBtn);
        return toolbar;
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

    private void swapContent(JComponent component) {
        if (currentCenter == component) return;
        if (currentCenter != null) remove(currentCenter);
        add(component, BorderLayout.CENTER);
        currentCenter = component;
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
        try {
            BufferedImage image = DiagramRenderer.render(model);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            if (baos.size() == 0) throw new RuntimeException("PNG encode produced no output");
            saveBytesToFile(proj, baos.toByteArray(), "join_diagram.png", "png");
        } catch (Exception e) {
            Messages.showErrorDialog(proj,
                    MpSqlVisualizerBundle.message("diagram.export.failed", e.getMessage() != null ? e.getMessage() : "unknown"),
                    MpSqlVisualizerBundle.message("diagram.export.title"));
        }
    }

    private void exportSvg() {
        DiagramModel model = currentModel;
        if (model == null) return;
        Project proj = project;
        if (proj == null) return;
        try {
            String svg = DiagramRenderer.renderToSvg(model);
            saveBytesToFile(proj, svg.getBytes(StandardCharsets.UTF_8), "join_diagram.svg", "svg");
        } catch (Exception e) {
            Messages.showErrorDialog(proj,
                    MpSqlVisualizerBundle.message("diagram.export.failed", e.getMessage() != null ? e.getMessage() : "unknown"),
                    MpSqlVisualizerBundle.message("diagram.export.title"));
        }
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
            try {
                Files.write(file.toPath(), bytes);
            } catch (Exception ex) {
                Messages.showErrorDialog(proj,
                        MpSqlVisualizerBundle.message("diagram.export.failed", ex.getMessage() != null ? ex.getMessage() : "unknown"),
                        MpSqlVisualizerBundle.message("diagram.export.title"));
                return;
            }
            if (!ApplicationManager.getApplication().isUnitTestMode()) {
                ApplicationManager.getApplication().invokeLater(() ->
                        Messages.showInfoMessage(proj,
                                MpSqlVisualizerBundle.message("diagram.export.success", file.getAbsolutePath()),
                                MpSqlVisualizerBundle.message("diagram.export.title")));
            }
        }
    }

    @Override
    public void dispose() {
        currentModel = null;
        Disposer.dispose(this);
    }

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
