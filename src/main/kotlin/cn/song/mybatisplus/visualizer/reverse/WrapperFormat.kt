package cn.song.mybatisplus.visualizer.reverse

/**
 * SQL 反向转换的 Wrapper 形态配置（T-M36.3 + T-M36.6 Wrappers 工厂形态）。
 *
 * 设置页「默认 Wrapper 形态」驱动生成结果：
 *  - [AUTO]（默认）：有实体 → LambdaQueryWrapper；无实体 → QueryWrapper（T-M36.2 既有行为）；
 *  - [QUERY]：恒字符串列名 QueryWrapper（跳过实体查找，P0 行为）；
 *  - [LAMBDA]：有实体 → LambdaQueryWrapper；无实体回落 QueryWrapper；
 *  - [WRAPPERS]（T-M36.6）：MP 官方静态工厂形态——有实体 → `Wrappers.<E>lambdaQuery()` +
 *    lambda 列；无实体 → `Wrappers.<E>query()` + 字符串列（`com.baomidou.mybatisplus.core.toolkit.Wrappers`，
 *    jar 实证）；MPJ 工厂（JoinWrappers/MPJWrappers 的 lambdaJoin/queryJoin）暂不纳入本形态，
 *    多表 JOIN 支持（T-M37）时再评估；
 *  - [MPJ_LAMBDA]：有实体 → MPJLambdaWrapper（MyBatis-Plus-Join 形态，lambda 列引用——
 *    单表 SQL 也允许生成 MPJ wrapper，用户项目风格统一；**MPJ 联表 SQL（JOIN）反向仍为
 *    P1+ 范围**，parser 明确报错）；无实体回落 QueryWrapper（MPJQueryWrapper 字符串形态
 *    需要实体类构造参数，单表无实体场景无意义）。
 *
 * 未来扩展位：MPJ 联表反向落地时新增 `MPJ_QUERY`（字符串列名 MPJQueryWrapper）与
 * `MPJ_LAMBDA` 的联表条件渲染，本枚举为设置持久化值（字符串），向后兼容新增项。
 */
enum class ReverseWrapperFormat(val persistedValue: String) {
    AUTO("AUTO"),
    QUERY("QUERY"),
    LAMBDA("LAMBDA"),
    WRAPPERS("WRAPPERS"),
    MPJ_LAMBDA("MPJ_LAMBDA");

    companion object {
        fun fromPersisted(value: String?): ReverseWrapperFormat =
            entries.firstOrNull { it.persistedValue == value } ?: AUTO
    }
}

/**
 * 最终生成的 Wrapper 形态（[resolveWrapperFormat] 的产物；T-M37 新增 MPJ_QUERY）。
 */
enum class GeneratedWrapper { QUERY, LAMBDA, WRAPPERS_QUERY, WRAPPERS_LAMBDA, MPJ_LAMBDA, MPJ_QUERY }

/**
 * 形态分发（纯函数，可单测）：配置 + 是否有可用实体 → 最终生成形态。
 * LAMBDA / MPJ_LAMBDA / WRAPPERS 无实体时回落 QUERY（保持「任何 SQL 都能转换」的零降级体验）。
 */
fun resolveWrapperFormat(format: ReverseWrapperFormat, hasEntity: Boolean): GeneratedWrapper = when (format) {
    ReverseWrapperFormat.AUTO -> if (hasEntity) GeneratedWrapper.LAMBDA else GeneratedWrapper.QUERY
    ReverseWrapperFormat.QUERY -> GeneratedWrapper.QUERY
    ReverseWrapperFormat.LAMBDA -> if (hasEntity) GeneratedWrapper.LAMBDA else GeneratedWrapper.QUERY
    ReverseWrapperFormat.WRAPPERS -> if (hasEntity) GeneratedWrapper.WRAPPERS_LAMBDA else GeneratedWrapper.WRAPPERS_QUERY
    ReverseWrapperFormat.MPJ_LAMBDA -> if (hasEntity) GeneratedWrapper.MPJ_LAMBDA else GeneratedWrapper.QUERY
}
