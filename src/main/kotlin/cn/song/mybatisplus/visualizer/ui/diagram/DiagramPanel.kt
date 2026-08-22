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
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
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

    // 降级文本区（渲染失败时显示）
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

    init {
        // 工具栏：缩放 + 导出
        add(buildToolbar(), BorderLayout.NORTH)
        // 初始占位
        add(placeholderLabel, BorderLayout.CENTER)
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
        // 后台渲染
        val task = DiagramRenderTask(project, plantUmlText, FileFormat.PNG) { bytes, error ->
            // 忽略过期任务的结果
            if (taskId != plantUmlText) return@DiagramRenderTask
            if (bytes != null) {
                currentBytes = bytes
                applyZoom()
            } else {
                showError(plantUmlText, error)
            }
        }
        if (project != null) {
            task.queue()
        } else {
            // 无项目时（测试）同步渲染
            try {
                val bytes = DiagramRenderTask.render(plantUmlText, FileFormat.PNG)
                currentBytes = bytes
                applyZoom()
            } catch (e: Exception) {
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

    // ------------------------------------------------------------------
    // 渲染结果显示
    // ------------------------------------------------------------------

    /** 将缓存的 PNG 字节按当前缩放比显示到 imageLabel。 */
    private fun applyZoom() {
        val bytes = currentBytes ?: return
        val image = ImageIO.read(bytes.inputStream()) ?: run {
            showError(currentSource ?: "", "Failed to decode image bytes")
            return
        }
        val scaled = if (zoom == 1.0) image else scaleImage(image, zoom)
        swapContent(scrollPane)
        imageLabel.icon = ImageIcon(scaled)
        imageLabel.text = null
        revalidate()
        repaint()
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
                currentBytes!!
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
