// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/RollbackPlanTool.kt 移植
package cn.song.mybatisplus.visualizer.mcp;

import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.mcpserver.annotations.McpDescription;
import com.intellij.mcpserver.annotations.McpTool;
import com.intellij.mcpserver.annotations.McpToolHintValue;
import com.intellij.mcpserver.annotations.McpToolHints;

import java.util.ArrayList;
import java.util.List;

/**
 * mpsv_rollback_plan 工具（T-McpToolset2 / T-MTS2-01，R1 纯逻辑）：为 INSERT / UPDATE / DELETE
 * 生成回滚预案（评级 + 可执行回滚语句 + 警示 + 自查模板）。
 *
 * 复用 T-RollbackSql 的 RollbackSqlGenerator（零 PSI 零 IDE，诚实拒绝不可逆推类型——
 * UNABLE 时 statements 恒空给人工恢复指引）。<b>只产出预案，绝不执行</b>——执行回滚是用户
 * 在 IDE 预览面板的显式动作，agent 面不提供执行通道。
 * 警示文案经 {@link MpSqlVisualizerBundle#messageEn}（bundle base 即英文，零新键）。
 * 逻辑分层同 {@link CheckSqlTool}：{@link #rollbackPlan} 纯函数可测，挂起方法只做 audit 包装。
 *
 * 移植注记：primaryKeyColumns 的 Kotlin 默认空串改为调用方必传（平台对 Java 方法无默认参数
 * 机制），空串语义（无主键列）与原实现一致。
 */
public class RollbackPlanTool extends McpToolsetSupport {

    public RollbackPlanTool() {
        super("mpsv_rollback_plan");
    }

    @McpTool(name = "mpsv_rollback_plan", title = "Generate a rollback plan for INSERT/UPDATE/DELETE")
    @McpDescription(
        description = "Generate a rollback plan for INSERT / UPDATE / DELETE statements WITHOUT executing anything: "
            + "a completeness rating (COMPLETE = primary-key located, PARTIAL = executable with risk "
            + "warnings, UNABLE = not derivable — statements list stays empty with recovery guidance), "
            + "candidate rollback statements, warnings, a pre-execution COUNT check template, and a "
            + "snapshot SELECT template. Pass 'primaryKeyColumns' (comma-separated) when the table has a "
            + "primary key to get precise per-row rollback. Deterministic text derivation; "
            + "read-only: produces a plan only — never touches the database."
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE
    )
    public String mpsv_rollback_plan(String sql, String primaryKeyColumns) {
        return audit("mpsv_rollback_plan", () -> rollbackPlan(sql, primaryKeyColumns));
    }

    /** 纯函数核心（零 IDE 依赖）。 */
    public String rollbackPlan(String sql, String primaryKeyColumns) {
        if (sql.isBlank()) {
            throwExpected(McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT, "sql is empty."));
        }
        List<String> pk = new ArrayList<>();
        for (String part : primaryKeyColumns.split("[,;\n]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                pk.add(trimmed);
            }
        }
        var result = RollbackSqlGenerator.generate(sql, pk.isEmpty() ? null : pk);
        StringBuilder out = new StringBuilder();
        out.append("rating: ").append(result.rating()).append('\n');
        if (result.targetTable() != null) {
            out.append("table: ").append(result.targetTable()).append('\n');
        }
        if (result.statements().isEmpty()) {
            out.append("statements: (none — see warnings)\n");
        } else {
            out.append("rollback statements (").append(result.statements().size()).append("):\n");
            for (int index = 0; index < result.statements().size(); index++) {
                String statement = result.statements().get(index);
                // T-StatementNaming（O-19）：语句前导注释名附注（无注释零差异）
                out.append("-- SQL #").append(index + 1);
                String statementName = cn.song.mybatisplus.visualizer.util.SqlStatementNamer.nameOf(statement);
                if (statementName != null) {
                    out.append(" [").append(statementName).append("]");
                }
                out.append('\n').append(statement).append('\n');
            }
        }
        if (!result.warnings().isEmpty()) {
            out.append("\nwarnings:\n");
            for (var warning : result.warnings()) {
                out.append("- ").append(
                    MpSqlVisualizerBundle.messageEn(warning.messageKey(), warning.messageArgs().toArray())
                ).append('\n');
            }
        }
        if (result.countCheckSql() != null) {
            out.append("\ncount check (run first): ").append(result.countCheckSql()).append('\n');
        }
        if (result.snapshotSelectSql() != null) {
            out.append("snapshot template: ").append(result.snapshotSelectSql()).append('\n');
        }
        return McpExplainFormatter.trimEnd(out.toString());
    }
}
