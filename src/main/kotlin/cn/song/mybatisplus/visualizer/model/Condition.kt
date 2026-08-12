package cn.song.mybatisplus.visualizer.model

/**
 * WHERE / HAVING 条件树（M1 条件解析产物）。
 *
 * 设计为密封类层级：每种条件操作一种节点类型，顶层以 [Connector.AND] 连接多个条件。
 * 嵌套的 `and(...)` / `or(...)` 用 [Group] 表达，渲染时加括号。
 */
sealed class Condition {

    /**
     * 二元比较条件：`column op value`。
     *
     * 覆盖 eq(=)、ne(!=)、gt(>)、ge(>=)、lt(<)、le(<=) 等。
     */
    data class Simple(
        val column: ColumnRef,
        val op: String,
        val value: SqlValue,
    ) : Condition()

    /**
     * 一元条件：`column IS NULL` / `column IS NOT NULL`。
     */
    data class Unary(
        val column: ColumnRef,
        val op: String, // "IS NULL" 或 "IS NOT NULL"
    ) : Condition()

    /**
     * IN 列表条件：`column IN (v1, v2, ...)` / `column NOT IN (...)`。
     */
    data class In(
        val column: ColumnRef,
        val negated: Boolean,
        val values: List<SqlValue>,
    ) : Condition()

    /**
     * BETWEEN 范围条件：`column BETWEEN lo AND hi` / `column NOT BETWEEN ...`。
     */
    data class Between(
        val column: ColumnRef,
        val negated: Boolean,
        val lo: SqlValue,
        val hi: SqlValue,
    ) : Condition()

    /**
     * LIKE 模糊条件，位置语义见 [LikePosition]。
     */
    data class Like(
        val column: ColumnRef,
        val negated: Boolean,
        val pattern: SqlValue,
        val position: LikePosition,
    ) : Condition()

    /**
     * 逻辑分组（and / or 嵌套，或 and(consumer) / or(consumer) 的 lambda 体）。
     *
     * @param connector 组内子条件的连接符（AND 或 OR）
     * @param children  子条件（按调用顺序）
     */
    data class Group(
        val connector: Connector,
        val children: List<Condition>,
    ) : Condition()

    /**
     * 列对列比较（MPJ 联表 ON 条件，F-11）：`left = right`。
     *
     * 如 `cssm.scheme_id = t.share_scheme_id`。
     */
    data class ColumnComparison(
        val left: ColumnRef,
        val right: ColumnRef,
    ) : Condition()

    /**
     * 原始 SQL 片段（apply / last 等透传），不解析直接渲染。
     */
    data class Raw(
        val sqlText: String,
    ) : Condition()
}

/**
 * 条件连接符。
 */
enum class Connector { AND, OR }

/**
 * LIKE 模式中的 `%` 位置（对应 MP 的 like / likeLeft / likeRight）。
 */
enum class LikePosition {
    /** 两侧都加 %：`%value%` */
    ANYWHERE,

    /** 左侧加 %：`%value` */
    LEFT,

    /** 右侧加 %：`value%` */
    RIGHT,

    /** 不加 %（notLike 且原样） */
    NONE,
}
