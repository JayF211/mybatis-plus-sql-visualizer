package cn.song.mybatisplus.visualizer.ui.components

import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * 一键复制可执行 SQL（O-4 Phase A）。
 *
 * 取当前面板 SQL（含参数回填与手动编辑），将 `?` 替换为带类型处理的字面值
 * （复用 [ParameterReplacer.buildExecutableSql]），复制到系统剪贴板并 toast 提示。
 *
 * 复制统一走 [UiTextClipboard]（平台 CopyPasteManager）：在 headless 测试环境下
 * 剪贴板写入不影响复制逻辑本身。
 */
class CopyExecutableSqlAction(private val project: Project?) {

    companion object {
        /** 通知分组 id（plugin.xml 注册）。 */
        private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"
    }

    /**
     * 执行复制：取面板当前 SQL + 参数值 → 替换占位符 → 剪贴板 → toast。
     *
     * @param panel 预览面板（取 SQL / 参数值 / 回填提示）
     * @return 是否成功复制
     */
    fun execute(panel: SqlPreviewPanel): Boolean {
        val raw = panel.editorDocumentText()
            ?.takeIf { it.isNotBlank() } ?: run {
            showToast(MpSqlVisualizerBundle.message("preview.copyExecutableSql.nothing"))
            return false
        }
        // 复制时剥离 SQL 注释（与 copySql 对齐）
        val sql = SqlCommentStripper.strip(raw)
        val sources = panel.unresolvedParams()
        val paramValues = panel.paramValuesSnapshot()

        val executableSql = if (sources.isEmpty()) {
            sql
        } else {
            ParameterReplacer.buildExecutableSql(
                sql = sql,
                sources = sources,
                paramValues = paramValues,
            )
        }

        copyToClipboard(executableSql)

        val replacedCount = countReplaced(sql, executableSql)
        val message = if (sources.isEmpty()) {
            MpSqlVisualizerBundle.message("preview.copyExecutableSql.noParams")
        } else {
            MpSqlVisualizerBundle.message("preview.copyExecutableSql.done", replacedCount)
        }
        showToast(message)
        return true
    }

    /** 统计被替换的 `?` 数量（原始 SQL 中 `?` 数 - 结果中 `?` 数）。 */
    private fun countReplaced(original: String, result: String): Int {
        val originalCount = original.count { it == '?' }
        val resultCount = result.count { it == '?' }
        return (originalCount - resultCount).coerceAtLeast(0)
    }

    /** 复制文本到系统剪贴板（统一走 UiTextClipboard 收口）。 */
    private fun copyToClipboard(text: String) {
        UiTextClipboard.copy(text)
    }

    /** Toast 通知（仅在非测试模式下显示，测试模式跳过避免通知系统依赖）。 */
    private fun showToast(message: String) {
        if (com.intellij.openapi.application.ApplicationManager.getApplication().isUnitTestMode) return
        val project = project ?: return
        runCatching {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(message, NotificationType.INFORMATION)
                .notify(project)
        }
    }
}
