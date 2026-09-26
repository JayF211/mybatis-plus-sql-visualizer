package cn.song.mybatisplus.visualizer.config

/**
 * T-PrefixValidate：两栏前缀登记（skip＝「源码捕获帧过滤前缀」/ biz＝「JAR 业务帧包名前缀」）的
 * 格式校验与一键修复。
 *
 * 为什么抽成纯函数：面板只做「展示 + 回写」，判定与修复逻辑全部收在本对象——
 * 无平台依赖、单测可直接覆盖（面板层只补一条 UI 装配测试）。
 *
 * 语义红线（勿在修复里翻转）：同一前缀同时出现在两栏时 **skip 优先**——agent 侧跳过发生在建池
 * 阶段、biz 只影响 L1 选主，运行时本就是 skip 赢（demo PR !1 E29 P2 档：业务帧被跳过后候选池
 * 抽空、跳转能力消失，故冲突必须显式暴露而不是留给用户猜）。
 */
object SourceFramePrefixesValidator {

    /** 单条前缀的合法形态：Java 标识符段以点连接，允许以点结尾（前缀语义推荐以「.」收尾）。 */
    private val PREFIX_REGEX = Regex("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*\\.?$")

    /** 两栏归属（决定提示文案与修复方向：冲突时保留 SKIP 删 BIZ）。 */
    enum class PrefixField { SKIP, BIZ }

    /** 非法行：[lineNo] 为文本框内 1 起始的原始行号（含空行计数，便于用户回找）。 */
    data class InvalidLine(val field: PrefixField, val lineNo: Int, val text: String)

    /** 同栏重复登记（无害但属脏数据，修复去重）。 */
    data class DuplicatePrefix(val field: PrefixField, val prefix: String)

    /** 跨栏冲突：同一前缀两栏都登记，skip 静默赢、biz 侧不生效。 */
    data class CrossFieldConflict(val prefix: String)

    data class Report(
        val invalidLines: List<InvalidLine>,
        val duplicates: List<DuplicatePrefix>,
        val conflicts: List<CrossFieldConflict>,
    ) {
        val hasProblems: Boolean
            get() = invalidLines.isNotEmpty() || duplicates.isNotEmpty() || conflicts.isNotEmpty()
    }

    /** 校验两栏文本；空行不计问题（apply 本就会丢弃），只挑非空行里的真问题。 */
    fun validate(skipText: String, bizText: String): Report {
        val skipLines = nonBlankLines(skipText)
        val bizLines = nonBlankLines(bizText)
        val invalid = invalidLines(PrefixField.SKIP, skipText) + invalidLines(PrefixField.BIZ, bizText)
        val duplicates = duplicates(PrefixField.SKIP, skipLines) + duplicates(PrefixField.BIZ, bizLines)
        val skipPrefixes = skipLines.map { it.second }.toSet()
        val bizPrefixes = bizLines.map { it.second }.toSet()
        val conflicts = (skipPrefixes intersect bizPrefixes).map { CrossFieldConflict(it) }
        return Report(invalid, duplicates, conflicts)
    }

    /**
     * 一键修复（两栏一起回写）：逐行 trim → 丢空行 → 丢非法行（无法替用户发明合法包名，只能剔除并提示）
     * → 同栏去重（保留首个）→ 跨栏冲突按 skip 优先从 BIZ 侧剔除。修复结果幂等（对已修复文本再跑不变）。
     */
    fun autoFix(skipText: String, bizText: String): Pair<String, String> {
        val skip = normalize(skipText)
        val biz = normalize(bizText).filter { it !in skip }
        return skip.joinToString("\n") to biz.joinToString("\n")
    }

    private fun normalize(text: String): List<String> =
        nonBlankLines(text).map { it.second }
            .filter { PREFIX_REGEX.matches(it) }
            .distinct()

    /** 非空行列表：[first]＝原始 1 起始行号（空行也计数），[second]＝trim 后内容。 */
    private fun nonBlankLines(text: String): List<Pair<Int, String>> =
        text.lines().mapIndexed { idx, line -> (idx + 1) to line.trim() }
            .filter { it.second.isNotEmpty() }

    private fun invalidLines(field: PrefixField, text: String): List<InvalidLine> =
        nonBlankLines(text).filter { !PREFIX_REGEX.matches(it.second) }
            .map { InvalidLine(field, it.first, it.second) }

    private fun duplicates(field: PrefixField, lines: List<Pair<Int, String>>): List<DuplicatePrefix> =
        lines.map { it.second }.filter { PREFIX_REGEX.matches(it) }
            .groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys.map { DuplicatePrefix(field, it) }
}
