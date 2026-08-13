package cn.song.mybatisplus.visualizer.model

/**
 * 动态条件元数据（M12）：布尔条件重载的解析产物，决定该条件在预览面板可否勾选开关。
 *
 * @param id             稳定标识（条件树 pre-order 序号，如 "c0" / "c1"），UI 复选框 ↔ 条件关联键；
 *                       面板维护的启用集合（Set<String>）以 id 为元素
 * @param flagSource     布尔条件实参源码文本（"true"/"false"/"isActive"/"StringUtils.isNotBlank(name)"），
 *                       用于复选框 tooltip 展示；null 表示该条件非布尔重载（保留扩展位）
 * @param defaultEnabled 默认勾选态（M13 简化）：字面量 true→true、false→false；运行时布尔→true；
 *                       不再做判空语义识别与静态求值
 * @param label          人读条件文本（如 "name = '张三'" / "name = ?"），复选框行标签
 * @param valueSource    关联的未解析参数来源（该条件有值输入框时非 null，用于按 source 绑定）；
 *                       多值条件（between 等）取第一个来源，完整列表见 [valueSources]
 * @param valueSources   关联的未解析参数来源全集（M19.5：between 双值 → 两个输入框，每个 source 一个）
 */
data class ConditionToggle(
    val id: String,
    val flagSource: String?,
    val defaultEnabled: Boolean,
    val label: String,
    val valueSource: String? = null,
    val valueSources: List<String> = emptyList(),
)
