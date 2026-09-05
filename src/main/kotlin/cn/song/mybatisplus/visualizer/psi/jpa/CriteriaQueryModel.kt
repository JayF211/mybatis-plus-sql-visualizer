package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.model.JoinType

/**
 * JPA CriteriaQuery 中间表示（CriteriaQueryLocator 产物，CriteriaQueryConverter 输入）。
 *
 * 从 PSI 调用链中提取的结构化查询模型，涵盖 FROM / SELECT / WHERE / GROUP BY / HAVING / ORDER BY / JOIN，
 * 以及 SELECT DISTINCT、CriteriaUpdate / CriteriaDelete（T-J4 / T-J6 / T-J7 / T-J17）。
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
    /** GROUP BY 列列表。 */
    val groupBys: List<GroupByModel> = emptyList(),
    /** HAVING 条件中间列表（顶层以 AND 连接）。 */
    val havings: List<PredicateModel> = emptyList(),
    /** ORDER BY 列表。 */
    val orderBys: List<OrderByModel> = emptyList(),
    /** JOIN 列表。 */
    val joins: List<JoinModel> = emptyList(),
    /** createQuery 的结果类型全限定名（与 entityClassName 通常一致；多态投影时可能不同）。 */
    val resultClassName: String? = null,
    /** 是否 SELECT DISTINCT（T-J4：cq.distinct(true)）。 */
    val distinct: Boolean = false,
    /** 操作类型（T-J6）：createQuery / createCriteriaUpdate / createCriteriaDelete 识别产物。 */
    val operation: CriteriaOperation = CriteriaOperation.SELECT,
    /** UPDATE SET 赋值列表（T-J6，仅 operation=UPDATE 时非空）。 */
    val setClauses: List<CriteriaSetClause> = emptyList(),
)

/**
 * Criteria 查询操作类型（T-J6）。
 */
enum class CriteriaOperation { SELECT, UPDATE, DELETE }

/**
 * UPDATE SET 赋值中间表示（T-J6）。
 *
 * @param fieldName       目标实体字段名（set(root.get("name"), v) / set("name", v) 的字段）
 * @param valueExpression 值的 Java 源码文本；setNull(path) 或 set(path, null) 时为 "null"
 */
data class CriteriaSetClause(
    val fieldName: String,
    val valueExpression: String? = null,
)

/**
 * SELECT 列中间表示。
 *
 * @param fieldName  实体字段名（驼峰，如 shareSchemeId）；聚合函数/表达式列时为 null
 * @param alias      列别名（当前未使用，预留）
 * @param aggregate  聚合函数信息；null 表示非聚合列
 * @param expression 复杂表达式列（T-J17：CASE / COALESCE / CONCAT / 算术运算等）；非 null 时由
 *                   converter 渲染为 rawExpr
 */
data class SelectColumn(
    val fieldName: String?,
    val alias: String? = null,
    val aggregate: AggregateInfo? = null,
    val expression: ExpressionModel? = null,
)

/**
 * 聚合函数信息（P1-5 CriteriaQuery 聚合函数支持，T-J17 扩展）。
 *
 * @param function  聚合函数名（COUNT / SUM / MAX / MIN / AVG / greatest→MAX / least→MIN）
 * @param fieldName 聚合目标字段名；COUNT(*) 时为 null
 * @param distinct  是否 DISTINCT（countDistinct → COUNT(DISTINCT x)）
 * @param castType  聚合前 CAST 目标类型（sumAsLong → BIGINT / sumAsDouble → DOUBLE）
 * @param expression 聚合内层复杂表达式（如 AVG(COALESCE(age, 0))）；fieldName 直取列时为 null
 */
data class AggregateInfo(
    val function: String,
    val fieldName: String? = null,
    val distinct: Boolean = false,
    val castType: String? = null,
    val expression: ExpressionModel? = null,
)

/**
 * WHERE 条件中间表示。
 */
sealed class PredicateModel {

    /** 简单二元比较：column op value（左值为普通列引用的快速路径）。 */
    data class Simple(
        val fieldName: String,
        val op: String,
        val valueExpression: String,
    ) : PredicateModel()

    /**
     * 表达式比较（T-J17）：左值为函数/运算/CASE 表达式（LOWER(name)、(a + b)、CASE ...）。
     *
     * op 另支持 "IS NULL" / "IS NOT NULL" / "LIKE" / "NOT LIKE"（右值仅 LIKE 需要）。
     */
    data class ExpressionComparison(
        val left: ExpressionModel,
        val op: String,
        val right: ExpressionModel,
    ) : PredicateModel()

    /** 表达式 BETWEEN（T-J17：cb.between(cb.lower(x), lo, hi) 等左值为表达式的形态）。 */
    data class ExpressionBetween(
        val left: ExpressionModel,
        val lo: ExpressionModel,
        val hi: ExpressionModel,
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

    /**
     * LIKE 模糊匹配。
     *
     * @param escapeChar escapeChar 重载的转义字符源码文本（T-J17：cb.like(x, p, '!') → ESCAPE）；null 表示无
     */
    data class Like(
        val fieldName: String,
        val negated: Boolean,
        val patternExpression: String,
        val escapeChar: String? = null,
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

    /** 布尔常量谓词（T-J17）：conjunction → 1 = 1 / disjunction → 1 = 0。 */
    data class BooleanConstant(
        val value: Boolean,
    ) : PredicateModel()

    /** 列对列比较（Join.on 显式 ON / root.get 与 join.get 互比；T-J7）。 */
    data class ColumnEquals(
        val leftFieldName: String,
        val rightFieldName: String,
        val op: String = "=",
        /** 左/右值 get 调用的接收者变量名（root / join 变量），converter 据此映射表别名。 */
        val leftVariable: String? = null,
        val rightVariable: String? = null,
    ) : PredicateModel()

    /** EXISTS 子查询（T-J17/J13 有界实现：单层子查询）。 */
    data class Exists(
        val subQuery: SubqueryModel,
        val negated: Boolean = false,
    ) : PredicateModel()

    /** 列 IN 子查询（T-J17/J13：path.get("x").in(subquery)）。 */
    data class InSubQuery(
        val fieldName: String,
        val subQuery: SubqueryModel,
        val negated: Boolean = false,
    ) : PredicateModel()

    /** 无法识别的调用降级（含集合谓词等刻意注释降级的形态）。 */
    data class Raw(
        val expressionText: String,
    ) : PredicateModel()
}

/** 条件组连接符。 */
enum class GroupConnector { AND, OR }

/**
 * CriteriaBuilder 表达式中间表示（T-J17）。
 *
 * 覆盖字符串/数值/算术/转换/CASE 等表达式函数；叶子为列引用、字面量/变量值或降级原文。
 * 列名映射（实体字段 → 数据库列）由 converter 按表元数据完成，locator 只保留字段名。
 */
sealed class ExpressionModel {

    /** 列引用：root.get("field") / root.get(Xxx_.field)。 */
    data class Column(
        val fieldName: String,
    ) : ExpressionModel()

    /**
     * 函数/运算节点。
     *
     * @param function 规范化函数标记：普通函数用 SQL 名（LOWER/CONCAT/ABS...），算术用
     *                 ADD/SUB/MUL/DIV/MOD/NEG，CAST 以 [modifier] 携带目标类型，TRIM 以
     *                 [modifier] 携带 Trimspec
     * @param args     参数表达式
     * @param modifier 附加修饰（CAST 类型 / TRIM 规格等）
     */
    data class Function(
        val function: String,
        val args: List<ExpressionModel> = emptyList(),
        val modifier: String? = null,
    ) : ExpressionModel()

    /**
     * CASE 表达式（T-J17）：selectCase().when(cond, r)...otherwise(e) 与 selectCase(expr).when(v, r)...。
     *
     * @param simpleExpr SimpleCase 基准表达式；searched case 为 null
     * @param whens      WHEN 分支（按调用顺序）
     * @param otherwise  ELSE 分支；无 otherwise 时为 null
     */
    data class Case(
        val simpleExpr: ExpressionModel? = null,
        val whens: List<CaseWhen> = emptyList(),
        val otherwise: ExpressionModel? = null,
    ) : ExpressionModel()

    /** 字面量/变量/参数等值叶子的 Java 源码文本（converter 按 sqlValue 求值）。 */
    data class Value(
        val expressionText: String,
    ) : ExpressionModel()

    /** 无法结构化的降级原文（含集合 size 等注释降级）。 */
    data class Raw(
        val expressionText: String,
    ) : ExpressionModel()
}

/**
 * CASE WHEN 分支（T-J17）。
 *
 * searched case（selectCase()）时 [predicate] 非空；simple case（selectCase(expr)）时
 * [condition] 非空，两者互斥。 [result] 为 THEN 结果。
 */
data class CaseWhen(
    val condition: ExpressionModel? = null,
    val predicate: PredicateModel? = null,
    val result: ExpressionModel? = null,
)

/**
 * 子查询中间表示（T-J17/J13 有界实现：单层子查询）。
 *
 * 支持 `Subquery<T> sq = query.subquery(...)` + `sq.from/select/where` 的有界抽取；
 * correlate 关联与多层嵌套无法静态渲染，置 [degradedReason] 后整体降级 Raw 注释。
 *
 * @param entityClassName 子查询 from 的实体类名
 * @param selectFieldName 子查询 select 的实体字段名（root.get("x")）；缺省按 * 渲染
 * @param predicates      子查询 WHERE 谓词
 * @param degradedReason  非 null 表示不可静态渲染的降级说明（correlate / 多层嵌套）
 */
data class SubqueryModel(
    val entityClassName: String? = null,
    val selectFieldName: String? = null,
    val predicates: List<PredicateModel> = emptyList(),
    val degradedReason: String? = null,
)

/**
 * GROUP BY 中间表示。
 *
 * @param fieldName 实体字段名（驼峰，如 "status"）
 */
data class GroupByModel(
    val fieldName: String,
)

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
 * @param joinType    联表类型（无 JoinType 实参的 join(String) 按规范默认 INNER，T-J7）
 * @param alias       关联表别名（未指定时为 null）
 * @param fetch       是否 fetch 调用（T-J7：FetchParent.fetch 语义为 EAGER 抓取合并入主查询，
 *                    渲染上与 join 同形）
 * @param onPredicates Join.on(...) 显式 ON 谓词（T-J7）；非空时优先于外键推断
 * @param variableName join/fetch 返回值赋值的变量名（deptJoin 等）；显式 ON 的列别名归属判定用
 */
data class JoinModel(
    val fieldName: String,
    val entityClassName: String? = null,
    val joinType: JoinType = JoinType.LEFT,
    val alias: String? = null,
    val fetch: Boolean = false,
    val onPredicates: List<PredicateModel> = emptyList(),
    val variableName: String? = null,
)
