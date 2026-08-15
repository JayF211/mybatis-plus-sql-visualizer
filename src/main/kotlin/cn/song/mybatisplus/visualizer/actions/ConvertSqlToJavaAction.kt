package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator
import cn.song.mybatisplus.visualizer.reverse.SqlReverseException
import cn.song.mybatisplus.visualizer.reverse.SqlReverseParser
import cn.song.mybatisplus.visualizer.ui.JavaCodePreviewDialog
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages

/**
 * SQL → MyBatis-Plus Java 代码 转换 Action（SQL 反向链路，P0）。
 *
 * 触发方式（plugin.xml 注册）：编辑器右键菜单 "Convert SQL to MP Java Code" + 快捷键
 * `Alt+Shift+J`（Windows/Linux）/ `Cmd+Shift+J`（macOS）。
 *
 * 行为：取编辑器选择区文本 → [SqlReverseParser] 解析（单表 SELECT，P0 范围）→
 * [JavaCodeGenerator] 生成 QueryWrapper 代码 → [JavaCodePreviewDialog] 预览（复制/关闭）；
 * 解析失败（P1+ 结构/语法错误）弹错误对话框（含行号定位）。
 *
 * 与 [ConvertToSqlAction]（Wrapper→SQL 正向）互补；不依赖 PSI，任何文件类型均可使用。
 */
class ConvertSqlToJavaAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) {
            Messages.showInfoMessage(
                project,
                MpSqlVisualizerBundle.message("reverse.error.noSelection"),
                MpSqlVisualizerBundle.message("plugin.name"),
            )
            return
        }
        val text = selectionModel.selectedText ?: return
        if (text.isBlank()) {
            Messages.showInfoMessage(
                project,
                MpSqlVisualizerBundle.message("reverse.error.noSelection"),
                MpSqlVisualizerBundle.message("plugin.name"),
            )
            return
        }

        val code = try {
            val result = SqlReverseParser.parse(text)
            JavaCodeGenerator.generate(result)
        } catch (ex: SqlReverseException) {
            val line = SqlReverseParser.lineOf(text, ex.offset)
            Messages.showErrorDialog(
                project,
                MpSqlVisualizerBundle.message(ex.key, ex.detail) + "\n（第 $line 行）",
                MpSqlVisualizerBundle.message("reverse.error.title"),
            )
            return
        }
        JavaCodePreviewDialog(project, code).show()
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
