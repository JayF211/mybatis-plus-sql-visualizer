package cn.song.mybatisplus.visualizer.psource

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil

/**
 * JDBC 模板 SQL 字符串预览服务（正向入口）：
 * JdbcTemplateSqlLocator + SqlStringConcatenator + NamedParamParser → [PreviewResult]。
 *
 * 组装规则（对齐 [cn.song.mybatisplus.visualizer.ui.AnnotationSqlPreviewService.buildResult]）：
 *  - SQL 文本经 [SqlFormatter.format] 格式化（多行关键字换行），末尾加 `;`（与注解预览一致）
 *  - 命名参数（`:CUR_USER` / `:flowKey`）→ `unresolvedParams`（面板参数行）+ `namedParamHints`
 *  - 可执行 SQL 生成时走 [ParameterReplacer.replaceNamedParams]（按名回填）+ 残留 `?` 顺序回填
 *  - 含运行时拼片段 → `warning` 降级提示
 *
 * 入口：gutter 点击 / ConvertToSqlAction 光标定位 → [cn.song.mybatisplus.visualizer.ui.SqlPreviewService.preview]
 */
object JdbcTemplateSqlPreviewService {

    /** SQL 末尾分隔符（与注解预览 / 日志还原一致）。 */
    private const val SQL_TERMINATOR = ";"

    /**
     * 预览 JDBC 模板 SQL 字符串（gutter / action 通用入口）。
     *
     * @param anchor 光标所在 PSI 元素
     * @param project 当前项目（保留扩展点，暂未使用）
     * @return 预览结果；未命中返回 null
     */
    fun preview(anchor: PsiElement, project: Project? = null): PreviewResult? {
        val call = JdbcTemplateSqlLocator.locate(anchor) ?: return null
        return preview(call, anchor)
    }

    /**
     * 预览从已定位的 JdbcTemplate 调用组装结果。
     *
     * @param call 已定位的 JdbcTemplate SQL 调用
     * @param anchor 原始锚点元素（用于错误信息）
     */
    fun preview(call: JdbcTemplateSqlLocator.JdbcTemplateSqlCall, anchor: PsiElement): PreviewResult? {
        val sqlText = call.sqlString
        if (sqlText.isBlank()) return null

        val warnings = mutableListOf<String>()
        if (!call.isLiteral) {
            warnings += "SQL 含运行时拼接片段（方法返回值 / 外部字段等），完整 SQL 在运行期才确定；仅预览静态片段。"
        }

        // 收集命名参数
        val namedParams = NamedParamParser.collectNamedParams(sqlText)
        val namedSources = namedParams.map { it.name }
        val namedHints = namedParams.associate { it.name to NamedParamParser.quoteHint(it) }

        // `?` 占位符来源（按出现顺序，`?` 数量）
        val placeholderCount = sqlText.count { it == '?' }
        val placeholderSources = if (placeholderCount > 0) {
            (1..placeholderCount).map { "param$it" }
        } else {
            emptyList()
        }

        // 合并参数来源：命名参数在前（按名回填），`?` 占位符在后（按序回填）
        val unresolvedParams = namedSources + placeholderSources

        // 合并引号提示（与 unresolvedParams 按序对应）
        val namedParamHintsList = namedParams.map { NamedParamParser.quoteHint(it) }
        val placeholderHints = placeholderSources.map { ParamHint() } // 无类型标注走面板启发式
        val paramHints = namedParamHintsList + placeholderHints

        // 格式化 SQL（多行关键字换行），末尾加分隔符
        val formattedSql = try {
            SqlFormatter.format(sqlText).trimEnd() + SQL_TERMINATOR
        } catch (e: Throwable) {
            sqlText.trimEnd() + SQL_TERMINATOR
        }
        val compactSql = sqlText.trimEnd() + SQL_TERMINATOR

        // 参数对照表（供 UI 展示）
        val paramTable = ParameterReplacer.buildParamTable(unresolvedParams)

        return PreviewResult(
            sql = formattedSql,
            compactSql = compactSql,
            unresolvedParams = unresolvedParams,
            paramTable = paramTable,
            wrapperType = null,
            tableName = extractFirstTableName(sqlText),
            warning = warnings.firstOrNull(),
            paramHints = paramHints,
            namedParamHints = namedHints,
            initialParamValues = emptyMap(),
        )
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 启发式提取首个表名（FROM / JOIN 后首个标识符）；提取失败返回 null。 */
    private fun extractFirstTableName(sql: String): String? {
        val upper = sql.uppercase()
        val fromIdx = upper.indexOf("FROM")
        if (fromIdx < 0) return null
        val afterFrom = sql.substring(fromIdx + 4).trimStart()
        // 取首个非空白字符序列（表名或子查询 `(`）
        val tokens = afterFrom.split(Regex("\\s+"))
        val firstToken = tokens.firstOrNull()?.trim() ?: return null
        if (firstToken.startsWith("(")) return null // 子查询，暂不展开
        // 去除可能的别名（如 `WKFL_TASK WT` → `WKFL_TASK`）
        return firstToken.substringBefore('`').substringBefore('[').ifBlank { null }
    }
}
