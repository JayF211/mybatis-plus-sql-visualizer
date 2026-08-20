package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.model.JoinType

/**
 * JPA CriteriaQuery 中间表示（CriteriaQueryLocator 产物，CriteriaQueryConverter 输入）。
 *
 * 从 PSI 调用链中提取的结构化查询模型，涵盖 FROM / SELECT / WHERE / ORDER BY / JOIN。
 */
data class CriteriaQueryModel(
    /** 实体类全限定名（from 的实体类）。 */
    val entityClassName: String? = null,
    /** 实体类 PsiClass（可解析时非 null）。 */
    val entityClass: com.intellij.psi.PsiClass? = null,
    /** SELECT 列列表（root.get(...) 形式）；空表示 select(root) 全列。 */
    val selectColumns: List<SelectColumn> = emptyList(),
    /** WHERE 条件中间列表。 */
    val predicates: List<PredicateModel> = emptyList(),
    /** ORDER BY 列表。 */
    val orderBys: List<OrderByModel> = emptyList(),
    /** JOIN 列表。 */
    val joins: List<JoinModel> = emptyList(),
    /** createQuery 的结果类型全限定名（与 entityClassName 通常一致；多态投影时可能不同）。 */
    val resultClassName: String? = null,
)

/**
 * SELECT 列中间表示。
 *
 * @param fieldName  实体字段名（驼峰，如 shareSchemeId）
 * @param alias      列别名（当前未使用，预留）
 */
data class SelectColumn(
    val fieldName: String,
    val alias: String? = null,
)

/**
 * WHERE 条件中间表示。
 */
sealed class PredicateModel {

    /** 简单二元比较：column op value。 */
    data class Simple(
        val fieldName: String,
        val op: String,
        val valueExpression: String,
    ) : PredicateModel()

    /** 一元条件：IS NULL / IS NOT NULL。 */
    data class IsNull(
        val fieldName: String,
        val negated: Boolean,
    ) : PredicateModel()

    /** BETWEEN 范围条件。 */
    data class Between(
        val fieldName: String,
        val loExpression: String,
        val hiExpression: String,
    ) : PredicateModel()

    /** LIKE 模糊匹配。 */
    data class Like(
        val fieldName: String,
        val negated: Boolean,
        val patternExpression: String,
    ) : PredicateModel()

    /** IN 列表条件。 */
    data class In(
        val fieldName: String,
        val negated: Boolean,
        val valueExpressions: List<String>,
    ) : PredicateModel()

    /** 逻辑分组（and / or）。 */
    data class Group(
        val connector: GroupConnector,
        val children: List<PredicateModel>,
        val negated: Boolean = false,
    ) : PredicateModel()

    /** 无法识别的调用降级。 */
    data class Raw(
        val expressionText: String,
    ) : PredicateModel()
}

/** 条件组连接符。 */
enum class GroupConnector { AND, OR }

/**
 * ORDER BY 中间表示。
 *
 * @param fieldName 实体字段名
 * @param asc       true=ASC / false=DESC
 */
data class OrderByModel(
    val fieldName: String,
    val asc: Boolean,
)

/**
 * JOIN 中间表示。
 *
 * @param fieldName  关联属性名（如 "department"）
 * @param entityClassName 关联实体类名（可解析时非 null）
 * @param joinType    联表类型
 * @param alias       关联表别名（未指定时为 null）
 */
data class JoinModel(
    val fieldName: String,
    val entityClassName: String? = null,
    val joinType: JoinType = JoinType.LEFT,
    val alias: String? = null,
)
