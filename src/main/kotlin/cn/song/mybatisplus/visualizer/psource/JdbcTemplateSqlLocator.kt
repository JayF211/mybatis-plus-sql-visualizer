package cn.song.mybatisplus.visualizer.psource

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.PsiTreeUtil

/**
 * JdbcTemplate / NamedParameterJdbcTemplate SQL 字符串定位器（JDBC 模板 SQL 字符串预览）。
 *
 * 识别 Spring JDBC 模板方法的字符串 SQL 实参，返回聚合后的 SQL 调用信息。
 *
 * 识别规则（对齐用户两段真实示例）：
 *  - 示例一：`String sql = "SELECT ..." + "..."; ... .queryForList(sql, paramMap)` →
 *    实参为局部变量引用，沿 initializer 聚合字面量拼接
 *  - 示例二：`StringBuilder sql = new StringBuilder(); sql.append(querySelect)...;
 *    .selectListPagination(X.class, sql.toString(), paramMap)` →
 *    实参为 `sql.toString()`（方法调用），定位到 StringBuilder 变量后聚合 append 链
 *
 * 识别策略（方法名 O(1) 判定 + 实参结构分析）：
 *  1. 方法名 ∈ JDBC_TEMPLATE_METHODS（大小写不敏感）
 *  2. 第 1 实参为字符串表达式 / StringBuilder 变量 / 字符串拼接 / `sql.toString()` 调用
 *  3. 非字符串实参（回调重载等）→ 返回 null
 *
 * 入口兼容 PsiIdentifier（gutter 锚点）与 PsiMethodCallExpression（action 精确匹配）。
 */
object JdbcTemplateSqlLocator {

    /** JDBC 模板 SQL 调用信息（定位器产物）。 */
    data class JdbcTemplateSqlCall(
        /** 方法名（queryForList / update / queryForObject ...）。 */
        val methodName: String,
        /** 字符串实参表达式（可能 null = 无法定位到字符串）。 */
        val sqlExpr: PsiExpression?,
        /** 拼接聚合后的 SQL 文本（含可能残留的运行时值占位）。 */
        val sqlString: String,
        /** StringBuilder 变量名（sqlExpr 为 `sql.toString()` 等变量引用时非 null）。 */
        val builderVariable: String?,
        /** true = 完全静态可聚合；false = 含运行时值（降级提示）。 */
        val isLiteral: Boolean,
        /** 参数实参表达式（Map / 对象，未使用，保留扩展）。 */
        val parameterExpression: PsiExpression?,
        /** 源方法调用表达式（供预览服务引用）。 */
        val callExpression: PsiMethodCallExpression,
    )

    /**
     * 定位 JdbcTemplate SQL 字符串调用。
     *
     * @param anchor 光标所在 PSI 元素（PsiIdentifier / PsiMethodCallExpression / 任意）
     * @return 定位结果；未命中返回 null
     */
    fun locate(anchor: PsiElement): JdbcTemplateSqlCall? {
        val call = resolveCallExpression(anchor) ?: return null
        val methodName = call.methodExpression.referenceName ?: return null
        if (!JdbcTemplateMethodDetector.isStringSqlMethodCandidate(methodName)) return null
        return locateFromCall(call)
    }

    /**
     * 从方法调用表达式定位 SQL 字符串。
     */
    fun locateFromCall(call: PsiMethodCallExpression): JdbcTemplateSqlCall? {
        val methodName = call.methodExpression.referenceName ?: return null
        if (!JdbcTemplateMethodDetector.isStringSqlMethodCandidate(methodName)) return null

        val args = call.argumentList.expressions
        if (args.isEmpty()) return null

        // 分析第 1 个字符串形态实参：定位 SQL 字符串表达式（T-DaoSql-02——
        // Spring JDBC 模板 SQL 即首参，行为不变；自研框架 (Class, sql, ...) 形态命中第 2 参）
        val (sqlExpr, builderVar) = resolveSqlArgument(args) ?: return null

        // 参数实参：SQL 实参之后的一个（queryForList(sql, paramMap) → paramMap；
        // selectList(Class, sql, params) 形态首参为 Class，参数在 sql 后——保留扩展位）
        val sqlIndex = args.indexOfFirst { it === sqlExpr }
        val paramArg = args.getOrNull(sqlIndex + 1)

        // 聚合为单一 SQL 文本
        val concatResult = SqlStringConcatenator.concat(sqlExpr) ?: return null

        return JdbcTemplateSqlCall(
            methodName = methodName,
            sqlExpr = sqlExpr,
            sqlString = concatResult.sql,
            builderVariable = builderVar,
            isLiteral = concatResult.complete,
            parameterExpression = paramArg,
            callExpression = call,
        )
    }

    // ------------------------------------------------------------------
    // 调用表达式解析
    // ------------------------------------------------------------------

    /** 从锚点解析出 PsiMethodCallExpression（向上查找最内层包含 anchor 的方法调用）。 */
    private fun resolveCallExpression(anchor: PsiElement): PsiMethodCallExpression? {
        // anchor 自身是方法调用
        if (anchor is PsiMethodCallExpression) return anchor
        // anchor 是方法名 PsiIdentifier → 取父链 PsiReferenceExpression → 祖父 PsiMethodCallExpression
        if (anchor is PsiIdentifier) {
            val parent = anchor.parent as? PsiReferenceExpression ?: return null
            val grandParent = parent.parent as? PsiMethodCallExpression ?: return null
            if (grandParent.methodExpression.referenceName == anchor.text) return grandParent
        }
        // anchor 在其他位置 → 向上查找包含它的方法调用
        return PsiTreeUtil.getParentOfType(anchor, PsiMethodCallExpression::class.java, false)
    }

    // ------------------------------------------------------------------
    // SQL 表达式定位
    // ------------------------------------------------------------------

    /**
     * 从实参列表解析出 SQL 字符串表达式（T-DaoSql-02）：取第一个字符串形态实参。
     *
     * 处理形态：
     *  - 字面量 / 拼接表达式 → 直接作为 SQL 表达式
     *  - `sql.toString()` 调用 → qualifier 解析为变量，作为 StringBuilder / 字符串变量
     *  - 变量引用 → 直接作为 SQL 表达式（concat 内部沿 initializer 追踪）
     *  - Class 字面量 / Map / 集合等非字符串形态（自研框架首参）→ 跳过看下一实参
     *
     * @return Pair(SQL 表达式, StringBuilder 变量名)；无字符串形态实参返回 null
     */
    private fun resolveSqlArgument(args: Array<PsiExpression>): Pair<PsiExpression, String?>? {
        for (arg in args) {
            when (arg) {
                is com.intellij.psi.PsiLiteralExpression ->
                    if (arg.value is String) return arg to null

                is com.intellij.psi.PsiBinaryExpression, is com.intellij.psi.PsiPolyadicExpression ->
                    return arg to null

                is com.intellij.psi.PsiMethodCallExpression ->
                    // `sql.toString()` 等：qualifier 是变量引用
                    resolveToStringCall(arg)?.let { return it }

                is PsiReferenceExpression ->
                    return arg to null

                else -> { /* Class 字面量（PsiClassObjectAccessExpression）/ 数组等 → 跳过 */ }
            }
        }
        return null
    }

    /**
     * 解析 `sql.toString()` / `sql.append(x).toString()` 等调用链：
     * 取 qualifier（最终持有变量），作为 SQL 表达式。
     */
    private fun resolveToStringCall(call: PsiMethodCallExpression): Pair<PsiExpression, String?>? {
        val methodName = call.methodExpression.referenceName ?: return null
        if (methodName != "toString" && methodName != "append") return null
        var qualifier = call.methodExpression.qualifierExpression ?: return null
        // 沿链向上：`sql.append(x).toString()` → 最终 qualifier 仍是 `sql`
        while (qualifier is PsiMethodCallExpression) {
            qualifier = qualifier.methodExpression.qualifierExpression ?: break
        }
        if (qualifier !is PsiReferenceExpression) return null
        val builderVar = qualifier.referenceName
        return qualifier to builderVar
    }
}
