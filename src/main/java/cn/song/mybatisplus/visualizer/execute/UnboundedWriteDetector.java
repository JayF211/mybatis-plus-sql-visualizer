// T-SyncK2JMaster309 自 execute/UnboundedWriteDetector.kt 移植
package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.BooleanValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 无界写探测器（T-UnboundedWriteGuard / O-9）：识别 UPDATE/DELETE 中「可能影响全表或大量行」
 * 的静态特征，供执行确认流升级文案与回滚视图弱谓词警示复用。
 *
 * <p>规则集对齐 DBX {@code sql_risk.rs}（源码级验证过的无界写判定），经 JSqlParser AST 表达——
 * parser 权威原则与 RollbackSqlGenerator 一致：</p>
 * <ul>
 *  <li>{@link FindingKind#MISSING_WHERE} UPDATE/DELETE 无 WHERE 子句；</li>
 *  <li>{@link FindingKind#TAUTOLOGY} 恒真谓词（{@code 1=1} / {@code a=a} / {@code 'x'='x'} / {@code TRUE}）；</li>
 *  <li>{@link FindingKind#OR_TAUTOLOGY} OR 任一侧恒真（整行命中）；</li>
 *  <li>{@link FindingKind#LIKE_WILDCARD} {@code LIKE} 纯通配（{@code '%'} / {@code '%%'}——等价无过滤）；</li>
 *  <li>{@link FindingKind#NO_COLUMN_REF} WHERE 仅常量表达式（无任何列引用）；</li>
 *  <li>{@link FindingKind#SUBQUERY} WHERE 含子查询（提示型，不算危险，供文案分级）；</li>
 *  <li>{@link FindingKind#PARSE_FAILED} 解析失败 fail closed（按最高风险处理，走既有确认流不阻断）。</li>
 * </ul>
 *
 * <p>消费者限定两处（KDoc 契约）：execute/LogToSqlPanel 确认流、sql/rollback/RollbackSqlGenerator
 * 弱谓词警示；MCP 侧（McpSelectGuard）维持 SELECT 白名单不动，不接入本探测器。</p>
 *
 * <p>零 PSI / 零 IDE 依赖（仅 JSqlParser + SqlCommentStripper），普通 JUnit 可直接覆盖。</p>
 */
public final class UnboundedWriteDetector {

    public static final UnboundedWriteDetector INSTANCE = new UnboundedWriteDetector();
    private UnboundedWriteDetector() {}

    /** 发现类别（危险升序大致排列；SUBQUERY 仅提示）。 */
    public enum FindingKind { MISSING_WHERE, TAUTOLOGY, OR_TAUTOLOGY, LIKE_WILDCARD, NO_COLUMN_REF, SUBQUERY, PARSE_FAILED }

    /**
     * 单条发现。
     *
     * @param kind   类别（i18n 键派生自类别，UI 层渲染）
     * @param detail 命中片段原文（截断，供日志/测试定位；不进用户文案）
     */
    public record Finding(FindingKind kind, String detail) {
        /** detail 缺省（Kotlin 默认参数 {@code detail: String = ""} 的等价构造形态）。 */
        public Finding(FindingKind kind) {
            this(kind, "");
        }
    }

    /** 探测结果：全部发现（按语句序聚合，多语句取并集=最高风险面）。 */
    public record WriteRisk(List<Finding> findings) {
        /** 是否存在危险发现（SUBQUERY 仅提示不算；PARSE_FAILED fail closed 算危险）。 */
        public boolean isDangerous() {
            for (Finding f : findings) {
                if (f.kind() != FindingKind.SUBQUERY) return true;
            }
            return false;
        }

        /** 去重后的类别集合（确认文案每类一行）。 */
        public Set<FindingKind> kinds() {
            Set<FindingKind> out = new LinkedHashSet<>();
            for (Finding f : findings) out.add(f.kind());
            return out;
        }

        /** Kotlin `val dangerous` 的 JVM 访问器等价入口（Kotlin 调用方按属性名读取）。 */
        public boolean getDangerous() {
            return isDangerous();
        }
    }

    /** detail 片段长度上限（日志/测试定位用，非用户文案）。 */
    private static final int DETAIL_LIMIT = 60;

    /** LIKE 纯通配：至少一个 `%`，且只含 `%`/`_`。 */
    private static final Pattern LIKE_WILDCARD_REGEX = Pattern.compile("[%_]*%[%_]*");

    /**
     * T-PS5-02：AST 单槽共享缓存——确认流（本探测器 {@link #detect}）与回滚链
     * （{@code cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator.generate}）对<b>同一 SQL 文本</b>
     * 各解析一次（一次大 DML 点击最坏 4 次解析、3 次在 EDT）；单槽命中即复用同一次解析产物，
     * 最坏路径的 3 次降为 1 次，且<b>零调用点改动</b>（两条链都以「文本」为输入，槽位天然对上）。
     *
     * <p>命中判据与 T-PS5-01 同款三重快判 + 内容全等收口（引用相等 → 长度 → 哈希 → 全等）；
     * 单槽持有上次解析文本引用，上界=一条 SQL 文本。
     *
     * <p><b>AST 只读纪律（硬约束）</b>：两处消费者（{@link #detect} 的 analyzeWhere、
     * {@code RollbackSqlGenerator} 的渲染链）经 grep 核实均为纯遍历——无 {@code set…}/{@code with…} 写节点调用，
     * 故共享同一 AST 实例不会互相污染；后续任何一方引入节点改写必须先脱离本槽（改为独立解析）。
     */
    private static final class AstSlot {
        final String cleaned;
        final List<Statement> statements;

        AstSlot(String cleaned, List<Statement> statements) {
            this.cleaned = cleaned;
            this.statements = statements;
        }
    }

    private static volatile AstSlot astSlot = null;

    /**
     * T-PS5-02 测试缝：AST 解析器（默认 JSqlParser）。生产链路勿改；测试注入替身以断言
     * 「确认流 → 回滚链只解析一次」。
     *
     * <p>独立函数式接口（而非 {@code java.util.function.Function}）是因为
     * {@code CCJSqlParserUtil.parseStatements} 抛受检的 {@code JSQLParserException}——
     * 标准函数式接口的方法不允许抛出受检异常。</p>
     */
    @FunctionalInterface
    public interface StatementParser {
        List<Statement> parse(String sql) throws Exception;
    }

    public static volatile StatementParser parseStatementsForTest = CCJSqlParserUtil::parseStatements;

    /** T-PS5-02 测试缝：清空单槽（用例隔离；生产链路无调用点）。 */
    public static void clearAstSlotForTest() {
        astSlot = null;
    }

    /**
     * T-PS5-02：共享解析入口——命中单槽直接复用，未命中解析后入槽。<b>解析失败原样抛出</b>
     * （fail 语义由调用方决定：{@link #detect} 返回 PARSE_FAILED，RollbackSqlGenerator 返回 UNABLE）。
     */
    public static List<Statement> parseStatementsShared(String cleaned) throws Exception {
        AstSlot slot = astSlot;
        if (slot != null) {
            if (slot.cleaned == cleaned) return slot.statements;
            if (slot.cleaned.length() == cleaned.length()
                    && slot.cleaned.hashCode() == cleaned.hashCode()
                    && slot.cleaned.equals(cleaned)) {
                return slot.statements;
            }
        }
        List<Statement> statements = parseStatementsForTest.parse(cleaned);
        astSlot = new AstSlot(cleaned, statements);
        return statements;
    }

    /**
     * 探测 SQL 文本（可含注释与多条语句）。UPDATE/DELETE 逐条判定；INSERT 不适用（忽略）；
     * 解析失败整体 fail closed 返回 {@link FindingKind#PARSE_FAILED}。
     *
     * <p>T-PS5-02：解析走 {@link #parseStatementsShared} 单槽——同文本紧随其后的回滚链
     * （RollbackSqlGenerator.generate）复用本产物，不二次解析。
     */
    @org.jetbrains.annotations.NotNull
    public static WriteRisk detect(@org.jetbrains.annotations.NotNull String sql) {
        String cleaned = SqlCommentStripper.strip(sql);
        if (cleaned.isBlank()) return new WriteRisk(List.of());
        List<Statement> statements;
        try {
            statements = parseStatementsShared(cleaned);
        } catch (Exception e) {
            return new WriteRisk(List.of(new Finding(FindingKind.PARSE_FAILED,
                    e.getMessage() != null && e.getMessage().length() > DETAIL_LIMIT
                            ? e.getMessage().substring(0, DETAIL_LIMIT)
                            : (e.getMessage() != null ? e.getMessage() : ""))));
        }
        List<Finding> findings = new ArrayList<>();
        for (Statement statement : statements) {
            if (statement instanceof Update) {
                findings.addAll(analyzeWhere(((Update) statement).getWhere()));
            } else if (statement instanceof Delete) {
                findings.addAll(analyzeWhere(((Delete) statement).getWhere()));
            }
            // INSERT 不适用（无 WHERE 语义）；其他类型非本探测器职责
        }
        return new WriteRisk(findings);
    }

    /**
     * WHERE 子句分析（RollbackSqlGenerator 复用——T-UW-03 弱谓词警示）。
     * 无 WHERE → {@link FindingKind#MISSING_WHERE}；恒真与纯通配在 AND/OR 叶子层逐原子判定。
     */
    public static List<Finding> analyzeWhere(@org.jetbrains.annotations.Nullable Expression where) {
        if (where == null) return List.of(new Finding(FindingKind.MISSING_WHERE));
        List<Finding> findings = new ArrayList<>();
        List<Expression> atoms = new ArrayList<>();
        collectAndAtoms(where, atoms);
        boolean hasColumnRef = false;
        for (Expression atom : atoms) {
            Expression unwrapped = unwrapGroup(atom);
            if (unwrapped instanceof net.sf.jsqlparser.expression.operators.conditional.OrExpression) {
                List<Expression> sides = new ArrayList<>();
                collectOrSides(unwrapped, sides);
                boolean anyTautology = false;
                for (Expression side : sides) {
                    if (isTautology(unwrapGroup(side))) { anyTautology = true; break; }
                }
                if (anyTautology) {
                    findings.add(new Finding(FindingKind.OR_TAUTOLOGY, shorten(unwrapped)));
                }
                for (Expression side : sides) {
                    Expression s = unwrapGroup(side);
                    if (isLikeWildcard(s)) findings.add(new Finding(FindingKind.LIKE_WILDCARD, shorten(s)));
                    if (containsColumn(s) || containsSelect(s)) hasColumnRef = true;
                }
            } else {
                if (isTautology(unwrapped)) findings.add(new Finding(FindingKind.TAUTOLOGY, shorten(unwrapped)));
                if (isLikeWildcard(unwrapped)) findings.add(new Finding(FindingKind.LIKE_WILDCARD, shorten(unwrapped)));
            }
            if (containsColumn(unwrapped) || containsSelect(unwrapped)) hasColumnRef = true;
        }
        boolean hasTautologyFinding = false;
        for (Finding f : findings) {
            if (f.kind() == FindingKind.TAUTOLOGY) { hasTautologyFinding = true; break; }
        }
        if (!hasColumnRef && !hasTautologyFinding) {
            findings.add(new Finding(FindingKind.NO_COLUMN_REF, shorten(where)));
        }
        if (containsSelect(where)) {
            findings.add(new Finding(FindingKind.SUBQUERY, shorten(where)));
        }
        return findings;
    }

    /**
     * 展开顶层 AND 两侧为原子；括号包住的 AND 组递归摊平（{@code WHERE (a=1 AND 1=1)} 的
     * 内嵌恒真不因括号漏报）；{@code (b=1 OR 1=1) AND c=2} 的括号 OR 保留 OrExpression
     * 形态，由调用方按 OR 处理。
     */
    private static void collectAndAtoms(Expression expr, List<Expression> out) {
        if (expr instanceof net.sf.jsqlparser.expression.operators.conditional.AndExpression) {
            net.sf.jsqlparser.expression.operators.conditional.AndExpression and =
                    (net.sf.jsqlparser.expression.operators.conditional.AndExpression) expr;
            collectAndAtoms(and.getLeftExpression(), out);
            collectAndAtoms(and.getRightExpression(), out);
        } else {
            Expression inner = unwrapGroup(expr);
            if (inner != expr && inner instanceof net.sf.jsqlparser.expression.operators.conditional.AndExpression) {
                collectAndAtoms(inner, out);
            } else {
                out.add(expr);
            }
        }
    }

    /** 展开 OR 两侧（OR 链 {@code a OR b OR c} 无论结合方向均摊平）；AND/OR 混合时 AND 侧整体作为一个 side。 */
    private static void collectOrSides(Expression expr, List<Expression> out) {
        if (expr instanceof net.sf.jsqlparser.expression.operators.conditional.OrExpression) {
            net.sf.jsqlparser.expression.operators.conditional.OrExpression or =
                    (net.sf.jsqlparser.expression.operators.conditional.OrExpression) expr;
            collectOrSides(or.getLeftExpression(), out);
            collectOrSides(or.getRightExpression(), out);
        } else {
            out.add(expr);
        }
    }

    /**
     * 解包括号组：JSqlParser 5.3 两种形态——{@code Parenthesis} 与 {@code ParenthesedExpressionList}
     * （布尔括号组解析为单元素列表，实证 {@code (b = 2 OR 1 = 1)} 即后者）；多层括号递归解到底。
     */
    private static Expression unwrapGroup(Expression expr) {
        Expression current = expr;
        while (true) {
            if (current instanceof Parenthesis) {
                current = ((Parenthesis) current).getExpression();
            } else if (current instanceof net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList<?>
                    && ((net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList<?>) current).size() == 1) {
                current = (Expression) ((net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList<?>) current).get(0);
            } else {
                return current;
            }
        }
    }

    /** 恒真判定：{@code TRUE} 字面量 / 等值两侧可渲染为同一常量或同名列。 */
    private static boolean isTautology(Expression expr) {
        if (expr instanceof BooleanValue) return ((BooleanValue) expr).getValue();
        if (expr instanceof EqualsTo) {
            EqualsTo eq = (EqualsTo) expr;
            String left = renderComparable(eq.getLeftExpression());
            String right = renderComparable(eq.getRightExpression());
            return left != null && left.equals(right);
        }
        return false;
    }

    /** 可比较渲染：列名小写归一 / 字面量原样；不可比形态返回 null。 */
    @org.jetbrains.annotations.Nullable
    private static String renderComparable(Expression expr) {
        if (expr instanceof Column) return "col:" + ((Column) expr).getColumnName().toLowerCase();
        if (expr instanceof LongValue || expr instanceof DoubleValue
                || expr instanceof StringValue || expr instanceof BooleanValue) {
            return "lit:" + expr;
        }
        return null;
    }

    /** LIKE 纯通配：右值为只含 {@code %}/{@code _}（至少一个 {@code %}）的字符串；NOT LIKE 匹配空集不算危险。 */
    private static boolean isLikeWildcard(Expression expr) {
        if (!(expr instanceof LikeExpression) || ((LikeExpression) expr).isNot()) return false;
        Expression right = ((LikeExpression) expr).getRightExpression();
        if (!(right instanceof StringValue)) return false;
        return LIKE_WILDCARD_REGEX.matcher(((StringValue) right).getValue()).matches();
    }

    /** 子树含任何列引用（子查询按含列处理——其内部引用外部列）。 */
    private static boolean containsColumn(Expression expr) {
        if (expr instanceof Column) return true;
        if (expr instanceof Select) return true;
        if (expr instanceof BinaryExpression) {
            BinaryExpression b = (BinaryExpression) expr;
            return containsColumn(b.getLeftExpression()) || containsColumn(b.getRightExpression());
        }
        if (expr instanceof Parenthesis) return containsColumn(((Parenthesis) expr).getExpression());
        if (expr instanceof SignedExpression) return containsColumn(((SignedExpression) expr).getExpression());
        if (expr instanceof InExpression) {
            InExpression in = (InExpression) expr;
            return containsColumn(in.getLeftExpression()) || containsColumn(in.getRightExpression());
        }
        if (expr instanceof IsNullExpression) return containsColumn(((IsNullExpression) expr).getLeftExpression());
        if (expr instanceof Function) {
            return functionParamsContain(((Function) expr).getParameters(), UnboundedWriteDetector::containsColumn);
        }
        return false;
    }

    /** 子树含子查询（5.x 子查询在表达式位置即 ParenthesedSelect extends Select）。 */
    private static boolean containsSelect(Expression expr) {
        if (expr instanceof Select) return true;
        if (expr instanceof BinaryExpression) {
            BinaryExpression b = (BinaryExpression) expr;
            return containsSelect(b.getLeftExpression()) || containsSelect(b.getRightExpression());
        }
        if (expr instanceof Parenthesis) return containsSelect(((Parenthesis) expr).getExpression());
        if (expr instanceof SignedExpression) return containsSelect(((SignedExpression) expr).getExpression());
        if (expr instanceof InExpression) {
            InExpression in = (InExpression) expr;
            return containsSelect(in.getLeftExpression()) || containsSelect(in.getRightExpression());
        }
        if (expr instanceof IsNullExpression) return containsSelect(((IsNullExpression) expr).getLeftExpression());
        if (expr instanceof Function) {
            return functionParamsContain(((Function) expr).getParameters(), UnboundedWriteDetector::containsSelect);
        }
        return false;
    }

    /** {@code expr.parameters?.any { pred(it) } == true}——参数表 null 视为不含。 */
    private static boolean functionParamsContain(
            net.sf.jsqlparser.expression.operators.relational.ExpressionList<?> parameters,
            java.util.function.Predicate<Expression> pred) {
        if (parameters == null) return false;
        for (Expression e : parameters) {
            if (e != null && pred.test(e)) return true;
        }
        return false;
    }

    private static String shorten(Expression expr) {
        String text = expr.toString().replace('\n', ' ');
        return text.length() > DETAIL_LIMIT ? text.substring(0, DETAIL_LIMIT) + "…" : text;
    }
}
