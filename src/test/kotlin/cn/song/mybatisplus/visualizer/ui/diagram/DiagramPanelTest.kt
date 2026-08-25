package cn.song.mybatisplus.visualizer.ui.diagram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * F-1：DiagramPanel 单元测试（纯逻辑，无 UI 渲染验证）。
 *
 * 覆盖：
 *  - 缩放因子变更（放大/缩小/重置）
 *  - PlantUML 渲染（静态方法直接调用，验证 PNG 字节可解码为图片）
 *  - 空输入 → 显示占位（clear 后状态）
 */
class DiagramPanelTest {

    /** 最小可渲染的 PlantUML 源（单 entity，无 JOIN）。不含 !theme plain / linetype ortho（8059 不支持 / 依赖 Graphviz）。 */
    private val minimalPlantUml = """
        @startuml
        hide circle
        entity "user" as u {
          * id : Long
          name : String
        }
        @enduml
    """.trimIndent()

    /** 含 JOIN 关系的 PlantUML 源。 */
    private val joinPlantUml = """
        @startuml
        hide circle
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

    // ------------------------------------------------------------------
    // 缩放
    // ------------------------------------------------------------------

    @Test
    fun `zoomIn increases zoom factor`() {
        val panel = DiagramPanel()
        val initial = panel.zoomForTest()
        panel.zoomIn()
        val after = panel.zoomForTest()
        assertEquals("zoom should increase", true, after > initial)
    }

    @Test
    fun `zoomOut decreases zoom factor`() {
        val panel = DiagramPanel()
        panel.zoomIn()
        val afterIn = panel.zoomForTest()
        panel.zoomOut()
        val afterOut = panel.zoomForTest()
        assertEquals("zoom should decrease", true, afterOut < afterIn)
    }

    @Test
    fun `zoomReset restores to 1`() {
        val panel = DiagramPanel()
        panel.zoomIn()
        panel.zoomIn()
        panel.zoomReset()
        assertEquals(1.0, panel.zoomForTest(), 0.001)
    }

    // ------------------------------------------------------------------
    // PlantUML 渲染（静态方法）
    // ------------------------------------------------------------------

    @Test
    fun `render minimal plantuml produces valid png`() {
        val bytes = DiagramRenderTask.render(minimalPlantUml, net.sourceforge.plantuml.FileFormat.PNG)
        assertEquals("bytes should not be empty", true, bytes.isNotEmpty())
        val image = ImageIO.read(bytes.inputStream())
        assertEquals("image should decode", true, image != null)
        assertEquals("image width positive", true, image!!.width > 0)
    }

    @Test
    fun `render join plantuml produces valid png`() {
        val bytes = DiagramRenderTask.render(joinPlantUml, net.sourceforge.plantuml.FileFormat.PNG)
        assertEquals("bytes should not be empty", true, bytes.isNotEmpty())
        val image = ImageIO.read(bytes.inputStream())
        assertNotNull("image should decode", image)
    }

    @Test
    fun `render to svg produces non-empty output`() {
        val bytes = DiagramRenderTask.render(minimalPlantUml, net.sourceforge.plantuml.FileFormat.SVG)
        assertEquals("SVG bytes should not be empty", true, bytes.isNotEmpty())
        val svgText = String(bytes)
        assertEquals("SVG should contain svg tag", true, svgText.contains("<svg"))
    }

    @Test
    fun `render invalid plantuml produces error diagram`() {
        // PlantUML renders invalid syntax as an error image (does not throw)
        val bytes = DiagramRenderTask.render("@startuml\ninvalid syntax !!!\n@enduml", net.sourceforge.plantuml.FileFormat.PNG)
        assertEquals("bytes should not be empty", true, bytes.isNotEmpty())
    }

    // ------------------------------------------------------------------
    // 面板交互（无 project → 同步渲染路径）
    // ------------------------------------------------------------------

    @Test
    fun `showDiagram with valid input does not throw`() {
        val panel = DiagramPanel()
        // 无 project 时走同步渲染路径，应正常执行不抛异常
        panel.showDiagram(joinPlantUml)
    }

    @Test
    fun `showDiagram with empty input clears display`() {
        val panel = DiagramPanel()
        panel.showDiagram(joinPlantUml)
        panel.showDiagram("")  // 空输入 → 显示占位
        // 无异常即可
    }

    @Test
    fun `clear resets state without exception`() {
        val panel = DiagramPanel()
        panel.showDiagram(joinPlantUml)
        panel.clear()
        assertEquals(1.0, panel.zoomForTest(), 0.001)
    }
}
