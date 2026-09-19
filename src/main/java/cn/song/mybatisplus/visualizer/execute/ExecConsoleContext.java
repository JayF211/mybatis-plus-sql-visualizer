// T-SyncK2JMaster309 自 execute/ExecConsoleContext.kt 移植
package cn.song.mybatisplus.visualizer.execute;

import com.intellij.database.console.JdbcConsole;
import com.intellij.database.model.ObjectKind;
import com.intellij.database.util.ObjectPath;
import com.intellij.database.util.SearchPath;
import org.jetbrains.annotations.Nullable;

/**
 * T-ConsoleDbContext：预览控制台的<b>库上下文</b>对齐与诊断。
 *
 * <p>两个职责（用户 20260917 定案 A+B）：</p>
 * <ul>
 *  <li><b>B 对齐</b>：预览会话默认以「数据源 URL 里的库」为准（{@link #alignToDataSourceDatabase}）——
 *      DBN 的「文件→数据源」映射与历史记忆会把 exec 文件钉在别的库上（真机事故：文件被映射到
 *      {@code mpj_demo@localhost}，而 SQL 属于 {@code jpa_demo}，导致 {@code Unknown column 'u.username'}）；</li>
 *  <li><b>A 诊断</b>：{@link #describeContext} 把「当前库 / 数据源 / 数据源默认库」拼成一行，供错误提示
 *      携带——裸 MySQL 报文（1054 之类）看不出是库选错了。</li>
 * </ul>
 *
 * <p><b>隔离约定</b>：本对象直接引用 DBN 类，只允许在 Database 插件链内调用（{@link DataSourceSelector}
 * 的 attach/reuse 与错误提取点），Community 下永不加载（同 {@code DbTableSpecAdapter} 惯例）。
 * 全部入口包 runCatching，任何反射/平台异常都退化为「不改动、不附加」，绝不影响主链。</p>
 *
 * <p>API 出处（243 平台 jar 反编译实证，非猜测）：</p>
 * <ul>
 *  <li>{@code JdbcConsole.getDataSource(): LocalDataSource}、{@code getSearchPath(): SearchPath}、
 *      {@code setSearchPath(SearchPath)}、{@code switchSchema(SearchPath, boolean)}</li>
 *  <li>{@code SearchPath.of(ObjectPath)}、{@code SearchPath.getCurrent()}、{@code ObjectPath.ROOT}、
 *      {@code ObjectPath.append(String, ObjectKind)}、{@code ObjectNamePart.name}（public final 字段）</li>
 * </ul>
 */
public final class ExecConsoleContext {

    private static final com.intellij.openapi.diagnostic.Logger LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(ExecConsoleContext.class);

    /** T-SI-03 ①：URL 未指定库时的诊断标记（预览头/错误上下文可见，不弹窗）。 */
    public static final String UNDETERMINED_MARKER = "未能确定默认库";

    /** T-SI-03 ②：强设后读回不一致的诊断标记。 */
    public static final String READBACK_MISMATCH_MARKER = "库上下文对齐未生效";

    public static final ExecConsoleContext INSTANCE = new ExecConsoleContext();
    private ExecConsoleContext() {}

    /** B：把控制台当前库对齐到数据源 URL 里的库；返回一行诊断描述（失败也不抛）。 */
    public static String alignToDataSourceDatabase(@Nullable Object console) {
        try {
            if (!(console instanceof JdbcConsole)) {
                return "console=" + (console != null ? console.getClass().getName() : "null") + "（非 JdbcConsole，跳过对齐）";
            }
            JdbcConsole c = (JdbcConsole) console;
            com.intellij.database.dataSource.LocalDataSource ds = c.getDataSource();
            String current = c.getSearchPath() != null && c.getSearchPath().getCurrent() != null
                    ? c.getSearchPath().getCurrent().name : null;
            String target = JdbcUrlDatabase.nameFromUrl(ds != null ? ds.getUrl() : null);
            if (target == null) {
                // T-SI-03 ①：URL 无库不再静默跳过——目标库无从判定，如实标注当前库（诊断可见化，
                // 不阻塞执行）。旧实现直接「跳过对齐」返回，真机「文件映射把会话钉在别的库」时
                // 日志与错误上下文里都看不到任何线索。
                return undeterminedDiagnostic(ds != null ? ds.getUrl() : null, current);
            }
            if (current != null && current.equalsIgnoreCase(target)) {
                return "库已一致：" + target;
            }
            SearchPath path = SearchPath.of(ObjectPath.ROOT.append(target, ObjectKind.DATABASE));
            // switchSchema 带事务/越界校验（字节码实证），首选；未生效则退回纯状态设置
            try {
                c.switchSchema(path, false);
            } catch (Throwable ignored) {
                // runCatching 语义
            }
            boolean strongSet = false;
            if (!equalsIgnoreCaseOrNull(nameOrNull(c), target)) {
                try {
                    c.setSearchPath(path);
                } catch (Throwable ignored) {
                    // runCatching 语义
                }
                strongSet = true;
            }
            String after = nameOrNull(c);
            String namespace = null;
            try {
                namespace = c.getCurrentNamespace() != null ? c.getCurrentNamespace().name : null;
            } catch (Throwable ignored) {
                // getOrNull 语义
            }
            // T-SI-03 ②：强设后读回校验——setSearchPath 是纯状态设置（不校验目标库存在性），
            // 读回与目标不一致必须醒目告警并附实际库；v1 只告警不阻断（只读场景不能被对齐失败拦死）。
            String mismatch = readBackMismatch(target, after);
            if (mismatch != null) {
                String diag = mismatchDiagnostic(target, after, namespace, ds != null ? ds.getName() : null, strongSet, mismatch);
                LOG.warn("[T-SI-03] " + diag);
                return diag;
            }
            return "库上下文对齐：" + (current != null ? current : "?") + " → " + (after != null ? after : "?")
                    + "（数据源=" + (ds != null ? ds.getName() : null) + "，URL 库=" + target + "）";
        } catch (Throwable e) {
            return "库上下文对齐跳过（" + e.getClass().getSimpleName() + ": " + e.getMessage() + "）";
        }
    }

    /** searchPath.current.name 的空安全读取（{@code c.searchPath?.current?.name}）。 */
    @Nullable
    private static String nameOrNull(JdbcConsole c) {
        return c.getSearchPath() != null && c.getSearchPath().getCurrent() != null
                ? c.getSearchPath().getCurrent().name : null;
    }

    /** {@code x?.equals(target, ignoreCase = true)}：x 为 null 时 false，否则忽略大小写比较。 */
    private static boolean equalsIgnoreCaseOrNull(@Nullable String x, @org.jetbrains.annotations.NotNull String target) {
        return x != null && x.equalsIgnoreCase(target);
    }

    /**
     * T-MysqlGStrip：控制台数据源的 JDBC URL（{@code \G} 剥离的方言判定输入）；取不到返回 null。
     * 走的是本对象既有的 {@code JdbcConsole.getDataSource().getUrl()} 面（{@link #alignToDataSourceDatabase}
     * 同源 API），失败一律 null → 调用方按「方言不可得 → 默认剥离」处理。
     */
    @Nullable
    public static String dataSourceUrl(@Nullable Object console) {
        try {
            return (console instanceof JdbcConsole) ? ((JdbcConsole) console).getDataSource().getUrl() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * T-SI-03 ②：读回校验（纯函数，默认 test 通道可覆盖）——切换/强设之后 searchPath 的当前
     * 节点必须等于目标库，否则返回不一致描述（一致返回 null）。大小写不敏感：MySQL 库名在
     * 不同平台的回读大小写不保证（Linux 服务端按库名大小写敏感，回读可能与 URL 写法不同）。
     *
     * <p>读回面为 DBN 会话状态（{@code getSearchPath().getCurrent()}）——243/262 的 JdbcConsole 与
     * DatabaseSession 均无同步语句 API（javap 实证），故 v1 不做服务端 {@code select current_database()}
     * 实读回，该面列 T-SI-05 真机验证项。</p>
     */
    @Nullable
    public static String readBackMismatch(@Nullable String target, @Nullable String current) {
        if (target == null || target.isBlank()) return null;
        if (current == null || current.isBlank()) return "读回为空（searchPath.current 取不到）";
        return current.equalsIgnoreCase(target) ? null : "读回不一致：searchPath.current=" + current;
    }

    /** T-SI-03 ①：URL 未指定库时的诊断文案（纯函数，默认 test 通道可覆盖）。 */
    @org.jetbrains.annotations.NotNull
    public static String undeterminedDiagnostic(@Nullable String url, @Nullable String current) {
        return UNDETERMINED_MARKER + "（数据源 URL 未指定库：" + (url != null ? url : "?") + "），当前=" + (current != null ? current : "?");
    }

    /** T-SI-03 ②：读回不一致的诊断文案（纯函数，默认 test 通道可覆盖）。 */
    @org.jetbrains.annotations.NotNull
    public static String mismatchDiagnostic(@org.jetbrains.annotations.NotNull String target,
                                            @Nullable String actual,
                                            @Nullable String namespace,
                                            @Nullable String dataSourceName,
                                            boolean strongSet,
                                            @org.jetbrains.annotations.NotNull String reason) {
        return READBACK_MISMATCH_MARKER + "（" + reason + "）：目标=" + target + "，实际=" + (actual != null ? actual : "?") + "，"
                + "命名空间=" + (namespace != null ? namespace : "?") + "，数据源=" + (dataSourceName != null ? dataSourceName : "?")
                + "（强设=" + strongSet + "）";
    }

    /**
     * A：错误提示附带的上下文行文案（纯函数，默认 test 通道可覆盖）。
     * T-SI-03 ①：{@code urlDb} 为空（URL 未指定库）时不再给「数据源默认库=?」的哑值，明说未能确定默认库。
     */
    @org.jetbrains.annotations.NotNull
    public static String contextLine(@Nullable String current, @Nullable String dataSourceName, @Nullable String urlDb) {
        if (urlDb == null) {
            return "[当前库=" + (current != null ? current : "?") + "，数据源=" + (dataSourceName != null ? dataSourceName : "?")
                    + "，" + UNDETERMINED_MARKER + "（URL 未指定库）]";
        }
        return "[当前库=" + (current != null ? current : "?") + "，数据源=" + (dataSourceName != null ? dataSourceName : "?")
                + "，数据源默认库=" + urlDb + "]";
    }

    /** A：错误提示附带的上下文行——「当前库 / 数据源 / 数据源默认库」。 */
    @Nullable
    public static String describeContext(@Nullable Object console) {
        try {
            if (!(console instanceof JdbcConsole)) return null;
            JdbcConsole c = (JdbcConsole) console;
            com.intellij.database.dataSource.LocalDataSource ds = c.getDataSource();
            String current = nameOrNull(c);
            if (current == null && c.getSearchPath() != null) {
                current = c.getSearchPath().getDisplayName();
            }
            return contextLine(current, ds != null ? ds.getName() : null,
                    JdbcUrlDatabase.nameFromUrl(ds != null ? ds.getUrl() : null));
        } catch (Throwable ignored) {
            return null;
        }
    }
}
