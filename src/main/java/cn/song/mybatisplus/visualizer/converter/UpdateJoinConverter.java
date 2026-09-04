package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.SetClause;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.UpdateStatement;
import cn.song.mybatisplus.visualizer.model.WrapperArg;
import cn.song.mybatisplus.visualizer.model.WrapperCall;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.model.WrapperType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * UpdateJoinWrapper 转换器（T-M21-08，MPJ 官方联表更新，1.4.5+）。
 * `UPDATE user t LEFT JOIN address t1 ON (...) SET t.name = ?, t1.tel = ? WHERE (t.id = ?)`。
 *
 * 已知简化（KDoc 标注）：跨表 SET 的列归属静态不可判——方法引用列统一补主表别名近似；
 * setUpdateEntity/AndNull 的实体字段值运行时不可知 → 主表非主键全列 ? 占位近似。
 */
public final class UpdateJoinConverter implements WrapperConverter {
    /** 单例（Kotlin object 转换）。 */
    public static final UpdateJoinConverter INSTANCE = new UpdateJoinConverter();

    private static final String MAIN_ALIAS_DEFAULT = "t";
    private static final String SET = "set";
    private static final String SET_IF_EXISTS = "setIfExists";
    private static final String SET_SQL = "setSql";
    private static final String SET_APPLY = "setApply";
    private static final String SET_INCR = "setIncrBy";
    private static final String SET_DECR = "setDecrBy";
    private static final String SET_UPDATE_ENTITY = "setUpdateEntity";
    private static final String SET_UPDATE_ENTITY_AND_NULL = "setUpdateEntityAndNull";
    private static final String SET_ALIAS = "setAlias";
    private static final String LAST = "last";
    private static final Set<String> SET_METHODS = Set.of(
            SET, SET_IF_EXISTS, SET_SQL, SET_APPLY, SET_INCR, SET_DECR, SET_UPDATE_ENTITY, SET_UPDATE_ENTITY_AND_NULL);

    private UpdateJoinConverter() {
    }

    @Override
    public Set<WrapperType> getSupportedTypes() {
        return Set.of(WrapperType.UPDATE_JOIN);
    }

    @Override
    public SqlStatement convert(WrapperChain chain, TableInfo table) {
        String mainAlias = chain.getConstructorAlias();
        if (mainAlias == null) {
            WrapperCall setAlias = null;
            for (WrapperCall c : chain.getCalls()) {
                if (SET_ALIAS.equals(c.getMethodName())) { setAlias = c; break; }
            }
            if (setAlias != null) {
                SqlValue v = WrapperConverterKt.asValue(argAt(setAlias.getArgs(), 0));
                if (v instanceof SqlValue.Literal lit) mainAlias = lit.getUnquoted();
            }
        }
        if (mainAlias == null) mainAlias = MAIN_ALIAS_DEFAULT;

        List<SetClause> sets = new ArrayList<>();
        for (WrapperCall call : chain.getCalls()) {
            if (SET_METHODS.contains(call.getMethodName())) {
                sets.addAll(parseSet(call, table, mainAlias));
            }
        }

        List<WrapperCall> joinCalls = new ArrayList<>();
        for (WrapperCall call : chain.getCalls()) {
            if (MpjJoinSupport.JOIN_METHODS.contains(call.getMethodName())) joinCalls.add(call);
        }
        List<cn.song.mybatisplus.visualizer.model.JoinClause> joins = new ArrayList<>();
        for (WrapperCall call : joinCalls) {
            cn.song.mybatisplus.visualizer.model.JoinClause j = MpjJoinSupport.INSTANCE.parseJoin(call, mainAlias);
            if (j != null) joins.add(j);
        }

        List<WrapperCall> conditionCalls = new ArrayList<>();
        for (WrapperCall call : chain.getCalls()) {
            String m = call.getMethodName();
            if (!SET_METHODS.contains(m) && !MpjJoinSupport.JOIN_METHODS.contains(m)
                    && !SET_ALIAS.equals(m) && !LAST.equals(m)) {
                conditionCalls.add(call);
            }
        }
        List<cn.song.mybatisplus.visualizer.model.Condition> conditions = new QueryWrapperParser(table, mainAlias)
                .parse(new WrapperChain(chain.getWrapperType(), chain.getEntityClass(), conditionCalls, null, false, false, false, false, null))
                .getConditions();

        String rawTail = null;
        for (WrapperCall call : chain.getCalls()) {
            if (LAST.equals(call.getMethodName())) {
                SqlValue v = WrapperConverterKt.asValue(argAt(call.getArgs(), 0));
                if (v instanceof SqlValue.Literal lit && lit.getUnquoted() != null
                        && !lit.getUnquoted().toLowerCase().startsWith("limit")) {
                    rawTail = lit.getUnquoted();
                }
            }
        }

        return new UpdateStatement(table, sets, conditions, rawTail, mainAlias, joins);
    }

    /** 解析 set 类调用为赋值子句（T-M21-08）。返回空列表表示解析失败/无列。 */
    private List<SetClause> parseSet(WrapperCall call, TableInfo table, String mainAlias) {
        List<WrapperArg> args = call.getArgs();
        switch (call.getMethodName()) {
            case SET, SET_IF_EXISTS -> {
                int startIndex = (args.size() >= 3 && isFlagArgument(args.get(0))) ? 1 : 0;
                ColumnRef column = WrapperConverterKt.asColumn(argAt(args, startIndex));
                if (column == null) return List.of();
                SqlValue value = WrapperConverterKt.asValue(argAt(args, startIndex + 1));
                if (value == null) return List.of();
                return List.of(new SetClause(withMainAliasIfUnqualified(column, mainAlias), value, null));
            }
            case SET_SQL, SET_APPLY -> {
                int startIndex = (args.size() >= 2 && isFlagArgument(args.get(0))) ? 1 : 0;
                SqlValue rawV = WrapperConverterKt.asValue(argAt(args, startIndex));
                if (!(rawV instanceof SqlValue.Literal lit)) return List.of();
                String sql = lit.getUnquoted();
                List<WrapperArg> tail = args.size() > startIndex + 1 ? args.subList(startIndex + 1, args.size()) : java.util.List.of();
                for (int index = 0; index < tail.size(); index++) {
                    SqlValue v = WrapperConverterKt.asValue(tail.get(index));
                    if (v == null) continue;
                    sql = sql.replace("{" + index + "}", renderSetValue(v));
                }
                return List.of(new SetClause(null, null, sql));
            }
            case SET_INCR, SET_DECR -> {
                int startIndex = (args.size() >= 3 && isFlagArgument(args.get(0))) ? 1 : 0;
                ColumnRef column = WrapperConverterKt.asColumn(argAt(args, startIndex));
                if (column == null) return List.of();
                SqlValue value = WrapperConverterKt.asValue(argAt(args, startIndex + 1));
                if (value == null) return List.of();
                // T-M23.5：列名按方言引用（保留字列名反引号包裹，如 t.`table`）
                String qualified = withMainAliasIfUnqualified(column, mainAlias)
                        .qualifiedName(cn.song.mybatisplus.visualizer.psi.PsiDialect.currentDialect());
                String op = SET_INCR.equals(call.getMethodName()) ? "+" : "-";
                return List.of(new SetClause(null, null, qualified + " = " + qualified + " " + op + " " + renderSetValue(value)));
            }
            case SET_UPDATE_ENTITY, SET_UPDATE_ENTITY_AND_NULL -> {
                List<SetClause> result = new ArrayList<>();
                for (ColumnInfo col : table.getColumns()) {
                    if (col.isId()) continue;
                    result.add(new SetClause(
                            new ColumnRef(mainAlias, col.getColumnName(), null, null, null, null, null),
                            new SqlValue.Placeholder("?"), null));
                }
                return result;
            }
            default -> {
                return List.of();
            }
        }
    }

    /** 列归属近似：ColumnArg（方法引用）统一补主表别名；字符串列已含表别名限定时原样保留。 */
    private ColumnRef withMainAliasIfUnqualified(ColumnRef column, String mainAlias) {
        return column.getTableAlias() == null && !column.getColumnName().contains(".")
                ? WrapperConverterKt.withTableAlias(column, mainAlias) : column;
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

    /** setSql 绑定参数渲染：字面量原样（raw），运行时值渲染为 ?。 */
    private static String renderSetValue(SqlValue value) {
        if (value instanceof SqlValue.Literal lit) return lit.getRaw();
        if (value instanceof SqlValue.Placeholder) return "?";
        return "";
    }

    private static WrapperArg argAt(List<WrapperArg> args, int i) {
        return (i >= 0 && i < args.size()) ? args.get(i) : null;
    }
}
