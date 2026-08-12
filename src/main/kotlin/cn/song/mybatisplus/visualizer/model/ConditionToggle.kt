package cn.song.mybatisplus.visualizer.model

/**
 * 动态条件「空值」语义（M12，用户补充）：布尔条件实参的判空方式决定空字符串是否拼接。
 *
 *  - [SKIP_ON_BLANK]：判空/空白（isNotBlank / isNotEmpty / hasText / StrUtil 等）→ 空字符串不拼接；
 *  - [SKIP_ON_NULL] ：判非 null（isNotNull / nonNull / != null）→ 空字符串是合法值需拼接（`name = ''`），仅 null 不拼接；
 *  - [UNKNOWN]      ：无法识别判空方式 → 默认按 [SKIP_ON_BLANK]（用户主诉求「值为空不拼接」）。
 */
enum class EmptyValueRule {
    SKIP_ON_BLANK,
    SKIP_ON_NULL,
    UNKNOWN,
}

/**
 * 动态条件元数据（M12）：布尔条件重载的解析产物，决定该条件在预览面板可否勾选开关。
 *
 * @param id             稳定标识（条件树 pre-order 序号，如 "c0" / "c1"），UI 复选框 ↔ 条件关联键；
 *                       面板维护的启用集合（Set<String>）以 id 为元素
 * @param flagSource     布尔条件实参源码文本（"true"/"false"/"isActive"/"StringUtils.isNotBlank(name)"），
 *                       用于复选框 tooltip 展示；null 表示该条件非布尔重载（保留扩展位）
 * @param defaultEnabled 默认勾选态：字面量 true→true、false→false；运行时布尔→true；
 *                       判空方法可静态求值（值已知字面量）→ 按语义求出（如 isNotBlank("")→false）
 * @param label          人读条件文本（如 "name = '张三'" / "name = ?"），复选框行标签
 * @param emptyRule      空值语义（[EmptyValueRule]），由判空方法名/表达式形态判定，默认 [EmptyValueRule.SKIP_ON_BLANK]
 * @param valueSource    关联的未解析参数来源（该条件有值输入框时非 null，用于按 source 绑定与空值联动）
 */
data class ConditionToggle(
    val id: String,
    val flagSource: String?,
    val defaultEnabled: Boolean,
    val label: String,
    val emptyRule: EmptyValueRule = EmptyValueRule.SKIP_ON_BLANK,
    val valueSource: String? = null,
)
