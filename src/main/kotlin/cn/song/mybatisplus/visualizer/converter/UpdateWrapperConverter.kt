package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType

/**
 * 字符串版 UpdateWrapper 转换器（F-17，清单 U09）。
 *
 * 真实项目（cyzc-platform）字符串版更新集中在优惠券/裂变域，四种形态：
 *  - `new UpdateWrapper<>()` + `setSql("use_total = use_total + 1")` + `eq("id", v)` + `apply`/`last`
 *  - 内联 `update(new UpdateWrapper<X>().eq("id", v).setSql(...))`
 *  - `mapper.update(null, new UpdateWrapper<X>()...)`
 *  - `wrapper.lambda()` 动态转换为 lambda 形态（psi 层 `WrapperLocator.buildChain` 已并入链）
 *
 * set/setSql 解析复用 [LambdaUpdateConverter]（其 `parseSet` 的 `asColumn` 支持字符串列），
 * 条件解析复用 [QueryWrapperParser]（`argAsColumn` 字符串列原样透传）。
 */
object UpdateWrapperConverter : WrapperConverter {

    override val supportedTypes: Set<WrapperType> = setOf(WrapperType.UPDATE)

    override fun convert(chain: WrapperChain, table: TableInfo): SqlStatement = LambdaUpdateConverter.convert(chain, table)
}
