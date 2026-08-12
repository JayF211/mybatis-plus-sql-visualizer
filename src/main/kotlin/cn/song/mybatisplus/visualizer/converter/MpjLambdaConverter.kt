package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.util.NamingStrategy

/**
 * MPJLambdaWrapper 转换器（F-11）：联表 SELECT。
 *
 * 支持：
 *  - `selectAs(Entity::getX, "alias")` → SELECT t.x AS alias
 *  - `leftJoin/innerJoin/rightJoin(Xxx.class, on(col1, col2))` → JOIN 子句
 *  - `eq` 等条件 / `orderByAsc` 等结构 → 委托 [QueryWrapperParser]（主表别名 `t`）
 *
 * 已知简化（T-M4-06 后续对齐 MPJ 官方行为）：
 *  - 主表别名固定 `t`（MPJ 惯例）；关联表别名 = 实体类名首字母小写
 *  - 关联表列名按 snake_case 兜底推断（不解析关联实体注解）
 *  - `on` 仅支持两列相等比较；`on(wrapper)` 条件形式留待增强
 */
object MpjLambdaConverter : WrapperConverter {

    /** MPJ 主表别名惯例。 */
    private const val MAIN_ALIAS = "t"

    override val supportedTypes: Set<WrapperType> = setOf(WrapperType.MPJ_LAMBDA)

    override fun convert(chain: WrapperChain, table: TableInfo): SqlStatement {
        val selectAsCalls = chain.calls.filter { it.methodName == SELECT_AS }
        val joinCalls = chain.calls.filter { it.methodName in JOIN_METHODS }

        // 其余调用（eq/select/orderBy 等）委托 Query 解析，列自动带主表别名 t.
        val restCalls = chain.calls.filterNot { it.methodName == SELECT_AS || it.methodName in JOIN_METHODS }
        val base = QueryWrapperParser(table, MAIN_ALIAS)
            .parse(WrapperChain(chain.wrapperType, chain.entityClass, restCalls))

        // selectAs 列合并（追加在主表 select 列之后）
        val selectColumns = base.selectColumns + selectAsCalls.mapNotNull { parseSelectAs(it) }
        val joins = joinCalls.mapNotNull { parseJoin(it) }

        return base.copy(
            tableAlias = MAIN_ALIAS,
            selectColumns = selectColumns,
            joins = joins,
        )
    }

    /**
     * 解析 selectAs(Entity::getX, "alias") → 带别名的主表列。
     */
    private fun parseSelectAs(call: WrapperCall): ColumnRef? {
        val column = call.args.getOrNull(0).asColumn() ?: return null
        val alias = (call.args.getOrNull(1).asValue() as? SqlValue.Literal)?.unquoted ?: return null
        return ColumnRef(tableAlias = MAIN_ALIAS, columnName = column.columnName, alias = alias)
    }

    /**
     * 解析 join(Xxx.class, on(col1, col2)) → 联表子句。
     *
     * 关联表信息由类名推断：表名 = snake_case(类名)，别名 = 类名首字母小写。
     * on 条件：col1 归属主表（t.），col2 归属关联表（alias.）。
     */
    private fun parseJoin(call: WrapperCall): JoinClause? {
        val type = when (call.methodName) {
            "leftJoin" -> JoinType.LEFT
            "innerJoin" -> JoinType.INNER
            else -> JoinType.RIGHT
        }
        val tableRef = call.args.getOrNull(0).asValue() as? SqlValue.Literal ?: return null
        val className = tableRef.raw.removeSuffix(".class").trim()
        if (className.isEmpty()) return null

        val tableName = NamingStrategy.toSnakeCase(className)
        val alias = className.replaceFirstChar { it.lowercaseChar() }

        // on 参数：on(col1, col2)（psi 层解析为 OnConditionArg）
        val onArgs = call.args.drop(1)
        val onConditions = onArgs.mapNotNull { arg ->
            (arg as? WrapperArg.OnConditionArg)?.let {
                Condition.ColumnComparison(
                    left = it.left.withTableAlias(MAIN_ALIAS),
                    right = it.right.withTableAlias(alias),
                )
            }
        }

        return JoinClause(type, tableName, alias, onConditions)
    }

    private const val SELECT_AS = "selectAs"
    private val JOIN_METHODS = setOf("leftJoin", "innerJoin", "rightJoin")
}
