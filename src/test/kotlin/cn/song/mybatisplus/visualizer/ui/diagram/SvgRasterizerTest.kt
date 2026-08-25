package cn.song.mybatisplus.visualizer.ui.diagram

import net.sourceforge.plantuml.FileFormat
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.PNGTranscoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * F-42：SvgRasterizer 单元测试（Batik SVG → BufferedImage 栅格化）。
 *
 * 覆盖：
 *  - PlantUML SVG 渲染产物可栅格化（宽高 > 0，与 PNG 尺寸一致）
 *  - 内联最小 SVG 可栅格化
 *  - 无效输入抛异常（不静默返回空图）
 *  - Batik PNGTranscoder 输出真实 PNG 字节（文件头 89 50 4E 47，主 IDE 导出 PNG 用）
 */
class SvgRasterizerTest {

    /** 与 JoinDiagramGenerator 输出风格一致（不含 !theme plain，8059 不支持）。 */
    private val plantUmlSource = """
        @startuml
        hide circle
        skinparam linetype ortho
        entity "user (u)" as u #lightgreen {
          * id : Long
          name : String
        }
        entity "order (o)" as o #lightblue {
          * id : Long
          user_id : Long
        }
        u ||--o{ o : "u.id = o.user_id"
        @enduml
    """.trimIndent()

    private val minimalSvg = """
        <svg xmlns="http://www.w3.org/2000/svg" width="100" height="60">
          <rect x="10" y="10" width="80" height="40" rx="5" fill="#dfe7ff" stroke="#4a6ee0"/>
          <text x="50" y="35" font-size="12" text-anchor="middle" fill="#101010">user</text>
        </svg>
    """.trimIndent()

    @Test
    fun `rasterize plantuml svg produces image with correct dimensions`() {
        val svgBytes = DiagramRenderTask.render(plantUmlSource, FileFormat.SVG)
        val image = SvgRasterizer.rasterize(svgBytes)

        // Batik 按 SVG 内嵌 width/height 像素属性渲染（PlantUML 输出带像素尺寸），
        // 宽高与 PNG 同源渲染在 10% DPI 容差内一致（无需像素级相等）
        val pngBytes = DiagramRenderTask.render(plantUmlSource, FileFormat.PNG)
        val pngImage = javax.imageio.ImageIO.read(pngBytes.inputStream())!!
        val tolerance = pngImage.width * 0.1
        assertTrue("width within 10% of png", kotlin.math.abs(image.width - pngImage.width) <= tolerance)
        assertTrue("height within 10% of png", kotlin.math.abs(image.height - pngImage.height) <= tolerance)
    }

    @Test
    fun `rasterize inline minimal svg produces image`() {
        val image = SvgRasterizer.rasterize(minimalSvg.toByteArray(Charsets.UTF_8))
        assertEquals("width from svg attribute", 100, image.width)
        assertEquals("height from svg attribute", 60, image.height)
    }

    @Test
    fun `rasterize invalid bytes throws`() {
        assertThrows(Exception::class.java) {
            SvgRasterizer.rasterize("this is not svg".toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun `png transcoder produces real png bytes`() {
        val svgBytes = DiagramRenderTask.render(plantUmlSource, FileFormat.SVG)
        val transcoder = PNGTranscoder()
        val baos = ByteArrayOutputStream()
        transcoder.transcode(TranscoderInput(svgBytes.inputStream()), TranscoderOutput(baos))

        val bytes = baos.toByteArray()
        val magic = intArrayOf(0x89, 0x50, 0x4E, 0x47)
        for (i in magic.indices) {
            assertEquals("png magic byte $i", magic[i], bytes[i].toInt() and 0xFF)
        }
        assertTrue("png longer than svg", bytes.size > svgBytes.size)
    }
}