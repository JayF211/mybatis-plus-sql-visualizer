package cn.song.mybatisplus.visualizer.model

import cn.song.mybatisplus.visualizer.sql.Dialect

/**
 * SQL 预览模型（M12）：供预览面板做**模型级重生成**（动态条件勾选）。
 *
 * @param statement      语句模型（含动态条件 toggle 元数据）
 * @param dialect        方言（重生成分页子句用）
 * @param formatSql      多行/单行设置（false=单行），面板据此对齐初始 compactMode
 * @param conditionViews 动态条件行（扁平化，含嵌套组内），供左栏复选框渲染
 */
data class SqlPreviewModel(
    val statement: SqlStatement,
    val dialect: Dialect,
    val formatSql: Boolean,
    val conditionViews: List<ConditionView>,
)

/**
 * 动态条件行（M12）：一个可勾选开关的动态条件。
 *
 * @param id             条件标识（与 [ConditionToggle.id] 一致，启用集合元素）
 * @param label          人读条件文本（复选框标签）
 * @param flagSource     布尔条件实参源码（tooltip），null 表示非布尔重载
 * @param defaultEnabled 默认勾选态
 * @param valueSource    关联值输入框来源；null 表示 checkbox-only（无输入框）
 */
data class ConditionView(
    val id: String,
    val label: String,
    val flagSource: String?,
    val defaultEnabled: Boolean,
    val valueSource: String?,
)
