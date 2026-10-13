package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.execute.SqlExecutor
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

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
        // T-SqlTemplateUx P2：失败反馈与预览面板拉平——除气泡外挂「定位到出错语句」「复制错误信息」
        // 两个可操作项（做法②：不新建面板，直接给 Notification 挂 NotificationAction）。
        // 定位锚点在动作触发时（EDT）从**触发编辑器**捕获（用户选中表名处即语句来源）；
        // 解析不出（无编辑器 / 文件已失效）则不给定位动作，退化为只给「复制错误信息」——
        // 与面板 errorLocateAction 的「先解析、后显隐」同姿态，绝不虚标可点的定位。
        val locate: (() -> Unit)? = resolveErrorLocateAnchor(
            e.getData(CommonDataKeys.EDITOR),
            e.getData(CommonDataKeys.VIRTUAL_FILE),
        )?.let { anchor ->
            { OpenFileDescriptor(project, anchor.file, anchor.line0, 0).navigate(true) }
        }
        SqlExecutor.execute(
            project, sql, null, true,
            onError = { errorMsg -> notifySqlTemplateError(project, errorMsg, locate) },
            // 富通道优先（execute/DataSourceSelector.deliverError 二选一）：与面板同走 onErrorRich，
            // 只是此处通知面不再需要 positionChar/lineInSql，故取 message 即可。
            onErrorRich = { info -> notifySqlTemplateError(project, info.message, locate) },
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

/**
 * T-SqlTemplateUx P2：错误定位锚点（触发编辑器 + 选区起始行，0-based）。
 *
 * 与预览面板的 `SqlPreviewService.SourceAnchor` 同语义——都是「语句来源位置」；差别只在来源：
 * 面板锚点由预览链登记，快捷模板动作没有预览链，故在动作触发时（EDT）就地取触发编辑器。
 * 只存 [VirtualFile] 与行号（不持有 [Editor] 引用，避免动作后编辑器被释放导致的悬空引用）。
 */
internal data class ErrorLocateAnchor(val file: VirtualFile, val line0: Int)

/**
 * T-SqlTemplateUx P2：从触发上下文解析定位锚点——编辑器、文件、选区三者缺一或文件已失效
 * 一律返回 null（调用方据此不给定位动作，退化为只给「复制错误信息」）。
 *
 * 锚点取**选区起始行**：快捷模板的语句由选区文本生成，选区即语句来源；不解析错误文本
 * （错误文本里的位置指向被执行 SQL，与 mapper 原文行不一一对应，v1 不换算——与面板口径一致）。
 */
internal fun resolveErrorLocateAnchor(editor: Editor?, file: VirtualFile?): ErrorLocateAnchor? {
    if (editor == null) return null
    val target = file ?: FileDocumentManager.getInstance().getFile(editor.document)
    if (target == null || !target.isValid) return null
    val offset = runCatching { editor.selectionModel.selectionStart }.getOrNull() ?: return null
    val line0 = runCatching { editor.document.getLineNumber(offset) }.getOrNull() ?: return null
    return ErrorLocateAnchor(target, line0)
}

/**
 * T-SqlTemplateUx P2：失败通知里的一个可操作项（文案 key + 点击行为）。
 *
 * 用「key + runnable」而非直接构造 [NotificationAction]，是为了让「挂了哪些动作、点了做什么」
 * 成为不依赖真机即可断言的纯逻辑（[NotificationAction] 的点击回调只能由平台事件驱动）。
 */
internal class SqlTemplateErrorAction(val titleKey: String, val run: () -> Unit)

/**
 * T-SqlTemplateUx P2：失败通知的可操作项清单——[locate] 非 null 才给「定位到出错语句」，
 * 「复制错误信息」恒给。文案 key 复用面板既有项（`execute.error.locate` / `panel.copyError`），
 * 保证两条入口（预览面板 / 快捷模板动作）用户可见的措辞一致。
 */
internal fun sqlTemplateErrorActions(locate: (() -> Unit)?, copy: () -> Unit): List<SqlTemplateErrorAction> {
    val actions = mutableListOf<SqlTemplateErrorAction>()
    locate?.let { actions += SqlTemplateErrorAction("execute.error.locate", it) }
    actions += SqlTemplateErrorAction("panel.copyError", copy)
    return actions
}

/**
 * T-SqlTemplateUx P2：快捷模板执行失败的富反馈通知——与预览面板同款的可操作项接在气泡上
 * （做法②：不新建面板，直接给 [Notification] 挂 [NotificationAction]）。
 */
internal fun buildSqlTemplateErrorNotification(
    errorMsg: String,
    locate: (() -> Unit)?,
    copy: () -> Unit,
): Notification {
    val notification = Notification(
        SelectedTableSqlTemplateAction.NOTIFICATION_GROUP_ID,
        MpSqlVisualizerBundle.message("sqlTemplate.notify.title"),
        errorMsg,
        NotificationType.WARNING,
    )
    for (action in sqlTemplateErrorActions(locate, copy)) {
        notification.addAction(
            NotificationAction.createSimpleExpiring(MpSqlVisualizerBundle.message(action.titleKey)) { action.run() },
        )
    }
    return notification
}

/** T-SqlTemplateUx P2：投递失败反馈通知（复制动作走 [UiTextClipboard] 统一收口）。 */
internal fun notifySqlTemplateError(project: Project, errorMsg: String, locate: (() -> Unit)?) {
    buildSqlTemplateErrorNotification(errorMsg, locate) { UiTextClipboard.copy(errorMsg) }.notify(project)
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
