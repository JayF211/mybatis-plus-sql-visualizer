package cn.song.mybatisplus.visualizer.xml

/**
 * 渲染上下文（T-M24-01）：复刻 MyBatis 3.5.19 `org.apache.ibatis.scripting.xmltags.DynamicContext`。
 *
 * - bindings：变量上下文（初始 `_parameter` / `_databaseId`；bind / foreach 变量写入）
 * - appendSql：段间以单个空格分隔（对齐 3.5.13+ 的 StringJoiner(" ") 行为），最终 getSql() 去首尾空白
 * - conditionOverrides：条件开关覆盖（预览面板直接决定 if/when 真假，id = test 表达式原文）
 * - protectedBindNames：bind 覆盖名（面板输入的覆盖值优先于表达式求值）
 * - errors：渲染期错误（判重表，嵌套子上下文须转发 delegate，否则内层错误静默）
 *
 * 预览引擎专用扩展（conditionOverrides / protectedBindNames / errors）不影响运行时复刻语义。
 */
open class DynamicContext(
    initialBindings: Map<String, Any?>,
    private val conditionOverrides: Map<String, Boolean> = emptyMap(),
    private val protectedBindNames: Set<String> = emptySet(),
    /** 静态字段值（T-M25.2）：`@class@field` → 值；预览引擎由 PSI 层解析用户项目常量类注入。 */
    open val staticFieldValues: Map<String, Any?> = emptyMap(),
) {

    /**
     * 变量上下文（`_parameter` / `_databaseId` / bind 变量 / foreach item/index / 模拟值）。
     *
     * 注意：用属性初始化器而非 init 块填充——子类（Trim/Foreach 的过滤上下文）覆写本属性
     * 转发 delegate，父类 init 块中的调用会虚分派到未初始化的子类字段（NPE）。
     */
    open val bindings: MutableMap<String, Any?> = initialBindings.toMutableMap()

    /**
     * 渲染期错误提示（如 foreach collection 为 null 且 nullable=false、表达式解析失败）。
     *
     * T-FS-07：①判重表——`<foreach>` 逐迭代会把同一表达式同一原因重复登记 N 次，
     * 用户看到的 `⚠` 警示行（`errors.joinToString("；")`）不该被同一句话刷屏，故同文案只留首现一行
     * （判重键 = 完整错误文案，已含表达式原文与原因，天然等价于「同表达式同原因」）；
     * ②子上下文（Foreach 的 Prefixed/Filtered、Trim 的 filtered）覆写本属性转发 delegate——
     * 此前各自持有独立列表，内层 `<if>` 求值失败被丢弃（静默，与 `${}`/bind/顶层 if 形态不一致）。
     */
    open val errors: MutableList<String> = DedupErrorList()

    /**
     * `${}` 直替计数（T-DollarInjectMark）：TextSqlNode 每求值一个 `${}` token 经
     * [countDollarSubstitution] 自增——引擎忠实口径（MyBatis 对字符串字面量内的 `${}`
     * 同样直替，计数不排除）；转义 `\${` 不经求值不计。渲染结束随
     * RenderedXmlSql.dollarSubstitutions 出栈，预览横幅消费。
     */
    var dollarSubstitutions: Int = 0
        protected set

    /** 直替计数入口：子类过滤上下文（Foreach 的 Prefixed/Filtered）转发到 delegate，计数落根上下文。 */
    open fun countDollarSubstitution() {
        dollarSubstitutions++
    }

    private val sqlBuilder = StringBuilder()
    private var uniqueNumber = 0

    /** 追加 SQL 段（段间单个空格；空段不产生分隔）。 */
    open fun appendSql(sql: String) {
        if (sql.isEmpty()) return
        if (sqlBuilder.isNotEmpty()) sqlBuilder.append(' ')
        sqlBuilder.append(sql)
    }

    /** 当前累积 SQL（首尾空白已去除）。 */
    open fun getSql(): String = sqlBuilder.toString().trim()

    /** 绑定变量（bind / foreach item·index）。 */
    open fun bind(name: String, value: Any?) {
        bindings[name] = value
    }

    /** 每次调用自增的唯一编号（foreach 的 `__frch_<名>_<n>` 用）。 */
    open fun getUniqueNumber(): Int = uniqueNumber++

    /** 条件开关覆盖：test 表达式原文 → 强制真值；null 表示无覆盖（走表达式求值）。 */
    open fun conditionResult(test: String): Boolean? = conditionOverrides[test]

    /** bind 名是否受保护（面板覆盖值优先，VarDeclSqlNode 跳过求值）。 */
    open fun isProtected(name: String): Boolean = name in protectedBindNames

    companion object {
        const val PARAMETER_OBJECT_KEY = "_parameter"
        const val DATABASE_ID_KEY = "_databaseId"
    }
}

/**
 * 判重错误表（T-FS-07）：LinkedHashSet 语义的 [MutableList]——保首现顺序、同文案只留一行。
 *
 * 只用于 [DynamicContext.errors]（MyBatis 无此概念，属预览引擎专用扩展）；保留 MutableList 形态
 * 使既有写入点（`context.errors += msg`）与既有测试无需改动。`add(index, element)` 忽略索引——
 * 判重集合无插入位置语义，仓内既有写入只有 `+=` / `add(msg)` 两种形态。
 */
private class DedupErrorList : AbstractMutableList<String>() {

    private val seen = LinkedHashSet<String>()

    override val size: Int get() = seen.size

    override fun get(index: Int): String = seen.elementAt(index)

    override fun add(index: Int, element: String) {
        seen.add(element)
    }

    override fun removeAt(index: Int): String = get(index).also { seen.remove(it) }

    /** 判重集合无「按位替换」语义：旧值移除、新值追加到末尾（仓内无调用点，仅为满足 MutableList 契约）。 */
    override fun set(index: Int, element: String): String {
        val previous = get(index)
        if (previous != element) {
            seen.remove(previous)
            seen.add(element)
        }
        return previous
    }

    override fun add(element: String): Boolean = seen.add(element)

    override fun clear() = seen.clear()

    override fun contains(element: String): Boolean = seen.contains(element)

    override fun indexOf(element: String): Int = seen.indexOf(element)

    override fun iterator(): MutableIterator<String> = seen.iterator()
}
