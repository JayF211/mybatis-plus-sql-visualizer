package cn.song.mybatisplus.visualizer.util;

import cn.song.mybatisplus.visualizer.psi.EntityClassFinder;
import cn.song.mybatisplus.visualizer.psi.EntityResolver;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * 插件缓存统一管理（设置页「清除缓存」入口）。
 *
 * 统一语义：清除/统计**插件全部**项目级缓存——当前含：
 *  - 实体反查缓存（[EntityClassFinder] 的 @TableName 反查结果，项目级 userData）
 *  - 实体解析缓存（[EntityResolver] 的三维缓存 T-EntityCache，全局静态）
 *  - Wrapper 解析缓存（[SqlPreviewService] 的 WrapperCache，项目服务实例）
 * 后续新增任何缓存类型在此注册（clearAll 加一行），设置页无需改动。
 *
 * 统计口径（修复「缓存信息恒 0 B」）：反查缓存仅在快路径未命中时才填充，单独统计恒为 0——
 * 展示用 [totalSizeBytes]/[entryCount] 覆盖全部三类；解析缓存为全局静态，多项目只计一次。
 */
public final class PluginCacheManager {

    private PluginCacheManager() {
    }

    /** 清除指定项目全部插件缓存（下次使用自动重建）。 */
    public static void clearAll(@NotNull Project project) {
        EntityClassFinder.Companion.clearReverseLookupCache(project);
        EntityResolver.Companion.clearCache();
        // T-Perf-18：补齐此前漏收的缓存——Jpa/Hbm 实体解析与 SqlPerfAnalyzer/动态表名
        // （clearAll 未收录时设置页「清除缓存」清不干净）
        cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver.clearCache();
        cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlEntityResolver.clearCache();
        cn.song.mybatisplus.visualizer.sql.SqlPerfAnalyzer.clearCache();
        cn.song.mybatisplus.visualizer.sql.interceptor.DynamicTableNameProcessor.clearCache();
        // Wrapper 解析缓存挂在项目服务上：getServiceIfCreated 未创建则跳过（清除动作不得触发服务实例化）
        SqlPreviewService previewService = project.getServiceIfCreated(SqlPreviewService.class);
        if (previewService != null) {
            previewService.clearCache();
        }
    }

    /** 项目级反查缓存大小（原口径保留；设置页汇总展示用 totalSizeBytes）。 */
    public static long sizeBytes(@NotNull Project project) {
        return EntityClassFinder.Companion.reverseLookupCacheSizeBytes(project);
    }

    /**
     * 缓存大小估算（全部缓存类型合计——设置页展示）：
     * 项目级反查缓存 + 全局实体解析缓存（跨项目共享，只计一次）+ 各项目 Wrapper 解析缓存
     * （服务未创建视为 0）。
     */
    public static long totalSizeBytes(@NotNull Collection<Project> projects) {
        long total = 0L;
        for (Project project : projects) {
            total += sizeBytes(project) + wrapperSizeBytes(project);
        }
        return total + EntityResolver.cacheSizeBytes();
    }

    /** 缓存条目数（设置页展示——缓存确实在积累的直观信号）。 */
    public static int entryCount(@NotNull Collection<Project> projects) {
        int count = 0;
        for (Project project : projects) {
            count += EntityClassFinder.Companion.reverseLookupCacheInfo(project).getFirst() + wrapperEntryCount(project);
        }
        return count + EntityResolver.cacheSize();
    }

    /** 缓存大小格式化（B / KB / MB——设置页展示用）。 */
    @NotNull
    public static String formatSize(long bytes) {
        if (bytes >= 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        if (bytes >= 1024) return String.format("%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }

    private static long wrapperSizeBytes(@NotNull Project project) {
        SqlPreviewService service = project.getServiceIfCreated(SqlPreviewService.class);
        return service != null ? service.wrapperCacheSizeBytes() : 0L;
    }

    private static int wrapperEntryCount(@NotNull Project project) {
        SqlPreviewService service = project.getServiceIfCreated(SqlPreviewService.class);
        return service != null ? service.wrapperCacheEntryCount() : 0;
    }
}
