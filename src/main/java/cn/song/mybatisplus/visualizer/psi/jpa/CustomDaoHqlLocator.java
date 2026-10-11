package cn.song.mybatisplus.visualizer.psi.jpa;

import cn.song.mybatisplus.visualizer.psi.PreviewReceiverResolver;
import com.intellij.psi.PsiArrayInitializerExpression;
import com.intellij.psi.PsiBinaryExpression;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
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
public final class CustomDaoHqlLocator {

    /** DAO 类名后缀集合。用于 qualifier 启发：方法调用的 qualifier 变量名以任一后缀结尾即视为 DAO 对象。 */
    private static final Set<String> DAO_QUALIFIER_SUFFIXES = Set.of(
            "Dao", "DaoImpl", "Mapper", "Repository", "CommonDao", "DaoHelper", "BaseDao"
    );

    /**
     * 查询方法名关键字集合（T-GutterDedup：仅"具体查询"关键字）。
     *
     * 用于方法名启发：方法名包含任一关键字即视为潜在查询方法。通用名 `execute` 已移出——
     * 它被 JdbcTemplate / HibernateTemplate 复用，单靠方法名会跨家族误标；`execute` 现在
     * 必须由 DAO qualifier（matchesDaoQualifier）或 HIBERNATE_TEMPLATE 接收者门控放行。
     */
    private static final Set<String> QUERY_METHOD_KEYWORDS = Set.of(
            "Query", "Hql", "Jpql", "findByQuery", "findBySql"
    );

    /**
     * HibernateTemplate 的 HQL 字符串查询方法名（T-GutterDedup：补齐缺失图标）。
     *
     * 接收者为 PreviewReceiverResolver.Family.HIBERNATE_TEMPLATE 且方法名在此集合、首参为字符串时，
     * 由本定位器认领（否则 `getHibernateTemplate().find/findByNamedParam/findByValueBean` 无预览图标）。
     */
    private static final Set<String> HIBERNATE_HQL_METHODS = Set.of(
            "find", "findByNamedParam", "findByValueBean", "iterate", "bulkUpdate"
    );

    /**
     * T-J12：HibernateTemplate 的命名查询方法名（首参是**查询名**字符串而非 HQL）。
     *
     * <p>{@code findByNamedQuery} / {@code findByNamedQueryAndNamedParam} / {@code findByNamedQueryAndValueBean}。
     * 认领后 kind = NAMED_QUERY、hql 字段存查询名、entityClassName 恒为 null；
     * SqlPreviewService 按名回查 NamedQueryLocator.findByQueryName。
     */
    private static final Set<String> HIBERNATE_NAMED_QUERY_METHODS = Set.of(
            "findByNamedQuery", "findByNamedQueryAndNamedParam", "findByNamedQueryAndValueBean"
    );

    /**
     * T-J12：HibernateTemplate 的 QBC 形态方法名（**仅登记名单，不认领**）。
     *
     * <p>{@code executeFind} 的实参是 DetachedCriteria（无 SQL 字符串），
     * 本定位器不认领（locate 返回 null）、标记「QBC 形态」占位交给后续里程碑；不得误抛。
     * 经 {@link #classifyHibernateTemplateMethod} 暴露类别供查询。
     * {@code findByCriteria} 按 T-J21 归入 HIBERNATE_ENTITY_OP_METHODS（两名单交集清干净）。
     */
    private static final Set<String> HIBERNATE_QBC_METHODS = Set.of("executeFind");

    /**
     * T-J21：HibernateTemplate 实体操作方法名（**仅登记名单，不认领**）。
     *
     * <p>save/saveOrUpdate/update/delete/deleteAll/get/load/loadAll/refresh/merge/persist/
     * replicate/lock/findByExample/findByCriteria——均无 SQL 字符串实参。
     * 这些名字不会绕过现有 HQL 内容启发（CUSTOM_DAO/UNKNOWN 家族仍需内容匹配 +
     * DAO qualifier/查询关键字双重门控）。
     */
    private static final Set<String> HIBERNATE_ENTITY_OP_METHODS = Set.of(
            "save", "saveOrUpdate", "update", "delete", "deleteAll",
            "get", "load", "loadAll", "refresh", "merge", "persist", "replicate", "lock",
            "findByExample", "findByCriteria"
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
        // T-J12：HibernateTemplate 家族按类别名单标记（NAMED_QUERY 时 hql 字段为查询名、
        // entityClassName 恒为 null——FROM/UPDATE/DELETE 模式对查询名不匹配）；其余家族恒为 HQL。
        DaoQueryKind kind = PreviewReceiverResolver.classify(call) == PreviewReceiverResolver.Family.HIBERNATE_TEMPLATE
                ? java.util.Objects.requireNonNullElse(
                        classifyHibernateTemplateMethod(call.getMethodExpression().getReferenceName()),
                        DaoQueryKind.HQL)
                : DaoQueryKind.HQL;

        return new CustomDaoHqlInfo(hql, result.literal(), entityClassName, parameters, method, paramSource, kind);
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
    private static boolean isCustomDaoHqlCall(@NotNull PsiMethodCallExpression call) {
        switch (PreviewReceiverResolver.classify(call)) {
            // JDBC 模板（queryForList/update/execute 等）与 JPA 原生（Session/EntityManager）交给各自 provider
            case JDBC_TEMPLATE:
            case JPA_SESSION:
                return false;
            // HibernateTemplate：按 T-J12/T-J21 类别名单分派——仅 HQL 字符串承载（find/.../bulkUpdate）
            // 与命名查询名承载（findByNamedQuery...）认领，且要求首参为字符串形态（`execute(callback)`
            // 非字符串实参不认领）；QBC / 实体操作无 SQL 字符串，仅登记类别、不认领。
            case HIBERNATE_TEMPLATE: {
                DaoQueryKind kind = classifyHibernateTemplateMethod(call.getMethodExpression().getReferenceName());
                return (kind == DaoQueryKind.HQL || kind == DaoQueryKind.NAMED_QUERY)
                        && PreviewReceiverResolver.isStringLikeFirstArg(call);
            }
            // 自定义 DAO / 无法判定：T-Perf-02 判定顺序反转——先 O(1) 名字启发（qualifier 名 →
            // 方法名），命中后才跑 HQL 内容正则；名字不命中零正则开销。布尔语义不变（AND 可交换）。
            default:
                return (matchesDaoQualifier(call) || matchesQueryMethodName(call)) && matchesHqlContent(call);
        }
    }

    /**
     * T-J12/T-J21：按方法名归类 HibernateTemplate 家族调用的查询承载类别。
     *
     * <p><b>登记名单入口</b>（SqlPreviewService / 后续里程碑查询用）：
     * <ul>
     *   <li>HQL：find / findByNamedParam / findByValueBean / iterate / bulkUpdate（首参 HQL 字符串）；</li>
     *   <li>NAMED_QUERY：findByNamedQuery / findByNamedQueryAndNamedParam / findByNamedQueryAndValueBean；</li>
     *   <li>QBC：executeFind（DetachedCriteria 实参，占位；findByCriteria 按 T-J21 归类 ENTITY_OP）；</li>
     *   <li>ENTITY_OP：save/update/delete 等实体操作（无 SQL 字符串）。</li>
     * </ul>
     *
     * @param methodName 方法调用名
     * @return 类别；不在任何名单返回 null（调用方按未知方法处理，不认领）
     */
    public static DaoQueryKind classifyHibernateTemplateMethod(@Nullable String methodName) {
        if (methodName == null) return null;
        if (HIBERNATE_HQL_METHODS.contains(methodName)) return DaoQueryKind.HQL;
        if (HIBERNATE_NAMED_QUERY_METHODS.contains(methodName)) return DaoQueryKind.NAMED_QUERY;
        if (HIBERNATE_QBC_METHODS.contains(methodName)) return DaoQueryKind.QBC;
        if (HIBERNATE_ENTITY_OP_METHODS.contains(methodName)) return DaoQueryKind.ENTITY_OP;
        return null;
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
     * <p>T-GutterDupFreeze（S1）：<b>首参必须是字符串承载式</b>（String 字面量 / {@code +} 拼接 / 解析为
     * String 的变量引用 / {@code xx.toString()}），先于内容正则判定。此前无此门控，非字符串表达式
     * （典型＝wrapper 调用链）会被 {@link #extractArgumentText} 兜底成<b>整条链的源码文本</b>，
     * 链内嵌的 SQL 串（{@code .exists("select 1 from t_order ...")} / {@code .apply} / {@code .inSql} / {@code .last}）
     * 令 {@code SELECT...FROM} 正则命中——纯 MyBatis-Plus 的 {@code userMapper.selectList(<wrapper 链>)}
     * 因此被误认成自定义 DAO HQL 调用，凭空亮出预览图标（20261008 用户真机实证）。
     * 与 HIBERNATE_TEMPLATE 家族同款门控（{@link PreviewReceiverResolver#isStringLikeFirstArg}），
     * 属既有口径对齐而非常规收紧。</p>
     *
     * @param call 目标方法调用
     * @return 字符串参数是否匹配 HQL 内容模式
     */
    private static boolean matchesHqlContent(@NotNull PsiMethodCallExpression call) {
        if (!PreviewReceiverResolver.isStringLikeFirstArg(call)) return false;
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
            // T-GutterDupFreeze（S1）：全无字面量部分（`a + b` 形态）静态不可求值 →
            // 不得回退成表达式源码文本充当 HQL（源码文本不是 SQL）
            return new HqlStringResult(null, false);
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
        // T-GutterDupFreeze（S1）：兜底分支原为 `expression.getText() to false`——把**任意表达式的
        // 源码文本**当字符串返回，是「wrapper 链源码被当成 HQL」这一误判的最终落点（链、new、
        // toString() 调用等都会走到这里）。改为「无法静态取得字符串」= 不是 HQL 承载：
        // 上游 matchesHqlContent 因 hql 为空判否、locateFromCall 亦不产出 info。
        return new HqlStringResult(null, false);
    }

    /**
     * 从参数表达式提取文本内容（用于内容启发检测）。
     *
     * 与 extractHqlString 共用同一提取逻辑（单一来源）：字面量直接取值；拼接表达式拼接
     * 所有字面量部分；变量引用递归解析初始化式（含拼接初始化式）。此前的独立实现只处理
     * 字面量初始化式，拼接变量回退为变量名导致内容启发误判（2026-09-03 用户反馈
     * `String hql = "select" + " ..."` 后传入自定义 DAO 无预览图标，根因即两套逻辑分叉）。
     *
     * @param expression 参数表达式
     * @return 提取的文本内容；无法提取时返回 null
     */
    @Nullable
    private static String extractArgumentText(@NotNull PsiExpression expression) {
        return extractHqlString(expression).hql();
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
     * 从 HQL 字符串提取实体类名（T-HqlUpdateEntityMap S1 起收敛到共享提取器）。
     *
     * 口径由本类先例的「FROM 优先」统一为语句头优先（UPDATE/DELETE 头先行）：
     * 无 FROM 子句的 UPDATE 同样命中，且 `UPDATE ... SET x = (SELECT ... FROM y)`
     * 不再误命中子查询实体；SELECT / 裸 from 行为不变。
     *
     * @param hql HQL 字符串
     * @return 实体类名；未匹配到时返回 null
     */
    @Nullable
    private static String extractEntityClassNameFromHql(@NotNull String hql) {
        return HqlEntityNameExtractor.extract(hql);
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
        if (isNameValueArrays(extraParams)) return ParamSource.NAME_VALUE_ARRAYS;
        if (extraParams.size() == 1 && !(extraParams.get(0) instanceof PsiLiteralExpression)) return ParamSource.MAP;
        return ParamSource.POSITIONAL;
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
    private static boolean isNameValueArrays(@NotNull List<PsiExpression> extraParams) {
        if (extraParams.size() < 2) return false;
        if (!(extraParams.get(0) instanceof PsiNewExpression)) return false;
        PsiArrayInitializerExpression namesInitializer = ((PsiNewExpression) extraParams.get(0)).getArrayInitializer();
        if (namesInitializer == null) return false;
        PsiExpression[] elements = namesInitializer.getInitializers();
        if (elements.length == 0) return false;
        // 名字数组元素必须全部是字符串字面量（参数名），排除 Object[] 等值数组误判
        for (PsiExpression element : elements) {
            if (!(element instanceof PsiLiteralExpression)) return false;
            if (!(((PsiLiteralExpression) element).getValue() instanceof String)) return false;
        }
        return true;
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
     *  - NAME_VALUE_ARRAYS：名字数组 + 值数组，按名匹配、按索引取值
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
            case NAME_VALUE_ARRAYS -> {
                // 名字数组 + 值数组（老式 DAO）：如 findByQueryUniqueResult(hql,
                // new String[]{"warehouseId","startTime"}, new Object[]{warehouseId, startTime})
                PsiExpression[] allArgs = call.getArgumentList().getExpressions();
                List<PsiExpression> extraParams = new ArrayList<>();
                for (int i = 1; i < allArgs.length; i++) {
                    extraParams.add(allArgs[i]);
                }
                List<String> names = new ArrayList<>();
                if (!extraParams.isEmpty()
                        && extraParams.get(0) instanceof PsiNewExpression namesArg
                        && namesArg.getArrayInitializer() != null) {
                    for (PsiExpression element : namesArg.getArrayInitializer().getInitializers()) {
                        if (element instanceof PsiLiteralExpression literal
                                && literal.getValue() instanceof String s) {
                            names.add(s);
                        }
                    }
                }
                List<String> valueTexts = new ArrayList<>();
                if (extraParams.size() > 1
                        && extraParams.get(1) instanceof PsiNewExpression valuesArg
                        && valuesArg.getArrayInitializer() != null) {
                    for (PsiExpression element : valuesArg.getArrayInitializer().getInitializers()) {
                        valueTexts.add(element.getText());
                    }
                }
                List<HqlParameter> list = new ArrayList<>();
                for (String paramName : hqlParamNames) {
                    int index = names.indexOf(paramName);
                    String valueExpr = index >= 0 && index < valueTexts.size() ? valueTexts.get(index) : "";
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
