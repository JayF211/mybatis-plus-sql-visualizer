package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.xml.AnnotationSqlParser
import cn.song.mybatisplus.visualizer.xml.XmlParseException
import cn.song.mybatisplus.visualizer.xml.XmlScriptException
import cn.song.mybatisplus.visualizer.xml.XmlStatementKind
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.util.PsiTreeUtil

/**
 * 注解 SQL 预览服务（T-M25-02/03）：Java Mapper 方法上的 MyBatis SQL 注解 → [PreviewResult]。
 *
 * 构建规则对齐 mybatis-3.5.19（MapperAnnotationBuilder）：
 * - 字面量注解（@Select/@Insert/@Update/@Delete）：value 元素（数组/单值）经**平台常量求值器**
 *   （computeConstantExpression：字面量/文本块/`+` 拼接/静态 final 引用）逐个求值 →
 *   `String.join(" ", values).trim()` → [AnnotationSqlParser]（script 检测与渲染）；
 * - Provider 注解（@SelectProvider 等，L1 常量折叠）：type → PsiClass、method → 同名方法或
 *   `provideSql` → return 表达式常量求值 → 同一渲染；含参数依赖/循环 → 降级提示
 *   「动态 SQL 由 Java 生成，无法静态预览」；
 * - @Param 参数名收集（@Param 值 > 实际参数名；多参数或含 @Param 时补 `param1..N`，
 *   对齐 ParamNameResolver 的 ParamMap 键）注入 mock；
 * - @Lang 非默认语言驱动 → 提示不支持预览。
 */
object AnnotationSqlPreviewService {

    /** 字面量 SQL 注解（qualifiedName 末段）。 */
    val LITERAL_ANNOTATIONS = setOf("Select", "Insert", "Update", "Delete")

    /** Provider 注解（SQL 由 Java 方法生成）。 */
    val PROVIDER_ANNOTATIONS = setOf("SelectProvider", "InsertProvider", "UpdateProvider", "DeleteProvider")

    /** 全部 SQL 注解（gutter/action 判定用）。 */
    val SQL_ANNOTATIONS = LITERAL_ANNOTATIONS + PROVIDER_ANNOTATIONS

    /** 注解是否为 MyBatis SQL 注解（按 qualifiedName 末段匹配，O(1) 语法判定）。 */
    fun isSqlAnnotation(annotation: PsiAnnotation): Boolean =
        annotation.qualifiedName?.substringAfterLast('.') in SQL_ANNOTATIONS

    /** M26-A：文件内全部 SQL 注解候选（文件级多候选——光标不在注解/Wrapper 上时回落）。 */
    fun annotationCandidates(file: com.intellij.psi.PsiFile): List<PsiAnnotation> =
        PsiTreeUtil.collectElementsOfType(file, PsiAnnotation::class.java).filter { isSqlAnnotation(it) }

    /** M26-A：注解 SQL 摘要（候选列表行文本——value 首个字符串元素，截断）。 */
    fun sqlSnippet(annotation: PsiAnnotation, maxLength: Int = 80): String {
        val value = annotation.findAttributeValue(null)
        val first = (value as? PsiArrayInitializerMemberValue)?.initializers?.firstOrNull() ?: value
        val text = (first as? com.intellij.psi.PsiLiteralExpression)?.value?.toString()?.trim().orEmpty()
        return if (text.length > maxLength) text.take(maxLength) + "…" else text
    }

    /** 预览注解 SQL（解析失败/降级场景返回带 warning 的空结果，不抛异常）。 */
    fun preview(annotation: PsiAnnotation, project: Project?): PreviewResult {
        val shortName = annotation.qualifiedName?.substringAfterLast('.') ?: return nullResult()
        return if (shortName in PROVIDER_ANNOTATIONS) {
            previewProvider(annotation, shortName, project)
        } else {
            previewLiteral(annotation, shortName, project)
        }
    }

    // ------------------------------------------------------------------
    // 字面量注解
    // ------------------------------------------------------------------

    private fun previewLiteral(annotation: PsiAnnotation, shortName: String, project: Project?): PreviewResult {
        val warnings = mutableListOf<String>()
        checkLanguageDriver(annotation, warnings)

        val rawValue = annotation.findAttributeValue(null)
        val values = mutableListOf<String>()
        when (rawValue) {
            is PsiArrayInitializerMemberValue ->
                rawValue.initializers.forEach { values += constantString(it, project, warnings) }
            else -> values += constantString(rawValue, project, warnings)
        }
        if (values.isEmpty()) {
            return nullResult(MpSqlVisualizerBundle.message("annotation.value.notFound"))
        }
        val sql = values.joinToString(" ").trim()
        val method = PsiTreeUtil.getParentOfType(annotation, PsiMethod::class.java)
        return buildResult(sql, shortName, paramNames(method), warnings, project)
    }

    // ------------------------------------------------------------------
    // Provider 注解（L1 常量折叠）
    // ------------------------------------------------------------------

    private fun previewProvider(annotation: PsiAnnotation, shortName: String, project: Project?): PreviewResult {
        val warnings = mutableListOf<String>()
        checkLanguageDriver(annotation, warnings)

        val providerClass = resolveProviderClass(annotation, project)
        val mapperMethod = PsiTreeUtil.getParentOfType(annotation, PsiMethod::class.java)
        if (providerClass == null) {
            return nullResult(MpSqlVisualizerBundle.message("annotation.provider.classNotFound"))
        }
        val methodName = constantString(annotation.findDeclaredAttributeValue("method"), project, warnings)
            ?: mapperMethod?.name
            ?: FALLBACK_METHOD_NAME
        val providerMethod = providerClass.findMethodsByName(methodName, false).firstOrNull()
        if (providerMethod == null) {
            return nullResult(MpSqlVisualizerBundle.message("annotation.provider.methodNotFound", methodName, providerClass.qualifiedName))
        }
        // return 表达式常量折叠（L1）；任一成功即用，失败/无 return → 降级提示
        val sql = providerMethod.body?.statements?.filterIsInstance<PsiReturnStatement>()
            ?.mapNotNull { it.returnValue?.let { rv -> constantExpression(rv, project) } }
            ?.firstOrNull()
        if (sql == null) {
            return nullResult(MpSqlVisualizerBundle.message("annotation.provider.notSupported"))
        }
        return buildResult(sql, shortName, paramNames(mapperMethod), warnings, project)
    }

    /** Provider type 解析：value/type 属性（PsiClassObjectAccessExpression）→ PsiClass。 */
    private fun resolveProviderClass(annotation: PsiAnnotation, project: Project?): PsiClass? {
        val value = annotation.findDeclaredAttributeValue("type") ?: annotation.findAttributeValue("value")
        val access = value as? PsiClassObjectAccessExpression ?: return null
        return (access.operand.type as? com.intellij.psi.PsiClassType)?.resolve() as? PsiClass
    }

    // ------------------------------------------------------------------
    // 共享
    // ------------------------------------------------------------------

    /** 元素常量求值：字面量/文本块/`+` 拼接/静态 final 引用；失败返回 null（含参数依赖等）。 */
    private fun constantExpression(memberValue: PsiAnnotationMemberValue?, project: Project?): String? {
        if (memberValue == null) return null
        val value = project?.let { p ->
            JavaPsiFacade.getInstance(p).constantEvaluationHelper.computeConstantExpression(memberValue)
        } ?: return null
        return value?.toString()
    }

    /** 数组元素/单值常量求值：失败记录 warning（元素缺失/非常量）。 */
    internal fun constantString(memberValue: PsiAnnotationMemberValue?, project: Project?, warnings: MutableList<String>): String {
        val result = constantExpression(memberValue, project)
        if (result == null && memberValue != null) {
            warnings += MpSqlVisualizerBundle.message("annotation.constant.failed")
        }
        return result.orEmpty()
    }

    /** @Lang 检测：方法存在 @Lang 且 value 非默认 XML 语言驱动 → 提示。 */
    private fun checkLanguageDriver(annotation: PsiAnnotation, warnings: MutableList<String>) {
        val method = PsiTreeUtil.getParentOfType(annotation, PsiMethod::class.java) ?: return
        val lang = method.annotations.firstOrNull { it.qualifiedName?.substringAfterLast('.') == "Lang" } ?: return
        val driverClass = (lang.findAttributeValue(null) as? PsiClassObjectAccessExpression)
            ?.operand?.type?.canonicalText
        if (driverClass != null && driverClass != "org.apache.ibatis.scripting.xmltags.XMLLanguageDriver") {
            warnings += MpSqlVisualizerBundle.message("annotation.lang.notSupported", driverClass)
        }
    }

    /**
     * 方法参数 mock 名（对齐 ParamNameResolver）：@Param 值优先，否则实际参数名；
     * 多参数或含 @Param 时补 `param1..N`（GENERIC_NAME_CACHE）。
     */
    internal fun paramNames(method: PsiMethod?): List<String> {
        if (method == null) return emptyList()
        val parameters = method.parameterList.parameters
        if (parameters.isEmpty()) return emptyList()
        var hasParamAnnotation = false
        val names = parameters.map { param ->
            val paramAnno = param.annotations.firstOrNull { it.qualifiedName?.substringAfterLast('.') == "Param" }
            if (paramAnno != null) {
                hasParamAnnotation = true
                paramAnno.findAttributeValue(null)?.let { (it as? com.intellij.psi.PsiLiteralExpression)?.value?.toString() }
                    ?: param.name
            } else {
                param.name
            }
        }
        return if (parameters.size > 1 || hasParamAnnotation) {
            names + (1..parameters.size).map { "param$it" }
        } else {
            names
        }
    }

    /** 模型 → 结果（解析异常兜底 warning；T-M25.2 静态常量引用经 project 解析注入）。 */
    private fun buildResult(
        sql: String,
        shortName: String,
        paramNames: List<String>,
        warnings: MutableList<String>,
        project: Project?,
    ): PreviewResult {
        val kind = when (shortName.removeSuffix("Provider")) {
            "Select" -> XmlStatementKind.SELECT
            "Insert" -> XmlStatementKind.INSERT
            "Update" -> XmlStatementKind.UPDATE
            else -> XmlStatementKind.DELETE
        }
        return try {
            val staticValues = XmlPreviewService.resolveStaticFields(sql, project, warnings)
            val model = AnnotationSqlParser().parse(sql, kind, paramNames, staticValues)
            XmlPreviewService.toResult(model, warnings)
        } catch (e: XmlParseException) {
            nullResult(e.message ?: "SQL 解析失败。")
        } catch (e: XmlScriptException) {
            nullResult(e.message ?: "SQL 脚本解析失败。")
        }
    }

    private fun nullResult(message: String? = null): PreviewResult = PreviewResult(
        sql = "",
        compactSql = "",
        unresolvedParams = emptyList(),
        paramTable = null,
        wrapperType = null,
        tableName = null,
        warning = message,
    )

    private const val FALLBACK_METHOD_NAME = "provideSql"
}
