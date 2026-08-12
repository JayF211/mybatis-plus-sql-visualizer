package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType

/**
 * LambdaQueryWrapper 转换器（F-01）：委托 [QueryWrapperParser] 实现。
 *
 * 无状态单例；表信息通过 [convert] 参数传入，符合 SPI 契约。
 */
object LambdaQueryConverter : WrapperConverter {

    override val supportedTypes: Set<WrapperType> = setOf(WrapperType.LAMBDA_QUERY)

    override fun convert(chain: WrapperChain, table: TableInfo): SqlStatement =
        QueryWrapperParser(table).parse(chain)
}
