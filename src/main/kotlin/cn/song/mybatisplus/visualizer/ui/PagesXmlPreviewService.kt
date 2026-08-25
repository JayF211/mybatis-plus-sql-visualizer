package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.psi.EntityResolver
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext
import cn.song.mybatisplus.visualizer.xml.PagesXmlParser
import cn.song.mybatisplus.visualizer.xml.PagesXmlParseException
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

/**
 * maintainPage 预览服务（F-45）：XML 文本 → 语句 → 预览模型 → [PreviewResult]。
 *
 * 纯函数（fileText 输入，无 PSI 依赖），gutter / Action 负责取文件文本与定位 maintainPage id；
 * 弹窗与面板完全复用 [SqlPreviewService.showResult] 链路。
 *
 * - [maintainPageId] 为 null 或未命中 → 取文件首个 maintainPage；
 * - 解析失败（非 <pages> / 非良构）→ 返回带 warning 的空结果（面板红色提示，不抛异常）；
 * - 参数对照表复用 [ParameterReplacer.buildParamTable]（`?` 占位符清单，顺序对应 `?`）；
 * - [project] 非 null 时解析 entityClass → 补全上下文（表名/列名/注释）。
 */
object PagesXmlPreviewService {

    fun preview(
        fileText: String,
        maintainPageId: String?,
        project: Project? = null,
    ): PreviewResult {
        val pagesModel = try {
            PagesXmlParser().parse(fileText)
        } catch (e: PagesXmlParseException) {
            return emptyResult(e.message ?: "XML 解析失败。")
        }

        val statement = pagesModel.statements.firstOrNull { it.id == maintainPageId }
            ?: pagesModel.statements.firstOrNull()
            ?: return emptyResult("未找到可预览的 SQL 语句（<pages> 中无 <maintainPage>）。")

        return toResult(statement, project)
    }

    /** PagesStatement → PreviewResult（纯 SQL 路径）。 */
    private fun toResult(
        statement: cn.song.mybatisplus.visualizer.xml.PagesStatement,
        project: Project?,
    ): PreviewResult {
        val sql = statement.sql
        val params = extractPlaceholders(sql)
        val entityContext = resolveEntityContext(statement.entityClass, project)
        return PreviewResult(
            sql = sql,
            compactSql = sql.replace(WHITESPACE_REGEX, " ").trim(),
            unresolvedParams = params,
            paramTable = ParameterReplacer.buildParamTable(params),
            wrapperType = null,
            tableName = entityContext?.tableName,
            columns = entityContext?.columns.orEmpty(),
            columnComments = entityContext?.columnComments.orEmpty(),
            tableComment = entityContext?.tableComment,
            warning = null,
            xmlModel = null,
            previewModel = null,
            pagesColumns = statement.columns.ifEmpty { null },
        )
    }

    /** 实体类推断：全限定名 → PSI findClass → EntityResolver → SqlCompletionContext。 */
    private fun resolveEntityContext(entityClass: String?, project: Project?): SqlCompletionContext? {
        if (entityClass.isNullOrBlank() || project == null) return null
        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(entityClass, GlobalSearchScope.allScope(project)) ?: return null
        val tableInfo = EntityResolver().resolve(psiClass) ?: return null
        return SqlCompletionContext(
            tableName = tableInfo.tableName,
            columns = tableInfo.allColumnNames,
            tableAlias = null,
            columnComments = tableInfo.columns.mapNotNull { col ->
                col.comment?.takeIf { it.isNotBlank() }?.let { col.columnName to it }
            }.toMap(),
            tableComment = tableInfo.comment,
        )
    }

    /** 提取 SQL 中 ? 占位符 → param1..N。 */
    private fun extractPlaceholders(sql: String): List<String> {
        val count = sql.count { it == '?' }
        return if (count == 0) emptyList() else (1..count).map { "param$it" }
    }

    private fun emptyResult(message: String): PreviewResult = PreviewResult(
        sql = "",
        compactSql = "",
        unresolvedParams = emptyList(),
        paramTable = null,
        wrapperType = null,
        tableName = null,
        warning = message,
    )

    private val WHITESPACE_REGEX = Regex("\\s+")
}
