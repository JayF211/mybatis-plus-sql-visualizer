package cn.song.mybatisplus.visualizer.model

/**
 * SQL 片段文本 + 其中占位符 `?` 的来源（T-UpdateSetSqlConcat）。
 *
 * 片段位置（setSql/setApply/apply/exists/last/having/inSql/compareSql/MPJ 字符串 join 等）的文本
 * 是**原样拼进语句的 SQL**：其中的 `?` 必须按出现顺序登记进参数表，否则面板按位置回填会整体错位
 * ——`SET amount = amount * ?` 的 `?` 会被当成后面 WHERE 条件的那一个，用户输入落到错误的占位符上。
 *
 * @param text 片段文本（静态段 + 占位符 `?`）
 * @param params 与 [text] 中占位符 `?` 一一对应的来源名（字符串字面量/注释内的 `?` 不计）
 */
data class SqlFragment(
    val text: String,
    val params: List<String> = emptyList(),
) {
    companion object {
        /** 无来源信息的纯文本片段（字面量等静态文本）。 */
        fun ofText(text: String): SqlFragment = SqlFragment(text)
    }
}
