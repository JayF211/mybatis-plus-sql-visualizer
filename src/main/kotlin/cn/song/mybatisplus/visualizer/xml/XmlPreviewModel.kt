package cn.song.mybatisplus.visualizer.xml

/**
 * XML 预览条件行（T-M24-04）：一个可勾选开关的动态条件（if / choose-when）。
 *
 * @param id             条件标识 = test 表达式原文（启用集合元素；同表达式多处出现共享开关）
 * @param label          人读条件文本（复选框标签 = 表达式）
 * @param defaultEnabled 默认勾选态（启发式：含 `== null` → false，其余 true——保守展开）
 */
data class XmlConditionView(
    val id: String,
    val label: String,
    val defaultEnabled: Boolean,
)

/** foreach 视图：collection 表达式（面板次数/空集/null 控件按表达式定位）。 */
data class XmlForEachView(val collectionExpr: String)

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
    private val rootNode: SqlNode,
) {

    fun defaultState(): XmlPreviewState = XmlPreviewState(
        enabledIds = conditionViews.filter { it.defaultEnabled }.map { it.id }.toSet(),
        forEachCounts = forEachViews.associate { it.collectionExpr to DEFAULT_FOREACH_SIZE },
    )

    fun renderSql(): RenderedXmlSql = renderSql(defaultState())

    fun renderSql(state: XmlPreviewState): RenderedXmlSql = XmlSqlRenderer().render(
        node = rootNode,
        bindings = mockBindings(state),
        conditionOverrides = state.enabledIds.associateWith { true },
        protectedNames = state.bindOverrides.keys.toSet(),
    )

    private fun mockBindings(state: XmlPreviewState): Map<String, Any?> {
        val bindings = mutableMapOf<String, Any?>()
        bindings[DynamicContext.PARAMETER_OBJECT_KEY] = MockObject(variables.associateWith { "1" })
        bindings[DynamicContext.DATABASE_ID_KEY] = null
        forEachViews.forEach { view ->
            bindings[view.collectionExpr] = if (view.collectionExpr in state.forEachNulls) {
                null
            } else {
                MockIterable(state.forEachCounts[view.collectionExpr] ?: DEFAULT_FOREACH_SIZE)
            }
        }
        state.bindOverrides.forEach { (name, value) -> bindings[name] = value }
        return bindings
    }

    companion object {
        /** foreach 默认展开次数（IN 场景两元素可直观展示 separator）。 */
        const val DEFAULT_FOREACH_SIZE = 2

        /** 启发式默认勾选态：含 `== null` → false（变量默认非 null），其余 true（保守展开）。 */
        fun defaultTruth(test: String): Boolean = !test.contains("== null")

        /** 由解析出的语句构建预览模型（变量收集 + 条件/foreach/bind 视图）。 */
        fun from(statement: XmlStatement, errors: List<String>): XmlPreviewModel {
            val conditionViews = mutableListOf<XmlConditionView>()
            val forEachViews = mutableListOf<XmlForEachView>()
            val bindViews = mutableListOf<XmlBindView>()
            val variables = mutableSetOf<String>()
            collect(statement.node, conditionViews, forEachViews, bindViews, variables)
            variables.remove(DynamicContext.PARAMETER_OBJECT_KEY)
            variables.remove(DynamicContext.DATABASE_ID_KEY)
            return XmlPreviewModel(
                statementId = statement.id,
                kind = statement.kind,
                conditionViews = conditionViews,
                forEachViews = forEachViews,
                bindViews = bindViews,
                parameterType = statement.parameterType,
                errors = errors,
                variables = variables,
                rootNode = statement.node,
            )
        }

        private fun collect(
            node: SqlNode,
            conditionViews: MutableList<XmlConditionView>,
            forEachViews: MutableList<XmlForEachView>,
            bindViews: MutableList<XmlBindView>,
            variables: MutableSet<String>,
        ) {
            when (node) {
                is MixedSqlNode -> node.children.forEach { collect(it, conditionViews, forEachViews, bindViews, variables) }
                is StaticTextSqlNode -> Unit
                is TextSqlNode -> {
                    val dollarTokens = mutableListOf<String>()
                    TokenParser.parse(node.text, "\${", "}") { token ->
                        dollarTokens += token
                        token
                    }
                    dollarTokens.forEach { variables += OgnlEvaluator.rootVariables(it) }
                }
                is IfSqlNode -> {
                    conditionViews += XmlConditionView(node.test, node.test, defaultTruth(node.test))
                    collect(node.contents, conditionViews, forEachViews, bindViews, variables)
                }
                is ChooseSqlNode -> {
                    // when 本身是 IfSqlNode，递归 collect 统一处理（此处不重复添加）
                    node.whenNodes.forEach { collect(it, conditionViews, forEachViews, bindViews, variables) }
                    node.defaultNode?.let { collect(it, conditionViews, forEachViews, bindViews, variables) }
                }
                is TrimSqlNode -> collect(node.contents, conditionViews, forEachViews, bindViews, variables)
                is ForeachSqlNode -> {
                    forEachViews += XmlForEachView(node.collectionExpression)
                    collect(node.contents, conditionViews, forEachViews, bindViews, variables)
                }
                is VarDeclSqlNode -> {
                    bindViews += XmlBindView(node.name, node.expression)
                    variables += OgnlEvaluator.rootVariables(node.expression)
                }
            }
        }
    }
}
