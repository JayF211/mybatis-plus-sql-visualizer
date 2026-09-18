package cn.song.mybatisplus.visualizer.xml

/**
 * XML 预览条件行（T-M24-04）：一个可勾选开关的动态条件（if / choose-when）。
 *
 * @param id             条件标识 = test 表达式原文（启用集合元素；同表达式多处出现共享开关）
 * @param label          人读条件文本（复选框标签 = 表达式）
 * @param defaultEnabled 默认勾选态（启发式：含 `== null` → false，其余 true——保守展开）
 * @param support        表达式支持面分档（T-ExprAstPrecheck T-EP-03 预检产物，xml 层字符串，零 PSI）
 * @param supportReason  分档理由码（标注 tooltip 用；SUPPORTED 时为 null）
 * @param supportDetail  理由细节（方法名 / `@类@字段` / 解析错误消息）
 */
data class XmlConditionView(
    val id: String,
    val label: String,
    val defaultEnabled: Boolean,
    val support: ExprSupport = ExprSupport.SUPPORTED,
    val supportReason: ExprReason? = null,
    val supportDetail: String? = null,
)

/** foreach 视图：collection 表达式（面板集合值/null 控件按表达式定位）+ item 名（T-M25.4 集合值回填键前缀）。 */
data class XmlForEachView(val collectionExpr: String, val item: String? = null)

/** bind 视图：变量名 + 表达式（面板覆盖值输入，空 = 自动求值）。 */
data class XmlBindView(val name: String, val expression: String)

/**
 * XML 预览渲染状态（面板勾选产物）：
 *
 * @param enabledIds    启用中的条件开关 id（test 表达式原文）
 * @param forEachCounts collection 表达式 → 展开次数（0 = 空集合 → 整段空输出）
 * @param forEachNulls  勾选为 null 的 collection 表达式集合（nullable=false 时渲染错误提示）
 * @param bindOverrides bind 名 → 覆盖值（优先于表达式求值）
 */
data class XmlPreviewState(
    val enabledIds: Set<String> = emptySet(),
    val forEachCounts: Map<String, Int> = emptyMap(),
    val forEachNulls: Set<String> = emptySet(),
    val bindOverrides: Map<String, String> = emptyMap(),
)

/**
 * XML 预览模型（T-M24-04）：驱动预览面板的动态条件勾选与参数输入。
 *
 * [renderSql] 是**唯一渲染源**——初始渲染与面板勾选重建共用，保证一致；
 * 纯逻辑（依赖 [OgnlEvaluator] / [XmlSqlRenderer]），不依赖 PSI / IDE，可独立单测。
 *
 * 模拟参数策略（M24 版，文档化）：
 *  - 顶层变量（bind / `${}` 表达式引用的根标识符，排除 `_parameter`/`_databaseId`）
 *    统一占位为字符串 `"1"`（非 null 非空，使 `x != null`、`x != ''`、`'%' + x + '%'` 等常见形态可求值）；
 *  - if/when 真假由面板开关直接决定（启发式默认），表达式求值仅在无开关覆盖时发生；
 *  - foreach 展开次数由面板输入（默认 [DEFAULT_FOREACH_SIZE]），collection 表达式全文作为 mock 键
 *    （[ForeachSqlNode] 精确命中优先）；
 *  - bind 覆盖值优先（[DynamicContext.isProtected]）。
 */
class XmlPreviewModel private constructor(
    val statementId: String,
    val kind: XmlStatementKind,
    val conditionViews: List<XmlConditionView>,
    val forEachViews: List<XmlForEachView>,
    val bindViews: List<XmlBindView>,
    val parameterType: String?,
    /** 解析期错误（include 缺失 / 循环等），渲染期错误在 RenderedXmlSql.errors。 */
    val errors: List<String>,
    /** 顶层变量（bind / `${}` 表达式引用的根标识符，排除保留键）——mock 参数对象属性。 */
    val variables: Set<String>,
    /** `_parameter` 的直接属性（`_parameter.title` 形态）——mock 参数对象属性。 */
    val paramProps: Set<String>,
    /** 静态字段值（T-M25.2）：`@class@field` → 值（PSI 层解析用户项目常量类注入）。 */
    val staticFieldValues: Map<String, Any?>,
    /** MP 占位 mock（T-M26-B）：`ew` → MockObject(customSqlSegment/sqlSelect/sqlSet 片段)，覆盖变量占位。 */
    val extraBindings: Map<String, Any?>,
    /** 参数回填引号映射（T-M29.5）：参数路径（`@Param` 值.字段 / 参数名）→ 是否引号——
     * 由 XmlParamTypeResolver 按 mapper 方法签名参数 Java 类型解析（DTO 字段类型 → ParamQuote）；
     * Map/无类型信息参数不含键（面板兜底引号）。 */
    val paramQuotes: Map<String, Boolean> = emptyMap(),
    private val rootNode: SqlNode,
) {

    /**
     * T-M29.5：参数路径 → 引号策略（精确匹配优先，前缀匹配兜底——
     * DTO 参数键为 `p.settlementStartDate` 精确路径；Map 参数键为 `p`，任意 `p.*` 属性命中）。
     * 未命中返回 null（面板兜底引号）。
     */
    fun quoteFor(source: String): Boolean? {
        paramQuotes[source]?.let { return it }
        return paramQuotes.entries.firstOrNull { (key, _) -> source.startsWith("$key.") }?.value
    }

    fun defaultState(): XmlPreviewState = XmlPreviewState(
        enabledIds = conditionViews.filter { it.defaultEnabled }.map { it.id }.toSet(),
        forEachCounts = forEachViews.associate { it.collectionExpr to DEFAULT_FOREACH_SIZE },
    )

    fun renderSql(): RenderedXmlSql = renderSql(defaultState())

    fun renderSql(state: XmlPreviewState): RenderedXmlSql = XmlSqlRenderer().render(
        node = rootNode,
        bindings = mockBindings(state),
        // 全部条件都有明确真值（开关缺席 = false）——开关是分支唯一真值源，表达式仅无开关时兜底求值
        conditionOverrides = conditionViews.associate { it.id to (it.id in state.enabledIds) },
        protectedNames = state.bindOverrides.keys.toSet(),
        staticFieldValues = staticFieldValues,
        bindNames = bindViews.map { it.name }.toSet(),
    )

    private fun mockBindings(state: XmlPreviewState): Map<String, Any?> {
        val bindings = mutableMapOf<String, Any?>()
        // 变量/参数名占位双写：_parameter 对象属性 + bindings 顶层（对齐 MyBatis ContextMap →
        // _parameter fallback：`${param1}` 与 `_parameter.param1` 均可取到占位 "1"）
        val mockProps = (variables + paramProps).associateWith { "1" }
        bindings[DynamicContext.PARAMETER_OBJECT_KEY] = MockObject(mockProps)
        mockProps.keys.forEach { bindings[it] = "1" }
        bindings[DynamicContext.DATABASE_ID_KEY] = null
        forEachViews.forEach { view ->
            bindings[view.collectionExpr] = if (view.collectionExpr in state.forEachNulls) {
                null
            } else {
                MockIterable(state.forEachCounts[view.collectionExpr] ?: DEFAULT_FOREACH_SIZE)
            }
        }
        state.bindOverrides.forEach { (name, value) -> bindings[name] = value }
        // T-M26-B：MP 占位 mock 覆盖（ew → 片段对象，置于最后优先级最高）
        bindings.putAll(extraBindings)
        return bindings
    }

    companion object {
        /** foreach 默认展开次数（IN 场景两元素可直观展示 separator）。 */
        const val DEFAULT_FOREACH_SIZE = 2

        /** 启发式默认勾选态：含 `== null` → false（变量默认非 null），其余 true（保守展开）。 */
        fun defaultTruth(test: String): Boolean = !test.contains("== null")

        /** 由解析出的语句构建预览模型（变量收集 + 条件/foreach/bind 视图）。
         *  T-M25-02：注解场景额外注入方法参数名（@Param 值/实际名/param1..N，MyBatis 多参数 Map 键）。
         *  T-M25.2：staticFieldValues 为 `@class@field` → 值（PSI 层解析用户项目常量类）。
         *  T-M26-B：extraBindings 为 MP 占位 mock（`ew` → 片段对象）。 */
        fun from(
            statement: XmlStatement,
            errors: List<String>,
            extraParamProps: Set<String> = emptySet(),
            staticFieldValues: Map<String, Any?> = emptyMap(),
            extraBindings: Map<String, Any?> = emptyMap(),
            paramQuotes: Map<String, Boolean> = emptyMap(),
        ): XmlPreviewModel {
            val conditionViews = mutableListOf<XmlConditionView>()
            val forEachViews = mutableListOf<XmlForEachView>()
            val bindViews = mutableListOf<XmlBindView>()
            val variables = mutableSetOf<String>()
            val paramProps = mutableSetOf<String>()
            collect(statement.node, conditionViews, forEachViews, bindViews, variables, paramProps)
            variables.remove(DynamicContext.PARAMETER_OBJECT_KEY)
            variables.remove(DynamicContext.DATABASE_ID_KEY)
            paramProps += extraParamProps
            return XmlPreviewModel(
                statementId = statement.id,
                kind = statement.kind,
                conditionViews = conditionViews,
                forEachViews = forEachViews,
                bindViews = bindViews,
                parameterType = statement.parameterType,
                errors = errors,
                variables = variables,
                paramProps = paramProps,
                staticFieldValues = staticFieldValues,
                extraBindings = extraBindings,
                paramQuotes = paramQuotes,
                rootNode = statement.node,
            )
        }

        /** 表达式根路径分派：`_parameter.x` → 模拟参数对象属性；其余根（排除保留键）→ 变量。 */
        private fun collectExpressionPaths(
            expression: String,
            variables: MutableSet<String>,
            paramProps: MutableSet<String>,
        ) {
            OgnlEvaluator.rootPaths(expression).forEach { path ->
                if (path.startsWith("${DynamicContext.PARAMETER_OBJECT_KEY}.")) {
                    paramProps += path.removePrefix("${DynamicContext.PARAMETER_OBJECT_KEY}.")
                } else if (path != DynamicContext.PARAMETER_OBJECT_KEY && path != DynamicContext.DATABASE_ID_KEY) {
                    variables += path
                }
            }
        }

        private fun collect(
            node: SqlNode,
            conditionViews: MutableList<XmlConditionView>,
            forEachViews: MutableList<XmlForEachView>,
            bindViews: MutableList<XmlBindView>,
            variables: MutableSet<String>,
            paramProps: MutableSet<String>,
        ) {
            when (node) {
                is MixedSqlNode ->
                    node.children.forEach { collect(it, conditionViews, forEachViews, bindViews, variables, paramProps) }
                is StaticTextSqlNode -> Unit
                is TextSqlNode -> {
                    val dollarTokens = mutableListOf<String>()
                    TokenParser.parse(node.text, "\${", "}") { token ->
                        dollarTokens += token
                        token
                    }
                    dollarTokens.forEach { collectExpressionPaths(it, variables, paramProps) }
                }
                is IfSqlNode -> {
                    // T-ExprAstPrecheck T-EP-03：构建条件行时对 test 表达式跑支持面预检（纯 AST 遍历，
                    // 零 PSI）——标注只是提示，展开行为仍由面板开关与 T-EP-02 的错误记录管
                    val classification = OgnlEvaluator.classifyDetail(node.test)
                    conditionViews += XmlConditionView(
                        node.test,
                        node.test,
                        defaultTruth(node.test),
                        classification.support,
                        classification.reason,
                        classification.detail,
                    )
                    collect(node.contents, conditionViews, forEachViews, bindViews, variables, paramProps)
                }
                is ChooseSqlNode -> {
                    // when 本身是 IfSqlNode，递归 collect 统一处理（此处不重复添加）
                    node.whenNodes.forEach { collect(it, conditionViews, forEachViews, bindViews, variables, paramProps) }
                    node.defaultNode?.let { collect(it, conditionViews, forEachViews, bindViews, variables, paramProps) }
                }
                is TrimSqlNode -> collect(node.contents, conditionViews, forEachViews, bindViews, variables, paramProps)
                is ForeachSqlNode -> {
                    forEachViews += XmlForEachView(node.collectionExpression, node.item)
                    collect(node.contents, conditionViews, forEachViews, bindViews, variables, paramProps)
                }
                is VarDeclSqlNode -> {
                    bindViews += XmlBindView(node.name, node.expression)
                    collectExpressionPaths(node.expression, variables, paramProps)
                }
            }
        }
    }
}
