package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SQL 预览弹窗默认尺寸策略测试（M14-04）：按屏幕比例自适应（mock 屏幕尺寸）。
 */
class SqlPreviewDialogSizeTest {

    @Test
    fun `大屏_按62pct与65pct比例`() {
        // 1920×1080 → 宽 1190、高 702（均在下限与 85% 上限之间）
        val size = SqlPreviewDialog.initialSize(1920, 1080)
        assertEquals(1190, size.width)
        assertEquals(702, size.height)
    }

    @Test
    fun `小屏_上限优先不超屏`() {
        // 1024×768：宽比例 634、85% 上限 870（<900 下限）→ 取上限 870；高 499 → 下限 600（600≤652）
        val size = SqlPreviewDialog.initialSize(1024, 768)
        assertEquals(870, size.width)
        assertEquals(600, size.height)
        assert(size.width <= 870) { "小屏默认宽度不应超过屏幕 85%（870）" }
        assert(size.height <= 652) { "小屏默认高度不应超过屏幕 85%（652）" }
    }

    @Test
    fun `无屏幕信息_兜底默认尺寸`() {
        val size = SqlPreviewDialog.initialSize(0, 0)
        assertEquals(900, size.width)
        assertEquals(600, size.height)
    }
}
