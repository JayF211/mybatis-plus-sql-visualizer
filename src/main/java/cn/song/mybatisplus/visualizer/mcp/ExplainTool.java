// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/ExplainTool.kt 移植
package cn.song.mybatisplus.visualizer.mcp;

import cn.song.mybatisplus.visualizer.sql.DialectRegistry;
import com.intellij.mcpserver.annotations.McpDescription;
import com.intellij.mcpserver.annotations.McpTool;
import com.intellij.mcpserver.annotations.McpToolHintValue;
import com.intellij.mcpserver.annotations.McpToolHints;
import com.intellij.openapi.project.Project;

/**
 * mpsv_explain 工具（T-McpToolset2 / T-MTS2-00：一期方法体逐行迁入，按工具拆 toolset 类；
 * resolveExplainTarget/executeExplain/ExplainTarget 随本类走）。
 *
 * 移植注记：原 executeExplain 以 `withContext(Dispatchers.IO)` 把同步 JDBC 挪出协程线程；
 * Java 侧无挂起机制（见 McpToolsetSupport 类头移植注记），执行改在调用线程同步完成——
 * 调用方（平台工具调度协程线程）天然允许阻塞 IO，且原语句本身即同步执行，语义等价。
 * dataSource/dialect 的 Kotlin 默认空串改为调用方必传（同 CrudSqlTool 注记）。
 */
public class ExplainTool extends McpToolsetSupport {

    public ExplainTool() {
        super("mpsv_explain");
    }

    @McpTool(name = "mpsv_explain", title = "Explain a SELECT against a configured data source")
    @McpDescription(
        description = "Run EXPLAIN for a single read-only SELECT statement against a real data source configured in the IDE "
            + "and return the plan text (MySQL/MariaDB/PostgreSQL/Oracle/SQL Server/H2/DM/KingBase dialect dispatch, "
            + "dialect auto-detected from the data source URL unless overridden). Deterministic plan rendering for "
            + "a statement you already have — pair with mpsv_render_sql to produce it first. "
            + "Strictly read-only: the statement must be a single plain SELECT (no locking reads, no INTO, no writes); "
            + "rejections are fail-closed."
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE
    )
    public String mpsv_explain(String selectSql, String dataSource, String dialect) {
        return audit("mpsv_explain", () -> {
            ExplainTarget target = resolveExplainTarget(requireProject(), selectSql, dataSource, dialect);
            McpExplainFormatter.RawResult raw = executeExplain(target);
            // notices 节尾附在方言行后（T-NoticeChannel / O-16）；空清单零追加——输出逐字节与
            // 现状一致（防回归硬门禁）。失败路径 notices 无从产生：executeExplain 直接 throwExpected，
            // RawResult 只在成功时存在，天然满足 DBX「出错语句的 notices 丢弃」口径。
            // index hints 节（T-ExplainIndex / N-23）：索引缺失类模式建议（同款零追加硬门禁；
            // 落点定案=T-EI-01 核实 IDE 内嵌控制台无程序化行读取面，MCP 输出为承载位）。
            StringBuilder out = new StringBuilder();
            out.append(McpExplainFormatter.format(raw))
                .append("\n\ndialect: ").append(target.dialectName)
                .append(McpExplainFormatter.formatNotices(raw.notices()))
                .append(cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor.formatHints(
                    cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor.advise(
                        target.dialectName, raw.headers(), raw.rows()
                    )
                ));
            return out.toString();
        });
    }

    /** explain 前置链（白名单→数据源定位→方言派发，步骤 1~3）。全链非挂起：不进协程状态机，
     *  从结构上杜绝 SpillingKt 引用；语义与分步内联版逐行一致（T-McpToolset 253/243 门禁修复）。 */
    private ExplainTarget resolveExplainTarget(Project project, String selectSql, String dataSource, String dialect) {
        // 1) 只读白名单（fail closed：非 SELECT / 多语句 / 锁读 / INTO / 解析失败全拒）
        McpSelectGuard.Verdict verdict = McpSelectGuard.checkSingleSelect(selectSql);
        if (!verdict.allowed()) {
            throwExpected(McpToolErrors.code(
                verdict.errorCode() != null ? verdict.errorCode() : McpToolErrors.SQL_BLOCKED,
                verdict.reason() != null ? verdict.reason() : "rejected."));
        }

        // 2) 数据源定位（零弹窗：不给/给不出唯一别名即报错并列可选名，绝不弹选择框）
        if (!McpDbExecutor.isDatabaseAvailable()) {
            throwExpected(
                McpToolErrors.code(
                    McpToolErrors.DATASOURCE_NOT_FOUND,
                    "the Database tool plugin is not available in this IDE; configure a data source first."
                )
            );
        }
        Object ds = McpDbExecutor.findDataSource(project, dataSource);
        if (ds == null) {
            var names = McpDbExecutor.listDataSourceNames(project);
            String reason;
            if (names.isEmpty()) {
                reason = "no data sources configured in this project.";
            } else if (dataSource.isBlank()) {
                reason = "several data sources exist (" + String.join(", ", names) + "); "
                    + "pass the 'dataSource' display name.";
            } else {
                reason = "data source '" + dataSource + "' not found; available: " + String.join(", ", names) + ".";
            }
            throwExpected(
                McpToolErrors.code(
                    McpToolErrors.DATASOURCE_NOT_FOUND,
                    reason
                )
            );
        }

        // 3) 方言派发（EXPLAIN 语句包装，七方言矩阵与 IDE 预览面板同链）
        if (!McpDialectDetector.isRecognized(dialect)) {
            throwExpected(
                McpToolErrors.code(
                    McpToolErrors.INVALID_ARGUMENT,
                    "dialect '" + dialect + "' is not recognized; supported: MySQL, MariaDB, PostgreSQL, Oracle, "
                        + "SQL Server, H2, SQLite, DM, KingBase (omit to auto-detect from the data source URL)."
                )
            );
        }
        String url = null;
        try {
            Object config = Class.forName("com.intellij.database.model.DasDataSource")
                .getMethod("getConnectionConfig").invoke(ds);
            if (config != null) {
                Object rawUrl = Class.forName("com.intellij.database.model.RawConnectionConfig")
                    .getMethod("getUrl").invoke(config);
                url = rawUrl instanceof String s ? s : null;
            }
        } catch (Throwable ignored) {
            url = null;
        }
        String dialectName = McpDialectDetector.resolve(dialect, url);
        String trimmedSql = selectSql.trim();
        if (trimmedSql.endsWith(";")) {
            trimmedSql = trimmedSql.substring(0, trimmedSql.length() - 1);
        }
        String explainSql = DialectRegistry.INSTANCE.byName(dialectName).renderExplainStatement(trimmedSql);
        if (explainSql == null) {
            throwExpected(
                McpToolErrors.code(
                    McpToolErrors.DIALECT_UNSUPPORTED,
                    "dialect '" + dialectName + "' has no EXPLAIN rendering in this plugin version."
                )
            );
        }
        return new ExplainTarget(project, ds, explainSql, dialectName);
    }

    /** 4) headless 执行（同步、无 UI、密码未保存即失败）。挂起帧只持 target 单引用。 */
    private McpExplainFormatter.RawResult executeExplain(ExplainTarget target) {
        try {
            // T-ExecTimeoutGuard：设置项「执行超时」透传（0=不限；explain 单语句取每语句基础值）
            return McpDbExecutor.executeQuery(
                target.project, target.ds, target.explainSql, McpExplainFormatter.MAX_ROWS,
                cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance()
                    .getState().getExecutionTimeoutSeconds()
            );
        } catch (Throwable e) {
            // T-ErrorLocate T-EL-04：驱动错误消息携带位置信号（PG `Position: N` / `at line N`）时
            // 附 errorPosition 文本行——agent 可定位再自修复；匹配不到/多 marker 即不加（诚实降级，
            // ErrorPositionExtractor 的 marker 单一性门控与「基建语句不带位置」同源）。
            String causeMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            kotlin.Triple<Integer, Boolean, String> pos =
                cn.song.mybatisplus.visualizer.execute.ErrorPositionExtractor.extractPosition(causeMsg);
            String positionLine = "";
            if (pos != null) {
                String kind = pos.getSecond() ? "char" : "line";
                positionLine = "\nerrorPosition: " + kind + " " + pos.getFirst();
            }
            throwExpected(
                McpToolErrors.code(
                    McpToolErrors.DATASOURCE_UNREACHABLE,
                    "EXPLAIN failed on '" + target.dialectName + "' data source: " + firstLine(causeMsg)
                        + positionLine
                )
            );
            return null; // 不可达：throwExpected 必抛
        }
    }

    /** explain 解析产物载体：项目/数据源/方言信息打包单值过挂起点（挂起帧单槽位，杜绝
     *  K2 nullOutSpilledVariable → SpillingKt 引用；该类 243 平台 stdlib 缺失，verifyPlugin 实证）。 */
    private static final class ExplainTarget {
        private final Project project;
        private final Object ds;
        private final String explainSql;
        private final String dialectName;

        private ExplainTarget(Project project, Object ds, String explainSql, String dialectName) {
            this.project = project;
            this.ds = ds;
            this.explainSql = explainSql;
            this.dialectName = dialectName;
        }
    }
}
