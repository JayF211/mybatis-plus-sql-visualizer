package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import com.intellij.openapi.project.Project

/**
 * T-M23.5：psi 层共享的当前方言读取（EntityResolver.inferTableName 同款模式）——
 * 设置服务不可用时兜底 MySQL（测试环境零影响）。
 *
 * 供批量文本模板（[BatchOperationConverter]）与解析期 raw 拼接
 * （[LambdaUpdateConverter] / [UpdateJoinConverter] / [QueryWrapperParser]）做保留字引用。
 *
 * T-DialectAutoInfer：方言模式 auto 开启时走智能推断（agent 最近连接 > IDE 数据源 > MySQL 回退），
 * 手动模式按设置值；settings 不可用（测试环境）仍兜底 MySQL。
 * 手动值解析由 [DialectRegistry.effective] 内部完成，此处不再读 PluginSettings。
 */
internal fun currentDialect(project: Project? = null): Dialect =
    runCatching { DialectRegistry.effective(project = project) }
        .getOrDefault(DialectRegistry.byName(null))
