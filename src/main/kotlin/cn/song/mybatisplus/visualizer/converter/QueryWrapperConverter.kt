package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType

/**
 * 字符串版 QueryWrapper 转换器（F-17，清单 Q03/Q09/Q18）。
 *
 * `QueryWrapper` 的列与条件均为**字符串字段版**（`eq("merchant_id", v)`、`select("DATE(...) as ...")`、
 * `groupBy("DATE(create_time), amount_direction")`）——由 psi 层解析为 [cn.song.mybatisplus.visualizer.model.WrapperArg.ValueArg]
 * 字面量，[QueryWrapperParser] 的 `argAsColumn`/`columnArgs` 已支持字符串列原样透传（含 SQL 表达式与别名），
 * 本转换器仅需注册 [WrapperType.QUERY] 类型并委托解析。
 */
object QueryWrapperConverter : WrapperConverter {

    override val supportedTypes: Set<WrapperType> = setOf(WrapperType.QUERY)

    override fun convert(chain: WrapperChain, table: TableInfo): SqlStatement = QueryWrapperParser(table).parse(chain)
}
