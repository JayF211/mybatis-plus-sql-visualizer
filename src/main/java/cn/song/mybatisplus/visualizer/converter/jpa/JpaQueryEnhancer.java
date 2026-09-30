package cn.song.mybatisplus.visualizer.converter.jpa;

import cn.song.mybatisplus.visualizer.model.AssociationInfo;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.JoinClause;
import cn.song.mybatisplus.visualizer.model.JoinType;
import cn.song.mybatisplus.visualizer.model.LimitClause;
import cn.song.mybatisplus.visualizer.model.OrderByItem;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.psi.ExpressionEvaluator;
import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.util.NamingStrategy;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JPA 查询方法增强支撑（T-J10 / T-J15 / T-J16 / T-J22）。
 *
 * 供 [cn.song.mybatisplus.visualizer.ui.SqlPreviewService] 的 previewDerivedQuery / previewJpql /
 * previewHql / previewCriteriaQuery / previewProcedure 各路径共用：
 *
 *  - **T-J10 Pageable/Sort/Limit 全路径**：[evaluatePageSort]——方法参数含 `Pageable`/`Page`/`Sort`/`Limit`
 *    类型时产出 LIMIT/OFFSET 占位与 ORDER BY；调用点（J9 动作路径）传 [callArgs] 时对
 *    `PageRequest.of(page, size)` / `Sort.by(...)` 做静态求值，有字面量就内联，无则 `?` 占位；
 *  - **T-J15 锁与抓取注解**：[lockClause]（@Lock → FOR UPDATE / 共享锁方言差异注释）、
 *    [queryHintNotes]（@QueryHints 逐条注释）、[entityGraph]/[resolveEntityGraphJoins]
 *    （@EntityGraph attributePaths → LEFT JOIN，关联元数据来自 [TableInfo.associations]，解析失败降级注释）；
 *  - **T-J16 存储过程**：[procedureInfo]——@Procedure 的 procedureName/value 优先、方法名兜底、
 *    outputParameterName 标注；
 *  - **T-J22 返回类型与投影**：[returnTypeNotes]（Page/Slice/Stream/Streamable/异步/接口投影 notes 级说明）、
 *    [countQueryAttribute]（@Query.countQuery 原文提取，转换由调用方完成）。
 *
 * 边界（文档化，不实现）：嵌套关联路径（`a.b`）静态降级注释；`Sort.by` 关联属性、
 * `ExampleMatcher` 形态归各转换器管；运行时回调（queryRewriter 等）不解析。
 * 全程不抛异常（runCatching 包裹 PSI 解析），解析失败一律降级为 null/注释。
 */
public final class JpaQueryEnhancer {

    /** 单例实例（兼容 Kotlin object 调用形式）。 */
    public static final JpaQueryEnhancer INSTANCE = new JpaQueryEnhancer();

    private JpaQueryEnhancer() {
    }

    // ------------------------------------------------------------------
    // T-J10：Pageable / Sort / Limit 参数分析
    // ------------------------------------------------------------------

    /**
     * 分页/排序参数分析结果。
     *
     * @param limit           LIMIT/OFFSET 子句模型（null 表示方法无分页参数）
     * @param orderBy         静态可求值的 ORDER BY 项（Sort.by 字面量）
     * @param extraParamNames 需要追加到参数行的参数名（pageOffset/pageSize/limit 变量名）
     * @param notes           语义注释（Sort 不可静态求值等）
     */
    public static final class PageSortEval {
        private final LimitClause limit;
        private final List<OrderByItem> orderBy;
        private final List<String> extraParamNames;
        private final List<String> notes;

        public PageSortEval(LimitClause limit, List<OrderByItem> orderBy,
                            List<String> extraParamNames, List<String> notes) {
            this.limit = limit;
            this.orderBy = orderBy;
            this.extraParamNames = extraParamNames;
            this.notes = notes;
        }

        public LimitClause getLimit() { return limit; }
        public List<OrderByItem> getOrderBy() { return orderBy; }
        public List<String> getExtraParamNames() { return extraParamNames; }
        public List<String> getNotes() { return notes; }
    }

    /**
     * 分析方法的 Pageable/Page/Sort/Limit 参数（T-J10）。
     *
     * @param method   宿主方法（Repository 声明方法或 HQL/Criteria 所在方法）
     * @param callArgs 调用点实参（J9 动作路径反查到声明方法后可传；按位置对齐方法参数）。
     *                 非 null 时对 PageRequest.of / Sort.by 做静态求值；null 时占位。
     * @param dialect  方言（LIMIT 形态由调用方 [Dialect.renderLimit] 渲染，此处只建模型）
     * @param table    实体表信息（Sort 属性名 → 列名映射用；null 时按驼峰转下划线）
     */
    public static PageSortEval evaluatePageSort(PsiMethod method) {
        return evaluatePageSort(method, null, null, null);
    }

    public static PageSortEval evaluatePageSort(PsiMethod method, List<PsiExpression> callArgs,
                                                Dialect dialect, TableInfo table) {
        try {
            if (method == null) return null;
            PsiParameter[] params = method.getParameterList().getParameters();
            if (params.length == 0) return null;

            LimitClause limit = null;
            List<OrderByItem> orderBys = new ArrayList<>();
            List<String> extraParams = new ArrayList<>();
            List<String> notes = new ArrayList<>();

            for (int index = 0; index < params.length; index++) {
                PsiParameter param = params[index];
                // 去泛型后缀（org.springframework.data.domain.Page<UserEntity> → ...Page）
                String typeName = substringBefore(param.getType().getCanonicalText(), '<');
                PsiExpression argExpr = callArgs != null && index < callArgs.size() ? callArgs.get(index) : null;
                if (isPageableType(typeName)) {
                    PageRequestEval pageEval = argExpr != null ? evalPageRequest(argExpr) : null;
                    // 双字面量（page/size 均可求值）才内联；否则占位 + 参数行
                    SqlValue.Literal sizeLiteral =
                            pageEval != null && pageEval.count instanceof SqlValue.Literal literal ? literal : null;
                    if (sizeLiteral != null) {
                        SqlValue.Literal offsetLiteral =
                                pageEval.offset instanceof SqlValue.Literal offset ? offset : null;
                        notes.add("PageRequest.of 静态求值：offset="
                                + (offsetLiteral != null ? offsetLiteral.getUnquoted() : "0")
                                + "，size=" + sizeLiteral.getUnquoted());
                        limit = new LimitClause(offsetLiteral, sizeLiteral);
                    } else {
                        limit = new LimitClause(
                                new SqlValue.Placeholder("pageOffset"),
                                new SqlValue.Placeholder("pageSize"));
                        extraParams.add("pageSize");
                        extraParams.add("pageOffset");
                    }
                } else if (isLimitType(typeName)) {
                    // Spring Data 3.2 Limit 参数：仅行数限制、无 OFFSET
                    if (limit == null) {
                        String paramName = param.getName() != null ? param.getName() : "limit";
                        limit = new LimitClause(null, new SqlValue.Placeholder(paramName));
                        extraParams.add(paramName);
                    }
                } else if (isSortType(typeName)) {
                    List<OrderByItem> sortItems = argExpr != null ? evalSortBy(argExpr, table) : null;
                    if (sortItems != null) {
                        if (sortItems.isEmpty()) {
                            notes.add("Sort.unsorted()：无动态排序");
                        } else {
                            orderBys.addAll(sortItems);
                        }
                    } else {
                        notes.add("Sort 参数：运行时动态 ORDER BY（静态不可解析，预览不含排序子句）");
                    }
                }
            }

            if (limit == null && orderBys.isEmpty() && notes.isEmpty()) return null;
            return new PageSortEval(limit, orderBys, extraParams, notes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 类型名是否为 Pageable/Page 家族（org.springframework.data.domain.Pageable / Page 子形态；简名兜底）。 */
    public static boolean isPageableType(String typeName) {
        return typeName.equals("Pageable") || typeName.endsWith(".Pageable")
                || (typeName.endsWith(".Page") && typeName.contains("domain"));
    }

    /** 类型名是否为 Spring Data 3.2 Limit 接口（简名兜底）。 */
    public static boolean isLimitType(String typeName) {
        return typeName.equals("Limit") || (typeName.endsWith(".Limit") && typeName.contains("domain"));
    }

    /** 类型名是否为 Sort（简名兜底）。 */
    public static boolean isSortType(String typeName) {
        return typeName.equals("Sort") || (typeName.endsWith(".Sort") && typeName.contains("domain"));
    }

    /**
     * 静态求值 `PageRequest.of(page, size)` / `PageRequest.ofSize(size)`（含变量初始化式回溯一次）。
     *
     * @return (offset, count)；字面量可求值时为 [SqlValue.Literal]，否则 null（调用方降级占位）
     */
    public static PageRequestEval evalPageRequest(PsiExpression expr) {
        try {
            PsiMethodCallExpression call = expr instanceof PsiMethodCallExpression direct ? direct : null;
            if (call == null && traceInitializer(expr) instanceof PsiMethodCallExpression viaVariable) {
                call = viaVariable;
            }
            if (call == null) return null;
            String name = call.getMethodExpression().getReferenceName();
            if (name == null) return null;
            PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
            String qualifierText = qualifier != null && qualifier.getText() != null ? qualifier.getText() : "";
            if (!qualifierText.endsWith("PageRequest")) return null;
            PsiExpression[] args = call.getArgumentList().getExpressions();
            switch (name) {
                case "of": {
                    if (args.length < 2) return null;
                    Integer page = intValue(args[0]);
                    Integer size = intValue(args[1]);
                    SqlValue offset = page != null && size != null
                            ? new SqlValue.Literal(String.valueOf(page * size)) : null;
                    return new PageRequestEval(offset, size != null ? new SqlValue.Literal(String.valueOf(size)) : null);
                }
                case "ofSize": {
                    Integer size = args.length > 0 ? intValue(args[0]) : null;
                    if (size == null) return null;
                    return new PageRequestEval(new SqlValue.Literal("0"), new SqlValue.Literal(String.valueOf(size)));
                }
                default:
                    return null;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** evalPageRequest 产物：offset/count 对（Kotlin Pair(offset, count) 的 Java 形态）。 */
    public static final class PageRequestEval {
        private final SqlValue offset;
        private final SqlValue count;

        public PageRequestEval(SqlValue offset, SqlValue count) {
            this.offset = offset;
            this.count = count;
        }

        public SqlValue getOffset() { return offset; }
        public SqlValue getCount() { return count; }
    }

    /**
     * 静态求值 `Sort.by(...)`（含变量初始化式回溯一次）。
     *
     * 支持：`Sort.by("a", "b")`、`Sort.by(Sort.Direction.DESC, "a")`、
     * `Sort.by(Sort.Order.desc("a"), Sort.Order.asc("b"))`、`Sort.unsorted()`（空列表）。
     * 属性名经 [mapColumn] 映射列名；关联属性（含 `.`）跳过（静态降级）。
     */
    public static List<OrderByItem> evalSortBy(PsiExpression expr, TableInfo table) {
        try {
            PsiMethodCallExpression call = expr instanceof PsiMethodCallExpression direct ? direct : null;
            if (call == null && traceInitializer(expr) instanceof PsiMethodCallExpression viaVariable) {
                call = viaVariable;
            }
            if (call == null) return null;
            String name = call.getMethodExpression().getReferenceName();
            if (name == null) return null;
            PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
            String qualifierText = qualifier != null && qualifier.getText() != null ? qualifier.getText() : "";
            if (!qualifierText.endsWith("Sort")) return null;
            PsiExpression[] args = call.getArgumentList().getExpressions();
            switch (name) {
                case "unsorted":
                    return List.of();
                case "by": {
                    List<OrderByItem> items = new ArrayList<>();
                    boolean desc = false;
                    for (PsiExpression arg : args) {
                        if (arg instanceof PsiReferenceExpression reference
                                && "DESC".equals(reference.getReferenceName())) {
                            // Sort.Direction.ASC/DESC 前缀参数，作用于其后的字符串实参
                            desc = true;
                        } else if (arg instanceof PsiReferenceExpression reference
                                && "ASC".equals(reference.getReferenceName())) {
                            desc = false;
                        } else if (arg instanceof PsiMethodCallExpression orderCall
                                && ("asc".equals(orderCall.getMethodExpression().getReferenceName())
                                        || "desc".equals(orderCall.getMethodExpression().getReferenceName()))) {
                            // Sort.Order.desc("x") / Sort.Order.asc("x")
                            PsiExpression[] orderArgs = orderCall.getArgumentList().getExpressions();
                            String prop = orderArgs.length > 0
                                    && orderArgs[0] instanceof PsiLiteralExpression literal
                                    && literal.getValue() instanceof String literalText ? literalText : null;
                            if (prop != null && !prop.contains(".")) {
                                items.add(new OrderByItem(new ColumnRef(mapColumn(prop, table)),
                                        "asc".equals(orderCall.getMethodExpression().getReferenceName())));
                            }
                        } else if (arg instanceof PsiLiteralExpression literal
                                && literal.getValue() instanceof String prop) {
                            // 字符串字面量属性
                            if (!prop.contains(".")) {
                                items.add(new OrderByItem(new ColumnRef(mapColumn(prop, table)), !desc));
                            }
                        }
                    }
                    return items;
                }
                default:
                    return null;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 实体字段名 → 列名（表元数据匹配优先，T-Embedded 嵌入段驼峰拼接路径次之，驼峰转下划线兜底）。 */
    private static String mapColumn(String fieldName, TableInfo table) {
        ColumnInfo matched = table != null ? table.columnByField(fieldName) : null;
        if (matched == null && table != null) {
            matched = table.columnByEmbeddedPath(fieldName);
        }
        return matched != null && matched.getColumnName() != null
                ? matched.getColumnName()
                : NamingStrategy.INSTANCE.toSnakeCase(fieldName);
    }

    // ------------------------------------------------------------------
    // T-J15：@Lock / @QueryHints / @EntityGraph
    // ------------------------------------------------------------------

    /**
     * @Lock 注解 → (FOR UPDATE 子句, 注释说明)。
     *
     * PESSIMISTIC_WRITE/WRITE/PESSIMISTIC_FORCE_INCREMENT → SELECT 尾部 `FOR UPDATE`；
     * READ/PESSIMISTIC_READ/OPTIMISTIC 等共享/乐观锁形态 → 仅注释说明方言差异（Oracle 无
     * FOR SHARE、MySQL 8 才有等），不追加 SQL 子句。无注解返回 null。
     */
    public static LockClause lockClause(PsiMethod method) {
        try {
            PsiAnnotation annotation = findAnnotation(method, "Lock");
            if (annotation == null) return null;
            String mode = "unspecified";
            if (annotation.findAttributeValue("value") instanceof PsiReferenceExpression reference
                    && reference.getReferenceName() != null) {
                mode = reference.getReferenceName();
            }
            if (mode.equals("PESSIMISTIC_WRITE") || mode.equals("WRITE")
                    || mode.equals("PESSIMISTIC_FORCE_INCREMENT")) {
                return new LockClause("FOR UPDATE",
                        "@Lock(" + mode + ")：悲观写锁，SELECT 追加 FOR UPDATE（锁至事务结束）");
            }
            if (mode.equals("PESSIMISTIC_READ") || mode.equals("READ")) {
                return new LockClause(null,
                        "@Lock(" + mode + ")：共享锁——方言差异大（PostgreSQL FOR SHARE / MySQL 8 FOR SHARE / "
                                + "Oracle 需以写锁子句近似），预览不追加子句");
            }
            if (mode.equals("OPTIMISTIC") || mode.equals("OPTIMISTIC_FORCE_INCREMENT")) {
                return new LockClause(null, "@Lock(" + mode + ")：乐观锁（版本列校验，无锁子句）");
            }
            return new LockClause(null, "@Lock(" + mode + ")：锁模式无法静态映射为 SQL 子句");
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** lockClause 产物：(FOR UPDATE 子句, 注释说明)，Kotlin Pair 的 Java 形态。 */
    public static final class LockClause {
        private final String clause;
        private final String note;

        public LockClause(String clause, String note) {
            this.clause = clause;
            this.note = note;
        }

        public String getClause() { return clause; }
        public String getNote() { return note; }
    }

    /** @QueryHints → 逐条注释行（`@QueryHint(name=.., value=..)`）；无注解返回空列表。 */
    public static List<String> queryHintNotes(PsiMethod method) {
        try {
            PsiAnnotation annotation = findAnnotation(method, "QueryHints");
            if (annotation == null) return List.of();
            List<String> notes = new ArrayList<>();
            PsiAnnotationMemberValue hintsValue = annotation.findAttributeValue("value");
            List<PsiAnnotation> hints = collectHintAnnotations(hintsValue);
            for (int index = 0; index < hints.size(); index++) {
                PsiAnnotation hint = hints.get(index);
                String name = "?";
                String value = "?";
                if (hint.findAttributeValue("name") instanceof PsiLiteralExpression literal
                        && literal.getValue() instanceof String literalText) {
                    name = literalText;
                }
                if (hint.findAttributeValue("value") instanceof PsiLiteralExpression literal
                        && literal.getValue() instanceof String literalText) {
                    value = literalText;
                }
                notes.add("@QueryHints[" + index + "]：" + name + " = " + value
                        + "（运行时作为持久化提示下发，不改变 SQL 文本）");
            }
            Boolean forCounting = null;
            if (annotation.findAttributeValue("forCounting") instanceof PsiLiteralExpression literal
                    && literal.getValue() instanceof Boolean literalValue) {
                forCounting = literalValue;
            }
            if (Boolean.FALSE.equals(forCounting)) {
                notes.add("@QueryHints(forCounting=false)：hint 不应用于 count 查询");
            }
            return notes;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // T-J16：@Procedure
    // ------------------------------------------------------------------

    /**
     * @Procedure 注解信息。
     *
     * @param procName            过程名（procedureName/value 优先，方法名兜底）
     * @param outputParameterName 输出参数名（null 表示未指定）
     * @param nameRef             @Procedure.name（引用元数据中具名存储过程查询，静态不可解析时提示）
     * @param paramCount          方法参数个数（? 占位个数）
     */
    public static final class ProcedureInfo {
        private final String procName;
        private final String outputParameterName;
        private final String nameRef;
        private final int paramCount;

        public ProcedureInfo(String procName, String outputParameterName, String nameRef, int paramCount) {
            this.procName = procName;
            this.outputParameterName = outputParameterName;
            this.nameRef = nameRef;
            this.paramCount = paramCount;
        }

        public String getProcName() { return procName; }
        public String getOutputParameterName() { return outputParameterName; }
        public String getNameRef() { return nameRef; }
        public int getParamCount() { return paramCount; }
    }

    /** 提取方法上的 @Procedure 注解信息（FQN org.springframework.data.jpa.repository.query.Procedure，短名兜底）。 */
    public static ProcedureInfo procedureInfo(PsiMethod method) {
        try {
            PsiAnnotation annotation = findAnnotation(method, "Procedure");
            if (annotation == null) return null;
            String fromValue = stringValue(annotation.findAttributeValue("value"));
            String fromProcedureName = stringValue(annotation.findAttributeValue("procedureName"));
            String procName = firstNonBlank(fromProcedureName);
            if (procName == null) procName = firstNonBlank(fromValue);
            if (procName == null) procName = method.getName();
            return new ProcedureInfo(
                    procName,
                    // 注解缺省（空串）时收窄为 null——不挂「输出参数」注释行
                    firstNonBlank(stringValue(annotation.findAttributeValue("outputParameterName"))),
                    firstNonBlank(stringValue(annotation.findAttributeValue("name"))),
                    method.getParameterList().getParameters().length);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // T-J22：返回类型与投影
    // ------------------------------------------------------------------

    /**
     * 返回类型 notes 级标注（T-J22，只说明不收敛 SELECT 列）：
     *  - `Page&lt;T&gt;` → 运行时附加 count 查询（countQuery SQL 由调用方按 @Query.countQuery 补充展示）；
     *  - `Slice&lt;T&gt;` → 多取一条判断是否有下一页；
     *  - `Stream&lt;T&gt;` → 须在事务中消费并关闭；
     *  - `Streamable&lt;T&gt;` → 可流式聚合的结果包装；
     *  - `CompletableFuture`/`Future` → 异步执行；
     *  - 接口投影（返回类型为非注解接口）→ 按 getter 名映射列（SELECT 列不收敛——文档化边界）；
     *  - 方法参数含 `Class&lt;T&gt;` 动态投影 → 说明。
     *
     * 返回类型基名按「去泛型 + 简名」匹配（FQN `org.springframework.data.domain.Page&lt;..&gt;` 同样命中）。
     */
    public static List<String> returnTypeNotes(PsiMethod method) {
        try {
            List<String> notes = new ArrayList<>();
            String returnTypeText = method.getReturnType() != null ? method.getReturnType().getCanonicalText() : null;
            if (returnTypeText == null) return notes;
            String baseName = substringAfterLast(substringBefore(returnTypeText, '<'), '.');
            switch (baseName) {
                case "Page":
                    notes.add("返回 Page：运行时附加 COUNT 查询统计总数（本预览仅数据查询本身）");
                    break;
                case "Slice":
                    notes.add("返回 Slice：运行时多取一条判断是否还有下一页（预览不含该探测查询）");
                    break;
                case "Stream":
                    notes.add("返回 Stream：须在事务中消费并 close()（SQL 本身与 List 形态一致）");
                    break;
                case "Streamable":
                    notes.add("返回 Streamable：可流式聚合的结果包装（SQL 本身与 List 形态一致）");
                    break;
                case "CompletableFuture":
                case "Future":
                case "ListenableFuture":
                    notes.add("返回 Future 家族：查询在线程池异步执行（SQL 不变）");
                    break;
                default:
                    String projection = projectionNote(method);
                    if (projection != null) notes.add(projection);
                    break;
            }
            boolean hasDynamicProjection = false;
            for (PsiParameter param : method.getParameterList().getParameters()) {
                String paramType = substringBefore(param.getType().getCanonicalText(), '<');
                if (paramType.equals("java.lang.Class") || paramType.equals("Class")) {
                    hasDynamicProjection = true;
                    break;
                }
            }
            if (hasDynamicProjection) notes.add("Class<T> 动态投影参数：运行时按投影类型收敛/转换结果列");
            return notes;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    /**
     * 接口投影说明（返回类型为接口且含 getter → 列出投影属性；
     * `List&lt;Projection&gt;` 等集合包装先解包一层元素类型）。
     *
     * 注意顺序：先取泛型参数（仅需 PSI 结构，无需解析外层 `java.util.List`——测试模块/无 JDK
     * 解析态下外层 `resolve()` 可能为 null，不能作为前置条件），元素类型不可解析时才回退
     * 返回类型本身（裸接口返回形态）。
     */
    private static String projectionNote(PsiMethod method) {
        try {
            if (!(method.getReturnType() instanceof PsiClassType classType)) return null;
            PsiType[] parameters = classType.getParameters();
            PsiClassType targetType = parameters.length > 0 && parameters[0] instanceof PsiClassType firstParam
                    ? firstParam : classType;
            PsiClass resolved = targetType.resolve();
            if (resolved == null) return null;
            if (!resolved.isInterface() || resolved.isAnnotationType()) return null;
            String qualifiedName = resolved.getQualifiedName() != null ? resolved.getQualifiedName() : "";
            if (qualifiedName.startsWith("java.") || qualifiedName.startsWith("org.springframework.")) return null;
            // 自身 methods 优先（接口声明 getter），allMethods 兜底（继承投影形态）；distinct 去重
            List<PsiMethod> allMethods = new ArrayList<>(Arrays.asList(resolved.getMethods()));
            for (PsiMethod inherited : resolved.getAllMethods()) {
                if (!allMethods.contains(inherited)) allMethods.add(inherited);
            }
            Set<String> properties = new LinkedHashSet<>();
            for (PsiMethod candidate : allMethods) {
                if (candidate.getParameterList().getParameters().length != 0) continue;
                // java.lang.Object 公共方法（getClass 等）不视为投影属性
                if (candidate.getContainingClass() != null
                        && "java.lang.Object".equals(candidate.getContainingClass().getQualifiedName())) continue;
                String name = candidate.getName();
                if (name.startsWith("get") && name.length() > 3) {
                    properties.add(decapitalize(name.substring(3)));
                } else if (name.startsWith("is") && name.length() > 2) {
                    properties.add(decapitalize(name.substring(2)));
                }
            }
            if (properties.isEmpty()) return null;
            return "接口投影 [" + String.join(", ", properties)
                    + "]：运行时按 getter 名映射列（AS 别名）；预览保持全列 SELECT 不收敛（文档化边界）";
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** @Query 的 countQuery 属性原文（T-J22：Page 返回时一并展示 count SQL）；无则 null。 */
    public static String countQueryAttribute(PsiMethod method) {
        try {
            PsiAnnotation annotation = findAnnotation(method, "Query");
            if (annotation == null) return null;
            return firstNonBlank(stringValue(annotation.findAttributeValue("countQuery")));
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // @EntityGraph → LEFT JOIN
    // ------------------------------------------------------------------

    /** @EntityGraph 注解信息（attributePaths + type）。 */
    public static final class EntityGraphInfo {
        private final List<String> paths;
        private final String type;

        public EntityGraphInfo(List<String> paths, String type) {
            this.paths = paths;
            this.type = type;
        }

        public List<String> getPaths() { return paths; }
        public String getType() { return type; }
    }

    /** 提取方法上的 @EntityGraph（jakarta/javax @NamedEntityGraph 引用形态不在本方法——引用按名回查属 J15 远期）。 */
    public static EntityGraphInfo entityGraph(PsiMethod method) {
        try {
            PsiAnnotation annotation = findAnnotation(method, "EntityGraph");
            if (annotation == null) return null;
            PsiAnnotationMemberValue attributePaths = annotation.findAttributeValue("attributePaths");
            List<String> paths = collectStringLiterals(attributePaths);
            String type = null;
            if (annotation.findAttributeValue("type") instanceof PsiReferenceExpression reference) {
                type = reference.getReferenceName();
            }
            if (paths.isEmpty()) return null;
            return new EntityGraphInfo(paths, type);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * attributePaths → LEFT JOIN 描述（按 [TableInfo.associations] 关联元数据）。
     *
     * @return (可解析的 join 描述列表, 降级注释列表——嵌套路径/无关联元数据)
     */
    public static final class GraphJoin {
        private final String targetTable;
        private final String targetAlias;
        /** 主表外键列（@JoinColumn(name)） */
        private final String mainColumn;
        /** 副表被引用列（referencedColumnName，缺省按主键 id 惯例） */
        private final String targetColumn;
        private final String path;

        public GraphJoin(String targetTable, String targetAlias, String mainColumn,
                         String targetColumn, String path) {
            this.targetTable = targetTable;
            this.targetAlias = targetAlias;
            this.mainColumn = mainColumn;
            this.targetColumn = targetColumn;
            this.path = path;
        }

        public String getTargetTable() { return targetTable; }
        public String getTargetAlias() { return targetAlias; }
        public String getMainColumn() { return mainColumn; }
        public String getTargetColumn() { return targetColumn; }
        public String getPath() { return path; }
    }

    /** resolveEntityGraphJoins 产物：(join 描述列表, 降级注释列表)，Kotlin Pair 的 Java 形态。 */
    public static final class GraphJoinResult {
        private final List<GraphJoin> joins;
        private final List<String> notes;

        public GraphJoinResult(List<GraphJoin> joins, List<String> notes) {
            this.joins = joins;
            this.notes = notes;
        }

        public List<GraphJoin> getJoins() { return joins; }
        public List<String> getNotes() { return notes; }
    }

    public static GraphJoinResult resolveEntityGraphJoins(EntityGraphInfo info, TableInfo table) {
        List<GraphJoin> joins = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        try {
            for (String path : info.getPaths()) {
                if (path.contains(".")) {
                    notes.add("@EntityGraph 嵌套关联路径 \"" + path
                            + "\"：需逐级实体元数据，静态降级（预览不含该 JOIN）");
                    continue;
                }
                AssociationInfo association = null;
                for (AssociationInfo candidate : table.getAssociations()) {
                    if (candidate.getFieldName().equals(path)) {
                        association = candidate;
                        break;
                    }
                }
                if (association == null) {
                    notes.add("@EntityGraph 路径 \"" + path + "\"：实体 " + table.getEntityClassName()
                            + " 无关联元数据（@OneToMany mapped 等形态），静态降级");
                    continue;
                }
                joins.add(new GraphJoin(
                        association.getTargetTable(),
                        association.getTargetAlias(),
                        association.getJoinColumn(),
                        association.getReferencedColumnName() != null ? association.getReferencedColumnName() : "id",
                        path));
            }
        } catch (Throwable ignored) {
            // 降级：返回已收集的 joins/notes
        }
        return new GraphJoinResult(joins, notes);
    }

    /** join 描述 → JoinClause（语句模型路径：previewDerivedQuery / previewCriteriaQuery）。 */
    public static JoinClause toJoinClause(GraphJoin join, String mainAlias, Dialect dialect) {
        return new JoinClause(JoinType.LEFT, join.getTargetTable(), join.getTargetAlias(),
                List.of(new Condition.ColumnComparison(
                        new ColumnRef(mainAlias, join.getMainColumn()),
                        new ColumnRef(join.getTargetAlias(), join.getTargetColumn()),
                        null)));
    }

    /** join 描述 → SQL 行（文本路径：previewJpql / previewHql）。 */
    public static String renderJoinLine(GraphJoin join, String mainAlias, Dialect dialect) {
        return "LEFT JOIN " + dialect.quoteIfKeyword(join.getTargetTable()) + " " + join.getTargetAlias() + " ON "
                + mainAlias + "." + dialect.quoteIfKeyword(join.getMainColumn())
                + " = " + join.getTargetAlias() + "." + dialect.quoteIfKeyword(join.getTargetColumn());
    }

    /**
     * 把 JOIN 行插入 SQL 文本的顶层 FROM 之后（首个顶层 WHERE/GROUP BY/ORDER BY/HAVING/LIMIT 之前）。
     * 找不到顶层插入点时降级为追加注释（不破坏原 SQL）。
     */
    public static String insertJoinLines(String sql, List<String> joinLines) {
        if (joinLines.isEmpty()) return sql;
        Integer insertAt = topLevelClauseStart(sql);
        if (insertAt == null) {
            StringBuilder degraded = new StringBuilder(sql);
            for (String joinLine : joinLines) {
                degraded.append("\n-- ").append(joinLine).append("（未能定位 FROM 子句，注释呈现）");
            }
            return degraded.toString();
        }
        return sql.substring(0, insertAt) + String.join("\n", joinLines) + sql.substring(insertAt);
    }

    // ------------------------------------------------------------------
    // 内部：PSI 辅助
    // ------------------------------------------------------------------

    /** 顶层子句关键字（括号深度 0、非字符串字面量内的首个命中）。 */
    private static final Pattern TOP_LEVEL_CLAUSE =
            Pattern.compile("\\b(WHERE|GROUP\\s+BY|ORDER\\s+BY|HAVING|LIMIT|OFFSET|FETCH|UNION)\\b",
                    Pattern.CASE_INSENSITIVE);

    /**
     * SQL 文本是否在**顶层**（括号深度 0、非字符串字面量内）含指定子句关键字。
     * 供调用方判定「查询内已含 LIMIT/ORDER BY」防重复追加。
     */
    public static boolean containsTopLevelClause(String sql, Pattern keywordRegex) {
        try {
            return topLevelMatchIndex(sql, keywordRegex) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * SQL 文本首个「顶层子句关键字」起始偏移（括号深度 0 且不在字符串字面量内）；
     * 无顶层关键字返回 null。
     */
    private static Integer topLevelClauseStart(String sql) {
        return topLevelMatchIndex(sql, TOP_LEVEL_CLAUSE);
    }

    /** 正则命中位置中第一个处于顶层（深度 0）者；无则 null。 */
    private static Integer topLevelMatchIndex(String sql, Pattern keywordRegex) {
        // 先按索引记录括号深度/字符串态，再从正则命中位置取深度（SQL 预览文本短，O(n) 两次扫描足够）
        int[] depths = new int[sql.length() + 1];
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            depths[i] = depth;
            char c = sql.charAt(i);
            if (c == '\'') {
                inString = !inString;
            } else if (!inString && c == '(') {
                depth++;
            } else if (!inString && c == ')') {
                depth--;
            }
        }
        Matcher matcher = keywordRegex.matcher(sql);
        while (matcher.find()) {
            if (depths[matcher.start()] == 0) return matcher.start();
        }
        return null;
    }

    /** 方法注解查找：FQN 未知时按短名兜底（LightPlatform/跨项目惯例）。 */
    private static PsiAnnotation findAnnotation(PsiMethod method, String shortName) {
        if (method.getModifierList() == null) return null;
        for (PsiAnnotation annotation : method.getModifierList().getAnnotations()) {
            String qualifiedName = annotation.getQualifiedName();
            String afterLastDot = qualifiedName != null ? substringAfterLast(qualifiedName, '.') : null;
            boolean byQualifiedName = shortName.equals(afterLastDot);
            boolean byReferenceName = annotation.getNameReferenceElement() != null
                    && shortName.equals(annotation.getNameReferenceElement().getReferenceName());
            if (byQualifiedName || byReferenceName) return annotation;
        }
        return null;
    }

    /** 注解属性字符串值（字面量直取；拼接表达式递归收集）。 */
    private static String stringValue(PsiAnnotationMemberValue value) {
        if (value == null) return null;
        if (value instanceof PsiLiteralExpression literal) {
            return literal.getValue() instanceof String literalText ? literalText : null;
        }
        List<String> parts = collectStringLiterals(value);
        String joined = String.join("", parts);
        return joined.isBlank() ? null : joined;
    }

    private static List<String> collectStringLiterals(PsiElement element) {
        if (element == null) return List.of();
        if (element instanceof PsiLiteralExpression literal) {
            return literal.getValue() instanceof String literalText ? List.of(literalText) : List.of();
        }
        List<String> result = new ArrayList<>();
        for (PsiElement child : element.getChildren()) {
            result.addAll(collectStringLiterals(child));
        }
        return result;
    }

    /** @QueryHints 的 value 属性 → @QueryHint 注解列表（数组/单值双形态）。 */
    private static List<PsiAnnotation> collectHintAnnotations(PsiAnnotationMemberValue value) {
        if (value == null) return List.of();
        if (value instanceof PsiAnnotation annotation) return List.of(annotation);
        List<PsiAnnotation> result = new ArrayList<>();
        for (PsiElement child : value.getChildren()) {
            if (child instanceof PsiAnnotation annotation) result.add(annotation);
        }
        return result;
    }

    /** 变量初始化式回溯一次（`Pageable p = PageRequest.of(...)` 的变量实参形态）。 */
    private static PsiExpression traceInitializer(PsiExpression expr) {
        if (!(expr instanceof PsiReferenceExpression reference)) return null;
        if (!(reference.resolve() instanceof PsiVariable variable)) return null;
        return variable.getInitializer();
    }

    /** 整数字面量求值（含变量回溯）。 */
    private static Integer intValue(PsiExpression expr) {
        if (expr == null) return null;
        SqlValue value = ExpressionEvaluator.evaluate(expr);
        if (value instanceof SqlValue.Literal literal) {
            String trimmed = literal.getUnquoted();
            int end = trimmed.length();
            while (end > 0 && (trimmed.charAt(end - 1) == 'L' || trimmed.charAt(end - 1) == 'l')) end--;
            trimmed = trimmed.substring(0, end);
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null; // SqlValue.Placeholder
    }

    // ------------------------------------------------------------------
    // 通用辅助
    // ------------------------------------------------------------------

    private static String substringBefore(String text, char c) {
        if (text == null) return "";
        int index = text.indexOf(c);
        return index >= 0 ? text.substring(0, index) : text;
    }

    private static String substringAfterLast(String text, char c) {
        if (text == null) return "";
        int index = text.lastIndexOf(c);
        return index >= 0 ? text.substring(index + 1) : text;
    }

    private static String firstNonBlank(String text) {
        return text != null && !text.isBlank() ? text : null;
    }

    /** 首字母小写（Kotlin replaceFirstChar { it.lowercaseChar() } 语义）。 */
    private static String decapitalize(String text) {
        if (text == null || text.isEmpty()) return text;
        char first = Character.toLowerCase(text.charAt(0));
        return first + text.substring(1);
    }
}
