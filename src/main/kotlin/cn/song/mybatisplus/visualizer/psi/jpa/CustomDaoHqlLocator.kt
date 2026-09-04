package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.psi.PreviewReceiverResolver
import com.intellij.psi.PsiBinaryExpression
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
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
 *  - NAME_VALUE_ARRAYS：new String[]{"name1","name2"} + new Object[]{v1, v2} 双数组传递（老式 DAO）
 *  - MAP：单 Map 参数包含所有键值
 *  - POSITIONAL：仅传值，按位置顺序
 *  - NONE：无额外参数
 */
enum class ParamSource { NAME_VALUE_PAIRS, NAME_VALUE_ARRAYS, MAP, POSITIONAL, NONE }

/**
 * 自定义 DAO HQL 定位器（F-JPA-02 / Wave2 扩展）。
 *
 * 从光标位置识别自定义 DAO 方法中的 HQL 字符串调用，弥补 [HqlQueryLocator] 仅识别标准 Hibernate Session.createQuery() 的不足。
 *
 * 识别模式：
 *  - `commonDao.findByQueryUniqueResult("select ... from Entity where ... :paramList", "paramList", value)`
 *  - `baseDao.executeHql("update Entity set ... where ...", paramMap)`
 *  - `xxxDao.findByQuery("from Entity where field = :id", "id", idValue)`
 *  - `getHibernateTemplate().find("from Entity ...")` / `.findByNamedParam(...)` / `.findByValueBean(...)`（T-GutterDedup）
 *
 * 判定逻辑（T-GutterDedup：内容启发 + 按接收者归属分派，杜绝通用方法名跨家族重复图标）：
 *  1. **内容启发**：首参字符串匹配 HQL 语法模式（SELECT...FROM / UPDATE...SET / DELETE...FROM / FROM Entity）；
 *  2. 按 [PreviewReceiverResolver.classify] 接收者家族分派：
 *     - JDBC_TEMPLATE / JPA_SESSION → 交给各自 provider（本定位器不认领）；
 *     - HIBERNATE_TEMPLATE → 仅 HQL 字符串首参的 `find`/`findByNamedParam`/`findByValueBean`/`iterate`；
 *     - CUSTOM_DAO / UNKNOWN → **qualifier 启发**（*Dao / *Mapper / *Repository / *BaseDao 等）OR
 *       **方法名启发**（Query / Hql / Jpql / findByQuery / findBySql 等具体关键字；通用名 execute 须 DAO qualifier）。
 *
 * 定位策略：
 *  1. 从光标向上找到 PsiMethod；
 *  2. 扫描方法体内全部 PsiMethodCallExpression；
 *  3. 对每个方法调用依次应用判定逻辑；
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
     * 查询方法名关键字集合（T-GutterDedup：仅"具体查询"关键字）。
     *
     * 用于方法名启发：方法名包含任一关键字即视为潜在查询方法。通用名 `execute` 已移出——
     * 它被 JdbcTemplate / HibernateTemplate 复用，单靠方法名会跨家族误标；`execute` 现在
     * 必须由 DAO qualifier（[matchesDaoQualifier]）或 HIBERNATE_TEMPLATE 接收者门控放行。
     */
    private val QUERY_METHOD_KEYWORDS = setOf(
        "Query", "Hql", "Jpql", "findByQuery", "findBySql"
    )

    /**
     * HibernateTemplate 的 HQL 字符串查询方法名（T-GutterDedup：补齐缺失图标）。
     *
     * 接收者为 [PreviewReceiverResolver.Family.HIBERNATE_TEMPLATE] 且方法名在此集合、首参为字符串时，
     * 由本定位器认领（否则 `getHibernateTemplate().find/findByNamedParam/findByValueBean` 无预览图标）。
     */
    private val HIBERNATE_HQL_METHODS = setOf("find", "findByNamedParam", "findByValueBean", "iterate")

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
     * 判定逻辑（T-GutterDedup：按接收者归属消歧，杜绝通用方法名跨家族重复图标）：
     *  1. 内容启发（首参须匹配 HQL 语法模式）；
     *  2. 按接收者 [PreviewReceiverResolver.classify] 家族分派：
     *     - JDBC_TEMPLATE / JPA_SESSION → 交给各自 provider（不认领）；
     *     - HIBERNATE_TEMPLATE → 仅 HQL 首参的 find/findByNamedParam/findByValueBean/iterate；
     *     - CUSTOM_DAO / UNKNOWN → DAO qualifier 或具体查询方法名（`execute` 等通用名须 DAO qualifier）。
     *
     * @param call 目标方法调用
     * @return 是否匹配自定义 DAO HQL 调用
     */
    private fun isCustomDaoHqlCall(call: PsiMethodCallExpression): Boolean {
        return when (PreviewReceiverResolver.classify(call)) {
            // JDBC 模板（queryForList/update/execute 等）与 JPA 原生（Session/EntityManager）交给各自 provider
            PreviewReceiverResolver.Family.JDBC_TEMPLATE,
            PreviewReceiverResolver.Family.JPA_SESSION -> false
            // HibernateTemplate：方法名本身即 HQL 语义（find/findByNamedParam/findByValueBean/iterate 首参恒为 HQL 字符串），
            // 故不走内容启发（HQL 可能是不可静态解析的变量/方法参数，内容看不到），仅要求首参为字符串形态
            // （`execute(callback)` 非字符串实参不认领）。
            PreviewReceiverResolver.Family.HIBERNATE_TEMPLATE ->
                HIBERNATE_HQL_METHODS.contains(call.methodExpression.referenceName) &&
                    PreviewReceiverResolver.isStringLikeFirstArg(call)
            // 自定义 DAO / 无法判定：内容启发 + (DAO qualifier OR 具体查询方法名)
            else -> {
                if (!matchesHqlContent(call)) return false
                matchesDaoQualifier(call) || matchesQueryMethodName(call)
            }
        }
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
            is PsiBinaryExpression, is com.intellij.psi.PsiPolyadicExpression -> {
                // 字符串拼接："..." + "..."（含链式 `+`，如 `"a" + "b" + "c"`——链式在 PSI 中为 PsiPolyadicExpression，
                // 与 PsiBinaryExpression 互不继承）→ 递归收集所有 PsiLiteralExpression 子节点
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
     * 与 [extractHqlString] 共用同一提取逻辑（单一来源）：字面量直接取值；拼接表达式拼接
     * 所有字面量部分；变量引用递归解析初始化式（含拼接初始化式）。此前的独立实现只处理
     * 字面量初始化式，拼接变量回退为变量名导致内容启发误判（2026-09-03 用户反馈
     * `String hql = "select" + " ..."` 后传入自定义 DAO 无预览图标，根因即两套逻辑分叉）。
     *
     * @param expression 参数表达式
     * @return 提取的文本内容；无法提取时返回 null
     */
    private fun extractArgumentText(expression: PsiExpression): String? {
        return extractHqlString(expression).first
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
            isNameValueArrays(extraParams) -> ParamSource.NAME_VALUE_ARRAYS
            extraParams.size == 1 && extraParams[0] !is PsiLiteralExpression -> ParamSource.MAP
            else -> ParamSource.POSITIONAL
        }
    }

    /**
     * 判断额外参数是否为「名字数组 + 值数组」双数组形式（老式 DAO 常用）。
     *
     * 即 `findByQueryUniqueResult(hql, new String[]{"name1","name2"}, new Object[]{v1, v2})`：
     * 第一个额外参数为字符串数组创建表达式，数组元素全部是字符串字面量（参数名）。
     *
     * @param extraParams HQL 参数之后的额外参数列表
     * @return 是否为名字数组+值数组形式
     */
    private fun isNameValueArrays(extraParams: List<PsiExpression>): Boolean {
        if (extraParams.size < 2) return false
        val namesArg = extraParams[0] as? PsiNewExpression ?: return false
        val elements = namesArg.arrayInitializer?.initializers ?: return false
        if (elements.isEmpty()) return false
        // 名字数组元素必须全部是字符串字面量（参数名），排除 Object[] 等值数组误判
        return elements.all { it is PsiLiteralExpression && (it.value as? String) != null }
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
     *  - NAME_VALUE_ARRAYS：名字数组 + 值数组，按名匹配、按索引取值
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
            ParamSource.NAME_VALUE_ARRAYS -> {
                // 名字数组 + 值数组（老式 DAO）：如 findByQueryUniqueResult(hql,
                // new String[]{"warehouseId","startTime"}, new Object[]{warehouseId, startTime})
                val extraParams = call.argumentList.expressions.drop(1)
                val names = (extraParams.getOrNull(0) as? PsiNewExpression)
                    ?.arrayInitializer?.initializers
                    ?.mapNotNull { (it as? PsiLiteralExpression)?.value as? String }
                    ?: emptyList()
                val valueTexts = (extraParams.getOrNull(1) as? PsiNewExpression)
                    ?.arrayInitializer?.initializers
                    ?.map { it.text }
                    ?: emptyList()
                hqlParamNames.map { paramName ->
                    val index = names.indexOf(paramName)
                    HqlParameter(
                        paramName = paramName,
                        valueExpr = if (index >= 0) valueTexts.getOrNull(index) ?: "" else "",
                        isPositional = false,
                    )
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
