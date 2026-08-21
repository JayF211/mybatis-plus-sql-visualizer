package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.psi.PsiBinaryExpression
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
 * 自定义 DAO 方法的 HQL 查询信息（CustomDaoHqlLocator 产物）。
 *
 * @param hql            HQL 字符串（字面量直接取值；变量引用或拼接时为拼接结果）
 * @param isLiteralHql   hql 是否为纯字面量（true=直接字面量；false=变量引用或拼接）
 * @param entityClassName 从 HQL 的 FROM 子句提取的实体类名（驼峰标识符）；null 表示未提取到
 * @param parameters     参数列表（以 HQL 中 :paramName 为主，值从方法参数中按名匹配）
 * @param method         HQL 所在 PsiMethod
 * @param paramSource    参数传递方式（name-value 交替 / Map / 位置参数 / 无额外参数）
 */
data class CustomDaoHqlInfo(
    val hql: String,
    val isLiteralHql: Boolean,
    val entityClassName: String?,
    val parameters: List<HqlParameter>,
    val method: PsiMethod,
    val paramSource: ParamSource,
)

/**
 * 自定义 DAO 方法调用中参数传递方式枚举。
 *
 * 用于描述 HQL 之后的参数列表组织形式：
 *  - NAME_VALUE_PAIRS：("name1", v1, "name2", v2) 交替传递
 *  - MAP：单 Map 参数包含所有键值
 *  - POSITIONAL：仅传值，按位置顺序
 *  - NONE：无额外参数
 */
enum class ParamSource { NAME_VALUE_PAIRS, MAP, POSITIONAL, NONE }

/**
 * 自定义 DAO HQL 定位器（F-JPA-02 / Wave2 扩展）。
 *
 * 从光标位置识别自定义 DAO 方法中的 HQL 字符串调用，弥补 [HqlQueryLocator] 仅识别标准 Hibernate Session.createQuery() 的不足。
 *
 * 识别模式：
 *  - `commonDao.findByQueryUniqueResult("select ... from Entity where ... :paramList", "paramList", value)`
 *  - `baseDao.executeHql("update Entity set ... where ...", paramMap)`
 *  - `xxxDao.findByQuery("from Entity where field = :id", "id", idValue)`
 *
 * 判定逻辑（三重启发取交集）：
 *  1. **qualifier 启发**：方法调用的 qualifier 变量名匹配 DAO 模式（*Dao / *DaoImpl / *Mapper / *Repository / *CommonDao 等）
 *  2. **方法名启发**：方法名含 Query / Hql / Jpql / execute 等关键字
 *  3. **内容启发**：字符串参数匹配 HQL 语法模式（SELECT...FROM / UPDATE...SET / DELETE...FROM / FROM Entity）
 *
 * 最终判定：(1 OR 2) AND 3
 *
 * 定位策略：
 *  1. 从光标向上找到 PsiMethod；
 *  2. 扫描方法体内全部 PsiMethodCallExpression；
 *  3. 对每个方法调用依次应用三重启发检测；
 *  4. 匹配成功时提取 HQL 字符串（支持字面量、拼接、变量引用）和参数信息；
 *  5. 从 HQL 的 FROM 子句提取实体类名。
 */
object CustomDaoHqlLocator {

    /**
     * DAO 类名后缀集合。
     *
     * 用于 qualifier 启发：方法调用的 qualifier 变量名以任一后缀结尾即视为 DAO 对象。
     */
    private val DAO_QUALIFIER_SUFFIXES = setOf(
        "Dao", "DaoImpl", "Mapper", "Repository", "CommonDao", "DaoHelper", "BaseDao"
    )

    /**
     * 查询方法名关键字集合。
     *
     * 用于方法名启发：方法名包含任一关键字即视为潜在查询方法。
     */
    private val QUERY_METHOD_KEYWORDS = setOf(
        "Query", "Hql", "Jpql", "execute", "findByQuery", "findBySql"
    )

    /**
     * HQL 内容匹配正则模式集合。
     *
     * 用于内容启发：字符串参数需匹配至少一个模式才视为 HQL。
     */
    private val HQL_CONTENT_PATTERNS = listOf(
        Regex("""\bSELECT\b.*\bFROM\b""", RegexOption.IGNORE_CASE),
        Regex("""\bUPDATE\b.*\bSET\b""", RegexOption.IGNORE_CASE),
        Regex("""\bDELETE\b.*\bFROM\b""", RegexOption.IGNORE_CASE),
        Regex("""\bFROM\b\s+[A-Z][a-zA-Z0-9_]*\b""", RegexOption.IGNORE_CASE),
    )

    /**
     * HQL 命名参数正则。
     *
     * 匹配 `:paramName` 形式的命名参数（排除 `::` 类型转换运算符）。
     */
    private val NAMED_PARAM_PATTERN = Regex("""(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)""")

    /**
     * HQL FROM 子句实体类名提取正则。
     *
     * 匹配 `FROM EntityName` 中的驼峰类名。
     */
    private val FROM_ENTITY_PATTERN = Regex("""\bFROM\s+([A-Z][a-zA-Z0-9_]*)\b""", RegexOption.IGNORE_CASE)

    /**
     * HQL UPDATE/DELETE 语句实体类名提取正则。
     *
     * 匹配 `UPDATE EntityName SET` 或 `DELETE FROM EntityName` 中的驼峰类名。
     */
    private val UPDATE_ENTITY_PATTERN = Regex("""\bUPDATE\s+([A-Z][a-zA-Z0-9_]*)\b""", RegexOption.IGNORE_CASE)
    private val DELETE_ENTITY_PATTERN = Regex("""\bDELETE\s+FROM\s+([A-Z][a-zA-Z0-9_]*)\b""", RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------------
    // 公开 API
    // ------------------------------------------------------------------

    /**
     * 从光标位置定位自定义 DAO 方法中的 HQL 调用。
     *
     * 定位策略（精确匹配）：
     *  1. 如果 anchor 是 PsiMethodCallExpression，直接检查该调用本身是否为 DAO HQL 调用；
     *  2. 如果 anchor 是 PsiMethod（测试场景），取方法体内第一个匹配；
     *  3. 否则向上查找宿主方法，扫描体内全部调用。
     *
     * @param anchor 光标所在 PSI 元素
     * @return CustomDaoHqlInfo；未识别到自定义 DAO HQL 调用时返回 null
     */
    fun locate(anchor: PsiElement): CustomDaoHqlInfo? {
        // 路径 1：anchor 是 PsiMethodCallExpression → 精确匹配该调用
        if (anchor is PsiMethodCallExpression) {
            return locateFromCall(anchor)
        }

        // 路径 2：anchor 是 PsiMethod 自身（测试场景）→ 取方法体内第一个匹配
        val method = anchor as? PsiMethod ?: PsiTreeUtil.getParentOfType(anchor, PsiMethod::class.java) ?: return null
        val body = method.body ?: return null
        val allCalls = PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression::class.java)

        // 路径 3：扫描方法体内全部方法调用，识别自定义 DAO HQL 调用
        for (call in allCalls) {
            val info = locateFromCall(call)
            if (info != null) return info
        }

        return null
    }

    /**
     * 从指定的 PsiMethodCallExpression 提取 CustomDaoHqlInfo。
     * 仅当该调用匹配 DAO HQL 三重启发检测时才返回非 null。
     */
    private fun locateFromCall(call: PsiMethodCallExpression): CustomDaoHqlInfo? {
        if (!isCustomDaoHqlCall(call)) return null

        val (hql, isLiteral) = extractHqlFromCall(call)
        if (hql.isNullOrBlank()) return null

        val method = PsiTreeUtil.getParentOfType(call, PsiMethod::class.java) ?: return null
        val entityClassName = extractEntityClassNameFromHql(hql)
        val paramSource = detectParamSource(call)
        val hqlParamNames = extractNamedParams(hql)
        val parameters = resolveParameters(call, hqlParamNames)

        return CustomDaoHqlInfo(
            hql = hql,
            isLiteralHql = isLiteral,
            entityClassName = entityClassName,
            parameters = parameters,
            method = method,
            paramSource = paramSource,
        )
    }

    // ------------------------------------------------------------------
    // 三重启发检测
    // ------------------------------------------------------------------

    /**
     * 判断方法调用是否为自定义 DAO HQL 调用。
     *
     * 判定逻辑：(qualifier 启发 OR 方法名启发) AND 内容启发
     *
     * @param call 目标方法调用
     * @return 是否匹配自定义 DAO HQL 调用
     */
    private fun isCustomDaoHqlCall(call: PsiMethodCallExpression): Boolean {
        val contentMatch = matchesHqlContent(call)
        if (!contentMatch) return false

        val qualifierMatch = matchesDaoQualifier(call)
        val methodNameMatch = matchesQueryMethodName(call)

        return qualifierMatch || methodNameMatch
    }

    /**
     * qualifier 启发检测。
     *
     * 检查方法调用的 qualifier 变量名是否以 DAO 后缀结尾。
     * 例如：`commonDao.findByQuery(...)` 的 qualifier 为 `commonDao`，以 `Dao` 结尾即匹配。
     *
     * @param call 目标方法调用
     * @return qualifier 是否匹配 DAO 模式
     */
    private fun matchesDaoQualifier(call: PsiMethodCallExpression): Boolean {
        val qualifier = call.methodExpression.qualifierExpression ?: return false
        val referenceExpr = qualifier as? PsiReferenceExpression ?: return false
        val qualifierName = referenceExpr.referenceName ?: return false
        return DAO_QUALIFIER_SUFFIXES.any { suffix -> qualifierName.endsWith(suffix) }
    }

    /**
     * 方法名启发检测。
     *
     * 检查方法名是否包含查询关键字（Query / Hql / Jpql / execute 等）。
     *
     * @param call 目标方法调用
     * @return 方法名是否包含查询关键字
     */
    private fun matchesQueryMethodName(call: PsiMethodCallExpression): Boolean {
        val methodName = call.methodExpression.referenceName ?: return false
        return QUERY_METHOD_KEYWORDS.any { keyword -> methodName.contains(keyword, ignoreCase = true) }
    }

    /**
     * 内容启发检测。
     *
     * 检查第一个字符串参数是否匹配 HQL 语法模式（SELECT...FROM / UPDATE...SET / DELETE...FROM / FROM Entity）。
     *
     * @param call 目标方法调用
     * @return 字符串参数是否匹配 HQL 内容模式
     */
    private fun matchesHqlContent(call: PsiMethodCallExpression): Boolean {
        val firstArg = call.argumentList.expressions.firstOrNull() ?: return false
        val text = extractArgumentText(firstArg) ?: return false
        return HQL_CONTENT_PATTERNS.any { pattern -> pattern.containsMatchIn(text) }
    }

    // ------------------------------------------------------------------
    // HQL 字符串提取
    // ------------------------------------------------------------------

    /**
     * 从自定义 DAO 方法调用提取 HQL 字符串。
     *
     * 支持：
     *  - 字符串字面量（PsiLiteralExpression）→ 直接取值
     *  - 字符串拼接（"..." + "..."）→ 递归收集 PsiLiteralExpression 子节点并拼接
     *  - 变量引用（PsiReferenceExpression）→ 尝试解析为常量
     *
     * @param call 目标方法调用
     * @return Pair(HQL 字符串, 是否为纯字面量)
     */
    private fun extractHqlFromCall(call: PsiMethodCallExpression): Pair<String?, Boolean> {
        val args = call.argumentList.expressions
        if (args.isEmpty()) return null to false

        val firstArg = args[0]
        return extractHqlString(firstArg)
    }

    /**
     * 从表达式提取 HQL 字符串。
     *
     * @param expression HQL 参数表达式
     * @return Pair(HQL 字符串, 是否为纯字面量)
     */
    private fun extractHqlString(expression: PsiExpression): Pair<String?, Boolean> {
        return when (expression) {
            is PsiLiteralExpression -> {
                val value = expression.value as? String
                (value ?: expression.text) to true
            }
            is PsiBinaryExpression -> {
                // 字符串拼接："..." + "..." → 递归收集所有 PsiLiteralExpression 子节点
                val parts = collectStringLiterals(expression)
                if (parts.isNotEmpty()) {
                    parts.joinToString("") to true
                } else {
                    expression.text to false
                }
            }
            is PsiReferenceExpression -> {
                // 变量引用：尝试解析为常量
                val resolved = expression.resolve()
                if (resolved is PsiVariable) {
                    val initializer = resolved.initializer
                    if (initializer != null) {
                        extractHqlString(initializer)
                    } else {
                        expression.referenceName to false
                    }
                } else {
                    expression.referenceName to false
                }
            }
            else -> expression.text to false
        }
    }

    /**
     * 从参数表达式提取文本内容（用于内容启发检测）。
     *
     * 对于字面量直接取值；对于拼接表达式拼接所有字面量部分；其他情况返回表达式文本。
     *
     * @param expression 参数表达式
     * @return 提取的文本内容；无法提取时返回 null
     */
    private fun extractArgumentText(expression: PsiExpression): String? {
        return when (expression) {
            is PsiLiteralExpression -> expression.value as? String ?: expression.text
            is PsiBinaryExpression -> {
                val parts = collectStringLiterals(expression)
                if (parts.isNotEmpty()) parts.joinToString("") else expression.text
            }
            is PsiReferenceExpression -> {
                val resolved = expression.resolve()
                if (resolved is PsiVariable) {
                    val initializer = resolved.initializer
                    if (initializer is PsiLiteralExpression) {
                        initializer.value as? String
                    } else {
                        expression.referenceName
                    }
                } else {
                    expression.referenceName
                }
            }
            else -> expression.text
        }
    }

    /**
     * 递归收集 PSI 树中所有 PsiLiteralExpression 的字符串值。
     *
     * 用于处理字符串拼接表达式（"..." + "..."）和嵌套拼接。
     *
     * @param element 起始 PSI 元素
     * @return 收集到的字符串列表
     */
    private fun collectStringLiterals(element: PsiElement): List<String> {
        if (element is PsiLiteralExpression) {
            val value = element.value as? String ?: return emptyList()
            return listOf(value)
        }
        return element.children.flatMap { collectStringLiterals(it) }
    }

    // ------------------------------------------------------------------
    // 实体类名提取
    // ------------------------------------------------------------------

    /**
     * 从 HQL 字符串的 FROM 子句提取实体类名。
     *
     * 取第一个匹配 `FROM EntityName` 的驼峰标识符作为实体类名。
     *
     * @param hql HQL 字符串
     * @return 实体类名；未匹配到时返回 null
     */
    private fun extractEntityClassNameFromHql(hql: String): String? {
        // 优先匹配 FROM 子句（SELECT）
        FROM_ENTITY_PATTERN.find(hql)?.let { return it.groupValues[1] }
        // 适配 UPDATE 语句：UPDATE EntityName SET ...
        UPDATE_ENTITY_PATTERN.find(hql)?.let { return it.groupValues[1] }
        // 适配 DELETE 语句：DELETE FROM EntityName ...
        DELETE_ENTITY_PATTERN.find(hql)?.let { return it.groupValues[1] }
        return null
    }

    // ------------------------------------------------------------------
    // 参数提取与解析
    // ------------------------------------------------------------------

    /**
     * 从 HQL 字符串提取命名参数名列表。
     *
     * 匹配 `:paramName` 形式的命名参数（排除 `::` 运算符）。
     *
     * @param hql HQL 字符串
     * @return 参数名列表（去重，保持出现顺序）
     */
    private fun extractNamedParams(hql: String): List<String> {
        return NAMED_PARAM_PATTERN.findAll(hql)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }

    /**
     * 检测方法调用的参数传递方式。
     *
     * 分析 HQL 参数之后的额外参数列表，判断组织形式：
     *  - 字符串与值交替出现 → NAME_VALUE_PAIRS
     *  - 无额外参数 → NONE
     *  - 仅非字符串值（可能为 Map 参数）→ MAP（启发判断）
     *  - 其他 → POSITIONAL
     *
     * @param call 目标方法调用
     * @return 参数传递方式
     */
    private fun detectParamSource(call: PsiMethodCallExpression): ParamSource {
        val args = call.argumentList.expressions
        if (args.size <= 1) return ParamSource.NONE

        val extraParams = args.drop(1)
        return when {
            extraParams.isEmpty() -> ParamSource.NONE
            isNameValuePairs(extraParams) -> ParamSource.NAME_VALUE_PAIRS
            extraParams.size == 1 && extraParams[0] !is PsiLiteralExpression -> ParamSource.MAP
            else -> ParamSource.POSITIONAL
        }
    }

    /**
     * 判断额外参数列表是否为 name-value 交替形式。
     *
     * 即：("name1", v1, "name2", v2) 模式——奇数索引参数为字符串字面量。
     *
     * @param extraParams HQL 参数之后的额外参数列表
     * @return 是否为 name-value 交替形式
     */
    private fun isNameValuePairs(extraParams: List<PsiExpression>): Boolean {
        if (extraParams.size < 2) return false
        // 检查偶数索引参数（0, 2, 4...）是否为字符串字面量（参数名）
        for (i in extraParams.indices step 2) {
            val arg = extraParams[i]
            if (arg !is PsiLiteralExpression) return false
            val value = arg.value
            if (value !is String) return false
        }
        return true
    }

    /**
     * 解析 HQL 参数列表。
     *
     * 以 HQL 中提取的 :paramName 为主键，从方法参数中按名匹配值：
     *  - NAME_VALUE_PAIRS：从交替参数中提取 {paramName: valueExpr}
     *  - MAP：标注参数名，值表达式引用 Map 变量
     *  - POSITIONAL/NONE：仅标注参数名，无值信息
     *
     * @param call 目标方法调用
     * @param hqlParamNames HQL 中提取的参数名列表
     * @return 解析后的 HqlParameter 列表
     */
    private fun resolveParameters(call: PsiMethodCallExpression, hqlParamNames: List<String>): List<HqlParameter> {
        if (hqlParamNames.isEmpty()) return emptyList()

        val paramSource = detectParamSource(call)
        return when (paramSource) {
            ParamSource.NAME_VALUE_PAIRS -> {
                val nameValueMap = extractNameValueMap(call)
                hqlParamNames.map { paramName ->
                    val valueExpr = nameValueMap[paramName] ?: ""
                    HqlParameter(paramName = paramName, valueExpr = valueExpr, isPositional = false)
                }
            }
            ParamSource.MAP -> {
                // Map 传参：值表达式引用 Map 变量（第二个参数）
                val mapArg = call.argumentList.expressions.getOrNull(1)
                val mapVarName = when (mapArg) {
                    is PsiReferenceExpression -> mapArg.referenceName ?: mapArg.text
                    else -> mapArg?.text ?: "paramMap"
                }
                hqlParamNames.map { paramName ->
                    HqlParameter(paramName = paramName, valueExpr = "$mapVarName.get(\"$paramName\")", isPositional = false)
                }
            }
            ParamSource.POSITIONAL -> {
                // 位置参数：按索引顺序匹配
                val extraParams = call.argumentList.expressions.drop(1)
                hqlParamNames.mapIndexed { index, paramName ->
                    val valueExpr = extraParams.getOrNull(index)?.text ?: ""
                    HqlParameter(paramName = paramName, valueExpr = valueExpr, isPositional = true)
                }
            }
            ParamSource.NONE -> {
                hqlParamNames.map { paramName ->
                    HqlParameter(paramName = paramName, valueExpr = "", isPositional = false)
                }
            }
        }
    }

    /**
     * 从 name-value 交替参数中提取参数名到值表达式的映射。
     *
     * 格式：("name1", v1, "name2", v2) → {name1: v1.text, name2: v2.text}
     *
     * @param call 目标方法调用
     * @return 参数名到值表达式文本的映射
     */
    private fun extractNameValueMap(call: PsiMethodCallExpression): Map<String, String> {
        val args = call.argumentList.expressions
        if (args.size < 2) return emptyMap()

        val result = mutableMapOf<String, String>()
        val extraParams = args.drop(1)

        for (i in extraParams.indices step 2) {
            val nameArg = extraParams.getOrNull(i) as? PsiLiteralExpression ?: continue
            val paramName = nameArg.value as? String ?: continue
            val valueExpr = extraParams.getOrNull(i + 1)?.text ?: ""
            result[paramName] = valueExpr
        }

        return result
    }
}
