package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver
import com.intellij.openapi.project.Project
import cn.song.mybatisplus.visualizer.psi.jpa.EntityAliasSweep
import com.intellij.psi.PsiElement

/**
 * FROM/JOIN 子句实体名 → 表名扫描器（J13，覆盖矩阵 §3「标量子查询 / IN(子查询) / EXISTS」行）。
 *
 * 背景：既有 [cn.song.mybatisplus.visualizer.converter.jpa.HqlConverter.mapEntityToTable] 与
 * JpqlConverter 同名方法只替换 **主实体类名**（全局正则）——主实体在子查询 FROM 中复现
 * （`EXISTS (SELECT 1 FROM UserEntity u2 ...)`）天然覆盖；但子查询引用 **其他实体**
 * （`... EXISTS (SELECT o FROM OrderEntity o WHERE o.userId = u.id)`）时，主实体的
 * TableInfo 不携带 OrderEntity 的表名映射，字符串管道无从替换。
 *
 * 本扫描器在调用方提供 Project 时，扫描 SQL 全文所有 `FROM <标识符>` / `JOIN <标识符>`
 * 出现点（含括号内的子查询——正则全文匹配），保留完整实体标识符（含 FQN 与小写命名实体），
 * 经共享实体名入口按查询宿主作用域查找，用 [JpaEntityResolver] 解析真实表名后替换。
 * 只做**有元数据依据**的替换：查不到实体类的标识符原样保留（不做命名策略盲猜——
 * 猜错表名比保留实体名更误导），因此无 Project 时调用方应跳过本扫描，行为与既有管道一致。
 *
 * O-57 起调用方还可传入 [knownTableNames]（别名作用域扫描已解析出的「实体名 → 表名」）：
 * 命中即免一次 PSI 检索；无 Project 而 [knownTableNames] 非空时仍可完成替换
 * （解析来源不同，替换结果一致）。
 *
 * 边界（刻意不做）：
 *  - 不解析子查询结构（EXISTS/IN 子查询按「识别 + 透传」处理，见 SelectClauseExpander）
 *  - 已映射表名在生产管道中不再按实体名检索；小写 @Entity(name) 仍保留支持
 *  - 同一标识符只处理一次；完整标识符边界避免简单名误改另一个 FQN
 *
 * PSI 读取只封装在本文件内，只读不修改 psi/jpa/ 下任何文件。
 * 无状态单例，普通 PSI 读取失败静默跳过；取消和 fatal 异常必须传播。
 */
internal object JpaFromEntityMapper {

    /**
     * FROM / JOIN 后的完整标识符；大小写不敏感仅作用于关键字。
     * 实体名保持精确大小写，支持 FQN 与小写 @Entity(name)。
     */
    private val ENTITY_REFERENCE = Regex(
        "(?i:\\b(?:FROM|JOIN)\\s+)([a-zA-Z_][a-zA-Z0-9_.$]*)\\b",
    )

    /**
     * 扫描并替换子查询内的其他实体名为表名（Project 形态——生产入口）。
     *
     * @param sql    管道中的 SQL 文本（主实体名已被既有 mapEntityToTable 替换）
     * @param table  主表信息（其 entityClassName 已处理，跳过）
     * @param project 项目（PSI 按名查找实体类；null 时原样返回——无元数据不做盲猜）
     * @param knownTableNames 调用方已解析出的「实体名 → 表名」（O-57：别名作用域扫描的副产物）。
     *        命中即免一次 PSI 检索；缺省空表时行为与历史版本一致。
     *        O-57 起 `project == null` 不再直接短路（改为「project 与 knownTableNames 同时为空才短路」）：
     *        该守卫**只在注入形态下才会真正放开**——生产 `convert(..., project = null)` 时
     *        [knownTableNames] 唯一可能的键是根实体名，而本扫描器明确过滤 `it != table.entityClassName`，
     *        故对既有调用方逐字等价（独立复核确认）。
     * @return 替换后的 SQL；无可替换实体时原样返回（幂等）
     */
    fun mapSubqueryEntities(
        sql: String,
        table: TableInfo,
        project: Project?,
        knownTableNames: Map<String, String> = emptyMap(),
        context: PsiElement? = null,
    ): String {
        if (project == null && knownTableNames.isEmpty()) return sql
        return mapSubqueryEntities(sql, table) { entityName ->
            knownTableNames[entityName] ?: (if (project == null) null else recoverJpaPsi {
                EntityAliasSweep.resolve(project, entityName, context).psiClass?.let {
                    JpaEntityResolver().resolve(it).tableName
                        .takeIf { name -> name.isNotBlank() && name != "unknown_table" }
                }
            })
        }
    }

    /**
     * 扫描并替换子查询内的其他实体名（表名解析函数注入形态——核心实现）。
     *
     * [resolveTableName] 收到实体类名，返回真实表名；返回 null 表示无元数据（跳过不改）。
     * 调用方已有 PsiClass / 解析缓存时用本形态，免按名检索
     * （LightPlatform 测试环境无需依赖索引）。
     */
    internal fun mapSubqueryEntities(
        sql: String,
        table: TableInfo,
        resolveTableName: (String) -> String?,
    ): String {
        // 普查批：字面量掩码——字面量内的 `from OrderEntity`（FROM/JOIN 假象）不得驱动扫描，
        // 字面量内的实体名原文不得被替换正则命中
        val (masked, literals) = JpaSqlLiteralMasking.mask(sql)
        val entityNames = ENTITY_REFERENCE.findAll(masked)
            .map { it.groupValues[1] }
            .filter { it != table.entityClassName }
            .distinct()
            .toList()
        if (entityNames.isEmpty()) return JpaSqlLiteralMasking.unmask(masked, literals)

        var result = masked
        for (entityName in entityNames) {
            // 已无该实体名残留（如实体名与表名同形被主映射替换）时跳过，保证幂等
            val entityPattern = Regex("(?<![\\w.$])${Regex.escape(entityName)}\\b")
            if (!entityPattern.containsMatchIn(result)) continue
            val tableName = resolveTableName(entityName) ?: continue
            result = entityPattern.replace(result, tableName)
        }
        return JpaSqlLiteralMasking.unmask(result, literals)
    }
}
