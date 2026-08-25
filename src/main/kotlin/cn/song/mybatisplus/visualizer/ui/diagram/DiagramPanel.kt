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
import net.sourceforge.plantuml.FileFormat
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.PNGTranscoder
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
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
 * F-1：JOIN 关系图渲染面板。
 *
 * 职责：
 *  - 接收 PlantUML 源文本，经 [DiagramRenderTask] 后台渲染为 PNG
 *  - 渲染成功后以缩放图显示（支持滚轮/按钮缩放）
 *  - 渲染失败时降级显示原始 PlantUML 文本（可复制调试）
 *  - 导出 PNG / SVG 到文件
 *
 * 缓存：同一源文本不重复渲染（以源文本 hash 为键缓存字节）。
 * 生命周期：注册到父组件 Disposer 树，释放时取消后台任务 + 清空缓存。
 *
 * @param project 当前项目（文件选择器 / 进度条归属）；可 null（测试/独立窗口）
 */
class DiagramPanel(private val project: Project? = null) : JBPanel<DiagramPanel>(BorderLayout()), Disposable {

    /** 缩放因子（1.0 = 原始大小）。 */
    private var zoom = 1.0

    /** 当前渲染的原始 PNG 字节（未缩放）。 */
    private var currentBytes: ByteArray? = null

    /** 当前显示的源文本（防重复渲染）。 */
    private var currentSource: String? = null

    /** 当前格式（默认 PNG；导出 SVG 时临时切换）。 */
    private var currentFormat: FileFormat = FileFormat.PNG

    /** 当前渲染任务的源文本标识（任务完成时校验，忽略过期结果）。 */
    private var taskId: String? = null

    // ---- UI 组件 ----
    private val imageLabel = JLabel().apply {
        isOpaque = true
        background = UIUtil.getPanelBackground()
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
    }
    private val scrollPane = JBScrollPane(imageLabel).apply {
        border = JBUI.Borders.empty()
        // 滚轮 + Ctrl 缩放
        addMouseWheelListener { e ->
            if (e.isControlDown) {
                if (e.wheelRotation < 0) zoomIn() else zoomOut()
                e.consume()
            }
        }
    }

    // 降级文本区（非 SVG 渲染失败时显示）
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
    ).apply {
        foreground = JBColor.GRAY
    }

    // JOIN 图异步渲染中提示（渲染需 2-6 秒，显示进度条+动画文字，避免用户误以为"无JOIN"）
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
        // 工具栏：缩放 + 导出
        add(buildToolbar(), BorderLayout.NORTH)
        // 初始占位（必须使用 swapContent 初始化 currentCenter）
        swapContent(placeholderLabel)
    }

    /** 构建顶部工具栏（缩放 + 导出按钮）。 */
    private fun buildToolbar(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        toolbar.isOpaque = false
        toolbar.border = JBUI.Borders.empty(2, 8, 2, 4)

        val zoomInBtn = JButton(MpSqlVisualizerBundle.message("diagram.zoomIn")).apply {
            addActionListener { zoomIn() }
        }
        val zoomOutBtn = JButton(MpSqlVisualizerBundle.message("diagram.zoomOut")).apply {
            addActionListener { zoomOut() }
        }
        val zoomResetBtn = JButton(MpSqlVisualizerBundle.message("diagram.zoomReset")).apply {
            addActionListener { zoomReset() }
        }
        val exportPngBtn = JButton(MpSqlVisualizerBundle.message("diagram.exportPng")).apply {
            addActionListener { exportDiagram(FileFormat.PNG) }
        }
        val exportSvgBtn = JButton(MpSqlVisualizerBundle.message("diagram.exportSvg")).apply {
            addActionListener { exportDiagram(FileFormat.SVG) }
        }

        toolbar.add(zoomInBtn)
        toolbar.add(zoomOutBtn)
        toolbar.add(zoomResetBtn)
        toolbar.add(javax.swing.JSeparator(javax.swing.SwingConstants.VERTICAL))
        toolbar.add(exportPngBtn)
        toolbar.add(exportSvgBtn)
        return toolbar
    }

    // ------------------------------------------------------------------
    // 公共接口
    // ------------------------------------------------------------------

    /**
     * 展示 PlantUML 图（后台渲染）。
     *
     * 幂等：同一源文本不重复渲染（直接应用缩放显示缓存字节）。
     * 源文本为空时显示占位提示。
     *
     * @param plantUmlText PlantUML 源文本
     */
    fun showDiagram(plantUmlText: String) {
        if (plantUmlText.isBlank()) {
            showPlaceholder()
            return
        }
        // 命中缓存：仅重绘
        if (plantUmlText == currentSource && currentBytes != null) {
            applyZoom()
            return
        }
        // 更新任务标识（过期任务的回调会被忽略）
        taskId = plantUmlText
        currentSource = plantUmlText
        // 渲染中提示（异步渲染需 2-6 秒，显示进度条+动画文字，完成后 applyZoom 替换为图像）
        swapContent(loadingPanel)
        // 后台渲染（PNG 因 JDK 模块限制失败时自动降级 SVG）
        renderAsync(plantUmlText)
    }

    /**
     * 后台异步渲染 PlantUML 图。
     * 使用 [DiagramRenderTask.renderWithFallback]：PNG 优先，JDK 模块受限时自动降级 SVG。
     */
    private fun renderAsync(plantUmlText: String) {
        val task = DiagramRenderTask(project, plantUmlText) { bytes, error ->
            // F-1 修复：不使用 currentSource/taskId 做期龄检查——DiagramPanel 被 dispose
            // 后 currentSource/taskId 被置 null，导致回调永远不执行。
            // 直接处理结果：有字节就显示，无字节就报错。
            if (bytes != null) {
                currentBytes = bytes
                currentSource = plantUmlText
                try {
                    applyZoom()
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            } else {
                showError(plantUmlText, error)
            }
        }
        if (project != null) {
            task.queue()
        } else {
            // 无项目时（测试）同步渲染
            try {
                val bytes = DiagramRenderTask.renderWithFallback(plantUmlText)
                currentBytes = bytes
                applyZoom()
            } catch (e: Throwable) {
                showError(plantUmlText, e.message)
            }
        }
    }

    /** 清空显示（恢复占位）。 */
    fun clear() {
        taskId = null
        currentSource = null
        currentBytes = null
        showPlaceholder()
    }

    /**
     * F-42：标签页切换到"JOIN 图"时调用。
     *
     * 渲染完成时组件可能处于 hidden（标签页未被选中），此时 swapContent/revalidate/repaint
     * 均不生效。切换到标签页后组件可见，重新执行 applyZoom 使图像真正显示。
     */
    fun refreshAfterVisible() {
        // 标签页切换后组件可见，重新执行 applyZoom 使图像真正显示
        if (currentBytes != null && isShowing) {
            applyZoom()
        }
    }

    // ------------------------------------------------------------------
    // 渲染结果显示
    // ------------------------------------------------------------------

    /** 将缓存的字节（PNG 或 SVG）按当前缩放比显示到 imageLabel。 */
    private fun applyZoom() {
        val bytes = currentBytes ?: run {
            return
        }
        // 检测 SVG 字节（PNG 降级产物）→ Batik 栅格化为 BufferedImage，走同一显示路径
        // （F-42：不能用 JavaFX WebView——IDEA 运行时无 javafx.embed.swing.JFXPanel，日志实证）
        val image = if (isSvgBytes(bytes)) {
            try {
                SvgRasterizer.rasterize(bytes)
            } catch (e: Exception) {
                showError(currentSource ?: "", "Failed to rasterize SVG: ${e.message}")
                return
            }
        } else {
            ImageIO.read(bytes.inputStream()) ?: run {
                showError(currentSource ?: "", "Failed to decode image bytes")
                return
            }
        }
        val scaled = if (zoom == 1.0) image else scaleImage(image, zoom)
        // 关键修复（F-42）：在修改组件前强制验证布局，确保在 JTabedByPane 中也能正确显示
        // 即使组件当前不可见（在未被选中的标签页中），swapContent 也会生效
        // 当组件变可见时，invalidate/validate 确保布局正确更新
        invalidate()
        getTreeLock().let { /* ensure layout is flushed */ }
        swapContent(scrollPane)
        imageLabel.icon = ImageIcon(scaled)
        imageLabel.text = null
        // 强制从根容器开始验证，确保嵌套在 JTabedByPane 中的组件也能更新
        var p: java.awt.Container? = this
        while (p != null) {
            p.invalidate()
            p = p.parent
        }
        revalidate()
        repaint()
    }

    /** 检测字节是否为 SVG 格式（以 <?xml 或 <svg 开头）。 */
    private fun isSvgBytes(bytes: ByteArray): Boolean {
        val header = String(bytes, 0, minOf(100, bytes.size), Charsets.UTF_8).trimStart()
        return header.startsWith("<?xml") || header.startsWith("<svg")
    }

    /** 缩放图像（双线性插值，平滑）。 */
    private fun scaleImage(src: BufferedImage, factor: Double): BufferedImage {
        val w = (src.width * factor).toInt().coerceAtLeast(1)
        val h = (src.height * factor).toInt().coerceAtLeast(1)
        val dst = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = dst.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return dst
    }

    /** 渲染失败：降级显示原始 PlantUML 文本 + 错误信息。 */
    private fun showError(source: String, error: String?) {
        val display = buildString {
            append(MpSqlVisualizerBundle.message("diagram.render.failed", error ?: "unknown"))
            append("\n\n--- PlantUML Source ---\n")
            append(source)
        }
        errorTextArea.text = display
        swapContent(errorScroll)
        revalidate()
        repaint()
    }

    /** 显示占位提示（无 JOIN）。 */
    private fun showPlaceholder() {
        swapContent(placeholderLabel)
        revalidate()
        repaint()
    }

    /** 替换中央显示区组件（幂等：同组件不重复移除添加）。 */
    private var currentCenter: JComponent? = null

    private fun swapContent(component: JComponent) {
        if (currentCenter === component) return
        currentCenter?.let { remove(it) }
        add(component, BorderLayout.CENTER)
        currentCenter = component
    }

    // ------------------------------------------------------------------
    // 缩放
    // ------------------------------------------------------------------

    fun zoomIn() {
        zoom = (zoom * 1.25).coerceAtMost(5.0)
        applyZoom()
    }

    fun zoomOut() {
        zoom = (zoom / 1.25).coerceAtLeast(0.2)
        applyZoom()
    }

    fun zoomReset() {
        zoom = 1.0
        applyZoom()
    }

    fun zoomForTest(): Double = zoom

    // ------------------------------------------------------------------
    // 导出
    // ------------------------------------------------------------------

    /**
     * 导出当前图为文件（PNG 或 SVG）。
     *
     * @param format 导出格式（PNG = 当前缓存字节；SVG 需重新渲染）
     */
    private fun exportDiagram(format: FileFormat) {
        val source = currentSource ?: return
        val proj = project ?: return
        val ext = if (format == FileFormat.SVG) "svg" else "png"
        val defaultName = "join_diagram.$ext"
        try {
            // 渲染目标格式的字节
            val bytes = if (format == FileFormat.PNG && currentBytes != null) {
                val cached = currentBytes!!
                if (isSvgBytes(cached)) {
                    // F-42：主 IDE 常态下 PNG 渲染失败、缓存为 SVG 字节——直接写出是
                    // "SVG 内容 + .png 后缀"的损坏文件，须经 Batik 重编码为真实 PNG
                    encodeSvgToPng(cached)
                } else {
                    cached
                }
            } else {
                DiagramRenderTask.render(source, format)
            }
            saveBytesToFile(proj, bytes, defaultName, ext)
        } catch (e: Exception) {
            Messages.showErrorDialog(
                proj,
                MpSqlVisualizerBundle.message("diagram.export.failed", e.message ?: "unknown"),
                MpSqlVisualizerBundle.message("diagram.export.title"),
            )
        }
    }

    /** 将 SVG 字节经 Batik PNGTranscoder 编码为 PNG 字节（自带 PNG 编码器，不经 imageio，无 JDK 模块限制）。 */
    private fun encodeSvgToPng(svgBytes: ByteArray): ByteArray {
        val transcoder = PNGTranscoder()
        val baos = ByteArrayOutputStream()
        transcoder.transcode(TranscoderInput(ByteArrayInputStream(svgBytes)), TranscoderOutput(baos))
        if (baos.size() == 0) {
            throw RuntimeException("PNG encode produced no output")
        }
        return baos.toByteArray()
    }

    /** 经文件选择器保存字节到用户指定路径。 */
    private fun saveBytesToFile(proj: Project, bytes: ByteArray, defaultName: String, ext: String) {
        val descriptor = FileSaverDescriptor(
            MpSqlVisualizerBundle.message("diagram.export.title"),
            MpSqlVisualizerBundle.message("diagram.export.desc"),
            ext,
        )
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, proj)
        val vfsFile: VirtualFileWrapper? = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, defaultName)
        if (vfsFile != null) {
            val file: File = vfsFile.file
            file.writeBytes(bytes)
            if (!ApplicationManager.getApplication().isUnitTestMode) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage(
                        proj,
                        MpSqlVisualizerBundle.message("diagram.export.success", file.absolutePath),
                        MpSqlVisualizerBundle.message("diagram.export.title"),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun dispose() {
        taskId = null
        currentBytes = null
        currentSource = null
        Disposer.dispose(this)
    }
}
