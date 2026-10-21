package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.SetClause;
import cn.song.mybatisplus.visualizer.model.SqlFragment;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.UpdateStatement;
import cn.song.mybatisplus.visualizer.model.WrapperArg;
import cn.song.mybatisplus.visualizer.model.WrapperCall;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import com.intellij.openapi.project.Project;

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
    public SqlStatement convert(WrapperChain chain, TableInfo table, Project project) {
        // T-UpdateSetSqlConcat：解析失败的 SET 调用原文（UI 警示用，避免静默产出无 SET 的非法 SQL）
        List<String> unparsedSets = new ArrayList<>();
        List<SetClause> sets = new ArrayList<>();
        for (WrapperCall call : chain.getCalls()) {
            if (SET_METHODS.contains(call.getMethodName())) {
                SetClause sc = parseSet(call, unparsedSets);
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

        // last 非 limit 尾缀（U05）：`last("AND (last_total - n >= 0)")` 原样追加到语句末尾
        // T-UpdateSetSqlConcat：片段位取 asSqlFragment——`last("AND (last_total - " + n + " >= 0)")` 不再整段丢弃
        SqlFragment tailFragment = null;
        for (WrapperCall call : chain.getCalls()) {
            if (LAST.equals(call.getMethodName())) {
                SqlFragment f = WrapperConverterKt.asSqlFragment(argAt(call.getArgs(), 0));
                if (f != null && f.getText() != null && !f.getText().regionMatches(true, 0, "limit", 0, 5)) {
                    tailFragment = f;
                    break;
                }
            }
        }

        // joins 是 Kotlin data class 的非空默认参数（= emptyList()）；Java 侧必须显式传空集合，不能传 null
        return new UpdateStatement(table, sets, conditions,
                tailFragment != null ? tailFragment.getText() : null, null, List.of(),
                unparsedSets,
                tailFragment != null ? tailFragment.getParams() : List.of());
    }

    /**
     * 解析 set / setSql / setIncrBy / setDecrBy / setIfExists 调用为赋值子句（T-M15-04 起支持布尔条件前缀与 {i} 参数；
     * T-M21-09：setIfExists（MPJ 1.4.9+，「值存在才 set」的运行时语义）归一为 set 处理——静态渲染普通赋值）。
     *
     * @param unparsedSets 解析失败的调用原文收集器（T-UpdateSetSqlConcat：UI 警示用）；
     *                     布尔条件为 false 的主动跳过不计入（那是 MP 语义，不是解析失败）
     */
    private SetClause parseSet(WrapperCall call, List<String> unparsedSets) {
        List<WrapperArg> args = call.getArgs();
        switch (call.getMethodName()) {
            case SET, SET_IF_EXISTS -> {
                int startIndex = (args.size() >= 3 && isFlagArgument(args.get(0))) ? 1 : 0;
                // M38.2（语义对照矩阵实证）：MP 运行时语义 condition=false → 该赋值整体不拼接，
                // 原实现剥离布尔参后照常渲染（false 被当 true），静态渲染须同步忽略
                if (isFalseFlag(args, startIndex == 1)) return null;
                cn.song.mybatisplus.visualizer.model.ColumnRef column = WrapperConverterKt.asColumn(argAt(args, startIndex));
                SqlValue value = WrapperConverterKt.asValue(argAt(args, startIndex + 1));
                if (column == null || value == null) {
                    unparsedSets.add(setCallSummary(call));
                    return null;
                }
                return new SetClause(column, value, null);
            }
            case SET_SQL -> {
                int startIndex = (args.size() >= 2 && isFlagArgument(args.get(0))) ? 1 : 0;
                // T-UpdateSetSqlConcat：片段位取 asSqlFragment——`setSql("amount = amount * " + rate)`
                // 静态前缀保留、运行期值写 `?`（原实现只认完全静态字面量，整段 SET 被丢弃）
                SqlFragment fragment = WrapperConverterKt.asSqlFragment(argAt(args, startIndex));
                if (fragment == null) {
                    unparsedSets.add(setCallSummary(call));
                    return null;
                }
                // `{i}` 绑定参数替换（U04-③）+ 按最终文本顺序登记占位符来源
                List<SqlValue> bindings = new ArrayList<>();
                List<WrapperArg> tail = args.size() > startIndex + 1 ? args.subList(startIndex + 1, args.size()) : java.util.List.of();
                for (WrapperArg a : tail) {
                    bindings.add(WrapperConverterKt.asValue(a));
                }
                SqlFragment rendered = WrapperConverterKt.renderSqlFragment(fragment, bindings);
                return new SetClause(null, null, rendered.getText(), rendered.getParams());
            }
            case SET_INCR, SET_DECR -> {
                int startIndex = (args.size() >= 3 && isFlagArgument(args.get(0))) ? 1 : 0;
                cn.song.mybatisplus.visualizer.model.ColumnRef column = WrapperConverterKt.asColumn(argAt(args, startIndex));
                SqlValue value = WrapperConverterKt.asValue(argAt(args, startIndex + 1));
                if (column == null || value == null) {
                    unparsedSets.add(setCallSummary(call));
                    return null;
                }
                String op = SET_INCR.equals(call.getMethodName()) ? "+" : "-";
                // T-M23.5：列名按方言引用（setIncrBy/setDecrBy 解析期拼 rawSql）
                String qualified = column.getQualifiedName();
                return new SetClause(null, null,
                        qualified + " = " + qualified + " " + op + " " + renderSetValue(value),
                        // T-UpdateSetSqlConcat：rawSql 里的 `?` 也要登记来源（否则后续条件整体错位）
                        value instanceof SqlValue.Placeholder ph ? List.of(ph.getSource()) : List.of());
            }
            default -> {
                return null;
            }
        }
    }

    /** SET 调用原文摘要（警示文案用）：`setSql("amount = amount * " + rate)`。 */
    private static String setCallSummary(WrapperCall call) {
        StringBuilder sb = new StringBuilder();
        List<WrapperArg> args = call.getArgs();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            WrapperArg arg = args.get(i);
            if (arg instanceof WrapperArg.ColumnArg ca) {
                sb.append(ca.getColumn().getColumnName());
            } else if (arg instanceof WrapperArg.ValueArg va) {
                SqlValue v = va.getValue();
                if (v instanceof SqlValue.Literal lit) sb.append(lit.getUnquoted());
                else if (v instanceof SqlValue.Placeholder ph) sb.append(ph.getSource());
            } else {
                sb.append("…");
            }
        }
        return call.getMethodName() + "(" + sb + ")";
    }

    /** 首参是否为布尔条件前缀；列引用（ColumnArg）恒为列。 */
    private static boolean isFlagArgument(WrapperArg arg) {
        if (arg instanceof WrapperArg.ColumnArg) return false;
        SqlValue value = WrapperConverterKt.asValue(arg);
        // T-UpdateSetSqlConcat：带片段形态的运行期实参是 SQL 模板（`setSql("a = " + x, ...)`），
        // 不是布尔前缀——否则模板会被当 flag 吞掉，真正的模板位被错取成第二参
        if (value instanceof SqlValue.Placeholder && WrapperConverterKt.asSqlFragment(arg) != null) return false;
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
