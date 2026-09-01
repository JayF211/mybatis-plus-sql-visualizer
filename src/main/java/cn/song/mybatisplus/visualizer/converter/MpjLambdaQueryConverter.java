package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.ColumnRef;
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
 * MPJLambdaQueryConverter（F-12/P17）：MPJLambdaQueryWrapper → SELECT。
 * 无联表，转换语义与 [LambdaQueryConverter] 一致（委托 [QueryWrapperParser]）。
 * T-M20-04 增补 selectIgnore（selectAll 全列展开后剔除列）。
 */
public final class MpjLambdaQueryConverter implements WrapperConverter {
    /** 单例（Kotlin object 转换）。 */
    public static final MpjLambdaQueryConverter INSTANCE = new MpjLambdaQueryConverter();

    private MpjLambdaQueryConverter() {
    }

    @Override
    public Set<WrapperType> getSupportedTypes() {
        return Set.of(WrapperType.MPJ_LAMBDA_QUERY);
    }

    @Override
    public SqlStatement convert(WrapperChain chain, TableInfo table) {
        List<WrapperCall> ignoreCalls = new ArrayList<>();
        for (WrapperCall call : chain.getCalls()) {
            if ("selectIgnore".equals(call.getMethodName())) ignoreCalls.add(call);
        }
        if (ignoreCalls.isEmpty()) return new QueryWrapperParser(table).parse(chain);

        List<WrapperCall> baseCalls = new ArrayList<>();
        for (WrapperCall call : chain.getCalls()) {
            if (!"selectIgnore".equals(call.getMethodName())) baseCalls.add(call);
        }
        SelectStatement base = new QueryWrapperParser(table).parse(new WrapperChain(chain.getWrapperType(), chain.getEntityClass(), baseCalls, null, false, false, false, false, null));
        if (!base.getSelectColumns().isEmpty() || table.getColumns().isEmpty()) return base;

        Set<String> ignored = new HashSet<>();
        for (WrapperCall call : ignoreCalls) {
            for (WrapperArg arg : call.getArgs()) {
                String col = ignoredColumnName(arg);
                if (col != null) ignored.add(col);
            }
        }
        List<ColumnRef> newCols = new ArrayList<>();
        for (String name : table.getAllColumnNames()) {
            if (!ignored.contains(name)) newCols.add(new ColumnRef(null, name, null, null, null, null, null));
        }
        return new SelectStatement(base.getTable(), newCols, base.getTableAlias(), base.getJoins(), base.getDistinct(),
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
        if (core.startsWith("t.")) core = core.substring(2);
        return core;
    }
}
