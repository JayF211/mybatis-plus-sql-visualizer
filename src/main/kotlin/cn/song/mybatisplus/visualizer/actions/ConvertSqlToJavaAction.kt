package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.psi.EntityClassFinder
import cn.song.mybatisplus.visualizer.psi.EntityResolver
import cn.song.mybatisplus.visualizer.reverse.GeneratedWrapper
import cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator
import cn.song.mybatisplus.visualizer.reverse.ReverseWrapperFormat
import cn.song.mybatisplus.visualizer.reverse.SqlReverseException
import cn.song.mybatisplus.visualizer.reverse.SqlReverseParser
import cn.song.mybatisplus.visualizer.reverse.SqlReverseResult
import cn.song.mybatisplus.visualizer.reverse.resolveWrapperFormat
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
import com.intellij.psi.PsiClass

/**
 * SQL → MyBatis-Plus Java 代码 转换 Action（SQL 反向链路，P0 + T-M36.2）。
 *
 * 触发方式（plugin.xml 注册）：编辑器右键菜单 "Convert SQL to MP Java Code" + 快捷键
 * `Alt+Shift+J`（Windows/Linux）/ `Cmd+Shift+J`（macOS）。
 *
 * 行为：取编辑器选择区文本 → [SqlReverseParser] 解析（单表 SELECT）→
 * [EntityClassFinder] 按表名查找项目内实体类（T-M36.2）：
 *  - 唯一候选 → 生成 `LambdaQueryWrapper<Entity>` 代码（列→字段映射，未映射回落字符串列名）；
 *  - 多候选 → [SqlSelectionDialog] 选择后生成；
 *  - 无候选 → 回落字符串列名 `QueryWrapper` 形态（P0 行为）。
 * 结果经 [JavaCodePreviewDialog] 预览（复制/关闭）；解析失败（P1+ 结构/语法错误）弹错误
 * 对话框（含行号定位）。
 *
 * 与 [ConvertToSqlAction]（Wrapper→SQL 正向）互补。实体查找为 PSI 索引访问 → 后台
 * ReadAction 执行（对齐 ConvertToSqlAction 的 EDT 慢操作守卫模式）；SQL 解析与代码生成为
 * 纯函数，在 EDT 回调中执行。
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
            SqlReverseParser.parse(text)
        } catch (ex: SqlReverseException) {
            val line = SqlReverseParser.lineOf(text, ex.offset)
            Messages.showErrorDialog(
                project,
                MpSqlVisualizerBundle.message(ex.key, ex.detail) + "\n（第 $line 行）",
                MpSqlVisualizerBundle.message("reverse.error.title"),
            )
            return
        }

        // 2. 实体查找（PSI 索引访问 → 后台 ReadAction；T-M36.2）；QUERY 形态无需实体（T-M36.3 跳过查找）
        val format = reverseFormat()
        if (format == ReverseWrapperFormat.QUERY) {
            showResult(project, result, emptyList(), GeneratedWrapper.QUERY)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val candidates = try {
                ReadAction.compute<List<EntityCandidate>, Throwable> {
                    EntityClassFinder(project).find(result.tableName).map { clazz ->
                        EntityCandidate(clazz, EntityResolver().resolve(clazz))
                    }
                }
            } catch (t: Throwable) {
                emptyList()
            }
            ApplicationManager.getApplication().invokeLater {
                // T-M36.3：配置 + 是否有实体 → 最终形态（AUTO/LAMBDA/MPJ_LAMBDA 无实体回落 QUERY）
                val wrapper = resolveWrapperFormat(format, candidates.isNotEmpty())
                showResult(project, result, candidates, wrapper)
            }
        }
    }

    /** 反向转换配置形态（测试环境 settings 未注册时默认 AUTO）。 */
    private fun reverseFormat(): ReverseWrapperFormat = runCatching {
        ReverseWrapperFormat.fromPersisted(PluginSettings.getInstance().state.reverseWrapperFormat)
    }.getOrDefault(ReverseWrapperFormat.AUTO)

    /** 结果分发：无实体（或 QUERY 形态）回落 QueryWrapper；唯一候选直接生成；多候选选择后生成。 */
    private fun showResult(
        project: Project,
        result: SqlReverseResult,
        candidates: List<EntityCandidate>,
        wrapper: GeneratedWrapper,
    ) {
        when (candidates.size) {
            0 -> JavaCodePreviewDialog(project, JavaCodeGenerator.generate(result)).show()
            1 -> showLambdaDialog(project, result, candidates[0], wrapper)
            else -> {
                // 摘要/行文本为纯数据（PSI 已在后台解析），EDT 直接构造
                val displayTexts = candidates.map { c ->
                    SqlSelectionDialog.displayText(
                        "${c.psiClass.name}（表 ${c.tableInfo.tableName}）",
                        c.psiClass.containingFile?.name ?: "",
                    )
                }
                SqlSelectionDialog(project, candidates, displayTexts) { selected ->
                    showLambdaDialog(project, result, selected, wrapper)
                }.show()
            }
        }
    }

    /** 生成并展示 Lambda 形态代码（wrapper 由配置分发决定，T-M36.3）。 */
    private fun showLambdaDialog(
        project: Project,
        result: SqlReverseResult,
        candidate: EntityCandidate,
        wrapper: GeneratedWrapper,
    ) {
        val target = JavaCodeGenerator.EntityTarget(
            className = candidate.psiClass.name ?: JavaCodeGenerator.entityClassName(result.tableName),
            columnToField = candidate.tableInfo.columns.associate { it.columnName to it.fieldName },
        )
        JavaCodePreviewDialog(project, JavaCodeGenerator.generate(result, target, wrapper)).show()
    }

    private fun showNoSelection(project: Project) {
        Messages.showInfoMessage(
            project,
            MpSqlVisualizerBundle.message("reverse.error.noSelection"),
            MpSqlVisualizerBundle.message("plugin.name"),
        )
    }

    /** 实体候选（类 + 已解析表信息；PSI 访问已在后台完成，EDT 仅持纯数据）。 */
    private data class EntityCandidate(val psiClass: PsiClass, val tableInfo: TableInfo)

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
