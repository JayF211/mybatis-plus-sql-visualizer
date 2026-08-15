package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.reverse.ReverseWrapperFormat
import cn.song.mybatisplus.visualizer.reverse.SqlReverseException
import cn.song.mybatisplus.visualizer.reverse.SqlReverseResult
import cn.song.mybatisplus.visualizer.reverse.SqlToJavaConverter
import cn.song.mybatisplus.visualizer.ui.JavaCodePreviewDialog
import cn.song.mybatisplus.visualizer.ui.SqlSelectionDialog
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * SQL → MyBatis-Plus Java 代码 转换 Action（SQL 反向链路，P0 + T-M36.2/3 + T-M36.4 共享编排）。
 *
 * 触发方式（plugin.xml 注册）：编辑器右键菜单 "Convert SQL to MP Java Code" + 快捷键
 * `Alt+Shift+J`（Windows/Linux）/ `Cmd+Shift+J`（macOS）。
 *
 * 行为：取编辑器选择区文本 → [SqlToJavaConverter] 共享编排（T-M36.4 抽取，与工具窗口
 * 「SQL → Java」tab 同一链路）——解析（纯函数，失败弹错误对话框含行号）→ 按设置形态
 * （[PluginSettings.REVERSE_QUERY] 跳过实体查找）后台 ReadAction 查找实体 → 分发
 * （无候选回落 QueryWrapper / 唯一候选直接生成 / 多候选 [SqlSelectionDialog] 选择）→
 * [JavaCodePreviewDialog] 预览（复制/关闭）。
 *
 * 与 [ConvertToSqlAction]（Wrapper→SQL 正向）互补。
 */
class ConvertSqlToJavaAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) {
            showNoSelection(project)
            return
        }
        val text = selectionModel.selectedText ?: return
        if (text.isBlank()) {
            showNoSelection(project)
            return
        }

        // 1. SQL 解析（纯函数，EDT 安全）；失败 → 错误弹窗（含行号）
        val result = try {
            SqlToJavaConverter.parse(text)
        } catch (ex: SqlReverseException) {
            Messages.showErrorDialog(
                project,
                MpSqlVisualizerBundle.message(ex.key, ex.detail) + "\n（第 ${SqlToJavaConverter.errorLine(text, ex.offset)} 行）",
                MpSqlVisualizerBundle.message("reverse.error.title"),
            )
            return
        }

        // 2. 实体查找（PSI 索引访问 → 后台 ReadAction）；QUERY 形态无需实体（跳过查找）
        val format = reverseFormat()
        if (format == ReverseWrapperFormat.QUERY) {
            showResult(project, result, emptyList(), format)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val candidates = try {
                ReadAction.compute<List<SqlToJavaConverter.EntityCandidate>, Throwable> {
                    SqlToJavaConverter.findCandidates(project, result.tableName)
                }
            } catch (t: Throwable) {
                emptyList()
            }
            ApplicationManager.getApplication().invokeLater {
                showResult(project, result, candidates, format)
            }
        }
    }

    /** 反向转换配置形态（测试环境 settings 未注册时默认 AUTO）。 */
    private fun reverseFormat(): ReverseWrapperFormat = runCatching {
        ReverseWrapperFormat.fromPersisted(PluginSettings.getInstance().state.reverseWrapperFormat)
    }.getOrDefault(ReverseWrapperFormat.AUTO)

    /** 结果分发：无候选回落 QueryWrapper；唯一候选直接生成；多候选选择后生成。 */
    private fun showResult(
        project: Project,
        result: SqlReverseResult,
        candidates: List<SqlToJavaConverter.EntityCandidate>,
        format: ReverseWrapperFormat,
    ) {
        when (candidates.size) {
            0 -> JavaCodePreviewDialog(
                project,
                SqlToJavaConverter.generateCode(result, null, format),
            ).show()
            1 -> showCodeDialog(project, result, candidates[0], format)
            else -> {
                // 摘要/行文本为纯数据（PSI 已在后台解析），EDT 直接构造
                val displayTexts = candidates.map { c ->
                    SqlSelectionDialog.displayText(
                        "${c.psiClass.name}（表 ${c.tableInfo.tableName}）",
                        c.psiClass.containingFile?.name ?: "",
                    )
                }
                SqlSelectionDialog(project, candidates, displayTexts) { selected ->
                    showCodeDialog(project, result, selected, format)
                }.show()
            }
        }
    }

    /** 生成并展示代码（形态由配置分发，T-M36.3）。 */
    private fun showCodeDialog(
        project: Project,
        result: SqlReverseResult,
        candidate: SqlToJavaConverter.EntityCandidate,
        format: ReverseWrapperFormat,
    ) {
        JavaCodePreviewDialog(project, SqlToJavaConverter.generateCode(result, candidate, format)).show()
    }

    private fun showNoSelection(project: Project) {
        Messages.showInfoMessage(
            project,
            MpSqlVisualizerBundle.message("reverse.error.noSelection"),
            MpSqlVisualizerBundle.message("plugin.name"),
        )
    }

    // 仅访问编辑器选择区（UI 状态，非 PSI）→ EDT 更新线程安全且无慢操作风险
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val selection = editor?.selectionModel
        e.presentation.isEnabledAndVisible =
            selection != null && selection.hasSelection() && !selection.selectedText.isNullOrBlank()
    }

    companion object {
        /** 插件动作 ID（plugin.xml 中注册）。 */
        const val ACTION_ID = "cn.song.mybatisplus.visualizer.ConvertSqlToJava"
    }
}
