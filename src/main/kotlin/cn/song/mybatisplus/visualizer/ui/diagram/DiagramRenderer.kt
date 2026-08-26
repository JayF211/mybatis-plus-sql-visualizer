package cn.song.mybatisplus.visualizer.ui.diagram

import cn.song.mybatisplus.visualizer.model.JoinType
import com.intellij.ui.JBColor
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
import kotlin.math.min
import kotlin.math.sqrt

/**
 * F-xx：JOIN 关系图 Swing 自绘引擎（IDEA 原生 ER 图风格 v3）。
 *
 * 直接 Graphics2D 渲染（无 2x DPI 超采样），HiDPI 由 Graphics2D.transform 自动处理。
 * 卡片样式：白底 + 细边框 + 投影，列间分隔线，行交替底色。
 * 关系线：深灰 1.5px 粗线 + 实心三角箭头。
 */
object DiagramRenderer {

    const val ENTITY_PADDING = 14
    const val HEADER_HEIGHT = 30
    const val ROW_HEIGHT = 22
    const val COLUMN_GAP = 100
    const val ROW_GAP = 56
    const val MARGIN = 56
    const val CARD_ARC = 6
    const val ARROW_LEN = 9
    const val ARROW_HW = 4
    const val MAX_CARD_WIDTH = 300
    const val MIN_CARD_WIDTH = 170
    const val MAX_TEXT_LEN = 60
    const val TYPE_GAP = 24
    const val KEY_ICON_W = 16

    val SHADOW = JBColor(Color(0x00, 0x00, 0x00, 25), Color(0x00, 0x00, 0x00, 45))
    val CARD_BG = JBColor(Color(0xFF, 0xFF, 0xFF), Color(0x2D, 0x2F, 0x33))
    val CARD_BG_ALT = JBColor(Color(0xF7, 0xF8, 0xFA), Color(0x2A, 0x2C, 0x30))
    val CARD_BORDER = JBColor(Color(0xC8, 0xCC, 0xD4), Color(0x50, 0x53, 0x5A))
    val MAIN_HDR_BG = JBColor(Color(0xDC, 0xE8, 0xDC), Color(0x2E, 0x3B, 0x2E))
    val JOIN_HDR_BG = JBColor(Color(0xDC, 0xE5, 0xED), Color(0x2A, 0x30, 0x38))
    val HEADER_LINE = JBColor(Color(0xB8, 0xBC, 0xC4), Color(0x4A, 0x4E, 0x56))
    val SEPARATOR = JBColor(Color(0xE4, 0xE6, 0xEA), Color(0x3A, 0x3D, 0x42))
    val TEXT = JBColor(Color(0x1A, 0x1A, 0x1A), Color(0xBB, 0xBB, 0xBB))
    val TYPE_COLOR = JBColor(Color(0x88, 0x88, 0x88), Color(0x80, 0x80, 0x80))
    val PK_COLOR = JBColor(Color(0x9A, 0x68, 0x00), Color(0xE2, 0xC0, 0x79))
    val LINE_COLOR = JBColor(Color(0x55, 0x55, 0x55), Color(0x77, 0x77, 0x77))
    val LABEL_BG = JBColor(Color(0xFF, 0xFF, 0xFF), Color(0x33, 0x36, 0x3B))
    val LABEL_BORDER = JBColor(Color(0xC0, 0xC0, 0xC0), Color(0x50, 0x53, 0x5A))

    // ------------------------------------------------------------------
    // Layout data classes
    // ------------------------------------------------------------------

    data class Layout(
        val width: Int, val height: Int,
        val header: String,
        val names: List<String>, val types: List<String>, val pks: List<Boolean>,
    )

    data class Pos(val x: Int, val y: Int)

    data class Line(
        val x1: Int, val y1: Int, val x2: Int, val y2: Int,
        val jt: JoinType, val label: String,
    )

    data class Size(val width: Int, val height: Int)

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

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
        return Size(totalW, totalH)
    }

    /** Render to BufferedImage (for PNG export). */
    fun render(model: DiagramModel): BufferedImage {
        if (model.entities.isEmpty()) return BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
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

        val img = BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)

        g2.color = UIUtil.getPanelBackground()
        g2.fillRect(0, 0, totalW, totalH)

        val lines = prepareLinesFor(model)
        drawCards(g2, model, font, bold)
        drawLabels(g2, model, lines, font, frc)

        g2.dispose()
        return img
    }

    /** Render to SVG string (for SVG export). */
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

        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$totalW" height="$totalH">""").append("\n")

        val lines = prepareLinesFor(model)
        appendLinesSvg(sb, lines)
        appendCardsSvg(sb, model)
        appendLabelsSvg(sb, lines)

        sb.append("</svg>\n")
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // Internal rendering (used by both render() and DiagramPanel canvas)
    // ------------------------------------------------------------------

    private fun measureEntities(ents: List<EntityNode>, font: Font, bold: Font, frc: FontRenderContext): List<Layout> {
        return ents.map { e ->
            val hdr = truncate(e.displayName)
            val hdrW = bold.getStringBounds(hdr, frc).width.toInt() + 2 * ENTITY_PADDING
            val names = e.columns.map { truncate(it.name) }
            val types = e.columns.map { truncate(it.type ?: "") }
            val pks = e.columns.map { it.isPk }
            val maxNameW = names.maxOfOrNull { font.getStringBounds(it, frc).width.toInt() } ?: 0
            val maxTypeW = types.maxOfOrNull { font.getStringBounds(it, frc).width.toInt() } ?: 0
            val contentW = max(hdrW, ENTITY_PADDING + KEY_ICON_W + maxNameW + TYPE_GAP + maxTypeW + ENTITY_PADDING)
            val cardW = contentW.coerceIn(MIN_CARD_WIDTH, MAX_CARD_WIDTH)
            val cardH = HEADER_HEIGHT + e.columns.size * ROW_HEIGHT + ENTITY_PADDING
            Layout(cardW, cardH, hdr, names, types, pks)
        }
    }

    private fun computeLayout(layouts: List<Layout>, minGap: Int): List<Pos> {
        if (layouts.isEmpty()) return emptyList()
        val gap = maxOf(minGap, COLUMN_GAP)
        val ps = mutableListOf(Pos(MARGIN, MARGIN))
        val jx = MARGIN + layouts[0].width + gap
        var jy = MARGIN
        for (i in 1 until layouts.size) {
            ps.add(Pos(jx, jy))
            jy += layouts[i].height + ROW_GAP
        }
        return ps
    }

    /** Prepare line data for the model (uses internal layout). */
    fun prepareLinesFor(model: DiagramModel): List<Line> {
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
        val byIdx = model.entities.withIndex().associate { (i, e) -> e.alias to i }
        return model.relationships.mapNotNull { r ->
            val fi = byIdx[r.fromAlias] ?: return@mapNotNull null
            val ti = byIdx[r.toAlias] ?: return@mapNotNull null
            Line(
                x1 = positions[fi].x + layouts[fi].width,
                y1 = positions[fi].y + layouts[fi].height / 2,
                x2 = positions[ti].x,
                y2 = positions[ti].y + layouts[ti].height / 2,
                jt = r.joinType,
                label = r.label,
            )
        }
    }

    /** Draw all entity cards. */
    fun drawCards(g2: Graphics2D, model: DiagramModel, font: Font, bold: Font) {
        val frc = g2.fontRenderContext
        val minGap = computeMinGap(model, font, frc)
        val layouts = measureEntities(model.entities, font, bold, frc)
        val positions = computeLayout(layouts, minGap)
        for ((i, e) in model.entities.withIndex()) {
            drawCard(g2, e, positions[i], layouts[i], font, bold)
        }
    }

    /** Draw labels (lines + badges + ON condition). */
    fun drawLabels(g2: Graphics2D, model: DiagramModel, lines: List<Line>, font: Font, frc: FontRenderContext) {
        val gaps = mutableListOf<java.awt.geom.Rectangle2D.Double>()

        for (l in lines) {
            var minX = Double.MAX_VALUE
            var maxX = Double.MIN_VALUE
            var minY = Double.MAX_VALUE
            var maxY = Double.MIN_VALUE

            // ON condition label: centered
            if (l.label.isNotEmpty()) {
                val rawText = if (l.label.length > 120) l.label.take(119) + "\u2026" else l.label
                val fullW = font.getStringBounds(rawText, frc).width
                val bw = (fullW + 20).toInt().coerceIn(60, 400)
                val bh = (font.getStringBounds(rawText, frc).height + 12).toInt()
                val bx = ((l.x1 + l.x2) / 2 - bw / 2)
                val by = ((l.y1 + l.y2) / 2 - bh / 2)
                onLabel(g2, rawText, bx, by, bw, bh, font)
                minX = minOf(minX, bx.toDouble())
                maxX = maxOf(maxX, (bx + bw).toDouble())
                minY = minOf(minY, by.toDouble())
                maxY = maxOf(maxY, (by + bh).toDouble())
            }

            // Cardinality labels
            val fromText = cardText(l.jt, false)
            val toText = cardText(l.jt, true)
            val fromW = font.getStringBounds(fromText, frc).width.toInt() + 16
            val toW = font.getStringBounds(toText, frc).width.toInt() + 16
            val cbh = 20

            val fromX = l.x1 + 30
            val toX = l.x2 - 30 - toW
            val fromY = l.y1 - cbh - 3
            val toY = l.y2 + 3
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

    private fun computeMinGap(model: DiagramModel, font: Font, frc: FontRenderContext): Int {
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
        return minGap
    }

    private fun drawLines(g2: Graphics2D, lines: List<Line>, gaps: List<java.awt.geom.Rectangle2D.Double>) {
        g2.color = LINE_COLOR
        g2.stroke = BasicStroke(1.5f)
        for (i in lines.indices) {
            val l = lines[i]
            val gap = gaps[i]
            val gx1 = (gap.x + 16).toInt()
            val gx2 = (gap.x + gap.width - 16).toInt()

            if (gap.width > 30) {
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
        p.lineTo(tipX - ux * ARROW_LEN + vx * ARROW_HW, tipY - uy * ARROW_LEN + vy * ARROW_HW)
        p.lineTo(tipX - ux * ARROW_LEN - vx * ARROW_HW, tipY - uy * ARROW_LEN - vy * ARROW_HW)
        p.closePath()
        g2.fill(p)
    }

    private fun drawCard(
        g2: Graphics2D, e: EntityNode, pos: Pos, lo: Layout,
        font: Font, bold: Font,
    ) {
        val ix = pos.x
        val iy = pos.y
        val iw = lo.width
        val ih = lo.height

        // Shadow
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.05f)
        g2.color = Color.BLACK
        g2.fillRoundRect(ix + 3, iy + 3, iw, ih, CARD_ARC, CARD_ARC)
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f)

        val colAreaY = iy + HEADER_HEIGHT

        // Alternating row backgrounds
        for (i in lo.names.indices) {
            val rowY = colAreaY + i * ROW_HEIGHT
            if (i % 2 == 1) {
                g2.color = CARD_BG_ALT
                g2.fillRect(ix, rowY, iw, ROW_HEIGHT)
            }
        }

        // Card body
        g2.color = CARD_BG
        g2.fillRoundRect(ix, iy, iw, ih, CARD_ARC, CARD_ARC)

        // Header
        val hdrColor = if (e.isMain) MAIN_HDR_BG else JOIN_HDR_BG
        g2.color = hdrColor
        g2.fillRoundRect(ix, iy, iw, HEADER_HEIGHT, CARD_ARC, CARD_ARC)
        g2.fillRect(ix, iy + HEADER_HEIGHT - CARD_ARC, iw, CARD_ARC)

        // Header bottom separator
        g2.color = HEADER_LINE
        g2.stroke = BasicStroke(1.0f)
        g2.drawLine(ix + ENTITY_PADDING, iy + HEADER_HEIGHT, ix + iw - ENTITY_PADDING, iy + HEADER_HEIGHT)

        // Column separators
        g2.color = SEPARATOR
        var sepY = colAreaY
        for (i in 0 until lo.names.size - 1) {
            sepY += ROW_HEIGHT
            g2.drawLine(ix + ENTITY_PADDING, sepY, ix + iw - ENTITY_PADDING, sepY)
        }

        // Card border
        g2.color = CARD_BORDER
        g2.stroke = BasicStroke(1.0f)
        g2.drawRoundRect(ix, iy, iw, ih, CARD_ARC, CARD_ARC)

        // Title text
        g2.font = bold
        g2.color = TEXT
        g2.drawString(lo.header, ix + ENTITY_PADDING, iy + HEADER_HEIGHT - 9)

        // Columns
        g2.font = font
        var cy = colAreaY + ENTITY_PADDING + ROW_HEIGHT - 5
        for (i in lo.names.indices) {
            val name = lo.names[i]
            val type = lo.types.getOrElse(i) { "" }
            val isPk = lo.pks.getOrElse(i) { false }

            if (isPk) {
                g2.color = PK_COLOR
                drawKey(g2, ix + ENTITY_PADDING, cy - 9, 11, 11)
            }

            g2.color = TEXT
            g2.drawString(name, ix + ENTITY_PADDING + (if (isPk) KEY_ICON_W else 0), cy)

            if (type.isNotEmpty()) {
                g2.color = TYPE_COLOR
                val tw = font.getStringBounds(type, g2.fontRenderContext).width.toInt()
                g2.drawString(type, ix + iw - ENTITY_PADDING - tw, cy)
            }
            cy += ROW_HEIGHT
        }
    }

    private fun drawKey(g2: Graphics2D, x: Int, y: Int, w: Int, h: Int) {
        g2.stroke = BasicStroke(1.2f)
        val r = w / 3
        g2.drawOval(x, y, r * 2, r * 2)
        g2.drawLine(x + r * 2, y + r, x + w, y + r)
        g2.drawLine(x + w - 3, y + r, x + w - 3, y + h - 2)
    }

    private fun badge(g2: Graphics2D, text: String, x: Int, y: Int, w: Int, h: Int, font: Font) {
        g2.color = LABEL_BG
        g2.fillRoundRect(x, y, w, h, 5, 5)
        g2.color = LABEL_BORDER
        g2.stroke = BasicStroke(1.0f)
        g2.drawRoundRect(x, y, w, h, 5, 5)
        g2.color = LINE_COLOR
        g2.font = font
        val ascent = font.getLineMetrics(text, g2.fontRenderContext).ascent.toInt()
        g2.drawString(text, x + 8, y + 3 + ascent)
    }

    private fun onLabel(g2: Graphics2D, text: String, bx: Int, by: Int, bw: Int, bh: Int, font: Font) {
        g2.color = LABEL_BG
        g2.fillRoundRect(bx, by, bw, bh, 6, 6)
        g2.color = LABEL_BORDER
        g2.stroke = BasicStroke(1.0f)
        g2.drawRoundRect(bx, by, bw, bh, 6, 6)
        g2.color = TEXT
        g2.font = font
        val ascent = font.getLineMetrics(text, g2.fontRenderContext).ascent.toInt()
        g2.drawString(text, bx + 10, by + 4 + ascent)
    }

    // ------------------------------------------------------------------
    // SVG output helpers
    // ------------------------------------------------------------------

    private fun appendLinesSvg(sb: StringBuilder, lines: List<Line>) {
        val lc = hexOf(LINE_COLOR)
        for (l in lines) {
            sb.append("""  <line x1="${l.x1}" y1="${l.y1}" x2="${l.x2}" y2="${l.y2}" stroke="$lc" stroke-width="1.5"/>""").append("\n")
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
                sb.append("""  <polygon points="${l.x2},${l.y2} $p1x,$p1y $p2x,$p2y" fill="$lc"/>""").append("\n")
            }
        }
    }

    private fun appendCardsSvg(sb: StringBuilder, model: DiagramModel) {
        val font = UIUtil.getLabelFont()
        val bold = font.deriveFont(Font.BOLD)
        val frc = FontRenderContext(null, true, true)
        val minGap = computeMinGap(model, font, frc)
        val layouts = measureEntities(model.entities, font, bold, frc)
        val positions = computeLayout(layouts, minGap)

        val bg = hexOf(CARD_BG)
        val bd = hexOf(CARD_BORDER)
        val tx = hexOf(TEXT)
        val ty = hexOf(TYPE_COLOR)
        val pk = hexOf(PK_COLOR)
        val hl = hexOf(HEADER_LINE)
        val sep = hexOf(SEPARATOR)

        for ((i, e) in model.entities.withIndex()) {
            val pos = positions[i]
            val lo = layouts[i]
            val ix = pos.x; val iy = pos.y; val iw = lo.width; val ih = lo.height
            val hdr = hexOf(if (e.isMain) MAIN_HDR_BG else JOIN_HDR_BG)
            val colAreaY = iy + HEADER_HEIGHT

            sb.append("""  <rect x="$ix" y="$iy" width="$iw" height="$ih" rx="$CARD_ARC" fill="$bg" stroke="$bd" stroke-width="1.0"/>""").append("\n")
            sb.append("""  <rect x="$ix" y="$iy" width="$iw" height="$HEADER_HEIGHT" rx="$CARD_ARC" fill="$hdr"/>""").append("\n")
            sb.append("""  <rect x="$ix" y="${iy + HEADER_HEIGHT - CARD_ARC}" width="$iw" height="$CARD_ARC" fill="$hdr"/>""").append("\n")
            sb.append("""  <line x1="${ix + ENTITY_PADDING}" y1="${iy + HEADER_HEIGHT}" x2="${ix + iw - ENTITY_PADDING}" y2="${iy + HEADER_HEIGHT}" stroke="$hl" stroke-width="1.0"/>""").append("\n")

            var sepY = colAreaY
            for (j in 0 until lo.names.size - 1) {
                sepY += ROW_HEIGHT
                sb.append("""  <line x1="${ix + ENTITY_PADDING}" y1="$sepY" x2="${ix + iw - ENTITY_PADDING}" y2="$sepY" stroke="$sep" stroke-width="0.8"/>""").append("\n")
            }

            sb.append("""  <text x="${ix + ENTITY_PADDING}" y="${iy + HEADER_HEIGHT - 9}" font-weight="bold" font-size="12" fill="$tx">${escapeXml(lo.header)}</text>""").append("\n")

            var cy = colAreaY + ENTITY_PADDING + ROW_HEIGHT - 5
            for (j in lo.names.indices) {
                val name = lo.names[j]
                val type = lo.types.getOrElse(j) { "" }
                val isPk = lo.pks.getOrElse(j) { false }
                val nx = ix + ENTITY_PADDING + (if (isPk) KEY_ICON_W else 0)
                if (isPk) {
                    sb.append("""  <circle cx="${ix + ENTITY_PADDING + 5}" cy="${cy - 4}" r="5" fill="none" stroke="$pk" stroke-width="1.2"/>""").append("\n")
                }
                sb.append("""  <text x="$nx" y="$cy" font-size="12" fill="$tx">${escapeXml(name)}</text>""").append("\n")
                if (type.isNotEmpty()) {
                    val estW = type.length * 6
                    sb.append("""  <text x="${ix + iw - ENTITY_PADDING - estW}" y="$cy" font-size="11" fill="$ty">${escapeXml(type)}</text>""").append("\n")
                }
                cy += ROW_HEIGHT
            }
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
            sb.append("""  <rect x="${l.x1 + 30}" y="${l.y1 - 9}" width="$fw" height="18" rx="5" fill="$bg" stroke="$bd" stroke-width="1.0"/>""").append("\n")
            sb.append("""  <text x="${l.x1 + 35}" y="${l.y1 + 4}" font-size="10" fill="$lc">$ft</text>""").append("\n")

            val tt = cardText(l.jt, true)
            val tw2 = tt.length * 6 + 10
            sb.append("""  <rect x="${l.x2 - 30 - tw2}" y="${l.y2 - 9}" width="$tw2" height="18" rx="5" fill="$bg" stroke="$bd" stroke-width="1.0"/>""").append("\n")
            sb.append("""  <text x="${l.x2 - 25 - tw2}" y="${l.y2 + 4}" font-size="10" fill="$lc">$tt</text>""").append("\n")

            if (l.label.isNotEmpty()) {
                val text = truncate(l.label)
                val estW = text.length * 6 + 12
                val mx = (l.x1 + l.x2) / 2
                val my = (l.y1 + l.y2) / 2
                sb.append("""  <rect x="${mx - estW / 2}" y="${my - 10}" width="$estW" height="18" rx="5" fill="$bg" stroke="$bd" stroke-width="1.0"/>""").append("\n")
                sb.append("""  <text x="$mx" y="${my + 4}" font-size="10" text-anchor="middle" fill="$tx">${escapeXml(text)}</text>""").append("\n")
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
}
