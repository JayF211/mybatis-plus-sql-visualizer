// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpDbExecutor.kt object 段移植
package cn.song.mybatisplus.visualizer.mcp;

import cn.song.mybatisplus.visualizer.mcp.McpDbExecutorKt.WarningSurface;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * T-McpToolset headless 执行通道（mpsv_explain 专用，反射隔离 DatabaseTools）。
 *
 * 为什么不复用 {@code cn.song.mybatisplus.visualizer.execute.SqlExecutor}：既有执行链是 UI 控制台
 * 形态（弹数据源选择框 + 异步回调 + 结果进原生控制台），违反 MCP 零弹窗红线且拿不到文本结果。
 * 本通道走 DatabaseConnectionManager 同步建连（243/262 javap 同形实证）：
 * <pre>{@code
 * DatabaseConnectionManager.getInstance()                    // static
 *   .build(project, localDataSource)                         // LocalDataSource 即 DatabaseConnectionPoint
 *   .setAskPassword(false)                                   // 零弹窗：密码未保存直接失败（fail closed）
 *   .createBlocking()  -> GuardedRef<DatabaseConnection>     // 同步无 UI
 * ref.get().getRemoteConnection()                            // DatabaseConnectionCore 接口方法
 *   .createStatement().executeQuery(sql)  -> RemoteResultSet // DBN remote JDBC 镜像
 * }</pre>
 * 全反射 + Method 一律从声明接口/类获取（T-Embed-10 跨 classloader 教训）。签名由
 * McpDbSignatureCrossVersionTest 对本机 243/253/262 发行版核验。
 *
 * T-NoticeChannel：执行前后经 {@code warningSurfaces} 取尽语句级与连接级 warnings
 * （getAllWarnings + clearWarnings，DBN 镜像接口），产出挂 RawResult.notices 供工具层渲染。
 *
 * 数据源发现复用 {@code cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver} 同链：
 * DataSourceManager.getManagers(project) → getDataSources()，按 DasDataSource.getName() 匹配别名。
 *
 * 移植注记：Kotlin 侧方法体不经 catch 直接把反射异常冒泡给调用方的 runCatching；Java 无受检
 * 异常豁免，故以 {@code throws Exception} 表达同一「异常原样上抛、调用方兜底」契约。
 */
public final class McpDbExecutor {

    private McpDbExecutor() {
    }

    /** 单例（Kotlin object 惯例形态；静态方法为主）。 */
    public static final McpDbExecutor INSTANCE = new McpDbExecutor();

    private static final Logger LOG = Logger.getInstance(McpDbExecutor.class);

    /** database 插件标记类（探测用，与 DatabaseSchemaResolver 同款）。 */
    private static final String MANAGER_CLASS = "com.intellij.database.psi.DataSourceManager";
    private static final String DAS_DATA_SOURCE = "com.intellij.database.model.DasDataSource";

    private static final String DCM = "com.intellij.database.dataSource.DatabaseConnectionManager";
    private static final String CONNECTION_POINT = "com.intellij.database.dataSource.DatabaseConnectionPoint";
    private static final String BUILDER = "com.intellij.database.dataSource.DatabaseConnectionManager$Builder";
    private static final String GUARDED_REF = "com.intellij.database.util.GuardedRef";
    private static final String CONNECTION_CORE = "com.intellij.database.dataSource.DatabaseConnectionCore";
    private static final String CONNECTION = "com.intellij.database.dataSource.DatabaseConnection";
    private static final String REMOTE_CONNECTION = "com.intellij.database.remote.jdbc.RemoteConnection";
    private static final String REMOTE_STATEMENT = "com.intellij.database.remote.jdbc.RemoteStatement";
    private static final String REMOTE_RESULT_SET = "com.intellij.database.remote.jdbc.RemoteResultSet";
    private static final String REMOTE_META = "com.intellij.database.remote.jdbc.RemoteResultSetMetaData";

    /** database 插件是否在场（Ultimate/DataGrip）。 */
    public static boolean isDatabaseAvailable() {
        try {
            Class.forName(MANAGER_CLASS);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 列出全部数据源显示名（alias 未匹配时给 agent 的可选清单）。 */
    public static List<String> listDataSourceNames(Project project) {
        List<Object> sources = dataSources(project);
        if (sources == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object ds : sources) {
            try {
                Object name = Class.forName(DAS_DATA_SOURCE).getMethod("getName").invoke(ds);
                if (name instanceof String s) {
                    out.add(s);
                }
            } catch (Throwable ignored) {
                // 单个数据源名反射失败 → 跳过该条（mapNotNull 语义）
            }
        }
        return out;
    }

    /**
     * 按别名找数据源。alias 空时：恰好一个数据源 → 用之；0 个/多个 → null（调用方报错并列名）。
     * 别名匹配大小写不敏感；找不到返回 null。
     */
    public static Object findDataSource(Project project, String alias) {
        List<Object> sources = dataSources(project);
        if (sources == null) {
            return null;
        }
        String named = alias == null ? null : alias.trim();
        if (named != null && named.isEmpty()) {
            named = null;
        }
        if (named == null) {
            return sources.size() == 1 ? sources.get(0) : null;
        }
        for (Object ds : sources) {
            String name = null;
            try {
                Object raw = Class.forName(DAS_DATA_SOURCE).getMethod("getName").invoke(ds);
                if (raw instanceof String s) {
                    name = s;
                }
            } catch (Throwable ignored) {
                // 反射失败视作无名（不参与匹配）
            }
            if (name != null && name.equalsIgnoreCase(named)) {
                return ds;
            }
        }
        return null;
    }

    /**
     * 在指定数据源上执行只读查询并采集行列文本（EXPLAIN 用）。
     * 密码未保存/不可达等建连失败以异常冒泡，适配层按
     * {@link McpToolErrors#DATASOURCE_UNREACHABLE} 包装为 agent 可读错误（零弹窗 fail closed）。
     *
     * <p>T-ExecTimeoutGuard（O-25）：timeoutSeconds &gt; 0 时执行前 setQueryTimeout（驱动级
     * 建议值，RemoteStatement 镜像 243/262 javap 同形）；超时形态异常
     * （{@code cn.song.mybatisplus.visualizer.execute.ExecutionTimeoutGuard.isTimeoutLike}）先
     * Statement.cancel() 收尾再冒泡，消息尾附 {@code timed out after Ns}（agent 可读，不引 JSON）。</p>
     */
    public static McpExplainFormatter.RawResult executeQuery(
        Project project,
        Object dataSource,
        String sql,
        int maxRows,
        int timeoutSeconds
    ) throws Exception {
        Object manager = Class.forName(DCM).getMethod("getInstance").invoke(null);
        Object builder = Class.forName(DCM).getMethod("build", Project.class, Class.forName(CONNECTION_POINT))
            .invoke(manager, project, dataSource);
        Class.forName(BUILDER).getMethod("setAskPassword", Boolean.TYPE)
            .invoke(builder, false);
        Object ref = Class.forName(BUILDER).getMethod("createBlocking").invoke(builder);
        try {
            Object connection = Class.forName(GUARDED_REF).getMethod("get").invoke(ref);
            Object remoteConnection = findInterfaceMethod(connection, CONNECTION_CORE, CONNECTION, "getRemoteConnection")
                .invoke(connection);
            Object statement = Class.forName(REMOTE_CONNECTION).getMethod("createStatement").invoke(remoteConnection);
            try {
                // 超时护栏：驱动级建议值，设置 0=不限；设置失败不拦执行（护栏尽力而为语义）
                if (timeoutSeconds > 0) {
                    try {
                        Class.forName(REMOTE_STATEMENT).getMethod("setQueryTimeout", Integer.TYPE)
                            .invoke(statement, timeoutSeconds);
                    } catch (Throwable failure) {
                        LOG.info("McpDbExecutor: setQueryTimeout unsupported by driver, guard off", failure);
                    }
                }
                WarningSurface[] surfaces = warningSurfaces(remoteConnection, statement);
                // 执行前先 drain 一次（DBX 口径）：DBN 连接可复用，上一条语句/会话遗留的陈旧
                // notices 不允许串扰进本次结果；返回值丢弃，只做清空。
                McpDbExecutorKt.drainWarnings(surfaces);
                try {
                    Object resultSet;
                    try {
                        resultSet = Class.forName(REMOTE_STATEMENT).getMethod("executeQuery", String.class)
                            .invoke(statement, sql);
                    } catch (InvocationTargetException e) {
                        throw e;
                    }
                    McpExplainFormatter.RawResult raw = collect(resultSet, maxRows);
                    // 执行成功才采集 notices；失败路径直接抛出（notices 无从产生=DBX「出错丢弃」口径）
                    List<String> notices = McpDbExecutorKt.drainWarnings(surfaces);
                    if (!notices.isEmpty()) {
                        LOG.info("McpDbExecutor: drained " + notices.size() + " driver notice(s)");
                    }
                    return new McpExplainFormatter.RawResult(
                        raw.headers(), raw.rows(), raw.truncated(), notices);
                } catch (InvocationTargetException e) {
                    // T-ExecTimeoutGuard：超时先收尾再报错——cancel 请求（RMI 镜像尽力而为）
                    // 后再冒泡带 timed out 标记的异常；cancel 失败不吞超时事实（如实措辞）
                    Throwable target = e.getTargetException();
                    if (cn.song.mybatisplus.visualizer.execute.ExecutionTimeoutGuard.isTimeoutLike(target)) {
                        try {
                            Class.forName(REMOTE_STATEMENT).getMethod("cancel").invoke(statement);
                        } catch (Throwable cancelFailure) {
                            LOG.warn("McpDbExecutor: cancel after timeout failed", cancelFailure);
                        }
                        throw new SQLException(
                            "query timed out after " + timeoutSeconds + "s (cancel requested); "
                                + "original: " + (target.getMessage() != null
                                    ? target.getMessage()
                                    : target.getClass().getSimpleName()),
                            target
                        );
                    }
                    throw e;
                }
            } finally {
                try {
                    Class.forName(REMOTE_STATEMENT).getMethod("close").invoke(statement);
                } catch (Throwable failure) {
                    LOG.warn("McpDbExecutor: close statement failed", failure);
                }
            }
        } finally {
            try {
                Class.forName(GUARDED_REF).getMethod("close").invoke(ref);
            } catch (Throwable failure) {
                LOG.warn("McpDbExecutor: close connection ref failed", failure);
            }
        }
    }

    /**
     * warnings 采集面（语句级先、连接级后，执行前 drain 与执行后采集共用同一组 Method 查找）。
     * getAllWarnings/clearWarnings 在 RemoteStatement 与 RemoteConnection 上均有声明
     * （243/262 发行版 javap 实证，McpDbSignatureChecker 登记）——DBN remote JDBC 镜像自带
     * 取尽方法，无需手写 getNextWarning 链式遍历（每次 next 都是一次 RMI 往返）。
     */
    private static WarningSurface[] warningSurfaces(Object remoteConnection, Object statement) {
        return new WarningSurface[]{
            new WarningSurface(
                () -> {
                    try {
                        Object raw = Class.forName(REMOTE_STATEMENT).getMethod("getAllWarnings").invoke(statement);
                        return raw instanceof List<?> list ? list : null;
                    } catch (Throwable ignored) {
                        // 该面取 warnings 失败 → drainWarnings 兜底为不产出（runCatching 语义）
                        return null;
                    }
                },
                () -> {
                    try {
                        Class.forName(REMOTE_STATEMENT).getMethod("clearWarnings").invoke(statement);
                    } catch (Throwable ignored) {
                        // clear 失败由 drainWarnings 兜底（不丢已取到的消息）
                    }
                }
            ),
            new WarningSurface(
                () -> {
                    try {
                        Object raw = Class.forName(REMOTE_CONNECTION).getMethod("getAllWarnings")
                            .invoke(remoteConnection);
                        return raw instanceof List<?> list ? list : null;
                    } catch (Throwable ignored) {
                        // 该面取 warnings 失败 → drainWarnings 兜底为不产出（runCatching 语义）
                        return null;
                    }
                },
                () -> {
                    try {
                        Class.forName(REMOTE_CONNECTION).getMethod("clearWarnings").invoke(remoteConnection);
                    } catch (Throwable ignored) {
                        // clear 失败由 drainWarnings 兜底（不丢已取到的消息）
                    }
                }
            ),
        };
    }

    /** 采集 ResultSet 行列（读满 maxRows 即停，truncated 标记供格式化层提示）。 */
    private static McpExplainFormatter.RawResult collect(Object resultSet, int maxRows) throws Exception {
        Class<?> rsClass = Class.forName(REMOTE_RESULT_SET);
        Object meta = rsClass.getMethod("getMetaData").invoke(resultSet);
        int columnCount = (Integer) Class.forName(REMOTE_META).getMethod("getColumnCount").invoke(meta);
        List<String> headers = new ArrayList<>();
        for (int col = 1; col <= columnCount; col++) {
            Object label = Class.forName(REMOTE_META).getMethod("getColumnLabel", Integer.TYPE)
                .invoke(meta, col);
            headers.add(label instanceof String s ? s : "col" + col);
        }
        Method nextMethod = rsClass.getMethod("next");
        Method getString = rsClass.getMethod("getString", Integer.TYPE);
        Method wasNull = rsClass.getMethod("wasNull");
        List<List<String>> rows = new ArrayList<>();
        boolean truncated = false;
        while ((Boolean) nextMethod.invoke(resultSet)) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            List<String> row = new ArrayList<>();
            for (int col = 1; col <= columnCount; col++) {
                Object raw = getString.invoke(resultSet, col);
                String value = raw instanceof String s ? s : null;
                if (value == null && (Boolean) wasNull.invoke(resultSet)) {
                    row.add("");
                } else {
                    row.add(value != null ? value : "");
                }
            }
            rows.add(row);
        }
        try {
            rsClass.getMethod("close").invoke(resultSet);
        } catch (Throwable ignored) {
            // 关闭失败不影响已采集数据（runCatching 语义）
        }
        LOG.info("McpDbExecutor: collected " + rows.size() + " rows (truncated=" + truncated
            + "), columns=" + headers.size());
        return new McpExplainFormatter.RawResult(headers, rows, truncated);
    }

    /**
     * 方法弹性查找：优先声明接口 primaryInterface（T-Embed-10：Method 一律从声明接口拿，
     * 跨插件 classloader 用实现类反射会抛 IllegalAccessException），243/262 声明层差异时
     * 回落 fallbackInterface。
     */
    private static Method findInterfaceMethod(Object target, String primaryInterface, String fallbackInterface,
                                              String name) throws Exception {
        try {
            return Class.forName(primaryInterface).getMethod(name);
        } catch (Throwable primaryFailure) {
            try {
                return Class.forName(fallbackInterface).getMethod(name);
            } catch (Throwable ignored) {
                // 两个声明层都没有 → 从实例类拿（照 DatabaseSchemaResolver 的失败即空语义，此处应不可达）
                return target.getClass().getMethod(name);
            }
        }
    }

    /** 数据源清单（反射 DataSourceManager.getManagers(project) → getDataSources()）。 */
    private static List<Object> dataSources(Project project) {
        try {
            Class<?> managerClass = Class.forName(MANAGER_CLASS);
            Object managersRaw = managerClass.getMethod("getManagers", Project.class).invoke(null, project);
            List<?> managers = managersRaw instanceof List<?> list ? list : List.of();
            List<Object> out = new ArrayList<>();
            for (Object manager : managers) {
                if (manager == null) {
                    continue;
                }
                Object listRaw = managerClass.getMethod("getDataSources").invoke(manager);
                if (!(listRaw instanceof List<?> list)) {
                    continue;
                }
                for (Object item : list) {
                    if (item != null) {
                        out.add(item);
                    }
                }
            }
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
