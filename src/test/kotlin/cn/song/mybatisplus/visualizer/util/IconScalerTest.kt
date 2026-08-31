package cn.song.mybatisplus.visualizer.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.image.BufferedImage
import javax.swing.ImageIcon

/**
 * 图标缩放纯函数测试（F-44-6 续 · 补用例）：
 * 零依赖纯 Swing 工具，此前 0 测试。覆盖比例缩放、非正方形、最小 1px 兜底、
 * 以及 paintIcon 不抛异常（返回的图标可用）。
 */
class IconScalerTest {

    private fun icon(w: Int, h: Int) = ImageIcon(BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB))

    @Test
    fun testScale_按比例缩放宽度高度() {
        val base = icon(16, 16)
        assertEquals("0.5 倍 → 8x8", 8, IconScaler.scale(base, 0.5f).iconWidth)
        assertEquals("0.5 倍 → 8x8", 8, IconScaler.scale(base, 0.5f).iconHeight)
        assertEquals("0.75 倍 → 12x12", 12, IconScaler.scale(base, 0.75f).iconWidth)
        assertEquals("1.0 倍 → 16x16", 16, IconScaler.scale(base, 1.0f).iconWidth)
        assertEquals("2.0 倍 → 32x32", 32, IconScaler.scale(base, 2.0f).iconWidth)
    }

    @Test
    fun testScale_最小一像素() {
        val tiny = icon(1, 1)
        val scaled = IconScaler.scale(tiny, 0.5f)
        assertEquals("1px 图标 0.5 倍应至少 1px（不塌为 0）", 1, scaled.iconWidth)
        assertEquals("1px 图标 0.5 倍应至少 1px", 1, scaled.iconHeight)
    }

    @Test
    fun testScale_非正方形按比例() {
        val wide = icon(32, 8)
        val scaled = IconScaler.scale(wide, 0.5f)
        assertEquals("宽 32 → 16", 16, scaled.iconWidth)
        assertEquals("高 8 → 4", 4, scaled.iconHeight)
    }

    @Test
    fun testScale_不抛异常且返回可用图标() {
        val base = icon(16, 16)
        val scaled = IconScaler.scale(base, 1.5f)
        // 返回图标应可被 paintIcon 渲染（不抛异常）
        val img = BufferedImage(scaled.iconWidth, scaled.iconHeight, BufferedImage.TYPE_INT_ARGB)
        scaled.paintIcon(null, img.createGraphics(), 0, 0)
        assertEquals("缩放后尺寸应正确", 24, scaled.iconWidth)
    }
}
