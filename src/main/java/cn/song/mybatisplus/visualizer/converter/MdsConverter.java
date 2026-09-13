package cn.song.mybatisplus.visualizer.converter;

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
import cn.song.mybatisplus.visualizer.sql.GeneratedSql;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.SqlGenerator;
import cn.song.mybatisplus.visualizer.util.NamingStrategy;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MyBatis Dynamic SQL 转换器（DS，M44 其他 ORM 第一批，矩阵 §7）。
 *
 * <p>MyBatis 官方 DSL（javap org.mybatis.dynamic-sql 2.0.0 实证）：手写 SqlTable 常量（无生成代码）
 * → builder 链 → build() 出 SelectModel → render(RenderingStrategy) 出 SQL：
 * `SelectDSL.select(UserDynamicSqlSupport.userId).from(userTable).where(col, isEqualTo(1)
 * .and(col2, isIn(1, 2))).orderBy(col.descending()).limit(10).build().render(...)`</p>
 *
 * <h2>链面（javap SelectDSL / LimitAndOffsetOperations / WhereOperations）</h2>
 * <ul>
 *  <li>entry：`SelectDSL.select(BasicColumn...)/selectDistinct(...)`（static，2.0 dsl 包）；</li>
 *  <li>`from(SqlTable[, alias])`、`where(col, 谓词[.and(col, 谓词)/.or(...)])`、`orderBy(排序列...)`、
 *      `limit(n)`/`offset(n)`（build/render/union 段忽略）；</li>
 *  <li>谓词（SqlBuilder 静态工厂 / org.mybatis.dynamic.sql.condition.*）：isEqualTo/isNotEqualTo/
 *      isIn/notIn/isLike/isNull/isNotNull/isBetween/isNotBetween/greaterThan/greaterThanOrEqualTo/
 *      lessThan/lessThanOrEqualTo（WhenPresent 变体同法近似渲染）；</li>
 *  <li>update/delete 基础形态：`UpdateDSL.update(table).set(col).equalTo(v).where(...)` /
 *      `DeleteDSL.deleteFrom(table).where(col, isEqualTo(...))`（1.x SqlBuilder 同名入口兼容）。</li>
 * </ul>
 *
 * <h2>列常量解析（SqlColumn 字段声明处初始化，无生成代码依赖）</h2>
 * <p>`UserDynamicSqlSupport.userId` → 字段初始化 `user.id`（转发引用）→ SqlTable 子类字段
 * `column("id", JDBCType.BIGINT)` → 列名 = 首个字符串字面量、所属表 = 子类构造 `super("t_user")`
 * 串 / `SqlTable.of("t_user")` / `new SqlTable("t_user")`。解析失败按字段名 snake 兜底。</p>
 *
 * <h2>占位符形态说明</h2>
 * <p>MDS 运行时渲染按 RenderingStrategy 产生 `#{parameters[..]}`（MyBatis3）或 `:name`
 * （SpringNamedParameter）形态；本插件参数面板统一 `?` 顺序占位 + 参数行（预览尾部注释标注）。</p>
 *
 * <h2>边界（MVP）</h2>
 * <ul>
 *  <li>insert/insertMultiple/insertSelect（记录形态）暂不建模；union/with/子查询条件暂不建模；</li>
 *  <li>`and(...)/or(...)` 分组谓词、not(...) 嵌套暂不建模（忽略，不出错误 SQL）。</li>
 * </ul>
 */
public final class MdsConverter {

    /** 单例（Kotlin object 转换）。 */
    public static final MdsConverter INSTANCE = new MdsConverter();

    // ------------------------------------------------------------------
    // 结果模型
    // ------------------------------------------------------------------

    /**
     * MyBatis Dynamic SQL 预览结果。
     *
     * @param methodName   entry 方法名（select/selectDistinct/deleteFrom/update）
     * @param statement    语句模型
     * @param sql          生成的 SQL（含尾部注释）
     * @param paramSources 未解析参数来源
     * @param paramHints   与 paramSources 并行的回填提示
     * @param tableName    表名
     * @param columns      SELECT 列名清单（select 系；其余为空）
     * @param warning      警示文本（全表删除等）；null 表示无
     */
    public static final class MdsPreview {
        private final String methodName;
        private final SqlStatement statement;
        private final String sql;
        private final List<String> paramSources;
        private final List<ParamHint> paramHints;
        private final String tableName;
        private final List<String> columns;
        private final String warning;

        public MdsPreview(String methodName, SqlStatement statement, String sql, List<String> paramSources,
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
        public MdsPreview(String methodName, SqlStatement statement, String sql) {
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

    /** entry 工厂类（2.0 dsl 包 SelectDSL/UpdateDSL/DeleteDSL + 1.x SqlBuilder 兼容）。 */
    private static final Set<String> ENTRY_CLASSES = Set.of("SelectDSL", "SqlBuilder", "DeleteDSL", "UpdateDSL");

    /** entry 方法名（select 系 SELECT；deleteFrom DELETE；update UPDATE）。 */
    private static final Set<String> ENTRY_METHODS = Set.of("select", "selectDistinct", "deleteFrom", "update");

    /** 比较谓词 → 操作符（SqlBuilder 静态工厂）。 */
    private static final Map<String, String> COMPARE_PREDICATES = new LinkedHashMap<>();

    /** IN / LIKE / BETWEEN / 一元谓词。 */
    private static final Set<String> IN_PREDICATES = Set.of("isIn", "notIn");
    private static final Map<String, String> UNARY_PREDICATES = new LinkedHashMap<>();
    private static final Set<String> LIKE_PREDICATES = Set.of("isLike");
    private static final Set<String> BETWEEN_PREDICATES = Set.of("isBetween", "isNotBetween");
    private static final Set<String> PREDICATES = new java.util.HashSet<>();

    /** where 谓词链连接方法（and(col, pred) / or(col, pred)）。 */
    private static final Set<String> CONNECTORS = Set.of("and", "or");

    /** 列常量初始化方法名（SqlTable.column / SqlTable.of）。 */
    private static final Set<String> COLUMN_INITIALIZER_METHODS = Set.of("column", "of");

    /** 链遍历深度守卫。 */
    private static final int MAX_CHAIN_DEPTH = 32;

    static {
        COMPARE_PREDICATES.put("isEqualTo", "=");
        COMPARE_PREDICATES.put("isNotEqualTo", "!=");
        COMPARE_PREDICATES.put("greaterThan", ">");
        COMPARE_PREDICATES.put("greaterThanOrEqualTo", ">=");
        COMPARE_PREDICATES.put("lessThan", "<");
        COMPARE_PREDICATES.put("lessThanOrEqualTo", "<=");
        UNARY_PREDICATES.put("isNull", "IS NULL");
        UNARY_PREDICATES.put("isNotNull", "IS NOT NULL");
        PREDICATES.addAll(COMPARE_PREDICATES.keySet());
        PREDICATES.addAll(IN_PREDICATES);
        PREDICATES.addAll(UNARY_PREDICATES.keySet());
        PREDICATES.addAll(LIKE_PREDICATES);
        PREDICATES.addAll(BETWEEN_PREDICATES);
    }

    private MdsConverter() {
    }

    // ------------------------------------------------------------------
    // 识别与入口
    // ------------------------------------------------------------------

    /** 调用是否为 MDS DSL 链头（静态工厂类 + entry 方法名）。 */
    public static boolean isMdsCall(PsiMethodCallExpression call) {
        try {
            String methodName = call.getMethodExpression().getReferenceName();
            if (methodName == null || !ENTRY_METHODS.contains(methodName)) {
                return false;
            }
            PsiExpression qualifierExpression = call.getMethodExpression().getQualifierExpression();
            String qualifierName = qualifierExpression instanceof PsiReferenceExpression reference
                    ? reference.getReferenceName()
                    : null;
            return qualifierName != null && ENTRY_CLASSES.contains(qualifierName);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 转换 MDS 链（链上任意位置调用均可，自动回溯 entry）。
     * 解析失败（无 from 表等）返回 null——不做错误 SQL 降级。
     */
    public static MdsPreview convert(PsiMethodCallExpression call) {
        return convert(call, true);
    }

    public static MdsPreview convert(PsiMethodCallExpression call, boolean resolveVariables) {
        try {
            return convertInternal(call, resolveVariables);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MdsPreview convertInternal(PsiMethodCallExpression call, boolean resolveVariables) {
        PsiMethodCallExpression entry = findEntryCall(call, MAX_CHAIN_DEPTH);
        if (entry == null) {
            return null;
        }
        String entryName = entry.getMethodExpression().getReferenceName();
        if (entryName == null) {
            return null;
        }
        List<PsiMethodCallExpression> segments = collectSegments(entry);

        MdsCtx ctx = new MdsCtx();
        // entry 实参 = SELECT 列清单（select(colA, colB)）；allColumns() 等方法调用实参解析为 null
        // → 不入列清单（生成器降级全列展开）
        if ("select".equals(entryName) || "selectDistinct".equals(entryName)) {
            for (PsiExpression arg : entry.getArgumentList().getExpressions()) {
                String columnName = resolveColumnName(arg, ctx);
                if (columnName != null) {
                    ctx.selectColumns.add(new ColumnRef(columnName));
                }
            }
        }
        // update(table) / deleteFrom(table)：表名来自 entry 首参（SqlTable 常量）
        if ("deleteFrom".equals(entryName) || "update".equals(entryName)) {
            PsiExpression[] entryArgs = entry.getArgumentList().getExpressions();
            ctx.tableName = entryArgs.length > 0 ? resolveSqlTableName(entryArgs[0]) : null;
        }
        for (int index = 1; index < segments.size(); index++) {
            PsiMethodCallExpression segment = segments.get(index);
            String name = segment.getMethodExpression().getReferenceName();
            if (name == null) {
                continue;
            }
            switch (name) {
                case "from" -> parseFrom(segment, ctx);
                case "where" -> ctx.addConditions(parseWhereCall(segment, ctx));
                case "orderBy" -> ctx.addOrderBy(parseOrderBy(segment, ctx));
                case "limit" -> {
                    SqlValue count = limitArg(segment);
                    ctx.limit = count != null ? new LimitClause(null, count) : null;
                }
                case "offset" -> ctx.offset = limitArg(segment);
                case "set" -> parseSet(segment, ctx);
                case "equalTo", "equalToWhenPresent" -> completeSet(segment, ctx);
                default -> {
                    // build/render/union/distinct 等段忽略
                }
            }
        }
        String tableName = ctx.tableName;
        if (tableName == null) {
            return null;
        }
        TableInfo table = new TableInfo(tableName, tableName, List.of());
        List<String> notes = new ArrayList<>();
        notes.add("-- MyBatis Dynamic SQL：运行时渲染占位符为 #{parameters[..]}（MyBatis3 策略），预览统一 ? 顺序占位");
        SqlStatement statement;
        switch (entryName) {
            case "select", "selectDistinct" -> {
                LimitClause limit = null;
                if (ctx.limit != null) {
                    limit = new LimitClause(ctx.offset != null ? ctx.offset : ctx.limit.getCount(),
                            ctx.limit.getCount());
                }
                statement = new SelectStatement(table, ctx.selectColumns, ctx.tableAlias, List.of(),
                        "selectDistinct".equals(entryName), ctx.conditions, List.of(), null, ctx.orderBy, limit,
                        null, null, null, List.of(), null, null);
            }
            case "deleteFrom" -> statement = new DeleteStatement(table, ctx.conditions);
            case "update" -> statement = new UpdateStatement(table, ctx.sets, ctx.conditions);
            default -> {
                return null;
            }
        }
        String warning = "deleteFrom".equals(entryName) && ctx.conditions.isEmpty()
                ? "⚠ 无 WHERE 条件，将删除全表数据！"
                : null;
        GeneratedSql generated = SqlGenerator.generateWithParams(statement);
        StringBuilder sql = new StringBuilder(generated.getSql());
        for (String note : notes) {
            sql.append('\n').append(note);
        }
        List<String> columns = new ArrayList<>();
        for (ColumnRef column : ctx.selectColumns) {
            columns.add(column.getColumnName());
        }
        return new MdsPreview(entryName, statement, sql.toString(), generated.getUnresolvedParams(),
                generated.getParamHints(), tableName, columns, warning);
    }

    // ------------------------------------------------------------------
    // 段解析
    // ------------------------------------------------------------------

    /** from(table[, alias]) → 表名 + 别名。 */
    private static void parseFrom(PsiMethodCallExpression segment, MdsCtx ctx) {
        PsiExpression[] args = segment.getArgumentList().getExpressions();
        if (args.length == 0) {
            return;
        }
        ctx.tableName = resolveSqlTableName(args[0]);
        if (ctx.tableName == null) {
            return;
        }
        String alias = null;
        if (args.length > 1 && args[1] instanceof PsiLiteralExpression literal
                && literal.getValue() instanceof String value) {
            alias = value;
        }
        if (alias != null && !alias.isBlank()) {
            ctx.tableAlias = alias;
        }
    }

    /** where(col, isEqualTo(1).and(col2, isIn(1, 2))) → 条件列表（or 顺序合并为 OR 组）。 */
    private static List<Condition> parseWhereCall(PsiMethodCallExpression segment, MdsCtx ctx) {
        PsiExpression[] args = segment.getArgumentList().getExpressions();
        if (args.length < 2 || !(args[1] instanceof PsiMethodCallExpression tail)) {
            return List.of();
        }
        String firstColumn = resolveColumnName(args[0], ctx);
        if (firstColumn == null) {
            return List.of();
        }
        List<PsiMethodCallExpression> predicateChain = ArgResolver.collectCallChain(tail);
        List<Condition> result = new ArrayList<>();
        for (PsiMethodCallExpression predCall : predicateChain) {
            String name = predCall.getMethodExpression().getReferenceName();
            if (name == null) {
                continue;
            }
            if (PREDICATES.contains(name) && result.isEmpty()) {
                // 链头谓词：作用于 where 首参列
                Condition condition = buildPredicate(firstColumn, predCall, ctx);
                if (condition != null) {
                    result.add(condition);
                }
            } else if (CONNECTORS.contains(name)) {
                // and(col, pred) / or(col, pred)：换列 + 连接符
                PsiExpression[] connectorArgs = predCall.getArgumentList().getExpressions();
                if (connectorArgs.length < 2) {
                    continue;
                }
                String column = resolveColumnName(connectorArgs[0], ctx);
                if (column == null) {
                    continue;
                }
                if (!(connectorArgs[1] instanceof PsiMethodCallExpression nested)) {
                    continue;
                }
                String nestedName = nested.getMethodExpression().getReferenceName();
                if (nestedName == null || !PREDICATES.contains(nestedName)) {
                    continue;
                }
                Condition condition = buildPredicate(column, nested, ctx);
                if (condition == null) {
                    continue;
                }
                if ("or".equals(name) && !result.isEmpty()) {
                    Condition prev = result.remove(result.size() - 1);
                    result.add(new Condition.Group(Connector.OR, List.of(prev, condition), null));
                } else {
                    result.add(condition);
                }
            }
        }
        return result;
    }

    /** 谓词调用 → Condition（谓词名单见类 KDoc；值实参静态求值）。 */
    private static Condition buildPredicate(String column, PsiMethodCallExpression call, MdsCtx ctx) {
        String name = call.getMethodExpression().getReferenceName();
        if (name == null) {
            return null;
        }
        ColumnRef columnRef = ctx.aliasedColumn(column);
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (COMPARE_PREDICATES.containsKey(name)) {
            if (args.length == 0) {
                return null;
            }
            return new Condition.Simple(columnRef, COMPARE_PREDICATES.get(name),
                    ExpressionEvaluator.evaluate(args[0]), null);
        }
        if (IN_PREDICATES.contains(name)) {
            List<SqlValue> values = new ArrayList<>();
            for (PsiExpression arg : args) {
                values.add(ExpressionEvaluator.evaluate(arg));
            }
            if (values.isEmpty()) {
                return null;
            }
            return new Condition.In(columnRef, "notIn".equals(name), values, null);
        }
        if (UNARY_PREDICATES.containsKey(name)) {
            return new Condition.Unary(columnRef, UNARY_PREDICATES.get(name), null);
        }
        if (LIKE_PREDICATES.contains(name)) {
            if (args.length == 0) {
                return null;
            }
            // MDS isLike 不自动加 %——模式原样
            return new Condition.Like(columnRef, false, ExpressionEvaluator.evaluate(args[0]),
                    LikePosition.NONE, null);
        }
        if (BETWEEN_PREDICATES.contains(name)) {
            if (args.length == 0) {
                return null;
            }
            SqlValue lo = ExpressionEvaluator.evaluate(args[0]);
            if (args.length < 2) {
                return null;
            }
            SqlValue hi = ExpressionEvaluator.evaluate(args[1]);
            return new Condition.Between(columnRef, "isNotBetween".equals(name), lo, hi, null);
        }
        return null;
    }

    /** orderBy(col.descending(), col2) → ORDER BY（descending DESC / ascending/缺省 ASC）。 */
    private static List<OrderByItem> parseOrderBy(PsiMethodCallExpression segment, MdsCtx ctx) {
        List<OrderByItem> result = new ArrayList<>();
        for (PsiExpression arg : segment.getArgumentList().getExpressions()) {
            if (arg instanceof PsiMethodCallExpression call) {
                String referenceName = call.getMethodExpression().getReferenceName();
                boolean asc;
                if ("descending".equals(referenceName)) {
                    asc = false;
                } else if ("ascending".equals(referenceName)) {
                    asc = true;
                } else {
                    continue;
                }
                PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
                String column = qualifier != null ? resolveColumnName(qualifier, ctx) : null;
                if (column == null) {
                    continue;
                }
                result.add(new OrderByItem(ctx.aliasedColumn(column), asc));
            } else {
                String column = resolveColumnName(arg, ctx);
                if (column != null) {
                    result.add(new OrderByItem(ctx.aliasedColumn(column), true));
                }
            }
        }
        return result;
    }

    /** update DSL：set(col) → 挂起列（equalTo(v) 完成）。 */
    private static void parseSet(PsiMethodCallExpression segment, MdsCtx ctx) {
        PsiExpression[] args = segment.getArgumentList().getExpressions();
        ctx.pendingSetColumn = args.length > 0 ? resolveColumnName(args[0], ctx) : null;
    }

    private static void completeSet(PsiMethodCallExpression segment, MdsCtx ctx) {
        String column = ctx.pendingSetColumn;
        if (column == null) {
            return;
        }
        PsiExpression[] args = segment.getArgumentList().getExpressions();
        if (args.length == 0) {
            return;
        }
        SqlValue value = ExpressionEvaluator.evaluate(args[0]);
        ctx.sets.add(new SetClause(ctx.aliasedColumn(column), value));
        ctx.pendingSetColumn = null;
    }

    // ------------------------------------------------------------------
    // SqlColumn / SqlTable 常量解析（声明处初始化，无生成代码）
    // ------------------------------------------------------------------

    /**
     * 实参 → 列名：
     * <ol>
     *  <li>SqlColumn 字段引用（`UserDynamicSqlSupport.userId` / static import 的 `userId`）→
     *      字段初始化 `column("user_id", ...)` / `user.userId`（转发引用）递归解析；</li>
     *  <li>解析失败 → 引用名 snake 兜底（`userId` → user_id），不出错误列名。</li>
     * </ol>
     */
    private static String resolveColumnName(PsiExpression expr, MdsCtx ctx) {
        if (!(expr instanceof PsiReferenceExpression fieldRef)) {
            return null;
        }
        if (fieldRef.resolve() instanceof PsiField field) {
            String resolved = resolveColumnField(field, 0);
            if (resolved != null) {
                return resolved;
            }
        }
        String referenceName = fieldRef.getReferenceName();
        return referenceName != null ? NamingStrategy.INSTANCE.toSnakeCase(referenceName) : null;
    }

    /** SqlColumn 字段 → 列名（转发引用递归，depth 守卫防循环）。 */
    private static String resolveColumnField(PsiField field, int depth) {
        if (depth > 4) {
            return null;
        }
        PsiElement initializer = field.getInitializer();
        if (initializer == null) {
            return null;
        }
        // 形态 1：`column("user_id", JDBCType.X)` / `SqlTable.of(...)` 调用 → 首个字符串字面量
        if (initializer instanceof PsiMethodCallExpression call) {
            String referenceName = call.getMethodExpression().getReferenceName();
            if (referenceName != null && COLUMN_INITIALIZER_METHODS.contains(referenceName)) {
                PsiExpression[] args = call.getArgumentList().getExpressions();
                return firstStringLiteral(args.length > 0 ? args[0] : null);
            }
        }
        // 形态 2：`user.userId` 转发引用 → 递归目标字段；
        // 引用不可解析（qualifier 静态类型未声明该字段等 stub 场景）→ 按引用名兜底（user.id → id）
        if (initializer instanceof PsiReferenceExpression ref) {
            if (ref.resolve() instanceof PsiField target) {
                return resolveColumnField(target, depth + 1);
            }
            String referenceName = ref.getReferenceName();
            if (referenceName != null) {
                return NamingStrategy.INSTANCE.toSnakeCase(referenceName);
            }
        }
        return null;
    }

    /** 实参 → SqlTable 表名（字段/变量引用 → `SqlTable.of("t_user")` / `new UserTable()` super 串）。 */
    private static String resolveSqlTableName(PsiExpression expr) {
        // 直接形态：SqlTable.of("t_user")
        if (expr instanceof PsiMethodCallExpression call
                && "of".equals(call.getMethodExpression().getReferenceName())) {
            PsiExpression[] args = call.getArgumentList().getExpressions();
            return firstStringLiteral(args.length > 0 ? args[0] : null);
        }
        // 直接形态：new SqlTable("t_user") / new UserTable()（子类 super 串）
        if (expr instanceof PsiNewExpression newExpr) {
            return sqlTableNameOf(newExpr);
        }
        // 引用形态：字段/变量持有 SqlTable 实例（`UserDynamicSqlSupport.user`）
        if (!(expr instanceof PsiReferenceExpression ref)) {
            return null;
        }
        PsiElement resolved = ref.resolve();
        if (resolved instanceof PsiField field) {
            return sqlTableNameOfInitializer(field.getInitializer());
        }
        if (resolved instanceof PsiVariable variable) {
            return sqlTableNameOfInitializer(variable.getInitializer());
        }
        return null;
    }

    private static String sqlTableNameOfInitializer(PsiElement initializer) {
        if (initializer instanceof PsiNewExpression newExpr) {
            return sqlTableNameOf(newExpr);
        }
        if (initializer instanceof PsiMethodCallExpression call
                && "of".equals(call.getMethodExpression().getReferenceName())) {
            PsiExpression[] args = call.getArgumentList().getExpressions();
            return firstStringLiteral(args.length > 0 ? args[0] : null);
        }
        return null;
    }

    /** new 形态表名：构造串字面量优先；SqlTable 子类构造器 `super("t_user")` 串兜底。 */
    private static String sqlTableNameOf(PsiNewExpression newExpr) {
        PsiExpression[] newArgs = newExpr.getArgumentList() != null
                ? newExpr.getArgumentList().getExpressions()
                : new PsiExpression[0];
        String direct = firstStringLiteral(newArgs.length > 0 ? newArgs[0] : null);
        if (direct != null) {
            return direct;
        }
        PsiElement classReferenceResolved = newExpr.getClassReference() != null
                ? newExpr.getClassReference().resolve()
                : null;
        if (!(classReferenceResolved instanceof PsiClass clazz)) {
            return null;
        }
        PsiClass current = clazz;
        int depth = 0;
        while (current != null && depth++ < 4) {
            for (PsiMethod ctor : current.getConstructors()) {
                PsiCodeBlock body = ctor.getBody();
                if (body == null) {
                    continue;
                }
                // 形态 1：显式 super(...) 调用（若 PSI 建模为 PsiMethodCallExpression）
                PsiMethodCallExpression superCall = null;
                for (PsiMethodCallExpression candidate : PsiTreeUtil.collectElementsOfType(body,
                        PsiMethodCallExpression.class)) {
                    if ("super".equals(candidate.getMethodExpression().getReferenceName())) {
                        superCall = candidate;
                        break;
                    }
                }
                if (superCall != null) {
                    PsiExpression[] superArgs = superCall.getArgumentList().getExpressions();
                    String name = firstStringLiteral(superArgs.length > 0 ? superArgs[0] : null);
                    if (name != null) {
                        return name;
                    }
                }
                // 形态 2 兜底：构造器体内首个字符串字面量（super(...) 若非 PsiMethodCallExpression 形态，
                // 构造器仅含 super("t_user") 一句时字面量即表名）
                String firstString = null;
                for (PsiLiteralExpression literal : PsiTreeUtil.collectElementsOfType(body,
                        PsiLiteralExpression.class)) {
                    if (literal.getValue() instanceof String value) {
                        firstString = value;
                        break;
                    }
                }
                if (firstString != null && !firstString.isBlank()) {
                    return firstString;
                }
            }
            current = current.getSuperClass();
        }
        return null;
    }

    /** 表达式内首个字符串字面量（拼接式递归）。 */
    private static String firstStringLiteral(PsiExpression expr) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof PsiLiteralExpression literal) {
            return literal.getValue() instanceof String value ? value : null;
        }
        for (PsiElement child : expr.getChildren()) {
            if (child instanceof PsiExpression childExpr) {
                String result = firstStringLiteral(childExpr);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 链回溯（链上任意位置触发）
    // ------------------------------------------------------------------

    private static PsiMethodCallExpression findEntryCall(PsiMethodCallExpression from, int maxDepth) {
        PsiMethodCallExpression current = from;
        int depth = 0;
        while (depth++ < maxDepth) {
            if (isMdsCall(current)) {
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
     * entry → 链尾完整段列表（头=entry，尾=build/render 等终端）。
     * 回溯处理调用层 + **引用层**（entry.parent 是外层调用的 methodExpression）两层延续——
     * 漏掉引用层则 segments 恒为 [entry]，from/where/orderBy/limit 全丢。
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
        return ArgResolver.collectCallChain(outermost);
    }

    /** limit/offset 实参求值。 */
    private static SqlValue limitArg(PsiMethodCallExpression segment) {
        PsiExpression[] args = segment.getArgumentList().getExpressions();
        return args.length > 0 ? ExpressionEvaluator.evaluate(args[0]) : null;
    }

    /** 转换上下文（每次 convert 独立实例）。 */
    private static final class MdsCtx {
        String tableName;
        String tableAlias;
        final List<ColumnRef> selectColumns = new ArrayList<>();
        final List<Condition> conditions = new ArrayList<>();
        final List<OrderByItem> orderBy = new ArrayList<>();
        final List<SetClause> sets = new ArrayList<>();
        LimitClause limit;
        SqlValue offset;
        String pendingSetColumn;

        void addConditions(List<Condition> conditions) {
            this.conditions.addAll(conditions);
        }

        void addOrderBy(List<OrderByItem> items) {
            orderBy.addAll(items);
        }

        /** 别名存在时列引用补别名（对齐 MDS 渲染 `u.user_id`）。 */
        ColumnRef aliasedColumn(String column) {
            return tableAlias != null ? new ColumnRef(tableAlias, column) : new ColumnRef(column);
        }
    }
}
