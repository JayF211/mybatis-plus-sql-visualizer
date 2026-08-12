package cn.song.mybatisplus.visualizer.model

/**
 * Wrapper 调用链模型（PSI 解析产物 → converter 输入）。
 *
 * 从源码中提取出"Wrapper 类型 + 实体类型 + 有序的方法调用列表"，
 * converter 层据此构建 [SqlStatement]。
 *
 * @param wrapperType Wrapper 类型
 * @param entityClass 泛型实体类名（如 CarDeviceInfoEntity），可能为 null（无法解析时）
 * @param calls       按源码顺序的方法调用（wrapper.eq(...) 等）
 */
data class WrapperChain(
    val wrapperType: WrapperType,
    val entityClass: String?,
    val calls: List<WrapperCall>,
)

/**
 * Wrapper 上的一次方法调用。
 *
 * @param methodName 方法名（如 eq、select、orderByDesc）
 * @param args       实参（psi 层已解析为 [WrapperArg]）
 */
data class WrapperCall(
    val methodName: String,
    val args: List<WrapperArg>,
)

/**
 * Wrapper 方法实参（psi 层解析产物）。
 *
 * 区分四类：
 *  - [ColumnArg]：列引用实参（`Entity::getXxx` 方法引用或 MPJ 的字符串字段）
 *  - [ValueArg]：值实参（字面量 / 运行时变量 / 表达式）
 *  - [NestedArg]：嵌套调用链实参（`and(consumer)` / `and(wrapper)` 的 lambda 体或子 wrapper）
 *  - [OnConditionArg]：MPJ `on(col1, col2)` 两列比较实参（F-11 联表 ON 条件）
 */
sealed class WrapperArg {

    /** 列引用实参。 */
    data class ColumnArg(val column: ColumnRef) : WrapperArg()

    /** 值实参。 */
    data class ValueArg(val value: SqlValue) : WrapperArg()

    /** 嵌套调用链实参（and/or 的 lambda 体）。 */
    data class NestedArg(val chain: WrapperChain) : WrapperArg()

    /** MPJ on(col1, col2) 两列比较（left 主表列，right 关联表列，别名由 converter 补充）。 */
    data class OnConditionArg(val left: ColumnRef, val right: ColumnRef) : WrapperArg()
}

/**
 * 支持的 Wrapper 类型（F-01/09/10/11/12/17）。
 *
 * M1 仅实现 [LAMBDA_QUERY]；[NESTED] 用于 and/or 嵌套 lambda 的内部链标记。
 */
enum class WrapperType {
    LAMBDA_QUERY,
    LAMBDA_UPDATE,
    LAMBDA_DELETE,
    MPJ_LAMBDA,
    MPJ_QUERY,
    QUERY,
    UPDATE,
    NESTED,
}

