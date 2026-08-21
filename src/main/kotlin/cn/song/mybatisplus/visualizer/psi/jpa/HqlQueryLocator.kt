package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.psi.PsiBinaryExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil

/**
 * Hibernate HQL 查询信息（HqlQueryLocator 产物）。
 *
 * @param methodName     HQL 所在方法名
 * @param hql            HQL 字符串（字面量直接取值；变量引用时为变量名）
 * @param isLiteralHql   hql 是否为字面量（true=已提取到字符串；false=变量引用，需运行时确定）
 * @param entityClass    返回实体类（createQuery(Xxx.class) 解析）；null 表示未指定
 * @param entityClassName 返回实体类名（可解析时非 null）
 * @param parameters     参数设置列表（setParameter/setInteger 等）
 * @param method         HQL 所在 PsiMethod
 */
data class HqlQueryInfo(
    val methodName: String,
    val hql: String,
    val isLiteralHql: Boolean,
    val entityClass: PsiClass?,
    val entityClassName: String?,
    val parameters: List<HqlParameter>,
    val method: PsiMethod,
    val isNativeQuery: Boolean = false,
    val createQueryMethodName: String = "",
)

/**
 * HQL 参数设置信息（setParameter/setInteger 等调用）。
 *
 * @param paramName 参数名（命名参数）或位置索引字符串（位置参数）
 * @param valueExpr 参数值表达式文本
 * @param isPositional 是否为位置参数（setInteger(0, value) 等）
 */
data class HqlParameter(
    val paramName: String,
    val valueExpr: String,
    val isPositional: Boolean = false,
)

/**
 * Hibernate HQL 定位器（F-JPA-02 / Wave2-P1）。
 *
 * 从光标位置识别 Hibernate Session.createQuery() 调用链，提取 HQL 字符串与实体上下文。
 *
 * 识别模式：
 *  - `session.createQuery("HQL字符串", Entity.class)` 或 `session.createQuery("HQL字符串")`
 *  - `sessionFactory.getCurrentSession().createQuery(...)`
 *  - `session.createNamedQuery("name")`（命名查询，hql 字段存查询名）
 *  - 参数设置：`query.setParameter("name", value)` / `query.setInteger("name", value)` 等
 *
 * 定位策略：
 *  1. 从光标向上查找包含 createQuery 调用的代码块或方法体；
 *  2. 扫描范围内全部 PsiMethodCallExpression；
 *  3. 识别 `createQuery` / `createNamedQuery` 调用并提取 HQL 字符串与实体类型；
 *  4. 追踪 Query 变量上的 setParameter/setXxx 调用提取参数信息。
 */
object HqlQueryLocator {

    // Hibernate Session.createQuery / createNamedQuery / createNativeQuery 方法名
    private val CREATE_QUERY_METHODS = setOf("createQuery", "createNamedQuery", "createNativeQuery")

    // Query 接口参数设置方法名
    private val SET_PARAM_METHODS = setOf(
        "setParameter", "setString", "setInteger", "setLong", "setDouble",
        "setFloat", "setBoolean", "setByte", "setShort", "setCharacter",
        "setDate", "setTime", "setTimestamp", "setCalendar",
    )

    // 位置参数设置方法名（第一个参数为 int 索引）
    private val SET_POSITIONAL_METHODS = setOf(
        "setString", "setInteger", "setLong", "setDouble",
        "setFloat", "setBoolean", "setByte", "setShort", "setCharacter",
    )

    /**
     * 从光标位置定位 HQL 查询调用链。
     *
     * 定位策略（精确匹配）：
     *  1. anchor 是 PsiMethodCallExpression → 精确匹配该调用（验证字符串参数）
     *  2. anchor 是 PsiIdentifier（gutter 图标）→ 向上查找包含它的 createQuery 调用
     *  3. anchor 是 PsiMethod（测试场景）→ 取方法体内第一个匹配
     *
     * @param anchor 光标所在 PSI 元素
     * @return HqlQueryInfo；未识别到 HQL 调用链时返回 null
     */
    fun locate(anchor: PsiElement): HqlQueryInfo? {
        // 路径 1：anchor 是 PsiMethodCallExpression → 精确匹配该调用
        if (anchor is PsiMethodCallExpression) {
            if (anchor.methodExpression.referenceName !in CREATE_QUERY_METHODS) return null
            if (!hasStringHqlArgument(anchor)) return null
            return locateFromCall(anchor)
        }

        // 路径 2：anchor 是 PsiMethod 自身（测试场景）→ 取方法体内第一个 createQuery
        if (anchor is PsiMethod) {
            return locateFirstInBody(anchor, null)
        }

        // 路径 3：查找包含 anchor 的最内层 createQuery 调用
        val containingCall = findContainingCreateQueryCall(anchor)
        if (containingCall != null) {
            if (!hasStringHqlArgument(containingCall)) return null
            return locateFromCall(containingCall)
        }

        // 路径 4：anchor 位于方法体内但不在某个 createQuery 调用内 → 取方法体内第一个
        val method = PsiTreeUtil.getParentOfType(anchor, PsiMethod::class.java) ?: return null
        return locateFirstInBody(method, null)
    }

    /**
     * 查找包含 anchor 的最内层 createQuery/createNamedQuery 调用。
     * 沿 PSI 树向上遍历，返回第一个匹配的方法调用表达式。
     */
    private fun findContainingCreateQueryCall(anchor: PsiElement): PsiMethodCallExpression? {
        var current: PsiElement? = anchor
        while (current != null) {
            if (current is PsiMethodCallExpression) {
                val methodName = current.methodExpression.referenceName
                if (methodName in CREATE_QUERY_METHODS) {
                    return current
                }
            }
            current = current.parent
        }
        return null
    }

    /**
     * 从指定 PsiMethod 体内查找 createQuery 调用。
     *
     * @param method 目标方法
     * @param anchor 锚点元素（非 null 时查找包含该锚点的调用；null 时返回第一个匹配）
     */
    private fun locateFirstInBody(method: PsiMethod, anchor: PsiElement?): HqlQueryInfo? {
        val body = method.body ?: return null
        val allCalls = PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression::class.java)
        for (call in allCalls) {
            val methodName = call.methodExpression.referenceName ?: continue
            if (methodName !in CREATE_QUERY_METHODS) continue
            // 有锚点时：仅返回包含该锚点的调用
            if (anchor != null && !call.textRange.contains(anchor.textRange)) continue
            // 验证字符串参数（排除 cb.createQuery(Class) 等）
            if (!hasStringHqlArgument(call)) continue
            return locateFromCall(call, allCalls.toList())
        }
        return null
    }

    /**
     * 检查 createQuery 调用的第一个参数是否为字符串（HQL/JPQL）。
     * 排除 cb.createQuery(Class) 等 CriteriaBuilder 调用。
     */
    private fun hasStringHqlArgument(call: PsiMethodCallExpression): Boolean {
        val args = call.argumentList.expressions
        if (args.isEmpty()) return false
        val firstArg = args[0]
        return when (firstArg) {
            is com.intellij.psi.PsiLiteralExpression -> firstArg.value is String
            is com.intellij.psi.PsiBinaryExpression -> true // 字符串拼接
            is PsiReferenceExpression -> {
                // 变量引用：尝试解析类型，仅 String 类型视为有效 HQL 参数
                val resolved = firstArg.resolve()
                if (resolved is com.intellij.psi.PsiVariable) {
                    val typeName = resolved.type.canonicalText
                    typeName == "java.lang.String" || typeName == "String"
                } else {
                    // 无法解析时保守视为无效（避免 cq 等 CriteriaQuery 变量被误判）
                    false
                }
            }
            else -> false // Class 字面量等
        }
    }

    /**
     * 从指定的 createQuery 调用提取 HqlQueryInfo。
     *
     * @param call 目标 createQuery 调用
     * @param allCalls 方法体内全部方法调用（用于参数追踪）；为 null 时自动收集
     */
    private fun locateFromCall(call: PsiMethodCallExpression, allCalls: List<PsiMethodCallExpression>? = null): HqlQueryInfo? {
        val method = PsiTreeUtil.getParentOfType(call, PsiMethod::class.java) ?: return null
        val (hql, isLiteral) = extractHqlString(call)
        val (entityClass, entityClassName) = extractEntityInfo(call)

        if (hql.isNullOrBlank()) return null

        // 追踪 Query 变量
        val queryVars = mutableSetOf<String>()
        findAssignedVariable(call)?.let { queryVars += it }

        // 第二遍：收集参数设置
        val parameters = mutableListOf<HqlParameter>()
        if (queryVars.isNotEmpty()) {
            val calls = allCalls ?: PsiTreeUtil.collectElementsOfType(method.body, PsiMethodCallExpression::class.java)
            for (paramCall in calls) {
                val paramMethodName = paramCall.methodExpression.referenceName ?: continue
                if (paramMethodName !in SET_PARAM_METHODS) continue
                val qualifier = paramCall.methodExpression.qualifierExpression ?: continue
                val qualifierRef = qualifier as? PsiReferenceExpression ?: continue
                if (qualifierRef.referenceName in queryVars) {
                    extractParameter(paramCall, paramMethodName)?.let { parameters += it }
                }
            }
        }

        val createQueryMethodName = call.methodExpression.referenceName ?: ""
        val isNativeQuery = createQueryMethodName == "createNativeQuery"

        return HqlQueryInfo(
            methodName = method.name,
            hql = hql,
            isLiteralHql = isLiteral,
            entityClass = entityClass,
            entityClassName = entityClassName,
            parameters = parameters,
            method = method,
            isNativeQuery = isNativeQuery,
            createQueryMethodName = createQueryMethodName,
        )
    }

    // ------------------------------------------------------------------
    // 作用域查找
    // ------------------------------------------------------------------

    /**
     * 从锚点向上查找包含 HQL 调用的代码块或方法体。
     */
    private fun findRelevantBlock(anchor: PsiElement): PsiElement? {
        var current: PsiElement? = anchor
        while (current != null) {
            when (current) {
                is PsiCodeBlock -> return current
                is PsiMethod -> return current.body
                is com.intellij.psi.PsiFile -> return current
            }
            current = current.parent
        }
        return null
    }

    // ------------------------------------------------------------------
    // HQL 字符串提取
    // ------------------------------------------------------------------

    /**
     * 从 createQuery/createNamedQuery 调用提取 HQL 字符串。
     *
     * @return Pair(hql字符串, 是否为字面量)
     */
    private fun extractHqlString(call: PsiMethodCallExpression): Pair<String?, Boolean> {
        val args = call.argumentList.expressions
        if (args.isEmpty()) return null to false

        val firstArg = args[0]
        return when (firstArg) {
            is PsiLiteralExpression -> {
                val value = firstArg.value as? String
                (value ?: firstArg.text) to true
            }
            is PsiBinaryExpression -> {
                // 字符串拼接："..." + "..." → 递归收集所有字符串字面量并拼接
                val parts = collectStringLiterals(firstArg)
                if (parts.isNotEmpty()) parts.joinToString("") to true
                else firstArg.text to false
            }
            is PsiReferenceExpression -> {
                // 变量引用：尝试解析为常量
                val resolved = firstArg.resolve()
                if (resolved is PsiVariable) {
                    val initializer = resolved.initializer
                    if (initializer is PsiLiteralExpression) {
                        (initializer.value as? String ?: initializer.text) to true
                    } else {
                        firstArg.referenceName to false
                    }
                } else {
                    firstArg.referenceName to false
                }
            }
            else -> firstArg.text to false
        }
    }

    /**
     * 递归收集 PSI 树中所有 PsiLiteralExpression 的字符串值。
     * 用于处理字符串拼接表达式（"..." + "..."）。
     */
    private fun collectStringLiterals(element: com.intellij.psi.PsiElement): List<String> {
        if (element is PsiLiteralExpression) {
            val value = element.value as? String ?: return emptyList()
            return listOf(value)
        }
        return element.children.flatMap { collectStringLiterals(it) }
    }

    // ------------------------------------------------------------------
    // 实体类型提取
    // ------------------------------------------------------------------

    /**
     * 从 createQuery(Xxx.class) 提取返回实体类信息。
     *
     * 提取策略（三级 fallback）：
     *  1. 第二个参数（Entity.class）→ PsiClass 解析
     *  2. 类型参数推断 → 编译器泛型信息
     *  3. 从 HQL/JPQL 字符串的 FROM 子句提取实体类名（单参数 createQuery 场景）
     */
    private fun extractEntityInfo(call: PsiMethodCallExpression): Pair<PsiClass?, String?> {
        val methodName = call.methodExpression.referenceName
        // createNamedQuery 无实体参数
        if (methodName == "createNamedQuery") return null to null

        // 查找第二个参数（Entity.class）
        val args = call.argumentList.expressions
        if (args.size >= 2) {
            val entityArg = args[1]
            if (entityArg is com.intellij.psi.PsiClassObjectAccessExpression) {
                val psiClass = (entityArg.operand.type as? com.intellij.psi.PsiClassType)?.resolve()
                return psiClass to (psiClass?.name ?: (entityArg.operand.type as? com.intellij.psi.PsiClassType)?.className)
            }
        }

        // 尝试从类型参数推断
        val typeArg = call.typeArguments.firstOrNull() as? com.intellij.psi.PsiClassType
        if (typeArg != null) {
            return typeArg.resolve() to typeArg.className
        }

        // 三级 fallback：从 HQL/JPQL 字符串的 FROM 子句提取实体类名
        // 适用于 em.createQuery("JPQL") 单参数场景（无 Entity.class 二参）
        if (args.isNotEmpty()) {
            val (hql, _) = extractHqlString(call)
            if (!hql.isNullOrBlank()) {
                val fromPattern = Regex("""\bFROM\s+([A-Z][a-zA-Z0-9_]*)\b""", RegexOption.IGNORE_CASE)
                fromPattern.find(hql)?.let { return null to it.groupValues[1] }
            }
        }

        return null to null
    }

    // ------------------------------------------------------------------
    // 变量追踪
    // ------------------------------------------------------------------

    /**
     * 查找方法调用被赋值给的局部变量名（如 `Query q = session.createQuery(...)` 的 q）。
     */
    private fun findAssignedVariable(call: PsiMethodCallExpression): String? {
        val parent = call.parent ?: return null
        // 局部变量声明：Type var = call;
        if (parent is PsiLocalVariable && parent.initializer === call) {
            return parent.name
        }
        // 赋值语句：var = call;
        if (parent is com.intellij.psi.PsiAssignmentExpression) {
            val lhs = parent.lExpression as? PsiReferenceExpression ?: return null
            return lhs.referenceName
        }
        return null
    }

    // ------------------------------------------------------------------
    // 参数提取
    // ------------------------------------------------------------------

    /**
     * 从 setParameter/setInteger 等调用提取参数信息。
     */
    private fun extractParameter(call: PsiMethodCallExpression, methodName: String): HqlParameter? {
        val args = call.argumentList.expressions
        if (args.isEmpty()) return null

        val isPositional = methodName in SET_POSITIONAL_METHODS && args.size >= 2

        return if (isPositional) {
            // 位置参数：setInteger(0, value) → paramName = "0"
            val indexArg = args[0]
            val indexText = when (indexArg) {
                is PsiLiteralExpression -> indexArg.value?.toString() ?: "0"
                else -> indexArg.text
            }
            val valueExpr = args[1].text
            HqlParameter(paramName = indexText, valueExpr = valueExpr, isPositional = true)
        } else {
            // 命名参数：setParameter("name", value) → paramName = "name"
            val nameArg = args[0]
            val paramName = when (nameArg) {
                is PsiLiteralExpression -> nameArg.value as? String ?: nameArg.text
                is PsiReferenceExpression -> nameArg.referenceName
                else -> nameArg.text
            } ?: return null
            val valueExpr = if (args.size > 1) args[1].text else ""
            HqlParameter(paramName = paramName, valueExpr = valueExpr, isPositional = false)
        }
    }
}
