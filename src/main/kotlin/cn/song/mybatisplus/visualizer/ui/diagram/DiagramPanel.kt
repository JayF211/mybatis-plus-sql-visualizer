package cn.song.mybatisplus.visualizer.ui.diagram

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingConstants

/**
 * F-1/F-xx：JOIN 关系图渲染面板。
 *
 * 渲染方案：paintComponent 内直接 Graphics2D 变换绘制，无中间位图。
 * 组件尺寸 = 图表自然尺寸 × zoom，scroll pane 处理溢出。
 */
class DiagramPanel(private val project: Project? = null) : JBPanel<DiagramPanel>(BorderLayout()), Disposable {

    private var zoom = 1.0
    private var currentModel: DiagramModel? = null

    private val loadingLabel = JBLabel(
        MpSqlVisualizerBundle.message("diagram.render.loading"), SwingConstants.CENTER,
    ).apply {
        foreground = JBColor.GRAY
        font = UIUtil.getLabelFont()
    }
    private val loadingProgress = JProgressBar().apply {
        isIndeterminate = true
        maximum = 100
        foreground = JBColor.GRAY
        background = UIUtil.getPanelBackground()
        border = javax.swing.border.EmptyBorder(0, 0, 0, 0)
    }
    private val loadingPanel: JComponent = JPanel().apply {
        background = UIUtil.getPanelBackground()
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        add(loadingLabel)
        add(loadingProgress)
        border = JBUI.Borders.empty(40, 20, 20, 20)
    }

    private val placeholderLabel = JBLabel(
        MpSqlVisualizerBundle.message("diagram.placeholder.noJoin"), SwingConstants.CENTER,
    ).apply { foreground = JBColor.GRAY }

    private val errorTextArea = javax.swing.JTextArea().apply {
        isEditable = false
        font = UIUtil.getLabelFont()
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(8)
        lineWrap = true
        wrapStyleWord = true
    }
    private val errorScroll = JBScrollPane(errorTextArea)

    private val diagramCanvas = DiagramCanvas()

    private val scrollPane = JBScrollPane(diagramCanvas).apply {
        border = JBUI.Borders.empty()
        viewport.background = UIUtil.getPanelBackground()
        addMouseWheelListener { e ->
            if (e.isControlDown()) {
                if (e.wheelRotation < 0) zoomIn() else zoomOut()
                e.consume()
            }
        }
    }

    private var currentCenter: JComponent? = null

    init {
        add(buildToolbar(), BorderLayout.NORTH)
        swapContent(placeholderLabel)
    }

    private fun buildToolbar(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        toolbar.isOpaque = false
        toolbar.border = JBUI.Borders.empty(2, 8, 2, 4)
        toolbar.add(JButton(MpSqlVisualizerBundle.message("diagram.zoomIn")).apply { addActionListener { zoomIn() } })
        toolbar.add(JButton(MpSqlVisualizerBundle.message("diagram.zoomOut")).apply { addActionListener { zoomOut() } })
        toolbar.add(JButton(MpSqlVisualizerBundle.message("diagram.zoomReset")).apply { addActionListener { zoomReset() } })
        toolbar.add(javax.swing.JSeparator(javax.swing.SwingConstants.VERTICAL))
        toolbar.add(JButton(MpSqlVisualizerBundle.message("diagram.exportPng")).apply { addActionListener { exportPng() } })
        toolbar.add(JButton(MpSqlVisualizerBundle.message("diagram.exportSvg")).apply { addActionListener { exportSvg() } })
        return toolbar
    }

    fun showDiagram(model: DiagramModel) {
        if (model.isEmpty) { showPlaceholder(); return }
        currentModel = model
        swapContent(scrollPane)
        diagramCanvas.setModel(model, zoom)
        revalidate()
        repaint()
    }

    fun clear() {
        currentModel = null
        diagramCanvas.setModel(null, zoom)
        showPlaceholder()
    }

    fun refreshAfterVisible() {
        if (currentModel != null && isShowing) {
            diagramCanvas.revalidate()
            repaint()
        }
    }

    private fun showPlaceholder() {
        swapContent(placeholderLabel)
        revalidate()
        repaint()
    }

    private fun swapContent(component: JComponent) {
        if (currentCenter === component) return
        currentCenter?.let { remove(it) }
        add(component, BorderLayout.CENTER)
        currentCenter = component
    }

    fun zoomIn() { zoom = (zoom * 1.25).coerceAtMost(5.0); diagramCanvas.setModel(currentModel, zoom); revalidate(); repaint() }
    fun zoomOut() { zoom = (zoom / 1.25).coerceAtLeast(0.2); diagramCanvas.setModel(currentModel, zoom); revalidate(); repaint() }
    fun zoomReset() { zoom = 1.0; diagramCanvas.setModel(currentModel, zoom); revalidate(); repaint() }
    fun zoomForTest(): Double = zoom

    private fun exportPng() {
        val model = currentModel ?: return
        val proj = project ?: return
        try {
            val image = DiagramRenderer.render(model)
            val baos = ByteArrayOutputStream()
            ImageIO.write(image, "png", baos)
            if (baos.size() == 0) throw RuntimeException("PNG encode produced no output")
            saveBytesToFile(proj, baos.toByteArray(), "join_diagram.png", "png")
        } catch (e: Exception) {
            Messages.showErrorDialog(proj,
                MpSqlVisualizerBundle.message("diagram.export.failed", e.message ?: "unknown"),
                MpSqlVisualizerBundle.message("diagram.export.title"))
        }
    }

    private fun exportSvg() {
        val model = currentModel ?: return
        val proj = project ?: return
        try {
            val svg = DiagramRenderer.renderToSvg(model)
            saveBytesToFile(proj, svg.toByteArray(Charsets.UTF_8), "join_diagram.svg", "svg")
        } catch (e: Exception) {
            Messages.showErrorDialog(proj,
                MpSqlVisualizerBundle.message("diagram.export.failed", e.message ?: "unknown"),
                MpSqlVisualizerBundle.message("diagram.export.title"))
        }
    }

    private fun saveBytesToFile(proj: Project, bytes: ByteArray, defaultName: String, ext: String) {
        val descriptor = FileSaverDescriptor(
            MpSqlVisualizerBundle.message("diagram.export.title"),
            MpSqlVisualizerBundle.message("diagram.export.desc"), ext)
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, proj)
        val vfsFile: VirtualFileWrapper? = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, defaultName)
        if (vfsFile != null) {
            val file: File = vfsFile.file
            file.writeBytes(bytes)
            if (!ApplicationManager.getApplication().isUnitTestMode) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage(proj,
                        MpSqlVisualizerBundle.message("diagram.export.success", file.absolutePath),
                        MpSqlVisualizerBundle.message("diagram.export.title"))
                }
            }
        }
    }

    override fun dispose() {
        currentModel = null
        Disposer.dispose(this)
    }

    /**
     * 直接 Graphics2D 渲染画布。
     * preferredSize = 图表自然尺寸 × zoom → scroll pane 自动处理溢出。
     */
    private inner class DiagramCanvas : JComponent() {
        private var model: DiagramModel? = null

        fun setModel(m: DiagramModel?, z: Double) {
            model = m
            if (m != null && !m.isEmpty) {
                val pref = DiagramRenderer.measurePreferredSize(m)
                val w = (pref.width * z).toInt().coerceAtLeast(100)
                val h = (pref.height * z).toInt().coerceAtLeast(60)
                preferredSize = java.awt.Dimension(w, h)
            } else {
                preferredSize = java.awt.Dimension(200, 100)
            }
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val m = model ?: return
            if (m.isEmpty) return

            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)

            // 背景
            g2.color = UIUtil.getPanelBackground()
            g2.fillRect(0, 0, width, height)

            val pref = DiagramRenderer.measurePreferredSize(m)
            val scaleX = width.toDouble() / pref.width
            val scaleY = height.toDouble() / pref.height
            val baseScale = minOf(scaleX, scaleY)
            val offsetX = ((width - pref.width * baseScale) / 2).coerceAtLeast(0.0)
            val offsetY = ((height - pref.height * baseScale) / 2).coerceAtLeast(0.0)
            g2.translate(offsetX, offsetY)
            g2.scale(baseScale, baseScale)

            val font = UIUtil.getLabelFont()
            val bold = font.deriveFont(Font.BOLD)
            val frc = g2.fontRenderContext

            val lines = DiagramRenderer.prepareLinesFor(m)
            DiagramRenderer.drawCards(g2, m, font, bold)
            DiagramRenderer.drawLabels(g2, m, lines, font, frc)

            g2.dispose()
        }
    }
}
