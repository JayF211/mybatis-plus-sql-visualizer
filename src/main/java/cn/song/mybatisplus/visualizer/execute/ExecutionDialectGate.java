package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.DialectRegistry;
import cn.song.mybatisplus.visualizer.sql.IdeaSqlContextBridge;
import cn.song.mybatisplus.visualizer.sql.UrlSubprotocolDialects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * 最终执行目标方言安全闸的纯决策与反射隔离目标解析。
 *
 * <p>master 的 {@code object ExecutionDialectGate} 的 Java 承载（全 static 形态）。</p>
 */
public final class ExecutionDialectGate {

    private ExecutionDialectGate() {
    }

    public enum RenderMode { AUTO, MANUAL, UNKNOWN }

    public enum Intent { QUERY, DML, EXPLAIN, TEMPLATE }

    public enum Status {
        MATCH,
        AUTO_RERENDER_REQUIRED,
        MANUAL_CONFLICT,
        RERENDER_UNAVAILABLE,
        TARGET_UNKNOWN_QUERY_CONFIRM,
        TARGET_UNKNOWN_BLOCK,
        RENDER_UNKNOWN_QUERY_CONFIRM,
        RENDER_UNKNOWN_BLOCK,
    }

    public record Request(
            RenderMode renderMode,
            @Nullable String renderedDialectName,
            Intent intent,
            boolean canLosslesslyRerender,
            boolean userTextUntouched,
            @Nullable String moduleKey
    ) {
        public Request(RenderMode renderMode, @Nullable String renderedDialectName, Intent intent) {
            this(renderMode, renderedDialectName, intent, false, false, null);
        }
    }

    public record Verdict(Status status, @Nullable String renderedDialectName, @Nullable String targetDialectName) {
    }

    public record Target(
            Object dataSource,
            @Nullable String stableId,
            @Nullable String displayName,
            @Nullable String dialectName,
            @Nullable String platformLanguageId
    ) {
    }

    /**
     * O-145 W7 尚未携带 Request 的显式入口清单；完成态必须为空。
     * {@code ExecutionDialectGateTest} 同时逐入口扫描调用参数，防止只清清单、不接生产链的空绿。
     */
    static final Set<String> UNCOVERED_ENTRY_POINTS = Set.of();

    /** 原始日志/临时 SQL 没有可信渲染来源；查询必须显式确认，写入与 EXPLAIN 一律阻断。 */
    public static Request rawSqlRequest(@NotNull String sql) {
        String firstKeyword = SqlTypeClassifier.getFirstKeyword(sql);
        Intent intent;
        if ("EXPLAIN".equalsIgnoreCase(firstKeyword)) {
            intent = Intent.EXPLAIN;
        } else if (SqlTypeClassifier.isQuery(sql)) {
            intent = Intent.QUERY;
        } else {
            intent = Intent.DML;
        }
        return new Request(RenderMode.UNKNOWN, null, intent);
    }

    public static Verdict evaluate(Request request, @Nullable String targetDialectName) {
        String rendered = known(request.renderedDialectName());
        String target = known(targetDialectName);
        if (request.renderMode() == RenderMode.UNKNOWN && rendered == null) {
            Status status = request.intent() == Intent.QUERY
                    ? Status.RENDER_UNKNOWN_QUERY_CONFIRM
                    : Status.RENDER_UNKNOWN_BLOCK;
            return new Verdict(status, null, target);
        }
        if (target == null) {
            Status status = request.intent() == Intent.QUERY
                    ? Status.TARGET_UNKNOWN_QUERY_CONFIRM
                    : Status.TARGET_UNKNOWN_BLOCK;
            return new Verdict(status, rendered, null);
        }
        if (rendered != null && rendered.equals(target)) {
            return new Verdict(Status.MATCH, rendered, target);
        }
        if (request.renderMode() == RenderMode.MANUAL) {
            return new Verdict(Status.MANUAL_CONFLICT, rendered, target);
        }
        if (request.renderMode() == RenderMode.AUTO && request.canLosslesslyRerender()
                && request.userTextUntouched()) {
            return new Verdict(Status.AUTO_RERENDER_REQUIRED, rendered, target);
        }
        return new Verdict(Status.RERENDER_UNAVAILABLE, rendered, target);
    }

    // ---------- 反射目标解析（测试缝：Kotlin internal var → 包私有 volatile 静态字段） ----------

    public interface SessionDataSourceLookup {
        @Nullable Object lookup(Object session);
    }

    public interface ConsoleDataSourceLookup {
        @Nullable Object lookup(Object console);
    }

    static volatile SessionDataSourceLookup sessionDataSourceForTest = null;

    static volatile ConsoleDataSourceLookup consoleDataSourceForTest = null;

    public static Target fromSession(Object session) {
        Object ds = null;
        SessionDataSourceLookup testLookup = sessionDataSourceForTest;
        if (testLookup != null) {
            ds = testLookup.lookup(session);
        } else {
            try {
                ClassLoader loader = ExecutionDialectGate.class.getClassLoader();
                Class<?> sessionClass = Class.forName(DataSourceSelector.SESSION_CLASS, false, loader);
                Object point = sessionClass.getMethod("getConnectionPoint").invoke(session);
                if (point != null) {
                    Class<?> pointClass = Class.forName(
                            "com.intellij.database.dataSource.DatabaseConnectionPoint", false, loader);
                    ds = pointClass.getMethod("getDataSource").invoke(point);
                }
            } catch (Throwable t) {
                if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) {
                    throw (Error) t;
                }
                ds = null;
            }
        }
        return ds != null ? fromDataSource(ds) : null;
    }

    public static Target fromConsole(Object console) {
        Object ds = null;
        ConsoleDataSourceLookup testLookup = consoleDataSourceForTest;
        if (testLookup != null) {
            ds = testLookup.lookup(console);
        } else {
            try {
                Class<?> jdbcConsole = Class.forName(
                        "com.intellij.database.console.JdbcConsole",
                        false,
                        ExecutionDialectGate.class.getClassLoader());
                ds = jdbcConsole.getMethod("getDataSource").invoke(console);
            } catch (Throwable t) {
                if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) {
                    throw (Error) t;
                }
                ds = null;
            }
        }
        return ds != null ? fromDataSource(ds) : null;
    }

    public static Target fromDataSource(Object dataSource) {
        String url = ExecDialectRouting.dataSourceUrl(dataSource);
        String platformId = ExecDialectRouting.platformDialectId(dataSource);
        // JDBC 子协议是当前目标的精确厂商事实；平台 Language 可能只是 MySQL/PG 家族兜底。
        String dialectName = targetDialectName(url, platformId);
        String displayName = DataSourceSettings.getName(dataSource);
        return new Target(
                dataSource,
                DataSourceSettings.getUniqueId(dataSource),
                "Unknown".equals(displayName) ? null : displayName,
                dialectName,
                platformId);
    }

    static String targetDialectName(@Nullable String jdbcUrl, @Nullable String platformLanguageId) {
        String byUrl = UrlSubprotocolDialects.dialectName(jdbcUrl);
        if (byUrl != null) {
            return byUrl;
        }
        return IdeaSqlContextBridge.pluginDialectName(platformLanguageId);
    }

    private static String known(@Nullable String name) {
        if (name == null) {
            return null;
        }
        for (Dialect dialect : DialectRegistry.INSTANCE.getDialects()) {
            if (dialect.getName().equalsIgnoreCase(name)) {
                return dialect.getName();
            }
        }
        return null;
    }
}
