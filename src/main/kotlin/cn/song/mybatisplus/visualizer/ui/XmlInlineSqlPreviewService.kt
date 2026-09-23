package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlMapping
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.xml.XmlInlineSqlDetector
import com.intellij.openapi.project.Project
import com.intellij.psi.xml.XmlTag

/**
 * 通用 XML 内嵌 SQL 预览服务（O-71 T-XmlInlineSql）：载体标签 → [PreviewResult]。
 *
 * - 无 `type` 或 type 非 HQL 族 → 原生 SQL 直显（pages 同款：`?` 占位符 → param 表）；
 * - `type` ∈ {hql, jpql}（就近祖先优先）→ 复用 hbm 命名查询的 HQL 管道
 *   （[SqlPreviewService.previewHbmNamedQuery]，name 传空即走 FROM 子句实体提取，
 *   O-57 别名作用域列名映射自动生效）；**管道抛异常 → 回退原文直显 + warning，
 *   不抛错**（验收底线：实体不可解析时至少展示原文）。
 *
 * 输入是已定位的载体 XmlTag（gutter / Action 负责定位），本服务在后台线程调用。
 */
object XmlInlineSqlPreviewService {

    fun preview(project: Project?, tag: XmlTag): PreviewResult {
        val sql = XmlInlineSqlDetector.extractSqlText(tag)
        if (sql.isBlank()) {
            return emptyResult(MpSqlVisualizerBundle.message("xmlInlineSql.empty.warning"))
        }
        if (project != null && XmlInlineSqlDetector.isHqlType(tag)) {
            val converted = runCatching {
                project.getService(SqlPreviewService::class.java)
                    .previewHbmNamedQuery(
                        HbmXmlMapping.NamedQuery(
                            name = "",
                            kind = HbmXmlMapping.QueryKind.HQL,
                            queryText = sql,
                        ),
                    )
            }.getOrNull()
            if (converted != null) return converted
            // HQL 管道异常 → 回退原文直显（不抛错）
            return plainResult(sql)
                .copy(warning = MpSqlVisualizerBundle.message("xmlInlineSql.hqlFallback.warning"))
        }
        return plainResult(sql)
    }

    /** 原生 SQL 直显（PagesXmlPreviewService 同款：`?` 计数 → param1..N）。 */
    private fun plainResult(sql: String): PreviewResult {
        val params = extractPlaceholders(sql)
        return PreviewResult(
            sql = sql,
            compactSql = sql.replace(WHITESPACE_REGEX, " ").trim(),
            unresolvedParams = params,
            paramTable = ParameterReplacer.buildParamTable(params),
            wrapperType = null,
            tableName = null,
        )
    }

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
