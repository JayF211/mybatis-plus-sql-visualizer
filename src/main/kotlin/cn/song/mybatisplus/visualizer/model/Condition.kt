package cn.song.mybatisplus.visualizer.model

/**
 * WHERE / HAVING 条件树（M1 条件解析产物）。
 *
 * 设计为密封类层级：每种条件操作一种节点类型，顶层以 [Connector.AND] 连接多个条件。
 * 嵌套的 `and(...)` / `or(...)` 用 [Group] 表达，渲染时加括号。
 */
sealed class Condition {

    /** 动态条件元数据（M12）：布尔条件重载的解析产物，null 表示该条件不可勾选（非动态）。 */
    abstract val toggle: ConditionToggle?

    /** 复制并挂载/替换动态条件元数据（M12）。非动态条件传 null 保持原样。 */
    fun withToggle(toggle: ConditionToggle?): Condition = when (this) {
        is Simple -> copy(toggle = toggle)
        is Unary -> copy(toggle = toggle)
        is In -> copy(toggle = toggle)
        is Between -> copy(toggle = toggle)
        is Like -> copy(toggle = toggle)
        is Group -> copy(toggle = toggle)
        is ColumnComparison -> copy(toggle = toggle)
        is Raw -> copy(toggle = toggle)
    }

    /**
     * 二元比较条件：`column op value`。
     *
     * 覆盖 eq(=)、ne(!=)、gt(>)、ge(>=)、lt(<)、le(<=) 等。
     */
    data class Simple(
        val column: ColumnRef,
        val op: String,
        val value: SqlValue,
        override val toggle: ConditionToggle? = null,
    ) : Condition()

    /**
     * 一元条件：`column IS NULL` / `column IS NOT NULL`。
     */
    data class Unary(
        val column: ColumnRef,
        val op: String, // "IS NULL" 或 "IS NOT NULL"
        override val toggle: ConditionToggle? = null,
    ) : Condition()

    /**
     * IN 列表条件：`column IN (v1, v2, ...)` / `column NOT IN (...)`。
     */
    data class In(
        val column: ColumnRef,
        val negated: Boolean,
        val values: List<SqlValue>,
        override val toggle: ConditionToggle? = null,
    ) : Condition()

    /**
     * BETWEEN 范围条件：`column BETWEEN lo AND hi` / `column NOT BETWEEN ...`。
     */
    data class Between(
        val column: ColumnRef,
        val negated: Boolean,
        val lo: SqlValue,
        val hi: SqlValue,
        override val toggle: ConditionToggle? = null,
    ) : Condition()

    /**
     * LIKE 模糊条件，位置语义见 [LikePosition]。
     */
    data class Like(
        val column: ColumnRef,
        val negated: Boolean,
        val pattern: SqlValue,
        val position: LikePosition,
        override val toggle: ConditionToggle? = null,
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
        override val toggle: ConditionToggle? = null,
    ) : Condition()

    /**
     * 列对列比较（MPJ 联表 ON 条件，F-11）：`left = right`。
     *
     * 如 `cssm.scheme_id = t.share_scheme_id`。
     */
    data class ColumnComparison(
        val left: ColumnRef,
        val right: ColumnRef,
        override val toggle: ConditionToggle? = null,
    ) : Condition()

    /**
     * 原始 SQL 片段（apply / last 等透传），不解析直接渲染。
     */
    data class Raw(
        val sqlText: String,
        override val toggle: ConditionToggle? = null,
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
