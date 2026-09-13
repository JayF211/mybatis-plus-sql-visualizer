package cn.song.mybatisplus.visualizer.psi.jpa;

import cn.song.mybatisplus.visualizer.model.JoinType;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassObjectAccessExpression;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * JPA CriteriaQuery / CriteriaUpdate / CriteriaDelete 定位器（F-JPA-02，T-J4/T-J6/T-J7/T-J17）。
 *
 * 从光标位置识别 JPA CriteriaBuilder 强类型查询的 PSI 调用链，构建 [CriteriaQueryModel] 中间表示。
 *
 * 识别模式：
 *  - `cb.createQuery(Xxx.class)` / `cb.createCriteriaUpdate(Xxx.class)` / `cb.createCriteriaDelete(Xxx.class)`
 *  - `cq.from(Xxx.class)` / `update.from(Xxx.class)` 设置根实体
 *  - `root.get("fieldName")` 或 `root.get(Xxx_.fieldName)` 引用列
 *  - `cb.equal/gt/ge/lt/le/isTrue/isFalse/in/like/...` 构建条件（清单见 [CB_CONDITION_METHODS]）
 *  - `cb.lower/upper/concat/abs/cast/coalesce/selectCase...` 表达式（见 [CB_EXPRESSION_FUNCTIONS]）
 *  - `cq.where / groupBy / having / orderBy / multiselect / select / distinct` 各子句
 *  - `root.join/joinCollection/joinSet/joinList/joinMap/fetch` + `Join.on(...)` 显式 ON
 *  - `query.subquery(...)` + `sq.from/select/where` + `cb.exists(sq)` / `path.get("x").in(sq)`
 *  - `update.set(path, value)` / `update.set("attr", value)` SET 子句
 *
 * 定位策略：
 *  1. 从光标向上查找包含 CriteriaQuery 相关调用的代码块或方法体；
 *  2. 扫描范围内全部 PsiMethodCallExpression；
 *  3. 识别 `createQuery` / `from` / `where` 等调用并提取中间模型。
 *
 * 无法静态解析的能力一律降级为 Raw/注释占位，绝不抛异常。
 */
public final class CriteriaQueryLocator {

    // T-Perf-23：循环内 Set 字面量提为常量
    private static final Set<String> CQ_SELECT_METHODS = Set.of("multiselect", "select");

    // CriteriaQuery 子句方法名。
    // T-J4 清理：原名单中的 getRestriction 是死条目——它只是谓词读取 API，从不参与查询构建，已移除。
    private static final Set<String> CRITERIA_QUERY_METHODS = Set.of(
            "from", "select", "multiselect", "where", "orderBy",
            "groupBy", "having", "distinct"
    );

    // CriteriaBuilder 条件方法名。
    // T-J4 清理：原名单中的 notIn 是死条目——jakarta persistence 3.1 的 CriteriaBuilder 并无该方法
    // （javap 反编译证实），NOT IN 由 `CriteriaBuilder.In.value(...).not()` 或 Expression.not() 表达，
    // 链式 .not() 在 buildIn 中识别。
    // T-J17 补齐：gt/ge/lt/le（Number 变体）、isTrue/isFalse、conjunction/disjunction、
    // exists、isEmpty/isNotEmpty 与 isMember/isNotMember（后四者渲染降级）。
    private static final Set<String> CB_CONDITION_METHODS = Set.of(
            "equal", "notEqual",
            "greaterThan", "greaterThanOrEqualTo",
            "lessThan", "lessThanOrEqualTo",
            "gt", "ge", "lt", "le",
            "between", "isNull", "isNotNull",
            "isTrue", "isFalse",
            "like", "notLike", "in",
            "conjunction", "disjunction",
            "and", "or", "not",
            "exists",
            "isEmpty", "isNotEmpty", "isMember", "isNotMember"
    );

    // CriteriaBuilder 排序辅助方法
    private static final Set<String> CB_ORDER_METHODS = Set.of("asc", "desc");

    // SELECT 聚合函数名（T-J17 补充的 countDistinct/sumAsLong/sumAsDouble/greatest/least 单独分支处理）
    private static final Set<String> AGGREGATE_FUNCTIONS = Set.of("COUNT", "SUM", "MAX", "MIN", "AVG");

    // CriteriaBuilder 表达式函数名（T-J17）：lower/upper/concat/substring/length/locate/trim、
    // abs/ceiling/floor/round/exp/ln/power/sqrt/sign、sum/prod/diff/quot/mod/neg、
    // toLong/.../toString、coalesce/nullif、function、count、size
    private static final Set<String> CB_EXPRESSION_FUNCTIONS = Set.of(
            "lower", "upper", "concat", "substring", "length", "locate", "trim",
            "abs", "ceiling", "floor", "round", "exp", "ln", "power", "sqrt", "sign",
            "sum", "prod", "diff", "quot", "mod", "neg",
            "toLong", "toInteger", "toFloat", "toDouble", "toBigDecimal", "toBigInteger", "toString",
            "coalesce", "nullif",
            "currentDate", "currentTime", "currentTimestamp",
            "function", "count", "size"
    );

    // 转换函数 → CAST 目标类型（统一 SQL 标准型，方言差异不处理）
    private static final Map<String, String> CAST_TYPE_MAP = Map.of(
            "toLong", "BIGINT",
            "toInteger", "INT",
            "toFloat", "FLOAT",
            "toDouble", "DOUBLE",
            "toBigDecimal", "DECIMAL",
            "toBigInteger", "BIGINT",
            "toString", "VARCHAR"
    );

    // Trimspec 枚举名
    private static final Set<String> TRIM_SPECS = Set.of("LEADING", "TRAILING", "BOTH");

    // join/fetch 识别方法名（T-J7：joinCollection/joinSet/joinList/joinMap 按 join 同路径识别）
    private static final Set<String> JOIN_METHODS = Set.of("join", "joinCollection", "joinSet", "joinList", "joinMap");

    // 关联属性 join 类型枚举名（join(String) 无 JoinType 实参时按 JPA 规范默认 INNER，T-J7）
    private static final Set<String> JOIN_TYPE_LEFT = Set.of("LEFT", "Left");
    private static final Set<String> JOIN_TYPE_INNER = Set.of("INNER", "Inner");
    private static final Set<String> JOIN_TYPE_RIGHT = Set.of("RIGHT", "Right");

    // 谓词提取的宿主方法（这些调用的实参由宿主分支负责提取，扫描循环跳过避免重复）。
    // on 的实参由 Join.on 后处理收集；add 的实参由列表变量解引用收集，同样属于宿主。
    private static final Set<String> PREDICATE_HOST_METHODS = Set.of("and", "or", "not", "where", "having", "on", "add");

    /** 单例（保持对 Kotlin `CriteriaQueryLocator.method()` 调用的二进制兼容）。 */
    public static final CriteriaQueryLocator INSTANCE = new CriteriaQueryLocator();

    private CriteriaQueryLocator() {
    }

    /**
     * 单次 locate 的上下文：根/join/谓词变量与子查询登记表。
     * 变量解析依赖 PSI 源码顺序（声明先于使用），乱序代码按降级处理。
     */
    private static final class LocateContext {
        final @NotNull PsiElement block;
        /** root 变量名（cq.from 赋值目标）。 */
        final @NotNull Set<String> roots = new LinkedHashSet<>();
        /** 全部 join/fetch 记录（含内联未赋值形态），按发现顺序。 */
        final @NotNull List<WorkingJoin> allJoins = new ArrayList<>();
        /** join/fetch 变量名 → join 记录。 */
        final @NotNull Map<String, WorkingJoin> joinVars = new LinkedHashMap<>();
        /** 谓词变量名 → 已提取模型（Predicate p = cb.equal(...) 的变量追踪）。 */
        final @NotNull Map<String, PredicateModel> predicateVars = new LinkedHashMap<>();
        /** 子查询变量名 → 子查询模型。 */
        final @NotNull Map<String, SubqueryModel> subqueries = new LinkedHashMap<>();
        /** 子查询 from 引入的 root 变量名。 */
        final @NotNull Set<String> subRoots = new LinkedHashSet<>();

        LocateContext(@NotNull PsiElement block) {
            this.block = block;
        }
    }

    /** join 提取的临时记录：显式 ON 谓词按调用序收集，最终物化为 [JoinModel]。 */
    private static final class WorkingJoin {
        final @NotNull String callText;
        final @NotNull String fieldName;
        final @NotNull JoinType joinType;
        final boolean fetch;
        final @Nullable String variableName;
        final @NotNull List<PredicateModel> onPredicates = new ArrayList<>();

        WorkingJoin(@NotNull String callText, @NotNull String fieldName, @NotNull JoinType joinType,
                    boolean fetch, @Nullable String variableName) {
            this.callText = callText;
            this.fieldName = fieldName;
            this.joinType = joinType;
            this.fetch = fetch;
            this.variableName = variableName;
        }

        JoinModel toModel() {
            return new JoinModel(fieldName, null, joinType, null, fetch,
                    new ArrayList<>(onPredicates), variableName);
        }
    }

    /**
     * 从光标位置定位 CriteriaQuery 调用链并构建中间模型。
     *
     * @param anchor 光标所在 PSI 元素
     * @return CriteriaQueryModel；未识别到 CriteriaQuery 时返回 null
     */
    @Nullable
    public static CriteriaQueryModel locate(@NotNull PsiElement anchor) {
        PsiElement block = findRelevantBlock(anchor);
        if (block == null) return null;
        List<PsiMethodCallExpression> allCalls = new ArrayList<>(
                PsiTreeUtil.collectElementsOfType(block, PsiMethodCallExpression.class));
        LocateContext ctx = new LocateContext(block);

        PsiClass entityClass = null;
        String entityClassName = null;
        String resultClassName = null;
        CriteriaOperation operation = CriteriaOperation.SELECT;
        boolean distinct = false;
        Set<String> criteriaQueryVars = new LinkedHashSet<>();
        List<PredicateModel> predicates = new ArrayList<>();
        List<GroupByModel> groupBys = new ArrayList<>();
        List<PredicateModel> havings = new ArrayList<>();
        List<OrderByModel> orderBys = new ArrayList<>();
        List<SelectColumn> selectColumns = new ArrayList<>();
        List<CriteriaSetClause> setClauses = new ArrayList<>();
        // Join.on(...) 调用后处理：PSI 遍历外层调用先于内层 join 登记，须在扫描结束后再解析目标
        List<PsiMethodCallExpression> pendingOnCalls = new ArrayList<>();

        // 第一遍：识别 createQuery / createCriteriaUpdate / createCriteriaDelete。
        // 关键：创建调用的**赋值目标**才是 CriteriaQuery/Update/Delete 变量，而非 qualifier（那是 CriteriaBuilder）
        for (PsiMethodCallExpression call : allCalls) {
            String methodName = call.getMethodExpression().getReferenceName();
            if (methodName == null) continue;
            String assignedVar = findAssignedVariable(call);
            switch (methodName) {
                case "createQuery":
                    if (resultClassName == null) resultClassName = extractResultClass(call);
                    if (assignedVar != null) criteriaQueryVars.add(assignedVar);
                    break;
                case "createCriteriaUpdate":
                    operation = CriteriaOperation.UPDATE;
                    if (resultClassName == null) resultClassName = extractResultClass(call);
                    if (assignedVar != null) criteriaQueryVars.add(assignedVar);
                    break;
                case "createCriteriaDelete":
                    operation = CriteriaOperation.DELETE;
                    if (resultClassName == null) resultClassName = extractResultClass(call);
                    if (assignedVar != null) criteriaQueryVars.add(assignedVar);
                    break;
                default:
                    break;
            }
        }

        // 未识别到 CriteriaQuery 变量时尝试从 from 调用推断（`cq.from(...)` 的 qualifier）
        if (criteriaQueryVars.isEmpty()) {
            for (PsiMethodCallExpression call : allCalls) {
                String methodName = call.getMethodExpression().getReferenceName();
                if (methodName == null) continue;
                if (!methodName.equals("from")) continue;
                PsiExpression qualifierExpr = call.getMethodExpression().getQualifierExpression();
                if (qualifierExpr == null) continue;
                criteriaQueryVars.add(qualifierExpr.getText());
            }
        }

        // 仍无 CriteriaQuery 变量 → 不是 Criteria 调用链
        if (criteriaQueryVars.isEmpty()) return null;

        // 子查询登记（T-J17/J13）：`Subquery<T> sq = query.subquery(...)` → 变量名 → 模型
        collectSubqueries(allCalls, criteriaQueryVars, ctx);

        // 第二遍：收集各子句
        for (PsiMethodCallExpression call : allCalls) {
            String methodName = call.getMethodExpression().getReferenceName();
            if (methodName == null) continue;
            PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
            if (qualifier == null) continue;
            String qualifierText = qualifier.getText();

            if (methodName.equals("from") && criteriaQueryVars.contains(qualifierText)) {
                // cq.from(Xxx.class) / update.from(Xxx.class)
                PsiExpression[] fromArgs = call.getArgumentList().getExpressions();
                PsiExpression fromArg = fromArgs.length > 0 ? fromArgs[0] : null;
                entityClass = extractEntityClass(fromArg);
                if (entityClassName == null) entityClassName = extractEntityClassName(fromArg);
                // Root 变量：from 调用的直接赋值目标
                String rootVar = findAssignedVariable(call);
                if (rootVar != null) ctx.roots.add(rootVar);
            } else if (methodName.equals("where") && criteriaQueryVars.contains(qualifierText)) {
                // cq.where(predicate...)
                for (PsiExpression arg : call.getArgumentList().getExpressions()) {
                    PredicateModel p = extractPredicate(arg, ctx);
                    if (p != null) predicates.add(p);
                }
            } else if (methodName.equals("groupBy") && criteriaQueryVars.contains(qualifierText)) {
                // cq.groupBy(root.get("field")) / cq.groupBy(List)
                for (PsiExpression arg : call.getArgumentList().getExpressions()) {
                    for (PsiExpression item : expandListArgument(arg, ctx)) {
                        GroupByModel g = extractGroupBy(item);
                        if (g != null) groupBys.add(g);
                    }
                }
            } else if (methodName.equals("having") && criteriaQueryVars.contains(qualifierText)) {
                // cq.having(predicate...)
                for (PsiExpression arg : call.getArgumentList().getExpressions()) {
                    PredicateModel p = extractPredicate(arg, ctx);
                    if (p != null) havings.add(p);
                }
            } else if (methodName.equals("orderBy") && criteriaQueryVars.contains(qualifierText)) {
                // cq.orderBy(cb.asc(...)) / cq.orderBy(List)
                for (PsiExpression arg : call.getArgumentList().getExpressions()) {
                    for (PsiExpression item : expandListArgument(arg, ctx)) {
                        OrderByModel o = extractOrderBy(item);
                        if (o != null) orderBys.add(o);
                    }
                }
            } else if (CQ_SELECT_METHODS.contains(methodName) && criteriaQueryVars.contains(qualifierText)) {
                // cq.multiselect(...) / cq.select(...)
                selectColumns.addAll(extractSelectColumns(call, ctx));
            } else if (methodName.equals("distinct") && criteriaQueryVars.contains(qualifierText)) {
                // cq.distinct(true)
                PsiExpression[] dArgs = call.getArgumentList().getExpressions();
                PsiExpression arg = dArgs.length > 0 ? dArgs[0] : null;
                if (arg == null || arg.getText().equals("true")) distinct = true;
            } else if (JOIN_METHODS.contains(methodName) && ctx.roots.contains(qualifierText)) {
                // root.join(...) / root.fetch(...)（T-J7）
                registerJoin(call, call, false, ctx);
            } else if (methodName.equals("fetch") && ctx.roots.contains(qualifierText)) {
                registerJoin(call, call, true, ctx);
            } else if (methodName.equals("on")) {
                // Join.on(...)：显式 ON 优先于外键推断（T-J7）；PSI 遍历外层先于内层 join，先挂起后处理
                pendingOnCalls.add(call);
            } else if (methodName.equals("set") && criteriaQueryVars.contains(qualifierText)
                    && operation == CriteriaOperation.UPDATE) {
                // update.set(root.get("name"), v) / update.set("name", v)（T-J6）
                CriteriaSetClause clause = extractSetClause(call, false);
                if (clause != null) setClauses.add(clause);
            } else if (methodName.equals("setNull") && criteriaQueryVars.contains(qualifierText)
                    && operation == CriteriaOperation.UPDATE) {
                // 防御：个别包装 API 提供 setNull 形态，按 set(path, null) 语义处理
                CriteriaSetClause clause = extractSetClause(call, true);
                if (clause != null) setClauses.add(clause);
            } else if (methodName.equals("in")
                    && qualifier instanceof PsiMethodCallExpression qualifierCall
                    && "get".equals(qualifierCall.getMethodExpression().getReferenceName())) {
                // Expression.in(...)：path.get("x").in(v1, v2) / .in(collection) / .in(subquery)（T-J17/J13）
                PredicateModel model = extractExpressionIn(call, qualifierCall, ctx);
                if (model != null) {
                    predicates.add(model);
                    String v = findAssignedVariable(call);
                    if (v != null) ctx.predicateVars.put(v, model);
                }
            } else if (CB_CONDITION_METHODS.contains(methodName) && isCriteriaBuilderCall(call)) {
                // CriteriaBuilder 条件方法（cb.equal / cb.greaterThan / cb.gt ...）
                // 排除作为 and/or/not/where/having 实参的嵌套调用（宿主分支负责提取，避免重复）
                if (!isNestedInHostPredicate(call)) {
                    recordPredicate(predicates, call, extractPredicateFromCall(call, ctx), ctx);
                }
            } else if (methodName.equals("not") && qualifier instanceof PsiMethodCallExpression) {
                // 链式取反：cb.equal(...).not() / cb.exists(sq).not()（qualifier 为谓词调用）
                if (!isNestedInHostPredicate(call)) {
                    recordPredicate(predicates, call, extractPredicateFromCall(call, ctx), ctx);
                }
            }
        }

        // Join.on 后处理：join 变量 / 内联 join 调用文本匹配显式 ON 谓词
        for (PsiMethodCallExpression onCall : pendingOnCalls) {
            PsiExpression q = onCall.getMethodExpression().getQualifierExpression();
            WorkingJoin target = null;
            if (q instanceof PsiReferenceExpression qRef) {
                target = ctx.joinVars.get(qRef.getReferenceName());
            } else if (q instanceof PsiMethodCallExpression qCall) {
                String qText = qCall.getText();
                for (int i = ctx.allJoins.size() - 1; i >= 0; i--) {
                    WorkingJoin candidate = ctx.allJoins.get(i);
                    if (candidate.callText.equals(qText)) {
                        target = candidate;
                        break;
                    }
                }
            }
            if (target == null) continue;
            for (PsiExpression arg : onCall.getArgumentList().getExpressions()) {
                PredicateModel p = extractPredicate(arg, ctx);
                if (p != null) target.onPredicates.add(p);
            }
        }

        List<JoinModel> joins = new ArrayList<>();
        for (WorkingJoin workingJoin : collectWorkingJoins(ctx)) {
            joins.add(workingJoin.toModel());
        }

        // 没有实体信息且无条件/SET → 不是可渲染的 Criteria 调用链
        if (entityClassName == null && predicates.isEmpty() && setClauses.isEmpty()) return null;

        return new CriteriaQueryModel(
                entityClassName,
                entityClass,
                selectColumns,
                predicates,
                groupBys,
                havings,
                orderBys,
                joins,
                resultClassName,
                distinct,
                operation,
                setClauses
        );
    }

    // ------------------------------------------------------------------
    // 作用域查找
    // ------------------------------------------------------------------

    /**
     * 从锚点向上查找包含 CriteriaQuery 调用的代码块或方法体。
     */
    @Nullable
    private static PsiElement findRelevantBlock(@NotNull PsiElement anchor) {
        PsiElement current = anchor;
        while (current != null) {
            if (current instanceof PsiCodeBlock) return current;
            if (current instanceof PsiMethod method) return method.getBody();
            // 已到文件顶层：以文件为范围
            if (current instanceof PsiFile) return current;
            current = current.getParent();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 变量追踪
    // ------------------------------------------------------------------

    /**
     * 查找方法调用被赋值给的局部变量名（如 `Root<X> root = cq.from(...)` 的 root）。
     */
    @Nullable
    private static String findAssignedVariable(@NotNull PsiMethodCallExpression call) {
        PsiElement parent = call.getParent();
        if (parent == null) return null;
        // 局部变量声明：Type var = call;
        if (parent instanceof PsiLocalVariable
                && ((PsiLocalVariable) parent).getInitializer() == call) {
            return ((PsiLocalVariable) parent).getName();
        }
        // 赋值语句：var = call;
        if (parent instanceof PsiAssignmentExpression) {
            PsiExpression lhs = ((PsiAssignmentExpression) parent).getLExpression();
            if (lhs instanceof PsiReferenceExpression) {
                return ((PsiReferenceExpression) lhs).getReferenceName();
            }
            return null;
        }
        return null;
    }

    /**
     * 判断方法调用是否为 cb.xxx() 形式（qualifier 为简单引用）。
     *
     * 由于 CriteriaBuilder 变量类型通常不可 resolve（LightPlatform 无 JPA classpath），
     * 这里采用启发式：方法名属于 CB 条件方法集，且 qualifier 是简单标识符引用。
     */
    private static boolean isCriteriaBuilderCall(@NotNull PsiMethodCallExpression call) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) return false;
        if (!CB_CONDITION_METHODS.contains(methodName)) return false;
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (qualifier == null) return false;
        // 简单引用表达式（cb.equal(...) 中 qualifier 是 cb）
        return qualifier instanceof PsiReferenceExpression;
    }

    /**
     * 检查 call 是否嵌套在负责谓词提取的宿主调用内（and/or/not/where/having 的实参位置）。
     * 嵌套调用会在处理宿主时被提取，扫描循环跳过以避免重复入列。
     */
    private static boolean isNestedInHostPredicate(@NotNull PsiMethodCallExpression call) {
        PsiElement parent = call.getParent();
        while (parent != null) {
            if (parent instanceof PsiExpressionList) {
                // 实参位置：argumentList → 宿主调用（链式调用要爬过多层才抵达，循环内处理）
                parent = parent.getParent();
            } else if (parent instanceof PsiReferenceExpression) {
                // 链式位置：cb.equal(...).not() 中 equal 的 parent 是引用表达式 → 宿主调用
                parent = parent.getParent();
            } else if (parent instanceof PsiMethodCallExpression parentCall
                    && "value".equals(parentCall.getMethodExpression().getReferenceName())) {
                // value() 是透明链层：cb.in(x).value(1).value(2) 经两层 value 抵达 where 宿主
                parent = parent.getParent();
            } else {
                break;
            }
        }
        if (!(parent instanceof PsiMethodCallExpression parentCall)) return false;
        String parentName = parentCall.getMethodExpression().getReferenceName();
        if (parentName == null) return false;
        return PREDICATE_HOST_METHODS.contains(parentName);
    }

    /** 记录条件模型；调用若赋值给变量则同步登记，供 and/or 组与 where 实参引用解析。 */
    private static void recordPredicate(@NotNull List<PredicateModel> sink,
                                        @NotNull PsiMethodCallExpression call,
                                        @Nullable PredicateModel model,
                                        @NotNull LocateContext ctx) {
        if (model == null) return;
        sink.add(model);
        String v = findAssignedVariable(call);
        if (v != null) ctx.predicateVars.put(v, model);
    }

    // ------------------------------------------------------------------
    // 实体类解析
    // ------------------------------------------------------------------

    /**
     * 从 cq.from(Xxx.class) 或 cb.createQuery(Xxx.class) 的实参解析 PsiClass。
     */
    @Nullable
    private static PsiClass extractEntityClass(@Nullable PsiExpression expr) {
        if (expr == null) return null;
        // Xxx.class → PsiClassObjectAccessExpression
        if (expr instanceof PsiClassObjectAccessExpression) {
            PsiType type = ((PsiClassObjectAccessExpression) expr).getOperand().getType();
            if (type instanceof PsiClassType) {
                return ((PsiClassType) type).resolve();
            }
        }
        return null;
    }

    /**
     * 从 from/createQuery 实参提取实体类名（不可 resolve 时按类型参数/类名兜底）。
     */
    @Nullable
    private static String extractEntityClassName(@Nullable PsiExpression expr) {
        if (expr == null) return null;
        if (expr instanceof PsiClassObjectAccessExpression) {
            PsiType type = ((PsiClassObjectAccessExpression) expr).getOperand().getType();
            if (type instanceof PsiClassType) {
                return ((PsiClassType) type).getClassName();
            }
        }
        return null;
    }

    /**
     * 从 createQuery/createCriteriaUpdate/createCriteriaDelete 实参提取结果类名。
     */
    @Nullable
    private static String extractResultClass(@NotNull PsiMethodCallExpression call) {
        // 实参形式：cb.createQuery(Xxx.class)
        PsiExpression[] args = call.getArgumentList().getExpressions();
        PsiExpression arg = args.length > 0 ? args[0] : null;
        if (arg instanceof PsiClassObjectAccessExpression) {
            PsiType type = ((PsiClassObjectAccessExpression) arg).getOperand().getType();
            if (type instanceof PsiClassType) {
                return ((PsiClassType) type).getClassName();
            }
        }
        // 类型参数形式：类型参数由 PsiMethodCallExpression 的 typeArguments 提供
        PsiType[] typeArgs = call.getTypeArgumentList().getTypeArguments();
        if (typeArgs.length > 0 && typeArgs[0] instanceof PsiClassType) {
            return ((PsiClassType) typeArgs[0]).getClassName();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 列引用解析（root.get("field") / root.get(Xxx_.field)）
    // ------------------------------------------------------------------

    /**
     * 从 root.get(...) 调用提取字段名。
     *
     * 支持两种形态：
     *  - `root.get("fieldName")`：字符串字面量
     *  - `root.get(Xxx_.fieldName)`：静态字段引用
     */
    @Nullable
    private static String extractFieldName(@NotNull PsiMethodCallExpression call) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) return null;
        if (!methodName.equals("get")) return null;
        PsiExpression[] args = call.getArgumentList().getExpressions();
        PsiExpression arg = args.length > 0 ? args[0] : null;
        if (arg == null) return null;
        if (arg instanceof PsiLiteralExpression) {
            Object value = ((PsiLiteralExpression) arg).getValue();
            return value instanceof String ? (String) value : null;
        }
        if (arg instanceof PsiReferenceExpression) {
            return ((PsiReferenceExpression) arg).getReferenceName();
        }
        return null;
    }

    /**
     * 从实参表达式提取字段名（支持 root.get(...) 或字符串字面量）。
     */
    @Nullable
    private static String extractFieldNameFromArg(@Nullable PsiExpression arg) {
        if (arg instanceof PsiMethodCallExpression
                && "get".equals(((PsiMethodCallExpression) arg).getMethodExpression().getReferenceName())) {
            return extractFieldName((PsiMethodCallExpression) arg);
        }
        if (arg instanceof PsiLiteralExpression) {
            Object value = ((PsiLiteralExpression) arg).getValue();
            return value instanceof String ? (String) value : null;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // List 实参解引用（T-J17 bug 修复：multiselect(List) / orderBy(List) / groupBy(List)）
    // ------------------------------------------------------------------

    /**
     * 解引用 List 类型实参为元素表达式列表。
     *
     * 有界实现：
     *  - 变量初始化为 `List.of(...)` / `Arrays.asList(...)` → 直接收集实参；
     *  - 变量初始化为 `new ArrayList<>()` 且以 `list.add(x)` 逐个加入 → 按源码顺序收集 add 实参；
     *  - 其余（无法解析的变量/表达式）→ 原样返回单元素列表。
     */
    @NotNull
    private static List<PsiExpression> expandListArgument(@NotNull PsiExpression expr, @NotNull LocateContext ctx) {
        if (!(expr instanceof PsiReferenceExpression)) return List.of(expr);
        String varName = ((PsiReferenceExpression) expr).getReferenceName();
        if (varName == null) return List.of(expr);
        // root/join 等实体变量不是列表变量
        if (ctx.roots.contains(varName) || ctx.joinVars.containsKey(varName)) return List.of(expr);
        PsiLocalVariable decl = null;
        for (PsiLocalVariable lv : PsiTreeUtil.collectElementsOfType(ctx.block, PsiLocalVariable.class)) {
            if (varName.equals(lv.getName())) {
                decl = lv;
                break;
            }
        }
        if (decl == null) return List.of(expr);
        PsiExpression initializer = decl.getInitializer();
        if (initializer == null) return List.of(expr);
        // List.of(...) / Arrays.asList(...)
        if (initializer instanceof PsiMethodCallExpression) {
            String initName = ((PsiMethodCallExpression) initializer).getMethodExpression().getReferenceName();
            if ("of".equals(initName) || "asList".equals(initName)) {
                List<PsiExpression> result = new ArrayList<>();
                for (PsiExpression e : ((PsiMethodCallExpression) initializer).getArgumentList().getExpressions()) {
                    result.add(e);
                }
                return result;
            }
        }
        // new ArrayList<>() + add(...) 逐个收集（按源码顺序）
        if (initializer instanceof PsiNewExpression) {
            List<PsiExpression> adds = new ArrayList<>();
            for (PsiMethodCallExpression mce : PsiTreeUtil.collectElementsOfType(ctx.block, PsiMethodCallExpression.class)) {
                if ("add".equals(mce.getMethodExpression().getReferenceName())
                        && mce.getMethodExpression().getQualifierExpression() != null
                        && varName.equals(mce.getMethodExpression().getQualifierExpression().getText())) {
                    for (PsiExpression e : mce.getArgumentList().getExpressions()) {
                        adds.add(e);
                    }
                }
            }
            if (!adds.isEmpty()) return adds;
        }
        return List.of(expr);
    }

    // ------------------------------------------------------------------
    // SELECT 解析
    // ------------------------------------------------------------------

    /**
     * 从 cq.select(...) / cq.multiselect(...) 提取 SELECT 列。
     *
     * 支持：root.get(...) 普通列、聚合函数（含 countDistinct/sumAsLong/greatest 等，T-J17）、
     * 复杂表达式（CASE/COALESCE/CONCAT/算术等，T-J17）、List 变量实参解引用（T-J17 bug 修复）。
     */
    @NotNull
    private static List<SelectColumn> extractSelectColumns(@NotNull PsiMethodCallExpression call,
                                                           @NotNull LocateContext ctx) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) return List.of();
        PsiExpression[] rawArgs = call.getArgumentList().getExpressions();
        if (rawArgs.length == 0) return List.of();

        // cq.select(root) → 全列（返回空列表，converter 按全列处理）
        if (methodName.equals("select") && rawArgs.length == 1) {
            PsiExpression arg = rawArgs[0];
            if (arg instanceof PsiReferenceExpression
                    && ctx.roots.contains(((PsiReferenceExpression) arg).getReferenceName())) {
                return List.of();
            }
        }

        List<PsiExpression> args = new ArrayList<>();
        for (PsiExpression rawArg : rawArgs) {
            args.addAll(expandListArgument(rawArg, ctx));
        }
        List<SelectColumn> result = new ArrayList<>();
        for (PsiExpression arg : args) {
            // multiselect(root)：root 整体作选择目标，静态预览按全列语义处理
            if (arg instanceof PsiReferenceExpression
                    && ctx.roots.contains(((PsiReferenceExpression) arg).getReferenceName())) {
                continue;
            }

            // 聚合函数调用
            AggregateInfo aggregate = extractAggregate(arg, ctx);
            if (aggregate != null) {
                result.add(new SelectColumn(aggregate.getFieldName(), null, aggregate));
                continue;
            }

            // root.get(...) 普通列
            if (arg instanceof PsiMethodCallExpression
                    && "get".equals(((PsiMethodCallExpression) arg).getMethodExpression().getReferenceName())) {
                String fieldName = extractFieldName((PsiMethodCallExpression) arg);
                if (fieldName == null) continue;
                result.add(new SelectColumn(fieldName, null, null));
                continue;
            }

            // 复杂表达式（CASE / COALESCE / CONCAT / (a + b) 等，T-J17）
            ExpressionModel exprModel = translateExpression(arg, ctx);
            if (exprModel instanceof ExpressionModel.Column column) {
                result.add(new SelectColumn(column.getFieldName(), null, null));
            } else if (exprModel instanceof ExpressionModel.Raw) {
                // 原样降级文本不进 SELECT 列
            } else {
                result.add(new SelectColumn(null, null, null, exprModel));
            }
        }
        return result;
    }

    /**
     * 尝试从 SELECT 参数表达式提取聚合函数信息（T-J17：countDistinct/sumAsLong/sumAsDouble/greatest/least）。
     */
    @Nullable
    private static AggregateInfo extractAggregate(@NotNull PsiExpression arg, @NotNull LocateContext ctx) {
        if (!(arg instanceof PsiMethodCallExpression)) return null;
        PsiMethodCallExpression call = (PsiMethodCallExpression) arg;
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) return null;
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (!(qualifier instanceof PsiReferenceExpression)) return null;

        PsiExpression[] args = call.getArgumentList().getExpressions();
        switch (methodName) {
            case "countDistinct":
                return new AggregateInfo("COUNT", extractFieldNameFromArg(firstOf(args)), true);
            case "sumAsLong":
                return new AggregateInfo("SUM", extractFieldNameFromArg(firstOf(args)), false,
                        CAST_TYPE_MAP.get("toLong"), null);
            case "sumAsDouble":
                return new AggregateInfo("SUM", extractFieldNameFromArg(firstOf(args)), false,
                        CAST_TYPE_MAP.get("toDouble"), null);
            case "greatest":
                return new AggregateInfo("MAX", extractFieldNameFromArg(firstOf(args)), false);
            case "least":
                return new AggregateInfo("MIN", extractFieldNameFromArg(firstOf(args)), false);
            default:
                break;
        }

        String upperName = methodName.toUpperCase(Locale.ROOT);
        if (!AGGREGATE_FUNCTIONS.contains(upperName)) return null;
        // cb.sum(a, b) 双参是算术加法而非聚合，交由表达式路径处理（T-J17）
        if (methodName.equals("sum") && args.length >= 2) return null;
        PsiExpression firstArg = firstOf(args);
        // cb.count() → COUNT(*)
        if (firstArg == null) {
            return new AggregateInfo(upperName, null, false);
        }
        // cb.count(root)：实参是 Root 变量引用 → COUNT(*) 语义
        if (firstArg instanceof PsiReferenceExpression
                && ctx.roots.contains(((PsiReferenceExpression) firstArg).getReferenceName())) {
            return new AggregateInfo(upperName, null, false);
        }
        String fieldName = extractFieldNameFromArg(firstArg);
        if (fieldName != null) {
            return new AggregateInfo(upperName, fieldName, false);
        }
        // 聚合内层复杂表达式（如 AVG(COALESCE(age, 0))）
        return new AggregateInfo(upperName, null, false, null, translateExpression(firstArg, ctx));
    }

    /** 返回实参数组首元素（空数组返回 null）。 */
    @Nullable
    private static PsiExpression firstOf(@NotNull PsiExpression[] args) {
        return args.length > 0 ? args[0] : null;
    }

    // ------------------------------------------------------------------
    // WHERE / Predicate 解析
    // ------------------------------------------------------------------

    /**
     * 从 where/having 的实参（谓词调用、谓词变量、谓词列表变量、toArray 形态）提取条件模型。
     */
    @Nullable
    private static PredicateModel extractPredicate(@Nullable PsiExpression expr, @NotNull LocateContext ctx) {
        if (expr == null) return null;
        if (expr instanceof PsiMethodCallExpression call) {
            // value() 透明链层：cb.in(x).value(a).value(b) 的最外层是 value 调用，下钻到基调用
            if ("value".equals(call.getMethodExpression().getReferenceName())) {
                PsiExpression cursor = expr;
                while (cursor instanceof PsiMethodCallExpression cursorCall
                        && "value".equals(cursorCall.getMethodExpression().getReferenceName())) {
                    PsiExpression qualifierExpr = cursorCall.getMethodExpression().getQualifierExpression();
                    if (qualifierExpr == null) return null;
                    cursor = qualifierExpr;
                }
                return extractPredicate(cursor, ctx);
            }
            String methodName = call.getMethodExpression().getReferenceName();
            if ("toArray".equals(methodName) || "add".equals(methodName)) {
                // predicates.toArray(new Predicate[0]) / predicates.add(p)：解引用列表变量（add 收集）
                PsiExpression qualifierExpr = call.getMethodExpression().getQualifierExpression();
                if (qualifierExpr == null) return null;
                List<PredicateModel> children = new ArrayList<>();
                for (PsiExpression item : expandListArgument(qualifierExpr, ctx)) {
                    PredicateModel p = extractPredicate(item, ctx);
                    if (p != null) children.add(p);
                }
                return predicateGroup(children);
            }
            if ("of".equals(methodName) || "asList".equals(methodName)) {
                // List.of(p1, p2) / Arrays.asList(p1, p2)
                List<PredicateModel> children = new ArrayList<>();
                for (PsiExpression item : call.getArgumentList().getExpressions()) {
                    PredicateModel p = extractPredicate(item, ctx);
                    if (p != null) children.add(p);
                }
                return predicateGroup(children);
            }
            // 仅处理 CriteriaBuilder 条件方法，排除 Java 集合工具方法等
            if (methodName != null && CB_CONDITION_METHODS.contains(methodName)) {
                return extractPredicateFromCall(call, ctx);
            }
            return null;
        }
        // 变量引用 → 先查已登记的谓词变量，再尝试列表变量解引用
        if (expr instanceof PsiReferenceExpression ref) {
            String refName = ref.getReferenceName();
            if (refName != null) {
                PredicateModel registered = ctx.predicateVars.get(refName);
                if (registered != null) return registered;
            }
            List<PsiExpression> items = expandListArgument(expr, ctx);
            if (items.size() > 1 || (items.size() == 1 && items.get(0) != expr)) {
                List<PredicateModel> children = new ArrayList<>();
                for (PsiExpression item : items) {
                    PredicateModel p = extractPredicate(item, ctx);
                    if (p != null) children.add(p);
                }
                PredicateModel group = predicateGroup(children);
                if (group != null) return group;
            }
            // 变量引用不生成 Raw 谓词，避免变量名出现在 WHERE 子句
            return null;
        }
        return null;
    }

    /** 谓词列表组装：空 → null（不可渲染）；单条件直用；多条件 AND 组。 */
    @Nullable
    private static PredicateModel predicateGroup(@NotNull List<PredicateModel> children) {
        if (children.isEmpty()) return null;
        if (children.size() == 1) return children.get(0);
        return new PredicateModel.Group(GroupConnector.AND, children, false);
    }

    /**
     * 从 CriteriaBuilder 条件调用提取 PredicateModel。
     *
     * 支持的调用模式（T-J17 全集）：
     *  - cb.equal/notEqual/greaterThan(...)/gt/ge/lt/le → Simple / ExpressionComparison
     *  - cb.isNull/isNotNull → IsNull；cb.between → Between / ExpressionBetween
     *  - cb.like/notLike（含 escapeChar 重载）→ Like
     *  - cb.in(x).value(a).value(b)（链式收集，bug 修复）/ cb.isTrue/isFalse / conjunction/disjunction
     *  - cb.exists(subquery) → Exists；isEmpty/isMember 等集合谓词 → 注释降级 Raw
     *  - cb.and(p1, p2) → Group(AND, [...])；cb.or → Group(OR)；cb.not(p) → Group(AND, [p], negated)
     */
    @NotNull
    private static PredicateModel extractPredicateFromCall(@NotNull PsiMethodCallExpression call,
                                                           @NotNull LocateContext ctx) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) return new PredicateModel.Raw(call.getText());
        PsiExpression[] args = call.getArgumentList().getExpressions();

        switch (methodName) {
            case "equal":
                return buildComparison(call, args, "=", ctx);
            case "notEqual":
                return buildComparison(call, args, "!=", ctx);
            case "greaterThan":
            case "gt":
                return buildComparison(call, args, ">", ctx);
            case "greaterThanOrEqualTo":
            case "ge":
                return buildComparison(call, args, ">=", ctx);
            case "lessThan":
            case "lt":
                return buildComparison(call, args, "<", ctx);
            case "lessThanOrEqualTo":
            case "le":
                return buildComparison(call, args, "<=", ctx);
            case "between":
                return buildBetween(call, args, ctx);
            case "isNull":
                return buildUnaryNull(call, args, false, ctx);
            case "isNotNull":
                return buildUnaryNull(call, args, true, ctx);
            case "isTrue":
                return buildBoolean(call, args, true, ctx);
            case "isFalse":
                return buildBoolean(call, args, false, ctx);
            case "like":
                return buildLike(call, args, false, ctx);
            case "notLike":
                return buildLike(call, args, true, ctx);
            case "in": {
                // Expression.in 形态（path.get("x").in(...)）的 qualifier 是 get 调用 → 走 Expression.in 路径
                PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
                if (qualifier instanceof PsiMethodCallExpression qualifierCall
                        && "get".equals(qualifierCall.getMethodExpression().getReferenceName())) {
                    PredicateModel model = extractExpressionIn(call, qualifierCall, ctx);
                    return model != null ? model : new PredicateModel.Raw(call.getText());
                }
                return buildIn(call, args, ctx);
            }
            case "conjunction":
                return new PredicateModel.BooleanConstant(true);
            case "disjunction":
                return new PredicateModel.BooleanConstant(false);
            case "exists":
                return buildExists(call, args, ctx);
            case "isEmpty":
                // 集合谓词：IS [NOT] EMPTY / [NOT] MEMBER OF 需集合子查询，静态渲染不可行 → 注释降级（T-J17）
                return degradeCollectionPredicate(call, args, "IS EMPTY", ctx);
            case "isNotEmpty":
                return degradeCollectionPredicate(call, args, "IS NOT EMPTY", ctx);
            case "isMember":
                return degradeCollectionPredicate(call, args, "MEMBER OF", ctx);
            case "isNotMember":
                return degradeCollectionPredicate(call, args, "NOT MEMBER OF", ctx);
            case "and":
                return buildGroup(args, GroupConnector.AND, ctx);
            case "or":
                return buildGroup(args, GroupConnector.OR, ctx);
            case "not":
                return buildNot(call, args, ctx);
            default:
                return new PredicateModel.Raw(call.getText());
        }
    }

    /** 构建二元比较条件（左值为列 → Simple；左值为函数/运算/CASE 表达式 → ExpressionComparison）。 */
    @NotNull
    private static PredicateModel buildComparison(@NotNull PsiMethodCallExpression call,
                                                  @NotNull PsiExpression[] args,
                                                  @NotNull String op,
                                                  @NotNull LocateContext ctx) {
        if (args.length < 2) return new PredicateModel.Raw(call.getText());
        PsiExpression left = args[0];
        String leftFieldName = extractFieldNameFromArg(left);
        PsiExpression right = args[1];

        // 列对列比较（Join.on / root.get 与 join.get 互比，T-J7）
        if (leftFieldName != null && right instanceof PsiMethodCallExpression rightGet
                && "get".equals(rightGet.getMethodExpression().getReferenceName())) {
            String rightFieldName = extractFieldName(rightGet);
            if (rightFieldName != null) {
                return new PredicateModel.ColumnEquals(leftFieldName, rightFieldName, op,
                        receiverVariable(left), receiverVariable(rightGet));
            }
        }

        if (leftFieldName != null) {
            // 普通列左值 + 值右值：保持既有 Simple 形态；literal()/parameter() 展开为值文本
            ExpressionModel rightModel = translateExpression(right, ctx);
            String rightText = rightModel instanceof ExpressionModel.Value value
                    ? value.getExpressionText()
                    : right.getText();
            return new PredicateModel.Simple(leftFieldName, op, rightText);
        }
        // 表达式左值（LOWER(x) / (a+b) / CASE ... 等，T-J17）
        ExpressionModel leftModel = translateExpression(left, ctx);
        if (leftModel instanceof ExpressionModel.Raw raw
                && raw.getExpressionText().equals(call.getText())) {
            return new PredicateModel.Raw(call.getText());
        }
        return new PredicateModel.ExpressionComparison(leftModel, op, translateExpression(right, ctx));
    }

    /** get 调用接收者变量名（root / deptJoin 等）。 */
    @Nullable
    private static String receiverVariable(@NotNull PsiExpression getCall) {
        if (!(getCall instanceof PsiMethodCallExpression call)) return null;
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (qualifier instanceof PsiReferenceExpression ref) {
            return ref.getReferenceName();
        }
        return null;
    }

    /** 构建 BETWEEN 条件（左值为表达式时走 ExpressionBetween，T-J17）。 */
    @NotNull
    private static PredicateModel buildBetween(@NotNull PsiMethodCallExpression call,
                                               @NotNull PsiExpression[] args,
                                               @NotNull LocateContext ctx) {
        if (args.length < 3) return new PredicateModel.Raw(call.getText());
        String fieldName = extractFieldNameFromArg(args[0]);
        if (fieldName != null) {
            return new PredicateModel.Between(fieldName, args[1].getText(), args[2].getText());
        }
        ExpressionModel leftModel = translateExpression(args[0], ctx);
        if (leftModel instanceof ExpressionModel.Raw raw
                && raw.getExpressionText().equals(call.getText())) {
            return new PredicateModel.Raw(call.getText());
        }
        return new PredicateModel.ExpressionBetween(leftModel,
                translateExpression(args[1], ctx),
                translateExpression(args[2], ctx));
    }

    /** 构建 IS NULL / IS NOT NULL 条件（左值为表达式时复用 ExpressionComparison 的 IS NULL op）。 */
    @NotNull
    private static PredicateModel buildUnaryNull(@NotNull PsiMethodCallExpression call,
                                                 @NotNull PsiExpression[] args,
                                                 boolean negated,
                                                 @NotNull LocateContext ctx) {
        if (args.length == 0) return new PredicateModel.Raw(call.getText());
        String fieldName = extractFieldNameFromArg(args[0]);
        if (fieldName != null) return new PredicateModel.IsNull(fieldName, negated);
        ExpressionModel leftModel = translateExpression(args[0], ctx);
        if (leftModel instanceof ExpressionModel.Raw raw
                && raw.getExpressionText().equals(call.getText())) {
            return new PredicateModel.Raw(call.getText());
        }
        return new PredicateModel.ExpressionComparison(leftModel,
                negated ? "IS NOT NULL" : "IS NULL",
                new ExpressionModel.Value("null"));
    }

    /** 构建 isTrue / isFalse 条件（→ col = TRUE / col = FALSE）。 */
    @NotNull
    private static PredicateModel buildBoolean(@NotNull PsiMethodCallExpression call,
                                               @NotNull PsiExpression[] args,
                                               boolean value,
                                               @NotNull LocateContext ctx) {
        if (args.length == 0) return new PredicateModel.Raw(call.getText());
        String literal = value ? "TRUE" : "FALSE";
        String fieldName = extractFieldNameFromArg(args[0]);
        if (fieldName != null) return new PredicateModel.Simple(fieldName, "=", literal);
        ExpressionModel leftModel = translateExpression(args[0], ctx);
        if (leftModel instanceof ExpressionModel.Raw raw
                && raw.getExpressionText().equals(call.getText())) {
            return new PredicateModel.Raw(call.getText());
        }
        return new PredicateModel.ExpressionComparison(leftModel, "=", new ExpressionModel.Value(literal));
    }

    /** 构建 LIKE / NOT LIKE 条件（escapeChar 重载 → ESCAPE 渲染，T-J17）。 */
    @NotNull
    private static PredicateModel buildLike(@NotNull PsiMethodCallExpression call,
                                            @NotNull PsiExpression[] args,
                                            boolean negated,
                                            @NotNull LocateContext ctx) {
        if (args.length < 2) return new PredicateModel.Raw(call.getText());
        String fieldName = extractFieldNameFromArg(args[0]);
        if (fieldName == null) return new PredicateModel.Raw(call.getText());
        // like(x, pattern, escapeChar) 三参重载
        String escapeChar = args.length > 2 ? args[2].getText() : null;
        return new PredicateModel.Like(fieldName, negated, args[1].getText(), escapeChar);
    }

    /**
     * 构建 IN 条件（cb.in(x) 返回 CriteriaBuilder.In 的形态）。
     *
     * 支持形态：
     *  - `cb.in(root.get("a")).value(v1).value(v2)`：链式收集（T-J17 bug 修复：原实现只沿
     *    parent 找一层且 PSI 上 parent 是引用表达式，实际取不到值）
     *  - `cb.in(root.get("a"), v1, v2)`：直接实参（历史兼容）
     *  - 链尾 `.not()` 由 buildNot 统一包裹为 NOT 组（jakarta 3.1 无 cb.notIn）
     */
    @NotNull
    private static PredicateModel buildIn(@NotNull PsiMethodCallExpression call,
                                          @NotNull PsiExpression[] args,
                                          @NotNull LocateContext ctx) {
        if (args.length == 0) return new PredicateModel.Raw(call.getText());
        String fieldName = extractFieldNameFromArg(args[0]);
        if (fieldName == null) return new PredicateModel.Raw(call.getText());
        List<String> chainedValues = collectInChain(call);
        List<String> values = new ArrayList<>();
        if (args.length > 1) {
            for (int i = 1; i < args.length; i++) {
                values.add(args[i].getText());
            }
        }
        values.addAll(chainedValues);
        if (values.isEmpty()) {
            // 无值时添加占位符标记，面板可输入多值
            values.add("__IN_PLACEHOLDER__");
        }
        return new PredicateModel.In(fieldName, false, values);
    }

    /**
     * Expression.in 形态：`path.get("x").in(v1, v2)` / `.in(collectionVar)` / `.in(subquery)`。
     *
     * @param qualifier `in` 调用的 qualifier（须为 get 调用）
     */
    @Nullable
    private static PredicateModel extractExpressionIn(@NotNull PsiMethodCallExpression call,
                                                      @NotNull PsiMethodCallExpression qualifier,
                                                      @NotNull LocateContext ctx) {
        String fieldName = extractFieldName(qualifier);
        if (fieldName == null) return null;
        PsiExpression[] args = call.getArgumentList().getExpressions();

        // path.get("x").in(subquery) → IN 子查询（T-J17/J13）
        if (args.length == 1 && args[0] instanceof PsiReferenceExpression subqueryRef) {
            String subqueryName = subqueryRef.getReferenceName();
            SubqueryModel subQuery = subqueryName != null ? ctx.subqueries.get(subqueryName) : null;
            if (subQuery != null) {
                return new PredicateModel.InSubQuery(fieldName, subQuery, false);
            }
        }

        List<String> values = new ArrayList<>();
        for (PsiExpression arg : args) {
            values.add(arg.getText());
        }
        if (values.isEmpty()) {
            values.add("__IN_PLACEHOLDER__");
        }
        return new PredicateModel.In(fieldName, false, values);
    }

    /**
     * 沿调用链向上收集 `cb.in(...).value(a).value(b)` 的值实参。
     *
     * PSI 结构：`cb.in(x)` 的 parent 是引用表达式 `value`，其 parent 才是 `.value(a)` 调用，
     * 因此须按「引用表达式 → 方法调用」交替向上行走。链尾 `.not()` 不在此消费——
     * 由调用链上的 not 分支统一包裹 NOT 组，避免双重取反。
     */
    @NotNull
    private static List<String> collectInChain(@NotNull PsiMethodCallExpression call) {
        List<String> values = new ArrayList<>();
        PsiElement current = call.getParent();
        while (true) {
            if (!(current instanceof PsiReferenceExpression ref)) break;
            if (!(ref.getParent() instanceof PsiMethodCallExpression chained)) break;
            if (!"value".equals(chained.getMethodExpression().getReferenceName())) break;
            for (PsiExpression e : chained.getArgumentList().getExpressions()) {
                values.add(e.getText());
            }
            current = chained.getParent();
        }
        return values;
    }

    /** cb.exists(subquery) → EXISTS 子查询（T-J17/J13）。 */
    @NotNull
    private static PredicateModel buildExists(@NotNull PsiMethodCallExpression call,
                                              @NotNull PsiExpression[] args,
                                              @NotNull LocateContext ctx) {
        SubqueryModel subquery = null;
        if (args.length > 0 && args[0] instanceof PsiReferenceExpression ref) {
            String refName = ref.getReferenceName();
            if (refName != null) subquery = ctx.subqueries.get(refName);
        }
        if (subquery == null) return new PredicateModel.Raw(call.getText());
        return new PredicateModel.Exists(subquery, false);
    }

    /** 集合谓词注释降级：IS EMPTY / MEMBER OF 等 JPQL 语义无直接 SQL 对应，静态渲染不可行。 */
    @NotNull
    private static PredicateModel degradeCollectionPredicate(@NotNull PsiMethodCallExpression call,
                                                             @NotNull PsiExpression[] args,
                                                             @NotNull String semantic,
                                                             @NotNull LocateContext ctx) {
        String target = args.length > 0 ? args[0].getText() : call.getText();
        return new PredicateModel.Raw(
                "/* 集合谓词 " + semantic + "(" + target + ")：需按关联表生成子查询，静态预览暂不支持 */");
    }

    /** 构建 AND / OR 条件组。 */
    @NotNull
    private static PredicateModel buildGroup(@NotNull PsiExpression[] args,
                                             @NotNull GroupConnector connector,
                                             @NotNull LocateContext ctx) {
        List<PredicateModel> children = new ArrayList<>();
        for (PsiExpression arg : args) {
            PredicateModel p = extractPredicate(arg, ctx);
            if (p != null) children.add(p);
        }
        return new PredicateModel.Group(connector, children, false);
    }

    /**
     * 构建 NOT 条件：cb.not(p) 与链式 cb.equal(...).not()（后者实参为空、谓词在 qualifier 上）。
     * qualifier 为 value 链中间层时（cb.in(x).value(1).not()）下钻到基调用提取。
     */
    @NotNull
    private static PredicateModel buildNot(@NotNull PsiMethodCallExpression call,
                                           @NotNull PsiExpression[] args,
                                           @NotNull LocateContext ctx) {
        PsiExpression inner = args.length > 0 ? args[0] : null;
        if (inner == null) inner = call.getMethodExpression().getQualifierExpression();
        if (inner == null) return new PredicateModel.Raw(call.getText());
        // value() 透明链层：下钻到 cb.in(...) 等基调用
        while (inner instanceof PsiMethodCallExpression innerCall
                && "value".equals(innerCall.getMethodExpression().getReferenceName())) {
            PsiExpression qualifier = innerCall.getMethodExpression().getQualifierExpression();
            if (qualifier == null) break;
            inner = qualifier;
        }
        PredicateModel child = extractPredicate(inner, ctx);
        if (child == null) return new PredicateModel.Raw(call.getText());
        return new PredicateModel.Group(GroupConnector.AND, List.of(child), true);
    }

    // ------------------------------------------------------------------
    // 表达式解析（T-J17：cb 表达式函数全集 → ExpressionModel）
    // ------------------------------------------------------------------

    /**
     * 表达式翻译：将 cb 表达式调用 / 字面量 / 变量翻译为 [ExpressionModel]。
     * 无法结构化的形态降级 Raw（converter 原样透传），绝不抛异常。
     */
    @NotNull
    private static ExpressionModel translateExpression(@Nullable PsiExpression expr, @NotNull LocateContext ctx) {
        if (expr == null) return new ExpressionModel.Raw("?");
        if (expr instanceof PsiMethodCallExpression call) {
            String methodName = call.getMethodExpression().getReferenceName();
            PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();

            if ("get".equals(methodName)) {
                String fieldName = extractFieldName(call);
                if (fieldName != null) return new ExpressionModel.Column(fieldName);
            } else if ("literal".equals(methodName)) {
                // cb.literal(x)：字符串/数值按实参文本交由 converter 求值
                PsiExpression[] litArgs = call.getArgumentList().getExpressions();
                return new ExpressionModel.Value(litArgs.length > 0 ? litArgs[0].getText() : "?");
            } else if ("nullLiteral".equals(methodName)) {
                // cb.nullLiteral() → NULL
                return new ExpressionModel.Value("null");
            } else if ("parameter".equals(methodName)) {
                // cb.parameter("name") → ? 占位（取未加引号的参数名，converter 识别为占位符）
                PsiExpression[] paramArgs = call.getArgumentList().getExpressions();
                PsiExpression arg = paramArgs.length > 0 ? paramArgs[0] : null;
                String name = null;
                if (arg instanceof PsiLiteralExpression literal) {
                    Object value = literal.getValue();
                    if (value instanceof String s) name = s;
                }
                if (name == null) name = arg != null ? arg.getText() : "?";
                return new ExpressionModel.Value(name);
            } else if ("value".equals(methodName)) {
                // 链式 value()：cb.coalesce(x).value(y).value(z)
                return translateValueChain(call, ctx);
            } else if ("when".equals(methodName) || "otherwise".equals(methodName)) {
                // CASE 链（最外层为 when/otherwise，T-J17）
                ExpressionModel caseModel = translateCaseChain(call, ctx);
                if (caseModel != null) return caseModel;
            } else if ("not".equals(methodName)) {
                // Expression.not()：布尔表达式取反
                if (qualifier != null) {
                    return new ExpressionModel.Function("NOT",
                            List.of(translateExpression(qualifier, ctx)), null);
                }
            }

            if (methodName != null && CB_EXPRESSION_FUNCTIONS.contains(methodName)
                    && qualifier instanceof PsiReferenceExpression) {
                return translateFunctionCall(call, methodName, ctx);
            }
            // 链式调用的中间层（如 .value() 之上的嵌套）降级原文
            return new ExpressionModel.Raw(expr.getText());
        }
        if (expr instanceof PsiLiteralExpression) return new ExpressionModel.Value(expr.getText());
        if (expr instanceof PsiClassObjectAccessExpression) return new ExpressionModel.Value(expr.getText());
        if (expr instanceof PsiReferenceExpression) return new ExpressionModel.Value(expr.getText());
        return new ExpressionModel.Raw(expr.getText());
    }

    /**
     * 翻译 cb 表达式函数调用为 Function 节点。
     *
     * 函数名规范化：
     *  - 算术：sum(2 参)=ADD、prod=MUL、diff=SUB、quot=DIV、mod=MOD、neg=NEG（sum 1 参=SUM 聚合）
     *  - 转换：toLong/.../toString → CAST + modifier 目标类型（统一 SQL 标准型）
     *  - trim：Trimspec 实参 → modifier（TRIM(LEADING FROM x)）
     *  - function(name, type, args...)：name 原样拼接（白名单为远期项，注释说明）
     *  - size：集合统计需子查询，静态渲染不可行 → 注释降级
     */
    @NotNull
    private static ExpressionModel translateFunctionCall(@NotNull PsiMethodCallExpression call,
                                                         @NotNull String methodName,
                                                         @NotNull LocateContext ctx) {
        PsiExpression[] args = call.getArgumentList().getExpressions();

        // cb.count(root) → COUNT(*)：实参是 Root 变量引用而非 get 调用
        if (methodName.equals("count") && args.length == 1
                && args[0] instanceof PsiReferenceExpression ref
                && ctx.roots.contains(ref.getReferenceName())) {
            return new ExpressionModel.Function("COUNT", List.of(), null);
        }

        // function(name, type, args...)：原样拼接 name(args)；返回类型实参跳过
        if (methodName.equals("function")) {
            String fnName = null;
            if (args.length > 0) {
                if (args[0] instanceof PsiLiteralExpression literal) {
                    Object value = literal.getValue();
                    if (value instanceof String s) fnName = s;
                }
                if (fnName == null) fnName = args[0].getText();
            }
            if (fnName == null) return new ExpressionModel.Raw(call.getText());
            List<ExpressionModel> realArgs = new ArrayList<>();
            for (int i = 2; i < args.length; i++) {
                realArgs.add(translateExpression(args[i], ctx));
            }
            return new ExpressionModel.Function(fnName, realArgs, null);
        }

        // size(collection)：JPQL SIZE 语义须展开为关联表 COUNT 子查询，静态不可行 → 注释降级
        if (methodName.equals("size")) {
            String target = args.length > 0 ? args[0].getText() : call.getText();
            return new ExpressionModel.Raw("/* cb.size(" + target + ")：集合统计需子查询，静态预览暂不支持 */");
        }

        // trim(x) / trim(Trimspec, x)
        if (methodName.equals("trim")) {
            String modifier = null;
            if (args.length > 0 && args[0] instanceof PsiReferenceExpression ref) {
                String refName = ref.getReferenceName();
                if (refName != null && TRIM_SPECS.contains(refName)) modifier = refName;
            }
            List<ExpressionModel> realArgs = new ArrayList<>();
            int start = modifier != null ? 1 : 0;
            for (int i = start; i < args.length; i++) {
                realArgs.add(translateExpression(args[i], ctx));
            }
            return new ExpressionModel.Function("TRIM", realArgs, modifier);
        }

        // sum：1 参为 SUM 聚合，2 参为加法
        if (methodName.equals("sum")) {
            String function = args.length >= 2 ? "ADD" : "SUM";
            List<ExpressionModel> realArgs = new ArrayList<>();
            for (PsiExpression arg : args) {
                realArgs.add(translateExpression(arg, ctx));
            }
            return new ExpressionModel.Function(function, realArgs, null);
        }

        // 转换函数 → CAST(x AS 类型)
        if (CAST_TYPE_MAP.containsKey(methodName)) {
            if (args.length == 0) return new ExpressionModel.Raw(call.getText());
            return new ExpressionModel.Function("CAST",
                    List.of(translateExpression(args[0], ctx)),
                    CAST_TYPE_MAP.get(methodName));
        }

        String function;
        switch (methodName) {
            case "prod":
                function = "MUL";
                break;
            case "diff":
                function = "SUB";
                break;
            case "quot":
                function = "DIV";
                break;
            case "mod":
                function = "MOD";
                break;
            case "neg":
                function = "NEG";
                break;
            default:
                function = methodName.toUpperCase(Locale.ROOT);
                break;
        }
        List<ExpressionModel> realArgs = new ArrayList<>();
        for (PsiExpression arg : args) {
            realArgs.add(translateExpression(arg, ctx));
        }
        return new ExpressionModel.Function(function, realArgs, null);
    }

    /**
     * 链式 value() 翻译：`cb.coalesce(x).value(y).value(z)` → COALESCE(x, y, z)。
     * 基调用非 coalesce 时按基调用翻译（链式实参丢弃降级）。
     */
    @NotNull
    private static ExpressionModel translateValueChain(@NotNull PsiMethodCallExpression expr,
                                                       @NotNull LocateContext ctx) {
        PsiExpression base = expr;
        List<PsiExpression> chained = new ArrayList<>();
        while (base instanceof PsiMethodCallExpression baseCall
                && "value".equals(baseCall.getMethodExpression().getReferenceName())) {
            for (PsiExpression e : baseCall.getArgumentList().getExpressions()) {
                chained.add(e);
            }
            PsiExpression qualifier = baseCall.getMethodExpression().getQualifierExpression();
            if (qualifier == null) break;
            base = qualifier;
        }
        ExpressionModel baseModel = translateExpression(base, ctx);
        if (chained.isEmpty() || !(baseModel instanceof ExpressionModel.Function function)
                || !"COALESCE".equals(function.getFunction())) {
            return baseModel;
        }
        List<ExpressionModel> args = new ArrayList<>(function.getArgs());
        for (int i = chained.size() - 1; i >= 0; i--) {
            args.add(translateExpression(chained.get(i), ctx));
        }
        return new ExpressionModel.Function(function.getFunction(), args, function.getModifier());
    }

    /**
     * CASE 链翻译（T-J17）：
     *  - `cb.selectCase().when(cond, r1).when(cond2, r2).otherwise(e)` → searched case
     *  - `cb.selectCase(expr).when(v1, r1).otherwise(e)` → simple case
     *
     * @return Case 模型；链结构不符（基调用非 selectCase）时返回 null 走降级
     */
    @Nullable
    private static ExpressionModel translateCaseChain(@NotNull PsiMethodCallExpression outermost,
                                                      @NotNull LocateContext ctx) {
        List<PsiExpression> whenConds = new ArrayList<>();
        List<PsiExpression> whenResults = new ArrayList<>();
        PsiExpression otherwiseArg = null;
        PsiMethodCallExpression cursor = outermost;

        // 最外层可能是 otherwise
        if ("otherwise".equals(cursor.getMethodExpression().getReferenceName())) {
            PsiExpression[] oArgs = cursor.getArgumentList().getExpressions();
            otherwiseArg = oArgs.length > 0 ? oArgs[0] : null;
            PsiExpression qualifier = cursor.getMethodExpression().getQualifierExpression();
            if (!(qualifier instanceof PsiMethodCallExpression)) return null;
            cursor = (PsiMethodCallExpression) qualifier;
        }
        // 逐层下钻 when
        while ("when".equals(cursor.getMethodExpression().getReferenceName())) {
            PsiExpression[] args = cursor.getArgumentList().getExpressions();
            if (args.length < 2) return null;
            whenConds.add(args[0]);
            whenResults.add(args[1]);
            PsiExpression qualifier = cursor.getMethodExpression().getQualifierExpression();
            if (!(qualifier instanceof PsiMethodCallExpression)) return null;
            cursor = (PsiMethodCallExpression) qualifier;
        }
        if (!"selectCase".equals(cursor.getMethodExpression().getReferenceName())) return null;
        PsiExpression[] scArgs = cursor.getArgumentList().getExpressions();
        PsiExpression simpleExpr = scArgs.length > 0 ? scArgs[0] : null;
        ExpressionModel simpleModel = simpleExpr != null ? translateExpression(simpleExpr, ctx) : null;

        // whens.reversed()：调用顺序自外向内，模型按自内向外排列
        List<CaseWhen> whens = new ArrayList<>();
        for (int i = whenConds.size() - 1; i >= 0; i--) {
            PsiExpression cond = whenConds.get(i);
            PsiExpression result = whenResults.get(i);
            if (simpleExpr == null) {
                // searched case：条件是谓词
                whens.add(new CaseWhen(null, extractPredicate(cond, ctx), translateExpression(result, ctx)));
            } else {
                // simple case：条件是匹配值
                whens.add(new CaseWhen(translateExpression(cond, ctx), null, translateExpression(result, ctx)));
            }
        }
        return new ExpressionModel.Case(simpleModel, whens,
                otherwiseArg != null ? translateExpression(otherwiseArg, ctx) : null);
    }

    // ------------------------------------------------------------------
    // GROUP BY 解析
    // ------------------------------------------------------------------

    /**
     * 从 cq.groupBy(...) 的实参提取 GroupByModel。
     */
    @Nullable
    private static GroupByModel extractGroupBy(@Nullable PsiExpression expr) {
        if (expr == null) return null;
        String fieldName = extractFieldNameFromArg(expr);
        if (fieldName == null) return null;
        return new GroupByModel(fieldName);
    }

    // ------------------------------------------------------------------
    // ORDER BY 解析
    // ------------------------------------------------------------------

    /**
     * 从 cq.orderBy(...) 的实参提取 OrderByModel。
     *
     * 支持：
     *  - cb.asc(root.get("field")) → OrderByModel(field, asc=true)
     *  - cb.desc(root.get("field")) → OrderByModel(field, asc=false)
     */
    @Nullable
    private static OrderByModel extractOrderBy(@Nullable PsiExpression expr) {
        if (expr == null) return null;
        if (!(expr instanceof PsiMethodCallExpression mce)) return null;
        String methodName = mce.getMethodExpression().getReferenceName();
        if (methodName == null) return null;
        if (!CB_ORDER_METHODS.contains(methodName)) return null;
        PsiExpression[] innerArgs = mce.getArgumentList().getExpressions();
        PsiExpression innerArg = innerArgs.length > 0 ? innerArgs[0] : null;
        if (innerArg == null) return null;
        String fieldName = extractFieldNameFromArg(innerArg);
        if (fieldName == null) return null;
        return new OrderByModel(fieldName, methodName.equals("asc"));
    }

    // ------------------------------------------------------------------
    // JOIN / FETCH 解析（T-J7）
    // ------------------------------------------------------------------

    /**
     * 从 root.join(...)/fetch(...) 调用提取 join 记录并登记变量。
     *
     * 覆盖 join/joinCollection/joinSet/joinList/joinMap/fetch 的 (attr[, JoinType]) 实参形态；
     * 无 JoinType 实参时按 JPA 规范默认 INNER。
     */
    private static void registerJoin(@NotNull PsiMethodCallExpression call,
                                     @NotNull PsiMethodCallExpression joinCall,
                                     boolean fetch,
                                     @NotNull LocateContext ctx) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length == 0) return;

        // 关联属性：字符串字面量或方法引用
        PsiExpression firstArg = args[0];
        String fieldName;
        if (firstArg instanceof PsiLiteralExpression) {
            Object value = ((PsiLiteralExpression) firstArg).getValue();
            fieldName = value instanceof String ? (String) value : null;
        } else if (firstArg instanceof PsiReferenceExpression) {
            fieldName = ((PsiReferenceExpression) firstArg).getReferenceName();
        } else {
            fieldName = firstArg.getText();
        }
        if (fieldName == null) return;

        // JoinType（join(String) 无实参时按规范默认 INNER）
        JoinType joinType;
        if (args.length > 1) {
            PsiExpression typeArg = args[1];
            if (typeArg instanceof PsiReferenceExpression) {
                joinType = mapJoinType(((PsiReferenceExpression) typeArg).getReferenceName());
            } else {
                joinType = JoinType.INNER;
            }
        } else {
            joinType = JoinType.INNER;
        }

        String variableName = findAssignedVariable(call);
        WorkingJoin join = new WorkingJoin(joinCall.getText(), fieldName, joinType, fetch, variableName);
        ctx.allJoins.add(join);
        if (variableName != null) ctx.joinVars.put(variableName, join);
    }

    /** 汇总全部 join 记录（变量登记 + 内联 root.join("d").on(...) 形态均按发现顺序）。 */
    @NotNull
    private static List<WorkingJoin> collectWorkingJoins(@NotNull LocateContext ctx) {
        return ctx.allJoins;
    }

    /** JoinType 枚举名映射。 */
    @NotNull
    private static JoinType mapJoinType(@Nullable String enumName) {
        if (enumName != null) {
            if (JOIN_TYPE_LEFT.contains(enumName)) return JoinType.LEFT;
            if (JOIN_TYPE_INNER.contains(enumName)) return JoinType.INNER;
            if (JOIN_TYPE_RIGHT.contains(enumName)) return JoinType.RIGHT;
        }
        return JoinType.INNER;
    }

    // ------------------------------------------------------------------
    // 子查询解析（T-J17/J13：单层有界实现）
    // ------------------------------------------------------------------

    /**
     * 收集子查询变量与其 from/select/where 子句。
     *
     * correlate 关联与「子查询 where 中再引用子查询」的多层嵌套无法静态渲染 →
     * 置 degradedReason，converter 整体降级 Raw 注释。
     */
    private static void collectSubqueries(@NotNull Collection<PsiMethodCallExpression> allCalls,
                                          @NotNull Set<String> criteriaQueryVars,
                                          @NotNull LocateContext ctx) {
        // 1. subquery 变量登记：`Subquery<T> sq = query.subquery(...)`
        for (PsiMethodCallExpression call : allCalls) {
            String methodName = call.getMethodExpression().getReferenceName();
            if (methodName == null) continue;
            if (!methodName.equals("subquery")) continue;
            PsiExpression qualifierExpr = call.getMethodExpression().getQualifierExpression();
            if (qualifierExpr == null) continue;
            String qualifierText = qualifierExpr.getText();
            if (!criteriaQueryVars.contains(qualifierText)) continue;
            String var = findAssignedVariable(call);
            if (var != null) ctx.subqueries.put(var, new SubqueryModel());
        }
        if (ctx.subqueries.isEmpty()) return;

        // 2. 子查询子句：sq.from / sq.select / sq.where / sq.correlate
        List<String> pendingVars = new ArrayList<>();
        List<PsiMethodCallExpression> pendingCalls = new ArrayList<>();
        for (PsiMethodCallExpression call : allCalls) {
            String methodName = call.getMethodExpression().getReferenceName();
            if (methodName == null) continue;
            PsiExpression qualifierExpr = call.getMethodExpression().getQualifierExpression();
            if (qualifierExpr == null) continue;
            String qualifierText = qualifierExpr.getText();
            if (!ctx.subqueries.containsKey(qualifierText)) continue;
            switch (methodName) {
                case "from": {
                    PsiExpression[] fromArgs = call.getArgumentList().getExpressions();
                    PsiExpression arg = fromArgs.length > 0 ? fromArgs[0] : null;
                    SubqueryModel current = ctx.subqueries.get(qualifierText);
                    ctx.subqueries.put(qualifierText, new SubqueryModel(
                            extractEntityClassName(arg),
                            current.getSelectFieldName(),
                            current.getPredicates(),
                            current.getDegradedReason()));
                    String rootVar = findAssignedVariable(call);
                    if (rootVar != null) ctx.subRoots.add(rootVar);
                    break;
                }
                case "select": {
                    PsiExpression[] selectArgs = call.getArgumentList().getExpressions();
                    PsiExpression arg = selectArgs.length > 0 ? selectArgs[0] : null;
                    String fieldName = arg != null ? extractFieldNameFromArg(arg) : null;
                    if (fieldName != null) {
                        SubqueryModel current = ctx.subqueries.get(qualifierText);
                        ctx.subqueries.put(qualifierText, new SubqueryModel(
                                current.getEntityClassName(),
                                fieldName,
                                current.getPredicates(),
                                current.getDegradedReason()));
                    }
                    break;
                }
                case "where": {
                    List<PredicateModel> children = new ArrayList<>();
                    for (PsiExpression arg : call.getArgumentList().getExpressions()) {
                        PredicateModel p = extractPredicate(arg, ctx);
                        if (p != null) children.add(p);
                    }
                    SubqueryModel current = ctx.subqueries.get(qualifierText);
                    List<PredicateModel> merged = new ArrayList<>(current.getPredicates());
                    merged.addAll(children);
                    ctx.subqueries.put(qualifierText, new SubqueryModel(
                            current.getEntityClassName(),
                            current.getSelectFieldName(),
                            merged,
                            current.getDegradedReason()));
                    break;
                }
                case "correlate": {
                    SubqueryModel current = ctx.subqueries.get(qualifierText);
                    ctx.subqueries.put(qualifierText, new SubqueryModel(
                            current.getEntityClassName(),
                            current.getSelectFieldName(),
                            current.getPredicates(),
                            "correlate 关联子查询"));
                    break;
                }
                default:
                    pendingVars.add(qualifierText);
                    pendingCalls.add(call);
                    break;
            }
        }

        // 3. 多层嵌套检测：子查询变量链上的子查询（sq2 = sq1... 或 where 中引用其他子查询变量）
        for (int i = 0; i < pendingVars.size(); i++) {
            String varName = pendingVars.get(i);
            PsiMethodCallExpression call = pendingCalls.get(i);
            boolean referencesSubquery = false;
            for (PsiReferenceExpression ref : PsiTreeUtil.collectElementsOfType(call, PsiReferenceExpression.class)) {
                String refName = ref.getReferenceName();
                if (refName != null && ctx.subqueries.containsKey(refName) && !refName.equals(varName)) {
                    referencesSubquery = true;
                    break;
                }
            }
            if (referencesSubquery) {
                SubqueryModel current = ctx.subqueries.get(varName);
                if (current != null) {
                    ctx.subqueries.put(varName, new SubqueryModel(
                            current.getEntityClassName(),
                            current.getSelectFieldName(),
                            current.getPredicates(),
                            "多层嵌套子查询"));
                }
            }
        }
        // 子查询谓词内含 EXISTS/IN 子查询 → 同样按多层嵌套降级
        for (Map.Entry<String, SubqueryModel> entry : ctx.subqueries.entrySet()) {
            SubqueryModel sub = entry.getValue();
            if (containsSubqueryPredicate(sub.getPredicates())) {
                ctx.subqueries.put(entry.getKey(), new SubqueryModel(
                        sub.getEntityClassName(),
                        sub.getSelectFieldName(),
                        sub.getPredicates(),
                        "多层嵌套子查询"));
            }
        }
    }

    /** 谓词树中是否含子查询节点（有界：子查询内不再嵌子查询）。 */
    private static boolean containsSubqueryPredicate(@NotNull List<PredicateModel> predicates) {
        for (PredicateModel predicate : predicates) {
            if (containsSubqueryPredicate(predicate)) return true;
        }
        return false;
    }

    private static boolean containsSubqueryPredicate(@NotNull PredicateModel predicate) {
        if (predicate instanceof PredicateModel.Exists || predicate instanceof PredicateModel.InSubQuery) {
            return true;
        }
        if (predicate instanceof PredicateModel.Group group) {
            for (PredicateModel child : group.getChildren()) {
                if (containsSubqueryPredicate(child)) return true;
            }
            return false;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // UPDATE SET 解析（T-J6）
    // ------------------------------------------------------------------

    /**
     * 从 update.set(root.get("name"), v) / update.set("name", v) / setNull 提取 SET 子句。
     */
    @Nullable
    private static CriteriaSetClause extractSetClause(@NotNull PsiMethodCallExpression call, boolean nullValue) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        String fieldName = null;
        if (args.length > 0) {
            PsiExpression arg = args[0];
            if (arg instanceof PsiMethodCallExpression argCall
                    && "get".equals(argCall.getMethodExpression().getReferenceName())) {
                fieldName = extractFieldName(argCall);
            } else if (arg instanceof PsiLiteralExpression) {
                Object value = ((PsiLiteralExpression) arg).getValue();
                fieldName = value instanceof String ? (String) value : null;
            } else if (arg instanceof PsiReferenceExpression) {
                fieldName = ((PsiReferenceExpression) arg).getReferenceName();
            }
        }
        if (fieldName == null) return null;
        String value = nullValue ? "null" : (args.length > 1 ? args[1].getText() : "null");
        return new CriteriaSetClause(fieldName, value);
    }
}
