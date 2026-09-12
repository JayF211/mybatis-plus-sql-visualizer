package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import java.awt.Color

/**
 * SQL 列表 CRUD 类型颜色标记配色门面（T-CrudColor，纯逻辑可单测）。
 *
 * 职责：把「类型 → 标记色」的映射与混色/对比度计算收在一处，供两个列表渲染器
 * （[LogEntryListCellRenderer] / [HistoryCellRenderer]）共用；设置态直读
 * [PluginSettings]（与既有 `formatTimestampFragment` / `formatDurationFragment` 同范式）。
 *
 * 标记形态（[PluginSettings.State.crudColorStyle]）：
 *  - [STYLE_BAR]：左侧色条（默认）——选中态走平台原生填充，色条不受影响；
 *  - [STYLE_BACKGROUND]：整行淡背景——选中态 = 保留类型色调（混色比例提高）+ 描边，不铺平台填充；
 *  - [STYLE_OFF]：关闭，与改造前逐字节一致。
 *
 * 类型口径与 [LogStatementClassifier] 完全一致（即与类型过滤弹窗同源），保证
 * 「筛出来的 SELECT 行不会被标成红色」。OTHER 恒不标记。
 */
object SqlListTypeColor {

    /** 左侧色条（默认）。 */
    const val STYLE_BAR = "bar"

    /** 整行淡背景（选中态走描边而非平台填充）。 */
    const val STYLE_BACKGROUND = "background"

    /** 关闭标记（现状行为）。 */
    const val STYLE_OFF = "off"

    /** 未选中行的混色比例（淡色调）。 */
    const val MIX_NORMAL = 0.14

    /** 选中行的混色比例（同色相加深一档，与描边共同表达选中）。 */
    const val MIX_SELECTED = 0.30

    /** 描边厚度（逻辑像素），安全区间 1..4——上限由渲染器 5px 上内边距约束。 */
    const val OUTLINE_WIDTH = 2

    /**
     * 左侧类型色条宽度（px）。
     * 从左内边距中切分（渲染器左内边距 5 + 色条 3 = 原 8），故文本左缘像素位置与改造前一致。
     */
    const val BAR_WIDTH = 3

    /**
     * 透明色——选中态内层行分隔线的**占位色**。
     * 占 1px inset 保证选中/未选中两状态 insets 逐边相等，但不绘制：否则灰线会画在描边环内部，
     * 把选中框的底边割断（2026-09-12 用户指出的观感缺陷）。
     */
    val TRANSPARENT: Color = Color(0, 0, 0, 0)

    const val DEFAULT_SELECT = "#4A86E8"
    const val DEFAULT_INSERT = "#4CAF50"
    const val DEFAULT_UPDATE = "#E67E22"
    const val DEFAULT_DELETE = "#D32F2F"

    /**
     * 暗色主题下描边色的最小亮度阈值——低于此值压在深色行底上不可见
     * （如 Darcula 选中蓝 `#2E436E` 亮度约 0.26，需提亮）。
     */
    private const val DARK_MIN_BRIGHTNESS = 0.45

    /** 提亮的最大倍率（防止低饱和色被拉爆成白）。 */
    private const val MAX_BRIGHTEN_FACTOR = 2.4

    // 默认色实例（预建，避免 defaultOf 每次解析/分配）
    private val COLOR_SELECT: Color = Color(0x4A86E8)
    private val COLOR_INSERT: Color = Color(0x4CAF50)
    private val COLOR_UPDATE: Color = Color(0xE67E22)
    private val COLOR_DELETE: Color = Color(0xD32F2F)

    /**
     * 解析结果缓存（每类型至多 1 条，共 ≤ 4 条）。
     *
     * **存在的理由不是省那一次字符串解析，而是省掉 Swing 的重绘副作用**：实测（JBR21）
     * `JComponent.setBackground(new Color(同值))` 会触发 `repaint() +1`，而传入**同一实例**则是 0。
     * 渲染器每格都要给色条/单元格设背景，若每次现解析就会每格多一次 repaint —— 这正是
     * T-LogListFreeze 那类「渲染热路径逐格副作用累积」问题的成因。缓存后同色格复用同一实例。
     */
    private val parsedCache = java.util.concurrent.ConcurrentHashMap<StatementType, Pair<String, Color>>()

    /** 类型 → 默认色（用户未配置 / 配置非法时的兜底）。 */
    fun defaultOf(type: StatementType): Color = when (type) {
        StatementType.SELECT -> COLOR_SELECT
        StatementType.INSERT -> COLOR_INSERT
        StatementType.UPDATE -> COLOR_UPDATE
        StatementType.DELETE -> COLOR_DELETE
        StatementType.OTHER -> COLOR_SELECT
    }

    /** 当前样式（读设置薄壳）。 */
    fun styleOf(): String = styleOf(PluginSettings.getInstance().state.crudColorStyle)

    /**
     * **纯逻辑**：原始样式串 → 样式常量；未知值按 [STYLE_BAR] 兜底
     * ——不留「配置写错就整列表失色」的口子。
     */
    fun styleOf(raw: String): String = when (raw) {
        STYLE_BAR -> STYLE_BAR
        STYLE_BACKGROUND -> STYLE_BACKGROUND
        STYLE_OFF -> STYLE_OFF
        else -> STYLE_BAR
    }

    /** 当前样式是否走平台选中填充（仅 [STYLE_BAR] 为 true）。 */
    fun usesSelectionFill(style: String): Boolean = style == STYLE_BAR

    /**
     * 类型 → 标记色（读设置薄壳；色值来源见 [hexOf]）。
     *
     * 关闭样式 / OTHER → null（不标记）；空串或非法值回退 [defaultOf]（不抛异常、不写坏配置）。
     */
    fun colorOf(type: StatementType): Color? = colorOf(type, styleOf(), hexOf(type))

    /**
     * **纯逻辑**：类型 + 样式 + 色值 → 标记色（null = 不标记）。
     *
     * 与设置解耦，便于零平台依赖单测；[colorOf] 只负责把设置态喂进来。
     */
    fun colorOf(type: StatementType, style: String, hex: String): Color? {
        if (style == STYLE_OFF) return null
        if (type == StatementType.OTHER) return null
        // 同色复用同一 Color 实例：既省分配，也避免 Swing setBackground 因「新实例同值」触发 repaint
        val cached = parsedCache[type]
        if (cached != null && cached.first == hex) return cached.second
        val color = parseHex(hex, defaultOf(type))
        parsedCache[type] = hex to color
        return color
    }

    /** 类型 → 设置中的色值（OTHER → 空串，走 [defaultOf] 兜底）。 */
    fun hexOf(type: StatementType): String {
        val state = PluginSettings.getInstance().state
        return when (type) {
            StatementType.SELECT -> state.crudColorSelect
            StatementType.INSERT -> state.crudColorInsert
            StatementType.UPDATE -> state.crudColorUpdate
            StatementType.DELETE -> state.crudColorDelete
            StatementType.OTHER -> ""
        }
    }

    /**
     * 解析 `#RRGGBB`（`#` 可省略）；非法 / 空 → [fallback]。
     *
     * 容错优先：设置项异常必须降级而非抛（与 `formatTimestampFragment` 对
     * `logTimestampFormat` 的 `IllegalArgumentException` 兜底同款）。
     */
    fun parseHex(hex: String, fallback: Color): Color {
        val raw = hex.trim().removePrefix("#")
        if (raw.length != 6) return fallback
        val value = try {
            raw.toLong(16)
        } catch (_: NumberFormatException) {
            return fallback
        }
        return Color(
            ((value shr 16) and 0xFF).toInt(),
            ((value shr 8) and 0xFF).toInt(),
            (value and 0xFF).toInt(),
        )
    }

    /**
     * 按比例混色（base 为主）：`ratio = 0` 得 base，`1` 得 tint。
     *
     * 用于整行淡背景——与列表当前背景混色即天然适配亮/暗主题，无需为两主题各配一套色值。
     */
    fun blend(base: Color, tint: Color, ratio: Double): Color {
        val r = ratio.coerceIn(0.0, 1.0)
        return Color(
            mixChannel(base.red, tint.red, r),
            mixChannel(base.green, tint.green, r),
            mixChannel(base.blue, tint.blue, r),
        )
    }

    /**
     * 描边色对比度保障：暗色主题下低亮度色提亮到可见阈值，亮色主题原样返回。
     *
     * 背景色样式的选中行底色已被类型色调加深（如 UPDATE 暗色下约 `#5A3B22`），
     * 暗色 `selectionBackground`（`#2E436E`）压在其上几乎不可见——故描边色不能直接取原色。
     * 自研纯函数而非用 `com.intellij.ui.ColorUtil.brighter`：`tones` 换算语义不直观，
     * 断言需依赖平台实现细节，行为不可控。
     */
    fun ensureOutlineContrast(color: Color, isDark: Boolean): Color {
        if (!isDark) return color
        if (brightnessOf(color) >= DARK_MIN_BRIGHTNESS) return color
        var factor = 1.0
        var candidate = color
        while (factor < MAX_BRIGHTEN_FACTOR && brightnessOf(candidate) < DARK_MIN_BRIGHTNESS) {
            factor *= 1.1
            candidate = scaleBrightness(color, factor)
        }
        return candidate
    }

    /** 感知亮度（0..1，Rec.601 加权）。 */
    internal fun brightnessOf(color: Color): Double =
        (0.299 * color.red + 0.587 * color.green + 0.114 * color.blue) / 255.0

    private fun scaleBrightness(color: Color, factor: Double): Color = Color(
        (color.red * factor).coerceAtMost(255.0).toInt(),
        (color.green * factor).coerceAtMost(255.0).toInt(),
        (color.blue * factor).coerceAtMost(255.0).toInt(),
    )

    /**
     * 逐通道线性插值——**显式 half-up**（`(v + 0.5).floor()`）。
     *
     * 不用 `kotlin.math.round`：它采用 ties-to-even（银行家舍入），
     * `round(76.5) = 76` 与常规直觉相反，会让设计稿色值表出现 1/255 的意外偏移。
     */
    private fun mixChannel(base: Int, tint: Int, ratio: Double): Int =
        kotlin.math.floor(base + (tint - base) * ratio + 0.5).toInt()
}
