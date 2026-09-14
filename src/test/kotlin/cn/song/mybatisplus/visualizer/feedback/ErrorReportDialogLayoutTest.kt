package cn.song.mybatisplus.visualizer.feedback

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JScrollPane

/**
 * 报告对话框布局与校验契约（T-FeedbackDlg）：buildCenterPanel/validateForm 纯函数 +
 * ReportFormFields 控件构造，headless 零 DialogWrapper peer 依赖。
 * 锁定三缺陷防回归：弹窗过宽（标题列数误用 TITLE_MAX）、占位提示缺失、不可拖拽（weighty=0）。
 */
class ErrorReportDialogLayoutTest {

    @Test
    fun `标题与描述空文本占位提示就位`() {
        val fields = ReportFormFields()
        assertEquals(
            MpSqlVisualizerBundle.message("error.report.dialog.subject.placeholder"),
            fields.titleField.emptyText.text,
        )
        assertEquals(
            MpSqlVisualizerBundle.message("error.report.dialog.description.placeholder"),
            fields.descriptionArea.emptyText.text,
        )
    }

    @Test
    fun `中心面板首选宽度收敛防回归`() {
        val panel = ErrorReportDialog.buildCenterPanel(ReportFormFields())
        assertTrue(
            "面板首选宽应≤800px（标题 200 列旧实现 >1200px 必炸），实际 ${panel.preferredSize.width}",
            panel.preferredSize.width <= 800,
        )
    }

    @Test
    fun `描述与堆栈行纵向随拖拽伸缩`() {
        val fields = ReportFormFields()
        val panel = ErrorReportDialog.buildCenterPanel(fields)
        val layout = panel.layout as GridBagLayout
        val stretchRows = panel.components.filter {
            val gbc = layout.getConstraints(it)
            gbc.weighty == 1.0 && gbc.fill == GridBagConstraints.BOTH
        }
        assertEquals("描述与堆栈两行应 weighty=1 + BOTH，实际 $stretchRows", 2, stretchRows.size)
        assertTrue("堆栈预览容器行应可伸缩", fields.stackPreviewContainer in stretchRows)
        val descScroll = stretchRows.filterIsInstance<JScrollPane>()
            .singleOrNull { it.viewport.view === fields.descriptionArea }
        assertNotNull("描述滚动面板行应可伸缩", descScroll)
    }

    @Test
    fun `校验_标题5到200边界`() {
        assertEquals(ErrorReportDialog.VALIDATION_TITLE_KEY, ErrorReportDialog.validateForm("abcd", "0123456789"))
        assertNull(ErrorReportDialog.validateForm("abcde", "0123456789"))
        assertNull(ErrorReportDialog.validateForm("  abcde  ", "0123456789"))
        assertNull(ErrorReportDialog.validateForm("a".repeat(200), "0123456789"))
        assertEquals(
            ErrorReportDialog.VALIDATION_TITLE_KEY,
            ErrorReportDialog.validateForm("a".repeat(201), "0123456789"),
        )
    }

    @Test
    fun `校验_描述10字下限`() {
        assertEquals(ErrorReportDialog.VALIDATION_DESCRIPTION_KEY, ErrorReportDialog.validateForm("abcde", "012345678"))
        assertEquals(ErrorReportDialog.VALIDATION_DESCRIPTION_KEY, ErrorReportDialog.validateForm("abcde", ""))
        assertNull(ErrorReportDialog.validateForm("abcde", "0123456789"))
        assertNull(ErrorReportDialog.validateForm("abcde", "  0123456789  "))
    }

    @Test
    fun `校验与占位文案键都有bundle落地`() {
        val keys = arrayOf(
            ErrorReportDialog.VALIDATION_TITLE_KEY,
            ErrorReportDialog.VALIDATION_DESCRIPTION_KEY,
            "error.report.dialog.subject.placeholder",
            "error.report.dialog.description.placeholder",
        )
        for (key in keys) {
            assertNotEquals("键 $key 缺 bundle 文案（会原样显示键名）", key, MpSqlVisualizerBundle.message(key))
        }
    }

    @Test
    fun `堆栈行显隐窗口补偿量_勾选撑高取消缩回`() {
        // 勾选：描述行损失 Δ=(slack+S)/2，撑高 2Δ=slack+S 让描述行分毫不差
        assertEquals(218, ErrorReportDialog.toggleResizeDelta(true, 700, 670, 188, 0))
        // 窗口无纵向富余（贴首选高）：只补堆栈行首选高
        assertEquals(188, ErrorReportDialog.toggleResizeDelta(true, 670, 670, 188, 0))
        // 取消：按堆栈行实占高（含富余分配）整块释放
        assertEquals(-280, ErrorReportDialog.toggleResizeDelta(false, 918, 670, -188, 280))
        // 取消且堆栈行未布局（实占高0）：退化为 preferred 缩减量的负值
        assertEquals(-188, ErrorReportDialog.toggleResizeDelta(false, 918, 670, -188, 0))
        // 取消但测不到任何释放量：不动窗口
        assertEquals(0, ErrorReportDialog.toggleResizeDelta(false, 670, 670, 0, 0))
    }
}
