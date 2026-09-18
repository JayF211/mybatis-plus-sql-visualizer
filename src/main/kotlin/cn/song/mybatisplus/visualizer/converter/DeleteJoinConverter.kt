package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import com.intellij.openapi.project.Project

/**
 * DeleteJoinWrapper 转换器（T-M21-08，MPJ 官方联表删除，1.4.5+，原 P3-01 提前）：
 * `DELETE t,t1 FROM user t LEFT JOIN address t1 ON (t1.user_id = t.id) WHERE (t.id = ?)`
 * （对照 MPJ 官方输出结构）。
 *
 * 删除目标控制（MPJ 官方 API）：
 *  - `delete(Class...)/delete(String...)` → 删除目标表别名列表：Class 实参 → 类名首字母小写别名
 *    （主表实体类 → 主别名，如 `delete(UserEntity.class)` → "t"；关联表类 → 其 join 别名）；
 *    String 实参 → 原样（如 "t1"）
 *  - `deleteAll()` → 主别名 + 全部 join 别名
 *  - 无 delete 调用 → 默认仅主别名（`DELETE t FROM ...`）
 *
 * 已知简化（KDoc 标注）：
 *  - **全逻辑删除表自动转 UPDATE SET del=1**（MPJ 官方对 @TableLogic 表的删除改写）不在本任务
 *    范围（文档化边界，见 docs 开发计划 T-M21-08）；语句仍按 DELETE 渲染；
 *  - 关联表别名与 UpdateJoinConverter 一致：[MpjJoinSupport.parseJoin] 核心 ON 形态
 *    （三参 / 四参带别名 / on(col1,col2) / lambda ON / 字符串 SQL join）。
 */
object DeleteJoinConverter : WrapperConverter {

    /** MPJ 主表别名默认值（setAlias / 构造别名未指定时）。 */
    private const val MAIN_ALIAS_DEFAULT = "t"

    override val supportedTypes: Set<WrapperType> = setOf(WrapperType.DELETE_JOIN)

    override fun convert(chain: WrapperChain, table: TableInfo, project: Project?): SqlStatement {
        // 主表别名：构造别名 > setAlias("x") 方法（官方 API）> 默认 t
        val mainAlias = chain.constructorAlias
            ?: chain.calls.firstOrNull { it.methodName == SET_ALIAS }
                ?.args?.firstOrNull()?.asValue()?.let { (it as? SqlValue.Literal)?.unquoted }
            ?: MAIN_ALIAS_DEFAULT

        // 联表子句（渲染于 FROM 之后、WHERE 之前；deleteAll 的目标列表依赖 join 别名）
        // T-FS-04：project 透传至类名兜底推断
        val joins = chain.calls
            .filter { it.methodName in MpjJoinSupport.JOIN_METHODS }
            .mapNotNull { MpjJoinSupport.parseJoin(it, mainAlias, project) }

        // 删除目标表（delete/deleteAll 控制删除哪些表）
        val deleteTargets = parseDeleteTargets(chain, table, mainAlias, joins)

        // 条件部分：剔除 join/setAlias/delete 后复用 Query 解析（columnPrefix=主别名注入）
        val conditionCalls = chain.calls.filterNot {
            it.methodName in MpjJoinSupport.JOIN_METHODS || it.methodName == SET_ALIAS
                || it.methodName == DELETE || it.methodName == DELETE_ALL
        }
        val conditions = QueryWrapperParser(table, mainAlias)
            .parse(WrapperChain(chain.wrapperType, chain.entityClass, conditionCalls))
            .conditions

        return DeleteStatement(table, conditions, deleteTargets, tableAlias = mainAlias, joins = joins)
    }

    /**
     * 解析删除目标表别名列表（T-M21-08）：
     *  - `delete(Class... classes)`：Class 实参 → 类名首字母小写别名；主表实体类 → 主别名
     *    （`delete(UserEntity.class)` 语义为删除主表行，其 SQL 别名是主别名 "t"，非 "userEntity"）；
     *  - `delete(String... tables)`：字符串实参 → 原样（表别名，如 "t1"）；
     *  - `deleteAll()`：主别名 + 全部 join 别名；
     *  - 无 delete 调用 → 默认主别名。
     * 多调用 / 重复目标去重（distinct 保序）。
     */
    private fun parseDeleteTargets(
        chain: WrapperChain,
        table: TableInfo,
        mainAlias: String,
        joins: List<cn.song.mybatisplus.visualizer.model.JoinClause>,
    ): List<String> {
        val deleteCalls = chain.calls.filter { it.methodName == DELETE || it.methodName == DELETE_ALL }
        if (deleteCalls.isEmpty()) return listOf(mainAlias)

        val targets = mutableListOf<String>()
        for (call in deleteCalls) {
            if (call.methodName == DELETE_ALL) {
                targets += mainAlias
                targets += joins.map { it.alias }
                continue
            }
            for (arg in call.args) {
                val literal = (arg.asValue() as? SqlValue.Literal) ?: continue
                // Class 实参（`UserEntity.class`，raw 无引号）与 String 实参（`"t1"`，raw 带引号）区分：
                // psi 层字符串字面量 raw 带单引号（见 SqlValue.Literal KDoc），类字面量 raw 为裸类名
                val isClass = !literal.raw.startsWith("'") && !literal.raw.startsWith("\"")
                val name = if (isClass) literal.raw.removeSuffix(".class").trim() else literal.unquoted
                if (name.isEmpty()) continue
                targets += if (isClass) {
                    if (name == table.entityClassName || name == chain.entityClass) {
                        mainAlias
                    } else {
                        name.replaceFirstChar { it.lowercaseChar() }
                    }
                } else {
                    name
                }
            }
        }
        return targets.distinct()
    }

    private const val SET_ALIAS = "setAlias"
    private const val DELETE = "delete"
    private const val DELETE_ALL = "deleteAll"
}
