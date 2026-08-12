package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.SetClause
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType

/**
 * LambdaUpdateWrapper 转换器（F-09）：`UPDATE 表 SET col = ? ... WHERE ...`。
 *
 * 处理：
 *  - `set(Entity::getX, value)` → [SetClause]（列 + 值）
 *  - `setSql("col = expr")` → [SetClause]（原始 SQL）
 *  - 其余调用（eq 等）复用 [QueryWrapperParser] 解析为 WHERE 条件
 *
 * 无 WHERE 条件时 conditions 为空，UI 层需警示"全表更新"风险。
 */
object LambdaUpdateConverter : WrapperConverter {

    override val supportedTypes: Set<WrapperType> = setOf(WrapperType.LAMBDA_UPDATE)

    override fun convert(chain: WrapperChain, table: TableInfo): SqlStatement {
        val sets = chain.calls
            .filter { it.methodName == SET || it.methodName == SET_SQL }
            .mapNotNull { parseSet(it) }

        // 条件部分：过滤 set 调用后复用 Query 解析（QueryWrapperParser 忽略未知方法，天然兼容）
        val conditionCalls = chain.calls.filterNot { it.methodName == SET || it.methodName == SET_SQL }
        val conditions = QueryWrapperParser(table)
            .parse(WrapperChain(chain.wrapperType, chain.entityClass, conditionCalls))
            .conditions

        return UpdateStatement(table, sets, conditions)
    }

    /**
     * 解析 set / setSql 调用为赋值子句。
     */
    private fun parseSet(call: WrapperCall): SetClause? = when (call.methodName) {
        SET -> {
            val column = call.args.getOrNull(0).asColumn() ?: return null
            val value = call.args.getOrNull(1).asValue() ?: return null
            SetClause(column = column, value = value)
        }
        SET_SQL -> {
            val raw = call.args.getOrNull(0).asValue() as? SqlValue.Literal ?: return null
            SetClause(rawSql = raw.unquoted)
        }
        else -> null
    }

    private const val SET = "set"
    private const val SET_SQL = "setSql"
}
