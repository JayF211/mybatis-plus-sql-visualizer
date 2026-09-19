package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import com.intellij.openapi.project.Project

/**
 * xbatis（原 mybatis-mp）QueryChain / UpdateChain / DeleteChain 转换器（XB，M44 其他 ORM 第一批，矩阵 §6）。
 *
 * 结构镜像 [LambdaQueryConverter] → [QueryWrapperParser] 委派：xbatis 条件方法名与 MP lambda
 * **高度重合**（javap cn.xbatis 1.10.8 `db.sql.api.cmd.executor.method.IConditionMethod` 与
 * 旧坐标 cn.mybatis-mp 1.8.1-RC3 实证：eq/ne/gt/lt/between/notBetween/in/notIn/isNull/isNotNull/
 * orderByAsc/orderByDesc/limit 与 MP 同名），差异方法名逐一归一化后委托 MP 解析器：
 *
 *  - `gte` / `lte`（xbatis 无 `ge`/`le` 短名）→ `ge` / `le`；
 *  - `like(LikeMode, col, v)`：xbatis 首参为 `db.sql.api.cmd.LikeMode` 枚举（ALL/LEFT/RIGHT）——
 *    映射 like / likeLeft / likeRight；`notLike` 同法；`iLike`/`notILike`（大小写不敏感）近似
 *    like/notLike（MySQL 默认 CI，方言差异注释见矩阵口径）；
 *  - `andNested(Consumer)` / `orNested(Consumer)` → `and` / `or`（嵌套 lambda 组）；
 *  - `empty/notEmpty`（xbatis 空串语义）→ 暂不建模（静默忽略，不产错误 SQL）；
 *  - `where(Getter, Function)` / `and(Getter, Function)` 复杂 Function 条件形态 → 暂不建模。
 *
 * 终结点（get/getOptional/list/cursor/count/exists/paging/execute）由 WrapperLocator
 * chainTerminalMethodNames 剔除，不入链。链家族语义分派：
 *  - UpdateChain：`set(...)` 家族调用（javap IUpdateSetMethod）→ [UpdateStatement]；
 *  - DeleteChain：WrapperLocator 置 deleteConsumed → [DeleteStatement]；
 *  - QueryChain（默认）→ [QueryWrapperParser] SELECT。
 *
 * 双坐标（cn.xbatis. / cn.mybatis-mp.）识别见 WrapperLocator（FQN 前缀 + 工厂简名双重门控）。
 */
object XbatisConverter : WrapperConverter {

    override val supportedTypes: Set<WrapperType> = setOf(WrapperType.XBATIS)

    /** xbatis LikeMode 枚举引用占位文本特征（`LikeMode.ALL` / `db.sql.api.cmd.LikeMode.LEFT`）。 */
    private const val LIKE_MODE_MARKER = "LikeMode"

    /** xbatis set 家族方法名（javap IUpdateSetMethod：`set(...)` 全重载）。 */
    private val SET_METHODS: Set<String> = setOf("set")

    override fun convert(chain: WrapperChain, table: TableInfo, project: Project?): SqlStatement {
        val calls = normalize(chain.calls)
        // 1. UpdateChain：set 调用存在 → UPDATE（set 列赋值 + 其余条件 WHERE）
        if (calls.any { it.methodName in SET_METHODS }) {
            return updateStatement(table, calls)
        }
        // 2. DeleteChain：条件链转 DELETE
        if (chain.deleteConsumed) {
            return DeleteStatement(table, parseConditions(table, calls))
        }
        // 3. QueryChain（默认）：SELECT（方法名归一后与 MP lambda 语义一致，直接委托）
        return QueryWrapperParser(table).parse(chain.copy(calls = calls))
    }

    /** 条件子集解析（嵌套链形态，复用 QueryWrapperParser 的条件树）。 */
    private fun parseConditions(table: TableInfo, calls: List<WrapperCall>): List<Condition> {
        val nested = WrapperChain(WrapperType.NESTED, table.entityClassName, calls)
        return QueryWrapperParser(table).parse(nested).conditions
    }

    // ------------------------------------------------------------------
    // 方法名归一化（xbatis → MP 解析器语义）
    // ------------------------------------------------------------------

    /**
     * 归一化 xbatis 差异方法名（见类 KDoc）；MP 同名方法原样保留。
     * like 族 LikeMode 首参移除并按模式归位 like/likeLeft/likeRight。
     */
    private fun normalize(calls: List<WrapperCall>): List<WrapperCall> =
        calls.flatMap { call ->
            when {
                call.methodName in LIKE_MODE_METHODS -> normalizeLikeMode(call)
                call.methodName == "gte" -> listOf(call.copy(methodName = "ge"))
                call.methodName == "lte" -> listOf(call.copy(methodName = "le"))
                call.methodName == "andNested" -> listOf(call.copy(methodName = "and"))
                call.methodName == "orNested" -> listOf(call.copy(methodName = "or"))
                call.methodName in UNSUPPORTED_METHODS -> emptyList()
                else -> listOf(call)
            }
        }

    /** xbatis like 族方法（javap IConditionMethod：like/notLike/iLike/notILike，首参 LikeMode）。 */
    private val LIKE_MODE_METHODS: Set<String> = setOf("like", "notLike", "iLike", "notILike")

    /** 暂不建模的 xbatis 方法（静默忽略——宁可少条件不可出错误 SQL）。 */
    private val UNSUPPORTED_METHODS: Set<String> = setOf("empty", "notEmpty")

    /**
     * like 族 LikeMode 首参归一化：`like(LikeMode.LEFT, col, v)` → `likeLeft(col, v)`。
     * 形态守卫：3 参且首参为非列引用（LikeMode 枚举引用占位）才按模式形态解析，
     * 其余形态（2 参直接 like）原样降级为 ANYWHERE。
     */
    private fun normalizeLikeMode(call: WrapperCall): List<WrapperCall> {
        val args = call.args
        if (args.size < 2) return listOf(call)
        val first = args.firstOrNull() as? WrapperArg.ValueArg
        val modeText = (first?.value as? SqlValue.Placeholder)?.source
            ?: (first?.value as? SqlValue.Literal)?.unquoted
        val hasMode = args.size >= 3 && modeText?.contains(LIKE_MODE_MARKER) == true
        val rest = if (hasMode) args.drop(1) else args
        val position = if (hasMode) likePositionOf(modeText.orEmpty()) else cn.song.mybatisplus.visualizer.model.LikePosition.ANYWHERE
        val baseName = when (call.methodName) {
            "iLike" -> "like"
            "notILike" -> "notLike"
            else -> call.methodName
        }
        val mapped = when (position) {
            cn.song.mybatisplus.visualizer.model.LikePosition.LEFT -> if (baseName == "like") "likeLeft" else "notLikeLeft"
            cn.song.mybatisplus.visualizer.model.LikePosition.RIGHT -> if (baseName == "like") "likeRight" else "notLikeRight"
            else -> baseName
        }
        return listOf(WrapperCall(mapped, rest))
    }

    /** LikeMode 枚举文本（ALL/LEFT/RIGHT，末段大写）→ LikePosition；未知值降级 ANYWHERE。 */
    private fun likePositionOf(modeText: String): cn.song.mybatisplus.visualizer.model.LikePosition =
        when (modeText.substringAfterLast('.').uppercase()) {
            "LEFT" -> cn.song.mybatisplus.visualizer.model.LikePosition.LEFT
            "RIGHT" -> cn.song.mybatisplus.visualizer.model.LikePosition.RIGHT
            else -> cn.song.mybatisplus.visualizer.model.LikePosition.ANYWHERE
        }

    // ------------------------------------------------------------------
    // UPDATE / DELETE 语句构建
    // ------------------------------------------------------------------

    /** UpdateChain set 调用 → SET 子句：`set(User::getName, "x")` → `SET user_name = 'x'`。 */
    private fun updateStatement(table: TableInfo, calls: List<WrapperCall>): SqlStatement {
        val sets = calls.filter { it.methodName in SET_METHODS }.mapNotNull { call ->
            val column = call.args.getOrNull(0).asColumn()?.columnName ?: return@mapNotNull null
            val value = call.args.getOrNull(1).asValue() ?: return@mapNotNull null
            cn.song.mybatisplus.visualizer.model.SetClause(column = ColumnRef(columnName = column), value = value)
        }
        val conditions = parseConditions(table, calls.filterNot { it.methodName in SET_METHODS })
        return UpdateStatement(table, sets, conditions)
    }
}
