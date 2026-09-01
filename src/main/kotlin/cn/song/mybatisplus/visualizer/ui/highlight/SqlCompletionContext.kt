package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.model.TableInfo

import com.intellij.openapi.util.Key

/**
 * SQL 补全上下文（M9-03/04）：当前预览涉及的实体表/列，随 Editor/PSI 文件双载体传递。
 *
 * 面板在创建预览编辑器时通过 [KEY] 绑定上下文，补全器从
 * `editor.getUserData(KEY)`（优先，M16.2——嵌入式编辑器编辑后稳定）或
 * `position.containingFile.getUserData(KEY)`（兜底）读取，避免全局可变状态。
 *
 * @param tableName      当前主表名（如 merchant_entity）
 * @param columns        当前实体列名（snake_case）
 * @param tableAlias     主表别名（MPJ 场景如 t；单表为 null）
 * @param columnComments 列注释（columnName → comment，M16：补全 tailText 与关键词检索；缺省空 Map 向后兼容）
 * @param tableComment   表注释（M16：表名补全 tailText 与关键词检索）
 */
class SqlCompletionContext(
    val tableName: String?,
    val columns: List<String>,
    val tableAlias: String?,
    val columnComments: Map<String, String> = emptyMap(),
    val tableComment: String? = null,
    /** T-M27.4：联表候选表（@TableName 实体；表名补全全位置提示，主表去重）。 */
    val candidateTables: List<TableInfo> = emptyList(),
) {
    companion object {
        @JvmField val KEY: Key<SqlCompletionContext> = Key.create("MP_SQL_COMPLETION_CONTEXT")
    }
}
