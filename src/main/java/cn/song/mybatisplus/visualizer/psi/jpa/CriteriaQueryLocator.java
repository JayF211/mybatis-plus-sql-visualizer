package cn.song.mybatisplus.visualizer.psi.jpa;

import cn.song.mybatisplus.visualizer.model.JoinType;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassObjectAccessExpression;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeArgumentList;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * JPA CriteriaQuery 定位器（F-JPA-02）。
 *
 * 从光标位置识别 JPA CriteriaBuilder 强类型查询的 PSI 调用链，构建 [CriteriaQueryModel] 中间表示。
 *
 * 识别模式：
 *  - `cb.createQuery(Xxx.class)` 或 `cb.createQuery()` 创建查询
 *  - `cq.from(Xxx.class)` 设置根实体
 *  - `root.get("fieldName")` 或 `root.get(Xxx_.fieldName)` 引用列
 *  - `cb.equal/greaterThan/lessThan/in/like/null...` 构建条件
 *  - `cq.where(predicate...)` 设置 WHERE
 *  - `cq.orderBy(cb.asc(root.get("field")))` 设置排序
 *  - `cq.multiselect(root.get("a"), root.get("b"))` 或 `cq.select(root)` 设置 SELECT
 *  - `root.join("xxx", JoinType.LEFT)` 设置 JOIN
 *
 * 定位策略：
 *  1. 从光标向上查找包含 CriteriaQuery 相关调用的代码块或方法体；
 *  2. 扫描范围内全部 PsiMethodCallExpression；
 *  3. 识别 `createQuery` / `from` / `where` 等调用并提取中间模型。
 */
public final class CriteriaQueryLocator {

    // CriteriaQuery 方法名
    private static final Set<String> CRITERIA_QUERY_METHODS = Set.of(
            "from", "select", "multiselect", "where", "orderBy",
            "groupBy", "having", "distinct", "getRestriction"
    );

    // CriteriaBuilder 条件方法名
    private static final Set<String> CB_CONDITION_METHODS = Set.of(
            "equal", "notEqual",
            "greaterThan", "greaterThanOrEqualTo",
            "lessThan", "lessThanOrEqualTo",
            "between", "isNull", "isNotNull",
            "like", "notLike", "in", "notIn",
            "and", "or", "not"
    );

    // CriteriaBuilder 排序辅助方法
    private static final Set<String> CB_ORDER_METHODS = Set.of("asc", "desc");

    // 关联属性 join 类型枚举名（默认 JoinType.LEFT）
    private static final Set<String> JOIN_TYPE_LEFT = Set.of("LEFT", "Left");
    private static final Set<String> JOIN_TYPE_INNER = Set.of("INNER", "Inner");
    private static final Set<String> JOIN_TYPE_RIGHT = Set.of("RIGHT", "Right");

    // CriteriaBuilder 聚合函数名
    private static final Set<String> AGGREGATE_FUNCTIONS = Set.of("COUNT", "SUM", "MAX", "MIN", "AVG");

    /** 单例（保持对 Kotlin `CriteriaQueryLocator.method()` 调用的二进制兼容）。 */
    public static final CriteriaQueryLocator INSTANCE = new CriteriaQueryLocator();

    private CriteriaQueryLocator() {
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

        // 查找 CriteriaQuery 变量与 from 调用
        PsiClass entityClass = null;
        String entityClassName = null;
        String resultClassName = null;
        Set<String> criteriaQueryVars = new LinkedHashSet<>();
        Set<String> roots = new LinkedHashSet(); // from 引入的 Root 变量名
        List<PredicateModel> predicates = new ArrayList<>();
        List<GroupByModel> groupBys = new ArrayList<>();
        List<PredicateModel> havings = new ArrayList<>();
        List<OrderByModel> orderBys = new ArrayList<>();
        List<SelectColumn> selectColumns = new ArrayList<>();
        List<JoinModel> joins = new ArrayList<>();

        // 第一遍：识别 createQuery（确认 CriteriaQuery 变量）
        // 模式：`CriteriaQuery<X> cq = cb.createQuery(X.class)` 或内联 `cb.createQuery(X.class)`
        // 关键：createQuery 的**赋值目标**才是 CriteriaQuery 变量，而非 qualifier（那是 CriteriaBuilder）
        for (PsiMethodCallExpression call : allCalls) {
            String methodName = call.getMethodExpression().getReferenceName();
            if (methodName == null) continue;
            if (!methodName.equals("createQuery")) continue;
            // 提取结果类型（createQuery(Xxx.class) 的类型参数）
            resultClassName = resultClassName != null ? resultClassName : extractResultClass(call);
            // 查找赋值目标变量（cq）
            String cqVar = findAssignedVariable(call);
            if (cqVar != null) {
                criteriaQueryVars.add(cqVar);
            }
            // 未赋值给变量（内联调用）：记录 qualifier 作为潜在 CriteriaBuilder，后续通过 from 推断
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

        // 仍无 CriteriaQuery 变量 → 不是 CriteriaQuery 调用链
        if (criteriaQueryVars.isEmpty()) return null;

        // 第二遍：收集各子句
        for (PsiMethodCallExpression call : allCalls) {
            String methodName = call.getMethodExpression().getReferenceName();
            if (methodName == null) continue;
            PsiExpression qualifierExpr = call.getMethodExpression().getQualifierExpression();
            if (qualifierExpr == null) continue;
            String qualifierText = qualifierExpr.getText();

            if (methodName.equals("from") && criteriaQueryVars.contains(qualifierText)) {
                // cq.from(Xxx.class)
                PsiExpression[] fromArgs = call.getArgumentList().getExpressions();
                PsiExpression fromArg = fromArgs.length > 0 ? fromArgs[0] : null;
                entityClass = extractEntityClass(fromArg);
                entityClassName = entityClassName != null ? entityClassName : extractEntityClassName(fromArg);
                // Root 变量：from 调用的直接赋值目标
                String rootVar = findAssignedVariable(call);
                if (rootVar != null) roots.add(rootVar);
            } else if (methodName.equals("where") && criteriaQueryVars.contains(qualifierText)) {
                // cq.where(predicate...)
                for (PsiExpression arg : call.getArgumentList().getExpressions()) {
                    PredicateModel p = extractPredicate(arg);
                    if (p != null) predicates.add(p);
                }
            } else if (methodName.equals("groupBy") && criteriaQueryVars.contains(qualifierText)) {
                // cq.groupBy(root.get("field"))
                for (PsiExpression arg : call.getArgumentList().getExpressions()) {
                    GroupByModel g = extractGroupBy(arg);
                    if (g != null) groupBys.add(g);
                }
            } else if (methodName.equals("having") && criteriaQueryVars.contains(qualifierText)) {
                // cq.having(predicate...)
                for (PsiExpression arg : call.getArgumentList().getExpressions()) {
                    PredicateModel p = extractPredicate(arg);
                    if (p != null) havings.add(p);
                }
            } else if (methodName.equals("orderBy") && criteriaQueryVars.contains(qualifierText)) {
                // cq.orderBy(cb.asc(...)/cb.desc(...))
                for (PsiExpression arg : call.getArgumentList().getExpressions()) {
                    OrderByModel o = extractOrderBy(arg);
                    if (o != null) orderBys.add(o);
                }
            } else if ((methodName.equals("multiselect") || methodName.equals("select"))
                    && criteriaQueryVars.contains(qualifierText)) {
                // cq.multiselect(...) / cq.select(...)
                selectColumns.addAll(extractSelectColumns(call, roots));
            } else if (methodName.equals("join") && roots.contains(qualifierText)) {
                // root.join(...)
                JoinModel j = extractJoin(call);
                if (j != null) joins.add(j);
            } else if (CB_CONDITION_METHODS.contains(methodName) && isCriteriaBuilderCall(call)) {
                // CriteriaBuilder 条件方法（cb.equal / cb.greaterThan ...）
                // 排除作为 cb.not/cb.and/cb.or 参数的嵌套调用（避免重复提取）
                if (!isNestedInParentPredicate(call)) {
                    PredicateModel p = extractPredicate(call);
                    if (p != null) predicates.add(p);
                }
            }
        }

        // 没有实体信息但存在条件 → 仍返回（converter 层降级处理）
        if (entityClassName == null && predicates.isEmpty()) return null;

        return new CriteriaQueryModel(
                entityClassName,
                entityClass,
                selectColumns,
                predicates,
                groupBys,
                havings,
                orderBys,
                joins,
                resultClassName
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
            if (current instanceof PsiMethod) return ((PsiMethod) current).getBody();
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
        if (parent instanceof PsiLocalVariable && ((PsiLocalVariable) parent).getInitializer() == call) {
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
     * 判断方法调用是否为 cb.xxx() 形式（qualifier 为已知 CriteriaBuilder 变量名）。
     *
     * 由于 CriteriaBuilder 变量类型通常不可 resolve（LightPlatform 无 JPA classpath），
     * 这里采用启发式：方法名属于 CB 条件方法集，且 qualifier 是简单标识符引用（非 cq/root 等已知实体集合）。
     */
    private static boolean isCriteriaBuilderCall(@NotNull PsiMethodCallExpression call) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) return false;
        if (!CB_CONDITION_METHODS.contains(methodName)) return false;
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        // 简单引用表达式（cb.equal(...) 中 qualifier 是 cb）
        return qualifier instanceof PsiReferenceExpression;
    }

    /**
     * 检查 call 是否嵌套在父级 CriteriaBuilder 谓词内（cb.not/cb.and/cb.or）。
     * 嵌套调用会在处理父级时被 extractPredicate(args[0]) 提取，避免重复。
     */
    private static boolean isNestedInParentPredicate(@NotNull PsiMethodCallExpression call) {
        if (!(call.getParent() instanceof PsiMethodCallExpression)) return false;
        PsiMethodCallExpression parent = (PsiMethodCallExpression) call.getParent();
        String parentName = parent.getMethodExpression().getReferenceName();
        if (parentName == null) return false;
        return parentName.equals("and") || parentName.equals("or") || parentName.equals("not");
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
     * 从 createQuery 类型参数提取结果类名（`cb.createQuery(Xxx.class)` 的类型参数）。
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
        // 类型参数形式：cb.createQuery(Xxx.class) 已由 PsiMethodCallExpression 的类型参数提供
        PsiTypeArgumentList typeArgList = call.getTypeArgumentList();
        if (typeArgList != null) {
            PsiType[] typeArgs = typeArgList.getTypeArguments();
            if (typeArgs.length > 0 && typeArgs[0] instanceof PsiClassType) {
                return ((PsiClassType) typeArgs[0]).getClassName();
            }
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
     *  - `root.get(Xxx_.fieldName)`：静态方法引用
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

    // ------------------------------------------------------------------
    // SELECT 解析
    // ------------------------------------------------------------------

    /**
     * 从 cq.select(...) / cq.multiselect(...) 提取 SELECT 列。
     *
     * 增强版（P1-5 聚合函数支持）：
     *  - cb.count(root) → SelectColumn(null, aggregate=AggregateInfo("COUNT"))
     *  - cb.sum(root.get("field")) → SelectColumn(null, aggregate=AggregateInfo("SUM", "field"))
     */
    @NotNull
    private static List<SelectColumn> extractSelectColumns(@NotNull PsiMethodCallExpression call, @NotNull Set<String> roots) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) return List.of();
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length == 0) return List.of();

        // cq.select(root) → 全列（返回空列表，converter 按全列处理）
        if (methodName.equals("select") && args.length == 1) {
            PsiExpression arg = args[0];
            if (arg instanceof PsiReferenceExpression
                    && roots.contains(((PsiReferenceExpression) arg).getReferenceName())) {
                return List.of(); // 全列语义
            }
        }

        // cq.multiselect(...) / cq.select(...) 各参数
        List<SelectColumn> result = new ArrayList<>();
        for (PsiExpression arg : args) {
            // 尝试解析为聚合函数调用
            AggregateInfo aggregate = extractAggregate(arg);
            if (aggregate != null) {
                result.add(new SelectColumn(aggregate.getFieldName(), null, aggregate));
                continue;
            }

            // 否则尝试 root.get(...)
            if (!(arg instanceof PsiMethodCallExpression)) continue;
            PsiMethodCallExpression getCall = (PsiMethodCallExpression) arg;
            if (!"get".equals(getCall.getMethodExpression().getReferenceName())) continue;
            String fieldName = extractFieldName(getCall);
            if (fieldName == null) continue;
            result.add(new SelectColumn(fieldName, null, null));
        }
        return result;
    }

    /**
     * 尝试从 SELECT 参数表达式提取聚合函数信息。
     */
    @Nullable
    private static AggregateInfo extractAggregate(@NotNull PsiExpression arg) {
        if (!(arg instanceof PsiMethodCallExpression)) return null;
        PsiMethodCallExpression call = (PsiMethodCallExpression) arg;
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) return null;

        String upperName = methodName.toUpperCase(Locale.ROOT);
        if (!AGGREGATE_FUNCTIONS.contains(upperName)) return null;

        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (!(qualifier instanceof PsiReferenceExpression)) return null;

        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length == 0) return new AggregateInfo(upperName, null, false);

        PsiExpression firstArg = args[0];
        String fieldName = extractFieldNameFromArg(firstArg);

        return new AggregateInfo(upperName, fieldName, false);
    }

    // ------------------------------------------------------------------
    // WHERE / Predicate 解析
    // ------------------------------------------------------------------

    /**
     * 从 where 的实参（单个 Predicate 或 Predicate 数组）提取条件模型。
     */
    @Nullable
    private static PredicateModel extractPredicate(@Nullable PsiExpression expr) {
        if (expr == null) return null;
        // 直接是方法调用：cb.equal(...) / cb.and(...) / cb.or(...) / cb.not(...)
        if (expr instanceof PsiMethodCallExpression) {
            String methodName = ((PsiMethodCallExpression) expr).getMethodExpression().getReferenceName();
            // 仅处理 CriteriaBuilder 条件方法，排除 Java 集合工具方法（toArray 等）
            if (methodName != null && (CB_CONDITION_METHODS.contains(methodName)
                    || methodName.equals("and") || methodName.equals("or") || methodName.equals("not"))) {
                return extractPredicateFromCall((PsiMethodCallExpression) expr);
            }
            return null;
        }
        // 变量引用（如 predicates）→ 尝试解析为谓词数组
        if (expr instanceof PsiReferenceExpression) {
            return resolvePredicateVariable((PsiReferenceExpression) expr);
        }
        // 其他表达式 → 忽略（不生成 Raw 谓词）
        return null;
    }

    /**
     * 尝试解析谓词变量（如 predicates）为谓词列表。
     * 场景：cq.where(predicates.toArray(new Predicate[0]))
     * 由于 StaticPredicateModel 不支持列表展开，此处返回 null 避免生成错误的 Raw 谓词。
     * 实际谓词已通过 CB_CONDITION_METHODS 路径提取（cb.equal/greaterThan 等）。
     */
    @Nullable
    private static PredicateModel resolvePredicateVariable(@NotNull PsiReferenceExpression expr) {
        // 变量引用不生成 Raw 谓词，避免 "predicates.toArray(...)" 出现在 WHERE 子句
        return null;
    }

    /**
     * 从 CriteriaBuilder 条件调用提取 PredicateModel。
     *
     * 支持的调用模式：
     *  - cb.equal(root.get("a"), value)     → Simple(a, "=", value)
     *  - cb.greaterThan(root.get("a"), v)   → Simple(a, ">", v)
     *  - cb.isNull(root.get("a"))           → IsNull(a, false)
     *  - cb.between(root.get("a"), lo, hi)  → Between(a, lo, hi)
     *  - cb.like(root.get("a"), pattern)    → Like(a, false, pattern)
     *  - cb.in(root.get("a"))               → In(a, false, [])
     *  - cb.and(p1, p2)                     → Group(AND, [p1, p2])
     *  - cb.or(p1, p2)                      → Group(OR, [p1, p2])
     *  - cb.not(p)                          → Group(AND, [p], negated=true)
     */
    @NotNull
    private static PredicateModel extractPredicateFromCall(@NotNull PsiMethodCallExpression call) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) return PredicateModel.Raw(call.getText());
        PsiExpression[] args = call.getArgumentList().getExpressions();

        return switch (methodName) {
            case "equal" -> buildSimple(call, args, "=");
            case "notEqual" -> buildSimple(call, args, "!=");
            case "greaterThan" -> buildSimple(call, args, ">");
            case "greaterThanOrEqualTo" -> buildSimple(call, args, ">=");
            case "lessThan" -> buildSimple(call, args, "<");
            case "lessThanOrEqualTo" -> buildSimple(call, args, "<=");
            case "between" -> buildBetween(call, args);
            case "isNull" -> buildIsNull(call, args, false);
            case "isNotNull" -> buildIsNull(call, args, true);
            case "like" -> buildLike(call, args, false);
            case "notLike" -> buildLike(call, args, true);
            case "in" -> buildIn(call, args, false);
            case "notIn" -> buildIn(call, args, true);
            case "and" -> buildGroup(args, GroupConnector.AND);
            case "or" -> buildGroup(args, GroupConnector.OR);
            case "not" -> buildNot(args);
            default -> PredicateModel.Raw(call.getText());
        };
    }

    /** 构建二元比较条件（eq/ne/gt/ge/lt/le）。 */
    @NotNull
    private static PredicateModel buildSimple(@NotNull PsiMethodCallExpression call, @NotNull PsiExpression[] args, @NotNull String op) {
        if (args.length < 2) return PredicateModel.Raw(call.getText());
        String fieldName = args.length > 0 ? extractFieldNameFromArg(args[0]) : null;
        if (fieldName == null) return PredicateModel.Raw(call.getText());
        String valueExpr = args[1].getText();
        return new PredicateModel.Simple(fieldName, op, valueExpr);
    }

    /** 构建 BETWEEN 条件。 */
    @NotNull
    private static PredicateModel buildBetween(@NotNull PsiMethodCallExpression call, @NotNull PsiExpression[] args) {
        if (args.length < 3) return PredicateModel.Raw(call.getText());
        String fieldName = args.length > 0 ? extractFieldNameFromArg(args[0]) : null;
        if (fieldName == null) return PredicateModel.Raw(call.getText());
        return new PredicateModel.Between(fieldName, args[1].getText(), args[2].getText());
    }

    /** 构建 IS NULL / IS NOT NULL 条件。 */
    @NotNull
    private static PredicateModel buildIsNull(@NotNull PsiMethodCallExpression call, @NotNull PsiExpression[] args, boolean negated) {
        if (args.length == 0) return PredicateModel.Raw(call.getText());
        String fieldName = args.length > 0 ? extractFieldNameFromArg(args[0]) : null;
        if (fieldName == null) return PredicateModel.Raw(call.getText());
        return new PredicateModel.IsNull(fieldName, negated);
    }

    /** 构建 LIKE / NOT LIKE 条件。 */
    @NotNull
    private static PredicateModel buildLike(@NotNull PsiMethodCallExpression call, @NotNull PsiExpression[] args, boolean negated) {
        if (args.length < 2) return PredicateModel.Raw(call.getText());
        String fieldName = args.length > 0 ? extractFieldNameFromArg(args[0]) : null;
        if (fieldName == null) return PredicateModel.Raw(call.getText());
        return new PredicateModel.Like(fieldName, negated, args[1].getText());
    }

    /**
     * 构建 IN / NOT IN 条件。
     * 支持两种形态：
     *  - cb.in(root.get("a"), v1, v2, ...) → 直接参数
     *  - cb.in(root.get("a")).value(statuses) → 链式调用（提取 .value() 的参数）
     */
    @NotNull
    private static PredicateModel buildIn(@NotNull PsiMethodCallExpression call, @NotNull PsiExpression[] args, boolean negated) {
        if (args.length == 0) return PredicateModel.Raw(call.getText());
        String fieldName = args.length > 0 ? extractFieldNameFromArg(args[0]) : null;
        if (fieldName == null) return PredicateModel.Raw(call.getText());
        // 直接参数：cb.in(root.get("a"), v1, v2, ...)
        List<String> directValues = args.length > 1
                ? Arrays.stream(args, 1, args.length).map(PsiExpression::getText).collect(Collectors.toList())
                : List.of();
        // 链式调用：cb.in(root.get("a")).value(statuses)
        List<String> chainedValues = extractInValuesFromChain(call);
        List<String> values = new ArrayList<>(directValues);
        values.addAll(chainedValues);
        if (values.isEmpty()) {
            // 无值时添加占位符标记，面板可输入多值
            values.add("__IN_PLACEHOLDER__");
        }
        return new PredicateModel.In(fieldName, negated, values);
    }

    /**
     * 从 cb.in(...).value(x) 链式调用中提取 IN 值列表。
     * 如果 cb.in() 的父方法是 .value()，提取其参数作为 IN 值。
     */
    @NotNull
    private static List<String> extractInValuesFromChain(@NotNull PsiMethodCallExpression call) {
        if (!(call.getParent() instanceof PsiMethodCallExpression)) return List.of();
        PsiMethodCallExpression parent = (PsiMethodCallExpression) call.getParent();
        String parentName = parent.getMethodExpression().getReferenceName();
        if (parentName == null) return List.of();
        if (!parentName.equals("value")) return List.of();
        List<String> result = new ArrayList<>();
        for (PsiExpression e : parent.getArgumentList().getExpressions()) {
            result.add(e.getText());
        }
        return result;
    }

    /** 构建 AND / OR 条件组。 */
    @NotNull
    private static PredicateModel buildGroup(@NotNull PsiExpression[] args, @NotNull GroupConnector connector) {
        List<PredicateModel> children = new ArrayList<>();
        for (PsiExpression a : args) {
            PredicateModel p = extractPredicate(a);
            if (p != null) children.add(p);
        }
        return new PredicateModel.Group(connector, children, false);
    }

    /** 构建 NOT 条件（cb.not(p) → Group(AND, [p], negated=true)）。 */
    @NotNull
    private static PredicateModel buildNot(@NotNull PsiExpression[] args) {
        if (args.length == 0) return PredicateModel.Raw("cb.not()");
        PredicateModel child = extractPredicate(args[0]);
        if (child == null) return PredicateModel.Raw(args[0].getText());
        return new PredicateModel.Group(GroupConnector.AND, List.of(child), true);
    }

    /**
     * 从实参表达式提取字段名（支持 root.get(...) 或字符串字面量）。
     */
    @Nullable
    private static String extractFieldNameFromArg(@NotNull PsiExpression arg) {
        if (arg instanceof PsiMethodCallExpression) {
            PsiMethodCallExpression mce = (PsiMethodCallExpression) arg;
            if ("get".equals(mce.getMethodExpression().getReferenceName())) {
                return extractFieldName(mce);
            }
        }
        if (arg instanceof PsiLiteralExpression) {
            Object value = ((PsiLiteralExpression) arg).getValue();
            return value instanceof String ? (String) value : null;
        }
        return null;
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
        if (!(expr instanceof PsiMethodCallExpression)) return null;
        PsiMethodCallExpression mce = (PsiMethodCallExpression) expr;
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
    // JOIN 解析
    // ------------------------------------------------------------------

    /**
     * 从 root.join("field", JoinType.LEFT) 提取 JoinModel。
     */
    @Nullable
    private static JoinModel extractJoin(@NotNull PsiMethodCallExpression call) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length == 0) return null;

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
        if (fieldName == null) return null;

        // JoinType（可选，默认 LEFT）
        JoinType joinType;
        if (args.length > 1) {
            PsiExpression typeArg = args[1];
            if (typeArg instanceof PsiReferenceExpression) {
                joinType = mapJoinType(((PsiReferenceExpression) typeArg).getReferenceName());
            } else {
                joinType = JoinType.LEFT;
            }
        } else {
            joinType = JoinType.LEFT;
        }

        return new JoinModel(fieldName, null, joinType, null);
    }

    /** JoinType 枚举名映射。 */
    @NotNull
    private static JoinType mapJoinType(@Nullable String enumName) {
        if (enumName != null) {
            if (JOIN_TYPE_LEFT.contains(enumName)) return JoinType.LEFT;
            if (JOIN_TYPE_INNER.contains(enumName)) return JoinType.INNER;
            if (JOIN_TYPE_RIGHT.contains(enumName)) return JoinType.RIGHT;
        }
        return JoinType.LEFT;
    }
}
