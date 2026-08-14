package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.DialectRegistry

/**
 * T-M23.5：psi 层共享的当前方言读取（EntityResolver.inferTableName 同款模式）——
 * 设置服务不可用时兜底 MySQL（测试环境零影响）。
 *
 * 供批量文本模板（[BatchOperationConverter]）与解析期 raw 拼接
 * （[LambdaUpdateConverter] / [UpdateJoinConverter] / [QueryWrapperParser]）做保留字引用。
 */
internal fun currentDialect(): Dialect =
    runCatching { DialectRegistry.byName(PluginSettings.getInstance().state.dialect) }
        .getOrDefault(DialectRegistry.byName(null))
