package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.SetClause
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType

/**
 * LambdaUpdateWrapper 转换器（F-09）：`UPDATE 表 SET col = ? ... WHERE ...`。
 *
 * 处理：
 *  - `set(Entity::getX, value)` → [SetClause]（列 + 值）
 *  - `set(boolean, Entity::getX, value)` → 布尔条件前缀剥离（U03，T-M15-04）
 *  - `setSql("col = expr")` → [SetClause]（原始 SQL）
 *  - `setSql("col = expr {0}", value)` → `{i}` 绑定参数替换（U04-③，T-M15-04）
 *  - `last("AND ...")` → 非 limit 尾缀透传（U05，T-M15-04；如防超发兜底条件）
 *  - 其余调用（eq 等）复用 [QueryWrapperParser] 解析为 WHERE 条件
 *
 * 无 WHERE 条件时 conditions 为空，UI 层需警示"全表更新"风险。
 */
object LambdaUpdateConverter : WrapperConverter {

    override val supportedTypes: Set<WrapperType> = setOf(WrapperType.LAMBDA_UPDATE)

    override fun convert(chain: WrapperChain, table: TableInfo): SqlStatement {
        val sets = chain.calls
            .filter { it.methodName in SET_METHODS }
            .mapNotNull { parseSet(it) }

        // 条件部分：过滤 set/setSql/setIncrBy/setDecrBy/last 调用后复用 Query 解析（QueryWrapperParser 忽略未知方法，天然兼容）
        val conditionCalls = chain.calls.filterNot { it.methodName in SET_METHODS || it.methodName == LAST }
        val conditions = QueryWrapperParser(table)
            .parse(WrapperChain(chain.wrapperType, chain.entityClass, conditionCalls))
            .conditions

        // last 非 limit 尾缀（U05）：`last("AND (last_total - n >= 0)")` 原样追加到语句末尾
        val rawTail = chain.calls
            .filter { it.methodName == LAST }
            .mapNotNull { (it.args.firstOrNull().asValue() as? SqlValue.Literal)?.unquoted }
            .firstOrNull { !it.startsWith("limit", ignoreCase = true) }

        return UpdateStatement(table, sets, conditions, rawTail)
    }

    /**
     * 解析 set / setSql / setIncrBy / setDecrBy / setIfExists 调用为赋值子句（T-M15-04 起支持布尔条件前缀与 `{i}` 参数；
     * T-M21-09：setIfExists（MPJ 1.4.9+，「值存在才 set」的运行时语义）归一为 set 处理——静态渲染普通赋值）。
     */
    private fun parseSet(call: WrapperCall): SetClause? = when (call.methodName) {
        SET, SET_IF_EXISTS -> {
            val args = call.args
            // 布尔条件前缀剥离（U03）：`set(StrUtil.isNotBlank(x), col, val)` / `set(true, col, val)`
            val startIndex = if (args.size >= 3 && isFlagArgument(args[0])) 1 else 0
            val column = args.getOrNull(startIndex).asColumn() ?: return null
            val value = args.getOrNull(startIndex + 1).asValue() ?: return null
            SetClause(column = column, value = value)
        }
        SET_SQL -> {
            val args = call.args
            // 布尔条件前缀剥离（同 set）：`setSql(condition, "col = expr")`
            val startIndex = if (args.size >= 2 && isFlagArgument(args[0])) 1 else 0
            val raw = args.getOrNull(startIndex).asValue() as? SqlValue.Literal ?: return null
            var sql = raw.unquoted
            // `{i}` 绑定参数替换（U04-③）：`setSql("frozen_amount = COALESCE(frozen_amount, 0) - {0}", allocated)`
            for ((index, value) in args.drop(startIndex + 1).withIndex()) {
                sql = sql.replace("{$index}", renderSetValue(value.asValue() ?: continue))
            }
            SetClause(rawSql = sql)
        }
        // setIncrBy(col, val) → SET col = col + val；setDecrBy → SET col = col - val（3.5.6+ 官方 API）
        SET_INCR, SET_DECR -> {
            val args = call.args
            val startIndex = if (args.size >= 3 && isFlagArgument(args[0])) 1 else 0
            val column = args.getOrNull(startIndex).asColumn() ?: return null
            val value = args.getOrNull(startIndex + 1).asValue() ?: return null
            val op = if (call.methodName == SET_INCR) "+" else "-"
            SetClause(rawSql = "${column.qualifiedName} = ${column.qualifiedName} $op ${renderSetValue(value)}")
        }
        else -> null
    }

    /** 首参是否为布尔条件前缀（true/false 字面量或运行时布尔表达式占位符）；列引用（ColumnArg）恒为列。 */
    private fun isFlagArgument(arg: WrapperArg): Boolean {
        if (arg is WrapperArg.ColumnArg) return false
        val value = arg.asValue()
        return when (value) {
            is SqlValue.Literal -> value.unquoted == "true" || value.unquoted == "false"
            is SqlValue.Placeholder -> true
            else -> false
        }
    }

    /** setSql 绑定参数渲染：字面量原样（raw），运行时值渲染为 `?`。 */
    private fun renderSetValue(value: SqlValue): String = when (value) {
        is SqlValue.Literal -> value.raw
        is SqlValue.Placeholder -> "?"
    }

    private const val SET = "set"
    private const val SET_SQL = "setSql"
    private const val SET_INCR = "setIncrBy"
    private const val SET_DECR = "setDecrBy"
    private const val SET_IF_EXISTS = "setIfExists" // T-M21-09：MPJ 1.4.9+，「值存在才 set」→ 归一为 set
    private const val LAST = "last"

    /** SET 类方法全集（set / setSql / setIncrBy / setDecrBy / setIfExists）。 */
    private val SET_METHODS = setOf(SET, SET_SQL, SET_INCR, SET_DECR, SET_IF_EXISTS)
}
