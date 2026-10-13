package cn.song.mybatisplus.visualizer.psource

import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiBinaryExpression
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiPolyadicExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiStatement
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil

/**
 * SQL 字符串拼接聚合器（JDBC 模板 SQL 字符串预览）：把分散在字面量 `+` 拼接 /
 * StringBuilder 分片 / 变量引用中的 SQL 片段聚合为单一文本。
 *
 * 两路实现：
 *  - 路径 A — 字面量 `+` 拼接（`"SELECT " + "..."`）→ 递归收集 `PsiLiteralExpression` 并 join
 *  - 路径 B — StringBuilder 聚合（`new StringBuilder().append(...)` 或
 *    `StringBuilder sql = new StringBuilder(); sql.append(querySelect)...`）→
 *    收集同一方法体内对目标变量的 `append` 调用（按 offset 排序），递归聚合每个实参
 *
 * 变量引用沿 initializer 递归追踪（不跨方法体），含运行时值时标记 `complete=false`
 * 并在结果中保留占位标记。
 *
 * 零 resolve（仅结构遍历 + initializer 读取），单测可注入假 PSI。
 */
object SqlStringConcatenator {

    /** 字符串聚合结果。 */
    data class ConcatenationResult(
        /** 聚合后的 SQL 文本（含可能残留的运行时值占位标记）。 */
        val sql: String,
        /** true = 完全静态可聚合；false = 含运行时值（预览降级）。 */
        val complete: Boolean,
    )

    /** 运行时值占位标记前缀（预览 warning 与 SQL 中保留位置）。 */
    const val DYNAMIC_PLACEHOLDER_PREFIX = "/*运行时值*/"

    /** 变量 initializer 追踪最大深度（防循环引用）。 */
    private const val MAX_TRACE_DEPTH = 5

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /**
     * 聚合表达式为 SQL 文本。
     *
     * @param expr SQL 字符串实参表达式（字面量 / 拼接 / 变量引用 / StringBuilder 变量）
     * @return 聚合结果；无法聚合返回 `null`
     */
    fun concat(expr: PsiExpression?): ConcatenationResult? {
        if (expr == null) return null
        return when (expr) {
            is PsiLiteralExpression -> concatLiteral(expr)
            is PsiBinaryExpression, is PsiPolyadicExpression -> concatPolyadic(expr)
            is PsiReferenceExpression -> concatReference(expr)
            else -> null
        }
    }

    /**
     * 聚合「StringBuffer/StringBuilder 变量 `.toString()` 调用链」形态：先把
     * `xxx.toString()` / `xxx.append(y).toString()` / `xxx.append(y)` 归约到持有变量引用，
     * 再交 [concat] 走既有聚合（变量 initializer 为 `new StringBuilder()` / `new StringBuffer()`
     * 时聚合其同方法体内全部 `append` 调用）。
     *
     * [concat] 自身对 `PsiMethodCallExpression` 无分支（返回 null），直接把 `sb.toString()`
     * 喂进去聚合不出内容——JdbcTemplate 通道靠 `JdbcTemplateSqlLocator.resolveToStringCall`
     * 做这步解包。本方法把同一语义提为共享入口，供 CustomDaoHqlLocator / HqlQueryLocator
     * 复用（T-HqlBufConcat），避免各处再写一份。
     *
     * @param expr SQL 字符串实参表达式（字面量 / 拼接 / 变量引用 / `sb.toString()` 调用链）
     * @return 聚合结果；无法聚合返回 `null`
     */
    fun concatUnwrappingToString(expr: PsiExpression?): ConcatenationResult? {
        if (expr == null) return null
        if (expr is PsiMethodCallExpression) {
            val target = unwrapToStringTarget(expr) ?: return null
            return concat(target)
        }
        return concat(expr)
    }

    /**
     * 把 `.toString()` / `.append(y)` 调用链归约到根变量引用
     * （`xxx.append(y).toString()` → `xxx`）。
     *
     * 方法名不在 {toString, append} 内、或 qualifier 链不落到变量引用时返回 null。
     */
    private fun unwrapToStringTarget(call: PsiMethodCallExpression): PsiExpression? {
        val name = call.methodExpression.referenceName ?: return null
        if (name != "toString" && name != "append") return null
        var qualifier: PsiExpression? = call.methodExpression.qualifierExpression ?: return null
        while (qualifier is PsiMethodCallExpression) {
            qualifier = qualifier.methodExpression.qualifierExpression ?: return null
        }
        return qualifier
    }

    // ------------------------------------------------------------------
    // 路径 A — 字面量 + 拼接
    // ------------------------------------------------------------------

    private fun concatLiteral(literal: PsiLiteralExpression): ConcatenationResult? {
        val value = literal.value as? String ?: return null
        return ConcatenationResult(sql = value, complete = true)
    }

    private fun concatPolyadic(expr: PsiExpression): ConcatenationResult? {
        if (expr !is PsiPolyadicExpression) return null
        if (expr.operationTokenType != JavaTokenType.PLUS) return null
        val parts = mutableListOf<String>()
        var complete = true
        for (operand in expr.operands) {
            when (val r = concat(operand)) {
                null -> {
                    // 不可静态求值的操作数（运行时值）→ 标记降级 + 保留占位
                    complete = false
                    parts += "$DYNAMIC_PLACEHOLDER_PREFIX${operand.text}*/"
                }
                else -> {
                    if (!r.complete) complete = false
                    parts += r.sql
                }
            }
        }
        return ConcatenationResult(sql = parts.joinToString(""), complete = complete)
    }

    // ------------------------------------------------------------------
    // 变量引用（路径 A/B 共享）
    // ------------------------------------------------------------------

    private fun concatReference(ref: PsiReferenceExpression, depth: Int = 0): ConcatenationResult? {
        if (depth > MAX_TRACE_DEPTH) return null
        val variable = ref.resolve() as? PsiVariable ?: return null
        val initializer = variable.initializer ?: return null
        return when (initializer) {
            is PsiLiteralExpression -> concatLiteral(initializer)
            is PsiBinaryExpression, is PsiPolyadicExpression -> concatPolyadic(initializer)
            is PsiReferenceExpression -> concatReference(initializer, depth + 1)
            is PsiNewExpression -> concatNewStringBuilder(initializer, variable, depth)
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // 路径 B — StringBuilder 聚合
    // ------------------------------------------------------------------

    /**
     * 聚合 `new StringBuilder(...)` 初值 + 同一方法体内全部 `append` 调用。
     *
     * @param newExpr      `new StringBuilder(...)` 表达式
     * @param variable     持有该 new 表达式的变量（用于查找 append 调用）
     * @param depth        当前追踪深度
     */
    private fun concatNewStringBuilder(
        newExpr: PsiNewExpression,
        variable: PsiVariable,
        depth: Int,
    ): ConcatenationResult? {
        val parts = mutableListOf<String>()
        var complete = true

        // 初值（`new StringBuilder("SELECT ...")`）
        val args = newExpr.argumentList?.expressions
        if (args != null && args.isNotEmpty()) {
            val initExpr = args[0]
            when (val r = concat(initExpr)) {
                null -> {
                    complete = false
                    parts += "$DYNAMIC_PLACEHOLDER_PREFIX${initExpr.text}*/"
                }
                else -> {
                    if (!r.complete) complete = false
                    parts += r.sql
                }
            }
        }

        // 查找同一方法体内对目标变量的 `append` 调用
        val appendResults = collectAppendCalls(variable, depth)
        for (r in appendResults) {
            if (!r.complete) complete = false
            parts += r.sql
        }

        val sql = parts.joinToString("")
        if (sql.isBlank() && appendResults.isEmpty()) return null
        return ConcatenationResult(sql = sql, complete = complete)
    }

    /**
     * 收集同一方法体内对目标变量的 `append` 调用（按 offset 排序）。
     *
     * 查找范围：目标变量所在方法体（PsiCodeBlock），过滤
     * `methodExpression.referenceName == "append"` 且 qualifier 解析到目标变量的调用。
     */
    private fun collectAppendCalls(variable: PsiVariable, depth: Int): List<ConcatenationResult> {
        val method = PsiTreeUtil.getParentOfType(variable, com.intellij.psi.PsiMethod::class.java)
            ?: return emptyList()
        val body = method.body ?: return emptyList()
        val varName = variable.name ?: return emptyList()
        if (varName.isBlank()) return emptyList()

        val results = mutableListOf<ConcatenationResult>()
        val appendCalls = PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression::class.java)
            .filter { call ->
                val methodName = call.methodExpression.referenceName ?: return@filter false
                if (methodName != "append") return@filter false
                // qualifier 可以是直接变量引用，也可以是链式方法调用（sql.append("A").append(x)）——
                // 沿 qualifier 链回溯到根变量，判定是否为目标变量
                val rootVar = resolveRootVariable(call.methodExpression.qualifierExpression)
                    ?: return@filter false
                rootVar == variable
            }
            // 按 startOffset 升序；链式调用中多层 append 共享同一 startOffset（都从 `sql` 起），
            // 此时按文本长度升序（内层最短 → 排前面），保证 append 实参按正确顺序拼接
            .sortedWith(compareBy({ it.textRange.startOffset }, { it.textLength }))

        for (call in appendCalls) {
            val args = call.argumentList.expressions
            if (args.isEmpty()) continue
            val argExpr = args[0]
            when (val r = concat(argExpr)) {
                null -> {
                    results += ConcatenationResult(
                        sql = "$DYNAMIC_PLACEHOLDER_PREFIX${argExpr.text}*/",
                        complete = false,
                    )
                }
                else -> results += r
            }
        }
        return results
    }

    /**
     * 沿 qualifier 链回溯根变量。
     *
     * 处理两种形态：
     *  - 直接调用 `sql.append(...)` → qualifier 是 PsiReferenceExpression → 直接解析
     *  - 链式调用 `sql.append("A").append(x).append("B")` → 外层 qualifier 是 PsiMethodCallExpression →
     *    递归取 qualifier 的 qualifier，直到回溯到 PsiReferenceExpression
     *
     * @param qualifier 方法调用的 qualifier 表达式（可能 null）
     * @return 根变量；无法解析返回 null
     */
    private fun resolveRootVariable(qualifier: PsiExpression?): PsiVariable? {
        var current: PsiExpression? = qualifier
        while (current != null) {
            current = when (current) {
                is PsiReferenceExpression -> return current.resolve() as? PsiVariable
                is PsiMethodCallExpression -> current.methodExpression.qualifierExpression
                else -> return null
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 递归收集 PSI 树中全部 `PsiLiteralExpression` 的字符串值（路径 A 兜底）。
     * 用于处理嵌套拼接表达式。
     */
    fun collectStringLiterals(element: PsiElement): List<String> {
        if (element is PsiLiteralExpression) {
            val value = element.value as? String ?: return emptyList()
            return listOf(value)
        }
        return element.children.flatMap { collectStringLiterals(it) }
    }
}
