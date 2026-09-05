package cn.song.mybatisplus.visualizer.ui.diagram

import cn.song.mybatisplus.visualizer.model.JoinType
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * F-1.1：JOIN 关系图 Swing 自绘引擎（IDEA 类图/ER 图风格）。
 *
 * 布局：主表居中上方，JOIN 表水平排列在下方（梳状拓扑）。
 * 连线：正交路由（T 型/梳状），标签紧贴水平段。
 * 卡片：白底 + 细边框 + 投影，列间分隔线，行交替底色，JOIN 类型标注。
 */
object DiagramRenderer {

    const val ENTITY_PADDING = 14
    const val HEADER_HEIGHT = 30
    const val ROW_HEIGHT = 22
    const val COLUMN_GAP = 100
    const val ROW_GAP = 80
    const val MARGIN = 56
    const val CARD_ARC = 6
    const val ARROW_LEN = 9
    const val ARROW_HW = 4
    const val MAX_CARD_WIDTH = 300
    const val MIN_CARD_WIDTH = 170
    const val MAX_TEXT_LEN = 60
    const val TYPE_GAP = 24
    const val KEY_ICON_W = 16
    const val JOIN_BADGE_PADDING = 6
    const val MAX_LABEL_CHARS = 48
    const val MIN_LABEL_WIDTH = 60
    const val MAX_LABEL_WIDTH = 300
    const val LABEL_LINE_GAP = 12
    const val LABEL_COLUMN_GAP = 24

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
    val JOIN_BADGE_BG = JBColor(Color(0xE8, 0xEA, 0xED), Color(0x3A, 0x3D, 0x42))
    val JOIN_BADGE_TEXT = JBColor(Color(0x55, 0x55, 0x55), Color(0x99, 0x99, 0x99))

    // ------------------------------------------------------------------
    // Layout data classes
    // ------------------------------------------------------------------

    data class Layout(
        val width: Int, val height: Int,
        val header: String, val joinBadge: String?,
        val names: List<String>, val types: List<String>, val pks: List<Boolean>,
    )

    data class Pos(val x: Int, val y: Int)

    /** 正交路由段：一系列折线点。 */
    data class OrthoPath(val points: List<Point>)

    data class Line(
        val fromAlias: String, val toAlias: String,
        val jt: JoinType, val label: String,
    )

    data class Size(val width: Int, val height: Int)

    /** ON 条件标签的布局信息（用于重叠检测与交错排列）。 */
    private data class LabelInfo(
        val lineIndex: Int, val bx: Int, val by: Int, val bw: Int, val bh: Int,
        val text: String, val splitY: Int,
    )

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // T-Perf-24：paint 期 measure+layout 缓存——键 = (model 同一性, 结构计数, 字体)。
    // 每帧 paint 多处重复 measure+layout（宽 SELECT 10 表×80 列 ≈ 1600 次字体度量/遍）
    // 是掉帧根因；命中后滚动/重绘零度量。换模型或主题字体变化自然失效。
    // ------------------------------------------------------------------
    private data class LayoutCacheKey(val modelId: Int, val shape: Int, val fontHash: Int, val boldHash: Int)

    @Volatile
    private var layoutCache: Pair<LayoutCacheKey, Pair<List<Layout>, List<Pos>>>? = null

    private fun cachedLayout(model: DiagramModel, font: Font, bold: Font, frc: FontRenderContext): Pair<List<Layout>, List<Pos>> {
        val key = LayoutCacheKey(
            System.identityHashCode(model),
            model.entities.size * 31 + model.relationships.size,
            font.hashCode(),
            bold.hashCode(),
        )
        layoutCache?.takeIf { it.first == key }?.let { return it.second }
        val (layouts, positions) = cachedLayout(model, font, bold, frc)
        layoutCache = key to (layouts to positions)
        return layouts to positions
    }

    /** Measure preferred display size (at 1x zoom). */
    fun measurePreferredSize(model: DiagramModel): Size {
        if (model.entities.isEmpty()) return Size(200, 100)
        val font = UIUtil.getLabelFont()
        val bold = font.deriveFont(Font.BOLD)
        val frc = FontRenderContext(null, true, true)
        val (layouts, positions) = cachedLayout(model, font, bold, frc)
        val pad = MARGIN + 16
        val totalW = contentWidth(model, layouts, positions, font, frc) + pad
        val totalH = positions.zip(layouts).maxOf { (p, l) -> p.y + l.height } + pad
        return Size(totalW, totalH)
    }

    /** Render to BufferedImage (for PNG export). */
    fun render(model: DiagramModel): BufferedImage {
        if (model.entities.isEmpty()) return BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val font = UIUtil.getLabelFont()
        val bold = font.deriveFont(Font.BOLD)
        val frc = FontRenderContext(null, true, true)
        val (layouts, positions) = cachedLayout(model, font, bold, frc)
        val pad = MARGIN + 16
        val totalW = contentWidth(model, layouts, positions, font, frc) + pad
        val totalH = positions.zip(layouts).maxOf { (p, l) -> p.y + l.height } + pad

        val img = BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()
        setupHints(g2)
        g2.color = UIUtil.getPanelBackground()
        g2.fillRect(0, 0, totalW, totalH)

        val lines = prepareLinesFor(model)
        drawOrthoLines(g2, lines, model, layouts, positions)
        drawCards(g2, model, font, bold)
        drawLabelsOnPath(g2, lines, model, layouts, positions, font, frc)

        g2.dispose()
        return img
    }

    /** Render to SVG string (for SVG export). */
    fun renderToSvg(model: DiagramModel): String {
        if (model.entities.isEmpty()) return """<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1"></svg>"""

        val font = UIUtil.getLabelFont()
        val bold = font.deriveFont(Font.BOLD)
        val frc = FontRenderContext(null, true, true)
        val (layouts, positions) = cachedLayout(model, font, bold, frc)
        val pad = MARGIN + 16
        val totalW = contentWidth(model, layouts, positions, font, frc) + pad
        val totalH = positions.zip(layouts).maxOf { (p, l) -> p.y + l.height } + pad

        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$totalW" height="$totalH">""").append("\n")

        val lines = prepareLinesFor(model)
        appendOrthoLinesSvg(sb, lines, model, layouts, positions)
        appendCardsSvg(sb, model, font, bold, frc)
        appendLabelsOnPathSvg(sb, lines, model, layouts, positions, font, frc)

        sb.append("</svg>\n")
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // Internal rendering (used by both render() and DiagramPanel canvas)
    // ------------------------------------------------------------------

    private fun setupHints(g2: Graphics2D) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    }

    private fun measureEntities(ents: List<EntityNode>, font: Font, bold: Font, frc: FontRenderContext): List<Layout> {
        return ents.map { e ->
            val badgeText = if (!e.isMain && e.joinType != null) " ${e.joinType.name} " else null
            val hdrBase = e.displayName
            val hdr = if (badgeText != null) truncate(hdrBase) else truncate(hdrBase)
            val badgeW = if (badgeText != null) bold.getStringBounds(badgeText, frc).width.toInt() + 2 * JOIN_BADGE_PADDING else 0
            val hdrW = bold.getStringBounds(hdr, frc).width.toInt() + 2 * ENTITY_PADDING + badgeW
            val names = e.columns.map { truncate(it.name) }
            val types = e.columns.map { truncate(it.type ?: "") }
            val pks = e.columns.map { it.isPk }
            val maxNameW = names.maxOfOrNull { font.getStringBounds(it, frc).width.toInt() } ?: 0
            val maxTypeW = types.maxOfOrNull { font.getStringBounds(it, frc).width.toInt() } ?: 0
            val contentW = max(hdrW, ENTITY_PADDING + KEY_ICON_W + maxNameW + TYPE_GAP + maxTypeW + ENTITY_PADDING)
            val cardW = contentW.coerceIn(MIN_CARD_WIDTH, MAX_CARD_WIDTH)
            val cardH = HEADER_HEIGHT + e.columns.size * ROW_HEIGHT + ENTITY_PADDING
            Layout(cardW, cardH, hdr, badgeText, names, types, pks)
        }
    }

    /**
     * 居中对称布局：主表居中上方，JOIN 表水平排列在下方。
     * 动态间距：根据 ON 标签实际渲染宽度调整相邻卡片间距，彻底避免标签重叠。
     *
     * ```
     *         ┌──────────┐
     *         │  主表    │
     *         └────┬─────┘
     *       ┌──────┼──────┐
     *       │      │      │
     *   ┌───┴──┐ ┌─┴───┐ ┌┴─────┐
     *   │ t1   │ │ t2  │ │ t3   │
     *   └──────┘ └─────┘ └──────┘
     * ```
     */
    private fun computeLayout(layouts: List<Layout>, model: DiagramModel, font: Font, frc: FontRenderContext): List<Pos> {
        if (layouts.isEmpty()) return emptyList()
        if (layouts.size == 1) return listOf(Pos(MARGIN, MARGIN))

        val main = layouts[0]
        val joinLayouts = layouts.drop(1)
        val maxLabelWidth = model.relationships
            .asSequence()
            .filter { it.label.isNotBlank() }
            .map { labelWidth(it.label, font, frc) }
            .maxOrNull() ?: 0
        val gap = maxOf(COLUMN_GAP, maxLabelWidth + LABEL_COLUMN_GAP + 48)
        val childX = MARGIN + main.width + gap
        val childY = MARGIN
        val childrenHeight = joinLayouts.sumOf { it.height } + (joinLayouts.size - 1) * ROW_GAP
        val mainY = childY + (childrenHeight - main.height) / 2

        val positions = mutableListOf(Pos(MARGIN, mainY))
        var y = childY
        for (layout in joinLayouts) {
            positions.add(Pos(childX, y))
            y += layout.height + ROW_GAP
        }
        return positions
    }

    private fun labelWidth(label: String, font: Font, frc: FontRenderContext): Int {
        val text = if (label.length > MAX_LABEL_CHARS) label.take(MAX_LABEL_CHARS - 1) + "\u2026" else label
        return (font.getStringBounds(text, frc).width.toInt() + 20).coerceIn(MIN_LABEL_WIDTH, MAX_LABEL_WIDTH)
    }

    private fun contentWidth(
        model: DiagramModel, layouts: List<Layout>, positions: List<Pos>,
        font: Font, frc: FontRenderContext,
    ): Int {
        val nodeRight = positions.zip(layouts).maxOf { (p, l) -> p.x + l.width }
        val labelRight = model.relationships.mapNotNull { relationship ->
            val target = model.entities.indexOfFirst { it.alias == relationship.toAlias }
            if (target < 0) return@mapNotNull null
            val path = computeOrthoPath(positions[0], layouts[0], positions[target], layouts[target]).points
            if (relationship.label.isBlank()) null else path[1].x + LABEL_LINE_GAP + labelWidth(relationship.label, font, frc)
        }.maxOrNull() ?: 0
        return max(nodeRight, labelRight)
    }

    /** Prepare line data for the model. */
    fun prepareLinesFor(model: DiagramModel): List<Line> {
        return model.relationships.map { r ->
            Line(
                fromAlias = r.fromAlias,
                toAlias = r.toAlias,
                jt = r.joinType,
                label = r.label,
            )
        }
    }

    /** Compute orthogonal path between main table and a child table. */
    private fun computeOrthoPath(
        mainPos: Pos, mainLayout: Layout,
        childPos: Pos, childLayout: Layout,
    ): OrthoPath {
        val splitX = mainPos.x + mainLayout.width + 24
        val mainCenterY = mainPos.y + mainLayout.height / 2
        val childCenterY = childPos.y + childLayout.height / 2
        return OrthoPath(
            listOf(
                Point(mainPos.x + mainLayout.width, mainCenterY),
                Point(splitX, mainCenterY),
                Point(splitX, childCenterY),
                Point(childPos.x, childCenterY),
            )
        )
    }

    /** Draw all entity cards. */
    fun drawCards(g2: Graphics2D, model: DiagramModel, font: Font, bold: Font) {
        val frc = g2.fontRenderContext
        val (layouts, positions) = cachedLayout(model, font, bold, frc)
        for ((i, e) in model.entities.withIndex()) {
            drawCard(g2, e, positions[i], layouts[i], font, bold)
        }
    }

    /** Draw orthogonal lines with arrows. */
    private fun drawOrthoLines(
        g2: Graphics2D, lines: List<Line>,
        model: DiagramModel, layouts: List<Layout>, positions: List<Pos>,
    ) {
        val byIdx = model.entities.withIndex().associate { (i, e) -> e.alias to i }
        g2.color = LINE_COLOR
        g2.stroke = BasicStroke(1.5f)

        for (line in lines) {
            val fi = byIdx[line.fromAlias] ?: continue
            val ti = byIdx[line.toAlias] ?: continue
            val path = computeOrthoPath(positions[fi], layouts[fi], positions[ti], layouts[ti])
            val pts = path.points

            // Draw polyline segments
            for (i in 0 until pts.size - 1) {
                g2.drawLine(pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y)
            }

            // Arrow at the end (pointing to child)
            if (pts.size >= 2) {
                val tip = pts.last()
                val from = pts[pts.size - 2]
                drawArrow(g2, tip.x, tip.y, from.x, from.y)
            }
        }
    }

    /** Canvas 专用关系线入口，复用与导出相同的布局算法。 */
    fun drawOrthoLinesForCanvas(g2: Graphics2D, model: DiagramModel, lines: List<Line>, font: Font, bold: Font) {
        val frc = g2.fontRenderContext
        val (layouts, positions) = cachedLayout(model, font, bold, frc)
        drawOrthoLines(g2, lines, model, layouts, positions)
    }

    /** Draw labels (ON condition + cardinality) positioned along orthogonal paths. */
    fun drawLabels(
        g2: Graphics2D, model: DiagramModel, lines: List<Line>,
        font: Font, frc: FontRenderContext,
    ) {
        val (layouts, positions) = cachedLayout(model, font, font.deriveFont(Font.BOLD), frc)
        drawLabelsOnPath(g2, lines, model, layouts, positions, font, frc)
    }

    private fun drawLabelsOnPath(
        g2: Graphics2D, lines: List<Line>,
        model: DiagramModel, layouts: List<Layout>, positions: List<Pos>,
        font: Font, frc: FontRenderContext,
    ) {
        val byIdx = model.entities.withIndex().associate { (i, e) -> e.alias to i }

        // 每条关系使用独立的标签行，避免标签互相覆盖或进入节点区域
        for ((idx, line) in lines.withIndex()) {
            val fi = byIdx[line.fromAlias] ?: continue
            val ti = byIdx[line.toAlias] ?: continue
            if (line.label.isBlank()) continue
            val pts = computeOrthoPath(positions[fi], layouts[fi], positions[ti], layouts[ti]).points
            val text = if (line.label.length > MAX_LABEL_CHARS) line.label.take(MAX_LABEL_CHARS - 1) + "\u2026" else line.label
            val bw = labelWidth(line.label, font, frc)
            val bh = (font.getStringBounds(text, frc).height + 12).toInt()
            val labelX = pts[1].x + LABEL_LINE_GAP
            val labelY = pts[1].y - bh / 2 + idx * (bh + LABEL_LINE_GAP)
            onLabel(g2, text, labelX, labelY, bw, bh, font)
        }

        // Second pass: cardinality labels
        for (line in lines) {
            val fi = byIdx[line.fromAlias] ?: continue
            val ti = byIdx[line.toAlias] ?: continue
            val fromText = cardText(line.jt, false)
            val toText = cardText(line.jt, true)
            val cbh = 20
            val fromW = font.getStringBounds(fromText, frc).width.toInt() + 16
            val toW = font.getStringBounds(toText, frc).width.toInt() + 16

            val mainBottom = positions[fi].y + layouts[fi].height
            badge(g2, fromText, positions[fi].x + layouts[fi].width / 2 - fromW / 2, mainBottom + 4, fromW, cbh, font)
            badge(g2, toText, positions[ti].x + layouts[ti].width / 2 - toW / 2, positions[ti].y - cbh - 4, toW, cbh, font)
        }
    }

    /**
     * 检测 ON 标签水平重叠，重叠的标签交替偏移到水平线上方/下方。
     * @return 调整后的 (x, y) 列表，与输入 onLabels 一一对应
     */
    private fun resolveOverlappingLabels(
        labels: List<LabelInfo>, font: Font, frc: FontRenderContext,
    ): List<Pair<Int, Int>> {
        if (labels.isEmpty()) return emptyList()
        val result = mutableListOf<Pair<Int, Int>>()
        // 已放置标签的水平区间 + 实际 Y（调整后）
        val placed = mutableListOf<Pair<IntRange, Int>>()

        for (info in labels) {
            val origX = info.bx
            val origY = info.by
            val xRange = origX..(origX + info.bw)

            // 检查与已放置标签的水平重叠
            var finalY = origY
            var overlaps = false
            for ((placedRange, placedY) in placed) {
                if (xRange.first <= placedRange.last && xRange.last >= placedRange.first) {
                    if (kotlin.math.abs(origY - placedY) < info.bh + 2) {
                        overlaps = true
                        break
                    }
                }
            }

            if (overlaps) {
                // 交替偏移：在水平线上方或下方
                val offset = if (result.size % 2 == 0) -(info.bh + 6) else (info.bh + 6)
                finalY = info.splitY - info.bh / 2 + offset
            }

            result.add(origX to finalY)
            placed.add(xRange to finalY)
        }
        return result
    }

    /** Draw labels for SVG (same logic as drawLabelsOnPath but appends to StringBuilder). */
    private fun appendLabelsOnPathSvg(
        sb: StringBuilder, lines: List<Line>,
        model: DiagramModel, layouts: List<Layout>, positions: List<Pos>,
        font: Font, frc: FontRenderContext,
    ) {
        val lc = hexOf(LINE_COLOR)
        val bg = hexOf(LABEL_BG)
        val bd = hexOf(LABEL_BORDER)
        val tx = hexOf(TEXT)

        val byIdx = model.entities.withIndex().associate { (i, e) -> e.alias to i }

        // First pass: compute ON label positions with overlap resolution
        val onLabels = mutableListOf<LabelInfo>()

        for ((idx, line) in lines.withIndex()) {
            val fi = byIdx[line.fromAlias] ?: continue
            val ti = byIdx[line.toAlias] ?: continue
            val path = computeOrthoPath(positions[fi], layouts[fi], positions[ti], layouts[ti])
            val pts = path.points

            if (line.label.isNotEmpty() && pts.size >= 3) {
                val hSegStart = pts[1]
                val hSegEnd = pts[2]
                val labelCenterX = (hSegStart.x + hSegEnd.x) / 2
                val labelY = hSegStart.y
                val text = truncate(line.label)
                val estW = text.length * 6 + 12
                val bh = 18
                val bx = labelCenterX - estW / 2
                val by = labelY - bh - LABEL_LINE_GAP - onLabels.size * (bh + LABEL_LINE_GAP)
                onLabels.add(LabelInfo(idx, bx, by, estW, bh, text, labelY))
            }
        }

        // Resolve overlaps using the same algorithm
        val resolved = resolveOverlappingLabels(onLabels, font, frc)

        // Second pass: draw everything
        var onLabelIdx = 0
        for (line in lines) {
            val fi = byIdx[line.fromAlias] ?: continue
            val ti = byIdx[line.toAlias] ?: continue

            // Cardinality badges
            val ft = cardText(line.jt, false)
            val fw = ft.length * 6 + 16
            val mainBottom = positions[fi].y + layouts[fi].height
            val fromBadgeX = positions[fi].x + layouts[fi].width / 2 - fw / 2
            sb.append("""  <rect x="$fromBadgeX" y="${mainBottom + 4}" width="$fw" height="18" rx="5" fill="$bg" stroke="$bd" stroke-width="1.0"/>""").append("\n")
            sb.append("""  <text x="${fromBadgeX + 8}" y="${mainBottom + 17}" font-size="10" fill="$lc">$ft</text>""").append("\n")

            val tt = cardText(line.jt, true)
            val tw2 = tt.length * 6 + 16
            val toBadgeX = positions[ti].x + layouts[ti].width / 2 - tw2 / 2
            sb.append("""  <rect x="$toBadgeX" y="${positions[ti].y - 22}" width="$tw2" height="18" rx="5" fill="$bg" stroke="$bd" stroke-width="1.0"/>""").append("\n")
            sb.append("""  <text x="${toBadgeX + 8}" y="${positions[ti].y - 9}" font-size="10" fill="$lc">$tt</text>""").append("\n")

            // ON label (with overlap resolution)
            val path = computeOrthoPath(positions[fi], layouts[fi], positions[ti], layouts[ti])
            val pts = path.points
            if (line.label.isNotEmpty() && pts.size >= 3 && onLabelIdx < resolved.size) {
                val info = onLabels[onLabelIdx]
                val (adjustedX, adjustedY) = resolved[onLabelIdx]
                onLabelIdx++
                sb.append("""  <rect x="$adjustedX" y="$adjustedY" width="${info.bw}" height="${info.bh}" rx="5" fill="$bg" stroke="$bd" stroke-width="1.0"/>""").append("\n")
                val textX = adjustedX + info.bw / 2
                val textY = adjustedY + info.bh / 2 + 4
                sb.append("""  <text x="$textX" y="$textY" font-size="10" text-anchor="middle" fill="$tx">${escapeXml(info.text)}</text>""").append("\n")
            }
        }
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

        // JOIN type badge (right side of header)
        if (lo.joinBadge != null) {
            val badgeW = bold.getStringBounds(lo.joinBadge, g2.fontRenderContext).width.toInt() + 2 * JOIN_BADGE_PADDING
            val badgeX = ix + iw - ENTITY_PADDING - badgeW
            val badgeY = iy + (HEADER_HEIGHT - 16) / 2
            g2.color = JOIN_BADGE_BG
            g2.fillRoundRect(badgeX, badgeY, badgeW, 16, 4, 4)
            g2.color = JOIN_BADGE_TEXT
            g2.font = font
            val ascent = font.getLineMetrics(lo.joinBadge, g2.fontRenderContext).ascent.toInt()
            g2.drawString(lo.joinBadge, badgeX + JOIN_BADGE_PADDING, badgeY + 2 + ascent)
        }

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

    private fun appendOrthoLinesSvg(
        sb: StringBuilder, lines: List<Line>,
        model: DiagramModel, layouts: List<Layout>, positions: List<Pos>,
    ) {
        val lc = hexOf(LINE_COLOR)
        val byIdx = model.entities.withIndex().associate { (i, e) -> e.alias to i }

        for (line in lines) {
            val fi = byIdx[line.fromAlias] ?: continue
            val ti = byIdx[line.toAlias] ?: continue
            val path = computeOrthoPath(positions[fi], layouts[fi], positions[ti], layouts[ti])
            val pts = path.points

            // Polyline segments
            for (i in 0 until pts.size - 1) {
                sb.append("""  <line x1="${pts[i].x}" y1="${pts[i].y}" x2="${pts[i + 1].x}" y2="${pts[i + 1].y}" stroke="$lc" stroke-width="1.5"/>""").append("\n")
            }

            // Arrow at end
            if (pts.size >= 2) {
                val tip = pts.last()
                val from = pts[pts.size - 2]
                val dx = (tip.x - from.x).toDouble()
                val dy = (tip.y - from.y).toDouble()
                val len = sqrt(dx * dx + dy * dy)
                if (len >= 1) {
                    val ux = dx / len; val uy = dy / len
                    val vx = -uy; val vy = ux
                    val p1x = tip.x - ux * ARROW_LEN + vx * ARROW_HW
                    val p1y = tip.y - uy * ARROW_LEN + vy * ARROW_HW
                    val p2x = tip.x - ux * ARROW_LEN - vx * ARROW_HW
                    val p2y = tip.y - uy * ARROW_LEN - vy * ARROW_HW
                    sb.append("""  <polygon points="${tip.x},${tip.y} $p1x,$p1y $p2x,$p2y" fill="$lc"/>""").append("\n")
                }
            }
        }
    }

    private fun appendCardsSvg(sb: StringBuilder, model: DiagramModel, font: Font, bold: Font, frc: FontRenderContext) {
        val bg = hexOf(CARD_BG)
        val bd = hexOf(CARD_BORDER)
        val tx = hexOf(TEXT)
        val ty = hexOf(TYPE_COLOR)
        val pk = hexOf(PK_COLOR)
        val hl = hexOf(HEADER_LINE)
        val sep = hexOf(SEPARATOR)
        val jbbg = hexOf(JOIN_BADGE_BG)
        val jbt = hexOf(JOIN_BADGE_TEXT)

        val (layouts, positions) = cachedLayout(model, font, bold, frc)

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

            // JOIN type badge
            if (lo.joinBadge != null) {
                val badgeW = lo.joinBadge.length * 7 + 2 * JOIN_BADGE_PADDING
                val badgeX = ix + iw - ENTITY_PADDING - badgeW
                val badgeY = iy + (HEADER_HEIGHT - 16) / 2
                sb.append("""  <rect x="$badgeX" y="$badgeY" width="$badgeW" height="16" rx="4" fill="$jbbg"/>""").append("\n")
                sb.append("""  <text x="${badgeX + JOIN_BADGE_PADDING}" y="${badgeY + 12}" font-size="10" fill="$jbt">${escapeXml(lo.joinBadge)}</text>""").append("\n")
            }

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
