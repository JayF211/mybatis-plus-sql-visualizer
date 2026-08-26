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
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingConstants

/**
 * F-1/F-xx：JOIN 关系图渲染面板。
 *
 * 职责：
 *  - 接收 [DiagramModel]，经 [DiagramRenderer] 渲染为 BufferedImage
 *  - 渲染成功后以缩放图显示（支持滚轮/按钮缩放）
 *  - 渲染失败时降级显示错误信息
 *  - 导出 PNG / SVG 到文件
 *
 * 缓存：同一模型不重复渲染（以模型 identity 为键缓存图像）。
 * 生命周期：注册到父组件 Disposer 树，释放时清空缓存。
 */
class DiagramPanel(private val project: Project? = null) : JBPanel<DiagramPanel>(BorderLayout()), Disposable {

    private var zoom = 1.0
    private var currentImage: BufferedImage? = null
    private var currentModel: DiagramModel? = null

    private val imageLabel = JLabel().apply {
        isOpaque = true
        background = UIUtil.getPanelBackground()
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
    }
    private val scrollPane = JBScrollPane(imageLabel).apply {
        border = JBUI.Borders.empty()
        addMouseWheelListener { e ->
            if (e.isControlDown) {
                if (e.wheelRotation < 0) zoomIn() else zoomOut()
                e.consume()
            }
        }
    }

    private val errorTextArea = javax.swing.JTextArea().apply {
        isEditable = false
        font = UIUtil.getLabelFont()
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(8)
        lineWrap = true
        wrapStyleWord = true
    }
    private val errorScroll = JBScrollPane(errorTextArea)

    private val placeholderLabel = JBLabel(
        MpSqlVisualizerBundle.message("diagram.placeholder.noJoin"), SwingConstants.CENTER,
    ).apply { foreground = JBColor.GRAY }

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
        if (model == currentModel && currentImage != null) { applyZoom(); return }
        currentModel = model
        swapContent(loadingPanel)
        try {
            currentImage = DiagramRenderer.render(model)
            currentModel = model
            applyZoom()
        } catch (e: Throwable) {
            showError(model, e.message ?: e.javaClass.simpleName)
        }
    }

    fun clear() {
        currentModel = null
        currentImage = null
        showPlaceholder()
    }

    fun refreshAfterVisible() {
        if (currentImage != null && isShowing) applyZoom()
    }

    private fun applyZoom() {
        val image = currentImage ?: return
        val scaled = if (zoom == 1.0) image else scaleImage(image, zoom)
        invalidate()
        swapContent(scrollPane)
        imageLabel.icon = ImageIcon(scaled)
        imageLabel.text = null
        var p: java.awt.Container? = this
        while (p != null) { p.invalidate(); p = p.parent }
        revalidate()
        repaint()
    }

    private fun scaleImage(src: BufferedImage, factor: Double): BufferedImage {
        val w = (src.width * factor).toInt().coerceAtLeast(1)
        val h = (src.height * factor).toInt().coerceAtLeast(1)
        val dst = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = dst.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return dst
    }

    private fun showError(model: DiagramModel, error: String?) {
        errorTextArea.text = buildString {
            append(MpSqlVisualizerBundle.message("diagram.render.failed", error ?: "unknown"))
            append("\n\nEntities: ${model.entities.size}, Relationships: ${model.relationships.size}")
        }
        swapContent(errorScroll)
        revalidate()
        repaint()
    }

    private fun showPlaceholder() {
        swapContent(placeholderLabel)
        revalidate()
        repaint()
    }

    private var currentCenter: JComponent? = null
    private fun swapContent(component: JComponent) {
        if (currentCenter === component) return
        currentCenter?.let { remove(it) }
        add(component, BorderLayout.CENTER)
        currentCenter = component
    }

    fun zoomIn() { zoom = (zoom * 1.25).coerceAtMost(5.0); applyZoom() }
    fun zoomOut() { zoom = (zoom / 1.25).coerceAtLeast(0.2); applyZoom() }
    fun zoomReset() { zoom = 1.0; applyZoom() }
    fun zoomForTest(): Double = zoom

    private fun exportPng() {
        val image = currentImage ?: return
        val proj = project ?: return
        try {
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
        currentImage = null
        Disposer.dispose(this)
    }
}
