package cn.song.mybatisplus.visualizer.model

/**
 * Wrapper 调用链模型（PSI 解析产物 → converter 输入）。
 *
 * 从源码中提取出"Wrapper 类型 + 实体类型 + 有序的方法调用列表"，
 * converter 层据此构建 [SqlStatement]。
 *
 * @param wrapperType     Wrapper 类型
 * @param entityClass     泛型实体类名（如 DeviceInfoEntity），可能为 null（无法解析时）
 * @param calls           按源码顺序的方法调用（wrapper.eq(...) 等）
 * @param constructorAlias 表别名构造（`new MPJLambdaWrapper<>(Class, "apply_info")` 第二参，清单 C4/P11）；null 表示默认
 * @param deleteConsumed  wrapper 作为实参传给删除类消费方法（`service.remove(wrapper)` / `mapper.delete(wrapper)`，
 *                        MP 惯用删除写法：删除条件用 LambdaQueryWrapper 构造）；true 时即使类型为查询类也应转 DELETE
 * @param countConsumed   wrapper 作为实参传给计数消费方法（`service.count(wrapper)` / `selectJoinCount(wrapper)`）；
 *                        true 时查询类 wrapper 转 `SELECT COUNT(*) FROM ...`
 * @param existsConsumed  链式终结点消费（`service.lambdaQuery()...exists()`，Q14-③，T-M15-01）；
 *                        true 时查询类链转 `SELECT EXISTS (子查询)`
 * @param updateEntityConsumed 实体更新消费（`service.update(entity, wrapper)` 双参，U07-②，T-M15-04）；
 *                        true 时用实体字段展开 SET 列（值占位）
 */
data class WrapperChain(
    val wrapperType: WrapperType,
    val entityClass: String?,
    val calls: List<WrapperCall>,
    val constructorAlias: String? = null,
    val deleteConsumed: Boolean = false,
    val countConsumed: Boolean = false,
    val existsConsumed: Boolean = false,
    val updateEntityConsumed: Boolean = false,
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

    /** MPJ selectAs 目标 VO 字段引用（`selectAs(Entity::getX, VO::getY)` 第二参）：别名 = VO 属性名（保留驼峰）。 */
    data class AliasArg(val propertyName: String) : WrapperArg()

    /** MPJ selectFunc 聚合函数实参（`selectFunc("COUNT(%s)", func -> func.accept(列).values(值), "alias")`）。 */
    data class FuncArg(
        val columns: List<ColumnRef>,
        val values: List<SqlValue>,
    ) : WrapperArg()

    /** MPJ join 的关联实体引用（`leftJoin(XxxEntity.class, ...)` 第一参）：携带实体解析的表信息（F-13 扩展）。 */
    data class EntityRefArg(val tableInfo: TableInfo) : WrapperArg()

    /** MPJ on(col1, col2) 两列比较（left 主表列，right 关联表列，别名由 converter 补充）。 */
    data class OnConditionArg(val left: ColumnRef, val right: ColumnRef) : WrapperArg()

    /**
     * allEq 的 Map 实参（T-M21-04，MP 官方 API）：
     * `allEq(Map.of(k1, v1, ...))` / `Map.ofEntries(...)` 调用静态展开的键值对
     * （String 键 → [ColumnRef]，columnName = 键）；非 Map.of 的 Map 变量 → 空 [entries]（converter 渲染可读占位）。
     *
     * @param entries     键（列名）→ 值（字面量/占位符）对
     * @param null2IsNull allEq(Map, null2IsNull) 语义：true 且值静态可判 null 时渲染 `col IS NULL`
     */
    data class MapArg(
        val entries: List<Pair<ColumnRef, SqlValue>>,
        val null2IsNull: Boolean,
    ) : WrapperArg()

    /**
     * selectFilter / select(Class, Predicate) 的列过滤谓词（T-M21-01，静态白/黑名单近似）：
     * lambda 体内方法调用实参的字符串字面量（`!t.getProperty().equals("xxx")` 的 "xxx"、
     * `t.getColumn().equals("col_name")` 等）→ 候选字段名；lambda 体含 `!` 否定 → 黑名单 [exclude]，
     * 否则白名单 [include]。两者均空 = 谓词无法静态分析（converter 回退全列）。
     */
    data class PredicateArg(
        val include: List<String>,
        val exclude: List<String>,
    ) : WrapperArg()
}

/**
 * 支持的 Wrapper 类型（F-01/09/10/11/12/17）。
 *
 * M1 仅实现 [LAMBDA_QUERY]；[NESTED] 用于 and/or 嵌套 lambda 的内部链标记。
 * T-M21-08：MPJ 官方 [UPDATE_JOIN]/[DELETE_JOIN]（UpdateJoinWrapper / DeleteJoinWrapper，
 * 1.4.5+ 联表更新/删除，原 P3-01 提前）——`UPDATE t LEFT JOIN ... ON ... SET ... WHERE ...` /
 * `DELETE t,t1 FROM ... LEFT JOIN ... WHERE ...` 形态，由 UpdateJoin/DeleteJoinConverter 转换。
 */
enum class WrapperType {
    LAMBDA_QUERY,
    LAMBDA_UPDATE,
    LAMBDA_DELETE,
    MPJ_LAMBDA,
    MPJ_QUERY,
    MPJ_LAMBDA_QUERY,
    QUERY,
    UPDATE,
    /** T-M21-08：UpdateJoinWrapper（MPJ 官方联表更新，1.4.5+）。 */
    UPDATE_JOIN,
    /** T-M21-08：DeleteJoinWrapper（MPJ 官方联表删除，1.4.5+）。 */
    DELETE_JOIN,
    NESTED,
}

