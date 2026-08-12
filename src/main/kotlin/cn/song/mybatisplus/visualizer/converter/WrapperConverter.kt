package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType

/**
 * Wrapper 转换器 SPI（T-M4-01，F-01/09/10/11/12 扩展点）。
 *
 * 每个 Wrapper 类型一个实现；新增类型只需实现本接口并注册进 [ConverterRegistry]，
 * UI 层（SqlPreviewService）无需改动即可接入（F-20 可扩展性要求）。
 */
interface WrapperConverter {

    /** 该转换器支持的 Wrapper 类型集合。 */
    val supportedTypes: Set<WrapperType>

    /**
     * 将 Wrapper 调用链转换为 SQL 语句模型。
     *
     * @param chain 调用链（PSI 解析产物）
     * @param table 实体解析的表信息
     * @return 语句模型（交给 SqlGenerator 渲染）
     */
    fun convert(chain: WrapperChain, table: TableInfo): SqlStatement
}

/**
 * 转换器注册表：按 Wrapper 类型分发到具体转换器。
 *
 * 新增 Wrapper 类型时在此追加实现（如 MPJ 联表、字符串版 QueryWrapper）。
 */
object ConverterRegistry {

    /** 全部已注册转换器（顺序即优先级；supportedTypes 互斥，顺序无实际影响）。 */
    val converters: List<WrapperConverter> = listOf(
        LambdaQueryConverter,
        LambdaUpdateConverter,
        LambdaDeleteConverter,
        MpjLambdaConverter,
        MpjQueryConverter,
        MpjLambdaQueryConverter,
    )

    /**
     * 转换调用链。
     *
     * @param chain 调用链
     * @param table 表信息
     * @return 语句模型；无匹配转换器时返回 null
     */
    fun convert(chain: WrapperChain, table: TableInfo): SqlStatement? =
        converters.firstOrNull { chain.wrapperType in it.supportedTypes }?.convert(chain, table)
}

// ---------------------------------------------------------------------------
// 共享实参解析辅助（各 converter 复用；对应 psi 层 WrapperArg 产物）
// ---------------------------------------------------------------------------

/**
 * 实参转列：ColumnArg 直接取；ValueArg(字面量) 视为字符串列名（MP 字符串字段版）。
 * 字符串列取 unquoted（如 `t.id AS device_id` 的 `t.id`），避免带引号。
 */
fun WrapperArg?.asColumn(): ColumnRef? = when (this) {
    is WrapperArg.ColumnArg -> column
    is WrapperArg.ValueArg -> (value as? SqlValue.Literal)?.let { ColumnRef(columnName = it.unquoted) }
    else -> null
}

/**
 * 实参转值。
 */
fun WrapperArg?.asValue(): SqlValue? = (this as? WrapperArg.ValueArg)?.value

/**
 * 为列引用补表别名（MPJ 主表场景），已有别名时保持不变。
 */
fun ColumnRef.withTableAlias(alias: String): ColumnRef =
    if (tableAlias == null) copy(tableAlias = alias) else this
