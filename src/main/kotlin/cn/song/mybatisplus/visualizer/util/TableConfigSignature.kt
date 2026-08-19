package cn.song.mybatisplus.visualizer.util

import cn.song.mybatisplus.visualizer.config.PluginSettings

/**
 * 影响表名/列名解析结果的配置签名（T-M19.4 双维度失效——配置签名维度）。
 *
 * 字段：推断策略 / 忽略字段名 / 添加表前缀 / 添加表后缀 / 移除表前缀 / 移除表后缀。
 * 设置变更后缓存失效（文件戳不变的场景，如设置页修改后同文件再次预览）。
 *
 * 共享于 [cn.song.mybatisplus.visualizer.psi.WrapperCache]（经 SqlPreviewService）与
 * [cn.song.mybatisplus.visualizer.psi.EntityResolver] 的解析缓存——统一来源防漂移。
 */
object TableConfigSignature {

    /**
     * 当前全局配置签名（设置服务不可用时回退默认值，恒返回非 null）。
     */
    @JvmStatic
    fun projectSignature(): String {
        val s = runCatching { PluginSettings.getInstance().state }.getOrNull()
        return listOf(
            s?.tableNameInference ?: PluginSettings.INFERENCE_SNAKE,
            s?.ignoredFields ?: "",
            s?.tablePrefix ?: "",
            s?.tableSuffix ?: "",
            s?.removeTablePrefix ?: "",
            s?.removeTableSuffix ?: "",
        ).joinToString("|")
    }
}
