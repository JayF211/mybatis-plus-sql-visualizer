package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.xml.MockObject
import cn.song.mybatisplus.visualizer.xml.XmlEwExpander
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser
import cn.song.mybatisplus.visualizer.xml.XmlParseException
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import cn.song.mybatisplus.visualizer.xml.XmlScriptException
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

/**
 * XML 预览服务（T-M24-04）：mapper 文件文本 → 语句 → 预览模型 → [PreviewResult]。
 *
 * 纯函数（fileText 输入，无 PSI 依赖），gutter / Action 负责取文件文本与定位语句 id；
 * 弹窗与面板完全复用 [SqlPreviewService.showResult] 链路。
 *
 * - [statementId] 为 null 或未命中 → 取文件首个语句（多语句候选选择后续迭代）；
 * - 解析失败（非 mapper / 非良构 / 未知标签）→ 返回带 warning 的空结果（面板红色提示，不抛异常）；
 * - 渲染期错误（foreach collection 为 null、未解析变量等）合并进 warning；
 * - 参数对照表复用 [ParameterReplacer.buildParamTable]（`#{}` 属性名清单，顺序对应 `?`）；
 * - T-M25.2：传入 [project] 时解析 `@class@field` 静态常量引用（用户项目常量类字段初始值，
 *   经 PSI 常量求值器取值）注入渲染——MyBatis mapper 常见 OGNL 常量写法。
 */
object XmlPreviewService {

    fun preview(fileText: String, statementId: String?, project: Project? = null): PreviewResult {
        val (model, staticWarnings) = try {
            val parsed = XmlMapperParser().parse(fileText)
            val statement = parsed.statements.firstOrNull { it.id == statementId } ?: parsed.statements.firstOrNull()
                ?: return emptyResult("未找到可预览的 SQL 语句（mapper 中无 select/insert/update/delete）。")
            val warnings = mutableListOf<String>()
            val staticValues = resolveStaticFields(fileText, project, warnings)
            // T-M26-B（P-立项-03）：MP 占位展开（${ew.customSqlSegment} 等）——Java 关联 wrapper 实参 → 片段注入
            val ewBindings = resolveEwPlaceholders(fileText, parsed.namespace, statement.id, project, warnings)
            XmlPreviewModel.from(statement, parsed.errors, staticFieldValues = staticValues, extraBindings = ewBindings) to warnings
        } catch (e: XmlParseException) {
            return emptyResult(e.message ?: "XML 解析失败。")
        } catch (e: XmlScriptException) {
            return emptyResult(e.message ?: "SQL 脚本解析失败。")
        }

        return toResult(model, staticWarnings)
    }

    /**
     * T-M26-B：MP 占位展开——扫描 `${变量.customSqlSegment/sqlSelect/sqlSet}`，
     * 经 [XmlEwResolver] 关联 Java Mapper 方法 wrapper 实参展开为 SQL 片段，
     * 包装为 MockObject（`ew.customSqlSegment` 属性访问求值）注入 mock bindings。
     */
    private fun resolveEwPlaceholders(
        fileText: String,
        namespace: String?,
        statementId: String,
        project: Project?,
        warnings: MutableList<String>,
    ): Map<String, Any?> {
        val placeholders = XmlEwExpander.placeholders(fileText)
        if (placeholders.isEmpty() || project == null) return emptyMap()
        val resolved = XmlEwResolver.resolve(namespace, statementId, placeholders, project, warnings)
        return resolved.mapValues { (_, fragments) -> MockObject(fragments) }
    }

    /**
     * 静态字段引用解析（T-M25.2）：扫描文本中的 `@类全名@字段名`（OGNL 静态访问），
     * 经 PSI 定位用户项目中的常量类与字段初始值（computeConstantExpression）；
     * 无 project（纯文本测试环境）→ 空映射；类/字段缺失或非常量 → warning + 不注入。
     */
    internal fun resolveStaticFields(fileText: String, project: Project?, warnings: MutableList<String>): Map<String, Any?> {
        if (project == null) return emptyMap()
        val refs = STATIC_REF_REGEX.findAll(fileText)
            .map { "${it.groupValues[1]}@${it.groupValues[2]}" }
            .toSet()
        if (refs.isEmpty()) return emptyMap()
        return refs.mapNotNull { ref ->
            val (className, fieldName) = ref.split('@', limit = 2)
            val value = resolveStaticFieldValue(project, className, fieldName)
            if (value == null) {
                warnings += "静态常量 $ref 无法解析（类/字段不存在或非常量），预览按空值处理，可在预览中填写覆盖值"
                null
            } else {
                ref to value
            }
        }.toMap()
    }

    /** 单字段求值：findClass → findFieldByName → 字段初始值常量求值。 */
    internal fun resolveStaticFieldValue(project: Project, className: String, fieldName: String): String? {
        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(className, GlobalSearchScope.allScope(project)) ?: return null
        val field = psiClass.findFieldByName(fieldName, false) ?: return null
        val initializer = field.initializer ?: return null
        return JavaPsiFacade.getInstance(project)
            .constantEvaluationHelper.computeConstantExpression(initializer)?.toString()
    }

    /** 预览模型 → [PreviewResult]（T-M25-02 注解预览共用）：初始渲染 + 参数对照 + 错误合并。 */
    internal fun toResult(model: XmlPreviewModel, extraWarnings: List<String> = emptyList()): PreviewResult {
        val rendered = model.renderSql()
        val errors = model.errors + extraWarnings + rendered.errors
        val sql = rendered.sql
        return PreviewResult(
            sql = sql,
            compactSql = sql.replace(WHITESPACE_REGEX, " ").trim(),
            unresolvedParams = rendered.mappings.map { it.property },
            paramTable = ParameterReplacer.buildParamTable(rendered.mappings.map { it.property }),
            wrapperType = null,
            tableName = null,
            warning = errors.joinToString("；").ifEmpty { null },
            xmlModel = model,
        )
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

    /** T-M25.2：OGNL 静态字段引用 `@类全名@字段名`（类名可含 `$` 内部类）。 */
    private val STATIC_REF_REGEX = Regex("@([A-Za-z_$][\\w.$]*)@(\\w+)")
}
