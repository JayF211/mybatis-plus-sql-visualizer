package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.generator.CrudStatement
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.ui.SqlSelectionDialog
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project

/**
 * CRUD 生成结果 → 现有预览窗口的展示流（T-CrudSql-04，展示层零新弹窗）。
 *
 * 定案（2026-09-12 用户确认）：候选语句经现有 [SqlSelectionDialog] 选择，选中后走
 * [SqlPreviewService.showResult] 打开既有 SqlPreviewDialog——渲染/格式化/执行/复制按钮
 * 全部复用，INSERT/UPDATE 的列勾选重建由 PreviewResult.batchModel 承载。
 * 候选 0/1 条时不弹选择框（0 条由调用方兜底提示）。
 */
object CrudSqlPreviewFlow {

    /** 候选项：表名（标签前缀）+ 语句。 */
    data class Candidate(val tableName: String, val statement: CrudStatement)

    /**
     * 展示候选语句。多表多语句合并进同一列表（标签带表名前缀）。
     *
     * @param tables 表名 → 该表生成的语句列表（空列表的表已由调用方过滤）
     */
    fun showCandidates(project: Project, tables: List<Pair<String, List<CrudStatement>>>) {
        val candidates = tables.flatMap { (table, statements) ->
            statements.map { Candidate(table, it) }
        }
        when (candidates.size) {
            0 -> return
            1 -> SqlPreviewService.showResult(project, candidates[0].statement.result, null)
            else -> SqlSelectionDialog(
                project,
                candidates,
                candidates.map { SqlSelectionDialog.displayText(summary(it), lineText(it)) },
            ) { selected ->
                SqlPreviewService.showResult(project, selected.statement.result, null)
            }.show()
        }
    }

    /** 兜底提示（update 已拦截可见性，竞态/解析异常路径才会到达）。 */
    fun showNoneFound(project: Project) {
        com.intellij.openapi.ui.Messages.showInfoMessage(
            project,
            MpSqlVisualizerBundle.message("crudSql.noneFound.message"),
            MpSqlVisualizerBundle.message("crudSql.noneFound.title"),
        )
    }

    /** 候选摘要：`表名 · 语句种类`（无主键降级追加标注）。纯函数可单测。 */
    internal fun summary(candidate: Candidate): String {
        val kind = MpSqlVisualizerBundle.message(candidate.statement.kind.labelKey)
        val base = "${candidate.tableName} · $kind"
        return if (candidate.statement.missingPrimaryKey) {
            "$base${MpSqlVisualizerBundle.message("crudSql.candidate.noPrimaryKey")}"
        } else {
            base
        }
    }

    /** 候选第二行：SQL 首行预览（displayText 内部截断 80 字符并 HTML 转义）。 */
    internal fun lineText(candidate: Candidate): String =
        candidate.statement.result.sql.lineSequence().firstOrNull().orEmpty()
}
