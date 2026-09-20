package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import com.intellij.openapi.project.Project

/**
 * MPJLambdaQueryConverter（F-12/P17）：MPJLambdaQueryWrapper → SELECT。
 *
 * MPJLambdaQueryWrapper 使用 lambda 列（`select(Entity::getX)`、`eq(...)`）且无联表，
 * 转换语义与 [LambdaQueryConverter] 一致（委托 [QueryWrapperParser]）。
 *
 * T-M20-04 增补 `selectIgnore`（MPJLambdaQueryWrapper 独有）：selectAll 全列展开后剔除列。
 * 无 selectIgnore 时保持纯委托（零开销）；有则前置收集忽略列集合，过滤后把剩余链交给
 * [QueryWrapperParser]，再按 `全列 - 忽略列` 产出显式列清单。
 */
object MpjLambdaQueryConverter : WrapperConverter {

    override val supportedTypes: Set<WrapperType> = setOf(WrapperType.MPJ_LAMBDA_QUERY)

    override fun convert(chain: WrapperChain, table: TableInfo, project: Project?): SqlStatement {
        val ignoreCalls = chain.calls.filter { it.methodName == "selectIgnore" }
        // 无 selectIgnore：纯委托（既有行为）
        if (ignoreCalls.isEmpty()) return QueryWrapperParser(table).parse(chain)

        // 剥离 selectIgnore 调用后委托 QueryWrapperParser（其余方法不受影响）
        val base = QueryWrapperParser(table)
            .parse(chain.copy(calls = chain.calls.filterNot { it.methodName == "selectIgnore" }))
        // selectIgnore 仅对 selectAll 全列展开（selectColumns 为空）生效；表无列时无从剔除（文档化边界）
        if (base.selectColumns.isNotEmpty() || table.columns.isEmpty()) return base
        val ignored = ignoreCalls.flatMap { call -> call.args.mapNotNull { ignoredColumnName(it) } }.toSet()
        return base.copy(selectColumns = (table.allColumnNames - ignored).map { ColumnRef(columnName = it) })
    }

    /**
     * selectIgnore 实参 → 列名（归一化）：ColumnArg（lambda 形态 `Entity::getX`）取 columnName；
     * ValueArg（字符串形态）取字面量（MPJ 字符串列可能带主表别名 `t.`）。
     */
    private fun ignoredColumnName(arg: WrapperArg): String? = when (arg) {
        is WrapperArg.ColumnArg -> normalizeIgnored(arg.column.columnName)
        is WrapperArg.ValueArg -> (arg.value as? SqlValue.Literal)?.unquoted?.let(::normalizeIgnored)
        else -> null
    }

    /** 归一化忽略列名：去空白、AS 别名后缀、主表别名前缀（`"t.station_id"` / `"t.station_id AS device_id"` → `station_id`）。 */
    private fun normalizeIgnored(name: String): String {
        var core = name.trim()
        core = core.substringBefore(" AS ").substringBefore(" as ").trim()
        return core.removePrefix("t.")
    }
}
