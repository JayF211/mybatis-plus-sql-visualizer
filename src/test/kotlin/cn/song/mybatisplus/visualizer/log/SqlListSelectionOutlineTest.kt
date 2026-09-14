package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.border.Border
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder

/**
 * [SqlListSelectionOutline] 单测（T-CrudColor §1.2.1）。
 *
 * 锁定四条守卫：
 *  1. **insets 逐边相等**（防选中瞬间文本位移 `W` px）——按 W ∈ 1..4 参数化，
 *     与渲染器的两套 Border 构造完全一致；
 *  2. **W 夹紧**到 1..4（越界不抛，防 `EmptyBorder` 负 inset）；
 *  3. **四条边带绘制**（W>1 时角部填满、中间区域不涂色）；
 *  4. [SqlListSelectionOutline.setColor] 改色即时生效（不重建实例）。
 */
class SqlListSelectionOutlineTest {

    private val separator = Color(0xE4E6EA)
    private val transparent = Color(0, 0, 0, 0)
    private val outline = Color(0x3574F0)

    /** 复刻渲染器的未选中 Border：Matte(0,0,1,0) + Empty(5,8,5,8)。 */
    private fun normalBorder(): Border =
        CompoundBorder(MatteBorder(0, 0, 1, 0, separator), EmptyBorder(5, 8, 5, 8))

    /**
     * 复刻渲染器的选中 Border。
     * 返回**具体类型**而非 [Border]：双参 `getBorderInsets(Component, Insets)` 定义在
     * `AbstractBorder` 上、不在 `Border` 接口上，声明成接口就调不到复用入参的重载。
     */
    private fun selectedBorder(w: Int): SqlListSelectionOutline = SqlListSelectionOutline(
        w,
        outline,
        CompoundBorder(
            MatteBorder(0, 0, 1, 0, transparent),
            EmptyBorder(5 - w, 8 - w, 5 - w, 8 - w),
        ),
    )

    // ---------- 守卫 1：insets 逐边相等 ----------

    @Test
    fun `insets_四档描边宽度均与未选中态逐边相等`() {
        val expected = normalBorder().getBorderInsets(JPanel())
        assertEquals("未选中态基准 insets 应为上5 左8 下6 右8", 5, expected.top)
        assertEquals(8, expected.left)
        assertEquals(6, expected.bottom)
        assertEquals(8, expected.right)
        for (w in SqlListSelectionOutline.MIN_WIDTH..SqlListSelectionOutline.MAX_WIDTH) {
            val actual = selectedBorder(w).getBorderInsets(JPanel())
            assertEquals("W=$w 时 top 应相等", expected.top, actual.top)
            assertEquals("W=$w 时 left 应相等", expected.left, actual.left)
            assertEquals("W=$w 时 bottom 应相等", expected.bottom, actual.bottom)
            assertEquals("W=$w 时 right 应相等", expected.right, actual.right)
        }
    }

    @Test
    fun `insets_复写模式与新建模式结果一致`() {
        val b = selectedBorder(2)
        val into = java.awt.Insets(0, 0, 0, 0)
        val filled = b.getBorderInsets(JPanel(), into)
        assertEquals(b.getBorderInsets(JPanel()), filled)
    }

    // ---------- 守卫 2：宽度夹紧 ----------

    @Test
    fun `宽度_越界夹紧到安全区间`() {
        assertEquals(1, SqlListSelectionOutline(0, outline, EmptyBorder(0, 0, 0, 0)).width)
        assertEquals(1, SqlListSelectionOutline(-3, outline, EmptyBorder(0, 0, 0, 0)).width)
        assertEquals(4, SqlListSelectionOutline(5, outline, EmptyBorder(0, 0, 0, 0)).width)
        assertEquals(4, SqlListSelectionOutline(Int.MAX_VALUE, outline, EmptyBorder(0, 0, 0, 0)).width)
        assertEquals(2, SqlListSelectionOutline(2, outline, EmptyBorder(0, 0, 0, 0)).width)
    }

    @Test
    fun `宽度_上限内不产生负内边距`() {
        // 内层 EmptyBorder(5-W, 8-W, 5-W, 8-W) 在 W ≤ 4 时四个值均 ≥ 0
        for (w in 1..SqlListSelectionOutline.MAX_WIDTH) {
            EmptyBorder(5 - w, 8 - w, 5 - w, 8 - w) // 负参会抛 IllegalArgumentException
        }
    }

    // ---------- 守卫 3：边带绘制 ----------

    @Test
    fun `边带_W2时四边各2px且角部填满`() {
        val img = render(width = 2)
        assertRed("左上角", img, 0, 0)
        assertRed("右上角（上边带整宽）", img, 39, 0)
        assertRed("上边带第二行", img, 20, 1)
        assertRed("左下角", img, 0, 19)
        assertRed("右边带整高", img, 39, 10)
        assertRed("左边带中部", img, 1, 10)
        assertNotRed("中心区域不应涂色", img, 20, 10)
        assertNotRed("内缩 1px 后为中心区", img, 2, 2)
    }

    @Test
    fun `边带_W1时仅四周1px`() {
        val img = render(width = 1)
        assertRed("左上角", img, 0, 0)
        assertRed("右下角", img, 39, 19)
        assertNotRed("W=1 时第二行应为中心区", img, 20, 1)
    }

    @Test
    fun `边带_退化尺寸不抛`() {
        listOf(1 to 1, 0 to 10, 10 to 0, 3 to 3, 2 to 2).forEach { (w, h) ->
            val img = BufferedImage(w.coerceAtLeast(1), h.coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = Color.WHITE
            g.fillRect(0, 0, img.width, img.height)
            SqlListSelectionOutline(2, outline, EmptyBorder(0, 0, 0, 0))
                .paintBorder(JPanel(), g, 0, 0, w, h)
            g.dispose()
        }
    }

    // ---------- 守卫 4：改色即时生效 ----------

    @Test
    fun `描边色_改色即时生效且不重建`() {
        val b = SqlListSelectionOutline(2, outline, EmptyBorder(0, 0, 0, 0))
        assertEquals(outline, b.outlineColor)
        val another = Color(0x548AF7)
        b.setColor(another)
        assertEquals("改色应即时生效（可变字段路径）", another, b.outlineColor)
        assertEquals("改色不应触发重建", 2, b.width)
    }

    @Test
    fun `描边色_改色后重绘取新色`() {
        val b = SqlListSelectionOutline(2, outline, EmptyBorder(0, 0, 0, 0))
        b.setColor(Color(0x00FF00))
        val img = paint(b, 2)
        assertEquals("重绘应取新色", Color(0x00FF00), Color(img.getRGB(0, 0)))
    }

    @Test
    fun `描边_标记不透明`() {
        assertTrue("环带填满整个边框区，应标记不透明",
            SqlListSelectionOutline(2, outline, EmptyBorder(0, 0, 0, 0)).isBorderOpaque)
    }

    // ---------- 辅助 ----------

    private fun render(width: Int): BufferedImage =
        paint(SqlListSelectionOutline(width, outline, EmptyBorder(0, 0, 0, 0)), width)

    private fun paint(border: SqlListSelectionOutline, width: Int): BufferedImage {
        val img = BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)
        val g: Graphics2D = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 40, 20)
        border.paintBorder(JPanel(), g, 0, 0, 40, 20)
        g.dispose()
        return img
    }

    private fun assertRed(what: String, img: BufferedImage, x: Int, y: Int) =
        assertEquals("$what ($x,$y) 应为描边色", outline, Color(img.getRGB(x, y)))

    private fun assertNotRed(what: String, img: BufferedImage, x: Int, y: Int) =
        assertFalse("$what ($x,$y) 不应为描边色", outline == Color(img.getRGB(x, y)))
}
