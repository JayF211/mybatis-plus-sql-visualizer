package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.BatchPreview
import cn.song.mybatisplus.visualizer.model.TableInfo
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
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
 *  - `saveBatch(list)` / `insertBatchSomeColumn(list)` / `save(entity)`（M19.5 补注册，与注释一致化）/
 *    `insert(list)` / `insert(entity)`（T-M20-08，BaseMapper 3.5.7+ 批量族，单实体与集合同 INSERT 模板）：
 *    INSERT INTO 表 (列...) VALUES (?, ...)
 *  - `saveOrUpdate(entity)` / `saveOrUpdateBatch(list)`（U12，T-M15-08）/ `insertOrUpdate(entity)`（T-M20-08）：
 *    INSERT + 方言 upsert（PG `ON CONFLICT`）
 *  - `removeByIds(list)` / `removeById(id)` / `deleteByIds(ids)`（U11/P1-02，T-M15-08 + M19.5）/
 *    `removeBatchByIds(list)`（T-M20-10 核对补注册）/ `deleteById(id)`（T-M22-04，Mapper 单删与
 *    ActiveRecord 无参形态同主键等值模板）：DELETE FROM 表 WHERE 主键 IN/=
 *  - `removeByMap(map)` / `deleteByMap(map)`（T-M20-07，MP/MPJ Map 条件删除）与
 *    `selectByMap(map)` / `listByMap(map)`（T-M20-07 Map 条件查询）：
 *    WHERE Map 等值条件占位（键值静态不可得，可读占位文本）
 *  - `getById(id)` / `getOptById(id)`（T-M20-10，IService 3.5.15）/ `listByIds(ids)` / `selectByIds(ids)`
 *    （M19.5，实体单条查询，P2-08 同点合并）：SELECT 全列 FROM 表 WHERE 主键 =/IN
 *  - `selectAll()`（T-M22-04，ActiveRecord）：SELECT 全列 FROM 表（无 WHERE）
 *
 * 实体类解析顺序：集合参数泛型（`List<Entity>`）→ 实体参数类型 → service qualifier 泛型
 * （`XxxService extends IService<Entity>` / `ServiceImpl<Mapper, Entity>`，getById/removeById 单参场景）
 * → qualifier 实体类型（T-M22-04，ActiveRecord `entity.insert()` 等无参形态）。
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
        // T-M20-08：BaseMapper 批量族（MP 3.5.7+）——insert(Collection)/insert(Collection, int)/insert(T)
        // 同名共用一个 INSERT 模板；insert 为通用 Java 方法名，非实体调用由 [isLikelyEntity] 挡掉
        "insert" to Operation.INSERT,
        "save" to Operation.INSERT,
        "saveOrUpdate" to Operation.UPSERT,
        // T-M20-08：BaseMapper.insertOrUpdate（全重载同名，BiPredicate 参数忽略）与 saveOrUpdate 同 upsert 模板
        "insertOrUpdate" to Operation.UPSERT,
        "saveOrUpdateBatch" to Operation.UPSERT,
        "removeByIds" to Operation.DELETE,
        "removeById" to Operation.DELETE,
        "deleteByIds" to Operation.DELETE,
        // T-M22-04：Mapper.deleteById(id) 与 ActiveRecord 无参形态（entity.deleteById()）同模板——主键等值
        "deleteById" to Operation.DELETE,
        // T-M20-10 核对：IService 3.5.15 便捷重载 removeBatchByIds(Collection)——核对时补注册（与 removeByIds 同模板）
        "removeBatchByIds" to Operation.DELETE,
        // T-M20-07：Map 条件消费（MP/MPJ）——DELETE/SELECT WHERE Map 等值条件（独立 builder，见 [buildMapConditionBatch]）
        "removeByMap" to Operation.DELETE,
        "deleteByMap" to Operation.DELETE,
        "getById" to Operation.SELECT,
        // T-M20-10：IService 3.5.15 getOptById(id) → 与 getById 同主键等值模板
        "getOptById" to Operation.SELECT,
        // T-M22-04：ActiveRecord（AbstractModel）selectAll() 全列查询——无 WHERE（buildSelectBatch 方法名分支）
        "selectAll" to Operation.SELECT,
        "listByIds" to Operation.SELECT,
        "selectByIds" to Operation.SELECT,
        "selectByMap" to Operation.SELECT,
        "listByMap" to Operation.SELECT,
    )

    /** T-M20-07：Map 等值条件操作方法名（独立 builder，不并入主键等值/IN 分支）。 */
    private val MAP_CONDITION_METHODS = setOf("removeByMap", "deleteByMap", "selectByMap", "listByMap")

    /** T-M20-10：主键等值单条查询方法名（getById / getOptById，其余批量查询走主键 IN）。 */
    private val SINGLE_ID_SELECT_METHODS = setOf("getById", "getOptById")

    /** T-M22-04：主键等值删除方法名（removeById / deleteById——Mapper 单删与 ActiveRecord 无参形态同模板，其余走主键 IN）。 */
    private val SINGLE_ID_DELETE_METHODS = setOf("removeById", "deleteById")

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
        // T-M20-07：Map 等值条件方法独立分支（键值静态不可得 → 占位文本；避免并入主键等值/IN 模板）
        if (methodName in MAP_CONDITION_METHODS) return buildMapConditionBatch(methodName, table)
        return when (operation) {
            Operation.UPDATE -> buildUpdateBatch(methodName, table)
            Operation.INSERT -> buildInsertBatch(methodName, table)
            Operation.DELETE -> buildDeleteBatch(methodName, table)
            Operation.UPSERT -> buildUpsertBatch(methodName, table)
            Operation.SELECT -> buildSelectBatch(methodName, table)
        }
    }

    /**
     * 解析目标实体表信息（T-M15-08 起支持三种来源，T-M22-04 增补第四种）：
     *  1. 集合参数泛型（`List<Entity>`，批量形态）
     *  2. 实体参数类型（`save(entity)` / `updateById(entity)` 单实体形态）
     *  3. service qualifier 泛型（`getById(id)` / `removeById(id)` 单参形态：`XxxService extends IService<Entity>`）
     *  4. qualifier 实体类型（T-M22-04，ActiveRecord：`entity.insert()` / `entity.updateById()` /
     *     `entity.deleteById()` / `entity.selectAll()` 无实参形态——实体从 qualifier 变量类型解析）
     *
     * M19.6（2026-08-13 用户实测）：步骤 1/2 增加 [isLikelyEntity] 限定——`getById(dto.getId())`
     * 的实参 Long 在真实 IDE 可解析，原实现把 java.lang.Long 当实体（FROM long + serialVersionUID
     * 字段列，用户实测症状）；限定后 Long/String 等基础类型跳过，走步骤 3 service 泛型。
     * T-M22-04：步骤 3 首分支（变量类型自身泛型参数）同样加 [isLikelyEntity] 限定（selectAll 等
     * 常见方法名防误伤——`List<String> list.selectAll()` 的 String 不得解析为实体）；
     * 步骤 4 置于步骤 3 之后：`service.getById(id)` 的 qualifier 是 service 变量（非实体），
     * 由 [isLikelyEntity] 限定保证不误判，实体变量仅在 service 泛型解析失败时兜底。
     */
    private fun resolveEntityTable(call: PsiMethodCallExpression): TableInfo? {
        val firstArg = call.argumentList.expressions.firstOrNull()
        // 1. 集合参数泛型（List<Entity>）；2. 实体参数类型（save(entity) / updateById(entity)）——
        // 无实参调用（ActiveRecord 无参形态）跳过，走步骤 3/4
        if (firstArg != null) {
            val listType = firstArg.type as? PsiClassType
            val entityType = listType?.parameters?.firstOrNull() as? PsiClassType
            val entityFromList = entityType?.resolve()
            if (entityFromList != null && isLikelyEntity(entityFromList)) return EntityResolver().resolve(entityFromList)
            // 类字面量实参（Db.getById(User.class, id) / Db.list(User.class, wrapper)，T-M22-04）显式处理：
            // LightPlatform 下 `User.class` 表达式的 type（Class<User>）可能不可解析，
            // operand 类型（User 本身）更可靠；`save(entity)` 等普通实体实参走 .type 原路径
            val entityFromArg = when (firstArg) {
                is PsiClassObjectAccessExpression -> (firstArg.operand.type as? PsiClassType)?.resolve()
                else -> (firstArg.type as? PsiClassType)?.resolve()
            }
            if (entityFromArg != null && isLikelyEntity(entityFromArg)) return EntityResolver().resolve(entityFromArg)
        }
        // 3. service qualifier 泛型（getById(id) 等单参非实体场景）
        val entityFromService = resolveServiceEntityClass(call)
        if (entityFromService != null) return EntityResolver().resolve(entityFromService)
        // 4. qualifier 实体类型（T-M22-04，ActiveRecord 无参形态）
        val entityFromQualifier = resolveQualifierEntityClass(call) ?: return null
        return EntityResolver().resolve(entityFromQualifier)
    }

    /**
     * T-M22-04（ActiveRecord 识别）：从 qualifier 变量类型解析实体——`entity.insert()` 的
     * qualifier 变量类型即实体本身（AbstractModel 子类）；[isLikelyEntity] 限定避免
     * `service.getById(id)` 等 service/mapper 变量被误判为实体（service 类名无 Entity 后缀、
     * 无 @TableName → 返回 null，保持原步骤 3 语义）。
     */
    private fun resolveQualifierEntityClass(call: PsiMethodCallExpression): PsiClass? {
        val qualifier = call.methodExpression.qualifierExpression as? PsiReferenceExpression ?: return null
        val variable = qualifier.resolve() as? PsiVariable ?: return null
        val varType = variable.type as? PsiClassType ?: return null
        val resolved = varType.resolve() ?: return null
        return resolved.takeIf { isLikelyEntity(it) }
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
        // 变量类型自身泛型（XxxService<Entity>）——T-M22-04：加 [isLikelyEntity] 限定
        // （selectAll 等常见方法名防误伤：`List<String> list.selectAll()` 的 String 不得解析为实体）
        (varType.parameters.firstOrNull() as? PsiClassType)?.resolve()?.takeIf { isLikelyEntity(it) }?.let { return it }
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

    /**
     * UPDATE 模板：非主键列 SET + 主键 WHERE（无主键标记时用首列；updateById 同 update-strategy: always 全列语义）。
     * T-M22-02（@Version 乐观锁）：实体含 @Version 字段时（对齐 MP OptimisticLockerInnerInterceptor）——
     * version 列不进实体 SET（`version = ?` 由 `version = version + 1` 取代），
     * WHERE 末尾追加 `AND version = ?`；参数组说明同步追加 version（buildParamGroups 顺序与 SQL 一致）。
     */
    private fun buildUpdateBatch(methodName: String, table: TableInfo): BatchPreview {
        val idColumn = table.columns.firstOrNull { it.isId } ?: table.columns.first()
        val versionColumn = table.versionColumn
        val setColumns = table.columns.filter { it.columnName != idColumn.columnName && it.columnName != versionColumn }
        val setSql = setColumns.joinToString(", ") { "${it.columnName} = ?" } +
            (versionColumn?.let { ", $it = $it + 1" } ?: "")
        val whereSql = "${idColumn.columnName} = ?" + (versionColumn?.let { " AND $it = ?" } ?: "")
        val sql = "UPDATE ${table.tableName}\nSET $setSql\nWHERE $whereSql"
        val paramGroups = buildParamGroups(setColumns.map { it.columnName } + idColumn.columnName + listOfNotNull(versionColumn))
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

    /** DELETE 模板：主键 IN 列表（removeByIds/deleteByIds）/ 主键等值（removeById，U11；deleteById，T-M22-04）。 */
    private fun buildDeleteBatch(methodName: String, table: TableInfo): BatchPreview {
        val idColumn = table.columns.firstOrNull { it.isId } ?: table.columns.first()
        val sql = if (methodName in SINGLE_ID_DELETE_METHODS) {
            "DELETE FROM ${table.tableName}\nWHERE ${idColumn.columnName} = ?"
        } else {
            "DELETE FROM ${table.tableName}\nWHERE ${idColumn.columnName} IN (?, ?, ...)"
        }
        return BatchPreview(methodName, table.tableName, sql, null)
    }

    /**
     * SELECT 模板（M19.5，实体单条查询，P1-02/P2-08 同点合并）：
     * 全列清单 + 主键等值（getById/getOptById，T-M20-10 并入）/ 主键 IN（listByIds/selectByIds）。
     * T-M22-04：ActiveRecord selectAll() 全列查询——**无 WHERE**（方法名分支）。
     */
    private fun buildSelectBatch(methodName: String, table: TableInfo): BatchPreview {
        val idColumn = table.columns.firstOrNull { it.isId } ?: table.columns.first()
        val selectColumns = table.columns.joinToString(", ") { it.columnName }
        val sql = when {
            methodName == "selectAll" -> "SELECT $selectColumns\nFROM ${table.tableName}"
            methodName in SINGLE_ID_SELECT_METHODS -> "SELECT $selectColumns\nFROM ${table.tableName}\nWHERE ${idColumn.columnName} = ?"
            else -> "SELECT $selectColumns\nFROM ${table.tableName}\nWHERE ${idColumn.columnName} IN (?, ?, ...)"
        }
        return BatchPreview(methodName, table.tableName, sql, null)
    }

    /**
     * T-M20-07（Map 条件消费）：`removeByMap(Map)` / `deleteByMap(Map)` / `selectByMap(Map)` / `listByMap(Map)`
     * 的 Map 等值条件模板——Map 键值均为运行时数据，列名与取值静态不可得，WHERE 用可读占位文本
     * `col = ? AND col = ? ...`；删除/查询由方法名前缀区分。参数组说明置空（Map 键不可静态枚举）。
     */
    private fun buildMapConditionBatch(methodName: String, table: TableInfo): BatchPreview {
        val isDelete = methodName.startsWith("remove") || methodName.startsWith("delete")
        val condition = "<Map 等值条件: col = ? AND col = ? ...>"
        val sql = if (isDelete) {
            "DELETE FROM ${table.tableName}\nWHERE $condition"
        } else {
            val selectColumns = table.columns.joinToString(", ") { it.columnName }
            "SELECT $selectColumns\nFROM ${table.tableName}\nWHERE $condition"
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
