package cn.song.mybatisplus.visualizer.ui.diagram

import cn.song.mybatisplus.visualizer.model.JoinType
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.sqrt

/**
 * F-xx：JOIN 关系图 Swing 自绘引擎（IDEA 原生 ER 图风格 v2）。
 *
 * 渲染质量：2x DPI 超采样 → 缩放至目标尺寸，消除文字/线条锯齿。
 * 卡片样式：白底 + 细边框 + 投影，列间分隔线，行交替底色（类 IDEA Database 图表）。
 * 关系线：深灰 2px 粗线 + 三角箭头，cardinality 标签在线两端，ON 条件为中点悬浮标签。
 *
 * 线程安全：纯函数式，无状态。
 */
object DiagramRenderer {

    // ---- 2x DPI 渲染：内部坐标 = 显示坐标 × SCALE ----
    private const val SCALE = 2

    private const val ENTITY_PADDING = 12
    private const val HEADER_HEIGHT = 28
    private const val ROW_HEIGHT = 20
    private const val COLUMN_GAP = 80
    private const val ROW_GAP = 48
    private const val MARGIN = 48
    private const val CARD_ARC = 8
    private const val ARROW_LEN = 10
    private const val ARROW_HW = 5
    private const val MAX_CARD_WIDTH = 280
    private const val MIN_CARD_WIDTH = 160
    private const val MAX_TEXT_LEN = 60
    private const val TYPE_GAP = 20
    private const val KEY_ICON_W = 16
    private const val SEPARATOR_PAD = 8

    // ---- Colors (light / dark) ----
    private val SHADOW = JBColor(Color(0x00, 0x00, 0x00, 28), Color(0x00, 0x00, 0x00, 50))
    private val CARD_BG = JBColor(Color(0xFF, 0xFF, 0xFF), Color(0x2D, 0x2F, 0x33))
    private val CARD_BG_ALT = JBColor(Color(0xF5, 0xF6, 0xF8), Color(0x28, 0x2A, 0x2E))
    private val CARD_BORDER = JBColor(Color(0xC0, 0xC4, 0xCC), Color(0x50, 0x53, 0x5A))
    private val MAIN_HDR_BG = JBColor(Color(0xDC, 0xE8, 0xDC), Color(0x2E, 0x3B, 0x2E))
    private val JOIN_HDR_BG = JBColor(Color(0xDC, 0xE5, 0xED), Color(0x2A, 0x30, 0x38))
    private val HEADER_LINE = JBColor(Color(0xB0, 0xB4, 0xBC), Color(0x4A, 0x4E, 0x56))
    private val SEPARATOR = JBColor(Color(0xE0, 0xE2, 0xE6), Color(0x3A, 0x3D, 0x42))
    private val TEXT = JBColor(Color(0x1A, 0x1A, 0x1A), Color(0xBB, 0xBB, 0xBB))
    private val TYPE_COLOR = JBColor(Color(0x88, 0x88, 0x88), Color(0x80, 0x80, 0x80))
    private val PK_COLOR = JBColor(Color(0x9A, 0x68, 0x00), Color(0xE2, 0xC0, 0x79))
    private val LINE_COLOR = JBColor(Color(0x55, 0x55, 0x55), Color(0x77, 0x77, 0x77))
    private val LABEL_BG = JBColor(Color(0xFF, 0xFF, 0xFF), Color(0x33, 0x36, 0x3B))
    private val LABEL_BORDER = JBColor(Color(0xBB, 0xBB, 0xBB), Color(0x50, 0x53, 0x5A))

    private fun s(v: Int) = v * SCALE
    private fun s(v: Double) = (v * SCALE).toInt()

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    fun render(model: DiagramModel): BufferedImage {
        if (model.entities.isEmpty()) return BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)

        val displayFont = UIUtil.getLabelFont()
        val font = displayFont.deriveFont(displayFont.size * SCALE.toFloat())
        val bold = font.deriveFont(Font.BOLD)
        val frc = FontRenderContext(null, true, true)

        // Dynamic gap calculation
        var minGap = s(COLUMN_GAP)
        for (r in model.relationships) {
            var needed = s(COLUMN_GAP)
            if (r.label.isNotEmpty()) {
                val text = if (r.label.length > 120) r.label.take(119) + "\u2026" else r.label
                val tw = font.getStringBounds(text, frc).width.toInt() + s(40)
                if (tw > needed) needed = tw
            }
            needed = (needed + s(120)).coerceAtMost(s(520))
            if (needed > minGap) minGap = needed
        }

        val layouts = measureEntities(model.entities, font, bold, frc)
        val positions = computeLayout(layouts, minGap)
        val pad = s(MARGIN) + s(16)
        val totalW = positions.zip(layouts).maxOf { (p, l) -> p.x + l.width } + pad
        val totalH = positions.zip(layouts).maxOf { (p, l) -> p.y + l.height } + pad

        // Render at 2x resolution
        val img = BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)

        g2.color = UIUtil.getPanelBackground()
        g2.fillRect(0, 0, totalW, totalH)

        val lines = prepareLines(model, positions, layouts)

        // Draw cards
        for ((i, e) in model.entities.withIndex()) {
            drawCard(g2, e, positions[i], layouts[i], font, bold)
        }

        // Draw labels (computes gaps, draws lines under, badges on top)
        drawLabels(g2, lines, font, frc)

        g2.dispose()

        // Scale down to display size for sharp rendering
        val displayW = totalW / SCALE
        val displayH = totalH / SCALE
        val scaled = BufferedImage(displayW, displayH, BufferedImage.TYPE_INT_ARGB)
        val gScaled = scaled.createGraphics()
        gScaled.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        gScaled.drawImage(img, 0, 0, displayW, displayH, null)
        gScaled.dispose()
        return scaled
    }

    fun renderToSvg(model: DiagramModel): String {
        if (model.entities.isEmpty()) return """<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1"></svg>"""

        val font = UIUtil.getLabelFont()
        val bold = font.deriveFont(Font.BOLD)
        val frc = FontRenderContext(null, true, true)

        var minGap = COLUMN_GAP
        for (r in model.relationships) {
            var needed = COLUMN_GAP
            if (r.label.isNotEmpty()) {
                val text = if (r.label.length > 120) r.label.take(119) + "\u2026" else r.label
                val tw = font.getStringBounds(text, frc).width.toInt() + 40
                if (tw > needed) needed = tw
            }
            needed = (needed + 120).coerceAtMost(520)
            if (needed > minGap) minGap = needed
        }

        val layouts = measureEntities(model.entities, font, bold, frc)
        val positions = computeLayout(layouts, minGap)
        val pad = MARGIN + 16
        val totalW = positions.zip(layouts).maxOf { (p, l) -> p.x + l.width } + pad
        val totalH = positions.zip(layouts).maxOf { (p, l) -> p.y + l.height } + pad
        val width = max(totalW, MIN_CARD_WIDTH + 2 * MARGIN)
        val height = max(totalH, HEADER_HEIGHT + 2 * MARGIN)

        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height">""")
        sb.append("\n")

        val lines = prepareLines(model, positions, layouts)
        appendLinesSvg(sb, lines)
        for ((i, e) in model.entities.withIndex()) {
            appendCardSvg(sb, e, positions[i], layouts[i])
        }
        appendLabelsSvg(sb, lines)
        sb.append("</svg>\n")
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // Measurement
    // ------------------------------------------------------------------

    private data class Layout(
        val width: Int, val height: Int,
        val header: String,
        val names: List<String>, val types: List<String>, val pks: List<Boolean>,
    )

    private fun measureEntities(ents: List<EntityNode>, font: Font, bold: Font, frc: FontRenderContext): List<Layout> {
        return ents.map { e ->
            val hdr = truncate(e.displayName)
            val hdrW = bold.getStringBounds(hdr, frc).width.toInt() + 2 * s(ENTITY_PADDING)
            val names = e.columns.map { truncate(it.name) }
            val types = e.columns.map { truncate(it.type ?: "") }
            val pks = e.columns.map { it.isPk }
            val maxNameW = names.maxOfOrNull { font.getStringBounds(it, frc).width.toInt() } ?: 0
            val maxTypeW = types.maxOfOrNull { font.getStringBounds(it, frc).width.toInt() } ?: 0
            val contentW = max(hdrW, s(ENTITY_PADDING) + s(KEY_ICON_W) + maxNameW + s(TYPE_GAP) + maxTypeW + s(ENTITY_PADDING))
            val cardW = contentW.coerceIn(s(MIN_CARD_WIDTH), s(MAX_CARD_WIDTH))
            val cardH = s(HEADER_HEIGHT) + e.columns.size * s(ROW_HEIGHT) + s(ENTITY_PADDING)
            Layout(cardW, cardH, hdr, names, types, pks)
        }
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private data class Pos(val x: Int, val y: Int)

    private fun computeLayout(layouts: List<Layout>, minGap: Int): List<Pos> {
        if (layouts.isEmpty()) return emptyList()
        val gap = maxOf(minGap, s(COLUMN_GAP))
        val ps = mutableListOf(Pos(s(MARGIN), s(MARGIN)))
        val jx = s(MARGIN) + layouts[0].width + gap
        var jy = s(MARGIN)
        for (i in 1 until layouts.size) {
            ps.add(Pos(jx, jy))
            jy += layouts[i].height + s(ROW_GAP)
        }
        return ps
    }

    // ------------------------------------------------------------------
    // Line data
    // ------------------------------------------------------------------

    private data class Line(
        val x1: Int, val y1: Int, val x2: Int, val y2: Int,
        val jt: JoinType, val label: String,
    )

    private fun prepareLines(m: DiagramModel, ps: List<Pos>, lo: List<Layout>): List<Line> {
        val byIdx = m.entities.withIndex().associate { (i, e) -> e.alias to i }
        return m.relationships.mapNotNull { r ->
            val fi = byIdx[r.fromAlias] ?: return@mapNotNull null
            val ti = byIdx[r.toAlias] ?: return@mapNotNull null
            Line(
                x1 = ps[fi].x + lo[fi].width,
                y1 = ps[fi].y + lo[fi].height / 2,
                x2 = ps[ti].x,
                y2 = ps[ti].y + lo[ti].height / 2,
                jt = r.joinType,
                label = r.label,
            )
        }
    }

    // ------------------------------------------------------------------
    // Layer 1: Lines (drawn UNDER labels)
    // ------------------------------------------------------------------

    private fun drawLines(g2: Graphics2D, lines: List<Line>, gaps: List<java.awt.geom.Rectangle2D.Double>) {
        g2.color = LINE_COLOR
        g2.stroke = BasicStroke(2.0f * SCALE)
        for (i in lines.indices) {
            val l = lines[i]
            val gap = gaps[i]
            val gx1 = (gap.x + s(8)).toInt()
            val gx2 = (gap.x + gap.width - s(8)).toInt()

            if (gap.width > s(30)) {
                g2.drawLine(l.x1, l.y1, gx1, l.y1)
                g2.drawLine(gx2, l.y2, l.x2, l.y2)
            } else {
                g2.drawLine(l.x1, l.y1, l.x2, l.y2)
            }
            drawArrow(g2, l.x2, l.y2, l.x1, l.y1)
        }
    }

    private fun drawArrow(g2: Graphics2D, tipX: Int, tipY: Int, fromX: Int, fromY: Int) {
        val dx = (tipX - fromX).toDouble()
        val dy = (tipY - fromY).toDouble()
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1) return
        val ux = dx / len; val uy = dy / len
        val vx = -uy; val vy = ux
        val p = Path2D.Double()
        p.moveTo(tipX.toDouble(), tipY.toDouble())
        p.lineTo(tipX - ux * s(ARROW_LEN) + vx * s(ARROW_HW), tipY - uy * s(ARROW_LEN) + vy * s(ARROW_HW))
        p.lineTo(tipX - ux * s(ARROW_LEN) - vx * s(ARROW_HW), tipY - uy * s(ARROW_LEN) - vy * s(ARROW_HW))
        p.closePath()
        g2.fill(p)
    }

    // ------------------------------------------------------------------
    // Layer 2: Cards (IDEA Database 图表风格)
    // ------------------------------------------------------------------

    private fun drawCard(
        g2: Graphics2D, e: EntityNode, pos: Pos, lo: Layout,
        font: Font, bold: Font,
    ) {
        val ix = pos.x
        val iy = pos.y
        val iw = lo.width
        val ih = lo.height

        // Shadow
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.06f)
        g2.color = Color.BLACK
        g2.fillRoundRect(ix + s(3), iy + s(4), iw, ih, s(CARD_ARC), s(CARD_ARC))
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f)

        // Card body with alternating row backgrounds
        val colAreaY = iy + s(HEADER_HEIGHT)
        val colAreaH = ih - s(HEADER_HEIGHT)

        // Alternating row backgrounds
        for (i in lo.names.indices) {
            val rowY = colAreaY + i * s(ROW_HEIGHT)
            if (i % 2 == 1) {
                g2.color = CARD_BG_ALT
                g2.fillRect(ix, rowY, iw, s(ROW_HEIGHT))
            }
        }

        // Card background
        g2.color = CARD_BG
        g2.fillRoundRect(ix, iy, iw, ih, s(CARD_ARC), s(CARD_ARC))

        // Header
        val hdrColor = if (e.isMain) MAIN_HDR_BG else JOIN_HDR_BG
        g2.color = hdrColor
        g2.fillRoundRect(ix, iy, iw, s(HEADER_HEIGHT), s(CARD_ARC), s(CARD_ARC))
        g2.fillRect(ix, iy + s(HEADER_HEIGHT) - s(CARD_ARC), iw, s(CARD_ARC))

        // Header bottom separator
        g2.color = HEADER_LINE
        g2.stroke = BasicStroke(1.0f * SCALE)
        g2.drawLine(ix + s(SEPARATOR_PAD), iy + s(HEADER_HEIGHT), ix + iw - s(SEPARATOR_PAD), iy + s(HEADER_HEIGHT))

        // Column separators
        g2.color = SEPARATOR
        var sepY = colAreaY
        for (i in 0 until lo.names.size - 1) {
            sepY += s(ROW_HEIGHT)
            g2.drawLine(ix + s(SEPARATOR_PAD), sepY, ix + iw - s(SEPARATOR_PAD), sepY)
        }

        // Card border
        g2.color = CARD_BORDER
        g2.stroke = BasicStroke(1.0f * SCALE)
        g2.drawRoundRect(ix, iy, iw, ih, s(CARD_ARC), s(CARD_ARC))

        // Title text
        g2.font = bold
        g2.color = TEXT
        g2.drawString(lo.header, ix + s(ENTITY_PADDING), iy + s(HEADER_HEIGHT) - s(8))

        // Columns
        g2.font = font
        var cy = colAreaY + s(ENTITY_PADDING) + s(ROW_HEIGHT) - s(5)
        for (i in lo.names.indices) {
            val name = lo.names[i]
            val type = lo.types.getOrElse(i) { "" }
            val isPk = lo.pks.getOrElse(i) { false }

            if (isPk) {
                g2.color = PK_COLOR
                drawKey(g2, ix + s(ENTITY_PADDING), cy - s(9), s(11), s(11))
            }

            g2.color = TEXT
            g2.drawString(name, ix + s(ENTITY_PADDING) + (if (isPk) s(KEY_ICON_W) else 0), cy)

            if (type.isNotEmpty()) {
                g2.color = TYPE_COLOR
                val tw = font.getStringBounds(type, g2.fontRenderContext).width.toInt()
                g2.drawString(type, ix + iw - s(ENTITY_PADDING) - tw, cy)
            }
            cy += s(ROW_HEIGHT)
        }
    }

    private fun drawKey(g2: Graphics2D, x: Int, y: Int, w: Int, h: Int) {
        g2.stroke = BasicStroke(1.2f * SCALE)
        val r = w / 3
        g2.drawOval(x, y, r * 2, r * 2)
        g2.drawLine(x + r * 2, y + r, x + w, y + r)
        g2.drawLine(x + w - s(3), y + r, x + w - s(3), y + h - s(2))
    }

    // ------------------------------------------------------------------
    // Layer 3: Labels (always on top)
    // ------------------------------------------------------------------

    private fun drawLabels(g2: Graphics2D, lines: List<Line>, font: Font, frc: FontRenderContext) {
        val gaps = mutableListOf<java.awt.geom.Rectangle2D.Double>()

        for (l in lines) {
            var minX = Double.MAX_VALUE
            var maxX = Double.MIN_VALUE
            var minY = Double.MAX_VALUE
            var maxY = Double.MIN_VALUE

            // ON condition label: centered
            if (l.label.isNotEmpty()) {
                val rawText = if (l.label.length > 120) l.label.take(119) + "\u2026" else l.label
                val maxBadgeW = s(400)
                val fullW = font.getStringBounds(rawText, frc).width
                val bw = (fullW + s(20)).toInt().coerceIn(s(60), maxBadgeW)
                val bh = (font.getStringBounds(rawText, frc).height + s(12)).toInt()
                val bx = ((l.x1 + l.x2) / 2 - bw / 2)
                val by = ((l.y1 + l.y2) / 2 - bh / 2)
                onLabel(g2, rawText, bx, by, bw, bh, font)
                minX = minOf(minX, bx.toDouble())
                maxX = maxOf(maxX, (bx + bw).toDouble())
                minY = minOf(minY, by.toDouble())
                maxY = maxOf(maxY, (by + bh).toDouble())
            }

            // Cardinality labels: offset from card edges, vertically above/below line
            val fromText = cardText(l.jt, false)
            val toText = cardText(l.jt, true)
            val fromW = font.getStringBounds(fromText, frc).width.toInt() + s(16)
            val toW = font.getStringBounds(toText, frc).width.toInt() + s(16)
            val cbh = s(20)

            val fromX = l.x1 + s(30)
            val toX = l.x2 - s(30) - toW
            val fromY = l.y1 - cbh - s(3)
            val toY = l.y2 + s(3)
            badge(g2, fromText, fromX, fromY, fromW, cbh, font)
            badge(g2, toText, toX, toY, toW, cbh, font)

            minX = minOf(minX, fromX.toDouble(), toX.toDouble())
            maxX = maxOf(maxX, (fromX + fromW).toDouble(), (toX + toW).toDouble())
            minY = minOf(minY, fromY.toDouble(), toY.toDouble())
            maxY = maxOf(maxY, (fromY + cbh).toDouble(), (toY + cbh).toDouble())

            gaps.add(java.awt.geom.Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY))
        }

        // Draw lines UNDER labels
        drawLines(g2, lines, gaps)
    }

    private fun badge(g2: Graphics2D, text: String, x: Int, y: Int, w: Int, h: Int, font: Font) {
        g2.color = LABEL_BG
        g2.fillRoundRect(x, y, w, h, s(5), s(5))
        g2.color = LABEL_BORDER
        g2.stroke = BasicStroke(1.0f * SCALE)
        g2.drawRoundRect(x, y, w, h, s(5), s(5))
        g2.color = LINE_COLOR
        g2.font = font
        val ascent = font.getLineMetrics(text, g2.fontRenderContext).ascent.toInt()
        g2.drawString(text, x + s(8), y + s(3) + ascent)
    }

    private fun onLabel(g2: Graphics2D, text: String, bx: Int, by: Int, bw: Int, bh: Int, font: Font) {
        g2.color = LABEL_BG
        g2.fillRoundRect(bx, by, bw, bh, s(6), s(6))
        g2.color = LABEL_BORDER
        g2.stroke = BasicStroke(1.0f * SCALE)
        g2.drawRoundRect(bx, by, bw, bh, s(6), s(6))
        g2.color = TEXT
        g2.font = font
        val ascent = font.getLineMetrics(text, g2.fontRenderContext).ascent.toInt()
        g2.drawString(text, bx + s(10), by + s(4) + ascent)
    }

    // ------------------------------------------------------------------
    // SVG output
    // ------------------------------------------------------------------

    private fun appendLinesSvg(sb: StringBuilder, lines: List<Line>) {
        val lc = hexOf(LINE_COLOR)
        for (l in lines) {
            sb.append("""  <line x1="${l.x1}" y1="${l.y1}" x2="${l.x2}" y2="${l.y2}" stroke="$lc" stroke-width="2.0"/>""")
            sb.append("\n")
            val dx = (l.x2 - l.x1).toDouble()
            val dy = (l.y2 - l.y1).toDouble()
            val len = sqrt(dx * dx + dy * dy)
            if (len >= 1) {
                val ux = dx / len; val uy = dy / len
                val vx = -uy; val vy = ux
                val p1x = l.x2 - ux * ARROW_LEN + vx * ARROW_HW
                val p1y = l.y2 - uy * ARROW_LEN + vy * ARROW_HW
                val p2x = l.x2 - ux * ARROW_LEN - vx * ARROW_HW
                val p2y = l.y2 - uy * ARROW_LEN - vy * ARROW_HW
                sb.append("""  <polygon points="${l.x2},${l.y2} $p1x,$p1y $p2x,$p2y" fill="$lc"/>""")
                sb.append("\n")
            }
        }
    }

    private fun appendCardSvg(sb: StringBuilder, e: EntityNode, pos: Pos, lo: Layout) {
        val ix = pos.x
        val iy = pos.y
        val iw = lo.width
        val ih = lo.height

        val sh = hexOf(SHADOW)
        val bg = hexOf(CARD_BG)
        val hdr = hexOf(if (e.isMain) MAIN_HDR_BG else JOIN_HDR_BG)
        val bd = hexOf(CARD_BORDER)
        val hl = hexOf(HEADER_LINE)
        val sep = hexOf(SEPARATOR)
        val tx = hexOf(TEXT)
        val ty = hexOf(TYPE_COLOR)
        val pk = hexOf(PK_COLOR)

        val colAreaY = iy + HEADER_HEIGHT
        val colAreaH = ih - HEADER_HEIGHT

        // Shadow
        sb.append("""  <rect x="${ix + 3}" y="${iy + 4}" width="$iw" height="$ih" rx="$CARD_ARC" fill="$sh"/>""")
        sb.append("\n")
        // Body
        sb.append("""  <rect x="$ix" y="$iy" width="$iw" height="$ih" rx="$CARD_ARC" fill="$bg" stroke="$bd" stroke-width="1.0"/>""")
        sb.append("\n")
        // Header
        sb.append("""  <rect x="$ix" y="$iy" width="$iw" height="$HEADER_HEIGHT" rx="$CARD_ARC" fill="$hdr"/>""")
        sb.append("\n")
        sb.append("""  <rect x="$ix" y="${iy + HEADER_HEIGHT - CARD_ARC}" width="$iw" height="$CARD_ARC" fill="$hdr"/>""")
        sb.append("\n")
        // Header separator
        sb.append("""  <line x1="${ix + SEPARATOR_PAD}" y1="${iy + HEADER_HEIGHT}" x2="${iw - SEPARATOR_PAD + ix}" y2="${iy + HEADER_HEIGHT}" stroke="$hl" stroke-width="1.0"/>""")
        sb.append("\n")
        // Column separators
        var sepY = colAreaY
        for (i in 0 until lo.names.size - 1) {
            sepY += ROW_HEIGHT
            sb.append("""  <line x1="${ix + SEPARATOR_PAD}" y1="$sepY" x2="${ix + iw - SEPARATOR_PAD}" y2="$sepY" stroke="$sep" stroke-width="0.8"/>""")
            sb.append("\n")
        }
        // Title
        sb.append("""  <text x="${ix + ENTITY_PADDING}" y="${iy + HEADER_HEIGHT - 8}" font-weight="bold" font-size="12" fill="$tx">${escapeXml(lo.header)}</text>""")
        sb.append("\n")
        // Columns
        var cy = colAreaY + ENTITY_PADDING + ROW_HEIGHT - 5
        for (i in lo.names.indices) {
            val name = lo.names[i]
            val type = lo.types.getOrElse(i) { "" }
            val isPk = lo.pks.getOrElse(i) { false }
            val nx = ix + ENTITY_PADDING + (if (isPk) KEY_ICON_W else 0)
            if (isPk) {
                sb.append("""  <circle cx="${ix + ENTITY_PADDING + 5}" cy="${cy - 4}" r="5" fill="none" stroke="$pk" stroke-width="1.2"/>""")
                sb.append("\n")
            }
            sb.append("""  <text x="$nx" y="$cy" font-size="12" fill="$tx">${escapeXml(name)}</text>""")
            sb.append("\n")
            if (type.isNotEmpty()) {
                val estW = type.length * 6
                sb.append("""  <text x="${ix + iw - ENTITY_PADDING - estW}" y="$cy" font-size="11" fill="$ty">${escapeXml(type)}</text>""")
                sb.append("\n")
            }
            cy += ROW_HEIGHT
        }
    }

    private fun appendLabelsSvg(sb: StringBuilder, lines: List<Line>) {
        val lc = hexOf(LINE_COLOR)
        val bg = hexOf(LABEL_BG)
        val bd = hexOf(LABEL_BORDER)
        val tx = hexOf(TEXT)

        for (l in lines) {
            val ft = cardText(l.jt, false)
            val fw = ft.length * 6 + 10
            sb.append("""  <rect x="${l.x1 + 30}" y="${l.y1 - 9}" width="$fw" height="18" rx="5" fill="$bg" stroke="$bd" stroke-width="1.0"/>""")
            sb.append("\n")
            sb.append("""  <text x="${l.x1 + 35}" y="${l.y1 + 4}" font-size="10" fill="$lc">$ft</text>""")
            sb.append("\n")

            val tt = cardText(l.jt, true)
            val tw2 = tt.length * 6 + 10
            sb.append("""  <rect x="${l.x2 - 30 - tw2}" y="${l.y2 - 9}" width="$tw2" height="18" rx="5" fill="$bg" stroke="$bd" stroke-width="1.0"/>""")
            sb.append("\n")
            sb.append("""  <text x="${l.x2 - 25 - tw2}" y="${l.y2 + 4}" font-size="10" fill="$lc">$tt</text>""")
            sb.append("\n")

            if (l.label.isNotEmpty()) {
                val text = truncate(l.label)
                val estW = text.length * 6 + 12
                val mx = (l.x1 + l.x2) / 2
                val my = (l.y1 + l.y2) / 2
                sb.append("""  <rect x="${mx - estW / 2}" y="${my - 10}" width="$estW" height="18" rx="5" fill="$bg" stroke="$bd" stroke-width="1.0"/>""")
                sb.append("\n")
                sb.append("""  <text x="$mx" y="${my + 4}" font-size="10" text-anchor="middle" fill="$tx">${escapeXml(text)}</text>""")
                sb.append("\n")
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun cardText(jt: JoinType, target: Boolean): String = when (jt) {
        JoinType.INNER -> if (target) "1" else "1"
        JoinType.LEFT -> if (target) "0..*" else "1"
        JoinType.RIGHT -> if (target) "1" else "0..*"
        JoinType.FULL -> if (target) "0..*" else "0..*"
    }

    private fun truncate(s: String): String =
        if (s.length > MAX_TEXT_LEN) s.take(MAX_TEXT_LEN - 1) + "\u2026" else s

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun hexOf(c: Color): String =
        if (c.alpha < 255) "#%02x%02x%02x%02x".format(c.alpha, c.red, c.green, c.blue)
        else "#%02x%02x%02x".format(c.red, c.green, c.blue)

    // ------------------------------------------------------------------
    // HiDPI-aware rendering for DiagramPanel
    // ------------------------------------------------------------------

    /** Preferred display size for the diagram. */
    data class Size(val width: Int, val height: Int)

    /** Measure preferred display size (at 1x zoom). */
    fun measurePreferredSize(model: DiagramModel): Size {
        if (model.entities.isEmpty()) return Size(200, 100)
        val font = UIUtil.getLabelFont()
        val bold = font.deriveFont(Font.BOLD)
        val frc = FontRenderContext(null, true, true)
        var minGap = COLUMN_GAP
        for (r in model.relationships) {
            var needed = COLUMN_GAP
            if (r.label.isNotEmpty()) {
                val text = if (r.label.length > 120) r.label.take(119) + "…" else r.label
                val tw = font.getStringBounds(text, frc).width.toInt() + 40
                if (tw > needed) needed = tw
            }
            needed = (needed + 120).coerceAtMost(520)
            if (needed > minGap) minGap = needed
        }
        val layouts = measureEntities(model.entities, font, bold, frc)
        val positions = computeLayout(layouts, minGap)
        val pad = MARGIN + 16
        val totalW = positions.zip(layouts).maxOf { (p, l) -> p.x + l.width } + pad
        val totalH = positions.zip(layouts).maxOf { (p, l) -> p.y + l.height } + pad
        return Size(totalW, totalH)
    }

    /**
     * Render diagram directly to a device-resolution BufferedImage.
     */
    fun renderToDevice(model: DiagramModel, deviceW: Int, deviceH: Int, zoom: Double = 1.0): BufferedImage {
        if (model.entities.isEmpty()) return BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)

        val font = UIUtil.getLabelFont()
        val bold = font.deriveFont(Font.BOLD)
        val frc = FontRenderContext(null, true, true)

        var minGap = COLUMN_GAP
        for (r in model.relationships) {
            var needed = COLUMN_GAP
            if (r.label.isNotEmpty()) {
                val text = if (r.label.length > 120) r.label.take(119) + "…" else r.label
                val tw = font.getStringBounds(text, frc).width.toInt() + 40
                if (tw > needed) needed = tw
            }
            needed = (needed + 120).coerceAtMost(520)
            if (needed > minGap) minGap = needed
        }

        val layouts = measureEntities(model.entities, font, bold, frc)
        val positions = computeLayout(layouts, minGap)
        val pad = MARGIN + 16
        val totalW = positions.zip(layouts).maxOf { (p, l) -> p.x + l.width } + pad
        val totalH = positions.zip(layouts).maxOf { (p, l) -> p.y + l.height } + pad

        val img = BufferedImage(deviceW, deviceH, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)

        val scaleX = deviceW.toDouble() / totalW
        val scaleY = deviceH.toDouble() / totalH
        val baseScale = minOf(scaleX, scaleY).coerceAtLeast(0.1) * zoom
        val offsetX = ((deviceW - totalW * baseScale) / 2).coerceAtLeast(0.0)
        val offsetY = ((deviceH - totalH * baseScale) / 2).coerceAtLeast(0.0)
        g2.translate(offsetX, offsetY)
        g2.scale(baseScale, baseScale)

        g2.color = UIUtil.getPanelBackground()
        g2.fillRect(0, 0, totalW, totalH)

        val lines = prepareLines(model, positions, layouts)

        for ((i, e) in model.entities.withIndex()) {
            drawCard(g2, e, positions[i], layouts[i], font, bold)
        }

        drawLabels(g2, lines, font, frc)

        g2.dispose()
        return img
    }

}
