package cn.song.mybatisplus.visualizer.converter.jpa

/**
 * Spring Data JPA 方法名推导解析器（PartTree 解析器）。
 *
 * 解析 Spring Data JPA Repository 方法名，输出结构化的 [PartTree] 模型。
 * 这是 Spring Data JPA 的核心查询推导机制。
 *
 * 解析规则：
 * 1. 方法名按第一个 `By` 分割为 Subject 和 Predicate 两部分
 * 2. Subject 解析操作类型（find/count/exists/delete）、Distinct、Top N / First N
 * 3. Predicate 按 Or/And 拆分，每个 Part 按 camelCase 边界切分并匹配条件关键字
 * 4. 支持 IgnoreCase / AllIgnoreCase、OrderBy 子句解析
 *
 * 参考：Spring Data JPA PartTree 实现，条件关键字优先级与 Spring Data 保持一致。
 */

/**
 * PartTree 解析产物。
 *
 * @param subject   Subject 段（操作类型、Distinct、限制条数）
 * @param predicates Predicate 段（Or/And 连接的条件列表）
 * @param orderBy   OrderBy 排序子句
 */
data class PartTree(
    val subject: Subject,
    val predicates: List<PredicatePart>,
    val orderBy: List<OrderByPart>,
)

/**
 * Subject 段：方法名中 `By` 之前的部分。
 *
 * @param operation 操作类型（FIND / COUNT / EXISTS / DELETE）
 * @param distinct  是否去重
 * @param limit     限制条数（Top N / First N），null 表示不限制
 */
data class Subject(
    val operation: Operation,
    val distinct: Boolean,
    val limit: Int?,
)

/**
 * Predicate 段中的单个 OrPart。
 *
 * 多个 PredicatePart 之间为 OR 连接，内部 conditions 之间为 AND 连接。
 * [connector] 表示该 Part 与前一个 Part 的连接符（第一个恒为 AND）。
 *
 * @param connector  与前一个 OrPart 的连接符（第一个恒为 AND）
 * @param conditions 该 OrPart 内的 AND 条件列表
 */
data class PredicatePart(
    val connector: Connector,
    val conditions: List<Condition>,
)

/**
 * 单个条件。
 *
 * @param propertyPath 属性路径（如 "lastName" 或 "address.city"）
 * @param type         条件类型（27 种）
 * @param ignoreCase   是否忽略大小写
 */
data class Condition(
    val propertyPath: String,
    val type: PartType,
    val ignoreCase: Boolean,
)

/**
 * OrderBy 排序项。
 *
 * @param propertyPath 排序属性路径
 * @param direction    排序方向（ASC / DESC）
 */
data class OrderByPart(
    val propertyPath: String,
    val direction: SortDirection,
)

/**
 * 操作类型枚举。
 */
enum class Operation { FIND, COUNT, EXISTS, DELETE }

/**
 * 条件连接符。
 */
enum class Connector { AND, OR }

/**
 * 排序方向。
 */
enum class SortDirection { ASC, DESC }

/**
 * 条件类型枚举（27 种），按匹配优先级排序。
 *
 * 优先级规则：IS_NULL/IS_NOT_NULL 先匹配，SIMPLE_PROPERTY 兜底。
 */
enum class PartType {
    /** BETWEEN ? AND ? */
    BETWEEN,

    /** IS NOT NULL */
    IS_NOT_NULL,

    /** IS NULL */
    IS_NULL,

    /** < ? */
    LESS_THAN,

    /** <= ? */
    LESS_THAN_EQUAL,

    /** > ? */
    GREATER_THAN,

    /** >= ? */
    GREATER_THAN_EQUAL,

    /** < ?（时间/日期语义） */
    BEFORE,

    /** > ?（时间/日期语义） */
    AFTER,

    /** NOT LIKE ? */
    NOT_LIKE,

    /** LIKE ? */
    LIKE,

    /** LIKE ?% */
    STARTING_WITH,

    /** LIKE %? */
    ENDING_WITH,

    /** LIKE %?% */
    CONTAINING,

    /** NOT LIKE %?% */
    NOT_CONTAINING,

    /** IS NOT EMPTY */
    IS_NOT_EMPTY,

    /** IS EMPTY */
    IS_EMPTY,

    /** NOT IN (?) */
    NOT_IN,

    /** IN (?) */
    IN,

    /** 空间近邻（暂不支持，降级为 SIMPLE_PROPERTY） */
    NEAR,

    /** 空间范围内（暂不支持，降级为 SIMPLE_PROPERTY） */
    WITHIN,

    /** REGEXP */
    REGEX,

    /** EXISTS */
    EXISTS,

    /** = true */
    TRUE,

    /** = false */
    FALSE,

    /** != ? */
    NEGATING_SIMPLE_PROPERTY,

    /** = ?（默认/兜底） */
    SIMPLE_PROPERTY,
}

/**
 * PartTree 解析器主类。
 *
 * 提供 [parse] 入口，将 Spring Data JPA 方法名解析为 [PartTree] 模型。
 */
object PartTreeParser {

    // Subject 关键字集合
    private val QUERY_KEYWORDS = setOf("find", "read", "get", "query", "search", "stream")
    private val COUNT_KEYWORD = "count"
    private val EXISTS_KEYWORD = "exists"
    private val DELETE_KEYWORDS = setOf("delete", "remove")
    private val DISTINCT_KEYWORD = "Distinct"

    // Subject 限制关键字
    private val TOP_KEYWORD = "Top"
    private val FIRST_KEYWORD = "First"

    // 条件关键字映射（按匹配优先级排序，高优先级在前）
    // 每个条目：(keyword, requiresParameter, partType)
    private val CONDITION_KEYWORDS: List<KeywordRule> = listOf(
        KeywordRule("IsBetween", true, PartType.BETWEEN),
        KeywordRule("Between", true, PartType.BETWEEN),

        KeywordRule("IsNotNull", false, PartType.IS_NOT_NULL),
        KeywordRule("NotNull", false, PartType.IS_NOT_NULL),

        KeywordRule("IsNull", false, PartType.IS_NULL),
        KeywordRule("Null", false, PartType.IS_NULL),

        KeywordRule("IsLessThanEqual", true, PartType.LESS_THAN_EQUAL),
        KeywordRule("LessThanEqual", true, PartType.LESS_THAN_EQUAL),

        KeywordRule("IsLessThan", true, PartType.LESS_THAN),
        KeywordRule("LessThan", true, PartType.LESS_THAN),

        KeywordRule("IsGreaterThanEqual", true, PartType.GREATER_THAN_EQUAL),
        KeywordRule("GreaterThanEqual", true, PartType.GREATER_THAN_EQUAL),

        KeywordRule("IsGreaterThan", true, PartType.GREATER_THAN),
        KeywordRule("GreaterThan", true, PartType.GREATER_THAN),

        KeywordRule("IsBefore", true, PartType.BEFORE),
        KeywordRule("Before", true, PartType.BEFORE),

        KeywordRule("IsAfter", true, PartType.AFTER),
        KeywordRule("After", true, PartType.AFTER),

        KeywordRule("IsNotLike", true, PartType.NOT_LIKE),
        KeywordRule("NotLike", true, PartType.NOT_LIKE),

        KeywordRule("IsLike", true, PartType.LIKE),
        KeywordRule("Like", true, PartType.LIKE),

        KeywordRule("IsStartingWith", true, PartType.STARTING_WITH),
        KeywordRule("StartingWith", true, PartType.STARTING_WITH),
        KeywordRule("StartsWith", true, PartType.STARTING_WITH),

        KeywordRule("IsEndingWith", true, PartType.ENDING_WITH),
        KeywordRule("EndingWith", true, PartType.ENDING_WITH),
        KeywordRule("EndsWith", true, PartType.ENDING_WITH),

        KeywordRule("IsContaining", true, PartType.CONTAINING),
        KeywordRule("Containing", true, PartType.CONTAINING),
        KeywordRule("Contains", true, PartType.CONTAINING),

        KeywordRule("IsNotContaining", true, PartType.NOT_CONTAINING),
        KeywordRule("NotContaining", true, PartType.NOT_CONTAINING),
        KeywordRule("NotContains", true, PartType.NOT_CONTAINING),

        KeywordRule("IsNotEmpty", false, PartType.IS_NOT_EMPTY),
        KeywordRule("NotEmpty", false, PartType.IS_NOT_EMPTY),

        KeywordRule("IsEmpty", false, PartType.IS_EMPTY),
        KeywordRule("Empty", false, PartType.IS_EMPTY),

        KeywordRule("IsNotIn", true, PartType.NOT_IN),
        KeywordRule("NotIn", true, PartType.NOT_IN),

        KeywordRule("IsIn", true, PartType.IN),
        KeywordRule("In", true, PartType.IN),

        KeywordRule("IsNear", true, PartType.NEAR),
        KeywordRule("Near", true, PartType.NEAR),

        KeywordRule("IsWithin", true, PartType.WITHIN),
        KeywordRule("Within", true, PartType.WITHIN),

        KeywordRule("MatchesRegex", true, PartType.REGEX),
        KeywordRule("Regex", true, PartType.REGEX),
        KeywordRule("Matches", true, PartType.REGEX),

        KeywordRule("Exists", false, PartType.EXISTS),

        KeywordRule("IsTrue", false, PartType.TRUE),
        KeywordRule("True", false, PartType.TRUE),

        KeywordRule("IsFalse", false, PartType.FALSE),
        KeywordRule("False", false, PartType.FALSE),

        KeywordRule("IsNot", true, PartType.NEGATING_SIMPLE_PROPERTY),
        KeywordRule("Not", true, PartType.NEGATING_SIMPLE_PROPERTY),

        // SIMPLE_PROPERTY 兜底："Is" 或无前缀
        KeywordRule("Is", true, PartType.SIMPLE_PROPERTY),
    )

    // OrderBy 关键字
    private val ORDER_BY_KEYWORD = "OrderBy"
    private val ASC_KEYWORD = "Asc"
    private val DESC_KEYWORD = "Desc"
    private val ALL_IGNORE_CASE_KEYWORD = "AllIgnoreCase"
    private val IGNORE_CASE_KEYWORD = "IgnoreCase"

    /**
     * 解析方法名为 PartTree 模型。
     *
     * @param methodName Spring Data JPA Repository 方法名
     * @return 解析产物
     * @throws IllegalArgumentException 方法名格式非法（无 By 关键字且非查询全部）
     */
    fun parse(methodName: String): PartTree {
        // 按第一个 "By" 分割
        val byIndex = indexOfBy(methodName)
        if (byIndex < 0) {
            // 无 By 段：纯 Subject（如 findAll → find all）
            val subject = parseSubject(methodName)
            return PartTree(subject, emptyList(), emptyList())
        }

        val subjectPart = methodName.substring(0, byIndex)
        val predicatePart = methodName.substring(byIndex + 2) // skip "By"

        val subject = parseSubject(subjectPart)

        // 先剥离尾部 IgnoreCase（可能在 OrderBy 之后，如 "LastNameOrderByFirstNameAscIgnoreCase"）
        val (predicateWithoutIgnoreCase, globalIgnoreCase) = extractIgnoreCase(predicatePart)

        // 从 Predicate 尾部剥离 OrderBy 子句
        val (predicateBody, orderByParts) = extractOrderBy(predicateWithoutIgnoreCase)

        // 解析 Or/And 条件
        val predicates = parsePredicate(predicateBody, globalIgnoreCase)

        return PartTree(subject, predicates, orderByParts)
    }

    /**
     * 查找第一个真正的 "By" 分隔符位置（非 OrderBy）。
     *
     * 遍历 "By" 出现位置，跳过 OrderBy 情况。
     */
    private fun indexOfBy(methodName: String): Int {
        var index = 0
        while (index < methodName.length - 1) {
            val found = methodName.indexOf("By", index)
            if (found < 0) return -1
            // 检查是否是 OrderBy：By 后面紧跟 OrderBy 关键字
            if (isOrderByAt(methodName, found)) {
                index = found + 2
                continue
            }
            return found
        }
        return -1
    }

    /**
     * 检查 methodName 中 position 位置的 "By" 是否是 OrderBy 的一部分。
     */
    private fun isOrderByAt(methodName: String, position: Int): Boolean {
        val afterBy = position + 2
        return methodName.startsWith("OrderBy", afterBy)
    }

    /**
     * 解析 Subject 段。
     *
     * 解析顺序（Spring Data JPA 语法）：
     * 1. 操作关键字（find/count/exists/delete）
     * 2. Distinct（可选）
     * 3. Top N / First N（可选）
     *
     * 示例：
     * - "find" → FIND
     * - "findDistinct" → FIND + distinct
     * - "findTop10" → FIND + limit 10
     * - "countDistinct" → COUNT + distinct
     * - "delete" → DELETE
     */
    private fun parseSubject(subjectPart: String): Subject {
        if (subjectPart.isEmpty()) {
            return Subject(Operation.FIND, false, null)
        }

        var remaining = subjectPart

        // 步骤 1：提取操作关键字（前缀匹配，按长度降序避免 find 匹配到 f）
        val operation: Operation = when {
            remaining in DELETE_KEYWORDS -> { remaining = ""; Operation.DELETE }
            remaining == COUNT_KEYWORD -> { remaining = ""; Operation.COUNT }
            remaining == EXISTS_KEYWORD -> { remaining = ""; Operation.EXISTS }
            remaining in QUERY_KEYWORDS -> { remaining = ""; Operation.FIND }
            remaining.startsWith("delete") -> {
                remaining = remaining.substring("delete".length); Operation.DELETE
            }
            remaining.startsWith("remove") -> {
                remaining = remaining.substring("remove".length); Operation.DELETE
            }
            remaining.startsWith("count") -> {
                remaining = remaining.substring("count".length); Operation.COUNT
            }
            remaining.startsWith("exists") -> {
                remaining = remaining.substring("exists".length); Operation.EXISTS
            }
            // find/read/get/query/search/stream 可能后跟 Distinct/Top/First
            else -> {
                for (kw in QUERY_KEYWORDS) {
                    if (remaining.startsWith(kw)) {
                        remaining = remaining.substring(kw.length)
                        return parseSubjectModifiers(remaining, Operation.FIND)
                    }
                }
                Operation.FIND
            }
        }

        // 对于 DELETE/COUNT/EXISTS，继续解析 Distinct/Top/First 修饰符
        return parseSubjectModifiers(remaining, operation)
    }

    /**
     * 在操作关键字之后解析修饰符（Distinct、Top N / First N）。
     */
    private fun parseSubjectModifiers(remaining: String, operation: Operation): Subject {
        var rem = remaining
        var distinct = false
        var limit: Int? = null

        // 提取 Distinct
        if (rem.startsWith(DISTINCT_KEYWORD)) {
            distinct = true
            rem = rem.substring(DISTINCT_KEYWORD.length)
        }

        // 提取 Top N / First N
        if (rem.startsWith(TOP_KEYWORD)) {
            rem = rem.substring(TOP_KEYWORD.length)
            val numberResult = parseLeadingNumber(rem)
            if (numberResult != null) {
                limit = numberResult.first
                rem = numberResult.second
            }
        } else if (rem.startsWith(FIRST_KEYWORD)) {
            rem = rem.substring(FIRST_KEYWORD.length)
            val numberResult = parseLeadingNumber(rem)
            if (numberResult != null) {
                limit = numberResult.first
                rem = numberResult.second
            } else {
                limit = 1 // First 无数字默认 1
            }
        }

        return Subject(operation, distinct, limit)
    }

    /**
     * 从字符串开头解析数字。
     *
     * @return Pair(数字, 剩余字符串) 或 null（开头非数字）
     */
    private fun parseLeadingNumber(s: String): Pair<Int, String>? {
        if (s.isEmpty() || !s[0].isDigit()) return null
        var endIndex = 0
        while (endIndex < s.length && s[endIndex].isDigit()) {
            endIndex++
        }
        val number = s.substring(0, endIndex).toInt()
        return Pair(number, s.substring(endIndex))
    }

    /**
     * 从 Predicate 尾部提取 OrderBy 子句。
     *
     * OrderBy 位于最尾部，以 "OrderBy" 开头，后跟 "PropAsc"/"PropDesc" 或 "Prop AndProp"/"Prop OrProp"。
     *
     * @return Pair(剥离 OrderBy 后的谓词体, OrderBy 排序项列表)
     */
    private fun extractOrderBy(predicatePart: String): Pair<String, List<OrderByPart>> {
        val orderByIndex = findOrderByIndex(predicatePart)
        if (orderByIndex < 0) {
            return Pair(predicatePart, emptyList())
        }

        val predicateBody = predicatePart.substring(0, orderByIndex)
        val orderByBody = predicatePart.substring(orderByIndex + ORDER_BY_KEYWORD.length)

        val orderByParts = parseOrderByBody(orderByBody)
        return Pair(predicateBody, orderByParts)
    }

    /**
     * 查找 OrderBy 子句起始位置。
     *
     * 从右向左扫描，找到最后一个独立的 OrderBy 关键字。
     */
    private fun findOrderByIndex(s: String): Int {
        // OrderBy 必须在末尾区域，从末尾开始查找
        var searchStart = s.length - 1
        while (searchStart >= 0) {
            val idx = s.lastIndexOf(ORDER_BY_KEYWORD, searchStart)
            if (idx < 0) return -1
            // 确认是独立的 OrderBy 关键字（后面不跟条件关键字或连接符混淆）
            val afterOrderBy = idx + ORDER_BY_KEYWORD.length
            if (afterOrderBy <= s.length) {
                // OrderBy 后面应有属性名（大写字母开头）
                if (afterOrderBy == s.length || s[afterOrderBy].isUpperCase()) {
                    return idx
                }
            }
            searchStart = idx - 1
        }
        return -1
    }

    /**
     * 解析 OrderBy 子句体。
     *
     * 支持格式：
     * - "LastNameAsc" → lastName ASC
     * - "LastNameDesc" → lastName DESC
     * - "LastName" → lastName ASC（默认）
     * - "LastNameAndFirstNameAsc" → lastName ASC, firstName ASC（混合）
     */
    private fun parseOrderByBody(orderByBody: String): List<OrderByPart> {
        if (orderByBody.isEmpty()) return emptyList()

        val parts = splitOrderByParts(orderByBody)
        return parts.map { part ->
            val direction = when {
                part.endsWith(DESC_KEYWORD) -> SortDirection.DESC
                part.endsWith(ASC_KEYWORD) -> SortDirection.ASC
                else -> SortDirection.ASC
            }
            val propertyName = when {
                part.endsWith(DESC_KEYWORD) -> part.dropLast(DESC_KEYWORD.length)
                part.endsWith(ASC_KEYWORD) -> part.dropLast(ASC_KEYWORD.length)
                else -> part
            }
            OrderByPart(decamelize(propertyName), direction)
        }
    }

    /**
     * 将 OrderBy 子句拆分为独立的属性项。
     *
     * 按 "And"/"Or" 拆分，同时考虑 Asc/Desc 后缀。
     */
    private fun splitOrderByParts(body: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var i = 0

        while (i < body.length) {
            // 检查 "And" 分隔符
            if (i > 0 && body.startsWith("And", i) && isCamelCaseBoundary(body, i)) {
                result.add(current.toString())
                current = StringBuilder()
                i += 3 // skip "And"
                continue
            }
            // 检查 "Or" 分隔符
            if (i > 0 && body.startsWith("Or", i) && isCamelCaseBoundary(body, i)) {
                result.add(current.toString())
                current = StringBuilder()
                i += 2 // skip "Or"
                continue
            }
            current.append(body[i])
            i++
        }
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        return result
    }

    /**
     * 检查 index 位置是否是 camelCase 边界（即该位置前是小写/数字，该位置是大写）。
     */
    private fun isCamelCaseBoundary(s: String, index: Int): Boolean {
        if (index <= 0 || index >= s.length) return false
        return s[index].isUpperCase() && (s[index - 1].isLowerCase() || s[index - 1].isDigit())
    }

    /**
     * 提取并剥离 IgnoreCase / AllIgnoreCase 后缀。
     *
     * @return Pair(剥离后的谓词体, 全局忽略大小写标志)
     */
    private fun extractIgnoreCase(predicateBody: String): Pair<String, Boolean> {
        if (predicateBody.endsWith(ALL_IGNORE_CASE_KEYWORD)) {
            return Pair(
                predicateBody.dropLast(ALL_IGNORE_CASE_KEYWORD.length),
                true
            )
        }
        if (predicateBody.endsWith(IGNORE_CASE_KEYWORD)) {
            return Pair(
                predicateBody.dropLast(IGNORE_CASE_KEYWORD.length),
                true
            )
        }
        return Pair(predicateBody, false)
    }

    /**
     * 解析 Predicate 主体为 Or/And 条件列表。
     *
     * @param predicateBody  OrderBy 和 IgnoreCase 剥离后的谓词体
     * @param globalIgnoreCase 全局忽略大小写标志
     */
    private fun parsePredicate(predicateBody: String, globalIgnoreCase: Boolean): List<PredicatePart> {
        if (predicateBody.isEmpty()) return emptyList()

        // 按 Or 拆分
        val orParts = splitByKeyword(predicateBody, "Or")

        return orParts.mapIndexed { index, orPart ->
            val connector = if (index == 0) Connector.AND else Connector.OR
            // 按 And 拆分
            val andParts = splitByKeyword(orPart, "And")
            val conditions = andParts.map { andPart ->
                parseCondition(andPart, globalIgnoreCase)
            }
            PredicatePart(connector, conditions)
        }
    }

    /**
     * 按关键字拆分（Or/And），保留 camelCase 边界语义。
     */
    private fun splitByKeyword(s: String, keyword: String): List<String> {
        if (s.isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        var current = StringBuilder()
        var i = 0

        while (i < s.length) {
            if (i > 0 && s.startsWith(keyword, i) && isCamelCaseBoundary(s, i)) {
                // 确认是有效的拆分点：关键字后面应是大写字母（新属性开头）或结束
                val afterKeyword = i + keyword.length
                if (afterKeyword >= s.length || s[afterKeyword].isUpperCase()) {
                    result.add(current.toString())
                    current = StringBuilder()
                    i = afterKeyword
                    continue
                }
            }
            current.append(s[i])
            i++
        }
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        return result
    }

    /**
     * 解析单个条件 Part。
     *
     * 按 camelCase 边界切分，从尾部匹配条件关键字。
     */
    private fun parseCondition(part: String, globalIgnoreCase: Boolean): Condition {
        // 查找最佳匹配的条件关键字
        val matchResult = matchConditionKeyword(part)

        if (matchResult != null) {
            val (propertyPart, partType) = matchResult
            return Condition(
                propertyPath = decamelize(propertyPart),
                type = partType,
                ignoreCase = globalIgnoreCase,
            )
        }

        // 兜底：SIMPLE_PROPERTY
        return Condition(
            propertyPath = decamelize(part),
            type = PartType.SIMPLE_PROPERTY,
            ignoreCase = globalIgnoreCase,
        )
    }

    /**
     * 在 Part 中匹配条件关键字。
     *
     * 策略：按 camelCase 边界切分 token，从尾部匹配最长的关键字组合。
     * 例如 "NameNotContaining" → tokens: [Name, Not, Containing]
     * 优先匹配 "NotContaining" (2 tokens) 而非 "Containing" (1 token)
     *
     * @return Pair(属性部分, PartType) 或 null（未匹配到关键字）
     */
    private fun matchConditionKeyword(part: String): Pair<String, PartType>? {
        val tokens = splitCamelCase(part)
        if (tokens.isEmpty()) return null

        // 收集所有匹配的规则，按关键字 token 数降序排列（优先最长匹配）
        val matches = mutableListOf<Pair<Int, KeywordRule>>() // (startIndex, rule)

        for (rule in CONDITION_KEYWORDS) {
            val keywordTokens = splitCamelCase(rule.keyword)
            if (keywordTokens.size > tokens.size) continue

            // 检查尾部 tokens 是否匹配关键字
            val startIndex = tokens.size - keywordTokens.size
            var matched = true
            for (j in keywordTokens.indices) {
                if (!tokens[startIndex + j].equals(keywordTokens[j], ignoreCase = true)) {
                    matched = false
                    break
                }
            }

            if (matched) {
                val propertyTokens = tokens.subList(0, startIndex)
                if (propertyTokens.isEmpty() && rule.requiresParameter) {
                    // 需要参数但无属性名，不能是这种类型
                    continue
                }
                matches.add(Pair(startIndex, rule))
            }
        }

        if (matches.isEmpty()) return null

        // 选择关键字 token 数最多的匹配（最长匹配优先）
        val bestMatch = matches.maxByOrNull { (_, rule) ->
            splitCamelCase(rule.keyword).size
        }!!

        val (startIndex, rule) = bestMatch
        val propertyTokens = tokens.subList(0, startIndex)
        val propertyPart = propertyTokens.joinToString("")
        return Pair(propertyPart, rule.type)
    }

    /**
     * 按 camelCase 边界拆分 token。
     *
     * 例如：
     * - "LastName" → ["Last", "Name"]
     * - "AddressCity" → ["Address", "City"]
     * - "NameIsNull" → ["Name", "Is", "Null"]
     */
    private fun splitCamelCase(s: String): List<String> {
        if (s.isEmpty()) return emptyList()

        val tokens = mutableListOf<String>()
        var current = StringBuilder()
        current.append(s[0])

        for (i in 1 until s.length) {
            val c = s[i]
            if (c.isUpperCase() && (s[i - 1].isLowerCase() || s[i - 1].isDigit())) {
                // camelCase 边界：前一个是小写/数字，当前是大写
                tokens.add(current.toString())
                current = StringBuilder()
            }
            current.append(c)
        }
        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }
        return tokens
    }

    /**
     * 将 camelCase 属性名转换为属性路径（暂仅处理嵌套属性）。
     *
     * Spring Data JPA 中，嵌套属性通常以 "AddressCity" 表示 "address.city"。
     * 但为了避免歧义（如 "AddressCity" 可能是单一属性），默认保留原样。
     * 实际使用时可结合实体元数据解析。
     *
     * 此处仅做首字母小写标准化。
     */
    private fun decamelize(name: String): String {
        if (name.isEmpty()) return name
        return name[0].lowercaseChar() + name.substring(1)
    }

    /**
     * 条件关键字规则。
     *
     * @param keyword         关键字（如 "IsNull", "Between"）
     * @param requiresParameter 是否需要参数（IS_NULL 类不需要，BETWEEN 需要）
     * @param type            对应的 PartType
     */
    private data class KeywordRule(
        val keyword: String,
        val requiresParameter: Boolean,
        val type: PartType,
    )
}
