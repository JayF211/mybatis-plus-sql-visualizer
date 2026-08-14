package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser
import cn.song.mybatisplus.visualizer.xml.XmlParseException
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import cn.song.mybatisplus.visualizer.xml.XmlScriptException

/**
 * XML 预览服务（T-M24-04）：mapper 文件文本 → 语句 → 预览模型 → [PreviewResult]。
 *
 * 纯函数（fileText 输入，无 PSI 依赖），gutter / Action 负责取文件文本与定位语句 id；
 * 弹窗与面板完全复用 [SqlPreviewService.showResult] 链路。
 *
 * - [statementId] 为 null 或未命中 → 取文件首个语句（多语句候选选择后续迭代）；
 * - 解析失败（非 mapper / 非良构 / 未知标签）→ 返回带 warning 的空结果（面板红色提示，不抛异常）；
 * - 渲染期错误（foreach collection 为 null、未解析变量等）合并进 warning；
 * - 参数对照表复用 [ParameterReplacer.buildParamTable]（`#{}` 属性名清单，顺序对应 `?`）。
 */
object XmlPreviewService {

    fun preview(fileText: String, statementId: String?): PreviewResult {
        val model = try {
            val parsed = XmlMapperParser().parse(fileText)
            val statement = parsed.statements.firstOrNull { it.id == statementId } ?: parsed.statements.firstOrNull()
                ?: return emptyResult("未找到可预览的 SQL 语句（mapper 中无 select/insert/update/delete）。")
            XmlPreviewModel.from(statement, parsed.errors)
        } catch (e: XmlParseException) {
            return emptyResult(e.message ?: "XML 解析失败。")
        } catch (e: XmlScriptException) {
            return emptyResult(e.message ?: "SQL 脚本解析失败。")
        }

        return toResult(model)
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
}
