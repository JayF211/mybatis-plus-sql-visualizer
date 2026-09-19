// T-SyncK2JMaster309 自 master sql/DataSourceUrlProbe.kt 移植
package cn.song.mybatisplus.visualizer.sql;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * T-DialectAutoInfer：IDE 数据源 URL 探测（DatabaseTools API，Ultimate-only）。
 *
 * <p>链路（262.3 javap 实锤，plugins/DatabaseTools/lib/modules/intellij.database*.jar）：
 * <ul>
 *  <li>`DataSourceManager.getManagers(project)` → 各 manager `getDataSources()` → RawDataSource</li>
 *  <li>`RawDataSource` 经 `DasDataSource.getConnectionConfig()` 得 `RawConnectionConfig.getUrl()`</li>
 * </ul>
 * 全程反射（IPGP 裁剪/Community 无 DatabaseTools 时类缺失 → runCatching 返回 null，静默降级）。
 * 只读探测，无 EDT 约束（与 {@code cn.song.mybatisplus.visualizer.execute.DialectPreMapping} 的
 * 读探测同类）；调用方通常已在后台线程。
 */
public final class DataSourceUrlProbe {

    private static final Logger LOG = Logger.getInstance(DataSourceUrlProbe.class);

    private DataSourceUrlProbe() {}

    /**
     * 取项目数据源列表中首个非空 URL（多数据源取列表序首个——DataSourceManager 列表序即
     * 用户面板展示序；尚无「最近使用」元数据可依，一期不深究）。
     * 探测失败（类缺失/反射异常/无数据源）→ null。
     */
    @Nullable
    public static String firstUrl(@Nullable Project project) {
        try {
            if (project == null || project.isDisposed()) return null;
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
