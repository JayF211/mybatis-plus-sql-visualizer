package cn.song.mybatisplus.visualizer.reverse

import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.psi.EntityClassFinder
import cn.song.mybatisplus.visualizer.psi.EntityResolver
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass

/**
 * SQL → Java 转换共享编排（T-M36.4）。
 *
 * 把 T-M36 系列的转换链路（parse → 实体查找 → 形态分发 → 生成）抽为公共组件，
 * 供两个入口复用（行为单一来源，防漂移）：
 *  - [cn.song.mybatisplus.visualizer.actions.ConvertSqlToJavaAction]（编辑器选中 SQL）；
 *  - [cn.song.mybatisplus.visualizer.log.SqlToJavaPanel]（工具窗口粘贴 SQL，T-M36.4）。
 *
 * 编排约定：parse / generateCode 为纯函数（EDT 安全）；findCandidates 访问 PSI 索引，
 * 必须在后台 ReadAction 内调用（2025.3+ 慢操作守卫）。
 */
object SqlToJavaConverter {

    /** 实体候选（类 + 已解析表信息；PSI 访问完成后仅持纯数据）。 */
    data class EntityCandidate(
        val psiClass: PsiClass,
        val tableInfo: TableInfo,
    )

    /** 纯解析（可能抛 [SqlReverseException]）。 */
    fun parse(sqlText: String): SqlReverseResult = SqlReverseParser.parse(sqlText)

    /** 错误偏移量 → 行号（1 起）。 */
    fun errorLine(sqlText: String, offset: Int): Int = SqlReverseParser.lineOf(sqlText, offset)

    /** 实体查找（须在 ReadAction 内；表名来自解析结果）。 */
    fun findCandidates(project: Project, tableName: String): List<EntityCandidate> =
        EntityClassFinder(project).find(tableName).map { clazz ->
            EntityCandidate(clazz, EntityResolver().resolve(clazz))
        }

    /**
     * 副表实体候选（T-M37 P1，须在 ReadAction 内调用）：按 join 表名查找**全部**匹配实体
     * （复用 [findCandidates]：EntityClassFinder.find + EntityResolver.resolve），
     * 键 = SQL 表别名（无别名的 join 不归属——P1 默认别名规则；未找到 → 空列表）。
     * 面板层按 joins 顺序逐表分发：多候选弹 [SqlSelectionDialog]，单候选直取，零候选回落。
     */
    fun findSubEntityCandidates(
        project: Project,
        joins: List<ReverseJoin>,
    ): Map<String, List<EntityCandidate>> {
        val result = mutableMapOf<String, List<EntityCandidate>>()
        for (join in joins) {
            val alias = join.alias ?: continue
            result[alias] = findCandidates(project, join.tableName)
        }
        return result
    }

    /**
     * 副表实体查找（T-M37 多表 JOIN，须在 ReadAction 内调用）：按 join 表名查找并解析，
     * 键 = SQL 表别名（无别名的 join 不归属——P1 默认别名规则）；副表取首个匹配。
     * 重构为复用 [findSubEntityCandidates] 取 first（行为与签名保持向后兼容）。
     */
    fun findSubEntities(
        project: Project,
        joins: List<ReverseJoin>,
    ): Map<String, JavaCodeGenerator.EntityTarget> {
        val result = mutableMapOf<String, JavaCodeGenerator.EntityTarget>()
        for ((alias, candidates) in findSubEntityCandidates(project, joins)) {
            val first = candidates.firstOrNull() ?: continue
            result[alias] = JavaCodeGenerator.EntityTarget(
                className = first.psiClass.name ?: continue,
                columnToField = first.tableInfo.columns.associate { it.columnName to it.fieldName },
            )
        }
        return result
    }

    /**
     * 生成代码：候选非 null → 按设置形态生成 lambda 版本；null → 字符串列名形态
     * （QUERY → new QueryWrapper；WRAPPERS → Wrappers.<E>query()，T-M36.6）。
     * T-M37：多表 JOIN → 强制 MPJ 形态（主表实体可用 → MPJLambdaWrapper；否则 MPJQueryWrapper）。
     *
     * @param format      设置页形态配置（QUERY 形态的调用方应跳过实体查找直接传 null 候选）
     * @param invocation  T-M36.7：调用上下文（Service/Mapper + 可选 VO），null → 纯表达式
     * @param subEntities T-M37：联表副实体（别名 → 实体），多表列归属用
     */
    fun generateCode(
        result: SqlReverseResult,
        candidate: EntityCandidate?,
        format: ReverseWrapperFormat,
        invocation: JavaCodeGenerator.InvocationTarget? = null,
        subEntities: Map<String, JavaCodeGenerator.EntityTarget> = emptyMap(),
    ): String {
        val isJoin = result.joins.isNotEmpty()
        val wrapper = when {
            isJoin && candidate != null -> GeneratedWrapper.MPJ_LAMBDA
            isJoin -> GeneratedWrapper.MPJ_QUERY
            else -> resolveWrapperFormat(format, hasEntity = candidate != null)
        }
        val target = candidate?.let {
            JavaCodeGenerator.EntityTarget(
                className = it.psiClass.name ?: JavaCodeGenerator.entityClassName(result.tableName),
                columnToField = it.tableInfo.columns.associate { col -> col.columnName to col.fieldName },
                subEntities = subEntities,
            )
        }
        return JavaCodeGenerator.generate(result, target, wrapper, invocation)
    }
}
