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
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseWheelListener
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
 * 直接 Graphics2D 渲染（无中间位图），HiDPI 自适应，任意缩放清晰。
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

    // Diagrams are rendered directly to this component
    private val diagramComponent = DiagramComponent()

    private val scrollPane = JBScrollPane(diagramComponent).apply {
        border = JBUI.Borders.empty()
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
        if (model == currentModel) { return }
        currentModel = model
        swapContent(scrollPane)
        diagramComponent.setModel(model)
        revalidate()
        repaint()
    }

    fun clear() {
        currentModel = null
        diagramComponent.setModel(null)
        showPlaceholder()
    }

    fun refreshAfterVisible() {
        if (currentModel != null && isShowing) repaint()
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

    fun zoomIn() { zoom = (zoom * 1.25).coerceAtMost(5.0); repaint() }
    fun zoomOut() { zoom = (zoom / 1.25).coerceAtLeast(0.2); repaint() }
    fun zoomReset() { zoom = 1.0; repaint() }
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
     * 自定义组件：直接 Graphics2D 渲染 ER 图，HiDPI 自适应。
     */
    private inner class DiagramComponent : JComponent() {
        private var model: DiagramModel? = null
        private var cachedImage: BufferedImage? = null
        private var cachedZoom = 0.0
        private var cachedW = 0
        private var cachedH = 0

        fun setModel(m: DiagramModel?) {
            model = m
            cachedImage = null
            if (m != null && !m.isEmpty) {
                val pref = DiagramRenderer.measurePreferredSize(m)
                preferredSize = java.awt.Dimension(pref.width, pref.height)
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

            val scaleX = g2.transform.scaleX
            val scaleY = g2.transform.scaleY
            val effZoom = zoom * scaleX

            // Render to cached image at device resolution if zoom or size changed
            val w = (width * scaleX).toInt().coerceAtLeast(1)
            val h = (height * scaleY).toInt().coerceAtLeast(1)
            if (cachedImage == null || cachedZoom != zoom || cachedW != w || cachedH != h) {
                cachedImage = DiagramRenderer.renderToDevice(m, w, h, zoom)
                cachedZoom = zoom
                cachedW = w
                cachedH = h
            }

            g2.drawImage(cachedImage, 0, 0, width, height, null)
            g2.dispose()
        }
    }
}
