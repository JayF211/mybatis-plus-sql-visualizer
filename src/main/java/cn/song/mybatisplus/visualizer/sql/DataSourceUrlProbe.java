// T-SyncK2JMaster309 自 master sql/DataSourceUrlProbe.kt 移植；T-SyncK2JMaster92 补 T-PS5-03 单槽 TTL 缓存
package cn.song.mybatisplus.visualizer.sql;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * T-DialectAutoInfer：IDE 数据源 URL 探测（DatabaseTools API，Ultimate-only）。
 *
 * <p>链路（262.3 javap 实锤，plugins/DatabaseTools/lib/modules/intellij.database*.jar）：
 * <ul>
 *  <li>{@code DataSourceManager.getManagers(project)} → 各 manager {@code getDataSources()} → RawDataSource</li>
 *  <li>{@code RawDataSource} 经 {@code DasDataSource.getConnectionConfig()} 得 {@code RawConnectionConfig.getUrl()}</li>
 * </ul>
 * 全程反射（IPGP 裁剪/Community 无 DatabaseTools 时类缺失 → runCatching 返回 null，静默降级）。
 * 只读探测，无 EDT 约束（与 {@code cn.song.mybatisplus.visualizer.execute.DialectPreMapping} 的
 * 读探测同类）；调用方通常已在后台线程。
 *
 * <p>T-PS5-03：结果加<b>单槽 TTL 缓存</b>——方言智能推断开启后单次预览会多次走
 * {@code DialectRegistry.effective}（报告实测约 7 次），每次探测都是一串反射调用；URL 本身是
 * 低频变更数据，TTL 窗口内复用即可。失效双保险：
 * <ol>
 *  <li>{@link #invalidate}——agent 会话建立/断开事件点清（{@code log.agent.AgentManager}）；</li>
 *  <li>TTL 上界（默认 5s）——数据源编辑后<b>至多一个 TTL 窗口内方言滞后</b>（可接受：预览是只读展示）。</li>
 * </ol>
 * 槽位持项目<b>弱引用</b>（不因缓存滞留已关闭项目），项目身份不同即重探。
 */
public final class DataSourceUrlProbe {

    private static final Logger LOG = Logger.getInstance(DataSourceUrlProbe.class);

    /** 单槽缓存条目（项目弱引用 + 结果 + 时间戳）。 */
    private static final class UrlSlot {
        final WeakReference<Project> project;
        final String url;
        final long atMs;

        UrlSlot(WeakReference<Project> project, String url, long atMs) {
            this.project = project;
            this.url = url;
            this.atMs = atMs;
        }
    }

    /** T-PS5-03：TTL 上界（毫秒）。测试注入短窗驱动过期语义。 */
    public static volatile long ttlMs = 5_000L;

    /** T-PS5-03 测试缝：时间源（默认系统时钟）。 */
    public static volatile LongSupplier clockMs = System::currentTimeMillis;

    /** T-PS5-03 测试缝：探测替身（默认 null = 走真实反射链）。 */
    public static volatile Function<Project, String> probeForTest = null;

    /** T-PS5-03 测试缝：真实探测调用计数（断言 TTL 窗口内只探测一次）。 */
    public static final AtomicInteger probeCount = new AtomicInteger(0);

    private static volatile UrlSlot slot = null;

    private DataSourceUrlProbe() {}

    /**
     * 取项目数据源列表中首个非空 URL（多数据源取列表序首个——DataSourceManager 列表序即
     * 用户面板展示序；尚无「最近使用」元数据可依，一期不深究）。
     * 探测失败（类缺失/反射异常/无数据源）→ null。TTL 窗口内命中缓存不再探测。
     */
    @Nullable
    public static String firstUrl(@Nullable Project project) {
        if (project == null) return null;
        long now = clockMs.getAsLong();
        UrlSlot cached = slot;
        if (cached != null && cached.project.get() == project && now - cached.atMs <= ttlMs) {
            return cached.url;
        }
        String url = probeFirstUrl(project);
        slot = new UrlSlot(new WeakReference<>(project), url, now);
        return url;
    }

    /**
     * T-PS5-03：缓存失效（agent 会话建立/断开事件点调用）。
     * {@code project == null} 或与本槽项目同身份时清槽（单槽语义：异项目槽本就不可命中）。
     */
    public static void invalidate(@Nullable Project project) {
        UrlSlot cached = slot;
        if (cached == null) return;
        if (project == null || cached.project.get() == project) slot = null;
    }

    /** T-PS5-03 测试缝：无条件清槽（用例隔离）。 */
    public static void invalidateAllForTest() {
        slot = null;
    }

    /** 真实探测（反射链，失败静默 null）；{@link #probeForTest} 注入时走替身。 */
    @Nullable
    private static String probeFirstUrl(@NotNull Project project) {
        probeCount.incrementAndGet();
        Function<Project, String> probe = probeForTest;
        if (probe != null) return probe.apply(project);
        try {
            if (project.isDisposed()) return null;
            List<Object> managers = dataSourceManagers(project);
            if (managers == null) return null;
            for (Object manager : managers) {
                // manager 为反射拿到的任意类型（Object），统一走反射取 getDataSources()
                Object raw = invoke(manager, "getDataSources");
                if (!(raw instanceof List)) continue;
                List<?> dataSources = (List<?>) raw;
                for (Object ds : dataSources) {
                    if (ds == null) continue;
                    Object config = invoke(ds, "getConnectionConfig");
                    if (config == null) continue;
                    Object urlRaw = invoke(config, "getUrl");
                    String url = urlRaw instanceof String ? (String) urlRaw : null;
                    if (url != null && !url.isBlank()) return url;
                }
            }
            return null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** `DataSourceManager.getManagers(project)` 反射调用；类缺失/异常 → null。 */
    @Nullable
    @SuppressWarnings("unchecked")
    private static List<Object> dataSourceManagers(@NotNull Project project) {
        Class<?> managerClass;
        try {
            managerClass = Class.forName(
                    "com.intellij.database.psi.DataSourceManager", true, DataSourceUrlProbe.class.getClassLoader());
        } catch (Throwable e) {
            return null;
        }
        Object managers;
        try {
            managers = managerClass.getMethod("getManagers", Project.class).invoke(null, project);
        } catch (Throwable e) {
            return null;
        }
        if (!(managers instanceof List)) return null;
        return (List<Object>) managers;
    }

    @Nullable
    private static Object invoke(@NotNull Object target, @NotNull String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable e) {
            LOG.debug("DataSourceUrlProbe." + method + " 反射失败（" + target.getClass().getName() + "）", e);
            return null;
        }
    }
}
