package cn.song.mybatisplus.visualizer.ui.completion

import cn.song.mybatisplus.visualizer.model.WrapperType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons

/**
 * Wrapper 方法名补全项工厂（F-5，阶段 A3）。
 *
 * 根据 [ChainStateDetector.ChainState] 生成上下文感知的方法名补全项。
 * 补全项带括号尾巴 + 插入处理器（光标定位到括号内），减少用户输入。
 *
 * 方法分组（对齐 MP 官方 API 语义）：
 *  - 条件方法：eq/ne/gt/ge/lt/le/like/notLike/in/notIn/isNull/isNotNull/between/notBetween
 *  - 逻辑连接：and/or
 *  - 聚合/分组：groupBy/having
 *  - 排序：orderBy/orderByAsc/orderByDesc
 *  - 字段选择：select/selectAs/selectIgnore
 *  - UPDATE 专用：set/setSql/setIncrBy/setDecrBy
 *  - 终结点：list/one/count/exists/page/getOne/getMap
 *  - MPJ 联表：leftJoin/rightJoin/innerJoin/fullJoin/selectAll
 */
object MethodLookupFactory {

    /** 方法名 → 展示描述（灰色 tailText） */
    private val METHOD_DESCRIPTIONS: Map<String, String> = mapOf(
        "eq" to "=",
        "ne" to "<>",
        "gt" to ">",
        "ge" to ">=",
        "lt" to "<",
        "le" to "<=",
        "like" to "LIKE",
        "notLike" to "NOT LIKE",
        "in" to "IN",
        "notIn" to "NOT IN",
        "isNull" to "IS NULL",
        "isNotNull" to "IS NOT NULL",
        "between" to "BETWEEN",
        "notBetween" to "NOT BETWEEN",
        "and" to "AND",
        "or" to "OR",
        "orderBy" to "ORDER BY",
        "orderByAsc" to "ASC",
        "orderByDesc" to "DESC",
        "groupBy" to "GROUP BY",
        "having" to "HAVING",
        "select" to "SELECT",
        "set" to "SET",
        "list" to "查询列表",
        "one" to "查询单条",
        "count" to "计数",
        "exists" to "存在判断",
        "page" to "分页",
        "leftJoin" to "左联",
        "rightJoin" to "右联",
        "innerJoin" to "内联",
        "fullJoin" to "全联",
        "selectAll" to "全字段",
    )

    // ------------------------------------------------------------------
    // 方法分组常量
    // ------------------------------------------------------------------

    /** 条件方法（WHERE 子句谓词）。 */
    private val CONDITION_METHODS = listOf(
        "eq", "ne", "gt", "ge", "lt", "le",
        "like", "notLike", "in", "notIn",
        "isNull", "isNotNull", "between", "notBetween",
    )

    /** 链首默认方法（查询类 wrapper 初始可用）。 */
    private val QUERY_HEAD_METHODS = listOf(
        "eq", "ne", "gt", "ge", "lt", "le",
        "like", "notLike", "in", "notIn",
        "isNull", "isNotNull", "between", "notBetween",
        "orderBy", "groupBy", "having", "select",
    )

    /** 条件方法之后可接的方法。 */
    private val AFTER_CONDITION_METHODS = listOf(
        "and", "or",
        "eq", "ne", "gt", "ge", "lt", "le",
        "like", "in", "orderBy", "groupBy",
        "limit", "last", "exists",
    )

    /** select 之后可接的方法。 */
    private val AFTER_SELECT_METHODS = listOf(
        "selectAs", "selectIgnore", "selectCount",
        "groupBy", "having", "orderBy", "limit",
    )

    /** orderBy 之后可接的方法。 */
    private val AFTER_ORDER_BY_METHODS = listOf(
        "orderByAsc", "orderByDesc",
        "limit", "page", "last", "exists", "list", "one",
    )

    /** UPDATE 的 set 之后可接的方法。 */
    private val AFTER_SET_METHODS = listOf(
        "set", "setSql", "setIncrBy", "setDecrBy",
        "eq", "ne", "where",
    )

    /** 链终结点方法。 */
    private val TERMINAL_METHODS = listOf(
        "list", "one", "count", "exists", "page", "getOne", "getMap",
    )

    /** MPJ 联表方法（MPJ wrapper 链首额外提示）。 */
    private val MPJ_JOIN_METHODS = listOf(
        "leftJoin", "rightJoin", "innerJoin", "fullJoin",
        "selectAll", "selectAs", "selectIgnore",
    )

    // ------------------------------------------------------------------
    // 补全项生成
    // ------------------------------------------------------------------

    /**
     * 根据链状态生成方法名补全项。
     *
     * @param state 链状态（[ChainStateDetector.detect] 返回值）
     * @return 补全项列表（已按上下文优先级排序）；空列表表示当前上下文无推荐方法
     */
    fun createLookups(state: ChainStateDetector.ChainState): List<LookupElement> {
        val methodNames = selectMethods(state)
        return methodNames.map { buildLookupElement(it) }
    }

    /**
     * 按链状态选择应提示的方法名集合（开发计划 §2.1 上下文感知规则）。
     */
    private fun selectMethods(state: ChainStateDetector.ChainState): List<String> {
        // 实参内不提示方法名（应提示字段名——由 FieldLookupFactory 负责）
        if (state.isInsideMethodArgs) return emptyList()

        // 终结点之后不提示（链已结束）
        if (state.lastMethod in TERMINAL_METHODS) return emptyList()

        return when {
            // MPJ wrapper 链首额外提示联表方法
            state.wrapperType in MPJ_WRAPPER_TYPES && state.calledMethods.isEmpty() ->
                MPJ_JOIN_METHODS + QUERY_HEAD_METHODS

            // UPDATE 链：上一个方法是 set
            state.lastMethod == "set" -> AFTER_SET_METHODS

            // select 之后
            state.lastMethod == "select" -> AFTER_SELECT_METHODS

            // orderBy 之后
            state.lastMethod == "orderBy" -> AFTER_ORDER_BY_METHODS

            // 条件方法之后
            state.lastMethod in CONDITION_METHODS -> AFTER_CONDITION_METHODS

            // 链首（无已调用方法）或 and/or 之后
            else -> QUERY_HEAD_METHODS
        }
    }

    /** MPJ  Wrapper 类型集合（需额外提示联表方法）。 */
    private val MPJ_WRAPPER_TYPES: Set<WrapperType> = setOf(
        WrapperType.MPJ_LAMBDA, WrapperType.MPJ_QUERY, WrapperType.MPJ_LAMBDA_QUERY,
        WrapperType.UPDATE_JOIN, WrapperType.DELETE_JOIN,
    )

    /**
     * 构建单个方法名补全项：
     *  - 展示名 = 方法名 + `()` 尾巴（灰色）
     *  - 插入后光标定位到括号内
     *  - 带图标（方法节点图标）
     */
    private fun buildLookupElement(methodName: String): LookupElement {
        val description = METHOD_DESCRIPTIONS[methodName]
        val builder = LookupElementBuilder.create(methodName)
            .withIcon(AllIcons.Nodes.Method)
            .withTailText("()", true)
            .withInsertHandler(METHOD_INSERT_HANDLER)
        return description?.let { builder.withTypeText(it) } ?: builder
    }

    /**
     * 方法名插入处理器：插入 `methodName()` 后将光标定位到括号内。
     * 仅当下一字符不是 `(` 时插入括号（避免重复）。
     */
    private val METHOD_INSERT_HANDLER: InsertHandler<LookupElement> = InsertHandler { context, item ->
        val editor = context.editor
        val caretOffset = editor.caretModel.offset
        val doc = editor.document
        // 仅在下一字符不是 ( 时才插入括号（用户已输入时不重复）
        if (caretOffset >= doc.textLength || doc.charsSequence[caretOffset] != '(') {
            doc.insertString(caretOffset, "()")
            editor.caretModel.moveToOffset(caretOffset + 1)
        }
    }
}
