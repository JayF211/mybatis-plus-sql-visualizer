package cn.song.mybatisplus.visualizer.ui.diagram

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `showError 接线错误页并显示异常摘要`() {
        val panel = DiagramPanel()
        val detail = "IllegalStateException: measure boom"
        panel.showError(detail)
        assertTrue("showError 后内容层应换到错误页（原 errorScroll 全仓零引用，从未接过线）", panel.isErrorPageForTest())
        assertEquals(
            "错误页文本应为 diagram.render.failed 的格式化结果",
            MpSqlVisualizerBundle.message("diagram.render.failed", detail),
            panel.errorTextForTest(),
        )
    }

    @Test
    fun `渲染抛异常时落错误页且不向调用方穿透`() {
        val panel = DiagramPanel()
        panel.showDiagram(joinDiagramModel)
        assertFalse("正常渲染不应进错误页", panel.isErrorPageForTest())
        // 真实测量/布局对结构合法的模型不抛，用注入的失败动作复现错误路径（T-RA-02）
        panel.renderGuarded(joinDiagramModel) { throw IllegalStateException("measure boom") }
        assertTrue("渲染失败应换到错误页（原实现异常穿透到弹窗构造，用户看不到原因）", panel.isErrorPageForTest())
        val text = panel.errorTextForTest()
        assertTrue("错误页应含异常类名，实际=$text", text.contains("IllegalStateException"))
        assertTrue("错误页应含异常摘要，实际=$text", text.contains("measure boom"))
        // 失败后回到「无图」语义：再次正常渲染应恢复画布，不被坏模型卡死
        panel.showDiagram(joinDiagramModel)
        assertFalse("失败后再次渲染应恢复画布", panel.isErrorPageForTest())
    }
}
