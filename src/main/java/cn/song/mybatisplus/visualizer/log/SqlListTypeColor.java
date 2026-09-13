package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;

import java.awt.Color;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SQL 列表 CRUD 类型颜色标记配色门面（T-CrudColor，纯逻辑可单测）。
 *
 * 职责：把「类型 → 标记色」的映射与混色/对比度计算收在一处，供两个列表渲染器
 * （{@link LogEntryListCellRenderer} / {@link HistoryCellRenderer}）共用；设置态直读
 * {@link PluginSettings}（与既有 formatTimestampFragment / formatDurationFragment 同范式）。
 *
 * 标记形态（{@code PluginSettings.State.crudColorStyle}）：
 *  - {@link #STYLE_BAR}：左侧色条（默认）——选中态走平台原生填充，色条不受影响；
 *  - {@link #STYLE_BACKGROUND}：整行淡背景——选中态 = 保留类型色调（混色比例提高）+ 描边，不铺平台填充；
 *  - {@link #STYLE_OFF}：关闭，与改造前逐字节一致。
 *
 * 类型口径与 {@link LogStatementClassifier} 完全一致（即与类型过滤弹窗同源），保证
 * 「筛出来的 SELECT 行不会被标成红色」。OTHER 恒不标记。
 */
public final class SqlListTypeColor {

    private SqlListTypeColor() {
    }

    /** 左侧色条（默认）。 */
    public static final String STYLE_BAR = "bar";

    /** 整行淡背景（选中态走描边而非平台填充）。 */
    public static final String STYLE_BACKGROUND = "background";

    /** 关闭标记（现状行为）。 */
    public static final String STYLE_OFF = "off";

    /** 未选中行的混色比例（淡色调）。 */
    public static final double MIX_NORMAL = 0.14;

    /** 选中行的混色比例（同色相加深一档，与描边共同表达选中）。 */
    public static final double MIX_SELECTED = 0.30;

    /** 描边厚度（逻辑像素），安全区间 1..4——上限由渲染器 5px 上内边距约束。 */
    public static final int OUTLINE_WIDTH = 2;

    /**
     * 左侧类型色条宽度（px）。
     * 从左内边距中切分（渲染器左内边距 5 + 色条 3 = 原 8），故文本左缘像素位置与改造前一致。
     */
    public static final int BAR_WIDTH = 3;

    /**
     * 透明色——选中态内层行分隔线的<b>占位色</b>。
     * 占 1px inset 保证选中/未选中两状态 insets 逐边相等，但不绘制：否则灰线会画在描边环内部，
     * 把选中框的底边割断（2026-09-12 用户指出的观感缺陷）。
     */
    public static final Color TRANSPARENT = new Color(0, 0, 0, 0);

    public static final String DEFAULT_SELECT = "#4A86E8";
    public static final String DEFAULT_INSERT = "#4CAF50";
    public static final String DEFAULT_UPDATE = "#E67E22";
    public static final String DEFAULT_DELETE = "#D32F2F";

    /**
     * 暗色主题下描边色的最小亮度阈值——低于此值压在深色行底上不可见
     * （如 Darcula 选中蓝 #2E436E 亮度约 0.26，需提亮）。
     */
    private static final double DARK_MIN_BRIGHTNESS = 0.45;

    /** 提亮的最大倍率（防止低饱和色被拉爆成白）。 */
    private static final double MAX_BRIGHTEN_FACTOR = 2.4;

    // 默认色实例（预建，避免 defaultOf 每次解析/分配）
    private static final Color COLOR_SELECT = new Color(0x4A86E8);
    private static final Color COLOR_INSERT = new Color(0x4CAF50);
    private static final Color COLOR_UPDATE = new Color(0xE67E22);
    private static final Color COLOR_DELETE = new Color(0xD32F2F);

    /**
     * 解析结果缓存（每类型至多 1 条，共 ≤ 4 条）。
     *
     * <b>存在的理由不是省那一次字符串解析，而是省掉 Swing 的重绘副作用</b>：实测（JBR21）
     * {@code JComponent.setBackground(new Color(同值))} 会触发 {@code repaint() +1}，而传入<b>同一实例</b>则是 0。
     * 渲染器每格都要给色条/单元格设背景，若每次现解析就会每格多一次 repaint —— 这正是
     * T-LogListFreeze 那类「渲染热路径逐格副作用累积」问题的成因。缓存后同色格复用同一实例。
     */
    private static final ConcurrentHashMap<StatementType, Map.Entry<String, Color>> PARSED_CACHE =
            new ConcurrentHashMap<>();

    /** 类型 → 默认色（用户未配置 / 配置非法时的兜底）。 */
    public static Color defaultOf(StatementType type) {
        switch (type) {
            case SELECT:
                return COLOR_SELECT;
            case INSERT:
                return COLOR_INSERT;
            case UPDATE:
                return COLOR_UPDATE;
            case DELETE:
                return COLOR_DELETE;
            default:
                return COLOR_SELECT; // OTHER
        }
    }

    /** 当前样式（读设置薄壳）。 */
    public static String styleOf() {
        return styleOf(PluginSettings.getInstance().getState().getCrudColorStyle());
    }

    /**
     * <b>纯逻辑</b>：原始样式串 → 样式常量；未知值按 {@link #STYLE_BAR} 兜底
     * ——不留「配置写错就整列表失色」的口子。
     */
    public static String styleOf(String raw) {
        switch (raw) {
            case STYLE_BAR:
                return STYLE_BAR;
            case STYLE_BACKGROUND:
                return STYLE_BACKGROUND;
            case STYLE_OFF:
                return STYLE_OFF;
            default:
                return STYLE_BAR;
        }
    }

    /** 当前样式是否走平台选中填充（仅 {@link #STYLE_BAR} 为 true）。 */
    public static boolean usesSelectionFill(String style) {
        return STYLE_BAR.equals(style);
    }

    /**
     * 类型 → 标记色（读设置薄壳；色值来源见 {@link #hexOf}）。
     *
     * 关闭样式 / OTHER → null（不标记）；空串或非法值回退 {@link #defaultOf}（不抛异常、不写坏配置）。
     */
    public static Color colorOf(StatementType type) {
        return colorOf(type, styleOf(), hexOf(type));
    }

    /**
     * <b>纯逻辑</b>：类型 + 样式 + 色值 → 标记色（null = 不标记）。
     *
     * 与设置解耦，便于零平台依赖单测；{@link #colorOf(StatementType)} 只负责把设置态喂进来。
     */
    public static Color colorOf(StatementType type, String style, String hex) {
        if (STYLE_OFF.equals(style)) {
            return null;
        }
        if (type == StatementType.OTHER) {
            return null;
        }
        // 同色复用同一 Color 实例：既省分配，也避免 Swing setBackground 因「新实例同值」触发 repaint
        Map.Entry<String, Color> cached = PARSED_CACHE.get(type);
        if (cached != null && cached.getKey().equals(hex)) {
            return cached.getValue();
        }
        Color color = parseHex(hex, defaultOf(type));
        PARSED_CACHE.put(type, Map.entry(hex, color));
        return color;
    }

    /** 类型 → 设置中的色值（OTHER → 空串，走 {@link #defaultOf} 兜底）。 */
    public static String hexOf(StatementType type) {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        switch (type) {
            case SELECT:
                return state.getCrudColorSelect();
            case INSERT:
                return state.getCrudColorInsert();
            case UPDATE:
                return state.getCrudColorUpdate();
            case DELETE:
                return state.getCrudColorDelete();
            default:
                return ""; // OTHER
        }
    }

    /**
     * 解析 {@code #RRGGBB}（{@code #} 可省略）；非法 / 空 → fallback。
     *
     * 容错优先：设置项异常必须降级而非抛（与 formatTimestampFragment 对
     * logTimestampFormat 的 IllegalArgumentException 兜底同款）。
     */
    public static Color parseHex(String hex, Color fallback) {
        String raw = hex.trim();
        if (raw.startsWith("#")) {
            raw = raw.substring(1);
        }
        if (raw.length() != 6) {
            return fallback;
        }
        long value;
        try {
            value = Long.parseLong(raw, 16);
        } catch (NumberFormatException e) {
            return fallback;
        }
        return new Color(
                (int) ((value >> 16) & 0xFF),
                (int) ((value >> 8) & 0xFF),
                (int) (value & 0xFF));
    }

    /**
     * 按比例混色（base 为主）：{@code ratio = 0} 得 base，{@code 1} 得 tint。
     *
     * 用于整行淡背景——与列表当前背景混色即天然适配亮/暗主题，无需为两主题各配一套色值。
     */
    public static Color blend(Color base, Color tint, double ratio) {
        double r = Math.max(0.0, Math.min(1.0, ratio));
        return new Color(
                mixChannel(base.getRed(), tint.getRed(), r),
                mixChannel(base.getGreen(), tint.getGreen(), r),
                mixChannel(base.getBlue(), tint.getBlue(), r));
    }

    /**
     * 描边色对比度保障：暗色主题下低亮度色提亮到可见阈值，亮色主题原样返回。
     *
     * 背景色样式的选中行底色已被类型色调加深（如 UPDATE 暗色下约 #5A3B22），
     * 暗色 selectionBackground（#2E436E）压在其上几乎不可见——故描边色不能直接取原色。
     * 自研纯函数而非用 com.intellij.ui.ColorUtil.brighter：tones 换算语义不直观，
     * 断言需依赖平台实现细节，行为不可控。
     */
    public static Color ensureOutlineContrast(Color color, boolean isDark) {
        if (!isDark) {
            return color;
        }
        if (brightnessOf(color) >= DARK_MIN_BRIGHTNESS) {
            return color;
        }
        double factor = 1.0;
        Color candidate = color;
        while (factor < MAX_BRIGHTEN_FACTOR && brightnessOf(candidate) < DARK_MIN_BRIGHTNESS) {
            factor *= 1.1;
            candidate = scaleBrightness(color, factor);
        }
        return candidate;
    }

    /** 感知亮度（0..1，Rec.601 加权）。 */
    public static double brightnessOf(Color color) {
        return (0.299 * color.getRed() + 0.587 * color.getGreen() + 0.114 * color.getBlue()) / 255.0;
    }

    private static Color scaleBrightness(Color color, double factor) {
        return new Color(
                (int) Math.min(255.0, color.getRed() * factor),
                (int) Math.min(255.0, color.getGreen() * factor),
                (int) Math.min(255.0, color.getBlue() * factor));
    }

    /**
     * 逐通道线性插值——<b>显式 half-up</b>（{@code (v + 0.5).floor()}）。
     *
     * 不用 {@code Math.round}（语义同 Kotlin ties-to-even 教训）：
     * Kotlin 版注释——kotlin.math.round 采用 ties-to-even（银行家舍入），
     * {@code round(76.5) = 76} 与常规直觉相反，会让设计稿色值表出现 1/255 的意外偏移。
     */
    private static int mixChannel(int base, int tint, double ratio) {
        return (int) Math.floor(base + (tint - base) * ratio + 0.5);
    }
}
