package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.FillClause;
import cn.song.mybatisplus.visualizer.model.JoinClause;
import cn.song.mybatisplus.visualizer.model.JoinType;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.UnionClause;
import cn.song.mybatisplus.visualizer.model.WrapperArg;
import cn.song.mybatisplus.visualizer.model.WrapperCall;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import cn.song.mybatisplus.visualizer.psi.EntityResolver;
import cn.song.mybatisplus.visualizer.sql.MySqlDialect;
import cn.song.mybatisplus.visualizer.sql.SqlGenerator;
import com.intellij.openapi.project.Project;

import kotlin.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MPJLambdaWrapper 转换器（F-11）：联表 SELECT（清单 P01~P20 的 MPJLambdaWrapper 形态）。
 *
 * 支持 selectAs/selectAll/distinct/selectMax/selectFunc、leftJoin/innerJoin/rightJoin（含 ON 多形态）、
 * selectCollection（子查询 JOIN）、from(子查询主表)、around、fill（MPJ 1.5.1+）等。
 * 其余条件 / 结构委托 [QueryWrapperParser]（主表别名前缀）。
 */
public final class MpjLambdaConverter implements WrapperConverter {

    /** MPJ 主表别名默认值（表别名构造未指定时）。 */
    private static final String MAIN_ALIAS_DEFAULT = "t";

    private static final String SELECT_AS = "selectAs";
    private static final String SELECT_AS_CLASS = "selectAsClass";
    private static final String SELECT_ALL = "selectAll";
    private static final String SELECT_SUB = "selectSub";
    private static final String SET_ALIAS = "setAlias";
    private static final String FILL_METHOD = "fill";
    private static final String FROM_METHOD = "from";
    private static final String AROUND_METHOD = "around";
    private static final String SELECT_COLLECTION = "selectCollection";
    private static final String SELECT_ASSOCIATION = "selectAssociation";
    private static final Set<String> NOOP_METHODS = Set.of("setTableName", "pageByMain");
    private static final Set<String> UNION_METHODS = Set.of("union", "unionAll");
    private static final Set<String> JOIN_METHODS = Set.of("leftJoin", "innerJoin", "rightJoin", "fullJoin", "join");

    /** 单例（Kotlin object 转换）。 */
    public static final MpjLambdaConverter INSTANCE = new MpjLambdaConverter();

    private MpjLambdaConverter() {
    }

    @Override
    public Set<WrapperType> getSupportedTypes() {
        return Set.of(WrapperType.MPJ_LAMBDA);
    }

    @Override
    public SqlStatement convert(WrapperChain chain, TableInfo table, Project project) {
        // 主表别名：构造别名 > setAlias("x") 方法 > 默认 t
        String mainAlias = chain.getConstructorAlias();
        if (mainAlias == null) mainAlias = findSetAlias(chain);
        if (mainAlias == null) mainAlias = MAIN_ALIAS_DEFAULT;

        List<WrapperCall> selectAsCalls = new ArrayList<>();
        List<WrapperCall> joinCalls = new ArrayList<>();
        List<WrapperCall> collectionCalls = new ArrayList<>();
        List<WrapperCall> selectAllCalls = new ArrayList<>();
        List<WrapperCall> fillCalls = new ArrayList<>();
        for (WrapperCall c : chain.getCalls()) {
            String mn = c.getMethodName();
            if (SELECT_AS.equals(mn) || SELECT_AS_CLASS.equals(mn) || SELECT_SUB.equals(mn)) selectAsCalls.add(c);
            else if (JOIN_METHODS.contains(mn)) joinCalls.add(c);
            else if (SELECT_COLLECTION.equals(mn)) {
                WrapperArg a0 = argAt(c.getArgs(), 0);
                if (!(a0 instanceof WrapperArg.ColumnArg) && !(a0 instanceof WrapperArg.AliasArg)) collectionCalls.add(c);
            } else if (SELECT_ALL.equals(mn)) selectAllCalls.add(c);
            else if (FILL_METHOD.equals(mn)) fillCalls.add(c);
        }

        // 其余调用委托 Query 解析（列自动带主表别名前缀）；已知无对应分派方法的调用剔除
        List<WrapperCall> restCalls = new ArrayList<>();
        for (WrapperCall c : chain.getCalls()) {
            String mn = c.getMethodName();
            if (SELECT_AS.equals(mn) || SELECT_AS_CLASS.equals(mn) || SELECT_SUB.equals(mn)
                    || JOIN_METHODS.contains(mn) || UNION_METHODS.contains(mn)
                    || SELECT_ALL.equals(mn) || SELECT_COLLECTION.equals(mn)
                    || SELECT_ASSOCIATION.equals(mn) || SET_ALIAS.equals(mn)
                    || FROM_METHOD.equals(mn) || AROUND_METHOD.equals(mn) || NOOP_METHODS.contains(mn)) {
                continue;
            }
            restCalls.add(c);
        }
        Map<String, TableInfo> aliasToTable = buildAliasToTable(joinCalls);
        Map<String, String> classToAlias = new HashMap<>();
        for (Map.Entry<String, TableInfo> e : aliasToTable.entrySet()) {
            classToAlias.put(e.getValue().getEntityClassName(), e.getKey());
        }

        // T-MpjColAlias：MPJ 联表列归属——lambda 列接收者是关联实体（如
        // select(StationInfoEntity::getStationName)）时，列按 join 解析出的关联别名归属
        // （st.station_name）而非主表别名。QueryWrapperParser 的 columnPrefix 无条件补主表
        // 别名（withTableAlias 仅空别名时补），不改解析器（单表/MPJ 共用语义不变），
        // 在委托前重写调用树 ColumnArg 的 tableAlias，覆盖 select/条件/orderBy 全部列入口。
        List<WrapperCall> attributedRest = new ArrayList<>();
        for (WrapperCall c : restCalls) {
            attributedRest.add(attributeJoinColumns(c, classToAlias, chain.getEntityClass()));
        }
        SelectStatement base = new QueryWrapperParser(table, mainAlias)
                .parse(new WrapperChain(chain.getWrapperType(), chain.getEntityClass(), attributedRest, null, false, false, false, false, null));

        List<ColumnRef> selectAsColumns = new ArrayList<>();
        for (WrapperCall call : selectAsCalls) {
            if (SELECT_AS_CLASS.equals(call.getMethodName())) {
                selectAsColumns.addAll(parseSelectAsClass(call, mainAlias));
            } else if (SELECT_SUB.equals(call.getMethodName())) {
                selectAsColumns.addAll(parseSelectSub(call));
            } else {
                ColumnRef col = parseSelectAs(call, mainAlias, classToAlias);
                if (col != null) selectAsColumns.add(col);
            }
        }
        List<ColumnRef> selectColumns;
        if (!selectAllCalls.isEmpty()) {
            selectColumns = new ArrayList<>();
            for (WrapperCall call : selectAllCalls) selectColumns.addAll(parseSelectAll(call, mainAlias, table));
            selectColumns.addAll(base.getSelectColumns());
            selectColumns.addAll(selectAsColumns);
        } else {
            selectColumns = new ArrayList<>(base.getSelectColumns());
            selectColumns.addAll(selectAsColumns);
        }

        List<String> collectionChildClasses = new ArrayList<>();
        for (WrapperCall call : collectionCalls) {
            String cls = collectionChildClassOf(call);
            if (cls != null) collectionChildClasses.add(cls);
        }
        List<JoinClause> joins = new ArrayList<>();
        for (WrapperCall call : chain.getCalls()) {
            String mn = call.getMethodName();
            if (JOIN_METHODS.contains(mn)) {
                boolean skip = false;
                for (String cls : collectionChildClasses) {
                    if (java.util.Objects.equals(joinEntityClassName(call), cls)) { skip = true; break; }
                }
                if (skip) continue;
                JoinClause join = parseJoin(call, mainAlias, project);
                if (join == null) continue;
                joins.add(join);
            } else if (SELECT_COLLECTION.equals(mn)) {
                JoinClause j = parseCollection(call, joinCalls, mainAlias, project);
                if (j != null) joins.add(j);
            }
        }

        List<UnionClause> unions = new ArrayList<>();
        for (WrapperCall call : chain.getCalls()) {
            if (UNION_METHODS.contains(call.getMethodName())) {
                UnionClause u = parseUnion(call);
                if (u != null) unions.add(u);
            }
        }

        Pair<String, String> fromSubQuery = null;
        for (WrapperCall call : chain.getCalls()) {
            if (FROM_METHOD.equals(call.getMethodName())) { fromSubQuery = parseFrom(call, table, mainAlias); break; }
        }

        Pair<String, String> around = null;
        for (WrapperCall call : chain.getCalls()) {
            if (AROUND_METHOD.equals(call.getMethodName())) { around = parseAround(call); break; }
        }

        List<FillClause> fillClauses = new ArrayList<>();
        for (WrapperCall call : fillCalls) {
            FillClause f = parseFill(call, aliasToTable);
            if (f != null) fillClauses.add(f);
        }
        List<FillClause> fillClausesOrNull = fillClauses.isEmpty() ? null : fillClauses;

        String rawAround = around != null ? around.getSecond() : null;
        String rawFirst;
        if (around != null && around.getFirst() != null) {
            rawFirst = base.getRawFirst() != null ? base.getRawFirst() + " " + around.getFirst() : around.getFirst();
        } else {
            rawFirst = base.getRawFirst();
        }

        return new SelectStatement(
                base.getTable(),
                selectColumns,
                mainAlias,
                joins,
                base.getDistinct(),
                base.getConditions(),
                base.getGroupBy(),
                base.getHaving(),
                base.getOrderBy(),
                base.getLimit(),
                rawFirst,
                rawAround,
                base.getComment(),
                unions,
                fromSubQuery,
                fillClausesOrNull
        );
    }

    /** 查找 setAlias("x") 调用首参的字面量 unquoted（无则返回 null）。 */
    private static String findSetAlias(WrapperChain chain) {
        for (WrapperCall c : chain.getCalls()) {
            if (SET_ALIAS.equals(c.getMethodName())) {
                SqlValue v = WrapperConverterKt.asValue(argAt(c.getArgs(), 0));
                if (v instanceof SqlValue.Literal lit) return lit.getUnquoted();
            }
        }
        return null;
    }

    /** union / unionAll → UNION (子查询)。 */
    private UnionClause parseUnion(WrapperCall call) {
        WrapperArg.EntityRefArg subTable = argAt(call.getArgs(), 0) instanceof WrapperArg.EntityRefArg e ? e : null;
        if (subTable == null) return null;
        WrapperArg.NestedArg nested = argAt(call.getArgs(), 1) instanceof WrapperArg.NestedArg n ? n : null;
        if (nested == null) return null;
        SelectStatement subStatement = new QueryWrapperParser(subTable.getTableInfo()).parse(nested.getChain());
        String keyword = "unionAll".equals(call.getMethodName()) ? "UNION ALL" : "UNION";
        return new UnionClause(keyword, SqlGenerator.INSTANCE.generate(subStatement, true, MySqlDialect.INSTANCE, null));
    }

    /** from(子查询主表) → 主表替换为子查询，返回 Pair(子查询 SQL, 外层别名)。 */
    private Pair<String, String> parseFrom(WrapperCall call, TableInfo table, String outerAlias) {
        WrapperArg.NestedArg nested = argAt(call.getArgs(), 0) instanceof WrapperArg.NestedArg n ? n : null;
        if (nested == null) return null;
        String subAlias = null;
        for (WrapperCall c : nested.getChain().getCalls()) {
            if (SET_ALIAS.equals(c.getMethodName())) {
                SqlValue v = WrapperConverterKt.asValue(argAt(c.getArgs(), 0));
                if (v instanceof SqlValue.Literal lit) { subAlias = lit.getUnquoted(); break; }
            }
        }
        if (subAlias == null) subAlias = "tb";
        SelectStatement subStatement = new QueryWrapperParser(table, subAlias).parse(nested.getChain());
        SelectStatement subStatementWithAlias = copyWithTableAlias(subStatement, subAlias);
        return new Pair<>(SqlGenerator.INSTANCE.generate(subStatementWithAlias, true, MySqlDialect.INSTANCE, null), outerAlias);
    }

    /** around 解析：双字符串字面量或模板形态。 */
    private Pair<String, String> parseAround(WrapperCall call) {
        SqlValue firstVal = WrapperConverterKt.asValue(argAt(call.getArgs(), 0));
        if (!(firstVal instanceof SqlValue.Literal lit)) return null;
        String first = lit.getUnquoted();
        if (!first.contains("{") && call.getArgs().size() == 2) {
            SqlValue secondVal = WrapperConverterKt.asValue(argAt(call.getArgs(), 1));
            if (secondVal instanceof SqlValue.Literal lit2) return new Pair<>(first, lit2.getUnquoted());
        }
        return new Pair<>(null, renderJoinSql(first, call.getArgs().size() > 1 ? call.getArgs().subList(1, call.getArgs().size()) : java.util.List.of()));
    }

    /** 解析 fill 调用 → FillClause（MPJ 1.5.1+）。 */
    private FillClause parseFill(WrapperCall call, Map<String, TableInfo> aliasToTable) {
        List<WrapperArg> args = call.getArgs();
        if (args.size() < 2) return null;
        WrapperArg.ColumnArg fieldColumnArg = argAt(args, 0) instanceof WrapperArg.ColumnArg ca ? ca : null;
        if (fieldColumnArg == null) return null;

        WrapperArg tagFieldArg = argAt(args, 1);
        TableInfo childTable;
        String childAlias;
        String tagField;
        if (tagFieldArg instanceof WrapperArg.ColumnArg ca) {
            String alias = ca.getColumn().getTableAlias();
            if (alias == null) return null;
            TableInfo ti = aliasToTable.get(alias);
            if (ti == null) return null;
            childTable = ti;
            childAlias = alias;
            tagField = ca.getColumn().getColumnName();
        } else if (tagFieldArg instanceof WrapperArg.ValueArg) {
            return null;
        } else {
            return null;
        }

        Integer limit = null;
        Boolean condition = null;
        SqlValue data = null;
        switch (args.size()) {
            case 2, 3 -> { /* 形态 1/2：无可选参数 */ }
            case 4 -> {
                WrapperArg arg3 = argAt(args, 3);
                SqlValue value3 = arg3 instanceof WrapperArg.ValueArg va ? va.getValue() : null;
                if (value3 instanceof SqlValue.Literal lit) {
                    Integer li = parseIntOrNull(lit.getUnquoted());
                    if (li != null) limit = li;
                    else if ("true".equals(lit.getUnquoted()) || "false".equals(lit.getUnquoted())) {
                        condition = parseBooleanStrictOrNull(lit.getUnquoted());
                    } else data = WrapperConverterKt.asValue(arg3);
                } else {
                    data = WrapperConverterKt.asValue(arg3);
                }
            }
            case 5 -> {
                SqlValue value3 = argAt(args, 3) instanceof WrapperArg.ValueArg va ? va.getValue() : null;
                if (value3 instanceof SqlValue.Literal lit && parseIntOrNull(lit.getUnquoted()) != null) {
                    limit = parseIntOrNull(lit.getUnquoted());
                }
                data = WrapperConverterKt.asValue(argAt(args, 4));
            }
            default -> { /* 6+ 形态：仅取前 5 参语义 */ }
        }

        if (Boolean.FALSE.equals(condition)) return null;

        List<ColumnRef> selectColumns = new ArrayList<>();
        for (ColumnInfo col : childTable.getColumns()) {
            selectColumns.add(new ColumnRef(childAlias, col.getColumnName(), null, null, null, null, null));
        }
        return new FillClause(childTable, childAlias, selectColumns, tagField, limit, condition, data);
    }

    /** 构建关联表别名 → TableInfo 映射。 */
    private Map<String, TableInfo> buildAliasToTable(List<WrapperCall> joinCalls) {
        Map<String, TableInfo> map = new LinkedHashMap<>();
        for (WrapperCall call : joinCalls) {
            WrapperArg.EntityRefArg tableInfoArg = argAt(call.getArgs(), 0) instanceof WrapperArg.EntityRefArg e ? e : null;
            if (tableInfoArg == null) continue;
            TableInfo tableInfo = tableInfoArg.getTableInfo();
            String alias = null;
            WrapperArg arg1 = argAt(call.getArgs(), 1);
            if (arg1 instanceof WrapperArg.ValueArg va) {
                SqlValue v = va.getValue();
                if (v instanceof SqlValue.Literal lit) {
                    String unquoted = lit.getUnquoted();
                    if (!unquoted.isEmpty()) alias = unquoted;
                }
            }
            if (alias == null) {
                alias = lowerFirst(tableInfo.getEntityClassName());
            }
            map.put(alias, tableInfo);
        }
        return map;
    }

    /**
     * T-MpjColAlias：委托 QueryWrapperParser 前重写调用树中的列别名归属——lambda 列接收者
     * 实体类命中 join 关联映射（classToAlias）且非主实体类时，把 ColumnArg 列的 tableAlias
     * 预置为 join 别名；解析器 columnPrefix（withTableAlias 仅空别名补前缀）保留既有归属，
     * select/条件/orderBy/聚合全部列入口一并修正。
     *
     * 根因场景：`select(StationInfoEntity::getStationName)` 原被无条件补主表别名 →
     * `t.station_name`（car_device_info 无此列，执行报 1054 Unknown column）。
     * NestedArg 递归处理（and/or 嵌套 lambda 体内的关联列）；字符串列（ValueArg）已含
     * 别名原样，不触碰。
     */
    private static WrapperCall attributeJoinColumns(
            WrapperCall call, Map<String, String> classToAlias, String mainEntityClass) {
        if (classToAlias.isEmpty()) return call;
        boolean changed = false;
        List<WrapperArg> newArgs = new ArrayList<>();
        for (WrapperArg arg : call.getArgs()) {
            if (arg instanceof WrapperArg.ColumnArg columnArg) {
                ColumnRef attributed = attributeColumn(columnArg.getColumn(), classToAlias, mainEntityClass);
                if (attributed != columnArg.getColumn()) {
                    changed = true;
                    newArgs.add(new WrapperArg.ColumnArg(attributed));
                } else {
                    newArgs.add(arg);
                }
            } else if (arg instanceof WrapperArg.NestedArg nestedArg) {
                WrapperChain oldChain = nestedArg.getChain();
                List<WrapperCall> newCalls = new ArrayList<>();
                for (WrapperCall inner : oldChain.getCalls()) {
                    newCalls.add(attributeJoinColumns(inner, classToAlias, mainEntityClass));
                }
                if (!newCalls.equals(oldChain.getCalls())) {
                    changed = true;
                    newArgs.add(new WrapperArg.NestedArg(new WrapperChain(
                            oldChain.getWrapperType(), oldChain.getEntityClass(), newCalls,
                            oldChain.getConstructorAlias(), oldChain.isDeleteConsumed(), oldChain.isCountConsumed(),
                            oldChain.isExistsConsumed(), oldChain.isUpdateEntityConsumed(), oldChain.getPageLimit())));
                } else {
                    newArgs.add(arg);
                }
            } else if (arg instanceof WrapperArg.FuncArg funcArg) {
                List<ColumnRef> cols = new ArrayList<>();
                boolean colsChanged = false;
                for (ColumnRef col : funcArg.getColumns()) {
                    ColumnRef attributed = attributeColumn(col, classToAlias, mainEntityClass);
                    if (attributed != col) colsChanged = true;
                    cols.add(attributed);
                }
                if (colsChanged) {
                    changed = true;
                    newArgs.add(funcArg.copy(cols, funcArg.getValues()));
                } else {
                    newArgs.add(arg);
                }
            } else {
                newArgs.add(arg);
            }
        }
        return changed ? call.copy(call.getMethodName(), newArgs) : call;
    }

    /** 单列归属：仅无别名 lambda 列、接收者实体类 ≠ 主实体类、且命中 join 映射时改写（self-join 主列保主表）。 */
    private static ColumnRef attributeColumn(ColumnRef column, Map<String, String> classToAlias, String mainEntityClass) {
        String receiver = column.getEntityClass();
        if (receiver == null) return column;
        if (column.getTableAlias() != null) return column;
        if (receiver.equals(mainEntityClass)) return column;
        String alias = classToAlias.get(receiver);
        if (alias == null) return column;
        return column.copy(alias, column.getColumnName(), column.getAlias(), column.getFunction(),
                column.getRawExpr(), column.getEntityClass(), column.getType());
    }

    /** 解析 selectSub → 子查询列。 */
    private List<ColumnRef> parseSelectSub(WrapperCall call) {
        WrapperArg.EntityRefArg subTableArg = argAt(call.getArgs(), 0) instanceof WrapperArg.EntityRefArg e ? e : null;
        if (subTableArg == null) return List.of();
        WrapperArg.NestedArg nested = argAt(call.getArgs(), 1) instanceof WrapperArg.NestedArg n ? n : null;
        if (nested == null) return List.of();
        String alias = null;
        WrapperArg arg2 = argAt(call.getArgs(), 2);
        if (arg2 instanceof WrapperArg.AliasArg aa) alias = aa.getPropertyName();
        else if (arg2 instanceof WrapperArg.ColumnArg ca) alias = ca.getColumn().getColumnName();
        else if (arg2 instanceof WrapperArg.ValueArg va) {
            SqlValue v = va.getValue();
            if (v instanceof SqlValue.Literal lit) alias = lit.getUnquoted();
        }
        if (alias == null) return List.of();
        SelectStatement subStatement = new QueryWrapperParser(subTableArg.getTableInfo()).parse(nested.getChain());
        String subSql = SqlGenerator.INSTANCE.generate(subStatement, true, MySqlDialect.INSTANCE, null);
        return List.of(new ColumnRef(null, subSql, alias, null, "(" + subSql + ")", null, null));
    }

    /** 解析 selectAs → 带别名的列。 */
    private ColumnRef parseSelectAs(WrapperCall call, String mainAlias, Map<String, String> classToAlias) {
        WrapperArg arg0 = argAt(call.getArgs(), 0);
        ColumnRef column = null;
        if (arg0 instanceof WrapperArg.ColumnArg ca) column = ca.getColumn();
        else if (arg0 instanceof WrapperArg.ValueArg va) {
            SqlValue v = va.getValue();
            if (v instanceof SqlValue.Literal lit) column = new ColumnRef(null, lit.getUnquoted(), null, null, null, null, null);
        }
        if (column == null) return null;

        String colType = null;
        String alias = null;
        WrapperArg arg1 = argAt(call.getArgs(), 1);
        if (arg1 instanceof WrapperArg.AliasArg aa) { colType = aa.getReturnType(); alias = aa.getPropertyName(); }
        else if (arg1 instanceof WrapperArg.ColumnArg ca) alias = ca.getColumn().getColumnName();
        else if (arg1 instanceof WrapperArg.ValueArg va) {
            SqlValue v = va.getValue();
            if (v instanceof SqlValue.Literal lit) alias = lit.getUnquoted();
        }
        if (alias == null) return null;

        String tableAlias;
        if (arg0 instanceof WrapperArg.ValueArg && column.getColumnName().contains(".")) {
            tableAlias = null;
        } else if (column.getEntityClass() != null && classToAlias.containsKey(column.getEntityClass())) {
            tableAlias = classToAlias.get(column.getEntityClass());
        } else {
            tableAlias = mainAlias;
        }
        return new ColumnRef(tableAlias, column.getColumnName(), alias, null, null, null, colType);
    }

    /** 解析 join → 联表子句（支持 6 种 ON 形态）。 */
    private JoinClause parseJoin(WrapperCall call, String mainAlias, Project project) {
        JoinType type;
        switch (call.getMethodName()) {
            case "leftJoin" -> type = JoinType.LEFT;
            case "innerJoin" -> type = JoinType.INNER;
            case "rightJoin" -> type = JoinType.RIGHT;
            case "fullJoin" -> type = JoinType.FULL;
            default -> type = JoinType.INNER;
        }
        List<WrapperArg> args = call.getArgs();

        // 6. 自定义关键字 join：join("FULL JOIN", condition, "address t1 on ..."[, args])
        if ("join".equals(call.getMethodName())) {
            String keyword = literalUnquoted(argAt(args, 0));
            if (keyword == null) return null;
            String joinSql = literalUnquoted(argAt(args, 2));
            if (joinSql == null) return null;
            return new JoinClause(type, "", "", List.of(),
                    renderJoinSql(joinSql, args.size() > 3 ? args.subList(3, args.size()) : java.util.List.of()), keyword, null, null);
        }

        // 5. 字符串 SQL join：leftJoin("address t1 on ...", args)
        WrapperArg first = argAt(args, 0);
        if (first instanceof WrapperArg.ValueArg va) {
            SqlValue value = va.getValue();
            if (value instanceof SqlValue.Literal lit) {
                String text = lit.getUnquoted();
                if (!text.trim().endsWith(".class")
                        && (text.contains(" ") || text.contains("(") || text.contains("{"))) {
                    int startIndex = (args.size() >= 2 && isFlagArgument(args.get(0))) ? 1 : 0;
                    String sql = literalUnquoted(argAt(args, startIndex));
                    if (sql == null) return null;
                    return new JoinClause(type, "", "", List.of(),
                            renderJoinSql(sql, args.size() > startIndex + 1 ? args.subList(startIndex + 1, args.size()) : java.util.List.of()), null, null, null);
                }
            }
        }

        // 主表推断
        TableInfo tableInfo;
        String className;
        WrapperArg arg0 = argAt(args, 0);
        if (arg0 instanceof WrapperArg.EntityRefArg e) {
            tableInfo = e.getTableInfo();
            className = e.getTableInfo().getEntityClassName();
        } else if (arg0 instanceof WrapperArg.ValueArg va) {
            SqlValue v = va.getValue();
            if (v instanceof SqlValue.Literal lit) {
                className = lit.getRaw().replaceAll("\\.class$", "").trim();
                tableInfo = null;
            } else {
                return null;
            }
        } else {
            return null;
        }
        if (className.isEmpty()) return null;

        // T-FS-04：类名兜底推断显式传调用方 project——多项目同开且项目级表配置不同时，
        // 表名修饰不再受「首个打开项目」影响（project 由 SPI 入参透传，null 时行为不变）
        String tableName = tableInfo != null ? tableInfo.getTableName() : EntityResolver.inferDecoratedTableName(className, project);
        String alias = lowerFirst(className);
        List<Condition> onConditions = new ArrayList<>();
        String logicDelete = tableInfo != null ? tableInfo.getLogicDeleteColumn() : null;

        List<WrapperArg> rest = args.size() > 1 ? args.subList(1, args.size()) : java.util.List.of();
        WrapperArg firstRest = argAt(rest, 0);

        if (args.size() == 5 && args.get(1) instanceof WrapperArg.ValueArg && args.get(2) instanceof WrapperArg.ColumnArg
                && args.get(3) instanceof WrapperArg.ValueArg && args.get(4) instanceof WrapperArg.ColumnArg) {
            // 5 参右别名：leftJoin(Class, "别名", 左列, "右别名", 右列)
            String a = literalUnquoted(args.get(1));
            if (a != null) alias = a;
            onConditions.add(new Condition.ColumnComparison(
                    withAlias(args.get(2), alias),
                    withAlias(args.get(4), literalOr(mainAlias, argAt(args, 3)))
            , null));
        } else if (args.size() == 4 && args.get(1) instanceof WrapperArg.ColumnArg && args.get(2) instanceof WrapperArg.ValueArg
                && args.get(3) instanceof WrapperArg.ColumnArg) {
            // 4 参右别名
            onConditions.add(new Condition.ColumnComparison(
                    withAlias(args.get(1), alias),
                    withAlias(args.get(3), literalOr(mainAlias, argAt(args, 2)))
            , null));
        } else if (args.size() == 4 && args.get(1) instanceof WrapperArg.ColumnArg && args.get(2) instanceof WrapperArg.ColumnArg
                && args.get(3) instanceof WrapperArg.NestedArg) {
            // ext 函数
            onConditions.add(new Condition.ColumnComparison(withAlias(args.get(1), alias), withAlias(args.get(2), mainAlias), null));
            onConditions.addAll(parseOnLambda(((WrapperArg.NestedArg) args.get(3)).getChain(), alias, mainAlias));
        } else if (args.size() == 5 && args.get(1) instanceof WrapperArg.ValueArg && args.get(2) instanceof WrapperArg.ColumnArg
                && args.get(3) instanceof WrapperArg.ColumnArg && args.get(4) instanceof WrapperArg.NestedArg) {
            // 别名 + ext
            String a = literalUnquoted(args.get(1));
            if (a != null) alias = a;
            onConditions.add(new Condition.ColumnComparison(withAlias(args.get(2), alias), withAlias(args.get(3), mainAlias), null));
            onConditions.addAll(parseOnLambda(((WrapperArg.NestedArg) args.get(4)).getChain(), alias, mainAlias));
        } else if (args.size() == 4 && args.get(1) instanceof WrapperArg.NestedArg && args.get(2) instanceof WrapperArg.ColumnArg
                && args.get(3) instanceof WrapperArg.ColumnArg) {
            // 子表 MConsumer 子查询
            TableInfo childTable = tableInfo;
            if (childTable == null) return null;
            WrapperChain subChain = ((WrapperArg.NestedArg) args.get(1)).getChain();
            String subAlias = null;
            for (WrapperCall c : subChain.getCalls()) {
                if (SET_ALIAS.equals(c.getMethodName())) {
                    SqlValue v = WrapperConverterKt.asValue(argAt(c.getArgs(), 0));
                    if (v instanceof SqlValue.Literal lit) { subAlias = lit.getUnquoted(); break; }
                }
            }
            if (subAlias == null) subAlias = lowerFirst(className);
            SelectStatement subStatement = new QueryWrapperParser(childTable, subAlias).parse(subChain);
            SelectStatement subStatementWithAlias = copyWithTableAlias(subStatement, subAlias);
            tableName = "(" + SqlGenerator.INSTANCE.generate(subStatementWithAlias, true, MySqlDialect.INSTANCE, null) + ")";
            alias = subAlias;
            logicDelete = null;
            onConditions.add(new Condition.ColumnComparison(withAlias(args.get(2), subAlias), withAlias(args.get(3), mainAlias), null));
        } else if (firstRest instanceof WrapperArg.NestedArg na) {
            // 4. lambda ON
            onConditions.addAll(parseOnLambda(na.getChain(), alias, mainAlias));
        } else {
            int index = 0;
            if (firstRest instanceof WrapperArg.ValueArg va) {
                SqlValue v = va.getValue();
                String aliasText = v instanceof SqlValue.Literal lit ? lit.getUnquoted() : null;
                if (aliasText != null && !aliasText.isEmpty()) {
                    alias = aliasText;
                    index = 1;
                }
            }
            WrapperArg aliasNext = argAt(rest, index);
            if (aliasNext instanceof WrapperArg.NestedArg na) {
                onConditions.addAll(parseOnLambda(na.getChain(), alias, mainAlias));
            } else if (aliasNext instanceof WrapperArg.OnConditionArg oca) {
                List<WrapperArg> tailArgs = rest.size() > index ? rest.subList(index, rest.size()) : java.util.Collections.emptyList();
                for (WrapperArg restArg : tailArgs) {
                    if (restArg instanceof WrapperArg.OnConditionArg oc) {
                        onConditions.add(new Condition.ColumnComparison(
                                WrapperConverterKt.withTableAlias(oc.getLeft(), mainAlias),
                                WrapperConverterKt.withTableAlias(oc.getRight(), alias)
                        , null));
                    }
                }
            } else {
                WrapperArg.ColumnArg left = argAt(rest, index) instanceof WrapperArg.ColumnArg ca ? ca : null;
                WrapperArg.ColumnArg right = argAt(rest, index + 1) instanceof WrapperArg.ColumnArg ca ? ca : null;
                if (left != null && right != null) {
                    onConditions.add(new Condition.ColumnComparison(
                            WrapperConverterKt.withTableAlias(left.getColumn(), alias),
                            WrapperConverterKt.withTableAlias(right.getColumn(), mainAlias)
                    , null));
                }
            }
        }

        return new JoinClause(type, tableName, alias, onConditions, null, null, logicDelete, tableInfo);
    }

    /** 字符串 join 的 {i} 绑定参数替换。 */
    private String renderJoinSql(String template, List<WrapperArg> values) {
        String sql = template;
        int idx = 0;
        for (WrapperArg value : values) {
            SqlValue v = WrapperConverterKt.asValue(value);
            if (v == null) continue;
            sql = sql.replace("{" + idx + "}", renderJoinValue(v));
            idx++;
        }
        return sql;
    }

    /** join 绑定参数渲染：字面量原样，运行时值渲染为 `?`。 */
    private static String renderJoinValue(SqlValue value) {
        if (value instanceof SqlValue.Literal lit) return lit.getRaw();
        if (value instanceof SqlValue.Placeholder) return "?";
        return "";
    }

    /** 首参是否为布尔条件前缀。 */
    private static boolean isFlagArgument(WrapperArg arg) {
        if (arg instanceof WrapperArg.ColumnArg) return false;
        SqlValue value = WrapperConverterKt.asValue(arg);
        if (value instanceof SqlValue.Literal lit) {
            return "true".equals(lit.getUnquoted()) || "false".equals(lit.getUnquoted());
        }
        return value instanceof SqlValue.Placeholder;
    }

    /** 解析 lambda ON（leftJoin(Class, on -> on.eq(...))）。 */
    private List<Condition> parseOnLambda(WrapperChain chain, String alias, String mainAlias) {
        List<Condition> result = new ArrayList<>();
        for (WrapperCall call : chain.getCalls()) {
            switch (call.getMethodName()) {
                case "eq", "ne" -> {
                    WrapperArg left = argAt(call.getArgs(), 0);
                    ColumnRef leftCol = null;
                    if (left instanceof WrapperArg.ColumnArg ca) {
                        leftCol = WrapperConverterKt.withTableAlias(ca.getColumn(), alias);
                    } else if (left instanceof WrapperArg.ValueArg va) {
                        SqlValue v = va.getValue();
                        if (v instanceof SqlValue.Literal lit) {
                            leftCol = new ColumnRef(null, lit.getUnquoted(), null, null, null, null, null);
                        }
                    }
                    if (leftCol == null) continue;
                    WrapperArg right = argAt(call.getArgs(), 1);
                    if (right instanceof WrapperArg.ColumnArg ca) {
                        result.add(new Condition.ColumnComparison(leftCol,
                                WrapperConverterKt.withTableAlias(ca.getColumn(), mainAlias), null));
                    } else if (right instanceof WrapperArg.ValueArg va) {
                        SqlValue lit = va.getValue();
                        if (lit instanceof SqlValue.Literal l && l.getUnquoted().contains(".")) {
                            result.add(new Condition.ColumnComparison(leftCol,
                                    new ColumnRef(null, l.getUnquoted(), null, null, null, null, null), null));
                        } else {
                            SqlValue value = WrapperConverterKt.asValue(right);
                            if (value == null) continue;
                            result.add(new Condition.Simple(leftCol, "ne".equals(call.getMethodName()) ? "<>" : "=", value, null));
                        }
                    } else {
                        SqlValue value = right != null ? WrapperConverterKt.asValue(right) : null;
                        if (value == null) continue;
                        result.add(new Condition.Simple(leftCol, "ne".equals(call.getMethodName()) ? "<>" : "=", value, null));
                    }
                }
                case "apply" -> {
                    SqlValue raw = WrapperConverterKt.asValue(argAt(call.getArgs(), 0));
                    if (raw instanceof SqlValue.Literal lit) result.add(new Condition.Raw(lit.getUnquoted(), null));
                }
                default -> { /* ignore */ }
            }
        }
        return result;
    }

    /** 解析 selectAsClass（取两表交集字段 → 主表列 AS 属性名）。 */
    private List<ColumnRef> parseSelectAsClass(WrapperCall call, String mainAlias) {
        WrapperArg.EntityRefArg source = argAt(call.getArgs(), 0) instanceof WrapperArg.EntityRefArg e ? e : null;
        if (source == null) return List.of();
        WrapperArg.EntityRefArg target = argAt(call.getArgs(), 1) instanceof WrapperArg.EntityRefArg e ? e : null;
        if (target == null) return List.of();
        Set<String> targetFields = new HashSet<>();
        for (ColumnInfo col : target.getTableInfo().getColumns()) targetFields.add(col.getFieldName());
        List<ColumnRef> result = new ArrayList<>();
        for (ColumnInfo col : source.getTableInfo().getColumns()) {
            if (targetFields.contains(col.getFieldName())) {
                result.add(new ColumnRef(mainAlias, col.getColumnName(), col.getFieldName(), null, null, null, null));
            }
        }
        return result;
    }

    /** 解析 selectAll（全列 / 前缀 / 排除）。 */
    private List<ColumnRef> parseSelectAll(WrapperCall call, String mainAlias, TableInfo table) {
        List<WrapperArg> args = call.getArgs();
        WrapperArg second = argAt(args, 1);
        String prefix = null;
        SqlValue sv = WrapperConverterKt.asValue(second);
        if (sv instanceof SqlValue.Literal lit) {
            String unquoted = lit.getUnquoted();
            if (!unquoted.trim().endsWith(".class")) prefix = unquoted;
        }
        int excludeStart = prefix != null ? 2 : 1;
        Set<String> exclude = new HashSet<>();
        // Kotlin drop(n) 语义：入参不足 n 个时返回空表（subList 会越界）
        List<WrapperArg> rest = args.size() > excludeStart ? args.size() > excludeStart ? args.subList(excludeStart, args.size()) : java.util.List.of() : List.of();
        for (WrapperArg a : rest) {
            ColumnRef col = WrapperConverterKt.asColumn(a);
            if (col != null) exclude.add(col.getColumnName());
        }
        List<ColumnRef> result = new ArrayList<>();
        for (String cn : table.getAllColumnNames()) {
            if (!exclude.contains(cn)) {
                result.add(new ColumnRef(mainAlias, prefix != null ? prefix + cn : cn, null, null, null, null, null));
            }
        }
        return result;
    }

    /** 实参是否为类名字面量。 */
    private static boolean isClassNameArg(WrapperArg arg) {
        SqlValue v = WrapperConverterKt.asValue(arg);
        if (!(v instanceof SqlValue.Literal lit)) return false;
        String trimmed = lit.getUnquoted().trim();
        return trimmed.endsWith(".class") || (!trimmed.isEmpty() && Character.isUpperCase(trimmed.charAt(0))
                && !trimmed.contains(" ") && !trimmed.contains("("));
    }

    /** selectCollection 调用是否为 prefix 形态（第 1 参为字符串 prefix，第 2 参为类参）。 */
    private static boolean isPrefixForm(WrapperCall call) {
        WrapperArg arg1 = argAt(call.getArgs(), 1);
        if (arg1 instanceof WrapperArg.EntityRefArg) return true;
        return isClassNameArg(arg1);
    }

    /** selectCollection 调用对应的子表类名。 */
    private static String collectionChildClassOf(WrapperCall call) {
        WrapperArg childArg = isPrefixForm(call) ? argAt(call.getArgs(), 1) : argAt(call.getArgs(), 0);
        if (childArg instanceof WrapperArg.EntityRefArg e) return e.getTableInfo().getEntityClassName();
        SqlValue v = WrapperConverterKt.asValue(childArg);
        return v instanceof SqlValue.Literal lit ? lit.getUnquoted() : null;
    }

    /** 从 selectCollection 调用提取子表 TableInfo（EntityRefArg 优先）。 */
    private static TableInfo collectionTableInfo(WrapperCall call) {
        WrapperArg.EntityRefArg entityRef = isPrefixForm(call)
                ? (argAt(call.getArgs(), 1) instanceof WrapperArg.EntityRefArg e ? e : null)
                : (argAt(call.getArgs(), 0) instanceof WrapperArg.EntityRefArg e ? e : null);
        return entityRef != null ? entityRef.getTableInfo() : null;
    }

    /** 从 join 调用提取关联实体类名（用于去重比较）。 */
    private static String joinEntityClassName(WrapperCall call) {
        WrapperArg first = argAt(call.getArgs(), 0);
        if (first instanceof WrapperArg.EntityRefArg e) return e.getTableInfo().getEntityClassName();
        SqlValue v = WrapperConverterKt.asValue(first);
        return v instanceof SqlValue.Literal lit ? lit.getUnquoted() : null;
    }

    /** 解析 selectCollection → LEFT JOIN 子查询。 */
    private JoinClause parseCollection(WrapperCall call, List<WrapperCall> joinCalls, String mainAlias, Project project) {
        boolean prefixForm = isPrefixForm(call);
        String childClass;
        String alias;
        int mapIndex;
        if (prefixForm) {
            WrapperArg classArg = argAt(call.getArgs(), 1);
            if (classArg instanceof WrapperArg.EntityRefArg e) childClass = e.getTableInfo().getEntityClassName();
            else {
                SqlValue v = WrapperConverterKt.asValue(classArg);
                childClass = v instanceof SqlValue.Literal lit ? lit.getUnquoted() : null;
            }
            if (childClass == null) return null;
            SqlValue a = WrapperConverterKt.asValue(argAt(call.getArgs(), 0));
            alias = a instanceof SqlValue.Literal lit ? lit.getUnquoted() : null;
            if (alias == null) return null;
            mapIndex = 3;
        } else {
            WrapperArg classArg = argAt(call.getArgs(), 0);
            if (classArg instanceof WrapperArg.EntityRefArg e) childClass = e.getTableInfo().getEntityClassName();
            else {
                SqlValue v = WrapperConverterKt.asValue(classArg);
                childClass = v instanceof SqlValue.Literal lit ? lit.getUnquoted() : null;
            }
            if (childClass == null) return null;
            alias = lowerFirst(childClass);
            mapIndex = 2;
        }
        TableInfo childInfo = collectionTableInfo(call);
        // T-1800（Bug1 修复）：优先从 EntityRefArg 取真实 @TableName，不再仅靠 snake_case 推断。
        // 例：AddressDO(@TableName("user_address")) → EntityRefArg 直接得 user_address，避免 address_d_o。
        // T-FS-04：类名兜底推断显式传调用方 project（实体类 PSI 不可解析时按该项目读项目级表配置）
        String childTable = childInfo != null ? childInfo.getTableName() : EntityResolver.inferDecoratedTableName(childClass, project);

        String subSelect = renderCollectionSubSelect(call, childTable, alias, mapIndex, project);

        List<Condition> onConditions = new ArrayList<>();
        for (WrapperCall joinCall : joinCalls) {
            WrapperArg first = argAt(joinCall.getArgs(), 0);
            boolean match;
            if (first instanceof WrapperArg.EntityRefArg e) {
                match = e.getTableInfo().getEntityClassName().equals(childClass);
            } else {
                SqlValue v = WrapperConverterKt.asValue(first);
                match = v instanceof SqlValue.Literal lit && lit.getUnquoted().equals(childClass);
            }
            if (!match) continue;
            JoinClause joinClause = parseJoin(joinCall, mainAlias, project);
            if (joinClause == null) continue;
            String joinAlias = joinClause.getAlias();
            for (Condition cond : joinClause.getOnConditions()) {
                if (cond instanceof Condition.ColumnComparison cc) {
                    ColumnRef newLeft = (cc.getLeft().getTableAlias() != null && cc.getLeft().getTableAlias().equals(joinAlias))
                            ? copyColumnWithAlias(cc.getLeft(), alias) : cc.getLeft();
                    ColumnRef newRight = (cc.getRight().getTableAlias() != null && cc.getRight().getTableAlias().equals(joinAlias))
                            ? copyColumnWithAlias(cc.getRight(), alias) : cc.getRight();
                    onConditions.add(new Condition.ColumnComparison(newRight, newLeft, null));
                } else {
                    onConditions.add(cond);
                }
            }
        }

        return new JoinClause(JoinType.LEFT, "(" + subSelect + ")", alias, onConditions, null, null, null, null);
    }

    /**
     * 渲染 selectCollection 的子查询文本（递归支持嵌套 collection）。
     *
     * @param project 调用方项目（T-FS-04）：孙表类名兜底推断按该项目读项目级表配置；null = 未提供
     */
    private String renderCollectionSubSelect(WrapperCall call, String childTable, String alias, int mapIndex, Project project) {
        List<String> subColumns = new ArrayList<>();
        List<String> nestedJoins = new ArrayList<>();
        WrapperArg.NestedArg mapping = argAt(call.getArgs(), mapIndex) instanceof WrapperArg.NestedArg n ? n : null;
        if (mapping != null) {
            for (WrapperCall c : mapping.getChain().getCalls()) {
                switch (c.getMethodName()) {
                    case "id", "result" -> {
                        ColumnRef col = WrapperConverterKt.asColumn(argAt(c.getArgs(), 0));
                        if (col != null) subColumns.add(col.getColumnName());
                    }
                    case "collection" -> {
                        SqlValue gv = WrapperConverterKt.asValue(argAt(c.getArgs(), 0));
                        if (!(gv instanceof SqlValue.Literal lit)) continue;
                        String grandChild = lit.getUnquoted();
                        // T-M19.4：孙表推断走统一入口（策略 + 修饰链，全局语义）
                        // T-FS-04：类名兜底推断显式传调用方 project（同 parseJoin）
                        String grandTable = EntityResolver.inferDecoratedTableName(grandChild, project);
                        String grandAlias = lowerFirst(grandChild);
                        String grandSub = renderCollectionSubSelect(c, grandTable, grandAlias, 2, project);
                        nestedJoins.add("LEFT JOIN (" + grandSub + ") " + grandAlias
                                + " ON " + grandAlias + "." + alias + "_id = " + alias + ".id");
                    }
                    default -> { /* ignore */ }
                }
            }
        }
        if (subColumns.isEmpty()) subColumns.add("*");
        String select = "SELECT " + String.join(", ", subColumns) + " FROM " + childTable + " " + alias;
        return nestedJoins.isEmpty() ? select : select + " " + String.join(" ", nestedJoins);
    }

    /** 取列实参（ColumnArg）补别名的便捷方法。 */
    private static ColumnRef withAlias(WrapperArg arg, String alias) {
        ColumnRef column = ((WrapperArg.ColumnArg) arg).getColumn();
        return WrapperConverterKt.withTableAlias(column, alias);
    }

    /** 类名字面量取第二参字面量，否则 fallback。 */
    private static String literalOr(String fallback, WrapperArg arg) {
        String s = literalUnquoted(arg);
        return s != null ? s : fallback;
    }

    /** 列引用（替换为新别名，其余字段保持不变）。 */
    private static ColumnRef copyColumnWithAlias(ColumnRef col, String alias) {
        return new ColumnRef(alias, col.getColumnName(), col.getAlias(), col.getFunction(),
                col.getRawExpr(), col.getEntityClass(), col.getType());
    }

    /** SelectStatement 复制（仅替换主表别名，其余沿用）。 */
    private static SelectStatement copyWithTableAlias(SelectStatement stmt, String tableAlias) {
        return new SelectStatement(
                stmt.getTable(), stmt.getSelectColumns(), tableAlias, stmt.getJoins(),
                stmt.getDistinct(), stmt.getConditions(), stmt.getGroupBy(), stmt.getHaving(),
                stmt.getOrderBy(), stmt.getLimit(), stmt.getRawFirst(), stmt.getRawAround(),
                stmt.getComment(), stmt.getUnions(), stmt.getFromSubQuery(), stmt.getFillClauses());
    }

    /** 安全取列表元素（越界返回 null）。 */
    private static WrapperArg argAt(List<WrapperArg> args, int i) {
        return (i >= 0 && i < args.size()) ? args.get(i) : null;
    }

    /** 列名实参字面量 unquoted（非字面量返回 null）。 */
    private static String literalUnquoted(WrapperArg arg) {
        SqlValue v = WrapperConverterKt.asValue(arg);
        if (v instanceof SqlValue.Literal lit) return lit.getUnquoted();
        return null;
    }

    /** 类名首字母小写。 */
    private static String lowerFirst(String name) {
        if (name.isEmpty()) return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    /** Integer 解析（失败返回 null）。 */
    private static Integer parseIntOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 严格布尔解析（仅 "true"/"false" 返回对应值，否则 null）。 */
    private static Boolean parseBooleanStrictOrNull(String s) {
        if ("true".equals(s)) return Boolean.TRUE;
        if ("false".equals(s)) return Boolean.FALSE;
        return null;
    }
}
