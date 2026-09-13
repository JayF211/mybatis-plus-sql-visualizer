package cn.song.mybatisplus.visualizer.converter.jpa;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

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
public final class PartTreeParser {

    /** 单例实例（兼容 Kotlin object 调用形式）。 */
    public static final PartTreeParser INSTANCE = new PartTreeParser();

    private PartTreeParser() {
    }

    // Subject 关键字集合
    private static final Set<String> QUERY_KEYWORDS = Set.of("find", "read", "get", "query", "search", "stream");
    private static final String COUNT_KEYWORD = "count";
    private static final String EXISTS_KEYWORD = "exists";
    private static final Set<String> DELETE_KEYWORDS = Set.of("delete", "remove");
    private static final String DISTINCT_KEYWORD = "Distinct";

    // Subject 限制关键字
    private static final String TOP_KEYWORD = "Top";
    private static final String FIRST_KEYWORD = "First";

    // 条件关键字映射（按匹配优先级排序，高优先级在前）
    // 每个条目：(keyword, requiresParameter, partType)
    private static final List<KeywordRule> CONDITION_KEYWORDS = List.of(
            new KeywordRule("IsBetween", true, PartType.BETWEEN),
            new KeywordRule("Between", true, PartType.BETWEEN),

            new KeywordRule("IsNotNull", false, PartType.IS_NOT_NULL),
            new KeywordRule("NotNull", false, PartType.IS_NOT_NULL),

            new KeywordRule("IsNull", false, PartType.IS_NULL),
            new KeywordRule("Null", false, PartType.IS_NULL),

            new KeywordRule("IsLessThanEqual", true, PartType.LESS_THAN_EQUAL),
            new KeywordRule("LessThanEqual", true, PartType.LESS_THAN_EQUAL),

            new KeywordRule("IsLessThan", true, PartType.LESS_THAN),
            new KeywordRule("LessThan", true, PartType.LESS_THAN),

            new KeywordRule("IsGreaterThanEqual", true, PartType.GREATER_THAN_EQUAL),
            new KeywordRule("GreaterThanEqual", true, PartType.GREATER_THAN_EQUAL),

            new KeywordRule("IsGreaterThan", true, PartType.GREATER_THAN),
            new KeywordRule("GreaterThan", true, PartType.GREATER_THAN),

            new KeywordRule("IsBefore", true, PartType.BEFORE),
            new KeywordRule("Before", true, PartType.BEFORE),

            new KeywordRule("IsAfter", true, PartType.AFTER),
            new KeywordRule("After", true, PartType.AFTER),

            new KeywordRule("IsNotLike", true, PartType.NOT_LIKE),
            new KeywordRule("NotLike", true, PartType.NOT_LIKE),

            new KeywordRule("IsLike", true, PartType.LIKE),
            new KeywordRule("Like", true, PartType.LIKE),

            new KeywordRule("IsStartingWith", true, PartType.STARTING_WITH),
            new KeywordRule("StartingWith", true, PartType.STARTING_WITH),
            new KeywordRule("StartsWith", true, PartType.STARTING_WITH),

            new KeywordRule("IsEndingWith", true, PartType.ENDING_WITH),
            new KeywordRule("EndingWith", true, PartType.ENDING_WITH),
            new KeywordRule("EndsWith", true, PartType.ENDING_WITH),

            new KeywordRule("IsContaining", true, PartType.CONTAINING),
            new KeywordRule("Containing", true, PartType.CONTAINING),
            new KeywordRule("Contains", true, PartType.CONTAINING),

            new KeywordRule("IsNotContaining", true, PartType.NOT_CONTAINING),
            new KeywordRule("NotContaining", true, PartType.NOT_CONTAINING),
            new KeywordRule("NotContains", true, PartType.NOT_CONTAINING),

            new KeywordRule("IsNotEmpty", false, PartType.IS_NOT_EMPTY),
            new KeywordRule("NotEmpty", false, PartType.IS_NOT_EMPTY),

            new KeywordRule("IsEmpty", false, PartType.IS_EMPTY),
            new KeywordRule("Empty", false, PartType.IS_EMPTY),

            new KeywordRule("IsNotIn", true, PartType.NOT_IN),
            new KeywordRule("NotIn", true, PartType.NOT_IN),

            new KeywordRule("IsIn", true, PartType.IN),
            new KeywordRule("In", true, PartType.IN),

            new KeywordRule("IsNear", true, PartType.NEAR),
            new KeywordRule("Near", true, PartType.NEAR),

            new KeywordRule("IsWithin", true, PartType.WITHIN),
            new KeywordRule("Within", true, PartType.WITHIN),

            new KeywordRule("MatchesRegex", true, PartType.REGEX),
            new KeywordRule("Regex", true, PartType.REGEX),
            new KeywordRule("Matches", true, PartType.REGEX),

            new KeywordRule("Exists", false, PartType.EXISTS),

            new KeywordRule("IsTrue", false, PartType.TRUE),
            new KeywordRule("True", false, PartType.TRUE),

            new KeywordRule("IsFalse", false, PartType.FALSE),
            new KeywordRule("False", false, PartType.FALSE),

            new KeywordRule("IsNot", true, PartType.NEGATING_SIMPLE_PROPERTY),
            new KeywordRule("Not", true, PartType.NEGATING_SIMPLE_PROPERTY),

            // SIMPLE_PROPERTY 兜底："Is" 或无前缀
            new KeywordRule("Is", true, PartType.SIMPLE_PROPERTY)
    );

    // OrderBy 关键字
    private static final String ORDER_BY_KEYWORD = "OrderBy";
    private static final String ASC_KEYWORD = "Asc";
    private static final String DESC_KEYWORD = "Desc";
    private static final String ALL_IGNORE_CASE_KEYWORD = "AllIgnoreCase";
    private static final String IGNORE_CASE_KEYWORD = "IgnoreCase";

    /**
     * 解析方法名为 PartTree 模型。
     *
     * @param methodName Spring Data JPA Repository 方法名
     * @return 解析产物
     * @throws IllegalArgumentException 方法名格式非法（无 By 关键字且非查询全部）
     */
    @NotNull
    public static PartTree parse(@NotNull String methodName) {
        // 按第一个 "By" 分割
        int byIndex = indexOfBy(methodName);
        if (byIndex < 0) {
            // 无 By 段：纯 Subject（如 findAll → find all）
            Subject subject = parseSubject(methodName);
            return new PartTree(subject, List.of(), List.of());
        }

        String subjectPart = methodName.substring(0, byIndex);
        String predicatePart = methodName.substring(byIndex + 2); // skip "By"

        Subject subject = parseSubject(subjectPart);

        // T-J4：IgnoreCase / AllIgnoreCase 任意位置剥离（原先只认尾部，中部的会并入属性名）。
        // 1) 尾部连续剥离（含 OrderBy 之后的形态，如 "LastNameOrderByFirstNameAscIgnoreCase"），
        //    尾部形态沿用历史语义：视为全局忽略大小写（单条件场景与 Spring Data 等价）。
        boolean globalIgnoreCase = false;
        while (true) {
            if (predicatePart.endsWith(ALL_IGNORE_CASE_KEYWORD)) {
                predicatePart = predicatePart.substring(0, predicatePart.length() - ALL_IGNORE_CASE_KEYWORD.length());
                globalIgnoreCase = true;
            } else if (predicatePart.endsWith(IGNORE_CASE_KEYWORD)) {
                predicatePart = predicatePart.substring(0, predicatePart.length() - IGNORE_CASE_KEYWORD.length());
                globalIgnoreCase = true;
            } else {
                break;
            }
        }
        // 2) 中部 AllIgnoreCase 剥离 → 全局生效（如 "FirstNameAllIgnoreCaseAndLastName"）。
        if (predicatePart.contains(ALL_IGNORE_CASE_KEYWORD)) {
            globalIgnoreCase = true;
            predicatePart = predicatePart.replace(ALL_IGNORE_CASE_KEYWORD, "");
        }

        // 从 Predicate 尾部剥离 OrderBy 子句
        OrderByResult orderByResult = extractOrderBy(predicatePart);
        String predicateBody = orderByResult.body();
        List<OrderByPart> orderByParts = orderByResult.orderBy();

        // 解析 Or/And 条件（段内 IgnoreCase 在 parseCondition 中逐段剥离并记录标记）
        List<PredicatePart> predicates = parsePredicate(predicateBody, globalIgnoreCase);

        return new PartTree(subject, predicates, orderByParts);
    }

    /**
     * 查找第一个真正的 "By" 分隔符位置（非 OrderBy）。
     */
    private static int indexOfBy(@NotNull String methodName) {
        int index = 0;
        while (index < methodName.length() - 1) {
            int found = methodName.indexOf("By", index);
            if (found < 0) return -1;
            // 检查是否是 OrderBy：By 后面紧跟 OrderBy 关键字
            if (isOrderByAt(methodName, found)) {
                index = found + 2;
                continue;
            }
            return found;
        }
        return -1;
    }

    /**
     * 检查 methodName 中 position 位置的 "By" 是否是 "OrderBy" 的尾部。
     *
     * <p>"OrderBy" = "Order" + "By"，故该 "By" 属于 OrderBy 当且仅当前 5 个字符恰为 "OrderBy"
     * 的前段（regionMatches 从 position-5 起）。旧实现误判「By 后紧跟 OrderBy」——
     * `findAllByOrderByXxx` 中 Subject 的 By 被跳过、OrderBy 内部的 By 反被当分隔符，
     * 产出 `createTimeDesc = ?` 假谓词且丢失 ORDER BY（探针实证，M39 修复）。
     */
    private static boolean isOrderByAt(@NotNull String methodName, int position) {
        return position >= 5 && methodName.regionMatches(position - 5, "OrderBy", 0, 7);
    }

    /**
     * 解析 Subject 段。
     */
    @NotNull
    private static Subject parseSubject(@NotNull String subjectPart) {
        if (subjectPart.isEmpty()) {
            return new Subject(Operation.FIND, false, null);
        }

        String remaining = subjectPart;

        // 步骤 1：提取操作关键字（前缀匹配，按长度降序避免 find 匹配到 f）
        Operation operation;
        if (DELETE_KEYWORDS.contains(remaining)) {
            remaining = "";
            operation = Operation.DELETE;
        } else if (COUNT_KEYWORD.equals(remaining)) {
            remaining = "";
            operation = Operation.COUNT;
        } else if (EXISTS_KEYWORD.equals(remaining)) {
            remaining = "";
            operation = Operation.EXISTS;
        } else if (QUERY_KEYWORDS.contains(remaining)) {
            remaining = "";
            operation = Operation.FIND;
        } else if (remaining.startsWith("delete")) {
            remaining = remaining.substring("delete".length());
            operation = Operation.DELETE;
        } else if (remaining.startsWith("remove")) {
            remaining = remaining.substring("remove".length());
            operation = Operation.DELETE;
        } else if (remaining.startsWith("count")) {
            remaining = remaining.substring("count".length());
            operation = Operation.COUNT;
        } else if (remaining.startsWith("exists")) {
            remaining = remaining.substring("exists".length());
            operation = Operation.EXISTS;
        } else {
            // find/read/get/query/search/stream 可能后跟 Distinct/Top/First
            boolean matched = false;
            for (String kw : QUERY_KEYWORDS) {
                if (remaining.startsWith(kw)) {
                    remaining = remaining.substring(kw.length());
                    return parseSubjectModifiers(remaining, Operation.FIND);
                }
            }
            operation = Operation.FIND;
        }

        // 对于 DELETE/COUNT/EXISTS，继续解析 Distinct/Top/First 修饰符
        return parseSubjectModifiers(remaining, operation);
    }

    /**
     * 在操作关键字之后解析修饰符（Distinct、Top N / First N）。
     */
    @NotNull
    private static Subject parseSubjectModifiers(@NotNull String remaining, @NotNull Operation operation) {
        String rem = remaining;
        boolean distinct = false;
        Integer limit = null;

        // 提取 Distinct
        if (rem.startsWith(DISTINCT_KEYWORD)) {
            distinct = true;
            rem = rem.substring(DISTINCT_KEYWORD.length());
        }

        // 提取 Top N / First N
        if (rem.startsWith(TOP_KEYWORD)) {
            rem = rem.substring(TOP_KEYWORD.length());
            NumberResult numberResult = parseLeadingNumber(rem);
            if (numberResult != null) {
                limit = numberResult.number();
                rem = numberResult.rest();
            }
        } else if (rem.startsWith(FIRST_KEYWORD)) {
            rem = rem.substring(FIRST_KEYWORD.length());
            NumberResult numberResult = parseLeadingNumber(rem);
            if (numberResult != null) {
                limit = numberResult.number();
                rem = numberResult.rest();
            } else {
                limit = 1; // First 无数字默认 1
            }
        }

        return new Subject(operation, distinct, limit);
    }

    /**
     * 从字符串开头解析数字。
     *
     * @return [数字, 剩余字符串] 或 null（开头非数字）
     */
    private static NumberResult parseLeadingNumber(@NotNull String s) {
        if (s.isEmpty() || !Character.isDigit(s.charAt(0))) return null;
        int endIndex = 0;
        while (endIndex < s.length() && Character.isDigit(s.charAt(endIndex))) {
            endIndex++;
        }
        int number = Integer.parseInt(s.substring(0, endIndex));
        return new NumberResult(number, s.substring(endIndex));
    }

    /**
     * 从 Predicate 尾部提取 OrderBy 子句。
     *
     * @return [剥离 OrderBy 后的谓词体, OrderBy 排序项列表]
     */
    private static OrderByResult extractOrderBy(@NotNull String predicatePart) {
        int orderByIndex = findOrderByIndex(predicatePart);
        if (orderByIndex < 0) {
            return new OrderByResult(predicatePart, List.of());
        }

        String predicateBody = predicatePart.substring(0, orderByIndex);
        String orderByBody = predicatePart.substring(orderByIndex + ORDER_BY_KEYWORD.length());

        List<OrderByPart> orderByParts = parseOrderByBody(orderByBody);
        return new OrderByResult(predicateBody, orderByParts);
    }

    /**
     * 查找 OrderBy 子句起始位置。
     *
     * 从右向左扫描，找到最后一个独立的 OrderBy 关键字。
     */
    private static int findOrderByIndex(@NotNull String s) {
        // OrderBy 必须在末尾区域，从末尾开始查找
        int searchStart = s.length() - 1;
        while (searchStart >= 0) {
            int idx = s.lastIndexOf(ORDER_BY_KEYWORD, searchStart);
            if (idx < 0) return -1;
            // 确认是独立的 OrderBy 关键字（后面不跟条件关键字或连接符混淆）
            int afterOrderBy = idx + ORDER_BY_KEYWORD.length();
            if (afterOrderBy <= s.length()) {
                // OrderBy 后面应有属性名（大写字母开头）
                if (afterOrderBy == s.length() || Character.isUpperCase(s.charAt(afterOrderBy))) {
                    return idx;
                }
            }
            searchStart = idx - 1;
        }
        return -1;
    }

    /**
     * 解析 OrderBy 子句体。
     */
    private static List<OrderByPart> parseOrderByBody(@NotNull String orderByBody) {
        if (orderByBody.isEmpty()) return List.of();

        List<String> parts = splitOrderByParts(orderByBody);
        List<OrderByPart> result = new ArrayList<>();
        for (String part : parts) {
            SortDirection direction;
            String propertyName;
            if (part.endsWith(DESC_KEYWORD)) {
                direction = SortDirection.DESC;
                propertyName = part.substring(0, part.length() - DESC_KEYWORD.length());
            } else if (part.endsWith(ASC_KEYWORD)) {
                direction = SortDirection.ASC;
                propertyName = part.substring(0, part.length() - ASC_KEYWORD.length());
            } else {
                direction = SortDirection.ASC;
                propertyName = part;
            }
            result.add(new OrderByPart(decamelize(propertyName), direction));
        }
        return result;
    }

    /**
     * 将 OrderBy 子句拆分为独立的属性项。
     *
     * 按 "And"/"Or" 拆分，同时考虑 Asc/Desc 后缀。
     */
    private static List<String> splitOrderByParts(@NotNull String body) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;

        while (i < body.length()) {
            // 检查 "And" 分隔符
            if (i > 0 && body.startsWith("And", i) && isCamelCaseBoundary(body, i)) {
                result.add(current.toString());
                current = new StringBuilder();
                i += 3; // skip "And"
                continue;
            }
            // 检查 "Or" 分隔符
            if (i > 0 && body.startsWith("Or", i) && isCamelCaseBoundary(body, i)) {
                result.add(current.toString());
                current = new StringBuilder();
                i += 2; // skip "Or"
                continue;
            }
            current.append(body.charAt(i));
            i++;
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    /**
     * 检查 index 位置是否是 camelCase 边界（即该位置前是小写/数字，该位置是大写）。
     */
    private static boolean isCamelCaseBoundary(@NotNull String s, int index) {
        if (index <= 0 || index >= s.length()) return false;
        return Character.isUpperCase(s.charAt(index))
                && (Character.isLowerCase(s.charAt(index - 1)) || Character.isDigit(s.charAt(index - 1)));
    }

    /**
     * 解析 Predicate 主体为 Or/And 条件列表。
     *
     * @param predicateBody OrderBy 和 IgnoreCase 剥离后的谓词体
     * @param globalIgnoreCase 全局忽略大小写标志（尾部 / AllIgnoreCase 剥离产物）
     */
    private static List<PredicatePart> parsePredicate(@NotNull String predicateBody, boolean globalIgnoreCase) {
        if (predicateBody.isEmpty()) return List.of();

        // 按 Or 拆分
        List<String> orParts = splitByKeyword(predicateBody, "Or");

        List<PredicatePart> result = new ArrayList<>();
        int index = 0;
        for (String orPart : orParts) {
            Connector connector = index == 0 ? Connector.AND : Connector.OR;
            // 按 And 拆分
            List<String> andParts = splitByKeyword(orPart, "And");
            List<Condition> conditions = new ArrayList<>();
            for (String andPart : andParts) {
                conditions.add(parseCondition(andPart, globalIgnoreCase));
            }
            result.add(new PredicatePart(connector, conditions));
            index++;
        }
        return result;
    }

    /**
     * 按关键字拆分（Or/And），保留 camelCase 边界语义。
     */
    private static List<String> splitByKeyword(@NotNull String s, @NotNull String keyword) {
        if (s.isEmpty()) return List.of();

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;

        while (i < s.length()) {
            if (i > 0 && s.startsWith(keyword, i) && isCamelCaseBoundary(s, i)) {
                // 确认是有效的拆分点：关键字后面应是大写字母（新属性开头）或结束
                int afterKeyword = i + keyword.length();
                if (afterKeyword >= s.length() || Character.isUpperCase(s.charAt(afterKeyword))) {
                    result.add(current.toString());
                    current = new StringBuilder();
                    i = afterKeyword;
                    continue;
                }
            }
            current.append(s.charAt(i));
            i++;
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    /**
     * 解析单个条件 Part。
     *
     * 按 camelCase 边界切分，从尾部匹配条件关键字。
     *
     * <p>T-J4：先剥离段尾 IgnoreCase / AllIgnoreCase 并逐段记录 ignoreCase 标记——
     * 中间位置的 IgnoreCase（如 "NameIgnoreCaseAndEmail" 的第一段）不再并入属性名。
     */
    private static Condition parseCondition(@NotNull String part, boolean globalIgnoreCase) {
        // 剥离段内 IgnoreCase（段级标记）
        AbstractMap.SimpleEntry<String, Boolean> stripped = stripPartIgnoreCase(part);
        String strippedPart = stripped.getKey();
        boolean ignoreCase = globalIgnoreCase || stripped.getValue();

        // 查找最佳匹配的条件关键字
        MatchResult matchResult = matchConditionKeyword(strippedPart);

        if (matchResult != null) {
            String propertyPart = matchResult.propertyPart();
            PartType partType = matchResult.partType();
            return new Condition(
                    decamelize(propertyPart),
                    partType,
                    ignoreCase,
                    splitNestedPath(propertyPart));
        }

        // 兜底：SIMPLE_PROPERTY
        return new Condition(
                decamelize(strippedPart),
                PartType.SIMPLE_PROPERTY,
                ignoreCase,
                splitNestedPath(strippedPart));
    }

    /**
     * T-J4：剥离单个条件 Part 段尾的 IgnoreCase / AllIgnoreCase，并返回段级忽略大小写标记。
     *
     * <p>Spring Data 语义：紧跟属性（或条件关键字）的 `IgnoreCase` 仅对该段生效；
     * `AllIgnoreCase` 已在 parse 全局剥离，这里兜底再剥一次。
     *
     * @return [剥离后的段, 该段是否忽略大小写]
     */
    @NotNull
    private static AbstractMap.SimpleEntry<String, Boolean> stripPartIgnoreCase(@NotNull String part) {
        String stripped = part;
        boolean found = false;
        while (true) {
            if (stripped.endsWith(ALL_IGNORE_CASE_KEYWORD)) {
                stripped = stripped.substring(0, stripped.length() - ALL_IGNORE_CASE_KEYWORD.length());
                found = true;
            } else if (stripped.endsWith(IGNORE_CASE_KEYWORD)) {
                stripped = stripped.substring(0, stripped.length() - IGNORE_CASE_KEYWORD.length());
                found = true;
            } else {
                return new AbstractMap.SimpleEntry<>(stripped, found);
            }
        }
    }

    /**
     * 在 Part 中匹配条件关键字。
     *
     * 策略：按 camelCase 边界切分 token，从尾部匹配最长的关键字组合。
     * 例如 "NameNotContaining" → tokens: [Name, Not, Containing]
     * 优先匹配 "NotContaining" (2 tokens) 而非 "Containing" (1 token)
     *
     * @return [属性部分, PartType] 或 null（未匹配到关键字）
     */
    private static MatchResult matchConditionKeyword(@NotNull String part) {
        List<String> tokens = splitCamelCase(part);
        if (tokens.isEmpty()) return null;

        // 收集所有匹配的规则，按关键字 token 数降序排列（优先最长匹配）
        List<AbstractMap.SimpleEntry<Integer, KeywordRule>> matches = new ArrayList<>();

        for (KeywordRule rule : CONDITION_KEYWORDS) {
            List<String> keywordTokens = splitCamelCase(rule.keyword());
            if (keywordTokens.size() > tokens.size()) continue;

            // 检查尾部 tokens 是否匹配关键字
            int startIndex = tokens.size() - keywordTokens.size();
            boolean matched = true;
            for (int j = 0; j < keywordTokens.size(); j++) {
                if (!tokens.get(startIndex + j).equalsIgnoreCase(keywordTokens.get(j))) {
                    matched = false;
                    break;
                }
            }

            if (matched) {
                List<String> propertyTokens = tokens.subList(0, startIndex);
                if (propertyTokens.isEmpty() && rule.requiresParameter()) {
                    // 需要参数但无属性名，不能是这种类型
                    continue;
                }
                matches.add(new AbstractMap.SimpleEntry<>(startIndex, rule));
            }
        }

        if (matches.isEmpty()) return null;

        // 选择关键字 token 数最多的匹配（最长匹配优先）
        AbstractMap.SimpleEntry<Integer, KeywordRule> bestMatch = null;
        int bestSize = -1;
        for (AbstractMap.SimpleEntry<Integer, KeywordRule> m : matches) {
            int size = splitCamelCase(m.getValue().keyword()).size();
            if (size > bestSize) {
                bestSize = size;
                bestMatch = m;
            }
        }

        int startIndex = bestMatch.getKey();
        KeywordRule rule = bestMatch.getValue();
        List<String> propertyTokens = tokens.subList(0, startIndex);
        String propertyPart = String.join("", propertyTokens);
        return new MatchResult(propertyPart, rule.type());
    }

    /**
     * 按 camelCase 边界拆分 token。
     */
    private static List<String> splitCamelCase(@NotNull String s) {
        if (s.isEmpty()) return List.of();

        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        current.append(s.charAt(0));

        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c) && (Character.isLowerCase(s.charAt(i - 1)) || Character.isDigit(s.charAt(i - 1)))) {
                // camelCase 边界：前一个是小写/数字，当前是大写
                tokens.add(current.toString());
                current = new StringBuilder();
            }
            current.append(c);
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
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
    private static String decamelize(@NotNull String name) {
        if (name.isEmpty()) return name;
        return name.substring(0, 1).toLowerCase() + name.substring(1);
    }

    /**
     * 条件关键字规则。
     *
     * @param keyword         关键字（如 "IsNull", "Between"）
     * @param requiresParameter 是否需要参数（IS_NULL 类不需要，BETWEEN 需要）
     * @param type            对应的 PartType
     */
    private static final class KeywordRule {
    private final @NotNull String keyword;
    private final boolean requiresParameter;
    private final @NotNull PartType type;

    public KeywordRule(@NotNull String keyword, boolean requiresParameter, @NotNull PartType type) {
        this.keyword = keyword;
        this.requiresParameter = requiresParameter;
        this.type = type;
    }

    public @NotNull String getKeyword() { return keyword; }
    public @NotNull String keyword() { return keyword; }
    public boolean isRequiresParameter() { return requiresParameter; }
    public boolean requiresParameter() { return requiresParameter; }
    public @NotNull PartType getType() { return type; }
    public @NotNull PartType type() { return type; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof KeywordRule)) return false;
        KeywordRule that = (KeywordRule) o;
        return java.util.Objects.equals(this.keyword, that.keyword) && java.util.Objects.equals(this.requiresParameter, that.requiresParameter) && java.util.Objects.equals(this.type, that.type);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(keyword, requiresParameter, type);
    }

    @Override
    public String toString() {
        return "KeywordRule[" + "keyword=" + keyword + ", " + "requiresParameter=" + requiresParameter + ", " + "type=" + type + "]";
    }
}

    // ------------------------------------------------------------------
    // 嵌套属性路径拆分
    // ------------------------------------------------------------------

    /**
     * 拆分嵌套属性路径。
     *
     * 策略：按 camelCase 边界切分 token，从头部开始匹配关联字段名。
     * 例如 "AddressCity" → tokens: [Address, City]
     *       第一个 token "Address" 的小写 "address" 匹配到关联字段 → associationPath="address", remainingPath="City"
     *
     * @param propertyPart 属性部分（驼峰形式，如 "AddressCity"）
     * @return 嵌套路径解析结果（非嵌套时 associationPath=null）
     */
    @NotNull
    public static NestedPropertyPath splitNestedPath(@NotNull String propertyPart) {
        if (propertyPart.isEmpty()) return new NestedPropertyPath(null, propertyPart);
        List<String> tokens = splitCamelCase(propertyPart);
        if (tokens.size() < 2) return new NestedPropertyPath(null, propertyPart);

        // 第一个 token 小写后作为候选关联字段名
        String firstToken = tokens.get(0);
        String candidateAssociation = firstToken.substring(0, 1).toLowerCase() + firstToken.substring(1);
        List<String> remainingTokens = tokens.size() > 1 ? tokens.subList(1, tokens.size()) : java.util.List.of();
        String remainingPath = String.join("", remainingTokens);

        return new NestedPropertyPath(
                candidateAssociation,  // 仅候选，需 TableInfo 确认
                remainingPath);
    }

    /** OrderBy 提取结果。 */
    private static final class OrderByResult {
    private final @NotNull String body;
    private final @NotNull List<OrderByPart> orderBy;

    public OrderByResult(@NotNull String body, @NotNull List<OrderByPart> orderBy) {
        this.body = body;
        this.orderBy = orderBy;
    }

    public @NotNull String getBody() { return body; }
    public @NotNull String body() { return body; }
    public @NotNull List<OrderByPart> getOrderBy() { return orderBy; }
    public @NotNull List<OrderByPart> orderBy() { return orderBy; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OrderByResult)) return false;
        OrderByResult that = (OrderByResult) o;
        return java.util.Objects.equals(this.body, that.body) && java.util.Objects.equals(this.orderBy, that.orderBy);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(body, orderBy);
    }

    @Override
    public String toString() {
        return "OrderByResult[" + "body=" + body + ", " + "orderBy=" + orderBy + "]";
    }
}

    /** 数字解析结果。 */
    private static final class NumberResult {
    private final int number;
    private final @NotNull String rest;

    public NumberResult(int number, @NotNull String rest) {
        this.number = number;
        this.rest = rest;
    }

    public int getNumber() { return number; }
    public int number() { return number; }
    public @NotNull String getRest() { return rest; }
    public @NotNull String rest() { return rest; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NumberResult)) return false;
        NumberResult that = (NumberResult) o;
        return java.util.Objects.equals(this.number, that.number) && java.util.Objects.equals(this.rest, that.rest);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(number, rest);
    }

    @Override
    public String toString() {
        return "NumberResult[" + "number=" + number + ", " + "rest=" + rest + "]";
    }
}

    /** 条件关键字匹配结果。 */
    private static final class MatchResult {
    private final @NotNull String propertyPart;
    private final @NotNull PartType partType;

    public MatchResult(@NotNull String propertyPart, @NotNull PartType partType) {
        this.propertyPart = propertyPart;
        this.partType = partType;
    }

    public @NotNull String getPropertyPart() { return propertyPart; }
    public @NotNull String propertyPart() { return propertyPart; }
    public @NotNull PartType getPartType() { return partType; }
    public @NotNull PartType partType() { return partType; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MatchResult)) return false;
        MatchResult that = (MatchResult) o;
        return java.util.Objects.equals(this.propertyPart, that.propertyPart) && java.util.Objects.equals(this.partType, that.partType);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(propertyPart, partType);
    }

    @Override
    public String toString() {
        return "MatchResult[" + "propertyPart=" + propertyPart + ", " + "partType=" + partType + "]";
    }
}
}
