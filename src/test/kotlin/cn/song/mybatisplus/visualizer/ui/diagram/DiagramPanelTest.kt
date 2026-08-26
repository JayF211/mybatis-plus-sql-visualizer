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

    private val userTable = TableInfo(
        entityClassName = "UserEntity",
        tableName = "user",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("name", "name"),
        ),
    )

    private val orderTable = TableInfo(
        entityClassName = "OrderEntity",
        tableName = "order",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("userId", "user_id"),
        ),
    )

    private val joinDiagramModel: DiagramModel
        get() {
            val stmt = SelectStatement(
                table = userTable, tableAlias = "u",
                joins = listOf(
                    JoinClause(
                        type = JoinType.LEFT, tableName = "order", alias = "o",
                        onConditions = listOf(
                            Condition.ColumnComparison(
                                left = ColumnRef(tableAlias = "u", columnName = "id"),
                                right = ColumnRef(tableAlias = "o", columnName = "user_id"),
                            ),
                        ),
                    ),
                ),
            )
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
