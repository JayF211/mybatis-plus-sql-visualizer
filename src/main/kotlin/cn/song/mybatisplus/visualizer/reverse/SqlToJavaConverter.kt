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
     * 生成代码：候选非 null → 按设置形态生成 lambda 版本；null → 字符串列名 QueryWrapper。
     *
     * @param format 设置页形态配置（QUERY 形态的调用方应跳过实体查找直接传 null 候选）
     */
    fun generateCode(
        result: SqlReverseResult,
        candidate: EntityCandidate?,
        format: ReverseWrapperFormat,
    ): String {
        if (candidate == null) return JavaCodeGenerator.generate(result)
        val wrapper = resolveWrapperFormat(format, hasEntity = true)
        val target = JavaCodeGenerator.EntityTarget(
            className = candidate.psiClass.name ?: JavaCodeGenerator.entityClassName(result.tableName),
            columnToField = candidate.tableInfo.columns.associate { it.columnName to it.fieldName },
        )
        return JavaCodeGenerator.generate(result, target, wrapper)
    }
}
