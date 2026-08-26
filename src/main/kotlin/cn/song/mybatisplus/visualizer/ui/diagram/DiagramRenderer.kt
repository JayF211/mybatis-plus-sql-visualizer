package cn.song.mybatisplus.visualizer.ui.diagram

import cn.song.mybatisplus.visualizer.model.JoinType
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
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
 * F-xx：JOIN 关系图 Swing 自绘引擎（ER 图风格）。
 *
 * 视觉风格：
 *  - 实体卡片：白底 + 明显边框 + 阴影，主表绿色标题栏，JOIN 表蓝色标题栏
 *  - 列信息：PK 前金色钥匙图标，列名左对齐，类型右对齐灰色
 *  - 关系线：深灰粗线 + 实心三角箭头
 *  - 标签：cardinality 置于线条两端空白处（不贴边），ON 条件标签白色不透明背景
 *  - 渲染层级：线 → 卡片 → 标签（标签始终在最上层）
 *
 * 线程安全：纯函数式，无状态。
 */
object DiagramRenderer {

    private const val ENTITY_PADDING = 14
    private const val HEADER_HEIGHT = 28
    private const val ROW_HEIGHT = 18
    private const val COLUMN_GAP = 120
    private const val ROW_GAP = 56
    private const val MARGIN = 56
    private const val CARD_ARC = 6
    private const val ARROW_LEN = 10
    private const val ARROW_HW = 5
    private const val MAX_CARD_WIDTH = 280
    private const val MIN_CARD_WIDTH = 160
    private const val MAX_TEXT_LEN = 50
    private const val TYPE_GAP = 24
    private const val KEY_ICON_W = 16
    private const val SHADOW_DX = 3
    private const val SHADOW_DY = 3

    // ---- Colors (light / dark) ----
    private val SHADOW = JBColor(Color(0x00, 0x00, 0x00, 35), Color(0x00, 0x00, 0x00, 60))
    private val CARD_BG = JBColor(Color(0xFF, 0xFF, 0xFF), Color(0x2B, 0x2B, 0x2B))
    private val CARD_BORDER = JBColor(Color(0xB0, 0xB4, 0xBC), Color(0x5A, 0x5E, 0x66))
    private val MAIN_HDR_BG = JBColor(Color(0xD7, 0xEA, 0xD7), Color(0x2D, 0x3B, 0x2D))
    private val JOIN_HDR_BG = JBColor(Color(0xD7, 0xE2, 0xEA), Color(0x2D, 0x33, 0x3B))
    private val HEADER_LINE = JBColor(Color(0xA0, 0xA4, 0xAC), Color(0x4A, 0x4E, 0x56))
    private val TEXT = JBColor(Color(0x1A, 0x1A, 0x1A), Color(0xCC, 0xCC, 0xCC))
    private val TYPE_COLOR = JBColor(Color(0x80, 0x80, 0x80), Color(0x88, 0x88, 0x88))
    private val PK_COLOR = JBColor(Color(0x9A, 0x68, 0x00), Color(0xD4, 0xA8, 0x40))
    private val LINE_COLOR = JBColor(Color(0x66, 0x66, 0x66), Color(0x88, 0x88, 0x88))
    private val LABEL_BG = JBColor(Color(0xFF, 0xFF, 0xFF), Color(0x30, 0x32, 0x36))
    private val LABEL_BORDER = JBColor(Color(0xBB, 0xBB, 0xBB), Color(0x5A, 0x5E, 0x66))
    private val CARD_SHADOW_INNER = 2  // 2px inner padding so shadow doesn\'t clip

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    fun render(model: DiagramModel): BufferedImage {
        if (model.entities.isEmpty()) return BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)

        val font = UIUtil.getLabelFont()
        val bold = font.deriveFont(Font.BOLD)
        val frc = FontRenderContext(null, true, true)

        // Compute dynamic gap based on ON condition text widths
        var minGap = COLUMN_GAP
        for (r in model.relationships) {
            if (r.label.isNotEmpty()) {
                val text = if (r.label.length > 120) r.label.take(119) + "…" else r.label
                val tw = font.getStringBounds(text, frc).width.toInt() + 40
                if (tw > minGap) minGap = (tw + 30).coerceAtMost(500)
            }
        }

        val layouts = measureEntities(model.entities, font, bold)
        val positions = computeLayout(layouts, minGap)
        val pad = MARGIN + 16
        val totalW = positions.zip(layouts).maxOf { (p, l) -> p.x + l.width } + pad
        val totalH = positions.zip(layouts).maxOf { (p, l) -> p.y + l.height } + pad
        val width = max(totalW, MIN_CARD_WIDTH + 2 * MARGIN)
        val height = max(totalH, HEADER_HEIGHT + 2 * MARGIN)

        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        // Background
        g2.color = UIUtil.getPanelBackground()
        g2.fillRect(0, 0, width, height)

        val lines = prepareLines(model, positions, layouts)

        // Draw cards
        for ((i, e) in model.entities.withIndex()) {
            drawCard(g2, e, positions[i], layouts[i], font, bold)
        }

        // Draw labels (computes gaps, draws lines under, badges on top)
        drawLabels(g2, lines, font)

        g2.dispose()
        return img
    }

    fun renderToSvg(model: DiagramModel): String {
        if (model.entities.isEmpty()) return """<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1"></svg>"""

        val font = UIUtil.getLabelFont()
        val bold = font.deriveFont(Font.BOLD)
        val frc = FontRenderContext(null, true, true)

        var minGap = COLUMN_GAP
        for (r in model.relationships) {
            if (r.label.isNotEmpty()) {
                val text = if (r.label.length > 120) r.label.take(119) + "…" else r.label
                val tw = font.getStringBounds(text, frc).width.toInt() + 40
                if (tw > minGap) minGap = (tw + 30).coerceAtMost(500)
            }
        }

        val layouts = measureEntities(model.entities, font, bold)
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

    private fun measureEntities(ents: List<EntityNode>, font: Font, bold: Font): List<Layout> {
        val frc = FontRenderContext(null, true, true)
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
            val cardH = HEADER_HEIGHT + e.columns.size * ROW_HEIGHT + ENTITY_PADDING + 4
            Layout(cardW, cardH, hdr, names, types, pks)
        }
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private data class Pos(val x: Int, val y: Int)

    private fun computeLayout(layouts: List<Layout>, minGap: Int = COLUMN_GAP): List<Pos> {
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
    // Layer 1: Lines
    // ------------------------------------------------------------------

    private fun drawLines(g2: Graphics2D, lines: List<Line>, gaps: List<java.awt.geom.Rectangle2D.Double>) {
        g2.color = LINE_COLOR
        g2.stroke = BasicStroke(1.8f)
        for (i in lines.indices) {
            val l = lines[i]
            val gap = gaps[i]
            val gx1 = gap.x.toInt()
            val gx2 = (gap.x + gap.width).toInt()
            val gy1 = gap.y.toInt() - 4
            val gy2 = (gap.y + gap.height).toInt() + 4

            // Skip line segments that pass through the gap
            if (gap.width > 20) {
                // Draw from start to gap start
                if (l.x1 < gx1 - 2) {
                    g2.drawLine(l.x1, l.y1, minOf(gx1, l.x2), l.y1)
                }
                // Draw from gap end to end (with arrow)
                if (l.x2 > gx2 + 2) {
                    val startX = maxOf(gx2, l.x1)
                    if (startX < l.x2) {
                        g2.drawLine(startX, l.y2, l.x2, l.y2)
                    }
                }
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

    // ------------------------------------------------------------------
    // Layer 2: Cards
    // ------------------------------------------------------------------

    private fun drawCard(
        g2: Graphics2D, e: EntityNode, pos: Pos, lo: Layout,
        font: Font, bold: Font,
    ) {
        val ix = pos.x + CARD_SHADOW_INNER
        val iy = pos.y + CARD_SHADOW_INNER
        val iw = lo.width - 2 * CARD_SHADOW_INNER
        val ih = lo.height - 2 * CARD_SHADOW_INNER

        // Shadow
        g2.color = SHADOW
        g2.fillRoundRect(ix + SHADOW_DX, iy + SHADOW_DY, iw, ih, CARD_ARC, CARD_ARC)

        // Card body
        g2.color = CARD_BG
        g2.fillRoundRect(ix, iy, iw, ih, CARD_ARC, CARD_ARC)

        // Header
        val hdrColor = if (e.isMain) MAIN_HDR_BG else JOIN_HDR_BG
        g2.color = hdrColor
        g2.fillRoundRect(ix, iy, iw, HEADER_HEIGHT, CARD_ARC, CARD_ARC)
        g2.fillRect(ix, iy + HEADER_HEIGHT - CARD_ARC, iw, CARD_ARC)

        // Header bottom line
        g2.color = HEADER_LINE
        g2.stroke = BasicStroke(1.2f)
        g2.drawLine(ix, iy + HEADER_HEIGHT, ix + iw, iy + HEADER_HEIGHT)

        // Card border
        g2.color = CARD_BORDER
        g2.stroke = BasicStroke(1.5f)
        g2.drawRoundRect(ix, iy, iw, ih, CARD_ARC, CARD_ARC)

        // Title text
        g2.font = bold
        g2.color = TEXT
        g2.drawString(lo.header, ix + ENTITY_PADDING, iy + HEADER_HEIGHT - 8)

        // Columns
        g2.font = font
        var cy = iy + HEADER_HEIGHT + ENTITY_PADDING + ROW_HEIGHT - 4
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
                val tw = g2.fontRenderContext.let { font.getStringBounds(type, it).width.toInt() }
                g2.drawString(type, ix + iw - ENTITY_PADDING - tw, cy)
            }
            cy += ROW_HEIGHT
        }
    }

    private fun drawKey(g2: Graphics2D, x: Int, y: Int, w: Int, h: Int) {
        g2.stroke = BasicStroke(1.3f)
        val r = w / 3
        g2.drawOval(x, y, r * 2, r * 2)
        g2.drawLine(x + r * 2, y + r, x + w, y + r)
        g2.drawLine(x + w - 3, y + r, x + w - 3, y + h - 2)
    }

    // ------------------------------------------------------------------
    // Layer 3: Labels (always on top, fully opaque backgrounds)
    // ------------------------------------------------------------------

    private fun drawLabels(g2: Graphics2D, lines: List<Line>, font: Font) {
        val frc = g2.fontRenderContext
        val gaps = mutableListOf<java.awt.geom.Rectangle2D.Double>()

        for (l in lines) {
            var minX = minOf(l.x1, l.x2).toDouble()
            var maxX = maxOf(l.x1, l.x2).toDouble()
            var minY = (l.y1 - 14).toDouble()
            var maxY = (l.y1 + 14).toDouble()

                                    // ON condition label: dynamic sizing - ensure gap is wide enough
            if (l.label.isNotEmpty()) {
                val rawText = if (l.label.length > 120) l.label.take(119) + "…" else l.label
                val maxBadgeW = 400  // max reasonable width
                val minGapNeeded = maxBadgeW + 32  // badge + margins
                val actualGap = (l.x2 - l.x1)
                val fullW = font.getStringBounds(rawText, frc).width
                val bw = (fullW + 20).toInt().coerceIn(60, maxBadgeW)
                val bh = (font.getStringBounds(rawText, frc).height.toInt() + 12)
                val bx = ((l.x1 + l.x2 - bw) / 2).toInt()
                val by = ((l.y1 + l.y2 - bh) / 2).toInt()
                onLabel(g2, rawText, bx, by, bw, bh, font)
                minX = minOf(minX, l.x1.toDouble())
                maxX = maxOf(maxX, l.x2.toDouble())
                minY = minOf(minY, by.toDouble())
                maxY = maxOf(maxY, (by + bh).toDouble())
            }

            // Cardinality labels: 22px from card edges
            val fromText = cardText(l.jt, false)
            val toText = cardText(l.jt, true)
            val fromW = font.getStringBounds(fromText, frc).width.toInt() + 16
            val toW = font.getStringBounds(toText, frc).width.toInt() + 16
            val cbh = 20

            // Position cardinality labels vertically offset from line to avoid overlapping ON condition label
            val fromX = l.x1 + 16
            val toX = l.x2 - 16 - toW
            val fromY = l.y1 - cbh - 2  // above the line
            val toY = l.y2 + 2          // below the line
            badge(g2, fromText, fromX, fromY, fromW, cbh, font)
            badge(g2, toText, toX, toY, toW, cbh, font)

            minX = minOf(minX, fromX.toDouble(), toX.toDouble())
            maxX = maxOf(maxX, (fromX + fromW).toDouble(), (toX + toW).toDouble())
            minY = minOf(minY, fromY.toDouble(), toY.toDouble())
            maxY = maxOf(maxY, (fromY + cbh).toDouble(), (toY + cbh).toDouble())

            gaps.add(java.awt.geom.Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY))
        }

        // Draw lines UNDER the labels (with gaps)
        drawLines(g2, lines, gaps)
    }

    private fun badge(g2: Graphics2D, text: String, x: Int, y: Int, w: Int, h: Int, font: Font) {
        val frc = g2.fontRenderContext
        g2.color = LABEL_BG
        g2.fillRoundRect(x, y, w, h, 5, 5)
        g2.color = LABEL_BORDER
        g2.stroke = BasicStroke(1.0f)
        g2.drawRoundRect(x, y, w, h, 5, 5)
        g2.color = LINE_COLOR
        g2.font = font
        val ascent = font.getLineMetrics(text, frc).ascent.toInt()
        g2.drawString(text, x + 8, y + 2 + ascent)
    }

    private fun onLabel(g2: Graphics2D, text: String, bx: Int, by: Int, bw: Int, bh: Int, font: Font) {
        val frc = g2.fontRenderContext
        g2.color = LABEL_BG
        g2.fillRoundRect(bx, by, bw, bh, 6, 6)
        g2.color = LABEL_BORDER
        g2.stroke = BasicStroke(1.0f)
        g2.drawRoundRect(bx, by, bw, bh, 6, 6)
        g2.color = TEXT
        g2.font = font
        val ascent = font.getLineMetrics(text, frc).ascent.toInt()
        g2.drawString(text, bx + 9, by + 3 + ascent)
    }

    // ------------------------------------------------------------------
    // SVG output (same layer structure)
    // ------------------------------------------------------------------

    private fun appendLinesSvg(sb: StringBuilder, lines: List<Line>) {
        val lc = hexOf(LINE_COLOR)
        for (l in lines) {
            sb.append("""  <line x1="${l.x1}" y1="${l.y1}" x2="${l.x2}" y2="${l.y2}" stroke="$lc" stroke-width="1.8"/>""")
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
        val ix = pos.x + CARD_SHADOW_INNER
        val iy = pos.y + CARD_SHADOW_INNER
        val iw = lo.width - 2 * CARD_SHADOW_INNER
        val ih = lo.height - 2 * CARD_SHADOW_INNER

        val sh = hexOf(SHADOW)
        val bg = hexOf(CARD_BG)
        val hdr = hexOf(if (e.isMain) MAIN_HDR_BG else JOIN_HDR_BG)
        val bd = hexOf(CARD_BORDER)
        val hl = hexOf(HEADER_LINE)
        val tx = hexOf(TEXT)
        val ty = hexOf(TYPE_COLOR)
        val pk = hexOf(PK_COLOR)

        sb.append("""  <rect x="${ix + SHADOW_DX}" y="${iy + SHADOW_DY}" width="$iw" height="$ih" rx="$CARD_ARC" fill="$sh"/>""")
        sb.append("\n")
        sb.append("""  <rect x="$ix" y="$iy" width="$iw" height="$ih" rx="$CARD_ARC" fill="$bg" stroke="$bd" stroke-width="1.5"/>""")
        sb.append("\n")
        sb.append("""  <rect x="$ix" y="$iy" width="$iw" height="$HEADER_HEIGHT" rx="$CARD_ARC" fill="$hdr"/>""")
        sb.append("\n")
        sb.append("""  <rect x="$ix" y="${iy + HEADER_HEIGHT - CARD_ARC}" width="$iw" height="$CARD_ARC" fill="$hdr"/>""")
        sb.append("\n")
        sb.append("""  <line x1="$ix" y1="${iy + HEADER_HEIGHT}" x2="${ix + iw}" y2="${iy + HEADER_HEIGHT}" stroke="$hl" stroke-width="1.2"/>""")
        sb.append("\n")
        sb.append("""  <text x="${ix + ENTITY_PADDING}" y="${iy + HEADER_HEIGHT - 8}" font-weight="bold" font-size="12" fill="$tx">${escapeXml(lo.header)}</text>""")
        sb.append("\n")

        var cy = iy + HEADER_HEIGHT + ENTITY_PADDING + ROW_HEIGHT - 4
        for (i in lo.names.indices) {
            val name = lo.names[i]
            val type = lo.types.getOrElse(i) { "" }
            val isPk = lo.pks.getOrElse(i) { false }
            val nx = ix + ENTITY_PADDING + (if (isPk) KEY_ICON_W else 0)
            if (isPk) {
                sb.append("""  <circle cx="${ix + ENTITY_PADDING + 5}" cy="${cy - 4}" r="4.5" fill="none" stroke="$pk" stroke-width="1.3"/>""")
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
            sb.append("""  <rect x="${l.x1 + 12}" y="${l.y1 - 9}" width="$fw" height="18" rx="5" fill="$bg" stroke="$bd" stroke-width="1.0"/>""")
            sb.append("\n")
            sb.append("""  <text x="${l.x1 + 17}" y="${l.y1 + 4}" font-size="10" fill="$lc">$ft</text>""")
            sb.append("\n")

            val tt = cardText(l.jt, true)
            val tw2 = tt.length * 6 + 10
            sb.append("""  <rect x="${l.x2 - 12 - tw2}" y="${l.y2 - 9}" width="$tw2" height="18" rx="5" fill="$bg" stroke="$bd" stroke-width="1.0"/>""")
            sb.append("\n")
            sb.append("""  <text x="${l.x2 - 7 - tw2}" y="${l.y2 + 4}" font-size="10" fill="$lc">$tt</text>""")
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
}
