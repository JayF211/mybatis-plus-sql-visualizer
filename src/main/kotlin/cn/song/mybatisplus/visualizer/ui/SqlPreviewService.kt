package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.converter.ConverterRegistry
import cn.song.mybatisplus.visualizer.converter.QueryWrapperParser
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.SelectStatement
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
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
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
        // 缓存键含锚点偏移：同文件多个 Wrapper（查询/删除/修改）各自独立缓存，避免串结果
        val located = cache.getOrCompute(file, anchor.textRange.startOffset) { WrapperLocator.locateWithTable(anchor) }

        // 1. Wrapper 转换（优先）
        located?.let { located ->
            val table = located.table ?: return null
            val statement = when {
                // 计数消费：service.count(wrapper) / selectJoinCount(wrapper) → SELECT COUNT(*) FROM ...（保留联表与条件）
                located.chain.countConsumed && located.chain.wrapperType.isQueryLike() -> {
                    val parsed = QueryWrapperParser(table).parse(located.chain)
                    SelectStatement(
                        table,
                        selectColumns = listOf(ColumnRef(columnName = "*", function = "COUNT")),
                        tableAlias = parsed.tableAlias,
                        joins = parsed.joins,
                        conditions = parsed.conditions,
                    )
                }
                // MP 惯用删除写法：删除条件用 LambdaQueryWrapper 构造，`service.remove(wrapper)` / `mapper.delete(wrapper)` 消费
                located.chain.deleteConsumed && located.chain.wrapperType.isQueryLike() ->
                    DeleteStatement(table, QueryWrapperParser(table).parse(located.chain).conditions)
                else -> ConverterRegistry.convert(located.chain, table) ?: return null
            }
            return buildPreviewResult(located.chain.wrapperType, table, statement)
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
     * 查询类 Wrapper 类型（可能被删除类消费方法 `remove(wrapper)`/`delete(wrapper)` 复用为删除条件，M10）。
     */
    private fun WrapperType.isQueryLike(): Boolean = this in setOf(
        WrapperType.LAMBDA_QUERY,
        WrapperType.QUERY,
        WrapperType.MPJ_LAMBDA,
        WrapperType.MPJ_QUERY,
        WrapperType.MPJ_LAMBDA_QUERY,
    )

    /**
     * 清空缓存（编辑器文件切换/项目关闭时由持有方调用）。
     */
    fun clearCache() = cache.clear()

    companion object {
        /**
         * 统一展示入口（M10-03）：按显示方式配置（F-16）展示预览结果，
         * 供 [cn.song.mybatisplus.visualizer.actions.ConvertToSqlAction] 与 gutter 图标点击共用。
         *
         * @param result null 表示未找到可转换的 Wrapper → 弹友好提示（不抛异常）
         */
        fun showResult(project: Project, result: PreviewResult?) {
            if (result == null) {
                Messages.showInfoMessage(
                    project,
                    MpSqlVisualizerBundle.message("action.notFound.message"),
                    MpSqlVisualizerBundle.message("action.notFound.title"),
                )
                return
            }
            val mode = project.getService(SqlPreviewService::class.java).displayMode()
            if (mode != PluginSettings.DISPLAY_TOOL_WINDOW) {
                SqlPreviewDialog(project, result).show()
            }
            if (mode != PluginSettings.DISPLAY_DIALOG) {
                SqlToolWindow.showResult(project, result)
            }
        }

        /**
         * 后台线程 + 读操作执行转换，EDT 回调结果。
         *
         * PSI 定位/解析（含注解解析）在 IDEA 2025.3+ 属于"慢操作"，禁止在 EDT 上执行；
         * 按钮/快捷键/编辑器跟随等 UI 触发方统一走此入口，避免
         * "Slow operations are prohibited on EDT"。
         *
         * @param project  项目
         * @param compute  在 ReadAction 内执行转换（锚点元素可先在 EDT 捕获，块内读取）
         * @param onResult 在 EDT 上回调转换结果（未找到 Wrapper 时为 null）
         */
        fun previewAsync(project: Project, compute: () -> PreviewResult?, onResult: (PreviewResult?) -> Unit) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = try {
                    ReadAction.compute<PreviewResult?, Throwable> { compute() }
                } catch (e: Throwable) {
                    null
                }
                ApplicationManager.getApplication().invokeLater { onResult(result) }
            }
        }
    }
}
