package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.TableInfo
import com.intellij.openapi.project.Project

/**
 * HQL/JPQL 关联 JOIN 改写器（J7：JOIN FETCH / JOIN 的 ON 条件真实化入口）。
 *
 * [HqlConverter] 与 [JpqlConverter] 共用，两条链路输出一致：
 *  - `JOIN FETCH u.department d` → `LEFT JOIN <副表> d ON <真实或推断 ON>`
 *    （Hibernate 语义 FETCH JOIN 默认 LEFT OUTER；无元数据时保留既有猜测文本与
 *    「推断的ON条件，请验证」注释，存量测试按此断言）
 *  - `JOIN u.department d`（无 FETCH 的关联 JOIN）→ `INNER JOIN <副表> d ON <真实 ON>`
 *    （JPQL 语义默认 INNER）——**仅当** [TableInfo.associations] 命中该关联属性
 *    或 PSI 注解解析成功（真实 ON 有据）才改写；查不到元数据时原样保留
 *    （不编造 ON，也避免误伤 `JOIN 表名 别名 ON ...` 的显式 SQL 形态——
 *    那类 JOIN 的表名不带点号，天然不命中本改写的 `别名.属性` 形态）
 *
 * 副表名取值：associations 命中时用其 targetTable（来自目标实体 @Table，最准）；
 * 否则按驼峰转下划线推断。别名缺省时用关联目标建议别名（associations.targetAlias），
 * 再退到关联属性名。
 *
 * ON 条件由 [JpaJoinOnResolver] 按三级优先解析（PSI 注解 > 关联元数据 > 猜测降级）。
 *
 * 无状态单例，可独立单测。
 */
internal object JpaJoinRewriter {

    /** 捕获组：1 前导空白 | 2 JOIN 类型 | 3 根别名.关联属性 | 4 可选副表别名。 */
    private val JOIN_FETCH = Regex(
        "(\\s+)(LEFT|INNER)?\\s*JOIN\\s+FETCH\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)" +
            "(\\s+[a-zA-Z_][a-zA-Z0-9_]*)?",
        RegexOption.IGNORE_CASE,
    )

    /** 无 FETCH 的关联 JOIN（`JOIN 别名.属性 [别名]`；后随非标识符字符）。 */
    private val ASSOCIATION_JOIN = Regex(
        "(\\s+)(LEFT|INNER)?\\s*JOIN\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)" +
            "(\\s+[a-zA-Z_][a-zA-Z0-9_]*)?(?=[^a-zA-Z0-9_]|$)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 改写 SQL 中的全部 JOIN FETCH 与关联 JOIN；无匹配原样返回。
     *
     * @param context T-EntityNameScope：HQL 所在 PSI 元素，透传给根实体解析做同名消歧；null = 无上下文
     */
    fun rewrite(
        sql: String,
        table: TableInfo,
        project: Project?,
        context: com.intellij.psi.PsiElement? = null,
    ): String {
        val afterFetch = rewriteInternal(sql, table) { rootAlias, relationField, joinAlias ->
            project?.let {
                JpaJoinOnResolver.resolveWithPsi(
                    it, table.entityClassName, rootAlias, relationField, joinAlias, table, context,
                )
            }
        }
        return afterFetch
    }

    /**
     * 改写形态二：根实体 PsiClass 直入（跳过按名检索，LightPlatform 测试环境
     * 无需依赖索引；生产方已有 PsiClass 时亦可直入）。
     */
    fun rewrite(sql: String, table: TableInfo, entityClass: com.intellij.psi.PsiClass?): String {
        return rewriteInternal(sql, table) { rootAlias, relationField, joinAlias ->
            entityClass?.let {
                JpaJoinOnResolver.resolveWithPsi(it, rootAlias, relationField, joinAlias, table)
            }
        }
    }

    /** 改写核心：JOIN FETCH 恒改写（既有行为）；关联 JOIN 仅在真实 ON 有据时改写。 */
    private fun rewriteInternal(
        sql: String,
        table: TableInfo,
        psiResolve: (rootAlias: String, relationField: String, joinAlias: String) -> JpaJoinOnResolver.OnResolution?,
    ): String {
        val afterFetch = rewriteJoinFetch(sql, table, psiResolve)
        return rewriteAssociationJoin(afterFetch, table, psiResolve)
    }

    /** `JOIN FETCH 别名.属性 [别名]` → `[LEFT|INNER] JOIN 副表 [别名] ON 条件`。 */
    private fun rewriteJoinFetch(
        sql: String,
        table: TableInfo,
        psiResolve: (String, String, String) -> JpaJoinOnResolver.OnResolution?,
    ): String {
        return JOIN_FETCH.replace(sql) { match ->
            val leadingWhitespace = match.groupValues[1]
            val joinType = match.groupValues[2].uppercase().ifEmpty { "LEFT" } // FETCH 默认 LEFT OUTER
            val rootAlias = match.groupValues[3]
            val relationName = match.groupValues[4]
            val explicitAlias = normalizeAlias(match.groupValues[5])

            val association = table.associationByField(relationName)
            // 先定副表别名（explicit > 目标建议别名 > 属性名），ON 条件按最终别名构建
            val alias = explicitAlias
                ?: association?.targetAlias
                ?: relationName
            val on = psiResolve(rootAlias, relationName, alias)
                ?: JpaJoinOnResolver.resolve(table, rootAlias, relationName, alias)

            val tableName = association?.targetTable
                ?: on.targetTable
                ?: relationName.replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]}_${it.groupValues[2].lowercase()}" }

            "$leadingWhitespace$joinType JOIN $tableName $alias ON ${on.onCondition}"
        }
    }

    /**
     * `JOIN 别名.属性 [别名]` → `[INNER|LEFT] JOIN 副表 [别名] ON 条件`。
     * 仅当关联元数据（associations 或 PSI）可确认该属性确为关联时改写，否则原样保留。
     */
    private fun rewriteAssociationJoin(
        sql: String,
        table: TableInfo,
        psiResolve: (String, String, String) -> JpaJoinOnResolver.OnResolution?,
    ): String {
        return ASSOCIATION_JOIN.replace(sql) { match ->
            val leadingWhitespace = match.groupValues[1]
            val joinType = match.groupValues[2].uppercase().ifEmpty { "INNER" } // JPQL 关联 JOIN 默认 INNER
            val rootAlias = match.groupValues[3]
            val relationName = match.groupValues[4]
            val explicitAlias = normalizeAlias(match.groupValues[5])

            val association = table.associationByField(relationName)
            val alias = explicitAlias
                ?: association?.targetAlias
                ?: relationName
            // 真实 ON 无据（元数据未命中）→ 不改写（防误伤显式 SQL 表名 JOIN）
            val on = psiResolve(rootAlias, relationName, alias)
            if (association == null && (on == null || !on.resolved)) return@replace match.value

            val resolution = on ?: JpaJoinOnResolver.resolve(table, rootAlias, relationName, alias)
            val tableName = association?.targetTable
                ?: resolution.targetTable
                ?: relationName.replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]}_${it.groupValues[2].lowercase()}" }

            "$leadingWhitespace$joinType JOIN $tableName $alias ON ${resolution.onCondition}"
        }
    }

    /** 副表别名归一化：捕获组缺省 / 命中子句关键字时视为未提供别名。 */
    private fun normalizeAlias(captured: String): String? {
        val alias = captured.trim()
        if (alias.isEmpty()) return null
        val keywords = setOf(
            "ON", "WHERE", "SET", "GROUP", "ORDER", "HAVING", "INNER", "LEFT",
            "RIGHT", "OUTER", "CROSS", "FULL", "JOIN", "FETCH", "AS", "UNION", "WITH",
        )
        return alias.takeIf { it.uppercase() !in keywords }
    }
}
