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
    /** "paramN" → 字面量原文（字符串 `''` 转义已还原）。 */
    val initialValues: Map<String, String>,
    /** 回填提示（字符串 quote=true；数值 quote=false——与面板 renderSingleValue 对齐）。 */
    val paramHints: List<ParamHint>,
)

/** 字面量恢复上限：超过（IN 大列表等）整体放弃，防参数行爆炸。 */
internal const val MAX_RECOVERED_PARAMS = 32

/**
 * 字面量参数恢复（T-HistParam F4）：rawSql 无 `?` 占位符（裸 SQL 捕获 / MyBatis `${}` /
 * 上游日志已内联参数值）时，把字面量原位改写为 `?`，让既有的值可见即可编辑。
 *
 * 识别范围两类：
 * - 单引号字符串字面量全量识别——双引号/反引号标识符是另一套引号体系天然不误伤；
 * - 数值字面量限定谓词语境——前一 token 是比较运算符（`= != <> > < >= <=`，`= -1` 的负号
 *   保留比较上下文）、IN 列表与 VALUES 列表括号内、BETWEEN 双界。LIMIT/OFFSET/SELECT 投影
 *   （`SELECT 1`、EXISTS 子查询）/函数实参一律不识别——MP 分页查询 LIMIT 恒为字面量，
 *   全量识别会给每条分页查询多出噪音参数行。
 *
 * 其余收敛与防御：
 * - 跳过空白值字面量（`''` / 纯空白）与以反斜杠结尾的歧义片段（扫描器只认 `''` 转义，
 *   MySQL `'don\'t'` 会错切出尾反斜杠片段）——面板空值输入渲染为 `?`，无法逐字符往返；
 * - 非纯数字 token 不识别（`1abc`/`0x1F`/`1E5` 后随词字符）；
 * - 超过 [MAX_RECOVERED_PARAMS] 个候选整体返回 null；
 * - 注释（`--` 行注释 / 块注释）与字符串内的引号不参与（扫描状态机与
 *   [cn.song.mybatisplus.visualizer.sql.placeholderPositions] 同形态），未闭合字符串到行尾丢弃。
 */
internal fun recoverLiteralParams(sql: String): LiteralParamRecovery? {
    if (sql.none { it == '\'' || it.isDigit() }) return null

    /** 候选：replaced 范围（字符串含引号；数值为 token 本身）+ 值 + 回填引号提示。 */
    data class Candidate(val range: IntRange, val value: String, val quote: Boolean)

    val candidates = mutableListOf<Candidate>()
    var i = 0
    val n = sql.length
    // —— 字符串/注释状态（与 placeholderPositions 同口径）——
    var inString = false
    var stringOpen = -1
    var inLineComment = false
    var inBlockComment = false
    // —— 数值字面量的谓词语境状态机 ——
    var parenDepth = 0
    var lastWasComparison = false // 前一显著 token 是比较运算符
    var inListArmed = false       // 见到 IN，等待列表 (
    var inListMark = -1           // 列表 ( 的嵌套深度（活动中即处于列表内）
    var valuesArmed = false       // 见到 VALUES，等待值列表 (
    var valuesMark = -1
    var betweenState = 0          // 0 无；1 待首界；2 待 AND 后第二界

    fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    while (i < n) {
        val c = sql[i]
        when {
            inLineComment -> if (c == '\n') inLineComment = false
            inBlockComment -> {
                if (c == '*' && i + 1 < n && sql[i + 1] == '/') {
                    inBlockComment = false
                    i++
                }
            }
            inString -> {
                if (c == '\'') {
                    if (i + 1 < n && sql[i + 1] == '\'') {
                        i++ // `''` 转义：跳过第二个引号
                    } else {
                        inString = false
                        candidates += Candidate(
                            IntRange(stringOpen, i),
                            sql.substring(stringOpen + 1, i).replace("''", "'"),
                            true,
                        )
                    }
                }
            }
            c == '\'' -> {
                inString = true
                stringOpen = i
            }
            c == '-' && i + 1 < n && sql[i + 1] == '-' -> inLineComment = true
            c == '/' && i + 1 < n && sql[i + 1] == '*' -> {
                inBlockComment = true
                i++
            }
            c == '(' -> {
                parenDepth++
                if (inListArmed) {
                    inListArmed = false
                    inListMark = parenDepth
                }
                if (valuesArmed) {
                    valuesArmed = false
                    valuesMark = parenDepth
                }
                lastWasComparison = false
            }
            c == ')' -> {
                if (parenDepth == inListMark) inListMark = -1
                if (parenDepth == valuesMark) valuesMark = -1
                if (parenDepth > 0) parenDepth--
                lastWasComparison = false
                betweenState = 0
            }
            c == ',' || c == ';' -> {
                lastWasComparison = false
                betweenState = 0
                if (c == ';') {
                    inListArmed = false
                    valuesArmed = false
                }
            }
            c in "<>!=" -> {
                // 比较运算符 run：= / != / <> / < / > / <= / >=
                var j = i
                while (j < n && sql[j] in "<>!=") j++
                lastWasComparison = true
                betweenState = 0
                i = j
                continue
            }
            c in "+-" -> {
                // 符号保留比较上下文（`= -1` 的值是 1，负号留在原文随 `-?` 往返无损）
            }
            c in "*/%" -> {
                lastWasComparison = false
                betweenState = 0
            }
            c == '.' -> {
                // 词边界，无上下文效果
            }
            c.isDigit() -> {
                // 数值 token：数字 + 至多一个小数点（点后必须有数字，`1.` 视作 `1` 与 `.`）；
                // 后随词字符则非纯数字（`1abc`/`0x1F`/`1E5`）不识别
                var j = i
                var dotSeen = false
                while (j < n) {
                    val d = sql[j]
                    when {
                        d.isDigit() -> j++
                        d == '.' && !dotSeen && j + 1 < n && sql[j + 1].isDigit() -> {
                            dotSeen = true
                            j++
                        }
                        else -> break
                    }
                }
                val qualified = lastWasComparison || betweenState == 1 || inListMark != -1 || valuesMark != -1
                if (qualified && (j >= n || !isWordChar(sql[j]))) {
                    candidates += Candidate(IntRange(i, j - 1), sql.substring(i, j), false)
                }
                lastWasComparison = false
                betweenState = if (betweenState == 1) 2 else 0
                i = j
                continue
            }
            isWordChar(c) -> {
                var j = i
                while (j < n && isWordChar(sql[j])) j++
                when (sql.substring(i, j).uppercase()) {
                    "IN" -> inListArmed = true
                    "BETWEEN" -> betweenState = 1
                    "VALUES" -> valuesArmed = true
                    "AND" -> if (betweenState == 2) betweenState = 1 else betweenState = 0
                    else -> betweenState = 0
                }
                lastWasComparison = false
                i = j
                continue
            }
        }
        i++
    }

    // 空白值与尾反斜杠歧义片段只出现在字符串候选（数值 token 不可能空白、必无反斜杠）
    val kept = candidates.filter { !(it.quote && (it.value.isBlank() || it.value.endsWith("\\"))) }
    if (kept.isEmpty() || kept.size > MAX_RECOVERED_PARAMS) return null

    val placeholderSql = buildString(sql.length) {
        var prev = 0
        kept.forEach { cand ->
            append(sql, prev, cand.range.first)
            append('?')
            prev = cand.range.last + 1
        }
        append(sql, prev, sql.length)
    }
    // 改写后的 SQL 里若还残留非参数 `?`（注释内等），说明字面量改写与占位符口径不自洽——
    // 直接放弃（保守保险；T-PSG 起面板回填已改串感知、不再因注释内 `?` 错位，但本闸门
    // 守的是「改写结果自洽」这条输入侧不变式，维持不放宽）
    if (placeholderSql.count { it == '?' } != kept.size) return null

    val sources = (1..kept.size).map { "param$it" }
    return LiteralParamRecovery(
        placeholderSql = placeholderSql,
        unresolvedParams = sources,
        initialValues = sources.zip(kept.map { it.value }).toMap(),
        paramHints = kept.map { ParamHint(quote = it.quote) },
    )
}

/**
 * 运行期熔断（T-HistParam F4）：占位符化 SQL 用原值按面板引号规则渲染回原文，必须逐字符一致。
 * 与面板 renderSingleValue 同规则（quote=true 引号+`''` 转义；quote=false 原样 trim），
 * 不一致（其他未知方言盲区）即放弃恢复维持现状。
 */
internal fun verifyLiteralRoundTrip(originalSql: String, recovery: LiteralParamRecovery): Boolean {
    val sb = StringBuilder(originalSql.length)
    var paramIndex = 0
    recovery.placeholderSql.forEach { c ->
        if (c == '?') {
            val value = recovery.initialValues[recovery.unresolvedParams[paramIndex]] ?: return false
            if (recovery.paramHints[paramIndex].quote == true) {
                sb.append('\'').append(value.replace("'", "''")).append('\'')
            } else {
                sb.append(value.trim())
            }
            paramIndex++
        } else {
            sb.append(c)
        }
    }
    return sb.toString() == originalSql
}
