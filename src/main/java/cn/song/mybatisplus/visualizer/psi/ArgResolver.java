package cn.song.mybatisplus.visualizer.psi;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.WrapperArg;
import cn.song.mybatisplus.visualizer.model.WrapperCall;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import cn.song.mybatisplus.visualizer.util.NamingStrategy;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassObjectAccessExpression;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiMethodReferenceExpression;
import com.intellij.psi.PsiPrefixExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 实参解析器：把 Wrapper 方法实参 {@link PsiExpression} 解析为 {@link WrapperArg}。
 *
 * <p>处理四种实参：</p>
 * <ul>
 *  <li>方法引用 {@code Entity::getXxx} → {@link WrapperArg.ColumnArg}（列引用，F-13 Lambda 解析）</li>
 *  <li>字面量 / 变量 / 表达式 → {@link WrapperArg.ValueArg}（{@link ExpressionEvaluator} 求值）</li>
 *  <li>Lambda {@code w -> w.eq(...)} → {@link WrapperArg.NestedArg}（and/or 嵌套，递归解析）</li>
 *  <li>其他（new 表达式、集合等）→ {@link WrapperArg.ValueArg} 占位</li>
 * </ul>
 */
public final class ArgResolver {

    private ArgResolver() {}

    /** MPJ on 函数名。 */
    private static final String ON_METHOD = "on";

    /** T-1800：selectCollection / selectAssociation 方法名（类参需解析为 EntityRefArg）。 */
    private static final String SELECT_COLLECTION = "selectCollection";
    private static final String SELECT_ASSOCIATION = "selectAssociation";

    /** MPJ selectFunc / applyFunc 方法名（第二参为 {@code func -> func.accept(...).values(...)} lambda）。 */
    private static final String SELECT_FUNC = "selectFunc";
    private static final String APPLY_FUNC = "applyFunc";

    /** MPJ selectFilter 方法名（T-M21-01：第一参实体类引用、第二参谓词 lambda）。 */
    private static final String SELECT_FILTER = "selectFilter";

    /** MPJ selectAsClass 方法名（两个实参均为实体类引用，converter 按字段交集映射）。 */
    private static final String SELECT_AS_CLASS = "selectAsClass";

    /** selectSub 方法（子查询列，第一参为子查询实体类引用）。 */
    private static final String SELECT_SUB = "selectSub";

    /** allEq 方法名（T-M21-04：Map 实参静态展开为键值对）。 */
    private static final String ALL_EQ = "allEq";

    /** MPJ join 方法名集合（第一参为关联实体类引用；join 为自定义关键字形态，第一参为关键字字符串）。 */
    private static final Set<String> JOIN_METHODS =
        Set.of("leftJoin", "innerJoin", "rightJoin", "fullJoin", "join");

    /** 支持子查询条件的比较方法（{@code eq(col, Sub.class, wrapper)} 等官方 sub-query API）。 */
    private static final Set<String> SUBQUERY_CONDITION_METHODS =
        Set.of("eq", "ne", "gt", "ge", "lt", "le", "in", "notIn");

    /** UNION 方法（第一参为子查询实体类引用）。 */
    private static final Set<String> UNION_METHODS = Set.of("union", "unionAll");

    /** MPJ 中第二参为「目标 VO 字段/别名」的方法名集合（selectAs、selectCount、selectSum/Min/Max/Avg/Len）。 */
    private static final Set<String> ALIAS_SECOND_ARG_METHODS = Set.of(
        "selectAs", "selectCount", "selectSum", "selectMin", "selectMax", "selectAvg", "selectLen");

    /**
     * 解析单个实参表达式。
     *
     * @param expression     实参（PSI 表达式）
     * @param table          实体表信息（解析 Lambda 列引用用，可为 null）
     * @param entityClass    外层实体类名（嵌套链填充用，可为 null）
     * @param asAlias        true 表示该位置是 MPJ selectAs 的目标 VO 字段（方法引用 → {@link WrapperArg.AliasArg}，
     *                       保留驼峰属性名作别名，不做 snake_case 列名转换）
     * @param resolveVariables 变量求值开关（M14.6「替换 ? 占位符」配置）：false 时跳过
     *                         {@link ExpressionEvaluator} 变量追踪，可静态求值的变量也渲染为 {@code ?}；
     *                         字面量（代码直接写的常量）恒内联，不受此开关影响
     * @return WrapperArg 模型
     */
    public static WrapperArg resolve(
        PsiExpression expression,
        TableInfo table,
        String entityClass,
        boolean asAlias,
        boolean resolveVariables
    ) {
        // selectAs(Entity::getX, VO::getY)：第二参方法引用 → 目标 VO 属性名（别名）+ 返回类型
        if (asAlias && expression instanceof PsiMethodReferenceExpression) {
            PsiMethodReferenceExpression methodRef = (PsiMethodReferenceExpression) expression;
            String name = methodRef.getReferenceName();
            if (name == null) {
                return new WrapperArg.ValueArg(new SqlValue.Placeholder(expression.getText()));
            }
            String property = getterToProperty(name);
            // Extract return type for diagram column type display
            String returnType = null;
            PsiType functionalInterfaceType = methodRef.getFunctionalInterfaceType();
            if (functionalInterfaceType instanceof PsiClassType) {
                PsiType[] parameters = ((PsiClassType) functionalInterfaceType).getParameters();
                if (parameters.length > 0) {
                    returnType = parameters[0].getCanonicalText();
                }
            }
            return property != null
                ? new WrapperArg.AliasArg(property, returnType)
                : new WrapperArg.ValueArg(new SqlValue.Placeholder(expression.getText()));
        }
        if (expression instanceof PsiMethodReferenceExpression) {
            // Lambda 方法引用：Entity::getXxx → 列引用
            return new WrapperArg.ColumnArg(resolveLambdaColumn((PsiMethodReferenceExpression) expression, table));
        }
        if (expression instanceof PsiMethodCallExpression) {
            // MPJ on(col1, col2)：两列比较实参（F-11）
            return resolveMethodCallArg((PsiMethodCallExpression) expression, table, resolveVariables);
        }
        if (expression instanceof PsiLiteralExpression) {
            // 字面量：直接求值（不受「替换 ? 占位符」开关影响，代码常量恒内联）
            return new WrapperArg.ValueArg(ExpressionEvaluator.evaluate(expression));
        }
        if (expression instanceof PsiLambdaExpression) {
            // Lambda 表达式体：and(w -> w.eq(...)) → 嵌套链
            return new WrapperArg.NestedArg(
                resolveNestedLambda((PsiLambdaExpression) expression, table, entityClass));
        }
        // 变量 / 算术 / new 表达式：求值为字面量或占位符（开关关闭时恒为占位符）
        return resolveValueArg(expression, resolveVariables);
    }

    /** 便捷重载：非别名位置（asAlias = false）。 */
    public static WrapperArg resolve(
        PsiExpression expression,
        TableInfo table,
        String entityClass,
        boolean resolveVariables
    ) {
        return resolve(expression, table, entityClass, false, resolveVariables);
    }

    /**
     * 非字面量实参求值（M14.6）：resolveVariables=false 时跳过变量追踪，直接渲染 {@code ?}（保留表达式原文作 source）。
     */
    private static WrapperArg resolveValueArg(PsiExpression expression, boolean resolveVariables) {
        return new WrapperArg.ValueArg(
            resolveVariables
                ? ExpressionEvaluator.evaluate(expression)
                : new SqlValue.Placeholder(expression.getText()));
    }

    /**
     * 解析方法调用实参：MPJ 的 {@code on(col1, col2)} → {@link WrapperArg.OnConditionArg}。
     * 其他方法调用（如集合工具调用）→ 占位符（受 resolveVariables 开关控制）。
     */
    private static WrapperArg resolveMethodCallArg(
        PsiMethodCallExpression call,
        TableInfo table,
        boolean resolveVariables
    ) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (ON_METHOD.equals(methodName)) {
            PsiExpression[] args = call.getArgumentList().getExpressions();
            if (args.length >= 2) {
                ColumnRef left = args[0] instanceof PsiMethodReferenceExpression
                    ? resolveLambdaColumn((PsiMethodReferenceExpression) args[0], table) : null;
                ColumnRef right = args[1] instanceof PsiMethodReferenceExpression
                    ? resolveLambdaColumn((PsiMethodReferenceExpression) args[1], table) : null;
                if (left != null && right != null) {
                    return new WrapperArg.OnConditionArg(left, right);
                }
            }
        }
        return resolveValueArg(call, resolveVariables);
    }

    // ------------------------------------------------------------------
    // Lambda 方法引用 → 列
    // ------------------------------------------------------------------

    /**
     * 解析 {@code Entity::getXxx} / {@code Entity::isXxx} 方法引用为列引用。
     *
     * <p>getter 名 → 属性名 → 查实体字段映射；无实体信息时按命名策略兜底推断。</p>
     */
    private static ColumnRef resolveLambdaColumn(PsiMethodReferenceExpression methodRef, TableInfo table) {
        String methodName = methodRef.getReferenceName();
        if (methodName == null) {
            return new ColumnRef(null, methodRef.getText(), null, null, null, null, null);
        }
        String propertyName = getterToProperty(methodName);
        if (propertyName == null) {
            return new ColumnRef(null, methodName, null, null, null, null, null);
        }

        // 优先从实体字段映射取列名
        ColumnInfo mapped = table != null ? table.columnByField(propertyName) : null;
        String columnName = mapped != null
            ? mapped.getColumnName()
            : NamingStrategy.INSTANCE.toSnakeCase(propertyName);

        // 提取方法引用接收者实体类名（用于 selectAs 图表列归属推断）
        String qualifiedName = methodRef.getQualifiedName();
        String entityClass;
        if (qualifiedName != null) {
            int separator = qualifiedName.indexOf("::");
            entityClass = separator >= 0 ? qualifiedName.substring(0, separator) : qualifiedName;
        } else {
            PsiElement firstChild = methodRef.getFirstChild();
            entityClass = firstChild != null ? firstChild.getText() : null;
        }

        return new ColumnRef(null, columnName, null, null, null, entityClass, null);
    }

    /**
     * getter 方法名转属性名：{@code getStationId} → {@code stationId}，{@code isEnabled} → {@code enabled}。
     * 非 getter/is 前缀返回 null。
     */
    private static String getterToProperty(String methodName) {
        String prefix;
        if (methodName.startsWith("get") && methodName.length() > 3) {
            prefix = "get";
        } else if (methodName.startsWith("is") && methodName.length() > 2) {
            prefix = "is";
        } else {
            return null;
        }
        String name = methodName.substring(prefix.length());
        // 首字母小写
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    // ------------------------------------------------------------------
    // Lambda 体 → 嵌套调用链
    // ------------------------------------------------------------------

    /**
     * 解析 {@code and(w -> w.eq(...).like(...))} 的 Lambda 体为嵌套调用链。
     *
     * <p>支持两种体：</p>
     * <ul>
     *  <li>表达式体（方法调用链）：直接收集链</li>
     *  <li>代码块体：收集块内全部方法调用（按源码顺序）</li>
     * </ul>
     */
    private static WrapperChain resolveNestedLambda(
        PsiLambdaExpression lambda,
        TableInfo table,
        String entityClass
    ) {
        PsiElement body = lambda.getBody();
        if (body == null) {
            return new WrapperChain(WrapperType.NESTED, entityClass, List.of());
        }
        List<PsiMethodCallExpression> calls = new ArrayList<>();

        if (body instanceof PsiMethodCallExpression) {
            // 表达式体：w.eq(...).like(...)
            calls.addAll(collectCallChain((PsiMethodCallExpression) body));
        } else {
            // 代码块体：收集块内所有方法调用
            List<PsiMethodCallExpression> collected =
                new ArrayList<>(PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression.class));
            collected.sort(Comparator.comparingInt(PsiElement::getTextOffset));
            calls.addAll(collected);
        }

        List<WrapperCall> wrapperCalls = new ArrayList<>();
        Set<Integer> seenEndOffsets = new LinkedHashSet<>();
        for (PsiMethodCallExpression call : calls) {
            if (seenEndOffsets.add(call.getTextRange().getEndOffset())) {
                wrapperCalls.add(toWrapperCall(call, table, entityClass));
            }
        }
        return new WrapperChain(WrapperType.NESTED, entityClass, wrapperCalls);
    }

    // ------------------------------------------------------------------
    // 调用链收集
    // ------------------------------------------------------------------

    /**
     * 从链尾调用沿 qualifier 回溯收集整条链（含自身）。
     *
     * <p>如 {@code new W().eq(a).like(b)} 传入 like 调用 → {@code [eq, like]}。</p>
     */
    public static List<PsiMethodCallExpression> collectCallChain(PsiMethodCallExpression call) {
        List<PsiMethodCallExpression> chain = new ArrayList<>();
        PsiMethodCallExpression current = call;
        while (current != null) {
            chain.add(0, current);
            PsiExpression qualifier = current.getMethodExpression().getQualifierExpression();
            current = qualifier instanceof PsiMethodCallExpression ? (PsiMethodCallExpression) qualifier : null;
        }
        return chain;
    }

    /**
     * 收集初始化表达式中的链式调用（{@code new LambdaQueryWrapper<>().eq(...)} 部分）。
     *
     * <p>找出所有「不是其他调用 qualifier」的链尾调用，各自回溯整链。
     * 注意：跳过 lambda 体内的调用（如 {@code and(x -> x.eq(...))} 中的 x.eq），
     * 它们由嵌套链解析处理，不应混入外层链。</p>
     */
    public static List<PsiMethodCallExpression> collectChainFromInitializer(PsiElement initializer) {
        List<PsiMethodCallExpression> result = new ArrayList<>();
        Collection<PsiMethodCallExpression> all =
            PsiTreeUtil.collectElementsOfType(initializer, PsiMethodCallExpression.class);
        for (PsiMethodCallExpression call : all) {
            // 跳过 lambda 体内的调用
            if (PsiTreeUtil.getParentOfType(call, PsiLambdaExpression.class) != null) {
                continue;
            }
            // 链尾：其父不是另一调用的 qualifier 引用
            PsiElement parent = call.getParent();
            if (parent instanceof PsiReferenceExpression && parent.getParent() instanceof PsiMethodCallExpression) {
                continue;
            }
            result.addAll(collectCallChain(call));
        }
        return result;
    }

    /**
     * 把 PsiMethodCallExpression 转为模型 {@link WrapperCall}。
     *
     * <p>MPJ 特殊参数位置：</p>
     * <ul>
     *  <li>{@code selectAs(Entity::getX, VO::getY)} / {@code selectCount(...)} 第二参按「目标 VO 字段」解析
     *      （{@link WrapperArg.AliasArg}）</li>
     *  <li>{@code selectFunc(模板, func -> func.accept(列).values(值), 别名)} 第二参按「聚合函数」解析
     *      （{@link WrapperArg.FuncArg}）</li>
     *  <li>其余参数按常规实参解析</li>
     * </ul>
     */
    public static WrapperCall toWrapperCall(
        PsiMethodCallExpression call,
        TableInfo table,
        String entityClass
    ) {
        return toWrapperCall(call, table, entityClass, true);
    }

    /** @see #toWrapperCall(PsiMethodCallExpression, TableInfo, String) */
    public static WrapperCall toWrapperCall(
        PsiMethodCallExpression call,
        TableInfo table,
        String entityClass,
        boolean resolveVariables
    ) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) {
            methodName = call.getMethodExpression().getText();
        }
        PsiExpression[] expressions = call.getArgumentList().getExpressions();
        // T-M21-04：allEq 重载的 null2IsNull 尾参（allEq(Map, null2IsNull) / allEq(boolean, Map, null2IsNull)
        // 末位布尔字面量）；运行时布尔（变量）不可静态判定 → 恒 false（仅字面量 true 生效）
        boolean allEqNull2IsNull = ALL_EQ.equals(methodName)
            && expressions.length > 0
            && expressions[expressions.length - 1] instanceof PsiLiteralExpression
            && Boolean.TRUE.equals(((PsiLiteralExpression) expressions[expressions.length - 1]).getValue());

        List<WrapperArg> args = new ArrayList<>();
        for (int index = 0; index < expressions.length; index++) {
            PsiExpression expr = expressions[index];
            if (ALL_EQ.equals(methodName)
                && !(expr instanceof PsiLambdaExpression)
                && !isBooleanLiteral(expr)) {
                // T-M21-04：allEq 的 Map 实参——Map.of / Map.ofEntries 调用静态展开键值对；
                // 非布尔/非 lambda 实参（Map 变量、new HashMap 等）→ 空条目 MapArg（converter 渲染占位）
                args.add(resolveMapArg(expr, allEqNull2IsNull, resolveVariables));
            } else if (SELECT_FILTER.equals(methodName) && index == 0) {
                // T-M21-01：selectFilter(Class, Predicate) 第一参实体类引用
                args.add(resolveJoinClass(expr, resolveVariables));
            } else if (SELECT_FILTER.equals(methodName) && index == 1) {
                // T-M21-01：第二参谓词 lambda
                args.add(resolveSelectPredicate(expr));
            } else if (JOIN_METHODS.contains(methodName) && index == 0) {
                args.add(resolveJoinClass(expr, resolveVariables));
            } else if (SELECT_AS_CLASS.equals(methodName)) {
                args.add(resolveJoinClass(expr, resolveVariables));
            } else if ((UNION_METHODS.contains(methodName) || SELECT_SUB.equals(methodName)) && index == 0) {
                // union/unionAll/selectSub 的第一参为子查询实体类引用（官方 API，M15 增补）
                args.add(resolveJoinClass(expr, resolveVariables));
            } else if (SUBQUERY_CONDITION_METHODS.contains(methodName) && index == 1
                && expressions.length >= 3
                && expressions[2] instanceof PsiLambdaExpression) {
                // 子查询条件（官方 sub-query API）：eq(col, SubEntity.class, w -> ...) 的第二参为子表类引用
                args.add(resolveJoinClass(expr, resolveVariables));
            } else if ((SELECT_COLLECTION.equals(methodName) || SELECT_ASSOCIATION.equals(methodName))
                && isCollectionClassArg(expr, expressions, index)) {
                // T-1800：selectCollection / selectAssociation 的类参解析为 EntityRefArg（携带 @TableName 注解）。
                // prefix 形态（第 2 参为类名）> 普通形态（第 1 参为类名）> free 形态（无类参，不产生子查询）。
                args.add(resolveJoinClass(expr, resolveVariables));
            } else if ((SELECT_COLLECTION.equals(methodName) || SELECT_ASSOCIATION.equals(methodName))
                && isCollectionVoGetter(expressions, index)) {
                // T-1800：selectCollection / selectAssociation 的 VO getter（prefix 形态第 3 参 / 普通形态第 2 参）
                // 解析为 AliasArg（保留驼峰属性名）。
                args.add(resolve(expr, table, entityClass, true, resolveVariables));
            } else if (SELECT_SUB.equals(methodName) && index == 2) {
                // selectSub 的第三参为结果别名（方法引用 → AliasArg 驼峰属性名）
                args.add(resolve(expr, table, entityClass, true, resolveVariables));
            } else if (ALIAS_SECOND_ARG_METHODS.contains(methodName) && index == 1) {
                args.add(resolve(expr, table, entityClass, true, resolveVariables));
            } else if (SELECT_FUNC.equals(methodName) || APPLY_FUNC.equals(methodName) && index == 1) {
                args.add(resolveFuncLambda(expr, table));
            } else {
                args.add(resolve(expr, table, entityClass, resolveVariables));
            }
        }
        return new WrapperCall(methodName, args);
    }

    /** T-M21-04：布尔字面量实参（allEq 的条件标志 / null2IsNull 尾参，不做 Map 解析）。 */
    private static boolean isBooleanLiteral(PsiExpression expression) {
        return expression instanceof PsiLiteralExpression
            && ((PsiLiteralExpression) expression).getValue() instanceof Boolean;
    }

    /**
     * T-1800：判断 selectCollection / selectAssociation 的当前实参是否为类参（需解析为 EntityRefArg）。
     *
     * <p>形态判定：</p>
     * <ul>
     *  <li>prefix 形态：第 2 参（index=1）为类参（第 1 参为字符串 prefix）</li>
     *  <li>普通形态：第 1 参（index=0）为类参</li>
     *  <li>free 形态：无类参（首参为 VO 方法引用），返回 false</li>
     * </ul>
     *
     * <p>判定规则：当前实参是 {@code PsiClassObjectAccessExpression}（{@code XxxEntity.class}）即视为类参。</p>
     */
    private static boolean isCollectionClassArg(PsiExpression expr, PsiExpression[] allArgs, int index) {
        if (!(expr instanceof PsiClassObjectAccessExpression)) return false;
        // prefix 形态：第 2 参（index=1）且第 1 参为字符串字面量
        if (index == 1 && allArgs[0] instanceof PsiLiteralExpression) return true;
        // 普通形态：第 1 参（index=0）
        return index == 0;
    }

    /**
     * T-1800：判断 selectCollection / selectAssociation 的当前实参是否为 VO getter（解析为 AliasArg）。
     *
     * <p>形态判定：</p>
     * <ul>
     *  <li>prefix 形态：第 3 参（index=2）为 VO getter</li>
     *  <li>普通形态：第 2 参（index=1）为 VO getter</li>
     * </ul>
     */
    private static boolean isCollectionVoGetter(PsiExpression[] allArgs, int index) {
        // prefix 形态：第 1 参为字符串字面量 → VO getter 在第 3 参
        if (allArgs.length > 0 && allArgs[0] instanceof PsiLiteralExpression) return index == 2;
        // 普通形态：VO getter 在第 2 参
        return index == 1;
    }

    /**
     * 解析 join 的关联实体引用：{@code leftJoin(XxxEntity.class, ...)} → {@link WrapperArg.EntityRefArg}
     * （F-13 关联实体注解解析）。无法解析为实体类时降级为类名字面量（converter 按命名策略兜底）。
     */
    private static WrapperArg resolveJoinClass(PsiExpression expr, boolean resolveVariables) {
        PsiClassObjectAccessExpression classAccess =
            expr instanceof PsiClassObjectAccessExpression ? (PsiClassObjectAccessExpression) expr : null;
        PsiClass psiClass = null;
        if (classAccess != null && classAccess.getOperand() != null) {
            PsiType operandType = classAccess.getOperand().getType();
            if (operandType instanceof PsiClassType) {
                psiClass = ((PsiClassType) operandType).resolve();
            }
        }
        return psiClass != null
            ? new WrapperArg.EntityRefArg(new EntityResolver().resolve(psiClass))
            : resolveValueArg(expr, resolveVariables);
    }

    /**
     * T-M21-04：解析 allEq 的 Map 实参为键值对列表。
     *
     * <ul>
     *  <li>{@code Map.of(k1, v1, k2, v2, ...)}：实参按两两一组解析；</li>
     *  <li>{@code Map.ofEntries(Map.entry(k, v), ...)}：每个实参为 entry 调用，取其前两参；</li>
     * </ul>
     *
     * <p>键为字符串字面量 → {@link WrapperArg.MapArg} 的条目（{@link ColumnRef}，columnName = 键）；
     * 值为字面量/可追踪变量 → {@link SqlValue}（其余占位符）；非 Map.of 的 Map 变量 → 空条目（converter 渲染占位）。</p>
     */
    private static WrapperArg resolveMapArg(
        PsiExpression expression,
        boolean null2IsNull,
        boolean resolveVariables
    ) {
        if (!(expression instanceof PsiMethodCallExpression)) {
            return new WrapperArg.MapArg(List.of(), null2IsNull);
        }
        PsiMethodCallExpression call = (PsiMethodCallExpression) expression;
        String methodName = call.getMethodExpression().getReferenceName();
        PsiExpression[] rawArgs = call.getArgumentList().getExpressions();
        List<kotlin.Pair<PsiExpression, PsiExpression>> pairs = new ArrayList<>();
        if ("ofEntries".equals(methodName)) {
            // Map.ofEntries(Map.entry(k, v), ...)：每个实参为 entry 调用，取其前两参
            for (PsiExpression entry : rawArgs) {
                if (!(entry instanceof PsiMethodCallExpression)) continue;
                PsiExpression[] entryArgs = ((PsiMethodCallExpression) entry).getArgumentList().getExpressions();
                if (entryArgs.length < 2) continue;
                pairs.add(new kotlin.Pair<>(entryArgs[0], entryArgs[1]));
            }
        } else {
            // Map.of(k1, v1, k2, v2, ...)：实参按两两一组解析
            int index = 0;
            while (index + 1 < rawArgs.length) {
                pairs.add(new kotlin.Pair<>(rawArgs[index], rawArgs[index + 1]));
                index += 2;
            }
        }
        List<kotlin.Pair<ColumnRef, SqlValue>> entries = new ArrayList<>();
        for (kotlin.Pair<PsiExpression, PsiExpression> pair : pairs) {
            PsiExpression keyExpr = pair.getFirst();
            PsiExpression valueExpr = pair.getSecond();
            if (!(keyExpr instanceof PsiLiteralExpression)) continue;
            Object keyValue = ((PsiLiteralExpression) keyExpr).getValue();
            if (!(keyValue instanceof String)) continue;
            SqlValue value = resolveVariables
                ? ExpressionEvaluator.evaluate(valueExpr)
                : new SqlValue.Placeholder(valueExpr.getText());
            entries.add(new kotlin.Pair<>(new ColumnRef(null, (String) keyValue, null, null, null, null, null), value));
        }
        return new WrapperArg.MapArg(entries, null2IsNull);
    }

    /**
     * T-M21-01：解析 selectFilter 的谓词 lambda
     * （{@code t -> !t.getProperty().equals("xxx")} 黑名单 / {@code t -> t.getColumn().equals("col_name")} 白名单）
     * 为静态白/黑名单。
     *
     * <p>收集 lambda 体内方法调用实参中的字符串字面量作为候选字段名；
     * 体内含 {@code !} 前缀表达式（negated equals）→ 黑名单，否则白名单。
     * 无法静态分析（无字符串字面量）→ 双空列表（converter 回退全列）。</p>
     */
    private static WrapperArg resolveSelectPredicate(PsiExpression expression) {
        if (!(expression instanceof PsiLambdaExpression)) {
            return new WrapperArg.PredicateArg(List.of(), List.of());
        }
        PsiElement body = ((PsiLambdaExpression) expression).getBody();
        if (body == null) {
            return new WrapperArg.PredicateArg(List.of(), List.of());
        }
        Set<String> names = new LinkedHashSet<>();
        for (PsiLiteralExpression literal : PsiTreeUtil.collectElementsOfType(body, PsiLiteralExpression.class)) {
            // 仅方法调用实参（排除其他位置的字符串）
            if (!(literal.getParent() instanceof PsiExpressionList)) continue;
            Object value = literal.getValue();
            if (!(value instanceof String)) continue;
            String name = ((String) value).trim();
            if (name.isEmpty()) continue;
            names.add(name);
        }
        boolean hasNegation = false;
        for (PsiPrefixExpression prefix : PsiTreeUtil.collectElementsOfType(body, PsiPrefixExpression.class)) {
            if ("!".equals(prefix.getOperationSign().getText())) {
                hasNegation = true;
                break;
            }
        }
        return hasNegation
            ? new WrapperArg.PredicateArg(List.of(), new ArrayList<>(names))
            : new WrapperArg.PredicateArg(new ArrayList<>(names), List.of());
    }

    /**
     * 解析 selectFunc 的聚合 lambda：{@code func -> func.accept(列1, 列2).values(值1, 值2)}。
     *
     * <p>收集 accept 的列引用与 values 的绑定参数值（清单 P09）。
     * T-M21-06：枚举形态 {@code selectFunc(DefaultFuncEnum.SUM, Entity::getX, ...)} 的第二参为列方法引用
     * → 单列 FuncArg（无绑定参数），与 lambda 形态并存。
     * 实参不是 lambda / 方法引用（异常代码）时降级为占位值。</p>
     */
    private static WrapperArg resolveFuncLambda(PsiExpression expression, TableInfo table) {
        if (expression instanceof PsiMethodReferenceExpression) {
            return new WrapperArg.FuncArg(
                List.of(resolveLambdaColumn((PsiMethodReferenceExpression) expression, table)), List.of());
        }
        if (!(expression instanceof PsiLambdaExpression)) {
            return new WrapperArg.ValueArg(ExpressionEvaluator.evaluate(expression));
        }
        PsiElement body = ((PsiLambdaExpression) expression).getBody();
        List<PsiMethodCallExpression> calls = body instanceof PsiMethodCallExpression
            ? collectCallChain((PsiMethodCallExpression) body)
            : List.of();
        List<ColumnRef> columns = List.of();
        List<SqlValue> values = List.of();
        for (PsiMethodCallExpression call : calls) {
            String name = call.getMethodExpression().getReferenceName();
            if ("accept".equals(name)) {
                List<ColumnRef> collected = new ArrayList<>();
                for (PsiExpression expr : call.getArgumentList().getExpressions()) {
                    if (expr instanceof PsiMethodReferenceExpression) {
                        collected.add(resolveLambdaColumn((PsiMethodReferenceExpression) expr, table));
                    }
                }
                columns = collected;
            } else if ("values".equals(name)) {
                List<SqlValue> collected = new ArrayList<>();
                for (PsiExpression expr : call.getArgumentList().getExpressions()) {
                    collected.add(ExpressionEvaluator.evaluate(expr));
                }
                values = collected;
            }
        }
        return new WrapperArg.FuncArg(columns, values);
    }
}
