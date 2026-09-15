package cn.song.mybatisplus.visualizer.actions

import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.keymap.Keymap
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.awt.event.InputEvent
import javax.swing.KeyStroke

/**
 * 快捷模板快捷键冲突检测（T-PreviewUx-Pack #3，吸收 DBX sqlShortcutActions 冲突检测思路）：
 *
 * plugin.xml 静态注册三动作默认键位（Alt+Shift+E/C/T，同族 ConvertToSql=Alt+Shift+Q /
 * ConvertSqlToJava=Alt+Shift+J）；项目启动后**应用级单次**检测当前 activeKeymap 中各默认键位
 * 是否已被其他 action 占用（平台侧以 KeymapManager 实际 API 为准，PluginSettingsPanel
 * conflictActionName 同款遍历范式）。冲突即为本插件动作**追加**备选键位（Ctrl+Alt+Shift+同字母，
 * addShortcut 只加不改，不摘除任何既有绑定）并弹通知提示；改绑失败（当前 scheme 不可写等
 * 环境差异）降级为仅提示冲突，引导 Settings > Keymap 手动调整。右键菜单入口不受键位冲突影响。
 */
class SqlTemplateShortcutConflictCheck : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (alreadyChecked) return
        alreadyChecked = true
        val keymap = runCatching { KeymapManager.getInstance().activeKeymap }.getOrNull() ?: return
        // T-ShortcutSettings：仅检测「当前键位 == plugin.xml 默认值」的动作（用户已通过设置页自定义的跳过，不覆盖）
        val actionIds = listOf(
            "cn.song.mybatisplus.visualizer.ExplainSelectedTable" to DEFAULT_KEYSTROKES[0],
            "cn.song.mybatisplus.visualizer.CountSelectedTable" to DEFAULT_KEYSTROKES[1],
            "cn.song.mybatisplus.visualizer.SampleSelectedTable" to DEFAULT_KEYSTROKES[2],
        )
        val lines = actionIds.mapNotNull { (actionId, defaultKeystroke) ->
            val current = runCatching {
                keymap.getShortcuts(actionId).filterIsInstance<KeyboardShortcut>().firstOrNull()?.firstKeyStroke
            }.getOrNull()
            // 当前键位 == 默认值 → 用户未配置，走既有冲突检测；否则跳过
            if (current != null && current == defaultKeystroke) resolveConflictLine(keymap, actionId) else null
        }
        if (lines.isEmpty()) return
        if (project.isDisposed) return
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup(SelectedTableSqlTemplateAction.NOTIFICATION_GROUP_ID)
            .createNotification(
                cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("sqlTemplate.conflict.title"),
                lines.joinToString("\n"),
                com.intellij.notification.NotificationType.WARNING,
            )
            .notify(project)
    }

    companion object {

        /** 应用级单次守卫（StartupUpdateCheck alreadyChecked 同款静态）。 */
        @Volatile
        var alreadyChecked = false
            private set

        /** 三动作在 plugin.xml 注册的默认键位（Alt+Shift+E/C/T），用于判定用户是否已自定义。 */
        private val DEFAULT_KEYSTROKES = listOf(
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_E, InputEvent.ALT_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, InputEvent.ALT_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_T, InputEvent.ALT_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
        )

        /**
         * 单动作冲突处理（纯逻辑 + 受控副作用）：默认键位无冲突返回 null；有冲突则尝试追加
         * 备选键位并返回提示行文案（改绑成败在文案中如实区分）。
         */
        internal fun resolveConflictLine(keymap: Keymap, actionId: String): String? {
            val current = runCatching {
                keymap.getShortcuts(actionId).filterIsInstance<KeyboardShortcut>().firstOrNull()
            }.getOrNull() ?: return null
            val owner = conflictOwner(keymap, actionId, current) ?: return null
            val fallback = fallbackOf(current)
            val rebound = fallback != null &&
                conflictOwner(keymap, actionId, fallback) == null &&
                runCatching { keymap.addShortcut(actionId, fallback) }.isSuccess
            val displayName = runCatching {
                com.intellij.openapi.actionSystem.ActionManager.getInstance()
                    .getAction(actionId)?.templatePresentation?.text
            }.getOrNull() ?: actionId
            val keyText = current.firstKeyStroke?.toString().orEmpty()
            return if (rebound && fallback != null) {
                cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message(
                    "sqlTemplate.conflict.item.rebound",
                    displayName, keyText, owner, fallback.firstKeyStroke.toString(),
                )
            } else {
                cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message(
                    "sqlTemplate.conflict.item.manual",
                    displayName, keyText, owner,
                )
            }
        }

        /** keymap 中 [shortcut] 的占用者 actionId（排除自身）；无冲突返回 null（conflictActionName 同款遍历）。 */
        internal fun conflictOwner(keymap: Keymap, actionId: String, shortcut: Shortcut): String? = runCatching {
            keymap.actionIds
                .filter { it != actionId }
                .firstOrNull { other -> keymap.getShortcuts(other).any { it == shortcut } }
        }.getOrNull()

        /** 备选键位推导：在当前修饰符上叠加 Ctrl+Alt（Alt+Shift+E → Ctrl+Alt+Shift+E）。 */
        internal fun fallbackOf(shortcut: KeyboardShortcut): KeyboardShortcut? {
            val ks = shortcut.firstKeyStroke ?: return null
            val mods = ks.modifiers or InputEvent.CTRL_MASK or InputEvent.ALT_MASK or InputEvent.SHIFT_MASK
            return KeyboardShortcut(
                javax.swing.KeyStroke.getKeyStroke(ks.keyCode, mods),
                shortcut.secondKeyStroke,
            )
        }
    }
}
