package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.sql.ParamHint

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
