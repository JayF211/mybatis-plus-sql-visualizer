package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.execute.SqlExecutor
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project

/**
 * `${table}` 快捷模板三动作（T-PreviewUx-Pack #3，吸收 DBX sqlShortcutActions 口径）：
 * mapper 编辑器 / SQL 预览编辑器内**选中文本**（表名或片段）一键套模板送原生 Database 控制台执行——
 * EXPLAIN 执行计划 / COUNT 行数 / 抽样 10 行。三动作共享一个 action group 注册 EditorPopupMenu
 * （plugin.xml），与「生成 CRUD SQL」右键入口并存不合并菜单（目标互补：CRUD 生成 vs 诊断三动作）。
 *
 * 执行通道：既有内嵌控制台链路（execute/DataSourceSelector + [SqlExecutor]）——选数据源 →
 * 原生 Database 控制台执行，凭证不出 IDE；Community（无 database 插件）由 [SqlExecutor] 提示降级。
 * 三动作产物均为查询类语句（EXPLAIN/COUNT/SELECT），免 DML 二次确认（与 EXPLAIN 既有口径一致，
 * SqlTypeClassifier.QUERY 同族）。
 */
abstract class SelectedTableSqlTemplateAction(
    private val textKey: String,
    private val template: (String) -> String,
) : AnAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.text = MpSqlVisualizerBundle.message(textKey)
        // 无选中文本即禁用（不增加低价值打断；右键菜单常态可看不可点）
        e.presentation.isEnabled = selectedText(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val text = selectedText(e) ?: return
        val sql = template(text.trim())
        SqlExecutor.execute(
            project, sql, null, true,
            onError = { errorMsg ->
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(
                        MpSqlVisualizerBundle.message("sqlTemplate.notify.title"),
                        errorMsg,
                        com.intellij.notification.NotificationType.WARNING,
                    )
                    .notify(project)
            },
        )
    }

    private fun selectedText(e: AnActionEvent): String? {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        if (!editor.selectionModel.hasSelection()) return null
        return editor.selectionModel.selectedText?.takeIf { it.isNotBlank() }
    }

    // 仅访问编辑器选择区（UI 状态，非 PSI）→ EDT 更新线程安全且无慢操作风险（ConvertSqlToJavaAction 同款）
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    companion object {
        /** 提示通知组（O-4 预览操作 toast 同组）。 */
        const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"
    }
}

/** EXPLAIN 执行计划：`EXPLAIN SELECT * FROM <选中表>`。 */
class ExplainSelectedTableAction : SelectedTableSqlTemplateAction(
    "action.cn.song.mybatisplus.visualizer.ExplainSelectedTable.text",
    { selected -> "EXPLAIN SELECT * FROM $selected" },
) {
    companion object {
        /** 动作 ID（plugin.xml 注册 id 与设置页 ShortcutEditor 共用）。 */
        const val ACTION_ID = "cn.song.mybatisplus.visualizer.ExplainSelectedTable"
    }
}

/** 行数统计：`SELECT COUNT(*) FROM <选中表>`。 */
class CountSelectedTableAction : SelectedTableSqlTemplateAction(
    "action.cn.song.mybatisplus.visualizer.CountSelectedTable.text",
    { selected -> "SELECT COUNT(*) FROM $selected" },
) {
    companion object {
        const val ACTION_ID = "cn.song.mybatisplus.visualizer.CountSelectedTable"
    }
}

/** 抽样 N 行（R5：LIMIT 值设置页可配，默认 10；范围钳制 1..10000 防 0/负数与误输入全表）：`SELECT * FROM <选中表> LIMIT N`。 */
class SampleSelectedTableAction : SelectedTableSqlTemplateAction(
    "action.cn.song.mybatisplus.visualizer.SampleSelectedTable.text",
    { selected ->
        val limit = runCatching {
            cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.sampleLimit
        }.getOrDefault(10).coerceIn(1, 10000)
        "SELECT * FROM $selected LIMIT $limit"
    },
) {
    companion object {
        const val ACTION_ID = "cn.song.mybatisplus.visualizer.SampleSelectedTable"
    }
}
