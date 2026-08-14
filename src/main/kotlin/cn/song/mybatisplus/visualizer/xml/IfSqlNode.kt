package cn.song.mybatisplus.visualizer.xml

/**
 * 条件节点（T-M24-01）：复刻 MyBatis 3.5.19 `org.apache.ibatis.scripting.xmltags.IfSqlNode`。
 *
 * 真值判定顺序：
 *  1. 预览面板条件开关（[DynamicContext.conditionResult]，id = test 表达式原文）→ 强制真值；
 *  2. 否则 OGNL 求值（判真规则见 [OgnlEvaluator.isTrue]）；
 *  3. 表达式解析失败 → 默认展开（true，预览容错——面板开关兜底）。
 */
class IfSqlNode(val contents: SqlNode, val test: String) : SqlNode {

    override fun apply(context: DynamicContext): Boolean {
        val truth = context.conditionResult(test) ?: evaluate(context)
        if (truth) contents.apply(context)
        return truth
    }

    private fun evaluate(context: DynamicContext): Boolean = when (val result = OgnlEvaluator.evaluate(test, context.bindings)) {
        is EvalResult.Ok -> OgnlEvaluator.isTrue(result.value)
        is EvalResult.Err -> true // 解析失败：预览默认展开（面板开关可改）
    }
}

/**
 * choose 节点（T-M24-01）：复刻 MyBatis 3.5.19 `org.apache.ibatis.scripting.xmltags.ChooseSqlNode`——
 * 按顺序取第一个命中的 when 短路输出；全部不中且 otherwise 存在时输出 otherwise。
 */
class ChooseSqlNode(val whenNodes: List<SqlNode>, val defaultNode: SqlNode?) : SqlNode {

    override fun apply(context: DynamicContext): Boolean {
        for (node in whenNodes) {
            if (node.apply(context)) return true
        }
        defaultNode?.apply(context)
        return false
    }
}

/**
 * bind 变量声明节点（T-M24-01）：复刻 MyBatis 3.5.19 `VarDeclSqlNode`（3.5.12 前名 BindSqlNode）。
 *
 * 执行到该节点时对 value 表达式求值并绑定；同名覆盖。面板覆盖名（[DynamicContext.isProtected]）
 * 优先——跳过求值、保留用户输入值。
 */
class VarDeclSqlNode(val name: String, val expression: String) : SqlNode {

    override fun apply(context: DynamicContext): Boolean {
        if (context.isProtected(name)) return true
        val result = OgnlEvaluator.evaluate(expression, context.bindings)
        context.bind(name, when (result) {
            is EvalResult.Ok -> result.value
            is EvalResult.Err -> null
        })
        return true
    }
}
