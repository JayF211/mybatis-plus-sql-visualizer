package cn.song.mybatisplus.visualizer.ui.diagram

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import net.sourceforge.plantuml.FileFormat
import net.sourceforge.plantuml.FileFormatOption
import net.sourceforge.plantuml.SourceStringReader
import java.io.ByteArrayOutputStream

/**
 * F-1：PlantUML 后台渲染任务。
 *
 * 将 PlantUML 源文本渲染为 PNG/SVG 字节，运行在后台线程（不阻塞 EDT）。
 * 渲染结果经 [onDone] 回调到 EDT 消费（更新 UI）。
 *
 * PlantUML `SourceStringReader` 为纯 Java 实现，无 native 依赖，线程安全（每次构造独立实例）。
 *
 * @param project      当前项目（进度条归属）
 * @param plantUmlText PlantUML 源文本
 * @param format       目标格式（PNG / SVG）
 * @param onDone       完成回调（EDT 线程）：成功传字节，失败传 null + 异常信息
 */
class DiagramRenderTask(
    project: Project?,
    private val plantUmlText: String,
    private val format: FileFormat = FileFormat.PNG,
    private val onDone: (bytes: ByteArray?, error: String?) -> Unit,
) : Task.Backgroundable(project, "Rendering JOIN diagram", true) {

    override fun run(indicator: ProgressIndicator) {
        indicator.text = "Generating PlantUML diagram..."
        // 注意：不调用 indicator.fraction —— 后台任务用 indeterminate 进度条
        // （BackgroundableProcessIndicator），setFraction 会打 WARN（主 IDE 日志实证），且无进度语义
        try {
            val bytes = if (format == FileFormat.PNG) {
                renderWithFallback(plantUmlText)
            } else {
                render(plantUmlText, format)
            }
            // F-42 关键修复：必须用 ModalityState.any() —— SQL 预览弹窗（SqlPreviewDialog）是
            // 模态弹窗（DialogWrapper(project, true)），默认 invokeLater 在模态状态下会延迟
            // 到弹窗关闭才执行回调，导致 UI 永远卡在"渲染中"（日志实证回调延迟 40 秒）
            ApplicationManager.getApplication().invokeLater(
                { onDone(bytes, null) },
                com.intellij.openapi.application.ModalityState.any(),
            )
        } catch (e: Throwable) {
            // 捕获 Throwable（含 Error 子类如 IllegalAccessError），确保 onDone 总被调用
            val msg = e.message ?: e.javaClass.simpleName
            ApplicationManager.getApplication().invokeLater(
                { onDone(null, msg) },
                com.intellij.openapi.application.ModalityState.any(),
            )
        }
    }

    companion object {
        /**
         * 将 PlantUML 源文本渲染为指定格式的字节。
         *
         * 独立静态方法，便于单测绕过 EDT 回调直接调用。
         *
         * @param text   PlantUML 源文本
         * @param format 目标格式
         * @return 渲染后的字节
         * @throws RuntimeException 渲染失败时抛出（含 PlantUML 错误信息）
         */
        @JvmStatic
        fun render(text: String, format: FileFormat = FileFormat.PNG): ByteArray {
            val reader = SourceStringReader(text)
            val option = FileFormatOption(format)
            val baos = ByteArrayOutputStream(64 * 1024)
            // generateImage 返回描述字符串（含错误信息），输出写入 baos
            reader.generateImage(baos, option)
            if (baos.size() == 0) {
                throw RuntimeException("PlantUML render produced no output")
            }
            val bytes = baos.toByteArray()
            // F-42 兜底：PlantUML 8059 的 SVG 输出依赖 Graphviz 时会渲染出
            // "Dot Executable: null / Cannot find Graphviz" 错误图（用户环境实证）——
            // 检测到错误图时抛异常，让上层显示错误信息而非错误图
            if (format == FileFormat.SVG) {
                val header = String(bytes, 0, minOf(200, bytes.size), Charsets.UTF_8)
                if (header.contains("Dot Executable") || header.contains("dot executable") ||
                    header.contains("Cannot find Graphviz") || header.contains("testdot")
                ) {
                    throw RuntimeException("PlantUML SVG render requires Graphviz (not available)")
                }
            }
            return bytes
        }

        /**
         * 安全渲染：PNG 优先，JDK 模块受限时自动降级 SVG。
         *
         * @param text PlantUML 源文本
         * @return 渲染字节；PNG 成功返回 PNG 字节，降级时返回 SVG 字节
         */
        @JvmStatic
        fun renderWithFallback(text: String): ByteArray {
            return try {
                render(text, FileFormat.PNG)
            } catch (e: java.lang.IllegalAccessError) {
                // F-1：PNG 模块访问受限 → 降级 SVG
                render(text, FileFormat.SVG)
            }
        }
    }
}
