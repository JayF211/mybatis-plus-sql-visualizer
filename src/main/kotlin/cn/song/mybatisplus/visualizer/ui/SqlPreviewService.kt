package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.converter.ConverterRegistry
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.psi.BatchOperationConverter
import cn.song.mybatisplus.visualizer.psi.WrapperCache
import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethodCallExpression

/**
 * SQL 预览服务（M3-01 整合层，F-07 统一入口）。
 *
 * 职责：把编辑器中的任意 PSI 元素转换为可展示的 [PreviewResult]。
 * 集成链路：WrapperLocator 定位 → ConverterRegistry 分发（SPI）→ SqlGenerator 生成 →
 * ParameterReplacer 参数对照 → WrapperCache 缓存（F-20）。
 *
 * M5 起读取 [PluginSettings] 配置：方言（分页子句）、格式化开关（多行/单行）。
 * 注册为 project service（见 plugin.xml），生命周期随项目。
 */
class SqlPreviewService(private val project: Project) {

    /** 文件级解析缓存（F-20）：文件内容变更（modificationStamp）即失效。 */
    private val cache = WrapperCache()

    /**
     * 全局配置（测试环境未注册 service 时为 null，使用默认值）。
     */
    private val settings: PluginSettings? =
        runCatching { PluginSettings.getInstance() }.getOrNull()

    /**
     * 预览锚点元素处的 Wrapper 转换结果。
     *
     * 识别顺序：
     *  1. Wrapper 调用链（SELECT/UPDATE/DELETE/MPJ）
     *  2. 批量操作调用（F-15，`service.updateBatchById(list)` 等）
     *
     * @param anchor 光标处 / 悬停处的 PSI 元素
     * @return 预览结果；两者均未命中时返回 null
     */
    fun preview(anchor: PsiElement): PreviewResult? {
        val file = anchor.containingFile ?: return null
        val located = cache.getOrCompute(file) { WrapperLocator.locateWithTable(anchor) }

        // 1. Wrapper 转换（优先）
        located?.let {
            val statement = ConverterRegistry.convert(it.chain, it.table ?: return null) ?: return null
            return buildPreviewResult(it.chain.wrapperType, it.table!!, statement)
        }

        // 2. 批量操作识别（anchor 或其父链上的方法调用）
        val batchCall = generateSequence(anchor) { it.parent }
            .filterIsInstance<PsiMethodCallExpression>()
            .firstOrNull()
        val batch = batchCall?.let { BatchOperationConverter.convert(it) } ?: return null
        return PreviewResult(
            sql = batch.sql,
            compactSql = batch.sql,
            unresolvedParams = emptyList(),
            paramTable = batch.paramGroups,
            wrapperType = null,
            tableName = batch.tableName,
        )
    }

    /**
     * 由语句模型构建预览结果（应用方言与格式化配置）。
     */
    private fun buildPreviewResult(
        wrapperType: cn.song.mybatisplus.visualizer.model.WrapperType?,
        table: cn.song.mybatisplus.visualizer.model.TableInfo,
        statement: SqlStatement,
    ): PreviewResult {
        val dialect = currentDialect()
        val compact = !formatSql()
        val generated = SqlGenerator.generateWithParams(statement, compact = compact, dialect = dialect)
        return PreviewResult(
            sql = generated.sql,
            compactSql = SqlGenerator.generate(statement, compact = true, dialect = dialect),
            unresolvedParams = generated.unresolvedParams,
            paramTable = ParameterReplacer.buildParamTable(generated.unresolvedParams),
            wrapperType = wrapperType,
            tableName = table.tableName,
            warning = buildWarning(statement),
            columns = table.allColumnNames,
        )
    }

    /**
     * 当前方言（配置未加载时默认 MySQL）。
     */
    fun currentDialect(): Dialect = settings?.let { DialectRegistry.byName(it.state.dialect) } ?: DialectRegistry.byName(null)

    /**
     * 是否格式化输出（true=多行缩进，false=单行）。
     */
    fun formatSql(): Boolean = settings?.state?.formatSql ?: true

    /**
     * 显示方式（DIALOG / TOOL_WINDOW / BOTH），供 UI 触发方读取。
     */
    fun displayMode(): String = settings?.state?.displayMode ?: PluginSettings.DISPLAY_BOTH

    /**
     * 风险警示（F-09/F-10）：UPDATE / DELETE 无 WHERE 条件时提示全表操作。
     */
    private fun buildWarning(statement: SqlStatement): String? = when (statement) {
        is UpdateStatement -> if (statement.conditions.isEmpty()) "⚠ 无 WHERE 条件，将更新全表数据！" else null
        is DeleteStatement -> if (statement.conditions.isEmpty()) "⚠ 无 WHERE 条件，将删除全表数据！" else null
        else -> null
    }

    /**
     * 清空缓存（编辑器文件切换/项目关闭时由持有方调用）。
     */
    fun clearCache() = cache.clear()
}
