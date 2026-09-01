package cn.song.mybatisplus.visualizer.psi.jpa;

import com.intellij.psi.PsiBinaryExpression;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiPolyadicExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
public final class CustomDaoHqlLocator {

    /** DAO 类名后缀集合。用于 qualifier 启发：方法调用的 qualifier 变量名以任一后缀结尾即视为 DAO 对象。 */
    private static final Set<String> DAO_QUALIFIER_SUFFIXES = Set.of(
            "Dao", "DaoImpl", "Mapper", "Repository", "CommonDao", "DaoHelper", "BaseDao"
    );

    /** 查询方法名关键字集合。用于方法名启发：方法名包含任一关键字即视为潜在查询方法。 */
    private static final Set<String> QUERY_METHOD_KEYWORDS = Set.of(
            "Query", "Hql", "Jpql", "execute", "findByQuery", "findBySql"
    );

    /** HQL 内容匹配正则模式集合。用于内容启发：字符串参数需匹配至少一个模式才视为 HQL。 */
    private static final List<Pattern> HQL_CONTENT_PATTERNS = List.of(
            Pattern.compile("\\bSELECT\\b.*\\bFROM\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bUPDATE\\b.*\\bSET\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bDELETE\\b.*\\bFROM\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bFROM\\b\\s+[A-Z][a-zA-Z0-9_]*\\b", Pattern.CASE_INSENSITIVE)
    );

    /** HQL 命名参数正则。匹配 `:paramName` 形式的命名参数（排除 `::` 类型转换运算符）。 */
    private static final Pattern NAMED_PARAM_PATTERN = Pattern.compile("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)");

    /** HQL FROM 子句实体类名提取正则。匹配 `FROM EntityName` 中的驼峰类名。 */
    private static final Pattern FROM_ENTITY_PATTERN = Pattern.compile("\\bFROM\\s+([A-Z][a-zA-Z0-9_]*)\\b", Pattern.CASE_INSENSITIVE);

    /** HQL UPDATE/DELETE 语句实体类名提取正则。 */
    private static final Pattern UPDATE_ENTITY_PATTERN = Pattern.compile("\\bUPDATE\\s+([A-Z][a-zA-Z0-9_]*)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_ENTITY_PATTERN = Pattern.compile("\\bDELETE\\s+FROM\\s+([A-Z][a-zA-Z0-9_]*)\\b", Pattern.CASE_INSENSITIVE);

    /** 单例（保持对 Kotlin `CustomDaoHqlLocator.method()` 调用的二进制兼容）。 */
    public static final CustomDaoHqlLocator INSTANCE = new CustomDaoHqlLocator();

    private CustomDaoHqlLocator() {
    }

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
    @Nullable
    public static CustomDaoHqlInfo locate(@NotNull PsiElement anchor) {
        // 路径 1：anchor 是 PsiMethodCallExpression → 精确匹配该调用
        if (anchor instanceof PsiMethodCallExpression) {
            return locateFromCall((PsiMethodCallExpression) anchor);
        }

        // 路径 2：anchor 是 PsiMethod 自身（测试场景）→ 取方法体内第一个匹配
        PsiMethod method = anchor instanceof PsiMethod ? (PsiMethod) anchor
                : PsiTreeUtil.getParentOfType(anchor, PsiMethod.class);
        if (method == null) return null;
        PsiCodeBlock body = method.getBody();
        if (body == null) return null;

        // 路径 3：扫描方法体内全部方法调用，识别自定义 DAO HQL 调用
        for (PsiMethodCallExpression call : PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression.class)) {
            CustomDaoHqlInfo info = locateFromCall(call);
            if (info != null) return info;
        }

        return null;
    }

    /**
     * 从指定的 PsiMethodCallExpression 提取 CustomDaoHqlInfo。
     * 仅当该调用匹配 DAO HQL 三重启发检测时才返回非 null。
     */
    @Nullable
    private static CustomDaoHqlInfo locateFromCall(@NotNull PsiMethodCallExpression call) {
        if (!isCustomDaoHqlCall(call)) return null;

        HqlStringResult result = extractHqlFromCall(call);
        String hql = result.hql();
        if (hql == null || hql.isBlank()) return null;

        PsiMethod method = PsiTreeUtil.getParentOfType(call, PsiMethod.class);
        if (method == null) return null;
        String entityClassName = extractEntityClassNameFromHql(hql);
        ParamSource paramSource = detectParamSource(call);
        List<String> hqlParamNames = extractNamedParams(hql);
        List<HqlParameter> parameters = resolveParameters(call, hqlParamNames);

        return new CustomDaoHqlInfo(hql, result.literal(), entityClassName, parameters, method, paramSource);
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
    private static boolean isCustomDaoHqlCall(@NotNull PsiMethodCallExpression call) {
        boolean contentMatch = matchesHqlContent(call);
        if (!contentMatch) return false;

        boolean qualifierMatch = matchesDaoQualifier(call);
        boolean methodNameMatch = matchesQueryMethodName(call);

        return qualifierMatch || methodNameMatch;
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
    private static boolean matchesDaoQualifier(@NotNull PsiMethodCallExpression call) {
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (!(qualifier instanceof PsiReferenceExpression)) return false;
        String qualifierName = ((PsiReferenceExpression) qualifier).getReferenceName();
        if (qualifierName == null) return false;
        for (String suffix : DAO_QUALIFIER_SUFFIXES) {
            if (qualifierName.endsWith(suffix)) return true;
        }
        return false;
    }

    /**
     * 方法名启发检测。
     *
     * 检查方法名是否包含查询关键字（Query / Hql / Jpql / execute 等）。
     *
     * @param call 目标方法调用
     * @return 方法名是否包含查询关键字
     */
    private static boolean matchesQueryMethodName(@NotNull PsiMethodCallExpression call) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) return false;
        String lower = methodName.toLowerCase(Locale.ROOT);
        for (String keyword : QUERY_METHOD_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /**
     * 内容启发检测。
     *
     * 检查第一个字符串参数是否匹配 HQL 语法模式（SELECT...FROM / UPDATE...SET / DELETE...FROM / FROM Entity）。
     *
     * @param call 目标方法调用
     * @return 字符串参数是否匹配 HQL 内容模式
     */
    private static boolean matchesHqlContent(@NotNull PsiMethodCallExpression call) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length == 0) return false;
        String text = extractArgumentText(args[0]);
        if (text == null) return false;
        for (Pattern pattern : HQL_CONTENT_PATTERNS) {
            if (pattern.matcher(text).find()) return true;
        }
        return false;
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
    @NotNull
    private static HqlStringResult extractHqlFromCall(@NotNull PsiMethodCallExpression call) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length == 0) return new HqlStringResult(null, false);

        PsiExpression firstArg = args[0];
        return extractHqlString(firstArg);
    }

    /**
     * 从表达式提取 HQL 字符串。
     *
     * @param expression HQL 参数表达式
     * @return Pair(HQL 字符串, 是否为纯字面量)
     */
    @NotNull
    private static HqlStringResult extractHqlString(@NotNull PsiExpression expression) {
        if (expression instanceof PsiLiteralExpression) {
            Object value = ((PsiLiteralExpression) expression).getValue();
            String text = value instanceof String ? (String) value : expression.getText();
            return new HqlStringResult(text, true);
        }
        if (expression instanceof PsiBinaryExpression || expression instanceof PsiPolyadicExpression) {
            // 字符串拼接："..." + "..."（含链式 `+`，如 `"a" + "b" + "c"`——链式在 PSI 中为 PsiPolyadicExpression，
            // 与 PsiBinaryExpression 互不继承）→ 递归收集所有 PsiLiteralExpression 子节点
            List<String> parts = collectStringLiterals(expression);
            if (!parts.isEmpty()) {
                return new HqlStringResult(String.join("", parts), true);
            }
            return new HqlStringResult(expression.getText(), false);
        }
        if (expression instanceof PsiReferenceExpression) {
            // 变量引用：尝试解析为常量
            PsiReferenceExpression refExpr = (PsiReferenceExpression) expression;
            PsiElement resolved = refExpr.resolve();
            if (resolved instanceof PsiVariable) {
                PsiExpression initializer = ((PsiVariable) resolved).getInitializer();
                if (initializer != null) {
                    return extractHqlString(initializer);
                }
                return new HqlStringResult(refExpr.getReferenceName(), false);
            }
            return new HqlStringResult(refExpr.getReferenceName(), false);
        }
        return new HqlStringResult(expression.getText(), false);
    }

    /**
     * 从参数表达式提取文本内容（用于内容启发检测）。
     *
     * 对于字面量直接取值；对于拼接表达式拼接所有字面量部分；其他情况返回表达式文本。
     *
     * @param expression 参数表达式
     * @return 提取的文本内容；无法提取时返回 null
     */
    @Nullable
    private static String extractArgumentText(@NotNull PsiExpression expression) {
        if (expression instanceof PsiLiteralExpression) {
            Object value = ((PsiLiteralExpression) expression).getValue();
            return value instanceof String ? (String) value : expression.getText();
        }
        if (expression instanceof PsiBinaryExpression || expression instanceof PsiPolyadicExpression) {
            List<String> parts = collectStringLiterals(expression);
            return parts.isEmpty() ? expression.getText() : String.join("", parts);
        }
        if (expression instanceof PsiReferenceExpression) {
            PsiReferenceExpression refExpr = (PsiReferenceExpression) expression;
            PsiElement resolved = refExpr.resolve();
            if (resolved instanceof PsiVariable) {
                PsiExpression initializer = ((PsiVariable) resolved).getInitializer();
                if (initializer instanceof PsiLiteralExpression) {
                    Object value = ((PsiLiteralExpression) initializer).getValue();
                    return value instanceof String ? (String) value : null;
                }
                return refExpr.getReferenceName();
            }
            return refExpr.getReferenceName();
        }
        return expression.getText();
    }

    /**
     * 递归收集 PSI 树中所有 PsiLiteralExpression 的字符串值。
     *
     * 用于处理字符串拼接表达式（"..." + "..."）和嵌套拼接。
     *
     * @param element 起始 PSI 元素
     * @return 收集到的字符串列表
     */
    @NotNull
    private static List<String> collectStringLiterals(@NotNull PsiElement element) {
        if (element instanceof PsiLiteralExpression) {
            Object value = ((PsiLiteralExpression) element).getValue();
            if (!(value instanceof String)) return List.of();
            return List.of((String) value);
        }
        List<String> result = new ArrayList<>();
        for (PsiElement child : element.getChildren()) {
            result.addAll(collectStringLiterals(child));
        }
        return result;
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
    @Nullable
    private static String extractEntityClassNameFromHql(@NotNull String hql) {
        // 优先匹配 FROM 子句（SELECT）
        Matcher fromMatch = FROM_ENTITY_PATTERN.matcher(hql);
        if (fromMatch.find()) return fromMatch.group(1);
        // 适配 UPDATE 语句：UPDATE EntityName SET ...
        Matcher updateMatch = UPDATE_ENTITY_PATTERN.matcher(hql);
        if (updateMatch.find()) return updateMatch.group(1);
        // 适配 DELETE 语句：DELETE FROM EntityName ...
        Matcher deleteMatch = DELETE_ENTITY_PATTERN.matcher(hql);
        if (deleteMatch.find()) return deleteMatch.group(1);
        return null;
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
    @NotNull
    private static List<String> extractNamedParams(@NotNull String hql) {
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = NAMED_PARAM_PATTERN.matcher(hql);
        while (matcher.find()) {
            seen.add(matcher.group(1));
        }
        return new ArrayList<>(seen);
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
    @NotNull
    private static ParamSource detectParamSource(@NotNull PsiMethodCallExpression call) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length <= 1) return ParamSource.NONE;

        List<PsiExpression> extraParams = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            extraParams.add(args[i]);
        }
        if (extraParams.isEmpty()) return ParamSource.NONE;
        if (isNameValuePairs(extraParams)) return ParamSource.NAME_VALUE_PAIRS;
        if (extraParams.size() == 1 && !(extraParams.get(0) instanceof PsiLiteralExpression)) return ParamSource.MAP;
        return ParamSource.POSITIONAL;
    }

    /**
     * 判断额外参数列表是否为 name-value 交替形式。
     *
     * 即：("name1", v1, "name2", v2) 模式——奇数索引参数为字符串字面量。
     *
     * @param extraParams HQL 参数之后的额外参数列表
     * @return 是否为 name-value 交替形式
     */
    private static boolean isNameValuePairs(@NotNull List<PsiExpression> extraParams) {
        if (extraParams.size() < 2) return false;
        // 检查偶数索引参数（0, 2, 4...）是否为字符串字面量（参数名）
        for (int i = 0; i < extraParams.size(); i += 2) {
            PsiExpression arg = extraParams.get(i);
            if (!(arg instanceof PsiLiteralExpression)) return false;
            Object value = ((PsiLiteralExpression) arg).getValue();
            if (!(value instanceof String)) return false;
        }
        return true;
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
    @NotNull
    private static List<HqlParameter> resolveParameters(@NotNull PsiMethodCallExpression call, @NotNull List<String> hqlParamNames) {
        if (hqlParamNames.isEmpty()) return List.of();

        ParamSource paramSource = detectParamSource(call);
        switch (paramSource) {
            case NAME_VALUE_PAIRS -> {
                Map<String, String> nameValueMap = extractNameValueMap(call);
                List<HqlParameter> list = new ArrayList<>();
                for (String paramName : hqlParamNames) {
                    String valueExpr = nameValueMap.getOrDefault(paramName, "");
                    list.add(new HqlParameter(paramName, valueExpr, false));
                }
                return list;
            }
            case MAP -> {
                // Map 传参：值表达式引用 Map 变量（第二个参数）
                PsiExpression[] args = call.getArgumentList().getExpressions();
                PsiExpression mapArg = args.length > 1 ? args[1] : null;
                String mapVarName;
                if (mapArg instanceof PsiReferenceExpression) {
                    String rn = ((PsiReferenceExpression) mapArg).getReferenceName();
                    mapVarName = rn != null ? rn : mapArg.getText();
                } else {
                    mapVarName = mapArg != null ? mapArg.getText() : "paramMap";
                }
                List<HqlParameter> list = new ArrayList<>();
                for (String paramName : hqlParamNames) {
                    list.add(new HqlParameter(paramName, mapVarName + ".get(\"" + paramName + "\")", false));
                }
                return list;
            }
            case POSITIONAL -> {
                // 位置参数：按索引顺序匹配
                PsiExpression[] args = call.getArgumentList().getExpressions();
                List<PsiExpression> extraParams = new ArrayList<>();
                for (int i = 1; i < args.length; i++) {
                    extraParams.add(args[i]);
                }
                List<HqlParameter> list = new ArrayList<>();
                for (int index = 0; index < hqlParamNames.size(); index++) {
                    String paramName = hqlParamNames.get(index);
                    String valueExpr = index < extraParams.size() ? extraParams.get(index).getText() : "";
                    list.add(new HqlParameter(paramName, valueExpr, true));
                }
                return list;
            }
            default -> {
                List<HqlParameter> list = new ArrayList<>();
                for (String paramName : hqlParamNames) {
                    list.add(new HqlParameter(paramName, "", false));
                }
                return list;
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
    @NotNull
    private static Map<String, String> extractNameValueMap(@NotNull PsiMethodCallExpression call) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length < 2) return Map.of();

        Map<String, String> result = new LinkedHashMap<>();
        List<PsiExpression> extraParams = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            extraParams.add(args[i]);
        }

        for (int i = 0; i + 1 < extraParams.size(); i += 2) {
            PsiExpression nameArg = extraParams.get(i);
            if (!(nameArg instanceof PsiLiteralExpression)) continue;
            Object value = ((PsiLiteralExpression) nameArg).getValue();
            if (!(value instanceof String)) continue;
            String paramName = (String) value;
            PsiExpression valueExpr = extraParams.get(i + 1);
            String valueText = valueExpr != null ? valueExpr.getText() : "";
            result.put(paramName, valueText);
        }

        return result;
    }

    /**
     * HQL 字符串提取结果（HQL 字符串 + 是否为纯字面量）。
     *
     * @param hql     HQL 字符串（纯字面量直接取值；变量引用或拼接时为拼接结果）；可能为 null
     * @param literal 是否为纯字面量（true=直接字面量；false=变量引用或拼接）
     */
    private static final class HqlStringResult {
    private final @Nullable String hql;
    private final boolean literal;

    public HqlStringResult(@Nullable String hql, boolean literal) {
        this.hql = hql;
        this.literal = literal;
    }

    public @Nullable String getHql() { return hql; }
    public @Nullable String hql() { return hql; }
    public boolean isLiteral() { return literal; }
    public boolean literal() { return literal; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HqlStringResult)) return false;
        HqlStringResult that = (HqlStringResult) o;
        return java.util.Objects.equals(this.hql, that.hql) && java.util.Objects.equals(this.literal, that.literal);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(hql, literal);
    }

    @Override
    public String toString() {
        return "HqlStringResult[" + "hql=" + hql + ", " + "literal=" + literal + "]";
    }
}
}
