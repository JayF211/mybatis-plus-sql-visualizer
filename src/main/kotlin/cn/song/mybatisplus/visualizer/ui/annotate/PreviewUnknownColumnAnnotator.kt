package cn.song.mybatisplus.visualizer.ui.annotate

import cn.song.mybatisplus.visualizer.execute.DataSourceSelector
import cn.song.mybatisplus.visualizer.execute.SchemaValidationGate
import cn.song.mybatisplus.visualizer.sql.schema.SchemaValidator
import cn.song.mybatisplus.visualizer.ui.inlay.PreviewInlayScope
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * 标注作用域：**只认本插件预览面板的 SQL 编辑器**。
 *
 * 两条紧致判据（任一成立）：
 *  1. **预览临时文件**：复用 [PreviewInlayScope.isPreviewFileName]——一级形态
 *     `mpsql-preview-<pid>-<seq>.sql` 四段全中，二级 `mpsql-preview-memory.sql` 精确匹配；
 *     不是 `startsWith("mpsql-preview")` 这类宽松前缀（用户自建同前缀文件不得命中）；
 *  2. **原生控制台执行文件**：`<project>/.idea/sql-visualizer/exec/mpsql-exec.sql`
 *     （[DataSourceSelector.isExecVfPath] 精确路径判据）。预览面板在 Ultimate 下真正展示的是
 *     挂在这个文件上的原生控制台编辑器；只认插件自有路径，用户在其他目录自建的
 *     `mpsql-exec.sql` 不满足路径判据，不会被标注。
 *
 * 为什么不做「所有 SQL 文件」：ExternalAnnotator 的 EP 是 language 级的，注册 `language="SQL"`
 * 后 IDE 内所有 SQL 编辑器都会向本 annotator 索取结果。作用域闸门是唯一的「不打扰用户自己的
 * `.sql` 文件」防线，必须在第一行就判定。
 */
internal object PreviewSchemaAnnotationScope {

    /** 是否为本插件预览面板的 SQL 编辑器载体（预览临时文件或原生控制台 exec 文件）。 */
    fun isPreviewSurface(file: PsiFile?): Boolean {
        if (file == null) return false
        if (PreviewInlayScope.isPreviewFileName(file.name)) return true
        val path = file.virtualFile?.path ?: return false
        return DataSourceSelector.isExecVfPath(path)
    }

    /**
     * 是否位于项目配置目录（`.idea`）下。
     *
     * 平台不把配置目录里的文件交给 daemon（测试实证：`.idea/sql-visualizer/exec/mpsql-exec.sql`
     * 的 ExternalAnnotator 永不触发，而同一内容放到 `sql-visualizer/exec/` 下立即触发）。
     * 本插件预览/执行临时文件恰好都在 `.idea/sql-visualizer/` 下，故 ExternalAnnotator 只作
     * 非配置目录的通用通道；配置目录内的真实编辑器由 [PreviewSchemaHighlighter] 的
     * RangeHighlighter 通道兜底。
     */
    fun isProjectConfigSurface(file: PsiFile?): Boolean {
        val path = file?.virtualFile?.path ?: return false
        return path.contains("/.idea/")
    }
}

/**
 * 预览编辑器「表中无此列」标注（UI025）。
 *
 * ## 数据来源（只读、零 DB I/O）
 *
 * 目标取自 [DataSourceSelector.schemaValidationTarget]：**只认当前已备/共享控制台的执行文档**
 * （文档身份相等 + 控制台 isValid + MySQL/MariaDB 的 URL 库与当前库一致）。无绑定目标、目标
 * 失效、库范围无法证明 → 不标注。列集只读
 * [cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.columnNamesCachedForTarget]
 * 的已预热缓存（经 [SchemaValidationGate.check] 三参入口），**绝不触发内省/建连**。
 *
 * ## 作用域与诚实降级
 *
 * 判定完全复用 [SchemaValidationGate.check] 的精确目标与 [SchemaValidator] 的 SQL 作用域规则：
 * 多语句、JOIN、CTE、子查询、限定列、显式库名、缓存冷、非 MySQL/MariaDB 目标一律
 * `Skipped`/空清单——**宁缺勿错**。标注位点由 [PreviewUnknownColumnPlanner] 从 JSqlParser
 * AST token 位置换算，不用字符串 `indexOf` 猜。
 *
 * ## 生命周期
 *
 * ExternalAnnotator 由平台在文档变更后重跑；每次 `apply` 只投递本次结果，平台会清掉上一轮
 * 外部标注，因此编辑后不会残留旧波浪线。`collectInformation` 取不到目标时返回 null，同样
 * 使平台清空旧标注。
 */
class PreviewUnknownColumnAnnotator :
    ExternalAnnotator<Any, List<PreviewUnknownColumnPlanner.Mark>>() {

    /** collect 阶段冻结的输入：项目 + 文档文本 + 已证实的执行目标。 */
    private data class Info(
        val project: Project,
        val text: String,
        val target: SchemaValidationGate.Target,
    )

    /**
     * 无 Editor 的批量路径拿不到「文档 → 已备控制台」身份，故不产结果（宁缺勿错）。
     * 真机 daemon 走带 Editor 的重载。
     */
    override fun collectInformation(file: PsiFile): Any? = null

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Any? {
        if (!PreviewSchemaAnnotationScope.isPreviewSurface(file)) return null
        val text = editor.document.text
        if (text.isBlank()) return null
        val target = DataSourceSelector.schemaValidationTarget(editor.document) ?: return null
        return Info(file.project, text, target)
    }

    override fun doAnnotate(collectedInfo: Any?): List<PreviewUnknownColumnPlanner.Mark> {
        val info = collectedInfo as? Info ?: return emptyList()
        val outcome = runCatching {
            SchemaValidationGate.check(info.project, info.text, info.target)
        }.getOrNull() ?: return emptyList()
        val issues: List<SchemaValidator.Issue> =
            (outcome as? SchemaValidationGate.Outcome.Checked)?.issues ?: return emptyList()
        return PreviewUnknownColumnPlanner.plan(info.text, issues)
    }

    override fun apply(
        file: PsiFile,
        annotationResult: List<PreviewUnknownColumnPlanner.Mark>,
        holder: AnnotationHolder,
    ) {
        val length = file.textLength
        for (mark in annotationResult) {
            if (mark.startOffset < 0 || mark.endOffset > length || mark.startOffset >= mark.endOffset) continue
            holder.newAnnotation(HighlightSeverity.ERROR, previewUnknownColumnMessage(mark))
                .range(TextRange(mark.startOffset, mark.endOffset))
                .create()
        }
    }
}
