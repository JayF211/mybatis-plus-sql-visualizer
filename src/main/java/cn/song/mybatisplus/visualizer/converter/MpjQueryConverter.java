package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.JoinClause;
import cn.song.mybatisplus.visualizer.model.JoinType;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.WrapperArg;
import cn.song.mybatisplus.visualizer.model.WrapperCall;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.model.WrapperType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MPJQueryWrapper 转换器（F-12）：字符串字段版联表 SELECT。
 * 列与 join 均为字符串形式，无需实体解析；字符串列已含表别名（如 t.id），不再补前缀。
 */
public final class MpjQueryConverter implements WrapperConverter {
    /** 单例（Kotlin object 转换）。 */
    public static final MpjQueryConverter INSTANCE = new MpjQueryConverter();

    /** MPJ 主表别名惯例（字符串写法中由调用方显式使用，如 t.id）。 */
    private static final String MAIN_ALIAS = "t";
    private static final Set<String> JOIN_METHODS = Set.of("leftJoin", "innerJoin", "rightJoin");

    private MpjQueryConverter() {
    }

    @Override
    public Set<WrapperType> getSupportedTypes() {
        return Set.of(WrapperType.MPJ_QUERY);
    }

    @Override
    public SqlStatement convert(WrapperChain chain, TableInfo table) {
        List<WrapperCall> selectCalls = new ArrayList<>();
        List<WrapperCall> joinCalls = new ArrayList<>();
        List<WrapperCall> ignoreCalls = new ArrayList<>();
        List<WrapperCall> restCalls = new ArrayList<>();
        for (WrapperCall call : chain.getCalls()) {
            String m = call.getMethodName();
            if ("select".equals(m)) selectCalls.add(call);
            else if (JOIN_METHODS.contains(m)) joinCalls.add(call);
            else if ("selectIgnore".equals(m)) ignoreCalls.add(call);
            else restCalls.add(call);
        }

        SelectStatement base = new QueryWrapperParser(table, MAIN_ALIAS)
                .parse(new WrapperChain(chain.getWrapperType(), chain.getEntityClass(), restCalls, null, false, false, false, false, null));

        List<ColumnRef> selectColumns = new ArrayList<>(base.getSelectColumns());
        for (WrapperCall call : selectCalls) {
            for (WrapperArg arg : call.getArgs()) {
                SqlValue v = WrapperConverterKt.asValue(arg);
                if (v instanceof SqlValue.Literal lit) {
                    selectColumns.add(new ColumnRef(null, lit.getUnquoted(), null, null, null, null, null));
                }
            }
        }

        if (selectColumns.isEmpty() && !table.getColumns().isEmpty()) {
            Set<String> ignored = new HashSet<>();
            for (WrapperCall call : ignoreCalls) {
                for (WrapperArg arg : call.getArgs()) {
                    String col = ignoredColumnName(arg);
                    if (col != null) ignored.add(col);
                }
            }
            selectColumns = new ArrayList<>();
            for (String name : table.getAllColumnNames()) {
                if (!ignored.contains(name)) {
                    selectColumns.add(new ColumnRef(MAIN_ALIAS, name, null, null, null, null, null));
                }
            }
        }

        List<JoinClause> joins = new ArrayList<>();
        for (WrapperCall call : joinCalls) {
            JoinClause j = parseJoin(call);
            if (j != null) joins.add(j);
        }

        return new SelectStatement(base.getTable(), selectColumns, MAIN_ALIAS, joins, base.getDistinct(),
                base.getConditions(), base.getGroupBy(), base.getHaving(), base.getOrderBy(), base.getLimit(),
                base.getRawFirst(), base.getRawAround(), base.getComment(), base.getUnions(), base.getFromSubQuery(), base.getFillClauses());
    }

    /** selectIgnore 实参 → 列名（归一化）。 */
    private String ignoredColumnName(WrapperArg arg) {
        if (arg instanceof WrapperArg.ColumnArg ca) return normalizeIgnored(ca.getColumn().getColumnName());
        if (arg instanceof WrapperArg.ValueArg va) {
            SqlValue v = va.getValue();
            if (v instanceof SqlValue.Literal lit) return normalizeIgnored(lit.getUnquoted());
        }
        return null;
    }

    /** 归一化忽略列名：去空白、AS 别名后缀、主表别名前缀。 */
    private String normalizeIgnored(String name) {
        String core = name.trim();
        int as1 = core.indexOf(" AS ");
        int as2 = core.indexOf(" as ");
        int cut = -1;
        if (as1 >= 0) cut = as1;
        if (as2 >= 0 && (cut < 0 || as2 < cut)) cut = as2;
        if (cut >= 0) core = core.substring(0, cut);
        core = core.trim();
        if (core.startsWith(MAIN_ALIAS + ".")) core = core.substring((MAIN_ALIAS + ".").length());
        return core;
    }

    /** 解析 join("table_name alias", "on sql") → 联表子句。 */
    private JoinClause parseJoin(WrapperCall call) {
        JoinType type;
        switch (call.getMethodName()) {
            case "leftJoin" -> type = JoinType.LEFT;
            case "innerJoin" -> type = JoinType.INNER;
            default -> type = JoinType.RIGHT;
        }
        SqlValue tv = WrapperConverterKt.asValue(argAt(call.getArgs(), 0));
        String tableText = tv instanceof SqlValue.Literal lit ? lit.getUnquoted() : null;
        if (tableText == null) return null;
        tableText = tableText.trim();
        String[] parts = tableText.split("\\s+");
        String tableName = parts[0];
        String alias = parts.length > 1 ? parts[1] : tableName;

        SqlValue ov = WrapperConverterKt.asValue(argAt(call.getArgs(), 1));
        String onText = ov instanceof SqlValue.Literal lit ? lit.getUnquoted() : null;
        List<Condition> onConditions = onText != null ? List.of(new Condition.Raw(onText, null)) : List.of();
        return new JoinClause(type, tableName, alias, onConditions, null, null, null, null);
    }

    private static WrapperArg argAt(List<WrapperArg> args, int i) {
        return (i >= 0 && i < args.size()) ? args.get(i) : null;
    }
}
