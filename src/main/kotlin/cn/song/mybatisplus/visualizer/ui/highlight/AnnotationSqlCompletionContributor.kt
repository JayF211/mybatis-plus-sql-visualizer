package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.sql.stripXmlTags
import cn.song.mybatisplus.visualizer.ui.AnnotationSqlPreviewService
import cn.song.mybatisplus.visualizer.ui.SourceSqlContext
import cn.song.mybatisplus.visualizer.ui.XmlSqlCompletionContextResolver
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiPolyadicExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

/**
 * Java SQL 注解字符串代码补全贡献器（T-M27-05，M27 架构 §3.2 注解场景）。
 *
 * 背景：M27 将源码 SQL 补全接入 Java Mapper 接口——@Select/@Insert/@Update/@Delete 的 value
 * 字符串（含数组元素 / `+` 拼接 / `<script>` 动态标签前缀）内部 Ctrl+Space 补全关键字 / 表名 /
 * 列名 / 别名前缀列 / `#{}` 参数名，与 M16 预览编辑器补全（[SqlCompletionContributor]）并列。
 * 补全逻辑（候选生成）复用共享契约 [SourceSqlCompleter]，本类只做**守卫 + 文本提取 + 上下文解析**。
 *
 * **守卫决策（O(1) 语法判定，全部命中才继续；任何一道不通过 → return，不产任何项）**：
 *  1. position 自身或祖先中存在 [PsiLiteralExpression]（`strict=false`——补全 position 可能是
 *     字面量叶子本身或字面量内的文本叶子）；
 *  2. 字面量值是 String（数值/布尔/字符字面量非 SQL 文本 → return）；
 *  3. 字面量所属注解满足 [AnnotationSqlPreviewService.isSqlAnnotation] 且 shortName ∈
 *     [AnnotationSqlPreviewService.LITERAL_ANNOTATIONS]——Provider 注解族（@SelectProvider 等）
 *     SQL 由 Java 方法生成，不在字符串里，直接 return（isSqlAnnotation 按 qualifiedName 末段
 *     O(1) 匹配，非 SQL 注解（@Deprecated 等）同样不触发）；
 *  4. 字面量是该注解的 **value** 或 value 数组元素：`findAttributeValue(null)` + isAncestor 判定
 *     （覆盖单值 / value = 命名属性 / 数组元素 / `+` 拼接子表达式）；findAttributeValue 失败
 *     （PSI 解析中/不完整）时放宽为按 PsiNameValuePair **名称**（null 或 "value"）扫描——
 *     不放大到非 value 命名属性（如 method 属性）。
 *
 * **文本提取**：对 value 整体（数组取当前元素、`+` 拼接取整段——[AnnotationSqlPreviewService.constantString]
 * 平台常量求值，含字面量/文本块/拼接/静态 final 引用）求值，失败/空串 → return；
 * 再经 [stripXmlTags] 等长剥离（`<script>`/XML 标签 → 等长空格，偏移量 1:1 映射）。
 *
 * **补全**：`XmlSqlCompletionContextResolver.resolveAnnotation(project, method)` →
 * `SourceSqlCompleter.sqlCompletions(text, offset, context)`；offset = caret 相对字面量起点
 * （clamp 0..text.length——求值文本与字面量原文长度差异的防御，子句判定对前缀宽容）；
 * 结果经 `result.prefixMatcher.prefixMatches` 过滤后 addElement。
 * 上下文解析/候选生成任一环节异常 → runCatching 降级空上下文（关键字补全不阻塞）。
 *
 * 注册（plugin.xml `<completion.contributor>`）由主控统一完成；本类实现 [DumbAware]——
 * dumb 模式下关键字补全与降级上下文仍可用（上下文解析内部已 runCatching）。
 */
class AnnotationSqlCompletionContributor : CompletionContributor(), DumbAware {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    // 守卫 1：光标在字符串字面量内（position 本身或祖先；strict=false 覆盖 position 即字面量叶子）
                    val literal = PsiTreeUtil.getParentOfType(
                        parameters.position, PsiLiteralExpression::class.java, false,
                    ) ?: return
                    // 守卫 2：字符串值（数值/布尔/字符字面量非 SQL 文本）
                    if (literal.value !is String) return
                    // 守卫 3：字面量所属注解是字面量 SQL 注解（Provider 注解 SQL 由 Java 生成，不补全）
                    val annotation = PsiTreeUtil.getParentOfType(literal, PsiAnnotation::class.java, false) ?: return
                    if (!AnnotationSqlPreviewService.isSqlAnnotation(annotation)) return
                    val shortName = annotation.qualifiedName?.substringAfterLast('.') ?: return
                    if (shortName !in AnnotationSqlPreviewService.LITERAL_ANNOTATIONS) return
                    // 守卫 4：字面量是该注解的 value 或 value 数组元素（不放大到非 value 属性）
                    if (!isAnnotationValueMember(annotation, literal)) return

                    // 文本提取：常量求值（数组取当前元素 / + 拼接取整段），失败空串 → return
                    val raw = extractSqlText(annotation, literal, parameters.position.project)
                    if (raw.isEmpty()) return
                    // <script>/XML 标签等长剥离（偏移量 1:1，offset 无需换算）
                    val text = stripXmlTags(raw)
                    val sqlOffset = (parameters.offset - literal.textRange.startOffset).coerceIn(0, text.length)

                    val sourceContext = resolveSourceContext(parameters, annotation)
                    val prefix = result.prefixMatcher
                    runCatching { SourceSqlCompleter.sqlCompletions(text, sqlOffset, sourceContext) }
                        .getOrElse { emptyList() }
                        .forEach { item ->
                            if (prefix.prefixMatches(item.lookupString)) {
                                result.addElement(item)
                            }
                        }
                }
            },
        )
    }

    /**
     * 求值对象选择 + 常量求值（T-M27-05）：
     *  - value 即当前字面量（单值 / value = 命名属性）→ 整个 value 求值；
     *  - value 是数组 → 只求值当前元素（数组整体不可折叠为 String）；
     *  - value 是 `+` 拼接（PsiPolyadicExpression）→ 整段求值（caret 所在子字面量是空串/残段时
     *    仍能得到完整 SQL 文本，offset 按当前字面量起点近似——clamp 保证不越界）；
     *  - 其余（守卫已放行的异常形态）→ 退回字面量本身求值。
     * 失败（非常量/参数依赖等）→ [AnnotationSqlPreviewService.constantString] 返回空串 → 调用方 return。
     */
    private fun extractSqlText(
        annotation: PsiAnnotation,
        literal: PsiLiteralExpression,
        project: Project?,
    ): String {
        val value = annotation.findAttributeValue(null)
        val memberValue: PsiAnnotationMemberValue = when {
            value != null && value == literal -> value
            value is PsiArrayInitializerMemberValue -> literal
            value is PsiPolyadicExpression -> value
            else -> literal
        }
        return AnnotationSqlPreviewService.constantString(memberValue, project, mutableListOf())
    }

    /**
     * 守卫 4：字面量是否为注解 value 的一部分（T-M27-05）：
     *  - 严格路径：`findAttributeValue(null)` 命中时，字面量是 value 本身 / value 数组元素 /
     *    value `+` 拼接的子表达式（isAncestor 非严格——包含自身）即放行；
     *  - 兜底路径：findAttributeValue 返回 null（PSI 解析中/不完整）→ 按 PsiNameValuePair
     *    **名称**（null 或 "value"）扫描其值树——非 value 命名属性（如 method）不参与。
     */
    private fun isAnnotationValueMember(annotation: PsiAnnotation, literal: PsiLiteralExpression): Boolean {
        val value = annotation.findAttributeValue(null)
        if (value != null) {
            return PsiTreeUtil.isAncestor(value, literal, false)
        }
        return annotation.parameterList.attributes.any { pair ->
            (pair.name == null || pair.name == "value") &&
                pair.value?.let { PsiTreeUtil.isAncestor(it, literal, false) } == true
        }
    }

    /**
     * 注解方法 → 补全上下文（T-M27-05）：method = 注解所在 [PsiMethod]；
     * 方法缺失（类级注解等异常形态）或解析异常 → 空上下文兜底（关键字补全不受影响，不抛异常）。
     */
    private fun resolveSourceContext(parameters: CompletionParameters, annotation: PsiAnnotation): SourceSqlContext =
        runCatching {
            val method = PsiTreeUtil.getParentOfType(annotation, PsiMethod::class.java, false)
            if (method == null) {
                SourceSqlContext(null, emptyList(), emptyList())
            } else {
                XmlSqlCompletionContextResolver.resolveAnnotation(parameters.position.project, method)
            }
        }.getOrElse { SourceSqlContext(null, emptyList(), emptyList()) }
}
