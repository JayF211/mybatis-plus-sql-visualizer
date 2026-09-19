package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.JoinClause;
import cn.song.mybatisplus.visualizer.model.JoinType;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.WrapperArg;
import cn.song.mybatisplus.visualizer.model.WrapperCall;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.psi.EntityResolver;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * MPJ join 解析共享辅助（T-M21-08）：UpdateJoin/DeleteJoinConverter 复用。
 *
 * 实现联表更新/删除需要的核心 ON 形态（与 MpjLambdaConverter 既有分派对齐）：
 *  1. 三参 leftJoin(Class, 关联表字段, 主表字段) → ON 别名.左 = 主别名.右
 *  2. 四参带别名 leftJoin(Class, "rel", 关联表字段, 主表字段)
 *  3. on(col1, col2) 两列比较（left 主表列、right 关联表列）
 *  4. lambda ON leftJoin(Class, on -> on.eq(...))（含四参别名 + lambda ON）
 *  5. 字符串 SQL join leftJoin("address t1 on ..."[, args]) 原样透传（{i} 参数替换）
 *  6. 自定义关键字 join("FULL JOIN", condition, "sql"[, args])
 */
public final class MpjJoinSupport {

    /** 单例（Kotlin internal object 转换）。 */
    public static final MpjJoinSupport INSTANCE = new MpjJoinSupport();

    private MpjJoinSupport() {
    }

    /** 联表方法全集（leftJoin/innerJoin/rightJoin/fullJoin + join 自定义关键字）。 */
    public static final Set<String> JOIN_METHODS = Set.of("leftJoin", "innerJoin", "rightJoin", "fullJoin", "join");

    private static WrapperArg argAt(List<WrapperArg> args, int i) {
        return (i >= 0 && i < args.size()) ? args.get(i) : null;
    }

    /**
     * 解析 join 调用 → 联表子句（核心形态）。
     *
     * @param call      join 调用（psi 层已解析实参）
     * @param mainAlias 主表别名（ON 右列归属）
     * @param project   调用方项目（T-FS-04）：类名兜底推断（关联实体类 PSI 不可解析时）
     *   按该项目读项目级表配置；null = 未提供（沿用「首个打开项目」既有语义）
     */
    public JoinClause parseJoin(WrapperCall call, String mainAlias, Project project) {
        JoinType type;
        switch (call.getMethodName()) {
            case "leftJoin" -> type = JoinType.LEFT;
            case "innerJoin" -> type = JoinType.INNER;
            case "rightJoin" -> type = JoinType.RIGHT;
            case "fullJoin" -> type = JoinType.FULL;
            default -> type = JoinType.INNER;
        }
        List<WrapperArg> args = call.getArgs();

        if ("join".equals(call.getMethodName())) {
            SqlValue kw = WrapperConverterKt.asValue(argAt(args, 0));
            String keyword = kw instanceof SqlValue.Literal lit ? lit.getUnquoted() : null;
            if (keyword == null) return null;
            SqlValue js = WrapperConverterKt.asValue(argAt(args, 2));
            String joinSql = js instanceof SqlValue.Literal lit ? lit.getUnquoted() : null;
            if (joinSql == null) return null;
            return new JoinClause(type, "", "", List.of(), renderJoinSql(joinSql, args.size() > 3 ? args.subList(3, args.size()) : java.util.List.of()), keyword, null, null);
        }

        WrapperArg first = argAt(args, 0);
        if (first instanceof WrapperArg.ValueArg va) {
            SqlValue litValue = va.getValue();
            if (litValue instanceof SqlValue.Literal literal) {
                String text = literal.getUnquoted();
                if (!text.trim().endsWith(".class") && (text.contains(" ") || text.contains("(") || text.contains("{"))) {
                    int startIndex = (args.size() >= 2 && isFlagArgument(args.get(0))) ? 1 : 0;
                    SqlValue s = WrapperConverterKt.asValue(argAt(args, startIndex));
                    String sql = s instanceof SqlValue.Literal lit ? lit.getUnquoted() : null;
                    if (sql == null) return null;
                    return new JoinClause(type, "", "", List.of(), renderJoinSql(sql, args.size() > startIndex + 1 ? args.subList(startIndex + 1, args.size()) : java.util.List.of()), null, null, null);
                }
            }
        }

        TableInfo tableInfo;
        String className;
        WrapperArg arg0 = argAt(args, 0);
        if (arg0 instanceof WrapperArg.EntityRefArg e) {
            tableInfo = e.getTableInfo();
            className = e.getTableInfo().getEntityClassName();
        } else if (arg0 instanceof WrapperArg.ValueArg va) {
            SqlValue v = va.getValue();
            if (!(v instanceof SqlValue.Literal lit)) return null;
            className = lit.getRaw().replace(".class", "").trim();
            tableInfo = null;
        } else {
            return null;
        }
        if (className.isEmpty()) return null;

        // T-M19.4：子表推断走统一入口（策略 + 修饰链，全局语义）
        // T-FS-04：类名兜底推断显式传调用方 project——多项目同开且项目级表配置不同时，
        // 表名修饰不再受「首个打开项目」影响（project 由 SPI 入参透传，null 时行为不变）
        String tableName = tableInfo != null ? tableInfo.getTableName() : EntityResolver.inferDecoratedTableName(className, project);
        String alias = lowerFirst(className);
        List<Condition> onConditions = new ArrayList<>();

        List<WrapperArg> rest = args.size() > 1 ? args.subList(1, args.size()) : java.util.List.of();
        WrapperArg firstRest = argAt(rest, 0);
        if (firstRest instanceof WrapperArg.NestedArg na) {
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
            } else if (aliasNext instanceof WrapperArg.OnConditionArg oc) {
                List<WrapperArg> tailArgs = rest.size() > index ? rest.subList(index, rest.size()) : java.util.Collections.emptyList();
                for (WrapperArg a : tailArgs) {
                    if (a instanceof WrapperArg.OnConditionArg o) {
                        onConditions.add(new Condition.ColumnComparison(
                                WrapperConverterKt.withTableAlias(o.getLeft(), mainAlias),
                                WrapperConverterKt.withTableAlias(o.getRight(), alias), null));
                    }
                }
            } else {
                WrapperArg.ColumnArg left = argAt(rest, index) instanceof WrapperArg.ColumnArg ca ? ca : null;
                WrapperArg.ColumnArg right = argAt(rest, index + 1) instanceof WrapperArg.ColumnArg ca ? ca : null;
                if (left != null && right != null) {
                    onConditions.add(new Condition.ColumnComparison(
                            WrapperConverterKt.withTableAlias(left.getColumn(), alias),
                            WrapperConverterKt.withTableAlias(right.getColumn(), mainAlias), null));
                }
            }
        }
        return new JoinClause(type, tableName, alias, onConditions, null, null, null, null);
    }

    /** lambda ON 条件解析（P13 形态）：`leftJoin(Class, on -> on.eq(...))`。 */
    private List<Condition> parseOnLambda(WrapperChain chain, String alias, String mainAlias) {
        List<Condition> result = new ArrayList<>();
        for (WrapperCall call : chain.getCalls()) {
            switch (call.getMethodName()) {
                case "eq", "ne" -> {
                    WrapperArg left = argAt(call.getArgs(), 0);
                    if (left == null) continue;
                    // Java 17 兼容：switch 模式匹配为预览特性，改用 instanceof 模式（Java 16+ 标准）
                    ColumnRef leftCol;
                    if (left instanceof WrapperArg.ColumnArg ca) {
                        leftCol = WrapperConverterKt.withTableAlias(ca.getColumn(), alias);
                    } else if (left instanceof WrapperArg.ValueArg va) {
                        SqlValue v = va.getValue();
                        leftCol = (v instanceof SqlValue.Literal lit)
                                ? new ColumnRef(null, lit.getUnquoted(), null, null, null, null, null)
                                : null;
                    } else {
                        leftCol = null;
                    }
                    if (leftCol == null) continue;
                    WrapperArg right = argAt(call.getArgs(), 1);
                    String op = "ne".equals(call.getMethodName()) ? "<>" : "=";
                    if (right instanceof WrapperArg.ColumnArg ca) {
                        result.add(new Condition.ColumnComparison(leftCol, WrapperConverterKt.withTableAlias(ca.getColumn(), mainAlias), null));
                    } else if (right instanceof WrapperArg.ValueArg va) {
                        SqlValue literal = va.getValue();
                        if (literal instanceof SqlValue.Literal lit && lit.getUnquoted() != null && lit.getUnquoted().contains(".")) {
                            result.add(new Condition.ColumnComparison(leftCol, new ColumnRef(null, lit.getUnquoted(), null, null, null, null, null), null));
                        } else {
                            SqlValue value = WrapperConverterKt.asValue(right);
                            if (value == null) continue;
                            result.add(new Condition.Simple(leftCol, op, value, null));
                        }
                    } else {
                        SqlValue value = right != null ? WrapperConverterKt.asValue(right) : null;
                        if (value == null) continue;
                        result.add(new Condition.Simple(leftCol, op, value, null));
                    }
                }
                case "apply" -> {
                    SqlValue raw = WrapperConverterKt.asValue(argAt(call.getArgs(), 0));
                    if (raw instanceof SqlValue.Literal lit && lit.getUnquoted() != null) {
                        result.add(new Condition.Raw(lit.getUnquoted(), null));
                    }
                }
                default -> { /* ignore */ }
            }
        }
        return result;
    }

    /** 字符串 join 的 {i} 绑定参数替换。 */
    private String renderJoinSql(String template, List<WrapperArg> values) {
        String sql = template;
        for (int index = 0; index < values.size(); index++) {
            SqlValue v = WrapperConverterKt.asValue(values.get(index));
            if (v == null) continue;
            sql = sql.replace("{" + index + "}", renderJoinValue(v));
        }
        return sql;
    }

    /** join 绑定参数渲染：字面量原样（raw），运行时值渲染为 ?。 */
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

    private static String lowerFirst(String name) {
        if (name.isEmpty()) return name;
        return name.substring(0, 1).toLowerCase() + name.substring(1);
    }
}
