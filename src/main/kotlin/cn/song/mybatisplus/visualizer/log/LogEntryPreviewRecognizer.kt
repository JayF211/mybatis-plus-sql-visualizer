package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle

/**
 * 日志条目 → 预览弹窗参数识别（T-M42）：双击日志 SQL 时把 `?` 占位符自动识别为可编辑参数行。
 *
 * 匹配判定与 [LogSqlRestorer] 的裸 `indexOf('?')` 替换语义自洽：
 * `rawSql` 中 `?` 数 == `params.size` ⇔ `restoredSql` 无残留 `?`（等价于弹窗现有未解析警告条件）。
 * 识别成功时参数行预填日志参数值，SQL 初始显示与现状（值内联）逐字符一致，用户改值才生效。
 *
 * 未识别（params 空 / 数量不匹配 / 无 `?`）：`recognized=false`，`sql`/`compactSql` 仍为
 * `rawSql`——由调用方（openPreview）决定回退 `restoredSql`（现状行为）。
 */
data class RecognizedLogPreview(
    /** rawSql + ';'（格式化由 openPreview 按设置做）；未识别时仍为 rawSql。 */
    val sql: String,
    /** rawSql + ';'；未识别时仍为 rawSql。 */
    val compactSql: String,
    /** 按 `?` 出现顺序的参数来源（"param1"..."paramN"）；未识别为空。 */
    val unresolvedParams: List<String>,
    /** "paramN" → 日志参数值（null 参数预填空串）；未识别为空。 */
    val initialValues: Map<String, String>,
    /** 与 [unresolvedParams] 并行的回填提示（类型 → 引号，见 [LogSqlParam.toHint]）；未识别为空。 */
    val paramHints: List<ParamHint>,
    /** true=匹配识别（`?` 数 == 参数数且 > 0）；false=回退现状。 */
    val recognized: Boolean,
)

/**
 * 识别日志条目为预览弹窗参数形态。
 *
 * 参数命名：日志无参数名，用序号 `param1..N`（对齐 MP 生成器 `param1` 风格）；
 * 值预填 `LogSqlParam.value`（null → 空串，回填显示 `?`——与还原 `NULL` 不一致的边界见开发计划 §7）。
 */
fun LogSqlEntry.toRecognizedPreview(): RecognizedLogPreview {
    val placeholderCount = countPlaceholders(rawSql)
    val recognized = placeholderCount > 0 && placeholderCount == params.size
    if (!recognized) {
        return RecognizedLogPreview(
            sql = rawSql,
            compactSql = rawSql,
            unresolvedParams = emptyList(),
            initialValues = emptyMap(),
            paramHints = emptyList(),
            recognized = false,
        )
    }
    val sources = (1..placeholderCount).map { "param$it" }
    val initialValues = sources.zip(params.map { it.value ?: "" }).toMap()
    val paramHints = params.map { it.toHint() }
    return RecognizedLogPreview(
        sql = rawSql,
        compactSql = rawSql,
        unresolvedParams = sources,
        initialValues = initialValues,
        paramHints = paramHints,
        recognized = true,
    )
}

/**
 * T-HibDualCapture：string-aware `?` 占位符计数——口径与 [placeholderPositions]（还原回填）
 * 完全一致，字符串字面量与注释内的 `?` 不计数，识别数量与回填位置不再错位。
 */
internal fun countPlaceholders(sql: String): Int = placeholderPositions(sql).size

/**
 * 日志条目 → 预览结果（T-HistParam F3：SQL 日志 tab 与 SQL 历史 tab 的统一识别通道）。
 *
 * 三级分流：
 * 1. 占位符匹配识别（[toRecognizedPreview]，`?` 数 == 参数数）→ 参数行预填日志值；
 * 2. 字面量参数恢复（[recoverLiteralParams]，rawSql 零 `?` 且 [literalRecovery] 开启，
 *    运行期逐字符一致性熔断 [verifyLiteralRoundTrip]）→ 字面量改写为 `?` 复用参数管线；
 * 3. 回退现状（restoredSql + 零参数/数量不匹配警告分流，零 `?` 无警告）。
 *
 * 纯函数无平台依赖（开关由调用方读设置传入）：SqlLogPanel 与 HistorySqlLogPanel 均传
 * formatSql=设置值（T-ListPreviewCopy：两页预览统一跟随「预览时格式化还原 SQL」，默认开）。
 */
internal fun LogSqlEntry.toPreviewResult(
    formatSql: Boolean,
    literalRecovery: Boolean,
): PreviewResult {
    val recognized = toRecognizedPreview()
    if (recognized.recognized) {
        return PreviewResult(
            sql = formatIfNeeded(withTerminator(recognized.sql), formatSql),
            compactSql = withTerminator(recognized.compactSql),
            unresolvedParams = recognized.unresolvedParams,
            paramTable = null,
            wrapperType = null,
            tableName = null,
            warning = null,
            paramHints = recognized.paramHints,
            initialParamValues = recognized.initialValues,
        )
    }
    // 字面量恢复只接「零占位符」分支（裸 SQL / ${} / 上游日志已内联值）；数量不匹配
    // （残留 `?`）维持现状警告，不做两套占位符的混合恢复
    if (literalRecovery && countPlaceholders(rawSql) == 0) {
        recoverLiteralParams(rawSql)?.let { recovery ->
            if (verifyLiteralRoundTrip(rawSql, recovery)) {
                return PreviewResult(
                    sql = formatIfNeeded(withTerminator(recovery.placeholderSql), formatSql),
                    compactSql = withTerminator(recovery.placeholderSql),
                    unresolvedParams = recovery.unresolvedParams,
                    paramTable = null,
                    wrapperType = null,
                    tableName = null,
                    warning = null,
                    paramHints = recovery.paramHints,
                    initialParamValues = recovery.initialValues,
                )
            }
        }
    }
    val base = withTerminator(restoredSql)
    return PreviewResult(
        sql = formatIfNeeded(base, formatSql),
        compactSql = restoredSql,
        unresolvedParams = emptyList(),
        paramTable = null,
        wrapperType = null,
        tableName = null,
        // T-HibDualCapture：文案分流——零参数是「绑定行没抓到」（引导开 TRACE），
        // 有参数但数量不齐才是「数量不匹配」；`?` 计数 string-aware
        warning = if (countPlaceholders(restoredSql) > 0) {
            MpSqlVisualizerBundle.message(
                if (params.isEmpty()) "log.preview.unresolvedNoBinding"
                else "log.preview.unresolvedWarning",
            )
        } else {
            null
        },
    )
}

private fun formatIfNeeded(sql: String, formatSql: Boolean): String =
    if (formatSql) SqlFormatter.format(sql) else sql
