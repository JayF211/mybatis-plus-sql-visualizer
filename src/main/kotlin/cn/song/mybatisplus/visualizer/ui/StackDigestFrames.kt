package cn.song.mybatisplus.visualizer.ui

/**
 * T-StackHoverUi：调用栈快照串的结构化解析与调试器 Frames 风格展示模型。
 *
 * 快照串由 agent 侧 `StackDigest.build(StackTraceElement[])` 产出，格式
 * `pkg.Class#method:line<-pkg.Class#method:line<-…`（`<-` 连接、栈顶在左、行号负数不写、
 * ≤16 帧/总长 ≤2000 字符——帧数或长度触顶时追加 `…` 截断标记）。IDE 侧此前把它当
 * 整段纯文本塞 tooltip（T-SourceJumpFunnel P2-2 形态），本对象把它拆回帧列表，
 * 供 [StackFrameHover] 悬停弹层逐帧渲染。
 */
internal data class StackFrameEntry(
    /** 全限定类名（`#` 之前；内嵌类/代理类 `$` 段原样保留）。 */
    val fqcn: String,
    /** 方法名（`#` 之后、行号 `:n` 之前；`lambda$run$0` 等 `$` 段原样保留）。 */
    val method: String,
    /** 源码行号（agent 侧语义：负数不写 `:n`，此处 0 = 未知）。 */
    val line: Int,
)

internal object StackDigestFrames {

    /** 帧间连接符（agent 侧 StackDigest 逐字契约）。 */
    internal const val FRAME_SEPARATOR = "<-"

    /** agent 侧截断标记（帧数触顶追加在完整末帧之后；长度触顶切在半帧中段，二者均以 `…` 收尾）。 */
    internal const val TRUNCATION_MARK = "…"

    /** agent 自身帧的包前缀（弹层里淡灰降权，模拟调试器「库帧」弱化样式）。 */
    internal const val AGENT_PACKAGE_PREFIX = "cn.song.mybatisplus.sqlvis."

    /**
     * 把快照串拆回帧列表（栈顶在左，展示序与串序一致）。解析失败容错到底：
     * 无 `#` 的残段跳过、整串不可解析返回空表（调用方回退纯文本形态），永不抛异常。
     *
     * 截断串（结尾 `…`）的末段一律丢弃：触顶末段要么是带标记的完整帧、要么是被 2000 字符
     * 硬截切出的半帧（后者解析后方法名是残缺前缀，展示会造假），丢 1 帧换来「不展示残帧」。
     */
    internal fun parse(digest: String?): List<StackFrameEntry> {
        if (digest.isNullOrBlank()) return emptyList()
        val trimmedDigest = digest.trim()
        if (trimmedDigest.isEmpty()) return emptyList()
        val truncated = trimmedDigest.endsWith(TRUNCATION_MARK)
        val s = if (truncated) trimmedDigest.removeSuffix(TRUNCATION_MARK) else trimmedDigest
        if (s.isEmpty()) return emptyList()
        val segments = s.split(FRAME_SEPARATOR)
        // 截断串末段一律丢弃：触顶末段要么是带标记的完整帧（丢 1/16 帧无伤）、要么是被 2000 字符
        // 硬截切出的半帧（解析后方法名是残缺前缀，展示会造假）——两者按「丢弃」统一处理。
        val usable = if (truncated && segments.isNotEmpty()) segments.dropLast(1) else segments
        val frames = ArrayList<StackFrameEntry>(usable.size)
        for (seg in usable) {
            val seg1 = seg.trim()
            if (seg1.isEmpty()) continue
            parseFrame(seg1)?.let { frames.add(it) }
        }
        return frames
    }

    /** agent 自身帧判定（采集埋点链，弹层降权用）。 */
    internal fun isAgentFrame(frame: StackFrameEntry): Boolean =
        frame.fqcn.startsWith(AGENT_PACKAGE_PREFIX)

    /**
     * 单帧解析：`pkg.Class#method:line`。`#` 定位类名/方法名边界（首个 `#`——方法名不含 `#`）；
     * 行号取方法名内**最后一个** `:` 之后全数字段（lambda 名 `lambda$run$0` 等含 `:` 的情况不存在，
     * 但代理/内嵌类名 `$` 段已在 `#` 前被隔离）。无行号段或非数字 → [StackFrameEntry.line] = 0。
     */
    private fun parseFrame(segment: String): StackFrameEntry? {
        val hash = segment.indexOf('#')
        if (hash <= 0 || hash == segment.length - 1) return null
        val fqcn = segment.substring(0, hash)
        if (fqcn.isBlank()) return null
        val rest = segment.substring(hash + 1)
        val colon = rest.lastIndexOf(':')
        if (colon > 0) {
            val linePart = rest.substring(colon + 1)
            if (linePart.isNotEmpty() && linePart.all { it.isDigit() }) {
                return StackFrameEntry(fqcn, rest.substring(0, colon), linePart.toInt())
            }
        }
        return StackFrameEntry(fqcn, rest, 0)
    }
}
