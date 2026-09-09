package cn.song.mybatisplus.visualizer.converter;

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
 * LambdaUpdateWrapper 转换器（F-09）：`UPDATE 表 SET col = ? ... WHERE ...`。
 *
 * 处理 set/setSql/setIncrBy/setDecrBy/setIfExists、布尔条件前缀剥离、`{i}` 参数替换、
 * last 非 limit 尾缀透传；其余调用复用 [QueryWrapperParser]。
 */
public final class LambdaUpdateConverter implements WrapperConverter {
    /** 单例（Kotlin object 转换）。 */
    public static final LambdaUpdateConverter INSTANCE = new LambdaUpdateConverter();

    private LambdaUpdateConverter() {
    }

    private static final String SET = "set";
    private static final String SET_SQL = "setSql";
    private static final String SET_INCR = "setIncrBy";
    private static final String SET_DECR = "setDecrBy";
    private static final String SET_IF_EXISTS = "setIfExists";
    private static final String LAST = "last";
    private static final Set<String> SET_METHODS = Set.of(SET, SET_SQL, SET_INCR, SET_DECR, SET_IF_EXISTS);

    @Override
    public Set<WrapperType> getSupportedTypes() {
        return Set.of(WrapperType.LAMBDA_UPDATE);
    }

    @Override
    public SqlStatement convert(WrapperChain chain, TableInfo table) {
        List<SetClause> sets = new ArrayList<>();
        for (WrapperCall call : chain.getCalls()) {
            if (SET_METHODS.contains(call.getMethodName())) {
                SetClause sc = parseSet(call);
                if (sc != null) sets.add(sc);
            }
        }

        List<WrapperCall> conditionCalls = new ArrayList<>();
        for (WrapperCall call : chain.getCalls()) {
            if (!SET_METHODS.contains(call.getMethodName()) && !LAST.equals(call.getMethodName())) conditionCalls.add(call);
        }
        List<cn.song.mybatisplus.visualizer.model.Condition> conditions = new QueryWrapperParser(table)
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

        // joins 是 Kotlin data class 的非空默认参数（= emptyList()）；Java 侧必须显式传空集合，不能传 null
        return new UpdateStatement(table, sets, conditions, rawTail, null, List.of());
    }

    /** 解析 set 类调用为赋值子句（T-M15-04 起支持布尔条件前缀与 {i} 参数）。 */
    private SetClause parseSet(WrapperCall call) {
        List<WrapperArg> args = call.getArgs();
        switch (call.getMethodName()) {
            case SET, SET_IF_EXISTS -> {
                int startIndex = (args.size() >= 3 && isFlagArgument(args.get(0))) ? 1 : 0;
                // M38.2（语义对照矩阵实证）：MP 运行时语义 condition=false → 该赋值整体不拼接，
                // 原实现剥离布尔参后照常渲染（false 被当 true），静态渲染须同步忽略
                if (isFalseFlag(args, startIndex == 1)) return null;
                cn.song.mybatisplus.visualizer.model.ColumnRef column = WrapperConverterKt.asColumn(argAt(args, startIndex));
                if (column == null) return null;
                SqlValue value = WrapperConverterKt.asValue(argAt(args, startIndex + 1));
                if (value == null) return null;
                return new SetClause(column, value, null);
            }
            case SET_SQL -> {
                int startIndex = (args.size() >= 2 && isFlagArgument(args.get(0))) ? 1 : 0;
                SqlValue rawV = WrapperConverterKt.asValue(argAt(args, startIndex));
                if (!(rawV instanceof SqlValue.Literal lit)) return null;
                String sql = lit.getUnquoted();
                List<WrapperArg> tail = args.size() > startIndex + 1 ? args.subList(startIndex + 1, args.size()) : java.util.List.of();
                for (int index = 0; index < tail.size(); index++) {
                    SqlValue v = WrapperConverterKt.asValue(tail.get(index));
                    if (v == null) continue;
                    sql = sql.replace("{" + index + "}", renderSetValue(v));
                }
                return new SetClause(null, null, sql);
            }
            case SET_INCR, SET_DECR -> {
                int startIndex = (args.size() >= 3 && isFlagArgument(args.get(0))) ? 1 : 0;
                cn.song.mybatisplus.visualizer.model.ColumnRef column = WrapperConverterKt.asColumn(argAt(args, startIndex));
                if (column == null) return null;
                SqlValue value = WrapperConverterKt.asValue(argAt(args, startIndex + 1));
                if (value == null) return null;
                String op = SET_INCR.equals(call.getMethodName()) ? "+" : "-";
                String qualified = column.getQualifiedName();
                return new SetClause(null, null, qualified + " = " + qualified + " " + op + " " + renderSetValue(value));
            }
            default -> {
                return null;
            }
        }
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

    /** M38.2：布尔前缀是否为 false 字面量（MP 运行时语义——condition=false 整个子句不拼接）。 */
    private static boolean isFalseFlag(List<WrapperArg> args, boolean hasFlag) {
        if (!hasFlag) return false;
        SqlValue flag = WrapperConverterKt.asValue(args.get(0));
        return flag instanceof SqlValue.Literal lit && "false".equals(lit.getUnquoted());
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
