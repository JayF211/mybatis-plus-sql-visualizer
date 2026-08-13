package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.BatchPreview
import cn.song.mybatisplus.visualizer.model.TableInfo
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable

/**
 * 批量/单实体操作转换器（F-15，M6-01 + T-M15-08 扩展 + M19.5 实体单条 CRUD）：识别
 * `service.updateBatchById(list)` 等调用，生成 SQL 模板 + 参数组说明。
 *
 * 支持的批量方法：
 *  - `updateBatchById(list)` / `updateById(entity)`：UPDATE 表 SET 非主键列 = ? WHERE 主键 = ?
 *  - `saveBatch(list)` / `insertBatchSomeColumn(list)` / `save(entity)`（M19.5 补注册，与注释一致化）：
 *    INSERT INTO 表 (列...) VALUES (?, ...)
 *  - `saveOrUpdate(entity)` / `saveOrUpdateBatch(list)`（U12，T-M15-08）：INSERT + 方言 upsert（PG `ON CONFLICT`）
 *  - `removeByIds(list)` / `removeById(id)` / `deleteByIds(ids)`（U11/P1-02，T-M15-08 + M19.5）：
 *    DELETE FROM 表 WHERE 主键 IN/=
 *  - `getById(id)` / `listByIds(ids)` / `selectByIds(ids)`（M19.5，实体单条查询，P2-08 同点合并）：
 *    SELECT 全列 FROM 表 WHERE 主键 =/IN
 *
 * 实体类解析顺序：集合参数泛型（`List<Entity>`）→ 实体参数类型 → service qualifier 泛型
 * （`XxxService extends IService<Entity>` / `ServiceImpl<Mapper, Entity>`，getById/removeById 单参场景）。
 * 运行时行值不可得，输出 `?` 模板并附参数组说明（每行一组，字段顺序与 SQL 一致）。
 */
object BatchOperationConverter {

    /** 批量方法 → SQL 模板生成策略。 */
    private enum class Operation(val sqlPrefix: String) {
        UPDATE("UPDATE"),
        INSERT("INSERT"),
        DELETE("DELETE"),
        UPSERT("UPSERT"),
        SELECT("SELECT"),
    }

    private val BATCH_OPERATIONS = mapOf(
        "updateBatchById" to Operation.UPDATE,
        "updateById" to Operation.UPDATE,
        "saveBatch" to Operation.INSERT,
        "insertBatchSomeColumn" to Operation.INSERT,
        "save" to Operation.INSERT,
        "saveOrUpdate" to Operation.UPSERT,
        "saveOrUpdateBatch" to Operation.UPSERT,
        "removeByIds" to Operation.DELETE,
        "removeById" to Operation.DELETE,
        "deleteByIds" to Operation.DELETE,
        "getById" to Operation.SELECT,
        "listByIds" to Operation.SELECT,
        "selectByIds" to Operation.SELECT,
    )

    /** 批量操作方法名集合（M10 起 internal 供 [WrapperAnchorDetector] gutter 锚点复用）。 */
    internal val batchOperationMethodNames: Set<String> = BATCH_OPERATIONS.keys

    /**
     * 转换批量/单实体方法调用。
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
            Operation.UPSERT -> buildUpsertBatch(methodName, table)
            Operation.SELECT -> buildSelectBatch(methodName, table)
        }
    }

    /**
     * 解析目标实体表信息（T-M15-08 起支持三种来源）：
     *  1. 集合参数泛型（`List<Entity>`，批量形态）
     *  2. 实体参数类型（`save(entity)` / `updateById(entity)` 单实体形态）
     *  3. service qualifier 泛型（`getById(id)` / `removeById(id)` 单参形态：`XxxService extends IService<Entity>`）
     *
     * M19.6（2026-08-13 用户实测）：步骤 1/2 增加 [isLikelyEntity] 限定——`getById(dto.getId())`
     * 的实参 Long 在真实 IDE 可解析，原实现把 java.lang.Long 当实体（FROM long + serialVersionUID
     * 字段列，用户实测症状）；限定后 Long/String 等基础类型跳过，走步骤 3 service 泛型。
     */
    private fun resolveEntityTable(call: PsiMethodCallExpression): TableInfo? {
        val firstArg = call.argumentList.expressions.firstOrNull() ?: return null
        // 1. 集合参数泛型（List<Entity>）
        val listType = firstArg.type as? PsiClassType
        val entityType = listType?.parameters?.firstOrNull() as? PsiClassType
        val entityFromList = entityType?.resolve()
        if (entityFromList != null && isLikelyEntity(entityFromList)) return EntityResolver().resolve(entityFromList)
        // 2. 实体参数类型（save(entity) / updateById(entity)）
        val entityFromArg = firstArg.type as? PsiClassType
        val entityClassFromArg = entityFromArg?.resolve()
        if (entityClassFromArg != null && isLikelyEntity(entityClassFromArg)) return EntityResolver().resolve(entityClassFromArg)
        // 3. service qualifier 泛型（getById(id) 等单参非实体场景）
        val entityFromService = resolveServiceEntityClass(call) ?: return null
        return EntityResolver().resolve(entityFromService)
    }

    /**
     * 实体类启发式判定（M19.6）：仅接受 **@TableName 注解实体**（真实实体，兼容无 Entity 后缀命名
     * 如 SpWorkflow）或**类名含 "Entity"** 的类；排除 JDK 基础类型（java./javax. 前缀）——
     * 避免 getById/removeById 的 Long/String 等实参类型被误当实体解析。
     */
    private fun isLikelyEntity(psiClass: PsiClass): Boolean {
        val qualifiedName = psiClass.qualifiedName ?: return false
        if (qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.")) return false
        if (psiClass.annotations.any { (it.qualifiedName ?: "").endsWith(".TableName") }) return true
        val name = psiClass.name ?: return false
        return "Entity" in name
    }

    /**
     * 从 service qualifier 解析实体类（`XxxService extends IService<Entity>` / `ServiceImpl<Mapper, Entity>`）：
     * 变量类型自身泛型参数优先（`XxxService<Entity>`），否则沿父类型链取「类名以 Entity 结尾的最后一个类型参数」。
     */
    private fun resolveServiceEntityClass(call: PsiMethodCallExpression): PsiClass? {
        val qualifier = call.methodExpression.qualifierExpression as? PsiReferenceExpression ?: return null
        val variable = qualifier.resolve() as? PsiVariable ?: return null
        val varType = variable.type as? PsiClassType ?: return null
        // 变量类型自身泛型（XxxService<Entity>）
        (varType.parameters.firstOrNull() as? PsiClassType)?.resolve()?.let { return it }
        // 父类/接口泛型参数（ServiceImpl<M, Entity> 的 Entity、IService<Entity> 的 Entity）
        val clazz = varType.resolve() ?: return null
        for (superType in clazz.superTypes) {
            val params = (superType as? PsiClassType)?.parameters ?: continue
            val candidate = params.lastOrNull() as? PsiClassType ?: continue
            val resolved = candidate.resolve() ?: continue
            if (resolved.name?.endsWith(ENTITY_SUFFIX) == true) return resolved
        }
        return null
    }

    /** UPDATE 模板：非主键列 SET + 主键 WHERE（无主键标记时用首列；updateById 同 update-strategy: always 全列语义）。 */
    private fun buildUpdateBatch(methodName: String, table: TableInfo): BatchPreview {
        val idColumn = table.columns.firstOrNull { it.isId } ?: table.columns.first()
        val setColumns = table.columns.filter { it.columnName != idColumn.columnName }
        val setSql = setColumns.joinToString(", ") { "${it.columnName} = ?" }
        val sql = "UPDATE ${table.tableName}\nSET $setSql\nWHERE ${idColumn.columnName} = ?"
        val paramGroups = buildParamGroups(setColumns.map { it.columnName } + idColumn.columnName)
        return BatchPreview(methodName, table.tableName, sql, paramGroups)
    }

    /**
     * INSERT 模板：全列 + VALUES。
     * `insertBatchSomeColumn`（自定义注入器，X09）：排除 `FieldFill.UPDATE` 填充列（update 侧维护，插入不参与）；
     * `save(entity)` 单实体同模板（F-15 语义一致）。
     */
    private fun buildInsertBatch(methodName: String, table: TableInfo): BatchPreview {
        val columns = if (methodName == "insertBatchSomeColumn") {
            table.columns.filter { it.fill != "UPDATE" }.map { it.columnName }
        } else {
            table.columns.map { it.columnName }
        }
        val placeholders = columns.joinToString(", ") { "?" }
        val sql = "INSERT INTO ${table.tableName} (${columns.joinToString(", ")})\nVALUES ($placeholders)"
        return BatchPreview(methodName, table.tableName, sql, buildParamGroups(columns))
    }

    /** DELETE 模板：主键 IN 列表（removeByIds/deleteByIds）/ 主键等值（removeById，U11）。 */
    private fun buildDeleteBatch(methodName: String, table: TableInfo): BatchPreview {
        val idColumn = table.columns.firstOrNull { it.isId } ?: table.columns.first()
        val sql = if (methodName == "removeById") {
            "DELETE FROM ${table.tableName}\nWHERE ${idColumn.columnName} = ?"
        } else {
            "DELETE FROM ${table.tableName}\nWHERE ${idColumn.columnName} IN (?, ?, ...)"
        }
        return BatchPreview(methodName, table.tableName, sql, null)
    }

    /**
     * SELECT 模板（M19.5，实体单条查询，P1-02/P2-08 同点合并）：
     * 全列清单 + 主键等值（getById）/ 主键 IN（listByIds/selectByIds）。
     */
    private fun buildSelectBatch(methodName: String, table: TableInfo): BatchPreview {
        val idColumn = table.columns.firstOrNull { it.isId } ?: table.columns.first()
        val selectColumns = table.columns.joinToString(", ") { it.columnName }
        val sql = if (methodName == "getById") {
            "SELECT $selectColumns\nFROM ${table.tableName}\nWHERE ${idColumn.columnName} = ?"
        } else {
            "SELECT $selectColumns\nFROM ${table.tableName}\nWHERE ${idColumn.columnName} IN (?, ?, ...)"
        }
        return BatchPreview(methodName, table.tableName, sql, null)
    }

    /**
     * UPSERT 模板（saveOrUpdate / saveOrUpdateBatch，U12，T-M15-08）：
     * INSERT + PG 方言 `ON CONFLICT (主键) DO UPDATE SET 非主键列 = EXCLUDED.列`。
     */
    private fun buildUpsertBatch(methodName: String, table: TableInfo): BatchPreview {
        val idColumn = table.columns.firstOrNull { it.isId } ?: table.columns.first()
        val columns = table.columns.map { it.columnName }
        val placeholders = columns.joinToString(", ") { "?" }
        val updateColumns = table.columns.filter { it.columnName != idColumn.columnName }
        val conflictSet = updateColumns.joinToString(", ") { "${it.columnName} = EXCLUDED.${it.columnName}" }
        val sql = "INSERT INTO ${table.tableName} (${columns.joinToString(", ")})\n" +
            "VALUES ($placeholders)\n" +
            "ON CONFLICT (${idColumn.columnName}) DO UPDATE SET $conflictSet"
        return BatchPreview(methodName, table.tableName, sql, buildParamGroups(columns))
    }

    /**
     * 参数组说明（docs/项目介绍.md 格式）：`-- 实际参数: [col=?, col=?] × N`。
     */
    private fun buildParamGroups(columns: List<String>): String {
        val group = columns.joinToString(", ") { "$it=?" }
        return "-- 实际参数: [$group] × N（每行一组，运行时值不可静态获取）"
    }

    private const val ENTITY_SUFFIX = "Entity"
}
