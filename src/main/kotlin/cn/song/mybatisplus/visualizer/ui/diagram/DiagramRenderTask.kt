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
            ApplicationManager.getApplication().invokeLater { onDone(bytes, null) }
        } catch (e: Throwable) {
            // 捕获 Throwable（含 Error 子类如 IllegalAccessError），确保 onDone 总被调用
            val msg = e.message ?: e.javaClass.simpleName
            ApplicationManager.getApplication().invokeLater { onDone(null, msg) }
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
            return baos.toByteArray()
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
