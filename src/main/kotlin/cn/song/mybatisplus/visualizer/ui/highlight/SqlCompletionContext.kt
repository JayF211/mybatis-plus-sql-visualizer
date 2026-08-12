package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.openapi.util.Key

/**
 * SQL 补全上下文（M9-03/04）：当前预览涉及的实体表/列，随 PSI 文件传递。
 *
 * 面板在创建预览 PSI 文件时通过 [KEY] 绑定上下文，补全器从
 * `position.containingFile.getUserData(KEY)` 读取，避免全局可变状态。
 *
 * @param tableName  当前主表名（如 merchant_entity）
 * @param columns    当前实体列名（snake_case）
 * @param tableAlias 主表别名（MPJ 场景如 t；单表为 null）
 */
class SqlCompletionContext(
    val tableName: String?,
    val columns: List<String>,
    val tableAlias: String?,
) {
    companion object {
        val KEY: Key<SqlCompletionContext> = Key.create("MP_SQL_COMPLETION_CONTEXT")
    }
}
