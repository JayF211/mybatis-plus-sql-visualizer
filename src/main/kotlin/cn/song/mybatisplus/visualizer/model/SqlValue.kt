package cn.song.mybatisplus.visualizer.model

/**
 * SQL 参数值（F-02 参数替换的基础模型）。
 *
 * Wrapper 调用实参在源码中的可读性分为两类：
 *  - 字面量/可静态求值的常量：可直接渲染进 SQL（如 `1001L` → `1001`、`"abc"` → `'abc'`）
 *  - 运行时变量/复杂表达式：源码中无法确定值，渲染为 `?` 占位符（M2 再做变量追踪替换）
 */
sealed class SqlValue {

    /**
     * 源码中可直接确定的值。
     *
     * @param raw      渲染进 SQL 的文本（字符串已带单引号 `'abc'`，数值原样 `1001`）
     * @param unquoted 原始值文本（字符串无引号 `abc`，数值与 raw 相同）。
     *                 用于 SQL 片段场景（last("limit 10")、setSql、selectAs 别名、字符串列名），
     *                 这些场景不需要引号包裹。
     */
    data class Literal(
        val raw: String,
        val unquoted: String = raw,
    ) : SqlValue()

    /**
     * 运行时值，渲染为占位符。
     *
     * @param source 参数来源描述（变量名或表达式文本），用于 M2 参数替换与对照表
     * @param fragment 该表达式作为**原始 SQL 片段**使用时的形态（文本 + 占位符来源，
     *                 `"amount = amount * " + rate` → `amount = amount * ?`，来源 `[rate]`）。
     *                 仅字符串拼接时非 null（见
     *                 [cn.song.mybatisplus.visualizer.psi.ExpressionEvaluator]）。
     *                 片段位置（setSql/apply/last/having 等不做参数绑定的入口）经
     *                 [cn.song.mybatisplus.visualizer.converter.asSqlFragment] 取用；
     *                 值位置（eq/set 的值实参）恒渲染 `?`，不读本字段——那里整个字符串是一个绑定参数。
     */
    data class Placeholder(
        val source: String,
        val fragment: SqlFragment? = null,
    ) : SqlValue()

    /** 该值渲染后是否为 `?` 占位符。 */
    val isPlaceholder: Boolean get() = this is Placeholder
}
