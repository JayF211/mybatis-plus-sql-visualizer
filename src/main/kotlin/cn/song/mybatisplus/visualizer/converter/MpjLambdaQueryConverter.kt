package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType

/**
 * MPJLambdaQueryConverter（F-12/P17）：MPJLambdaQueryWrapper → SELECT。
 *
 * MPJLambdaQueryWrapper 使用 lambda 列（`select(Entity::getX)`、`eq(...)`）且无联表，
 * 转换语义与 [LambdaQueryConverter] 一致（委托 [QueryWrapperParser]）。
 */
object MpjLambdaQueryConverter : WrapperConverter {

    override val supportedTypes: Set<WrapperType> = setOf(WrapperType.MPJ_LAMBDA_QUERY)

    override fun convert(chain: WrapperChain, table: TableInfo): SqlStatement =
        QueryWrapperParser(table).parse(chain)
}
