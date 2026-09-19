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
        "UserEntity",
        "user",
        listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("name", "name"),
        ),
    )

    private val orderTable = TableInfo(
        "OrderEntity",
        "order",
        listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("userId", "user_id"),
        ),
    )

    private val joinDiagramModel: DiagramModel
        get() {
            // 16 参 canonical 形态：master 靠默认参只写前 4 个（joins 在第 4 位），Java 无默认参
            // 且泛型擦除下不能为 joins/conditions 各出一版 4 参重载，故显式写全参
            val stmt = SelectStatement(
                userTable, emptyList(), "u",
                listOf(
                    JoinClause(
                        JoinType.LEFT, "order", "o",
                        listOf(
                            Condition.ColumnComparison(
                                ColumnRef("u", "id"),
                                ColumnRef("o", "user_id"),
                                null,
                            ),
                        ),
                    ),
                ),
                false, emptyList(), emptyList(), null, emptyList(), null,
                null, null, null, emptyList(), null, null,
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

    /**
     * T-F8：加载态状态机——翻转 + 渲染期间不摘转圈。
     *
     * 为什么「渲染期间不得提前摘」是本面板的契约：真正的耗时段在调用方（[SqlPreviewPanel] 的
     * 后台 schema 富化），面板只负责「进/出加载态」两件事；若 [showDiagram] 自己摘转圈，
     * 富化窗口内用户看不到任何反馈（这正是 F8 审计项里 T-UN-14 转圈从未出现的形态）。
     */
    @Test
    fun `加载态翻转且渲染期间不摘转圈`() {
        val panel = DiagramPanel()
        val seq = mutableListOf<Boolean>()
        panel.loadingProbeForTest = { seq += it }

        panel.showLoading()
        assertTrue("showLoading 后应处于加载态", panel.isLoadingForTest())

        panel.showDiagram(joinDiagramModel)
        assertTrue("渲染本身不得摘转圈——摘转圈是调用方的收尾动作", panel.isLoadingForTest())

        panel.hideLoading()
        assertFalse("hideLoading 后应退出加载态", panel.isLoadingForTest())
        assertEquals("加载态应恰好翻转一次进入 + 一次退出", listOf(true, false), seq)
    }

    /**
     * T-F8：`clear()` 复位加载态——加载期间无 JOIN / 面板清空（整图卸载）时必须摘转圈，
     * 否则转圈留在已卸载的空标签页上永不消失（用户只看到「一直在加载」）。
     */
    @Test
    fun `clear 复位加载态防整图卸载卡转圈`() {
        val panel = DiagramPanel()
        panel.showLoading()
        assertTrue("前置：应处于加载态", panel.isLoadingForTest())
        panel.clear()
        assertFalse("clear 必须复位加载态", panel.isLoadingForTest())
    }
}
