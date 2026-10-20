package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.sql.Dialect;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.update.Update;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Pattern;

/**
 * R42 DML 影响面预演（V60 已证伪入批）。
 *
 * <p>UPDATE/DELETE 执行前不知道会碰多少行，是开发者最贵的恐惧之一。本对象把 WHERE 谓词改写为
 * 等价 {@code COUNT(*)} <b>只读试算</b>语句（复用执行链的真机通道，不做自带连接），并给出目标表与
 * 行数上限提示；由 IDE 侧动作/确认链决定何时提交（本对象只产出语句，不执行）。</p>
 *
 * <h2>V60 语义边界（改写等价性判据，全部落在此处）</h2>
 * <ul>
 * <li><b>单表</b> UPDATE/DELETE：{@code DELETE FROM t WHERE p} ≡ {@code SELECT COUNT(*) FROM t WHERE p}。</li>
 * <li><b>JOIN / 多表 / USING</b>：{@link Plan.NotApplicable}——多表下一条目标行可能被 JOIN 匹配多次，
 *    COUNT 会<b>多计</b>，等价性不成立（宁可不报，不给错数）。</li>
 * <li><b>子查询（单表）</b>：等价性成立（子查询是筛选条件的一部分，两侧同构），放行。</li>
 * <li><b>无 WHERE</b>：{@link Plan.NotApplicable}——全表 {@code COUNT(*)} 即全表扫描，正是 V60 要求做
 *    成本防护的形态；影响面无界已在执行确认弹窗（{@link UnboundedWriteDetector}）如实警示，
 *    此处不再制造一次可能拖垮库的扫描。</li>
 * <li><b>DELETE ... LIMIT n</b>：仅当方言支持该尾段（{@link Dialect#getSupportsDeleteLimit()}）时，实际影响
 *    上界 = min(匹配行数, n)——此时试算也<b>收窄到 n 行</b>（{@code SELECT COUNT(*) FROM (SELECT 1 …
 *    LIMIT n) …}），天然获得成本上限；方言不支持该尾段时，渲染产物本就不含 LIMIT（见 R52
 *    能力缺口提示），故按完整匹配行数试算（不设上限，这才是真实影响面）。</li>
 * <li><b>INSERT/MERGE/DDL/多语句</b>：不适用（本对象只覆盖 UPDATE/DELETE 影响面）。</li>
 * </ul>
 *
 * <h2>只读红线联动（V60 口径）</h2>
 * 产出的试算语句是单条 SELECT，<b>必须通过 MCP 只读护栏</b> {@code McpSelectGuard}
 * ——由测试钉住（{@code DmlImpactEstimatorTest}）；即 SQL 含 LIMIT/子查询等形态时也不会被护栏改判为写。
 *
 * <p>纯逻辑层：只依赖 JSqlParser 5.3 + Dialect（无 IDE 类型），普通 JUnit 可直接覆盖。</p>
 */
public final class DmlImpactEstimator {

    private DmlImpactEstimator() {
    }

    /** 试算计划。 */
    public sealed interface Plan permits Applicable, NotApplicable {
    }

    /** 可试算：产出只读 COUNT 语句。 */
    public record Applicable(Probe probe) implements Plan {
    }

    /** 不适用（改写不等价/成本不可控），reasonKey 为 bundle 文案键。 */
    public record NotApplicable(String reasonKey) implements Plan {
    }

    /**
     * 试算语句与提示信息。
     *
     * @param probeSql    只读 COUNT 试算语句（单条 SELECT）
     * @param targetTable 影响表（原始写法，含库/模式限定）
     * @param rowCap      行数上限（DELETE LIMIT n 且方言支持时 = n；否则 null）
     * @param whereText   WHERE 谓词原文（截断；无 WHERE 不会走到这里）
     */
    public record Probe(String probeSql, String targetTable, @Nullable Long rowCap, String whereText) {
    }

    /** WHERE 原文在提示里的长度上限。 */
    private static final int WHERE_LIMIT = 200;

    /** 子查询谓词原文长度上限（超过即判成本不可控）。 */
    private static final int SUBQUERY_WHERE_LIMIT = 400;

    private static final Pattern SUBQUERY_HINT = Pattern.compile("\\bSELECT\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 生成影响面试算计划。
     *
     * @param sql     渲染产物 SQL（单条 UPDATE/DELETE）
     * @param dialect 当前生效方言（决定 DELETE LIMIT 是否收窄试算）
     */
    @NotNull
    public static Plan plan(@NotNull String sql, @NotNull Dialect dialect) {
        List<Statement> statements;
        try {
            statements = SqlAstParser.INSTANCE.parseStatements(sql);
        } catch (Exception e) {
            return new NotApplicable("dml.impact.reason.unparseable");
        }
        if (statements == null || statements.size() != 1) {
            return new NotApplicable("dml.impact.reason.multiStatement");
        }
        Statement statement = statements.get(0);
        if (statement instanceof Delete delete) {
            if (delete.getTable() == null) {
                return new NotApplicable("dml.impact.reason.unparseable");
            }
            Long limitRows = null;
            Limit limit = delete.getLimit();
            if (limit != null && limit.getRowCount() instanceof LongValue longValue) {
                limitRows = longValue.getValue();
            }
            int tablesSize = delete.getTables() != null ? delete.getTables().size() : 0;
            return planFor(delete.getTable().toString(), delete.getWhere(),
                    delete.getJoins() != null && !delete.getJoins().isEmpty(), tablesSize > 1, limitRows, dialect);
        }
        if (statement instanceof Update update) {
            if (update.getTable() == null) {
                return new NotApplicable("dml.impact.reason.unparseable");
            }
            // JSqlParser 5.3 把 `UPDATE a JOIN b ON …` 的 JOIN 放在 startJoins（实测：joins=null、
            // startJoins=[JOIN b ON …]）——两处都要看，否则多表 UPDATE 会被误判为单表（COUNT 多计）。
            boolean joins = (update.getJoins() != null && !update.getJoins().isEmpty())
                    || (update.getStartJoins() != null && !update.getStartJoins().isEmpty());
            // Update 只有单一 getTable()（5.3 javap 实证：无 getTables()）——多表 UPDATE 由 joins 侧覆盖
            return planFor(update.getTable().toString(), update.getWhere(), joins, false, null, dialect);
        }
        return new NotApplicable("dml.impact.reason.notDml");
    }

    private static Plan planFor(String tableText, @Nullable Expression where, boolean joins, boolean multiTable,
                                @Nullable Long limitRows, Dialect dialect) {
        if (joins || multiTable) {
            return new NotApplicable("dml.impact.reason.multiTable");
        }
        if (where == null) {
            return new NotApplicable("dml.impact.reason.noWhere");
        }
        String fullWhereText = where.toString().replace('\n', ' ');
        if (containsSubqueryFailSafe(where) && fullWhereText.length() > SUBQUERY_WHERE_LIMIT) {
            // 超长子查询谓词：试算语句会非常长且成本不可控，诚实降级
            return new NotApplicable("dml.impact.reason.complexWhere");
        }
        String whereText = fullWhereText.length() > WHERE_LIMIT ? fullWhereText.substring(0, WHERE_LIMIT) : fullWhereText;
        Long cap = limitRows != null && dialect.getSupportsDeleteLimit() && limitRows > 0 ? limitRows : null;
        String probeSql = cap != null
                ? "SELECT COUNT(*) FROM (SELECT 1 FROM " + tableText + " WHERE " + fullWhereText
                  + " LIMIT " + cap + ") mpsv_impact_probe"
                : "SELECT COUNT(*) FROM " + tableText + " WHERE " + fullWhereText;
        return new Applicable(new Probe(probeSql, tableText, cap, whereText));
    }

    /** 谓词是否含子查询（仅用于超长降级判据；不影响等价性结论）。 */
    private static boolean containsSubqueryFailSafe(@Nullable Expression where) {
        if (where == null) {
            return false;
        }
        String text = where.toString();
        return !text.isBlank() && SUBQUERY_HINT.matcher(text).find();
    }
}
