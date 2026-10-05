package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.Connector;
import cn.song.mybatisplus.visualizer.model.DeleteStatement;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.model.LimitClause;
import cn.song.mybatisplus.visualizer.model.OrderByItem;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SetClause;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.UpdateStatement;
import cn.song.mybatisplus.visualizer.psi.ArgResolver;
import cn.song.mybatisplus.visualizer.psi.ExpressionEvaluator;
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver;
import cn.song.mybatisplus.visualizer.sql.GeneratedSql;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.SqlGenerator;
import cn.song.mybatisplus.visualizer.util.NamingStrategy;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.search.searches.ReferencesSearch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Querydsl 转换器（QD，M44 其他 ORM 第一批，矩阵 §3）。
 *
 * <p>`JPAQueryFactory` receiver（`private JPAQueryFactory queryFactory = new JPAQueryFactory(em);`）链 +
 * **表达式树 walker**——where 实参是 Operation mixin 表达式树而非平铺链（javap querydsl-jpa 5.1.0 /
 * querydsl-core 5.1.1 实证）：</p>
 *
 * <pre>
 * queryFactory.selectFrom(QUser.user)
 *     .where(QUser.user.status.eq(1), user.name.contains("x"))
 *     .orderBy(QUser.user.id.desc())
 *     .limit(10).offset(5)
 *     .fetchOne()
 * </pre>
 *
 * <h2>表达式树解析</h2>
 * <ul>
 *  <li>根：Q 类静态字段（`QUser.user`）或 Q 类型变量（`QUser user = QUser.user;`）——
 *      Q 类 superType（EntityPathBase/BeanPath）泛型参数拿实体类 → [JpaEntityResolver] 出 TableInfo；</li>
 *  <li>receiver 链末段属性名（`status`）→ 实体列名（按 @Table/@Column 注解映射，缺省 snake 兜底）；</li>
 *  <li>op（javap SimpleExpression/ComparableExpression/StringExpression/BooleanExpression）：
 *      eq/ne/gt/goe/lt/loe → Simple；in/notIn → In；isNull/isNotNull → Unary；
 *      like/notLike → Like；contains/startsWith/endsWith → Like（% 位置映射）；
 *      between/notBetween → Between；isTrue/isFalse → = TRUE/FALSE；</li>
 *  <li>and/or/not → Group（嵌套表达式递归 [parsePredicate]，谓词变量有界追踪，深度 ≤ 5）；</li>
 *  <li>BooleanBuilder 动态累积（`b.and(p1).or(p2)`）→ ReferencesSearch 收集 and/or 链；</li>
 *  <li>Expressions.*Template 等未支持形态 → 该谓词跳过（不出错误 SQL）。</li>
 * </ul>
 *
 * <h2>链面</h2>
 * <ul>
 *  <li>selectFrom(Q 根)/select(...)/from(...)；orderBy(path.desc())；limit/offset → 分页；</li>
 *  <li>写路径：update(根).set(path, 值).where(...).execute() / delete(根).where(...).execute() /
 *      insert(根).columns(paths).values(vals).execute()；</li>
 *  <li>终结点 fetch/fetchOne/fetchFirst/execute（fetchCount/fetchResults 已弃用，忽略）。</li>
 * </ul>
 *
 * <h2>预览口径</h2>
 * <p>Q 类只携带 Java 属性名，列名 = 实体 JPA 注解映射（复用 [JpaEntityResolver]）——
 * 预览注释恒标注「按实体注解映射的近似 SQL」。</p>
 *
 * <h2>边界（MVP）</h2>
 * <p>join 基础形态暂不建模（出现 join 段降级注释）；JPAExpressions 子查询、Expressions.*Template、
 * CaseBuilder、PathBuilder 动态路径（get(String)）、GroupBy 投影暂不建模。</p>
 */
public final class QuerydslConverter {

    /** 单例（Kotlin object 转换）。 */
    public static final QuerydslConverter INSTANCE = new QuerydslConverter();

    // ------------------------------------------------------------------
    // 结果模型
    // ------------------------------------------------------------------

    /**
     * Querydsl 预览结果。
     *
     * @param methodName   entry 方法名
     * @param statement    语句模型（insert 路径为 null——语句手工拼接）
     * @param sql          生成的 SQL（含尾部注释）
     * @param paramSources 未解析参数来源
     * @param paramHints   与 paramSources 并行的回填提示
     * @param tableName    表名
     * @param columns      实体全列名
     * @param warning      警示文本（全表删除等）；null 表示无
     */
    public static final class QdPreview {
        private final String methodName;
        private final SqlStatement statement;
        private final String sql;
        private final List<String> paramSources;
        private final List<ParamHint> paramHints;
        private final String tableName;
        private final List<String> columns;
        private final String warning;

        public QdPreview(String methodName, SqlStatement statement, String sql, List<String> paramSources,
                         List<ParamHint> paramHints, String tableName, List<String> columns, String warning) {
            this.methodName = methodName;
            this.statement = statement;
            this.sql = sql;
            this.paramSources = paramSources;
            this.paramHints = paramHints;
            this.tableName = tableName;
            this.columns = columns;
            this.warning = warning;
        }

        /** 便捷构造：默认参数（paramSources/paramHints 空、tableName 空串、columns 空、warning null）。 */
        public QdPreview(String methodName, SqlStatement statement, String sql) {
            this(methodName, statement, sql, List.of(), List.of(), "", List.of(), null);
        }

        public String getMethodName() {
            return methodName;
        }

        public SqlStatement getStatement() {
            return statement;
        }

        public String getSql() {
            return sql;
        }

        public List<String> getParamSources() {
            return paramSources;
        }

        public List<ParamHint> getParamHints() {
            return paramHints;
        }

        public String getTableName() {
            return tableName;
        }

        public List<String> getColumns() {
            return columns;
        }

        public String getWarning() {
            return warning;
        }
    }

    // ------------------------------------------------------------------
    // 名单
    // ------------------------------------------------------------------

    /** JPAQueryFactory entry 方法（javap JPAQueryFactory：10 方法）。 */
    private static final Set<String> ENTRY_METHODS = Set.of(
            "select", "selectDistinct", "selectOne", "selectZero", "selectFrom", "from", "query",
            "update", "delete", "insert");

    /** 比较 op → SQL 操作符（javap SimpleExpression/ComparableExpression）。 */
    private static final Map<String, String> COMPARE_OPS = new LinkedHashMap<>();

    /** 集合 / 一元 / LIKE / BETWEEN op（javap SimpleExpression/StringExpression）。 */
    private static final Set<String> IN_OPS = Set.of("in", "notIn");
    private static final Map<String, String> UNARY_OPS = new LinkedHashMap<>();

    /** LIKE op → (position, negated)（contains→%v%、startsWith→v%、endsWith→%v）。 */
    private static final Map<String, LikeOp> LIKE_OPS = new LinkedHashMap<>();

    private static final Set<String> BETWEEN_OPS = Set.of("between", "notBetween");
    private static final Set<String> BOOLEAN_OPS = Set.of("isTrue", "isFalse");

    /** 逻辑组合 op（BooleanExpression/BooleanBuilder）。 */
    private static final Set<String> GROUP_OPS = Set.of("and", "or", "not");

    /** join 段方法（降级注释用；无 fullJoin——JPQL 限制）。 */
    private static final Set<String> JOIN_METHODS = Set.of("join", "innerJoin", "leftJoin", "rightJoin", "fetchJoin");

    /** 终结点（fetchCount/fetchResults 已弃用，忽略）。 */
    private static final Set<String> TERMINALS = Set.of("fetch", "fetchOne", "fetchFirst", "iterate", "stream", "execute");

    /** 谓词变量 / BooleanBuilder 追踪深度守卫。 */
    private static final int MAX_TRACE_DEPTH = 5;

    private static final int MAX_CHAIN_DEPTH = 32;

    /** Q 路径类型后缀（javap EntityPathBase 系：StringPath/NumberPath/...）。 */
    private static final String PATH_TYPE_SUFFIX = "Path";

    /** Q 类基类简名（canonical 去泛参/包名后的匹配名单）。 */
    private static final Set<String> Q_BASE_SIMPLE_NAMES =
            Set.of("EntityPathBase", "BeanPath", "EntityPathBase<", "BeanPath<");

    static {
        COMPARE_OPS.put("eq", "=");
        COMPARE_OPS.put("ne", "!=");
        COMPARE_OPS.put("gt", ">");
        COMPARE_OPS.put("goe", ">=");
        COMPARE_OPS.put("lt", "<");
        COMPARE_OPS.put("loe", "<=");
        UNARY_OPS.put("isNull", "IS NULL");
        UNARY_OPS.put("isNotNull", "IS NOT NULL");
        LIKE_OPS.put("like", new LikeOp(LikePosition.NONE, false));
        LIKE_OPS.put("notLike", new LikeOp(LikePosition.NONE, true));
        LIKE_OPS.put("likeIgnoreCase", new LikeOp(LikePosition.NONE, false));
        LIKE_OPS.put("contains", new LikeOp(LikePosition.ANYWHERE, false));
        LIKE_OPS.put("containsIgnoreCase", new LikeOp(LikePosition.ANYWHERE, false));
        LIKE_OPS.put("startsWith", new LikeOp(LikePosition.RIGHT, false));
        LIKE_OPS.put("startsWithIgnoreCase", new LikeOp(LikePosition.RIGHT, false));
        LIKE_OPS.put("endsWith", new LikeOp(LikePosition.LEFT, false));
        LIKE_OPS.put("endsWithIgnoreCase", new LikeOp(LikePosition.LEFT, false));
    }

    private QuerydslConverter() {
    }

    // ------------------------------------------------------------------
    // 识别与入口
    // ------------------------------------------------------------------

    /** 调用是否为 Querydsl 链头（entry 方法名 + receiver 类型启发）。 */
    public static boolean isQuerydslCall(PsiMethodCallExpression call) {
        try {
            String methodName = call.getMethodExpression().getReferenceName();
            if (methodName == null || !ENTRY_METHODS.contains(methodName)) {
                return false;
            }
            return receiverIsFactory(call);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** receiver 是否为 JPAQueryFactory（变量声明类型 / 表达式类型；负例守卫：普通 receiver 不识别）。 */
    private static boolean receiverIsFactory(PsiMethodCallExpression call) {
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (qualifier == null) {
            return false;
        }
        if (qualifier instanceof PsiReferenceExpression reference
                && reference.resolve() instanceof PsiVariable variable
                && variable.getType() instanceof PsiClassType variableType
                && "JPAQueryFactory".equals(variableType.getClassName())) {
            return true;
        }
        return qualifier.getType() instanceof PsiClassType qualifierType
                && "JPAQueryFactory".equals(qualifierType.getClassName());
    }

    /**
     * 转换 Querydsl 链（链上任意位置调用均可，自动回溯 entry）。
     * 非 Querydsl 链 / 实体不可解析 → null（交回既有管道）。
     */
    public static QdPreview convert(PsiMethodCallExpression call) {
        return convert(call, true);
    }

    public static QdPreview convert(PsiMethodCallExpression call, boolean resolveVariables) {
        try {
            return convertInternal(call, resolveVariables);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static QdPreview convertInternal(PsiMethodCallExpression call, boolean resolveVariables) {
        PsiMethodCallExpression entry = findEntryCall(call, MAX_CHAIN_DEPTH);
        if (entry == null) {
            return null;
        }
        String entryName = entry.getMethodExpression().getReferenceName();
        if (entryName == null) {
            return null;
        }
        List<PsiMethodCallExpression> segments = collectSegments(entry);

        QdCtx ctx = new QdCtx(resolveVariables);
        for (int index = 1; index < segments.size(); index++) {
            PsiMethodCallExpression segment = segments.get(index);
            String name = segment.getMethodExpression().getReferenceName();
            if (name == null) {
                continue;
            }
            switch (name) {
                case "where" -> {
                    for (PsiExpression arg : segment.getArgumentList().getExpressions()) {
                        Condition condition = parsePredicate(arg, ctx, 0);
                        if (condition != null) {
                            ctx.conditions.add(condition);
                        }
                    }
                }
                case "orderBy" -> ctx.addOrderBy(parseOrderBy(segment, ctx));
                case "limit" -> {
                    PsiExpression[] args = segment.getArgumentList().getExpressions();
                    ctx.limit = args.length > 0 ? new LimitClause(null, ExpressionEvaluator.evaluate(args[0])) : null;
                }
                case "offset" -> {
                    PsiExpression[] args = segment.getArgumentList().getExpressions();
                    ctx.offset = args.length > 0 ? ExpressionEvaluator.evaluate(args[0]) : null;
                }
                case "set" -> parseSet(segment, ctx);
                case "columns" -> ctx.insertColumns = new ArrayList<>(parsePathColumns(segment, ctx));
                case "values" -> {
                    List<SqlValue> values = new ArrayList<>();
                    for (PsiExpression arg : segment.getArgumentList().getExpressions()) {
                        values.add(ExpressionEvaluator.evaluate(arg));
                    }
                    ctx.insertValues = values;
                }
                default -> {
                    if (JOIN_METHODS.contains(name)) {
                        ctx.joinSegmentSeen = true;
                    }
                    // groupBy/having/restrict 与其余段：MVP 边界，忽略
                }
            }
        }

        // entry 实参：selectFrom(QUser.user)/update(根)/delete(根)/insert(根) 的根路径；
        // select(...) 的投影列
        if ("select".equals(entryName) || "selectDistinct".equals(entryName)) {
            for (PsiExpression arg : entry.getArgumentList().getExpressions()) {
                QdPath path = resolvePath(arg, ctx, 0);
                if (path != null) {
                    ctx.selectColumns.add(ctx.columnRefOf(path));
                }
            }
        }
        QdPath rootPath = null;
        PsiExpression[] entryArgs = entry.getArgumentList().getExpressions();
        if (entryArgs.length > 0) {
            rootPath = resolveRootPath(entryArgs[0], ctx, 0);
        }
        PsiClass entityClass;
        if (rootPath != null) {
            entityClass = rootPath.entityClass;
        } else {
            // select(QUser.user.id, ...) 形态：从首个投影路径反查实体
            entityClass = null;
            for (PsiExpression arg : entryArgs) {
                QdPath path = resolvePath(arg, ctx, 0);
                if (path != null) {
                    entityClass = path.entityClass;
                    break;
                }
            }
        }
        if (entityClass == null) {
            return null;
        }
        TableInfo table = ctx.tableOf(entityClass);

        List<String> notes = new ArrayList<>();
        notes.add("Querydsl：Q 类仅携带 Java 属性名，列名按实体注解映射的近似 SQL");
        if (ctx.joinSegmentSeen) {
            notes.add("join(...)：基础 join 形态本版本暂不建模，预览不含 JOIN 子句");
        }
        SqlStatement statement;
        String sql;
        List<String> params;
        List<ParamHint> hints;
        switch (entryName) {
            case "update" -> {
                // 普查批：update 无 where → notes + 警示（对齐同函数 delete 分支既有形态）
                if (ctx.conditions.isEmpty()) {
                    notes.add("无 WHERE 条件：update(根).execute() 将更新全表（运行时 Querydsl update 无条件即全改）");
                }
                statement = new UpdateStatement(table, ctx.sets, ctx.conditions);
                GeneratedSql generated = SqlGenerator.generateWithParams(statement);
                sql = generated.getSql();
                params = generated.getUnresolvedParams();
                hints = generated.getParamHints();
            }
            case "delete" -> {
                if (ctx.conditions.isEmpty()) {
                    notes.add("无 WHERE 条件：delete(根).execute() 将删除全表（运行时 Querydsl delete 无条件即全删）");
                }
                statement = new DeleteStatement(table, ctx.conditions);
                GeneratedSql generated = SqlGenerator.generateWithParams(statement);
                sql = generated.getSql();
                params = generated.getUnresolvedParams();
                hints = generated.getParamHints();
            }
            case "insert" -> {
                // insert(根).columns(path...).values(v...)：列/值按位置对齐；无 columns 段 → 实体全列
                List<ColumnRef> columns = ctx.insertColumns;
                if (columns.isEmpty()) {
                    columns = new ArrayList<>();
                    for (String columnName : table.getAllColumnNames()) {
                        columns.add(new ColumnRef(columnName));
                    }
                }
                List<SqlValue> values = ctx.insertValues;
                List<String> colNames = new ArrayList<>();
                for (ColumnRef column : columns) {
                    colNames.add(column.getColumnName());
                }
                List<String> valueTexts = new ArrayList<>();
                for (int index = 0; index < colNames.size(); index++) {
                    SqlValue value = index < values.size() ? values.get(index) : null;
                    valueTexts.add(value instanceof SqlValue.Literal literal ? literal.getRaw() : "?");
                }
                sql = "INSERT INTO " + table.getTableName() + " (" + String.join(", ", colNames)
                        + ")\nVALUES (" + String.join(", ", valueTexts) + ")";
                List<String> insertParams = new ArrayList<>();
                for (int index = 0; index < colNames.size(); index++) {
                    SqlValue value = index < values.size() ? values.get(index) : null;
                    insertParams.add(value instanceof SqlValue.Placeholder placeholder
                            ? placeholder.getSource()
                            : "values[" + index + "]");
                }
                params = insertParams;
                List<ParamHint> insertHints = new ArrayList<>();
                for (int index = 0; index < params.size(); index++) {
                    SqlValue value = index < values.size() ? values.get(index) : null;
                    insertHints.add(new ParamHint(value == null || value instanceof SqlValue.Placeholder));
                }
                hints = insertHints;
                statement = null;
            }
            default -> {
                LimitClause limit = null;
                if (ctx.limit != null) {
                    limit = new LimitClause(ctx.offset != null ? ctx.offset : ctx.limit.getCount(),
                            ctx.limit.getCount());
                }
                statement = new SelectStatement(table, ctx.selectColumns, null, List.of(), false,
                        ctx.conditions, List.of(), null, ctx.orderBy, limit,
                        null, null, null, List.of(), null, null);
                GeneratedSql generated = SqlGenerator.generateWithParams(statement);
                sql = generated.getSql();
                params = generated.getUnresolvedParams();
                hints = generated.getParamHints();
            }
        }
        StringBuilder finalSql = new StringBuilder(sql);
        for (String note : notes) {
            finalSql.append("\n-- ").append(note);
        }
        // 普查批：update 无 where → 全表更新警示（此前只报 delete，不对称）
        String warning;
        if ("delete".equals(entryName) && ctx.conditions.isEmpty()) {
            warning = "⚠ 无 WHERE 条件，将删除全表数据！";
        } else if ("update".equals(entryName) && ctx.conditions.isEmpty()) {
            warning = "⚠ 无 WHERE 条件，将更新全表数据！";
        } else {
            warning = null;
        }
        return new QdPreview(entryName, statement, finalSql.toString(), params, hints, table.getTableName(),
                table.getAllColumnNames(), warning);
    }

    // ------------------------------------------------------------------
    // 段解析
    // ------------------------------------------------------------------

    /** orderBy(QUser.user.id.desc(), ...) → ORDER BY。 */
    private static List<OrderByItem> parseOrderBy(PsiMethodCallExpression segment, QdCtx ctx) {
        List<OrderByItem> result = new ArrayList<>();
        for (PsiExpression arg : segment.getArgumentList().getExpressions()) {
            if (!(arg instanceof PsiMethodCallExpression call)) {
                continue;
            }
            String referenceName = call.getMethodExpression().getReferenceName();
            boolean asc;
            if ("desc".equals(referenceName)) {
                asc = false;
            } else if ("asc".equals(referenceName)) {
                asc = true;
            } else {
                continue;
            }
            QdPath path = resolvePath(call.getMethodExpression().getQualifierExpression(), ctx, 0);
            if (path == null) {
                continue;
            }
            result.add(new OrderByItem(ctx.columnRefOf(path), asc));
        }
        return result;
    }

    /** update 系 set(path, 值) → SET 子句。 */
    private static void parseSet(PsiMethodCallExpression segment, QdCtx ctx) {
        PsiExpression[] args = segment.getArgumentList().getExpressions();
        if (args.length == 0) {
            return;
        }
        QdPath path = resolvePath(args[0], ctx, 0);
        if (path == null) {
            return;
        }
        if (args.length < 2) {
            return;
        }
        SqlValue value = ExpressionEvaluator.evaluate(args[1]);
        ctx.sets.add(new SetClause(ctx.columnRefOf(path), value));
    }

    /** columns(path...) → INSERT 列清单。 */
    private static List<ColumnRef> parsePathColumns(PsiMethodCallExpression segment, QdCtx ctx) {
        List<ColumnRef> result = new ArrayList<>();
        for (PsiExpression arg : segment.getArgumentList().getExpressions()) {
            QdPath path = resolvePath(arg, ctx, 0);
            if (path != null) {
                result.add(ctx.columnRefOf(path));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 表达式树 walker（谓词 → Condition）
    // ------------------------------------------------------------------

    /**
     * 谓词表达式 → Condition（递归）：
     * <ul>
     *  <li>谓词变量（`Predicate p = ...`）有界追踪初始化式；BooleanBuilder 变量收集 and/or 引用链；</li>
     *  <li>方法调用形态：op = 方法名，receiver = 属性路径，实参求值；</li>
     *  <li>and/or/not → Group 递归组合。</li>
     * </ul>
     */
    private static Condition parsePredicate(PsiExpression expr, QdCtx ctx, int depth) {
        if (expr == null || depth > MAX_TRACE_DEPTH) {
            return null;
        }
        // 谓词变量追踪
        if (expr instanceof PsiReferenceExpression reference) {
            PsiElement resolved = reference.resolve();
            if (resolved instanceof PsiVariable variable) {
                PsiExpression init = variable.getInitializer();
                if (init != null) {
                    Condition fromInit = parsePredicate(init, ctx, depth + 1);
                    if (fromInit != null) {
                        return fromInit;
                    }
                }
                if (isBooleanBuilderType(variable.getType())) {
                    return booleanBuilderCondition(variable, ctx, depth);
                }
            }
            return null;
        }
        if (!(expr instanceof PsiMethodCallExpression call)) {
            return null;
        }
        String op = call.getMethodExpression().getReferenceName();
        if (op == null) {
            return null;
        }
        PsiExpression receiver = call.getMethodExpression().getQualifierExpression();
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (GROUP_OPS.contains(op)) {
            Connector connector = "or".equals(op) ? Connector.OR : Connector.AND;
            if ("not".equals(op)) {
                Condition child = receiver != null ? parsePredicate(receiver, ctx, depth + 1) : null;
                if (child == null) {
                    return null;
                }
                return new Condition.Group(Connector.AND, List.of(child), null, true);
            }
            List<Condition> children = new ArrayList<>();
            if (receiver != null) {
                Condition child = parsePredicate(receiver, ctx, depth + 1);
                if (child != null) {
                    children.add(child);
                }
            }
            if (args.length > 0) {
                Condition child = parsePredicate(args[0], ctx, depth + 1);
                if (child != null) {
                    children.add(child);
                }
            }
            if (children.size() < 2) {
                return children.isEmpty() ? null : children.get(0);
            }
            return new Condition.Group(connector, children, null);
        }
        if (COMPARE_OPS.containsKey(op)) {
            if (receiver == null) {
                return null;
            }
            QdPath path = resolvePath(receiver, ctx, depth);
            if (path == null) {
                return null;
            }
            if (args.length == 0) {
                return null;
            }
            SqlValue value = ExpressionEvaluator.evaluate(args[0]);
            return new Condition.Simple(ctx.columnRefOf(path), COMPARE_OPS.get(op), value, null);
        }
        if (IN_OPS.contains(op)) {
            if (receiver == null) {
                return null;
            }
            QdPath path = resolvePath(receiver, ctx, depth);
            if (path == null) {
                return null;
            }
            List<SqlValue> values = new ArrayList<>();
            for (PsiExpression arg : args) {
                values.add(ExpressionEvaluator.evaluate(arg));
            }
            if (values.isEmpty()) {
                return null;
            }
            return new Condition.In(ctx.columnRefOf(path), "notIn".equals(op), values, null);
        }
        if (UNARY_OPS.containsKey(op)) {
            if (receiver == null) {
                return null;
            }
            QdPath path = resolvePath(receiver, ctx, depth);
            if (path == null) {
                return null;
            }
            return new Condition.Unary(ctx.columnRefOf(path), UNARY_OPS.get(op), null);
        }
        if (LIKE_OPS.containsKey(op)) {
            if (receiver == null) {
                return null;
            }
            QdPath path = resolvePath(receiver, ctx, depth);
            if (path == null) {
                return null;
            }
            LikeOp likeOp = LIKE_OPS.get(op);
            if (args.length == 0) {
                return null;
            }
            SqlValue value = ExpressionEvaluator.evaluate(args[0]);
            return new Condition.Like(ctx.columnRefOf(path), likeOp.negated, value, likeOp.position, null);
        }
        if (BETWEEN_OPS.contains(op)) {
            if (receiver == null) {
                return null;
            }
            QdPath path = resolvePath(receiver, ctx, depth);
            if (path == null) {
                return null;
            }
            if (args.length == 0) {
                return null;
            }
            SqlValue lo = ExpressionEvaluator.evaluate(args[0]);
            if (args.length < 2) {
                return null;
            }
            SqlValue hi = ExpressionEvaluator.evaluate(args[1]);
            return new Condition.Between(ctx.columnRefOf(path), "notBetween".equals(op), lo, hi, null);
        }
        if (BOOLEAN_OPS.contains(op)) {
            if (receiver == null) {
                return null;
            }
            QdPath path = resolvePath(receiver, ctx, depth);
            if (path == null) {
                return null;
            }
            return new Condition.Simple(ctx.columnRefOf(path), "=",
                    new SqlValue.Literal("isTrue".equals(op) ? "TRUE" : "FALSE"), null);
        }
        return null;
    }

    /** BooleanBuilder 变量 and/or 引用链收集（`b.and(p1).or(p2)`）→ AND 组（OR 内嵌组）；按源码顺序连接。 */
    private static Condition booleanBuilderCondition(PsiVariable variable, QdCtx ctx, int depth) {
        if (depth > MAX_TRACE_DEPTH) {
            return null;
        }
        // 初始谓词（new BooleanBuilder(pred)）
        Condition initPredicate = null;
        if (variable.getInitializer() instanceof PsiMethodCallExpression initCall) {
            PsiExpression[] initArgs = initCall.getArgumentList().getExpressions();
            if (initArgs.length > 0) {
                initPredicate = parsePredicate(initArgs[0], ctx, depth + 1);
            }
        }
        List<Condition> conditions = new ArrayList<>();
        if (initPredicate != null) {
            conditions.add(initPredicate);
        }
        boolean sawOr = false;
        List<RefEntry> refs = new ArrayList<>();
        for (PsiReference reference : ReferencesSearch.search(variable).findAll()) {
            if (!(reference.getElement() instanceof PsiReferenceExpression ref)) {
                continue;
            }
            // .and(pred) / .or(pred)：引用的父是 methodExpression → 调用
            PsiElement parent = ref.getParent();
            PsiElement grandParent = parent != null ? parent.getParent() : null;
            if (!(grandParent instanceof PsiMethodCallExpression call)) {
                continue;
            }
            String op = call.getMethodExpression().getReferenceName();
            if (!"and".equals(op) && !"or".equals(op)) {
                continue;
            }
            refs.add(new RefEntry(call.getTextRange().getStartOffset(), op, call));
        }
        refs.sort(Comparator.comparingInt((QuerydslConverter.RefEntry entry) -> entry.offset));
        for (RefEntry entry : refs) {
            PsiExpression[] entryArgs = entry.call.getArgumentList().getExpressions();
            Condition arg = entryArgs.length > 0 ? parsePredicate(entryArgs[0], ctx, depth + 1) : null;
            if (arg == null) {
                continue;
            }
            if ("or".equals(entry.op) && !conditions.isEmpty()) {
                sawOr = true;
                Condition prev = conditions.remove(conditions.size() - 1);
                conditions.add(new Condition.Group(Connector.OR, List.of(prev, arg), null));
            } else {
                conditions.add(arg);
            }
        }
        if (conditions.isEmpty()) {
            return null;
        }
        if (sawOr || conditions.size() == 1) {
            return conditions.get(0);
        }
        return new Condition.Group(Connector.AND, conditions, null);
    }

    private static boolean isBooleanBuilderType(PsiType type) {
        return type instanceof PsiClassType classType && "BooleanBuilder".equals(classType.getClassName());
    }

    // ------------------------------------------------------------------
    // Q 路径解析（根 → 实体；属性 → 列）
    // ------------------------------------------------------------------

    /**
     * 路径解析产物。
     *
     * @param entityClass 根 Q 类对应的实体类
     * @param property    receiver 链末段属性名（`QUser.user.status` → status）；根本身为 null
     * @param nestedDepth 属性段数（&gt;1 = 嵌套路径，末段近似 + 注释）
     */
    private static final class QdPath {
        final PsiClass entityClass;
        final String property;
        final int nestedDepth;

        QdPath(PsiClass entityClass, String property, int nestedDepth) {
            this.entityClass = entityClass;
            this.property = property;
            this.nestedDepth = nestedDepth;
        }
    }

    /** 表达式 → Q 路径（根 = Q 类静态字段 / Q 类型变量；属性段沿 qualifier 外扩收集）。 */
    private static QdPath resolvePath(PsiExpression expr, QdCtx ctx, int depth) {
        if (expr == null || depth > MAX_TRACE_DEPTH) {
            return null;
        }
        List<String> segments = new ArrayList<>();
        PsiElement current = expr;
        while (current instanceof PsiReferenceExpression reference) {
            PsiElement resolved = reference.resolve();
            if (resolved == null) {
                return null;
            }
            // 根形态 1：Q 类静态字段（`QUser.user`）——字段类型即 Q 类
            if (resolved instanceof PsiField field && isQClass(psiClassOfType(field.getType()))) {
                PsiClass qClass = psiClassOfType(field.getType());
                if (qClass == null) {
                    return null;
                }
                PsiClass entity = entityOfQClass(qClass);
                if (entity == null) {
                    return null;
                }
                return new QdPath(entity, segments.isEmpty() ? null : segments.get(segments.size() - 1),
                        segments.size());
            }
            // 根形态 2：Q 类型变量（`QUser user = QUser.user;`）
            if (resolved instanceof PsiVariable variable && isQClass(psiClassOfType(variable.getType()))) {
                PsiClass qClass = psiClassOfType(variable.getType());
                if (qClass == null) {
                    return null;
                }
                PsiClass entity = entityOfQClass(qClass);
                if (entity == null) {
                    return null;
                }
                return new QdPath(entity, segments.isEmpty() ? null : segments.get(segments.size() - 1),
                        segments.size());
            }
            // 属性段（Q 类实例字段：StringPath/NumberPath/...）
            String referenceName = reference.getReferenceName();
            if (referenceName == null) {
                return null;
            }
            segments.add(referenceName);
            current = reference.getQualifierExpression();
        }
        return null;
    }

    /** 根路径（selectFrom/update/delete/insert 实参）：忽略属性段，仅取实体。 */
    private static QdPath resolveRootPath(PsiExpression expr, QdCtx ctx, int depth) {
        QdPath path = resolvePath(expr, ctx, depth);
        return path != null ? new QdPath(path.entityClass, null, path.nestedDepth) : null;
    }

    /** PsiType → PsiClass（非类类型返回 null）。 */
    private static PsiClass psiClassOfType(PsiType type) {
        return type instanceof PsiClassType classType ? classType.resolve() : null;
    }

    /** 类是否为 Q 类（简名 Q 前缀 + EntityPathBase/BeanPath 系 superType）。 */
    private static boolean isQClass(PsiClass clazz) {
        if (clazz == null) {
            return false;
        }
        String name = clazz.getName();
        if (name == null || !name.startsWith("Q")) {
            return false;
        }
        for (PsiClassType superType : clazz.getSuperTypes()) {
            String canonical = superType.getCanonicalText();
            int genericStart = canonical.indexOf('<');
            String base = genericStart >= 0 ? canonical.substring(0, genericStart) : canonical;
            int lastDot = base.lastIndexOf('.');
            String simple = lastDot >= 0 ? base.substring(lastDot + 1) : base;
            if (Q_BASE_SIMPLE_NAMES.contains(simple)) {
                return true;
            }
            if (canonical.startsWith("com.querydsl.core.types.dsl.EntityPathBase")
                    || canonical.startsWith("com.querydsl.core.types.dsl.BeanPath")) {
                return true;
            }
        }
        return false;
    }

    /** Q 类 → 实体类（superType EntityPathBase/BeanPath 泛型第一参；测试环境 stub 按简名匹配）。 */
    private static PsiClass entityOfQClass(PsiClass qClass) {
        for (PsiClassType superType : qClass.getSuperTypes()) {
            String canonical = superType.getCanonicalText();
            int genericStart = canonical.indexOf('<');
            String base = genericStart >= 0 ? canonical.substring(0, genericStart) : canonical;
            int lastDot = base.lastIndexOf('.');
            String simple = lastDot >= 0 ? base.substring(lastDot + 1) : base;
            if ("EntityPathBase".equals(simple) || "BeanPath".equals(simple)
                    || canonical.startsWith("com.querydsl.core.types.dsl.EntityPathBase")
                    || canonical.startsWith("com.querydsl.core.types.dsl.BeanPath")) {
                PsiType[] parameters = superType.getParameters();
                if (parameters.length == 0 || !(parameters[0] instanceof PsiClassType paramType)) {
                    continue;
                }
                return paramType.resolve();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 链回溯与上下文
    // ------------------------------------------------------------------

    private static PsiMethodCallExpression findEntryCall(PsiMethodCallExpression from, int maxDepth) {
        PsiMethodCallExpression current = from;
        int depth = 0;
        while (depth++ < maxDepth) {
            if (isQuerydslCall(current)) {
                return current;
            }
            PsiExpression qualifier = current.getMethodExpression().getQualifierExpression();
            if (!(qualifier instanceof PsiMethodCallExpression qualifierCall)) {
                return null;
            }
            current = qualifierCall;
        }
        return null;
    }

    /**
     * entry → 链尾完整段列表（头=entry）。
     * 回溯处理调用层 + **引用层**（entry.parent 是外层调用的 methodExpression）两层延续——
     * 漏掉引用层则 segments 恒为 [entry]，where/orderBy/limit/set 全丢（表名来自 entry 实参不受影响）。
     */
    private static List<PsiMethodCallExpression> collectSegments(PsiMethodCallExpression entry) {
        PsiMethodCallExpression outermost = entry;
        PsiElement cursor = entry.getParent();
        while (cursor != null) {
            if (cursor instanceof PsiMethodCallExpression cursorCall
                    && cursorCall.getMethodExpression().getQualifierExpression() == outermost) {
                outermost = cursorCall;
                cursor = cursorCall.getParent();
            } else if (cursor instanceof PsiReferenceExpression cursorRef
                    && cursorRef.getQualifierExpression() == outermost) {
                if (!(cursorRef.getParent() instanceof PsiMethodCallExpression next)) {
                    break;
                }
                outermost = next;
                cursor = next.getParent();
            } else {
                break;
            }
        }
        // query() 中转形态：queryFactory.query().from(...) —— from 段成为 entry
        return ArgResolver.collectCallChain(outermost);
    }

    /** LIKE op 的 (position, negated) 二元组。 */
    private static final class LikeOp {
        final LikePosition position;
        final boolean negated;

        LikeOp(LikePosition position, boolean negated) {
            this.position = position;
            this.negated = negated;
        }
    }

    /** BooleanBuilder 引用链条目（按源码偏移排序）。 */
    private static final class RefEntry {
        final int offset;
        final String op;
        final PsiMethodCallExpression call;

        RefEntry(int offset, String op, PsiMethodCallExpression call) {
            this.offset = offset;
            this.op = op;
            this.call = call;
        }
    }

    /** 转换上下文（每次 convert 独立实例）。 */
    private static final class QdCtx {
        final boolean resolveVariables;
        final List<ColumnRef> selectColumns = new ArrayList<>();
        final List<Condition> conditions = new ArrayList<>();
        final List<OrderByItem> orderBy = new ArrayList<>();
        final List<SetClause> sets = new ArrayList<>();
        List<ColumnRef> insertColumns = new ArrayList<>();
        List<SqlValue> insertValues = new ArrayList<>();
        LimitClause limit;
        SqlValue offset;
        boolean joinSegmentSeen;

        /** 实体类 → TableInfo 缓存（QD 预览口径：实体注解映射）。 */
        private final Map<PsiClass, TableInfo> tableCache = new HashMap<>();

        QdCtx(boolean resolveVariables) {
            this.resolveVariables = resolveVariables;
        }

        TableInfo tableOf(PsiClass entityClass) {
            return tableCache.computeIfAbsent(entityClass, key -> new JpaEntityResolver().resolve(key));
        }

        void addOrderBy(List<OrderByItem> items) {
            orderBy.addAll(items);
        }

        /** 路径 → 列引用：属性列名 = 实体注解映射（columnByField）或 snake 兜底；嵌套路径末段近似。 */
        ColumnRef columnRefOf(QdPath path) {
            String columnName;
            if (path.property == null) {
                String className = path.entityClass.getName();
                columnName = className != null ? NamingStrategy.INSTANCE.toSnakeCase(className) : "id";
            } else {
                ColumnInfo column = tableOf(path.entityClass).columnByField(path.property);
                columnName = column != null ? column.getColumnName()
                        : NamingStrategy.INSTANCE.toSnakeCase(path.property);
            }
            return new ColumnRef(columnName);
        }
    }
}
