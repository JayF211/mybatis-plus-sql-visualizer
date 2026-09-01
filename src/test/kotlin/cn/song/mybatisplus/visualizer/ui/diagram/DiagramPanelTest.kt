package cn.song.mybatisplus.visualizer.ui.diagram

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagramPanelTest {

    private val userTable = TableInfo("UserEntity", "user", listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("name", "name"),
        ))

    private val orderTable = TableInfo("OrderEntity", "order", listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("userId", "user_id"),
        ))

    private val joinDiagramModel: DiagramModel
        get() {
            val stmt = SelectStatement(userTable, emptyList(), "u", listOf(
                    JoinClause(JoinType.LEFT, "order", "o", listOf(
                            Condition.ColumnComparison(ColumnRef("u", "id"), ColumnRef("o", "user_id"), null),
                        )),
                ), false, emptyList(), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
            return cn.song.mybatisplus.visualizer.sql.JoinDiagramGenerator.buildDiagramModel(stmt)
        }

    @Test
    fun `zoomIn increases zoom factor`() {
        val panel = DiagramPanel()
        val initial = panel.zoomForTest()
        panel.zoomIn()
        assertEquals(true, panel.zoomForTest() > initial)
    }

    @Test
    fun `zoomOut decreases zoom factor`() {
        val panel = DiagramPanel()
        panel.zoomIn()
        val afterIn = panel.zoomForTest()
        panel.zoomOut()
        assertEquals(true, panel.zoomForTest() < afterIn)
    }

    @Test
    fun `zoomReset restores to 1`() {
        val panel = DiagramPanel()
        panel.zoomIn(); panel.zoomIn()
        panel.zoomReset()
        assertEquals(1.0, panel.zoomForTest(), 0.001)
    }

    @Test
    fun `render join model produces valid image`() {
        val image = DiagramRenderer.render(joinDiagramModel)
        assertNotNull(image)
        assertTrue(image.width > 0 && image.height > 0)
    }

    @Test
    fun `render empty model produces minimal image`() {
        val image = DiagramRenderer.render(DiagramModel(emptyList(), emptyList()))
        assertNotNull(image)
        assertEquals(1, image.width)
        assertEquals(1, image.height)
    }

    @Test
    fun `render to svg produces valid svg xml`() {
        val svg = DiagramRenderer.renderToSvg(joinDiagramModel)
        assertTrue(svg.contains("<svg"))
        assertTrue(svg.contains("user"))
        assertTrue(svg.contains("<rect"))
        assertTrue(svg.contains("<line"))
    }

    @Test
    fun `showDiagram with valid model does not throw`() {
        val panel = DiagramPanel()
        panel.showDiagram(joinDiagramModel)
    }

    @Test
    fun `showDiagram with empty model clears display`() {
        val panel = DiagramPanel()
        panel.showDiagram(joinDiagramModel)
        panel.showDiagram(DiagramModel(emptyList(), emptyList()))
    }

    @Test
    fun `clear resets state without exception`() {
        val panel = DiagramPanel()
        panel.showDiagram(joinDiagramModel)
        panel.clear()
        assertEquals(1.0, panel.zoomForTest(), 0.001)
    }
}
