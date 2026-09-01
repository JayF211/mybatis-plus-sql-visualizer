package cn.song.mybatisplus.visualizer.util;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import org.jetbrains.annotations.NotNull;

/**
 * 影响表名/列名解析结果的配置签名（T-M19.4 双维度失效——配置签名维度）。
 *
 * 字段：推断策略 / 忽略字段名 / 添加表前缀 / 添加表后缀 / 移除表前缀 / 移除表后缀。
 * 设置变更后缓存失效（文件戳不变的场景，如设置页修改后同文件再次预览）。
 *
 * 共享于 [cn.song.mybatisplus.visualizer.psi.WrapperCache]（经 SqlPreviewService）与
 * [cn.song.mybatisplus.visualizer.psi.EntityResolver] 的解析缓存——统一来源防漂移。
 */
public final class TableConfigSignature {

    private TableConfigSignature() {
    }

    /**
     * 当前全局配置签名（设置服务不可用时回退默认值，恒返回非 null）。
     */
    @NotNull
    public static String projectSignature() {
        PluginSettings.State s = null;
        try {
            s = PluginSettings.getInstance().getState();
        } catch (Throwable ignored) {
            // settings service 未注册（测试环境）→ 兜底默认值
        }
        String tableNameInference = s != null ? s.getTableNameInference() : PluginSettings.INFERENCE_SNAKE;
        String ignoredFields = s != null ? s.getIgnoredFields() : "";
        String tablePrefix = s != null ? s.getTablePrefix() : "";
        String tableSuffix = s != null ? s.getTableSuffix() : "";
        String removeTablePrefix = s != null ? s.getRemoveTablePrefix() : "";
        String removeTableSuffix = s != null ? s.getRemoveTableSuffix() : "";
        return String.join("|",
                tableNameInference, ignoredFields, tablePrefix, tableSuffix, removeTablePrefix, removeTableSuffix);
    }
}
