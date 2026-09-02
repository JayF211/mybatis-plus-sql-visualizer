package cn.song.mybatisplus.visualizer.psi.jpa;

import com.intellij.psi.PsiBinaryExpression;
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
import com.intellij.psi.PsiPolyadicExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Hibernate HQL 定位器（F-JPA-02 / Wave2-P1）。
 *
 * 从光标位置识别 Hibernate Session.createQuery() 调用链，提取 HQL 字符串与实体上下文。
 */
public final class HqlQueryLocator {

    /** 单例实例（Kotlin object 语义兼容，供外部 Xxx.method() 调用路由）。 */
    public static final HqlQueryLocator INSTANCE = new HqlQueryLocator();

    private HqlQueryLocator() {
    }

    // Hibernate Session.createQuery / createNamedQuery / createNativeQuery 方法名
    private static final Set<String> CREATE_QUERY_METHODS = Set.of(
            "createQuery", "createNamedQuery", "createNativeQuery");

    // Query 接口参数设置方法名
    private static final Set<String> SET_PARAM_METHODS = Set.of(
            "setParameter", "setString", "setInteger", "setLong", "setDouble",
            "setFloat", "setBoolean", "setByte", "setShort", "setCharacter",
            "setDate", "setTime", "setTimestamp", "setCalendar");

    // 位置参数设置方法名（第一个参数为 int 索引）
    private static final Set<String> SET_POSITIONAL_METHODS = Set.of(
            "setString", "setInteger", "setLong", "setDouble",
            "setFloat", "setBoolean", "setByte", "setShort", "setCharacter");

    /** HQL 字符串 + 是否字面量（extractHqlString 返回值载体）。 */
    private static final class HqlStringResult {
    private final @Nullable String hql;
    private final boolean literal;

    public HqlStringResult(@Nullable String hql, boolean isLiteral) {
        this.hql = hql;
        this.literal = isLiteral;
    }

    public @Nullable String getHql() { return hql; }
    public @Nullable String hql() { return hql; }
    public boolean isLiteral() { return literal; }

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
        return "HqlStringResult[" + "hql=" + hql + ", " + "isLiteral=" + literal + "]";
    }
}

    /** 实体类 + 实体类名（extractEntityInfo 返回值载体）。 */
    private static final class EntityInfoResult {
    private final @Nullable PsiClass entityClass;
    private final @Nullable String entityClassName;

    public EntityInfoResult(@Nullable PsiClass entityClass, @Nullable String entityClassName) {
        this.entityClass = entityClass;
        this.entityClassName = entityClassName;
    }

    public @Nullable PsiClass getEntityClass() { return entityClass; }
    public @Nullable PsiClass entityClass() { return entityClass; }
    public @Nullable String getEntityClassName() { return entityClassName; }
    public @Nullable String entityClassName() { return entityClassName; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EntityInfoResult)) return false;
        EntityInfoResult that = (EntityInfoResult) o;
        return java.util.Objects.equals(this.entityClass, that.entityClass) && java.util.Objects.equals(this.entityClassName, that.entityClassName);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(entityClass, entityClassName);
    }

    @Override
    public String toString() {
        return "EntityInfoResult[" + "entityClass=" + entityClass + ", " + "entityClassName=" + entityClassName + "]";
    }
}

    /**
     * 从光标位置定位 HQL 查询调用链。
     *
     * @param anchor 光标所在 PSI 元素
     * @return HqlQueryInfo；未识别到 HQL 调用链时返回 null
     */
    @Nullable
    public static HqlQueryInfo locate(@NotNull PsiElement anchor) {
        // 路径 1：anchor 是 PsiMethodCallExpression → 精确匹配该调用
        if (anchor instanceof PsiMethodCallExpression call) {
            if (!CREATE_QUERY_METHODS.contains(call.getMethodExpression().getReferenceName())) return null;
            if (!hasStringHqlArgument(call)) return null;
            return locateFromCall(call, null);
        }

        // 路径 2：anchor 是 PsiMethod 自身（测试场景）→ 取方法体内第一个匹配
        if (anchor instanceof PsiMethod method) {
            return locateFirstInBody(method, null);
        }

        // 路径 3：查找包含 anchor 的最内层 createQuery 调用
        PsiMethodCallExpression containingCall = findContainingCreateQueryCall(anchor);
        if (containingCall != null) {
            if (!hasStringHqlArgument(containingCall)) return null;
            return locateFromCall(containingCall, null);
        }

        // 路径 4：anchor 位于方法体内但不在某个 createQuery 调用内 → 取方法体内第一个
        PsiMethod method = PsiTreeUtil.getParentOfType(anchor, PsiMethod.class);
        if (method == null) return null;
        return locateFirstInBody(method, null);
    }

    /**
     * 查找包含 anchor 的最内层 createQuery/createNamedQuery 调用。
     */
    @Nullable
    private static PsiMethodCallExpression findContainingCreateQueryCall(@NotNull PsiElement anchor) {
        PsiElement current = anchor;
        while (current != null) {
            if (current instanceof PsiMethodCallExpression call) {
                String methodName = call.getMethodExpression().getReferenceName();
                if (methodName != null && CREATE_QUERY_METHODS.contains(methodName)) {
                    return call;
                }
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * 从指定 PsiMethod 体内查找 createQuery 调用。
     */
    @Nullable
    private static HqlQueryInfo locateFirstInBody(@NotNull PsiMethod method, @Nullable PsiElement anchor) {
        PsiCodeBlock body = method.getBody();
        if (body == null) return null;
        List<PsiMethodCallExpression> allCalls =
                new ArrayList<>(PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression.class));
        for (PsiMethodCallExpression call : allCalls) {
            String methodName = call.getMethodExpression().getReferenceName();
            if (methodName == null || !CREATE_QUERY_METHODS.contains(methodName)) continue;
            // 有锚点时：仅返回包含该锚点的调用
            if (anchor != null && !call.getTextRange().contains(anchor.getTextRange())) continue;
            // 验证字符串参数
            if (!hasStringHqlArgument(call)) continue;
            return locateFromCall(call, allCalls);
        }
        return null;
    }

    /**
     * 检查 createQuery 调用的第一个参数是否为字符串（HQL/JPQL）。
     */
    private static boolean hasStringHqlArgument(@NotNull PsiMethodCallExpression call) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length == 0) return false;
        PsiExpression firstArg = args[0];
        if (firstArg instanceof PsiLiteralExpression literal) {
            return literal.getValue() instanceof String;
        }
        if (firstArg instanceof PsiBinaryExpression) return true;
        if (firstArg instanceof PsiPolyadicExpression) return true;
        if (firstArg instanceof PsiReferenceExpression ref) {
            // 变量引用：尝试解析类型，仅 String 类型视为有效 HQL 参数
            PsiElement resolved = ref.resolve();
            if (resolved instanceof PsiVariable v) {
                String typeName = v.getType().getCanonicalText();
                return "java.lang.String".equals(typeName) || "String".equals(typeName);
            }
            return false;
        }
        return false;
    }

    /**
     * 从指定的 createQuery 调用提取 HqlQueryInfo。
     */
    @Nullable
    private static HqlQueryInfo locateFromCall(@NotNull PsiMethodCallExpression call, @Nullable List<PsiMethodCallExpression> allCalls) {
        PsiMethod method = PsiTreeUtil.getParentOfType(call, PsiMethod.class);
        if (method == null) return null;
        HqlStringResult hqlResult = extractHqlString(call);
        EntityInfoResult entityInfo = extractEntityInfo(call);

        if (hqlResult.hql() == null || hqlResult.hql().isBlank()) return null;

        // 追踪 Query 变量
        Set<String> queryVars = new LinkedHashSet<>();
        String assigned = findAssignedVariable(call);
        if (assigned != null) queryVars.add(assigned);

        // 第二遍：收集参数设置
        List<HqlParameter> parameters = new ArrayList<>();
        if (!queryVars.isEmpty()) {
            List<PsiMethodCallExpression> calls = allCalls != null ? allCalls
                    : new ArrayList<>(PsiTreeUtil.collectElementsOfType(method.getBody(), PsiMethodCallExpression.class));
            for (PsiMethodCallExpression paramCall : calls) {
                String paramMethodName = paramCall.getMethodExpression().getReferenceName();
                if (paramMethodName == null) continue;
                if (!SET_PARAM_METHODS.contains(paramMethodName)) continue;
                PsiExpression qualifier = paramCall.getMethodExpression().getQualifierExpression();
                if (qualifier == null) continue;
                // 支持链式 qualifier——沿链向上找最终赋值变量
                String qualifierVar;
                if (qualifier instanceof PsiReferenceExpression ref) {
                    qualifierVar = ref.getReferenceName();
                } else if (qualifier instanceof PsiMethodCallExpression mc) {
                    qualifierVar = findAssignedVariable(mc);
                } else {
                    qualifierVar = null;
                }
                if (qualifierVar == null) continue;
                if (queryVars.contains(qualifierVar)) {
                    HqlParameter p = extractParameter(paramCall, paramMethodName);
                    if (p != null) parameters.add(p);
                }
            }
        }

        String createQueryMethodName = call.getMethodExpression().getReferenceName() != null
                ? call.getMethodExpression().getReferenceName() : "";
        boolean isNativeQuery = "createNativeQuery".equals(createQueryMethodName);

        return new HqlQueryInfo(method.getName(), hqlResult.hql(), hqlResult.isLiteral(),
                entityInfo.entityClass(), entityInfo.entityClassName(), parameters, method,
                isNativeQuery, createQueryMethodName);
    }

    /**
     * 从锚点向上查找包含 HQL 调用的代码块或方法体。
     */
    @Nullable
    private static PsiElement findRelevantBlock(@NotNull PsiElement anchor) {
        PsiElement current = anchor;
        while (current != null) {
            if (current instanceof PsiCodeBlock) return current;
            if (current instanceof PsiMethod) return ((PsiMethod) current).getBody();
            if (current instanceof PsiFile) return current;
            current = current.getParent();
        }
        return null;
    }

    /**
     * 从 createQuery/createNamedQuery 调用提取 HQL 字符串。
     */
    @NotNull
    private static HqlStringResult extractHqlString(@NotNull PsiMethodCallExpression call) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length == 0) return new HqlStringResult(null, false);
        return extractHqlString(args[0]);
    }

    /**
     * 从表达式提取 HQL 字符串。
     */
    @NotNull
    private static HqlStringResult extractHqlString(@NotNull PsiExpression expression) {
        if (expression instanceof PsiLiteralExpression literal) {
            String value = literal.getValue() instanceof String s ? s : null;
            return new HqlStringResult(value != null ? value : literal.getText(), true);
        }
        if (expression instanceof PsiBinaryExpression || expression instanceof PsiPolyadicExpression) {
            List<String> parts = collectStringLiterals(expression);
            if (!parts.isEmpty()) return new HqlStringResult(String.join("", parts), true);
            return new HqlStringResult(expression.getText(), false);
        }
        if (expression instanceof PsiReferenceExpression ref) {
            PsiElement resolved = ref.resolve();
            if (resolved instanceof PsiVariable v) {
                PsiExpression initializer = v.getInitializer();
                if (initializer instanceof PsiLiteralExpression li) {
                    return new HqlStringResult(li.getValue() instanceof String s ? s : li.getText(), true);
                }
                // 字符串拼接：String hql = "select" + " ..." —— 收集字面量片段静态还原
                if (initializer instanceof PsiBinaryExpression || initializer instanceof PsiPolyadicExpression) {
                    List<String> parts = collectStringLiterals(initializer);
                    if (!parts.isEmpty()) {
                        return new HqlStringResult(String.join("", parts), true);
                    }
                }
                return new HqlStringResult(ref.getReferenceName(), false);
            }
            return new HqlStringResult(ref.getReferenceName(), false);
        }
        return new HqlStringResult(expression.getText(), false);
    }

    /**
     * 从 createQuery(Xxx.class) 提取返回实体类信息（三级 fallback）。
     */
    @NotNull
    private static EntityInfoResult extractEntityInfo(@NotNull PsiMethodCallExpression call) {
        String methodName = call.getMethodExpression().getReferenceName();
        // createNamedQuery 无实体参数
        if ("createNamedQuery".equals(methodName)) return new EntityInfoResult(null, null);

        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length >= 2) {
            PsiExpression entityArg = args[1];
            if (entityArg instanceof PsiClassObjectAccessExpression coa) {
                PsiClass psiClass = (coa.getOperand().getType() instanceof PsiClassType ct) ? ct.resolve() : null;
                String className = (coa.getOperand().getType() instanceof PsiClassType ct2) ? ct2.getClassName() : null;
                return new EntityInfoResult(psiClass, psiClass != null ? psiClass.getName() : className);
            }
        }

        // 尝试从类型参数推断
        PsiType[] typeArgs = call.getTypeArgumentList().getTypeArguments();
        PsiClassType typeArg = null;
        if (typeArgs.length > 0 && typeArgs[0] instanceof PsiClassType ct) {
            typeArg = ct;
        }
        if (typeArg != null) {
            return new EntityInfoResult(typeArg.resolve(), typeArg.getClassName());
        }

        // 三级 fallback：从 HQL/JPQL 字符串的 FROM 子句提取实体类名
        if (args.length > 0) {
            HqlStringResult hqlResult = extractHqlString(call);
            String hql = hqlResult.hql();
            if (hql != null && !hql.isBlank()) {
                Pattern fromPattern = Pattern.compile("\\bFROM\\s+([A-Z][a-zA-Z0-9_]*)\\b", Pattern.CASE_INSENSITIVE);
                java.util.regex.Matcher m = fromPattern.matcher(hql);
                if (m.find()) return new EntityInfoResult(null, m.group(1));
            }
        }

        return new EntityInfoResult(null, null);
    }

    /**
     * 查找方法调用被赋值给的局部变量名（如 `Query q = session.createQuery(...)` 的 q）。
     */
    @Nullable
    private static String findAssignedVariable(@NotNull PsiMethodCallExpression call) {
        PsiElement current = call;
        while (current.getParent() != null) {
            PsiElement parent = current.getParent();
            if (parent instanceof PsiLocalVariable localVar) return localVar.getName();
            if (parent instanceof com.intellij.psi.PsiAssignmentExpression assign) {
                if (assign.getLExpression() instanceof PsiReferenceExpression lhs) return lhs.getReferenceName();
            }
            // 仅允许沿链式调用向上
            current = parent;
            if (!(current instanceof PsiMethodCallExpression) && !(current instanceof PsiReferenceExpression)) return null;
        }
        return null;
    }

    /**
     * 从 setParameter/setInteger 等调用提取参数信息。
     */
    @Nullable
    private static HqlParameter extractParameter(@NotNull PsiMethodCallExpression call, @NotNull String methodName) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length == 0) return null;

        boolean isPositional = SET_POSITIONAL_METHODS.contains(methodName) && args.length >= 2;

        if (isPositional) {
            // 位置参数：setInteger(0, value) → paramName = "0"
            PsiExpression indexArg = args[0];
            String indexText;
            if (indexArg instanceof PsiLiteralExpression literal) {
                indexText = literal.getValue() != null ? literal.getValue().toString() : "0";
            } else {
                indexText = indexArg.getText();
            }
            String valueExpr = args[1].getText();
            return new HqlParameter(indexText, valueExpr, true);
        } else {
            // 命名参数：setParameter("name", value) → paramName = "name"
            PsiExpression nameArg = args[0];
            String paramName;
            if (nameArg instanceof PsiLiteralExpression literal) {
                paramName = literal.getValue() instanceof String s ? s : nameArg.getText();
            } else if (nameArg instanceof PsiReferenceExpression ref) {
                paramName = ref.getReferenceName();
            } else {
                paramName = nameArg.getText();
            }
            if (paramName == null) return null;
            String valueExpr = args.length > 1 ? args[1].getText() : "";
            return new HqlParameter(paramName, valueExpr, false);
        }
    }

    /**
     * 递归收集 PSI 树中所有 PsiLiteralExpression 的字符串值。
     */
    private static List<String> collectStringLiterals(@NotNull PsiElement element) {
        if (element instanceof PsiLiteralExpression literal) {
            if (literal.getValue() instanceof String s) return List.of(s);
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (PsiElement child : element.getChildren()) {
            result.addAll(collectStringLiterals(child));
        }
        return result;
    }
}
