package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.SqlValue
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable

/**
 * 表达式求值器（F-02 前置）：把 Wrapper 方法实参表达式求值为 [SqlValue]。
 *
 * 分级策略：
 *  - 字面量（数字 / 字符串 / 布尔 / char / null）：求值为 [SqlValue.Literal]，可直接渲染进 SQL
 *  - 变量引用：追踪同文件初始化赋值（M2-01，`Long deviceId = 1001L;` → `1001`），
 *    支持多级引用链；追踪不到（方法参数等运行时值）求值为 [SqlValue.Placeholder]
 *  - 其他表达式（算术 / 方法调用 / new）：求值为 [SqlValue.Placeholder]，渲染为 `?`
 */
object ExpressionEvaluator {

    /** 变量追踪最大深度，防止循环引用死循环。 */
    private const val MAX_TRACE_DEPTH = 5

    /**
     * 求值表达式。
     *
     * @param expression 实参表达式（PSI）
     * @return SQL 值模型
     */
    fun evaluate(expression: PsiExpression): SqlValue = when (expression) {
        is PsiLiteralExpression -> evaluateLiteral(expression)
        is PsiReferenceExpression -> evaluateReference(expression)
        // `X.class` 类字面量（MPJ join 参数）→ 求值为简单类名（如 CarDeviceInfoEntity）
        is PsiClassObjectAccessExpression -> evaluateClassLiteral(expression)
        else -> SqlValue.Placeholder(expression.text)
    }

    /**
     * 求值类字面量 `X.class` → 简单类名。
     * 直接从文本提取（如 `com.foo.X.class` → `X`），避免依赖类解析。
     */
    private fun evaluateClassLiteral(expr: PsiClassObjectAccessExpression): SqlValue {
        val className = expr.text.removeSuffix(".class").trim().substringAfterLast('.')
        return SqlValue.Literal(className)
    }

    /**
     * 求值字面量。
     */
    private fun evaluateLiteral(literal: PsiLiteralExpression): SqlValue {
        val value = literal.value
        return when (value) {
            // null 字面量 → SQL NULL
            null -> SqlValue.Literal("NULL")

            // 字符串 → 单引号包裹（转义内部单引号）；unquoted 保留原始值供 SQL 片段场景使用
            is String -> SqlValue.Literal(
                raw = "'" + value.replace("'", "''") + "'",
                unquoted = value,
            )

            // 布尔
            is Boolean -> SqlValue.Literal(value.toString())

            // 字符 → 单引号包裹
            is Char -> SqlValue.Literal("'$value'", unquoted = value.toString())

            // 数值类型（Byte/Short/Int/Long/Float/Double）
            is Number -> SqlValue.Literal(value.toString())

            // 其他（如文本块等）按文本原样
            else -> SqlValue.Literal(literal.text)
        }
    }

    /**
     * 求值变量引用（M2-01 变量追踪）。
     *
     * 沿变量的初始化表达式递归追踪：
     *  - `Long deviceId = 1001L;` → 字面量求值
     *  - `Long a = b; Long b = 2L;` → 多级引用链（受 [MAX_TRACE_DEPTH] 保护）
     *  - 无法静态确定（方法参数等）→ 占位符
     */
    private fun evaluateReference(ref: PsiReferenceExpression, depth: Int = 0): SqlValue {
        if (depth > MAX_TRACE_DEPTH) {
            return SqlValue.Placeholder(ref.text)
        }
        val variable = ref.resolve() as? PsiVariable ?: return SqlValue.Placeholder(ref.text)
        val initializer = variable.initializer ?: return SqlValue.Placeholder(ref.text)
        return when (initializer) {
            is PsiLiteralExpression -> evaluateLiteral(initializer)
            is PsiReferenceExpression -> evaluateReference(initializer, depth + 1)
            else -> SqlValue.Placeholder(ref.text)
        }
    }
}
