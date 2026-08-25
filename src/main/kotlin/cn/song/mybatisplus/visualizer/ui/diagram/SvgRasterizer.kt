package cn.song.mybatisplus.visualizer.ui.diagram

import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.ImageTranscoder
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream

/**
 * F-42：SVG → BufferedImage 栅格化（Apache Batik）。
 *
 * 背景：主 IDE 没有 `--add-opens java.desktop/com.sun.imageio.plugins.png`，PlantUML PNG 渲染
 * 必抛 IllegalAccessError（jar 反编译实证 PngIO 经 ImageIO 加载 com.sun.imageio.plugins.png.PNGMetadata），
 * 只能降级 SVG；而 SVG 不能经 ImageIO.read 解码，更不能用 JavaFX（IDEA 运行时无该类）。
 * Batik 把 SVG 渲染为 BufferedImage（纯 Java，全平台一致），复用 DiagramPanel 现有 ImageIcon 路径。
 *
 * 依赖：batik-transcoder 1.17（打包进插件 lib/）。构造函数抛 TranscoderException（受检），
 * 统一转换为 RuntimeException 便于调用方无受检异常地消费。
 */
object SvgRasterizer {

    /**
     * 将 SVG 字节栅格化为图像。
     *
     * @param svgBytes SVG 内容字节（以 `<?xml` 或 `<svg` 开头）
     * @return 栅格化后的图像；SVG 无有效尺寸或解析失败时抛出 [RuntimeException]
     */
    fun rasterize(svgBytes: ByteArray): BufferedImage {
        val rendered = arrayOfNulls<BufferedImage>(1)
        val transcoder = object : ImageTranscoder() {
            override fun createImage(width: Int, height: Int): BufferedImage =
                BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

            override fun writeImage(image: BufferedImage, output: TranscoderOutput) {
                rendered[0] = image
            }
        }
        transcoder.transcode(TranscoderInput(ByteArrayInputStream(svgBytes)), TranscoderOutput())
        return rendered[0] ?: throw RuntimeException("SVG rasterize produced no image")
    }
}