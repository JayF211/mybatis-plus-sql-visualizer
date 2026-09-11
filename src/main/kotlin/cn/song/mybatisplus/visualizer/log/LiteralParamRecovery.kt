package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.sql.ParamHint

/**
 * 单条字面量参数恢复结果（T-HistParam F4）：占位符化 SQL + 合成参数形态，
 * 字段语义与 [RecognizedLogPreview] 对齐（面板参数行/改值/回填管线零改动复用）。
 */
internal class LiteralParamRecovery(
    /** 字面量原位改写为 `?` 后的 SQL。 */
    val placeholderSql: String,
    /** 参数来源（"param1".."paramN"，与占位符顺序对应）。 */
    val unresolvedParams: List<String>,
    /** "paramN" → 字面量原文（`''` 转义已还原）。 */
    val initialValues: Map<String, String>,
    /** 回填提示（恒 quote=true——识别范围仅单引号字符串字面量）。 */
    val paramHints: List<ParamHint>,
)

/** 字面量恢复上限：超过（IN 大列表等）整体放弃，防参数行爆炸。 */
internal const val MAX_RECOVERED_PARAMS = 32

/**
 * 字面量参数恢复（T-HistParam F4）：rawSql 无 `?` 占位符（裸 SQL 捕获 / MyBatis `${}` /
 * 上游日志已内联参数值）时，把单引号字符串字面量原位改写为 `?`，让既有的值可见即可编辑。
 *
 * 范围刻意收敛：
 * - 只识别单引号字符串字面量——双引号/反引号标识符是另一套引号体系天然不误伤；数值、布尔
 *   裸字面量大量出现在 LIMIT/OFFSET/投影常量位置，噪音大，一期不做；
 * - 跳过空白值字面量（`''` / 纯空白）与以反斜杠结尾的歧义片段（扫描器只认 `''` 转义，
 *   MySQL `'don\'t'` 会错切出尾反斜杠片段，见下）——面板空值输入渲染为 `?`，无法逐字符往返；
 * - 超过 [MAX_RECOVERED_PARAMS] 个候选整体返回 null；
 * - 注释（`--` 行注释 / 块注释）与字符串内的引号不参与（扫描状态机与 [placeholderPositions]
 *   同形态），未闭合字符串到行尾丢弃。
 */
internal fun recoverLiteralParams(sql: String): LiteralParamRecovery? {
    if (sql.none { it == '\'' }) return null
    val inners = mutableListOf<IntRange>()
    var inString = false
    var stringOpen = -1
    var inLineComment = false
    var inBlockComment = false
    var i = 0
    while (i < sql.length) {
        val c = sql[i]
        when {
            inLineComment -> if (c == '\n') inLineComment = false
            inBlockComment -> {
                if (c == '*' && i + 1 < sql.length && sql[i + 1] == '/') {
                    inBlockComment = false
                    i++
                }
            }
            inString -> {
                if (c == '\'') {
                    if (i + 1 < sql.length && sql[i + 1] == '\'') {
                        i++ // `''` 转义：跳过第二个引号
                    } else {
                        inString = false
                        inners += (stringOpen + 1) until i
                    }
                }
            }
            c == '\'' -> {
                inString = true
                stringOpen = i
            }
            c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> inLineComment = true
            c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                inBlockComment = true
                i++
            }
        }
        i++
    }
    val candidates = inners.map { sql.substring(it) }
        .filter { it.isNotBlank() }
        // 尾反斜杠片段是方言转义盲区（MySQL `'don\'t'` 会被错切出 `don\`）——引号渲染恰能
        // 逐字符重组原文、熔断拦不住，但畸形值成为参数后改值即产出坏 SQL，一律跳过
        .filter { !it.endsWith("\\") }
    if (candidates.isEmpty() || candidates.size > MAX_RECOVERED_PARAMS) return null

    // 预览面板回填按 `?` 字符朴素逐个替换（backfillParamsBySource）：改写后的 SQL 里若还残留
    // 非参数 `?`（注释内、被跳过的空白字面量不可能含 `?`，只剩注释场景），回填会错位——直接放弃
    val placeholderSql = buildString(sql.length) {
        var prev = 0
        candidates.forEach { inner ->
            val start = sql.indexOf("'$inner'", prev)
            append(sql, prev, start).append('?')
            prev = start + inner.length + 2
        }
        append(sql, prev, sql.length)
    }
    val recoveredCount = placeholderSql.count { it == '?' }
    if (recoveredCount != candidates.size) return null

    val values = candidates.map { it.replace("''", "'") }
    val sources = (1..values.size).map { "param$it" }
    return LiteralParamRecovery(
        placeholderSql = placeholderSql,
        unresolvedParams = sources,
        initialValues = sources.zip(values).toMap(),
        paramHints = values.map { ParamHint(quote = true) },
    )
}

/**
 * 运行期熔断（T-HistParam F4）：占位符化 SQL 用原值按面板引号规则渲染回原文，必须逐字符一致。
 * 兜住扫描器的方言盲区（如 MySQL 反斜杠转义 `'a\'b'` 会被错切）——不一致即放弃恢复维持现状。
 */
internal fun verifyLiteralRoundTrip(originalSql: String, recovery: LiteralParamRecovery): Boolean {
    val sb = StringBuilder(originalSql.length)
    var paramIndex = 0
    recovery.placeholderSql.forEach { c ->
        if (c == '?') {
            val value = recovery.initialValues[recovery.unresolvedParams[paramIndex]] ?: return false
            sb.append('\'').append(value.replace("'", "''")).append('\'')
            paramIndex++
        } else {
            sb.append(c)
        }
    }
    return sb.toString() == originalSql
}
