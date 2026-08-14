package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.SetClause
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.psi.currentDialect

/**
 * UpdateJoinWrapper 转换器（T-M21-08，MPJ 官方联表更新，1.4.5+，原 P3-01 提前）：
 * `UPDATE user t LEFT JOIN address t1 ON (t1.user_id = t.id) SET t.name = ?, t.update_by = ?, t1.tel = ? WHERE (t.id = ?)`
 * （SET 列可跨表带别名，对照 MPJ 官方输出结构）。
 *
 * 链解析（对照 MPJ 官方 API）：
 *  - `set(col, val)` / `set(boolean, col, val)`（含 setIfExists，T-M21-09 归一）→ SET 赋值，列带主表别名
 *  - `setSql(sql[, args])` / `setApply(sql[, args])` → 原始 SQL 片段（`{i}` 绑定参数替换）
 *  - `setIncrBy/setDecrBy(col, n)` → `col = col + n`（raw 形态）
 *  - `setUpdateEntity(entity)` / `setUpdateEntityAndNull(entity)` → 主表非主键全列 `t.col = ?`（静态近似）
 *  - `leftJoin/innerJoin/rightJoin/fullJoin` → 联表子句（复用 [MpjJoinSupport.parseJoin] 核心形态）
 *  - eq/ge/.../apply/last 等条件 → [QueryWrapperParser]（主表别名前缀注入）
 *  - `setAlias("x")` → 主表别名（默认 "t"）；构造第二参别名同样生效
 *
 * 已知简化（KDoc 标注，与官方输出对照后的文档化边界）：
 *  - **跨表 SET 的列归属静态不可判**：方法引用列（ColumnArg）统一按主表别名近似
 *    （`set(UserEntity::getName, v)` → `t.name = ?`）；字符串列已带表别名限定
 *    （`set("t1.tel", v)` → `t1.tel = ?`）时原样保留；
 *  - setUpdateEntity/setUpdateEntityAndNull 的实体字段值运行时不可知 → 主表非主键全列
 *    `t.col = ?` 占位近似（官方语义：setUpdateEntity 仅非 null 字段，AndNull 含 null 字段）；
 *  - 链终结点 `update()/update(T)/updateAndNull()/updateAndNull(T)`（1.4.10+）由
 *    WrapperLocator 剔除出链（updateAndNull 语义同 setUpdateEntityAndNull 近似，实体字段
 *    静态不可得，无额外 SQL 差异）。
 */
object UpdateJoinConverter : WrapperConverter {

    /** MPJ 主表别名默认值（setAlias / 构造别名未指定时）。 */
    private const val MAIN_ALIAS_DEFAULT = "t"

    override val supportedTypes: Set<WrapperType> = setOf(WrapperType.UPDATE_JOIN)

    override fun convert(chain: WrapperChain, table: TableInfo): SqlStatement {
        // 主表别名：构造别名 > setAlias("x") 方法（官方 API）> 默认 t
        val mainAlias = chain.constructorAlias
            ?: chain.calls.firstOrNull { it.methodName == SET_ALIAS }
                ?.args?.firstOrNull()?.asValue()?.let { (it as? SqlValue.Literal)?.unquoted }
            ?: MAIN_ALIAS_DEFAULT

        // SET 赋值（按源码顺序；setUpdateEntity 展开为多列）
        val sets = chain.calls
            .filter { it.methodName in SET_METHODS }
            .flatMap { parseSet(it, table, mainAlias) }

        // 联表子句（渲染于 FROM 之后、SET 之前）
        val joins = chain.calls
            .filter { it.methodName in MpjJoinSupport.JOIN_METHODS }
            .mapNotNull { MpjJoinSupport.parseJoin(it, mainAlias) }

        // 条件部分：剔除 set/join/setAlias/last 后复用 Query 解析（columnPrefix=主别名注入；
        // 字符串列条件如 eq("t1.xxx", v) 原样透传，天然支持跨表条件）
        val conditionCalls = chain.calls.filterNot {
            it.methodName in SET_METHODS || it.methodName in MpjJoinSupport.JOIN_METHODS
                || it.methodName == SET_ALIAS || it.methodName == LAST
        }
        val conditions = QueryWrapperParser(table, mainAlias)
            .parse(WrapperChain(chain.wrapperType, chain.entityClass, conditionCalls))
            .conditions

        // last 非 limit 尾缀（U05 语义对齐）：`last("AND (... >= 0)")` 原样追加到语句末尾
        val rawTail = chain.calls
            .filter { it.methodName == LAST }
            .mapNotNull { (it.args.firstOrNull().asValue() as? SqlValue.Literal)?.unquoted }
            .firstOrNull { !it.startsWith("limit", ignoreCase = true) }

        return UpdateStatement(table, sets, conditions, rawTail, tableAlias = mainAlias, joins = joins)
    }

    /**
     * 解析 set 类调用为赋值子句（T-M21-08）。
     *
     * @return 赋值列表（setUpdateEntity 展开为多列；解析失败返回空列表）
     */
    private fun parseSet(call: WrapperCall, table: TableInfo, mainAlias: String): List<SetClause> = when (call.methodName) {
        SET, SET_IF_EXISTS -> {
            // 布尔条件前缀剥离：`set(StrUtil.isNotBlank(x), col, val)` / `set(true, col, val)`
            val args = call.args
            val startIndex = if (args.size >= 3 && isFlagArgument(args[0])) 1 else 0
            val column = args.getOrNull(startIndex).asColumn() ?: return emptyList()
            val value = args.getOrNull(startIndex + 1).asValue() ?: return emptyList()
            listOf(SetClause(column = withMainAliasIfUnqualified(column, mainAlias), value = value))
        }
        SET_SQL, SET_APPLY -> {
            // 布尔条件前缀剥离（同 set）：`setSql(condition, "col = expr")`
            val args = call.args
            val startIndex = if (args.size >= 2 && isFlagArgument(args[0])) 1 else 0
            val raw = args.getOrNull(startIndex).asValue() as? SqlValue.Literal ?: return emptyList()
            var sql = raw.unquoted
            // `{i}` 绑定参数替换：`setSql("frozen_amount = COALESCE(frozen_amount, 0) - {0}", allocated)`
            for ((index, value) in args.drop(startIndex + 1).withIndex()) {
                sql = sql.replace("{$index}", renderSetValue(value.asValue() ?: continue))
            }
            listOf(SetClause(rawSql = sql))
        }
        // setIncrBy(col, n) → `col = col + n`；setDecrBy → `col = col - n`（3.5.6+ 官方 API）
        SET_INCR, SET_DECR -> {
            val args = call.args
            val startIndex = if (args.size >= 3 && isFlagArgument(args[0])) 1 else 0
            val column = args.getOrNull(startIndex).asColumn() ?: return emptyList()
            val value = args.getOrNull(startIndex + 1).asValue() ?: return emptyList()
            // T-M23.5：列名按方言引用（联表 setIncrBy/setDecrBy 解析期拼 rawSql）
            val qualified = withMainAliasIfUnqualified(column, mainAlias).qualifiedName(currentDialect())
            val op = if (call.methodName == SET_INCR) "+" else "-"
            listOf(SetClause(rawSql = "$qualified = $qualified $op ${renderSetValue(value)}"))
        }
        // setUpdateEntity / setUpdateEntityAndNull：实体字段静态不可得 → 主表非主键全列占位近似
        SET_UPDATE_ENTITY, SET_UPDATE_ENTITY_AND_NULL ->
            table.columns
                .filter { !it.isId }
                .map { SetClause(column = ColumnRef(tableAlias = mainAlias, columnName = it.columnName), value = SqlValue.Placeholder("?")) }
        else -> emptyList()
    }

    /**
     * 列归属近似（T-M21-08，KDoc 标注）：ColumnArg（方法引用）统一补主表别名；
     * 字符串列已含表别名限定（含 '.'，如 `t1.tel`）时原样保留（显式跨表 SET），
     * 否则补主表别名。
     */
    private fun withMainAliasIfUnqualified(column: ColumnRef, mainAlias: String): ColumnRef =
        if (column.tableAlias == null && !column.columnName.contains('.')) column.withTableAlias(mainAlias) else column

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
    private const val SET_IF_EXISTS = "setIfExists" // T-M21-09：MPJ 1.4.9+，「值存在才 set」→ 归一为 set
    private const val SET_SQL = "setSql"
    private const val SET_APPLY = "setApply"
    private const val SET_INCR = "setIncrBy"
    private const val SET_DECR = "setDecrBy"
    private const val SET_UPDATE_ENTITY = "setUpdateEntity"
    private const val SET_UPDATE_ENTITY_AND_NULL = "setUpdateEntityAndNull"
    private const val SET_ALIAS = "setAlias"
    private const val LAST = "last"

    /** SET 类方法全集（set / setSql / setApply / setIncrBy / setDecrBy / setUpdateEntity* / setIfExists）。 */
    private val SET_METHODS = setOf(
        SET, SET_IF_EXISTS, SET_SQL, SET_APPLY, SET_INCR, SET_DECR, SET_UPDATE_ENTITY, SET_UPDATE_ENTITY_AND_NULL,
    )
}
