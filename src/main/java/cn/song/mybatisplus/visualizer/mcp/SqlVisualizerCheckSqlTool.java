// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerCheckSqlTool.kt 移植
package cn.song.mybatisplus.visualizer.mcp;

import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier;
import cn.song.mybatisplus.visualizer.execute.UnboundedWriteDetector;
import cn.song.mybatisplus.visualizer.util.SqlStatementSplitter;
import com.intellij.mcpserver.annotations.McpDescription;
import com.intellij.mcpserver.annotations.McpTool;
import com.intellij.mcpserver.annotations.McpToolHintValue;
import com.intellij.mcpserver.annotations.McpToolHints;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * sqlv_check_sql 工具（T-McpToolset2 / T-MTS2-01，R1 纯逻辑）：SQL 静态写风险与类型核查。
 *
 * 组合三件纯逻辑资产：SqlStatementSplitter（语句数）+ SqlTypeClassifier（QUERY/DML/DDL 分类）
 * + UnboundedWriteDetector（O-9 七规则无界写探测）。纯文本判定，<b>绝不执行</b>。
 *
 * T-MTS2-05 分层：业务逻辑在 {@link #checkSql}（internal 纯函数，普通 JUnit 直测）；挂起方法只做
 * audit 包装——243 测试 JVM 的 stdlib 缺 SpillingKt，audit 本体的高阶 suspend 结构性发射
 * 在测试进程加载即 NoClassDefFoundError（一期语义=mcp-optional.xml 不加载则类永不可达，
 * ignored-problems 豁免留痕），故测试一律走纯函数层，挂起链只有真机/生产触达。
 *
 * 移植注记：平台以 kotlin-reflect 识别工具方法并同步调用（见 McpToolsetSupport 类头移植注记），
 * suspend 去除后本工具方法语义不变（方法体本无真实挂起点）。
 */
public class SqlVisualizerCheckSqlTool extends McpToolsetSupport {

    public SqlVisualizerCheckSqlTool() {
        super("sqlv_check_sql");
    }

    @McpTool(name = "sqlv_check_sql", title = "Static write-risk and type check for a SQL statement")
    @McpDescription(
        description = "Statically check SQL text (no execution, no database access): statement count, per-statement "
            + "type (QUERY/DML/DDL), and unbounded-write findings — missing WHERE (whole-table scope), "
            + "tautological conditions (e.g. 1=1), tautological OR branches, pure-wildcard LIKE ('%'), "
            + "constant-only WHERE, subqueries (informational), and unparseable statements (fail-closed). "
            + "Deterministic static analysis via JSqlParser AST. Run this before sqlv_explain on write "
            + "statements, or after sqlv_render_logs / sqlv_render_sql to assess what rendering produced. "
            + "Read-only: analysis only, never executes SQL."
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE
    )
    public McpToolTextResult sqlv_check_sql(String sql) {
        return audit("sqlv_check_sql", () -> checkSql(sql));
    }

    /** 纯函数核心（零 IDE 依赖）。 */
    public String checkSql(String sql) {
        if (sql.isBlank()) {
            throwExpected(McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT, "sql is empty."));
        }
        List<String> statements = SqlStatementSplitter.splitStripped(sql);
        UnboundedWriteDetector.WriteRisk risk = UnboundedWriteDetector.detect(sql);
        StringBuilder out = new StringBuilder();
        out.append("statements: ").append(statements.size()).append('\n');
        for (int index = 0; index < statements.size(); index++) {
            String statement = statements.get(index);
            out.append("statement #").append(index + 1).append(": ")
                .append(SqlTypeClassifier.classify(statement)).append('\n');
        }
        out.append("dangerous: ").append(risk.isDangerous()).append('\n');
        if (risk.findings().isEmpty()) {
            out.append("findings: none");
        } else {
            out.append("findings:\n");
            List<UnboundedWriteDetector.FindingKind> kinds = new ArrayList<>(risk.kinds());
            kinds.sort(Comparator.naturalOrder());
            for (int index = 0; index < kinds.size(); index++) {
                UnboundedWriteDetector.FindingKind kind = kinds.get(index);
                out.append(index + 1).append(". ").append(kindEnglish(kind));
                UnboundedWriteDetector.Finding firstOfKind = null;
                for (UnboundedWriteDetector.Finding finding : risk.findings()) {
                    if (finding.kind() == kind) {
                        firstOfKind = finding;
                        break;
                    }
                }
                if (firstOfKind != null && firstOfKind.detail() != null && !firstOfKind.detail().isBlank()) {
                    out.append(" — matched: ").append(firstOfKind.detail());
                }
                out.append('\n');
            }
        }
        return McpExplainFormatter.trimEnd(out.toString());
    }

    /** 发现类别 → agent 面英文短句（英文常量不进 bundle 的一期口径；detail 截断在探测器侧已做）。 */
    private static String kindEnglish(UnboundedWriteDetector.FindingKind kind) {
        return switch (kind) {
            case MISSING_WHERE ->
                "MISSING_WHERE: no WHERE clause — the statement applies to the whole table";
            case TAUTOLOGY ->
                "TAUTOLOGY: tautological condition (e.g. 1=1) — the WHERE clause does not filter";
            case OR_TAUTOLOGY ->
                "OR_TAUTOLOGY: an OR branch is tautological — every row matches";
            case LIKE_WILDCARD ->
                "LIKE_WILDCARD: LIKE with a pure-wildcard pattern (e.g. '%') — equivalent to no filter";
            case NO_COLUMN_REF ->
                "NO_COLUMN_REF: the WHERE clause contains only constant expressions — no column reference";
            case SUBQUERY ->
                "SUBQUERY: the WHERE clause contains a subquery — scope depends on its result (informational)";
            case PARSE_FAILED ->
                "PARSE_FAILED: statement structure could not be parsed — verify the scope manually";
        };
    }
}
