package cn.song.mybatisplus.visualizer.ui.diagram;

import cn.song.mybatisplus.visualizer.model.JoinType;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * F-1.1：JOIN 关系图 Swing 自绘引擎（IDEA 类图/ER 图风格）。
 *
 * 布局：主表居中上方，JOIN 表水平排列在下方（梳状拓扑）。
 * 连线：正交路由（T 型/梳状），标签紧贴水平段。
 * 卡片：白底 + 细边框 + 投影，列间分隔线，行交替底色，JOIN 类型标注。
 *
 * 原 Kotlin object 转换而来：单例 INSTANCE 保留，方法提升为 public static。
 * 注意：DiagramModel/EntityNode/ColumnEntry/RelationshipLine 仍由同包 Kotlin 提供，
 * 故此处按 Kotlin 属性访问（getXxx()/isXxx()）调用。
 */
public final class DiagramRenderer {

    public static final DiagramRenderer INSTANCE = new DiagramRenderer();
    private DiagramRenderer() {}

    public static final int ENTITY_PADDING = 14;
    public static final int HEADER_HEIGHT = 30;
    public static final int ROW_HEIGHT = 22;
    public static final int COLUMN_GAP = 100;
    public static final int ROW_GAP = 80;
    public static final int MARGIN = 56;
    public static final int CARD_ARC = 6;
    public static final int ARROW_LEN = 9;
    public static final int ARROW_HW = 4;
    public static final int MAX_CARD_WIDTH = 300;
    public static final int MIN_CARD_WIDTH = 170;
    public static final int MAX_TEXT_LEN = 60;
    public static final int TYPE_GAP = 24;
    public static final int KEY_ICON_W = 16;
    public static final int JOIN_BADGE_PADDING = 6;
    public static final int MAX_LABEL_CHARS = 48;
    public static final int MIN_LABEL_WIDTH = 60;
    public static final int MAX_LABEL_WIDTH = 300;
    public static final int LABEL_LINE_GAP = 12;
    public static final int LABEL_COLUMN_GAP = 24;

    public static final JBColor SHADOW = new JBColor(new Color(0x00, 0x00, 0x00, 25), new Color(0x00, 0x00, 0x00, 45));
    public static final JBColor CARD_BG = new JBColor(new Color(0xFF, 0xFF, 0xFF), new Color(0x2D, 0x2F, 0x33));
    public static final JBColor CARD_BG_ALT = new JBColor(new Color(0xF7, 0xF8, 0xFA), new Color(0x2A, 0x2C, 0x30));
    public static final JBColor CARD_BORDER = new JBColor(new Color(0xC8, 0xCC, 0xD4), new Color(0x50, 0x53, 0x5A));
    public static final JBColor MAIN_HDR_BG = new JBColor(new Color(0xDC, 0xE8, 0xDC), new Color(0x2E, 0x3B, 0x2E));
    public static final JBColor JOIN_HDR_BG = new JBColor(new Color(0xDC, 0xE5, 0xED), new Color(0x2A, 0x30, 0x38));
    public static final JBColor HEADER_LINE = new JBColor(new Color(0xB8, 0xBC, 0xC4), new Color(0x4A, 0x4E, 0x56));
    public static final JBColor SEPARATOR = new JBColor(new Color(0xE4, 0xE6, 0xEA), new Color(0x3A, 0x3D, 0x42));
    public static final JBColor TEXT = new JBColor(new Color(0x1A, 0x1A, 0x1A), new Color(0xBB, 0xBB, 0xBB));
    public static final JBColor TYPE_COLOR = new JBColor(new Color(0x88, 0x88, 0x88), new Color(0x80, 0x80, 0x80));
    public static final JBColor PK_COLOR = new JBColor(new Color(0x9A, 0x68, 0x00), new Color(0xE2, 0xC0, 0x79));
    public static final JBColor LINE_COLOR = new JBColor(new Color(0x55, 0x55, 0x55), new Color(0x77, 0x77, 0x77));
    public static final JBColor LABEL_BG = new JBColor(new Color(0xFF, 0xFF, 0xFF), new Color(0x33, 0x36, 0x3B));
    public static final JBColor LABEL_BORDER = new JBColor(new Color(0xC0, 0xC0, 0xC0), new Color(0x50, 0x53, 0x5A));
    public static final JBColor JOIN_BADGE_BG = new JBColor(new Color(0xE8, 0xEA, 0xED), new Color(0x3A, 0x3D, 0x42));
    public static final JBColor JOIN_BADGE_TEXT = new JBColor(new Color(0x55, 0x55, 0x55), new Color(0x99, 0x99, 0x99));

    // ------------------------------------------------------------------
    // Layout data classes（本类内嵌套，等价 Kotlin 原 object 内的 data class）
    // ------------------------------------------------------------------

    /**
     * 单个实体卡片的度量结果。
     *
     * @param width      卡片宽度
     * @param height     卡片高度
     * @param header     标题文本（已截断）
     * @param joinBadge  JOIN 类型徽标文本（仅 JOIN 表，可为 null）
     * @param names      列名列表（已截断）
     * @param types      列类型列表（与 names 一一对应）
     * @param pks        是否主键列表（与 names 一一对应）
     */
    public static final class Layout {
    private final int width;
    private final int height;
    private final @NotNull String header;
    private final @Nullable String joinBadge;
    private final @NotNull List<String> names;
    private final @NotNull List<String> types;
    private final @NotNull List<Boolean> pks;

    public Layout(int width, int height, @NotNull String header, @Nullable String joinBadge, @NotNull List<String> names, @NotNull List<String> types, @NotNull List<Boolean> pks) {
        this.width = width;
        this.height = height;
        this.header = header;
        this.joinBadge = joinBadge;
        this.names = names;
        this.types = types;
        this.pks = pks;
    }

    public int getWidth() { return width; }
    public int width() { return width; }
    public int getHeight() { return height; }
    public int height() { return height; }
    public @NotNull String getHeader() { return header; }
    public @NotNull String header() { return header; }
    public @Nullable String getJoinBadge() { return joinBadge; }
    public @Nullable String joinBadge() { return joinBadge; }
    public @NotNull List<String> getNames() { return names; }
    public @NotNull List<String> names() { return names; }
    public @NotNull List<String> getTypes() { return types; }
    public @NotNull List<String> types() { return types; }
    public @NotNull List<Boolean> getPks() { return pks; }
    public @NotNull List<Boolean> pks() { return pks; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Layout)) return false;
        Layout that = (Layout) o;
        return java.util.Objects.equals(this.width, that.width) && java.util.Objects.equals(this.height, that.height) && java.util.Objects.equals(this.header, that.header) && java.util.Objects.equals(this.joinBadge, that.joinBadge) && java.util.Objects.equals(this.names, that.names) && java.util.Objects.equals(this.types, that.types) && java.util.Objects.equals(this.pks, that.pks);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(width, height, header, joinBadge, names, types, pks);
    }

    @Override
    public String toString() {
        return "Layout[" + "width=" + width + ", " + "height=" + height + ", " + "header=" + header + ", " + "joinBadge=" + joinBadge + ", " + "names=" + names + ", " + "types=" + types + ", " + "pks=" + pks + "]";
    }
}

    /**
     * 卡片在画布上的坐标。
     *
     * @param x 左上角 x
     * @param y 左上角 y
     */
    public static final class Pos {
    private final int x;
    private final int y;

    public Pos(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int getX() { return x; }
    public int x() { return x; }
    public int getY() { return y; }
    public int y() { return y; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Pos)) return false;
        Pos that = (Pos) o;
        return java.util.Objects.equals(this.x, that.x) && java.util.Objects.equals(this.y, that.y);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(x, y);
    }

    @Override
    public String toString() {
        return "Pos[" + "x=" + x + ", " + "y=" + y + "]";
    }
}

    /**
     * 正交路由段：一系列折线点。
     *
     * @param points 折线点列表
     */
    public static final class OrthoPath {
    private final @NotNull List<Point> points;

    public OrthoPath(@NotNull List<Point> points) {
        this.points = points;
    }

    public @NotNull List<Point> getPoints() { return points; }
    public @NotNull List<Point> points() { return points; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OrthoPath)) return false;
        OrthoPath that = (OrthoPath) o;
        return java.util.Objects.equals(this.points, that.points);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(points);
    }

    @Override
    public String toString() {
        return "OrthoPath[" + "points=" + points + "]";
    }
}

    /**
     * 关系线（用于绘制）。
     *
     * @param fromAlias 起始实体 alias（始终为主表）
     * @param toAlias   目标实体 alias（JOIN 表）
     * @param jt        JOIN 类型
     * @param label     关系线标签（ON 条件文本）
     */
    public static final class Line {
    private final @NotNull String fromAlias;
    private final @NotNull String toAlias;
    private final @NotNull JoinType jt;
    private final @NotNull String label;

    public Line(@NotNull String fromAlias, @NotNull String toAlias, @NotNull JoinType jt, @NotNull String label) {
        this.fromAlias = fromAlias;
        this.toAlias = toAlias;
        this.jt = jt;
        this.label = label;
    }

    public @NotNull String getFromAlias() { return fromAlias; }
    public @NotNull String fromAlias() { return fromAlias; }
    public @NotNull String getToAlias() { return toAlias; }
    public @NotNull String toAlias() { return toAlias; }
    public @NotNull JoinType getJt() { return jt; }
    public @NotNull JoinType jt() { return jt; }
    public @NotNull String getLabel() { return label; }
    public @NotNull String label() { return label; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Line)) return false;
        Line that = (Line) o;
        return java.util.Objects.equals(this.fromAlias, that.fromAlias) && java.util.Objects.equals(this.toAlias, that.toAlias) && java.util.Objects.equals(this.jt, that.jt) && java.util.Objects.equals(this.label, that.label);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(fromAlias, toAlias, jt, label);
    }

    @Override
    public String toString() {
        return "Line[" + "fromAlias=" + fromAlias + ", " + "toAlias=" + toAlias + ", " + "jt=" + jt + ", " + "label=" + label + "]";
    }
}

    /**
     * 画布尺寸。
     *
     * @param width  宽度
     * @param height 高度
     */
    public static final class Size {
    private final int width;
    private final int height;

    public Size(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int getWidth() { return width; }
    public int width() { return width; }
    public int getHeight() { return height; }
    public int height() { return height; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Size)) return false;
        Size that = (Size) o;
        return java.util.Objects.equals(this.width, that.width) && java.util.Objects.equals(this.height, that.height);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(width, height);
    }

    @Override
    public String toString() {
        return "Size[" + "width=" + width + ", " + "height=" + height + "]";
    }
}

    /** ON 条件标签的布局信息（用于重叠检测与交错排列）。 */
    private static final class LabelInfo {
    private final int lineIndex;
    private final int bx;
    private final int by;
    private final int bw;
    private final int bh;
    private final @NotNull String text;
    private final int splitY;

    public LabelInfo(int lineIndex, int bx, int by, int bw, int bh, @NotNull String text, int splitY) {
        this.lineIndex = lineIndex;
        this.bx = bx;
        this.by = by;
        this.bw = bw;
        this.bh = bh;
        this.text = text;
        this.splitY = splitY;
    }

    public int getLineIndex() { return lineIndex; }
    public int lineIndex() { return lineIndex; }
    public int getBx() { return bx; }
    public int bx() { return bx; }
    public int getBy() { return by; }
    public int by() { return by; }
    public int getBw() { return bw; }
    public int bw() { return bw; }
    public int getBh() { return bh; }
    public int bh() { return bh; }
    public @NotNull String getText() { return text; }
    public @NotNull String text() { return text; }
    public int getSplitY() { return splitY; }
    public int splitY() { return splitY; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LabelInfo)) return false;
        LabelInfo that = (LabelInfo) o;
        return java.util.Objects.equals(this.lineIndex, that.lineIndex) && java.util.Objects.equals(this.bx, that.bx) && java.util.Objects.equals(this.by, that.by) && java.util.Objects.equals(this.bw, that.bw) && java.util.Objects.equals(this.bh, that.bh) && java.util.Objects.equals(this.text, that.text) && java.util.Objects.equals(this.splitY, that.splitY);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(lineIndex, bx, by, bw, bh, text, splitY);
    }

    @Override
    public String toString() {
        return "LabelInfo[" + "lineIndex=" + lineIndex + ", " + "bx=" + bx + ", " + "by=" + by + ", " + "bw=" + bw + ", " + "bh=" + bh + ", " + "text=" + text + ", " + "splitY=" + splitY + "]";
    }
}

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // T-Perf-24：paint 期 measure+layout 缓存——键 = (model 同一性, 结构计数, 字体)。
    // 每帧 paint 多处重复 measure+layout（宽 SELECT 10 表×80 列 ≈ 1600 次字体度量/遍）
    // 是掉帧根因；命中后滚动/重绘零度量。换模型或主题字体变化自然失效。
    // ------------------------------------------------------------------
    private record LayoutCacheKey(int modelId, int shape, int fontHash, int boldHash) {
    }

    private record CachedLayout(List<Layout> layouts, List<Pos> positions) {
    }

    private record CacheEntry(LayoutCacheKey key, CachedLayout value) {
    }

    private static volatile CacheEntry layoutCache = null;

    private static CachedLayout cachedLayout(DiagramModel model, Font font, Font bold, FontRenderContext frc) {
        LayoutCacheKey key = new LayoutCacheKey(
                System.identityHashCode(model),
                model.getEntities().size() * 31 + model.getRelationships().size(),
                font.hashCode(),
                bold.hashCode());
        CacheEntry entry = layoutCache;
        if (entry != null && entry.key().equals(key)) return entry.value();
        List<Layout> layouts = measureEntities(model.getEntities(), font, bold, frc);
        List<Pos> positions = computeLayout(layouts, model, font, frc);
        layoutCache = new CacheEntry(key, new CachedLayout(layouts, positions));
        return new CachedLayout(layouts, positions);
    }

    /** Measure preferred display size (at 1x zoom). */
    public static Size measurePreferredSize(@NotNull DiagramModel model) {
        if (model.getEntities().isEmpty()) return new Size(200, 100);
        Font font = UIUtil.getLabelFont();
        Font bold = font.deriveFont(Font.BOLD);
        FontRenderContext frc = new FontRenderContext(null, true, true);
        CachedLayout cl = cachedLayout(model, font, bold, frc);
        List<Layout> layouts = cl.layouts();
        List<Pos> positions = cl.positions();
        int pad = MARGIN + 16;
        int totalW = contentWidth(model, layouts, positions, font, frc) + pad;
        int totalH = 0;
        for (int i = 0; i < positions.size(); i++) {
            totalH = Math.max(totalH, positions.get(i).y() + layouts.get(i).height());
        }
        totalH += pad;
        return new Size(totalW, totalH);
    }

    /** Render to BufferedImage (for PNG export). */
    public static BufferedImage render(@NotNull DiagramModel model) {
        if (model.getEntities().isEmpty()) return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Font font = UIUtil.getLabelFont();
        Font bold = font.deriveFont(Font.BOLD);
        FontRenderContext frc = new FontRenderContext(null, true, true);
        CachedLayout cl = cachedLayout(model, font, bold, frc);
        List<Layout> layouts = cl.layouts();
        List<Pos> positions = cl.positions();
        int pad = MARGIN + 16;
        int totalW = contentWidth(model, layouts, positions, font, frc) + pad;
        int totalH = 0;
        for (int i = 0; i < positions.size(); i++) {
            totalH = Math.max(totalH, positions.get(i).y() + layouts.get(i).height());
        }
        totalH += pad;

        BufferedImage img = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        setupHints(g2);
        g2.setColor(UIUtil.getPanelBackground());
        g2.fillRect(0, 0, totalW, totalH);

        List<Line> lines = prepareLinesFor(model);
        drawOrthoLines(g2, lines, model, layouts, positions);
        drawCards(g2, model, font, bold);
        drawLabelsOnPath(g2, lines, model, layouts, positions, font, frc);

        g2.dispose();
        return img;
    }

    /** Render to SVG string (for SVG export). */
    public static String renderToSvg(@NotNull DiagramModel model) {
        if (model.getEntities().isEmpty()) return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\"></svg>";

        Font font = UIUtil.getLabelFont();
        Font bold = font.deriveFont(Font.BOLD);
        FontRenderContext frc = new FontRenderContext(null, true, true);
        CachedLayout cl = cachedLayout(model, font, bold, frc);
        List<Layout> layouts = cl.layouts();
        List<Pos> positions = cl.positions();
        int pad = MARGIN + 16;
        int totalW = contentWidth(model, layouts, positions, font, frc) + pad;
        int totalH = 0;
        for (int i = 0; i < positions.size(); i++) {
            totalH = Math.max(totalH, positions.get(i).y() + layouts.get(i).height());
        }
        totalH += pad;

        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(totalW)
                .append("\" height=\"").append(totalH).append("\">\n");

        List<Line> lines = prepareLinesFor(model);
        appendOrthoLinesSvg(sb, lines, model, layouts, positions);
        appendCardsSvg(sb, model, font, bold, frc);
        appendLabelsOnPathSvg(sb, lines, model, layouts, positions, font, frc);

        sb.append("</svg>\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Internal rendering (used by both render() and DiagramPanel canvas)
    // ------------------------------------------------------------------

    private static void setupHints(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    private static List<Layout> measureEntities(List<EntityNode> ents, Font font, Font bold, FontRenderContext frc) {
        List<Layout> result = new ArrayList<>();
        for (EntityNode e : ents) {
            String badgeText = (!e.isMain() && e.getJoinType() != null) ? " " + e.getJoinType().name() + " " : null;
            String hdrBase = e.getDisplayName();
            String hdr = truncate(hdrBase);
            int badgeW = (badgeText != null)
                    ? (int) bold.getStringBounds(badgeText, frc).getWidth() + 2 * JOIN_BADGE_PADDING : 0;
            int hdrW = (int) bold.getStringBounds(hdr, frc).getWidth() + 2 * ENTITY_PADDING + badgeW;
            List<String> names = new ArrayList<>();
            for (ColumnEntry c : e.getColumns()) names.add(truncate(c.getName()));
            List<String> types = new ArrayList<>();
            for (ColumnEntry c : e.getColumns()) types.add(truncate(c.getType() != null ? c.getType() : ""));
            List<Boolean> pks = new ArrayList<>();
            for (ColumnEntry c : e.getColumns()) pks.add(c.isPk());
            int maxNameW = 0;
            for (String nm : names) {
                int w = (int) font.getStringBounds(nm, frc).getWidth();
                if (w > maxNameW) maxNameW = w;
            }
            int maxTypeW = 0;
            for (String tp : types) {
                int w = (int) font.getStringBounds(tp, frc).getWidth();
                if (w > maxTypeW) maxTypeW = w;
            }
            int contentW = Math.max(hdrW, ENTITY_PADDING + KEY_ICON_W + maxNameW + TYPE_GAP + maxTypeW + ENTITY_PADDING);
            int cardW = Math.min(Math.max(contentW, MIN_CARD_WIDTH), MAX_CARD_WIDTH);
            int cardH = HEADER_HEIGHT + e.getColumns().size() * ROW_HEIGHT + ENTITY_PADDING;
            result.add(new Layout(cardW, cardH, hdr, badgeText, names, types, pks));
        }
        return result;
    }

    /**
     * 居中对称布局：主表居中上方，JOIN 表水平排列在下方。
     * 动态间距：根据 ON 标签实际渲染宽度调整相邻卡片间距，彻底避免标签重叠。
     */
    private static List<Pos> computeLayout(List<Layout> layouts, DiagramModel model, Font font, FontRenderContext frc) {
        if (layouts.isEmpty()) return List.of();
        if (layouts.size() == 1) return List.of(new Pos(MARGIN, MARGIN));

        Layout main = layouts.get(0);
        List<Layout> joinLayouts = layouts.size() > 1 ? layouts.subList(1, layouts.size()) : java.util.List.of();
        int maxLabelWidth = 0;
        for (RelationshipLine rel : model.getRelationships()) {
            if (!rel.getLabel().isBlank()) {
                int w = labelWidth(rel.getLabel(), font, frc);
                if (w > maxLabelWidth) maxLabelWidth = w;
            }
        }
        int gap = Math.max(COLUMN_GAP, maxLabelWidth + LABEL_COLUMN_GAP + 48);
        int childX = MARGIN + main.width() + gap;
        int childY = MARGIN;
        int childrenHeight = 0;
        for (Layout lj : joinLayouts) childrenHeight += lj.height();
        childrenHeight += (joinLayouts.size() - 1) * ROW_GAP;
        int mainY = childY + (childrenHeight - main.height()) / 2;

        List<Pos> positions = new ArrayList<>();
        positions.add(new Pos(MARGIN, mainY));
        int y = childY;
        for (Layout layout : joinLayouts) {
            positions.add(new Pos(childX, y));
            y += layout.height() + ROW_GAP;
        }
        return positions;
    }

    private static int labelWidth(String label, Font font, FontRenderContext frc) {
        String text = label.length() > MAX_LABEL_CHARS ? label.substring(0, MAX_LABEL_CHARS - 1) + "\u2026" : label;
        return Math.min(Math.max((int) font.getStringBounds(text, frc).getWidth() + 20, MIN_LABEL_WIDTH), MAX_LABEL_WIDTH);
    }

    private static int contentWidth(
            DiagramModel model, List<Layout> layouts, List<Pos> positions,
            Font font, FontRenderContext frc) {
        int nodeRight = 0;
        for (int i = 0; i < positions.size(); i++) {
            int cand = positions.get(i).x() + layouts.get(i).width();
            if (cand > nodeRight) nodeRight = cand;
        }
        Integer labelRight = null;
        for (RelationshipLine relationship : model.getRelationships()) {
            int target = -1;
            for (int i = 0; i < model.getEntities().size(); i++) {
                if (model.getEntities().get(i).getAlias().equals(relationship.getToAlias())) {
                    target = i;
                    break;
                }
            }
            if (target < 0) continue;
            List<Point> pts = computeOrthoPath(
                    positions.get(0), layouts.get(0), positions.get(target), layouts.get(target)).points();
            if (relationship.getLabel().isBlank()) continue;
            int cand = pts.get(1).x + LABEL_LINE_GAP + labelWidth(relationship.getLabel(), font, frc);
            if (labelRight == null || cand > labelRight) labelRight = cand;
        }
        int lr = (labelRight != null) ? labelRight : 0;
        return Math.max(nodeRight, lr);
    }

    /** Prepare line data for the model. */
    public static List<Line> prepareLinesFor(@NotNull DiagramModel model) {
        List<Line> result = new ArrayList<>();
        for (RelationshipLine r : model.getRelationships()) {
            result.add(new Line(r.getFromAlias(), r.getToAlias(), r.getJoinType(), r.getLabel()));
        }
        return result;
    }

    /** Compute orthogonal path between main table and a child table. */
    private static OrthoPath computeOrthoPath(
            Pos mainPos, Layout mainLayout,
            Pos childPos, Layout childLayout) {
        int splitX = mainPos.x() + mainLayout.width() + 24;
        int mainCenterY = mainPos.y() + mainLayout.height() / 2;
        int childCenterY = childPos.y() + childLayout.height() / 2;
        return new OrthoPath(List.of(
                new Point(mainPos.x() + mainLayout.width(), mainCenterY),
                new Point(splitX, mainCenterY),
                new Point(splitX, childCenterY),
                new Point(childPos.x(), childCenterY)
        ));
    }

    /** Draw all entity cards. */
    public static void drawCards(Graphics2D g2, DiagramModel model, Font font, Font bold) {
        FontRenderContext frc = g2.getFontRenderContext();
        CachedLayout cl = cachedLayout(model, font, bold, frc);
        List<Layout> layouts = cl.layouts();
        List<Pos> positions = cl.positions();
        for (int i = 0; i < model.getEntities().size(); i++) {
            drawCard(g2, model.getEntities().get(i), positions.get(i), layouts.get(i), font, bold);
        }
    }

    /** Draw orthogonal lines with arrows. */
    private static void drawOrthoLines(
            Graphics2D g2, List<Line> lines,
            DiagramModel model, List<Layout> layouts, List<Pos> positions) {
        Map<String, Integer> byIdx = new HashMap<>();
        for (int i = 0; i < model.getEntities().size(); i++) byIdx.put(model.getEntities().get(i).getAlias(), i);
        g2.setColor(LINE_COLOR);
        g2.setStroke(new BasicStroke(1.5f));

        for (Line line : lines) {
            Integer fi = byIdx.get(line.fromAlias());
            if (fi == null) continue;
            Integer ti = byIdx.get(line.toAlias());
            if (ti == null) continue;
            OrthoPath path = computeOrthoPath(positions.get(fi), layouts.get(fi), positions.get(ti), layouts.get(ti));
            List<Point> pts = path.points();

            // Draw polyline segments
            for (int i = 0; i < pts.size() - 1; i++) {
                g2.drawLine(pts.get(i).x, pts.get(i).y, pts.get(i + 1).x, pts.get(i + 1).y);
            }

            // Arrow at the end (pointing to child)
            if (pts.size() >= 2) {
                Point tip = pts.get(pts.size() - 1);
                Point from = pts.get(pts.size() - 2);
                drawArrow(g2, tip.x, tip.y, from.x, from.y);
            }
        }
    }

    /** Canvas 专用关系线入口，复用与导出相同的布局算法。 */
    public static void drawOrthoLinesForCanvas(Graphics2D g2, DiagramModel model, List<Line> lines, Font font, Font bold) {
        FontRenderContext frc = g2.getFontRenderContext();
        CachedLayout cl = cachedLayout(model, font, bold, frc);
        List<Layout> layouts = cl.layouts();
        List<Pos> positions = cl.positions();
        drawOrthoLines(g2, lines, model, layouts, positions);
    }

    /** Draw labels (ON condition + cardinality) positioned along orthogonal paths. */
    public static void drawLabels(
            Graphics2D g2, DiagramModel model, List<Line> lines,
            Font font, FontRenderContext frc) {
        CachedLayout cl = cachedLayout(model, font, font.deriveFont(Font.BOLD), frc);
        List<Layout> layouts = cl.layouts();
        List<Pos> positions = cl.positions();
        drawLabelsOnPath(g2, lines, model, layouts, positions, font, frc);
    }

    private static void drawLabelsOnPath(
            Graphics2D g2, List<Line> lines,
            DiagramModel model, List<Layout> layouts, List<Pos> positions,
            Font font, FontRenderContext frc) {
        Map<String, Integer> byIdx = new HashMap<>();
        for (int i = 0; i < model.getEntities().size(); i++) byIdx.put(model.getEntities().get(i).getAlias(), i);

        // 每条关系使用独立的标签行，避免标签互相覆盖或进入节点区域
        for (int idx = 0; idx < lines.size(); idx++) {
            Line line = lines.get(idx);
            Integer fi = byIdx.get(line.fromAlias());
            if (fi == null) continue;
            Integer ti = byIdx.get(line.toAlias());
            if (ti == null) continue;
            if (line.label().isBlank()) continue;
            List<Point> pts = computeOrthoPath(
                    positions.get(fi), layouts.get(fi), positions.get(ti), layouts.get(ti)).points();
            String text = line.label().length() > MAX_LABEL_CHARS
                    ? line.label().substring(0, MAX_LABEL_CHARS - 1) + "\u2026" : line.label();
            int bw = labelWidth(line.label(), font, frc);
            int bh = (int) (font.getStringBounds(text, frc).getHeight() + 12);
            int labelX = pts.get(1).x + LABEL_LINE_GAP;
            int labelY = pts.get(1).y - bh / 2 + idx * (bh + LABEL_LINE_GAP);
            onLabel(g2, text, labelX, labelY, bw, bh, font);
        }

        // Second pass: cardinality labels
        for (Line line : lines) {
            Integer fi = byIdx.get(line.fromAlias());
            if (fi == null) continue;
            Integer ti = byIdx.get(line.toAlias());
            if (ti == null) continue;
            String fromText = cardText(line.jt(), false);
            String toText = cardText(line.jt(), true);
            int cbh = 20;
            int fromW = (int) font.getStringBounds(fromText, frc).getWidth() + 16;
            int toW = (int) font.getStringBounds(toText, frc).getWidth() + 16;

            int mainBottom = positions.get(fi).y() + layouts.get(fi).height();
            badge(g2, fromText, positions.get(fi).x() + layouts.get(fi).width() / 2 - fromW / 2, mainBottom + 4, fromW, cbh, font);
            badge(g2, toText, positions.get(ti).x() + layouts.get(ti).width() / 2 - toW / 2, positions.get(ti).y() - cbh - 4, toW, cbh, font);
        }
    }

    /**
     * 检测 ON 标签水平重叠，重叠的标签交替偏移到水平线上方/下方。
     *
     * @return 调整后的 (x, y) 列表，与输入 onLabels 一一对应
     */
    private static List<int[]> resolveOverlappingLabels(
            List<LabelInfo> labels, Font font, FontRenderContext frc) {
        if (labels.isEmpty()) return List.of();
        List<int[]> result = new ArrayList<>();
        // 已放置标签的水平区间 + 实际 Y（调整后）
        List<int[]> placed = new ArrayList<>();

        for (LabelInfo info : labels) {
            int origX = info.bx();
            int origY = info.by();
            int rangeStart = origX;
            int rangeEnd = origX + info.bw();

            // 检查与已放置标签的水平重叠
            int finalY = origY;
            boolean overlaps = false;
            for (int[] p : placed) {
                int pStart = p[0];
                int pEnd = p[1];
                int pY = p[2];
                if (rangeStart <= pEnd && rangeEnd >= pStart) {
                    if (Math.abs(origY - pY) < info.bh() + 2) {
                        overlaps = true;
                        break;
                    }
                }
            }

            if (overlaps) {
                // 交替偏移：在水平线上方或下方
                int offset = (result.size() % 2 == 0) ? -(info.bh() + 6) : (info.bh() + 6);
                finalY = info.splitY() - info.bh() / 2 + offset;
            }

            result.add(new int[]{origX, finalY});
            placed.add(new int[]{rangeStart, rangeEnd, finalY});
        }
        return result;
    }

    /** Draw labels for SVG (same logic as drawLabelsOnPath but appends to StringBuilder). */
    private static void appendLabelsOnPathSvg(
            StringBuilder sb, List<Line> lines,
            DiagramModel model, List<Layout> layouts, List<Pos> positions,
            Font font, FontRenderContext frc) {
        String lc = hexOf(LINE_COLOR);
        String bg = hexOf(LABEL_BG);
        String bd = hexOf(LABEL_BORDER);
        String tx = hexOf(TEXT);

        Map<String, Integer> byIdx = new HashMap<>();
        for (int i = 0; i < model.getEntities().size(); i++) byIdx.put(model.getEntities().get(i).getAlias(), i);

        // First pass: compute ON label positions with overlap resolution
        List<LabelInfo> onLabels = new ArrayList<>();

        for (int idx = 0; idx < lines.size(); idx++) {
            Line line = lines.get(idx);
            Integer fi = byIdx.get(line.fromAlias());
            if (fi == null) continue;
            Integer ti = byIdx.get(line.toAlias());
            if (ti == null) continue;
            OrthoPath path = computeOrthoPath(positions.get(fi), layouts.get(fi), positions.get(ti), layouts.get(ti));
            List<Point> pts = path.points();

            if (!line.label().isEmpty() && pts.size() >= 3) {
                Point hSegStart = pts.get(1);
                Point hSegEnd = pts.get(2);
                int labelCenterX = (hSegStart.x + hSegEnd.x) / 2;
                int labelY = hSegStart.y;
                String text = truncate(line.label());
                int estW = text.length() * 6 + 12;
                int bh = 18;
                int bx = labelCenterX - estW / 2;
                int by = labelY - bh - LABEL_LINE_GAP - onLabels.size() * (bh + LABEL_LINE_GAP);
                onLabels.add(new LabelInfo(idx, bx, by, estW, bh, text, labelY));
            }
        }

        // Resolve overlaps using the same algorithm
        List<int[]> resolved = resolveOverlappingLabels(onLabels, font, frc);

        // Second pass: draw everything
        int onLabelIdx = 0;
        for (Line line : lines) {
            Integer fi = byIdx.get(line.fromAlias());
            if (fi == null) continue;
            Integer ti = byIdx.get(line.toAlias());
            if (ti == null) continue;

            // Cardinality badges
            String ft = cardText(line.jt(), false);
            int fw = ft.length() * 6 + 16;
            int mainBottom = positions.get(fi).y() + layouts.get(fi).height();
            int fromBadgeX = positions.get(fi).x() + layouts.get(fi).width() / 2 - fw / 2;
            sb.append("  <rect x=\"").append(fromBadgeX).append("\" y=\"").append(mainBottom + 4)
                    .append("\" width=\"").append(fw).append("\" height=\"18\" rx=\"5\" fill=\"").append(bg)
                    .append("\" stroke=\"").append(bd).append("\" stroke-width=\"1.0\"/>\n");
            sb.append("  <text x=\"").append(fromBadgeX + 8).append("\" y=\"").append(mainBottom + 17)
                    .append("\" font-size=\"10\" fill=\"").append(lc).append("\">").append(ft).append("</text>\n");

            String tt = cardText(line.jt(), true);
            int tw2 = tt.length() * 6 + 16;
            int toBadgeX = positions.get(ti).x() + layouts.get(ti).width() / 2 - tw2 / 2;
            sb.append("  <rect x=\"").append(toBadgeX).append("\" y=\"").append(positions.get(ti).y() - 22)
                    .append("\" width=\"").append(tw2).append("\" height=\"18\" rx=\"5\" fill=\"").append(bg)
                    .append("\" stroke=\"").append(bd).append("\" stroke-width=\"1.0\"/>\n");
            sb.append("  <text x=\"").append(toBadgeX + 8).append("\" y=\"").append(positions.get(ti).y() - 9)
                    .append("\" font-size=\"10\" fill=\"").append(lc).append("\">").append(tt).append("</text>\n");

            // ON label (with overlap resolution)
            OrthoPath path = computeOrthoPath(positions.get(fi), layouts.get(fi), positions.get(ti), layouts.get(ti));
            List<Point> pts = path.points();
            if (!line.label().isEmpty() && pts.size() >= 3 && onLabelIdx < resolved.size()) {
                LabelInfo info = onLabels.get(onLabelIdx);
                int[] res = resolved.get(onLabelIdx);
                int adjustedX = res[0];
                int adjustedY = res[1];
                onLabelIdx++;
                sb.append("  <rect x=\"").append(adjustedX).append("\" y=\"").append(adjustedY)
                        .append("\" width=\"").append(info.bw()).append("\" height=\"").append(info.bh())
                        .append("\" rx=\"5\" fill=\"").append(bg).append("\" stroke=\"").append(bd)
                        .append("\" stroke-width=\"1.0\"/>\n");
                int textX = adjustedX + info.bw() / 2;
                int textY = adjustedY + info.bh() / 2 + 4;
                sb.append("  <text x=\"").append(textX).append("\" y=\"").append(textY)
                        .append("\" font-size=\"10\" text-anchor=\"middle\" fill=\"").append(tx).append("\">")
                        .append(escapeXml(info.text())).append("</text>\n");
            }
        }
    }

    private static int computeMinGap(DiagramModel model, Font font, FontRenderContext frc) {
        int minGap = COLUMN_GAP;
        for (RelationshipLine r : model.getRelationships()) {
            int needed = COLUMN_GAP;
            if (!r.getLabel().isEmpty()) {
                String text = r.getLabel().length() > 120 ? r.getLabel().substring(0, 119) + "\u2026" : r.getLabel();
                int tw = (int) font.getStringBounds(text, frc).getWidth() + 40;
                if (tw > needed) needed = tw;
            }
            needed = Math.min(needed + 120, 520);
            if (needed > minGap) minGap = needed;
        }
        return minGap;
    }

    private static void drawArrow(Graphics2D g2, int tipX, int tipY, int fromX, int fromY) {
        double dx = tipX - fromX;
        double dy = tipY - fromY;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1) return;
        double ux = dx / len;
        double uy = dy / len;
        double vx = -uy;
        double vy = ux;
        Path2D.Double p = new Path2D.Double();
        p.moveTo(tipX, tipY);
        p.lineTo(tipX - ux * ARROW_LEN + vx * ARROW_HW, tipY - uy * ARROW_LEN + vy * ARROW_HW);
        p.lineTo(tipX - ux * ARROW_LEN - vx * ARROW_HW, tipY - uy * ARROW_LEN - vy * ARROW_HW);
        p.closePath();
        g2.fill(p);
    }

    private static void drawCard(
            Graphics2D g2, EntityNode e, Pos pos, Layout lo,
            Font font, Font bold) {
        int ix = pos.x();
        int iy = pos.y();
        int iw = lo.width();
        int ih = lo.height();

        // Shadow
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.05f));
        g2.setColor(Color.BLACK);
        g2.fillRoundRect(ix + 3, iy + 3, iw, ih, CARD_ARC, CARD_ARC);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));

        int colAreaY = iy + HEADER_HEIGHT;

        // Alternating row backgrounds
        for (int i = 0; i < lo.names().size(); i++) {
            int rowY = colAreaY + i * ROW_HEIGHT;
            if (i % 2 == 1) {
                g2.setColor(CARD_BG_ALT);
                g2.fillRect(ix, rowY, iw, ROW_HEIGHT);
            }
        }

        // Card body
        g2.setColor(CARD_BG);
        g2.fillRoundRect(ix, iy, iw, ih, CARD_ARC, CARD_ARC);

        // Header
        Color hdrColor = e.isMain() ? MAIN_HDR_BG : JOIN_HDR_BG;
        g2.setColor(hdrColor);
        g2.fillRoundRect(ix, iy, iw, HEADER_HEIGHT, CARD_ARC, CARD_ARC);
        g2.fillRect(ix, iy + HEADER_HEIGHT - CARD_ARC, iw, CARD_ARC);

        // Header bottom separator
        g2.setColor(HEADER_LINE);
        g2.setStroke(new BasicStroke(1.0f));
        g2.drawLine(ix + ENTITY_PADDING, iy + HEADER_HEIGHT, ix + iw - ENTITY_PADDING, iy + HEADER_HEIGHT);

        // Column separators
        g2.setColor(SEPARATOR);
        int sepY = colAreaY;
        for (int i = 0; i < lo.names().size() - 1; i++) {
            sepY += ROW_HEIGHT;
            g2.drawLine(ix + ENTITY_PADDING, sepY, ix + iw - ENTITY_PADDING, sepY);
        }

        // Card border
        g2.setColor(CARD_BORDER);
        g2.setStroke(new BasicStroke(1.0f));
        g2.drawRoundRect(ix, iy, iw, ih, CARD_ARC, CARD_ARC);

        // Title text
        g2.setFont(bold);
        g2.setColor(TEXT);
        g2.drawString(lo.header(), ix + ENTITY_PADDING, iy + HEADER_HEIGHT - 9);

        // JOIN type badge (right side of header)
        if (lo.joinBadge() != null) {
            int badgeW = (int) bold.getStringBounds(lo.joinBadge(), g2.getFontRenderContext()).getWidth() + 2 * JOIN_BADGE_PADDING;
            int badgeX = ix + iw - ENTITY_PADDING - badgeW;
            int badgeY = iy + (HEADER_HEIGHT - 16) / 2;
            g2.setColor(JOIN_BADGE_BG);
            g2.fillRoundRect(badgeX, badgeY, badgeW, 16, 4, 4);
            g2.setColor(JOIN_BADGE_TEXT);
            g2.setFont(font);
            int ascent = (int) font.getLineMetrics(lo.joinBadge(), g2.getFontRenderContext()).getAscent();
            g2.drawString(lo.joinBadge(), badgeX + JOIN_BADGE_PADDING, badgeY + 2 + ascent);
        }

        // Columns
        g2.setFont(font);
        int cy = colAreaY + ENTITY_PADDING + ROW_HEIGHT - 5;
        for (int i = 0; i < lo.names().size(); i++) {
            String name = lo.names().get(i);
            String type = i < lo.types().size() ? lo.types().get(i) : "";
            boolean isPk = i < lo.pks().size() && lo.pks().get(i);

            if (isPk) {
                g2.setColor(PK_COLOR);
                drawKey(g2, ix + ENTITY_PADDING, cy - 9, 11, 11);
            }

            g2.setColor(TEXT);
            g2.drawString(name, ix + ENTITY_PADDING + (isPk ? KEY_ICON_W : 0), cy);

            if (!type.isEmpty()) {
                g2.setColor(TYPE_COLOR);
                int tw = (int) font.getStringBounds(type, g2.getFontRenderContext()).getWidth();
                g2.drawString(type, ix + iw - ENTITY_PADDING - tw, cy);
            }
            cy += ROW_HEIGHT;
        }
    }

    private static void drawKey(Graphics2D g2, int x, int y, int w, int h) {
        g2.setStroke(new BasicStroke(1.2f));
        int r = w / 3;
        g2.drawOval(x, y, r * 2, r * 2);
        g2.drawLine(x + r * 2, y + r, x + w, y + r);
        g2.drawLine(x + w - 3, y + r, x + w - 3, y + h - 2);
    }

    private static void badge(Graphics2D g2, String text, int x, int y, int w, int h, Font font) {
        g2.setColor(LABEL_BG);
        g2.fillRoundRect(x, y, w, h, 5, 5);
        g2.setColor(LABEL_BORDER);
        g2.setStroke(new BasicStroke(1.0f));
        g2.drawRoundRect(x, y, w, h, 5, 5);
        g2.setColor(LINE_COLOR);
        g2.setFont(font);
        int ascent = (int) font.getLineMetrics(text, g2.getFontRenderContext()).getAscent();
        g2.drawString(text, x + 8, y + 3 + ascent);
    }

    private static void onLabel(Graphics2D g2, String text, int bx, int by, int bw, int bh, Font font) {
        g2.setColor(LABEL_BG);
        g2.fillRoundRect(bx, by, bw, bh, 6, 6);
        g2.setColor(LABEL_BORDER);
        g2.setStroke(new BasicStroke(1.0f));
        g2.drawRoundRect(bx, by, bw, bh, 6, 6);
        g2.setColor(TEXT);
        g2.setFont(font);
        int ascent = (int) font.getLineMetrics(text, g2.getFontRenderContext()).getAscent();
        g2.drawString(text, bx + 10, by + 4 + ascent);
    }

    // ------------------------------------------------------------------
    // SVG output helpers
    // ------------------------------------------------------------------

    private static void appendOrthoLinesSvg(
            StringBuilder sb, List<Line> lines,
            DiagramModel model, List<Layout> layouts, List<Pos> positions) {
        String lc = hexOf(LINE_COLOR);
        Map<String, Integer> byIdx = new HashMap<>();
        for (int i = 0; i < model.getEntities().size(); i++) byIdx.put(model.getEntities().get(i).getAlias(), i);

        for (Line line : lines) {
            Integer fi = byIdx.get(line.fromAlias());
            if (fi == null) continue;
            Integer ti = byIdx.get(line.toAlias());
            if (ti == null) continue;
            OrthoPath path = computeOrthoPath(positions.get(fi), layouts.get(fi), positions.get(ti), layouts.get(ti));
            List<Point> pts = path.points();

            // Polyline segments
            for (int i = 0; i < pts.size() - 1; i++) {
                sb.append("  <line x1=\"").append(pts.get(i).x).append("\" y1=\"").append(pts.get(i).y)
                        .append("\" x2=\"").append(pts.get(i + 1).x).append("\" y2=\"").append(pts.get(i + 1).y)
                        .append("\" stroke=\"").append(lc).append("\" stroke-width=\"1.5\"/>\n");
            }

            // Arrow at end
            if (pts.size() >= 2) {
                Point tip = pts.get(pts.size() - 1);
                Point from = pts.get(pts.size() - 2);
                double dx = tip.x - from.x;
                double dy = tip.y - from.y;
                double len = Math.sqrt(dx * dx + dy * dy);
                if (len >= 1) {
                    double ux = dx / len;
                    double uy = dy / len;
                    double vx = -uy;
                    double vy = ux;
                    double p1x = tip.x - ux * ARROW_LEN + vx * ARROW_HW;
                    double p1y = tip.y - uy * ARROW_LEN + vy * ARROW_HW;
                    double p2x = tip.x - ux * ARROW_LEN - vx * ARROW_HW;
                    double p2y = tip.y - uy * ARROW_LEN - vy * ARROW_HW;
                    sb.append("  <polygon points=\"").append(tip.x).append(",").append(tip.y)
                            .append(" ").append(p1x).append(",").append(p1y)
                            .append(" ").append(p2x).append(",").append(p2y)
                            .append("\" fill=\"").append(lc).append("\"/>\n");
                }
            }
        }
    }

    private static void appendCardsSvg(StringBuilder sb, DiagramModel model, Font font, Font bold, FontRenderContext frc) {
        String bg = hexOf(CARD_BG);
        String bd = hexOf(CARD_BORDER);
        String tx = hexOf(TEXT);
        String ty = hexOf(TYPE_COLOR);
        String pk = hexOf(PK_COLOR);
        String hl = hexOf(HEADER_LINE);
        String sep = hexOf(SEPARATOR);
        String jbbg = hexOf(JOIN_BADGE_BG);
        String jbt = hexOf(JOIN_BADGE_TEXT);

        CachedLayout cl = cachedLayout(model, font, bold, frc);
        List<Layout> layouts = cl.layouts();
        List<Pos> positions = cl.positions();

        for (int i = 0; i < model.getEntities().size(); i++) {
            EntityNode e = model.getEntities().get(i);
            Pos pos = positions.get(i);
            Layout lo = layouts.get(i);
            int ix = pos.x();
            int iy = pos.y();
            int iw = lo.width();
            int ih = lo.height();
            String hdr = hexOf(e.isMain() ? MAIN_HDR_BG : JOIN_HDR_BG);
            int colAreaY = iy + HEADER_HEIGHT;

            sb.append("  <rect x=\"").append(ix).append("\" y=\"").append(iy)
                    .append("\" width=\"").append(iw).append("\" height=\"").append(ih)
                    .append("\" rx=\"").append(CARD_ARC).append("\" fill=\"").append(bg)
                    .append("\" stroke=\"").append(bd).append("\" stroke-width=\"1.0\"/>\n");
            sb.append("  <rect x=\"").append(ix).append("\" y=\"").append(iy)
                    .append("\" width=\"").append(iw).append("\" height=\"").append(HEADER_HEIGHT)
                    .append("\" rx=\"").append(CARD_ARC).append("\" fill=\"").append(hdr).append("\"/>\n");
            sb.append("  <rect x=\"").append(ix).append("\" y=\"").append(iy + HEADER_HEIGHT - CARD_ARC)
                    .append("\" width=\"").append(iw).append("\" height=\"").append(CARD_ARC)
                    .append("\" fill=\"").append(hdr).append("\"/>\n");
            sb.append("  <line x1=\"").append(ix + ENTITY_PADDING).append("\" y1=\"").append(iy + HEADER_HEIGHT)
                    .append("\" x2=\"").append(ix + iw - ENTITY_PADDING).append("\" y2=\"").append(iy + HEADER_HEIGHT)
                    .append("\" stroke=\"").append(hl).append("\" stroke-width=\"1.0\"/>\n");

            int sepY = colAreaY;
            for (int j = 0; j < lo.names().size() - 1; j++) {
                sepY += ROW_HEIGHT;
                sb.append("  <line x1=\"").append(ix + ENTITY_PADDING).append("\" y1=\"").append(sepY)
                        .append("\" x2=\"").append(ix + iw - ENTITY_PADDING).append("\" y2=\"").append(sepY)
                        .append("\" stroke=\"").append(sep).append("\" stroke-width=\"0.8\"/>\n");
            }

            sb.append("  <text x=\"").append(ix + ENTITY_PADDING).append("\" y=\"").append(iy + HEADER_HEIGHT - 9)
                    .append("\" font-weight=\"bold\" font-size=\"12\" fill=\"").append(tx).append("\">")
                    .append(escapeXml(lo.header())).append("</text>\n");

            // JOIN type badge
            if (lo.joinBadge() != null) {
                int badgeW = lo.joinBadge().length() * 7 + 2 * JOIN_BADGE_PADDING;
                int badgeX = ix + iw - ENTITY_PADDING - badgeW;
                int badgeY = iy + (HEADER_HEIGHT - 16) / 2;
                sb.append("  <rect x=\"").append(badgeX).append("\" y=\"").append(badgeY)
                        .append("\" width=\"").append(badgeW).append("\" height=\"16\" rx=\"4\" fill=\"").append(jbbg).append("\"/>\n");
                sb.append("  <text x=\"").append(badgeX + JOIN_BADGE_PADDING).append("\" y=\"").append(badgeY + 12)
                        .append("\" font-size=\"10\" fill=\"").append(jbt).append("\">").append(escapeXml(lo.joinBadge())).append("</text>\n");
            }

            int cy = colAreaY + ENTITY_PADDING + ROW_HEIGHT - 5;
            for (int j = 0; j < lo.names().size(); j++) {
                String name = lo.names().get(j);
                String type = j < lo.types().size() ? lo.types().get(j) : "";
                boolean isPk = j < lo.pks().size() && lo.pks().get(j);
                int nx = ix + ENTITY_PADDING + (isPk ? KEY_ICON_W : 0);
                if (isPk) {
                    sb.append("  <circle cx=\"").append(ix + ENTITY_PADDING + 5).append("\" cy=\"").append(cy - 4)
                            .append("\" r=\"5\" fill=\"none\" stroke=\"").append(pk).append("\" stroke-width=\"1.2\"/>\n");
                }
                sb.append("  <text x=\"").append(nx).append("\" y=\"").append(cy)
                        .append("\" font-size=\"12\" fill=\"").append(tx).append("\">").append(escapeXml(name)).append("</text>\n");
                if (!type.isEmpty()) {
                    int estW = type.length() * 6;
                    sb.append("  <text x=\"").append(ix + iw - ENTITY_PADDING - estW).append("\" y=\"").append(cy)
                            .append("\" font-size=\"11\" fill=\"").append(ty).append("\">").append(escapeXml(type)).append("</text>\n");
                }
                cy += ROW_HEIGHT;
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String cardText(JoinType jt, boolean target) {
        return switch (jt) {
            case INNER -> target ? "1" : "1";
            case LEFT -> target ? "0..*" : "1";
            case RIGHT -> target ? "1" : "0..*";
            case FULL -> target ? "0..*" : "0..*";
        };
    }

    private static String truncate(String s) {
        return s.length() > MAX_TEXT_LEN ? s.substring(0, MAX_TEXT_LEN - 1) + "\u2026" : s;
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String hexOf(Color c) {
        if (c.getAlpha() < 255) {
            return String.format("#%02x%02x%02x%02x", c.getAlpha(), c.getRed(), c.getGreen(), c.getBlue());
        } else {
            return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
        }
    }
}
