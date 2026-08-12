package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.BatchPreview
import cn.song.mybatisplus.visualizer.model.TableInfo
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethodCallExpression

/**
 * 批量操作转换器（F-15，M6-01）：识别 `service.updateBatchById(list)` 等批量调用，
 * 生成 SQL 模板 + 参数组说明。
 *
 * 支持的批量方法：
 *  - `updateBatchById(list)`：UPDATE 表 SET 非主键列 = ? WHERE 主键 = ?
 *  - `saveBatch(list)` / `insertBatchSomeColumn(list)`：INSERT INTO 表 (列...) VALUES (?, ...)
 *  - `removeByIds(list)`：DELETE FROM 表 WHERE 主键 IN (?, ...)
 *
 * 实体类从第一个参数（集合 `List<Entity>`）的泛型解析；运行时行值不可得，
 * 输出 `?` 模板并附参数组说明（每行一组，字段顺序与 SQL 一致）。
 */
object BatchOperationConverter {

    /** 批量方法 → SQL 模板生成策略。 */
    private enum class Operation(val sqlPrefix: String) {
        UPDATE("UPDATE"),
        INSERT("INSERT"),
        DELETE("DELETE"),
    }

    private val BATCH_OPERATIONS = mapOf(
        "updateBatchById" to Operation.UPDATE,
        "saveBatch" to Operation.INSERT,
        "insertBatchSomeColumn" to Operation.INSERT,
        "removeByIds" to Operation.DELETE,
    )

    /**
     * 转换批量方法调用。
     *
     * @param call service 方法调用表达式
     * @return 批量预览；方法不匹配或实体无法解析时返回 null
     */
    fun convert(call: PsiMethodCallExpression): BatchPreview? {
        val methodName = call.methodExpression.referenceName ?: return null
        val operation = BATCH_OPERATIONS[methodName] ?: return null
        val table = resolveEntityTable(call) ?: return null
        return when (operation) {
            Operation.UPDATE -> buildUpdateBatch(methodName, table)
            Operation.INSERT -> buildInsertBatch(methodName, table)
            Operation.DELETE -> buildDeleteBatch(methodName, table)
        }
    }

    /**
     * 从第一个参数（`List<Entity>`）解析实体表信息。
     */
    private fun resolveEntityTable(call: PsiMethodCallExpression): TableInfo? {
        val firstArg = call.argumentList.expressions.firstOrNull() ?: return null
        val listType = firstArg.type as? PsiClassType ?: return null
        val entityType = listType.parameters.firstOrNull() as? PsiClassType ?: return null
        val entityClass = entityType.resolve() ?: return null
        return EntityResolver().resolve(entityClass)
    }

    /** UPDATE 模板：非主键列 SET + 主键 WHERE（无主键标记时用首列）。 */
    private fun buildUpdateBatch(methodName: String, table: TableInfo): BatchPreview {
        val idColumn = table.columns.firstOrNull { it.isId } ?: table.columns.first()
        val setColumns = table.columns.filter { it.columnName != idColumn.columnName }
        val setSql = setColumns.joinToString(", ") { "${it.columnName} = ?" }
        val sql = "UPDATE ${table.tableName}\nSET $setSql\nWHERE ${idColumn.columnName} = ?"
        val paramGroups = buildParamGroups(setColumns.map { it.columnName } + idColumn.columnName)
        return BatchPreview(methodName, table.tableName, sql, paramGroups)
    }

    /** INSERT 模板：全列 + VALUES。 */
    private fun buildInsertBatch(methodName: String, table: TableInfo): BatchPreview {
        val columns = table.columns.map { it.columnName }
        val placeholders = columns.joinToString(", ") { "?" }
        val sql = "INSERT INTO ${table.tableName} (${columns.joinToString(", ")})\nVALUES ($placeholders)"
        return BatchPreview(methodName, table.tableName, sql, buildParamGroups(columns))
    }

    /** DELETE 模板：主键 IN 列表。 */
    private fun buildDeleteBatch(methodName: String, table: TableInfo): BatchPreview {
        val idColumn = table.columns.firstOrNull { it.isId } ?: table.columns.first()
        val sql = "DELETE FROM ${table.tableName}\nWHERE ${idColumn.columnName} IN (?, ?, ...)"
        return BatchPreview(methodName, table.tableName, sql, null)
    }

    /**
     * 参数组说明（docs/项目介绍.md 格式）：`-- 实际参数: [col=?, col=?] × N`。
     */
    private fun buildParamGroups(columns: List<String>): String {
        val group = columns.joinToString(", ") { "$it=?" }
        return "-- 实际参数: [$group] × N（每行一组，运行时值不可静态获取）"
    }
}
