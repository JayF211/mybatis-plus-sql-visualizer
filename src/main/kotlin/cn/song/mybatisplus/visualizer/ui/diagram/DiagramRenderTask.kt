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
        indicator.fraction = 0.0
        try {
            val bytes = render(plantUmlText, format)
            indicator.fraction = 1.0
            // 切回 EDT 交付结果
            ApplicationManager.getApplication().invokeLater { onDone(bytes, null) }
        } catch (e: Exception) {
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
    }
}
